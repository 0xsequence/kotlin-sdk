package com.omsclient.kotlin_sdk.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystoreSessionStore(
    context: Context,
    private val alias: String = DEFAULT_KEY_ALIAS,
    private val fileName: String = DEFAULT_FILE_NAME,
) : OMSClientSecureSessionStore {
    private val sessionFile = File(context.noBackupFilesDir, fileName)

    override fun load(): OMSClientSessionSnapshot? {
        return runCatching {
            if (!sessionFile.exists()) {
                return null
            }

            val persisted = PersistedSessionEnvelope.fromJson(sessionFile.readText())
            if (!persisted.isRestorable()) {
                return null
            }
            OMSClientSessionSnapshot(
                walletId = persisted.walletId,
                walletAddress = persisted.walletAddress,
                signerAddress = persisted.signerAddress,
            )
        }.getOrNull()
    }

    override fun save(snapshot: OMSClientSessionSnapshot, privateKey: ByteArray?) {
        require(!snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank()) {
            "Cannot persist pending OMS Client auth state"
        }
        sessionFile.parentFile?.mkdirs()
        val existing = readPersistedEnvelope()
        val encryptedPrivateKey = when {
            privateKey != null -> encryptPrivateKey(privateKey)
            existing?.hasEncryptedPrivateKey() == true -> EncryptedPrivateKey(
                ciphertext = requireNotNull(existing.encryptedPrivateKey),
                iv = requireNotNull(existing.iv),
            )
            else -> throw IllegalStateException("Cannot persist OMS Client session metadata without a private key")
        }
        val persisted = PersistedSessionEnvelope(
            encryptedPrivateKey = encryptedPrivateKey.ciphertext,
            iv = encryptedPrivateKey.iv,
            walletId = snapshot.walletId,
            walletAddress = snapshot.walletAddress,
            signerAddress = snapshot.signerAddress,
        )
        sessionFile.writeText(persisted.toJson())
    }

    override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T {
        val persisted = requireNotNull(readPersistedEnvelope()) { "No persisted OMS Client session" }
        val decrypted = decryptPrivateKey(persisted)
        return try {
            block(decrypted)
        } finally {
            decrypted.fill(0)
        }
    }

    override fun clear() {
        if (sessionFile.exists()) {
            sessionFile.delete()
        }
    }

    private fun encryptPrivateKey(privateKey: ByteArray): EncryptedPrivateKey {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val ciphertext = cipher.doFinal(privateKey)
        return EncryptedPrivateKey(
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            iv = Base64.getEncoder().encodeToString(cipher.iv),
        )
    }

    private fun decryptPrivateKey(persisted: PersistedSessionEnvelope): ByteArray {
        val iv = requireNotNull(persisted.iv) { "Persisted session missing encryption IV" }
        val encryptedPrivateKey = requireNotNull(persisted.encryptedPrivateKey) {
            "Persisted session missing encrypted private key"
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(iv)),
            )
        }
        return cipher.doFinal(Base64.getDecoder().decode(encryptedPrivateKey))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(alias, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val keySpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun readPersistedEnvelope(): PersistedSessionEnvelope? =
        if (sessionFile.exists()) PersistedSessionEnvelope.fromJson(sessionFile.readText()) else null

    private data class PersistedSessionEnvelope(
        val encryptedPrivateKey: String? = null,
        val iv: String? = null,
        val challenge: String? = null,
        val verifier: String? = null,
        val walletId: String? = null,
        val walletAddress: String? = null,
        val signerAddress: String? = null,
    ) {
        fun hasEncryptedPrivateKey(): Boolean =
            !encryptedPrivateKey.isNullOrBlank() && !iv.isNullOrBlank()

        fun isRestorable(): Boolean =
            hasEncryptedPrivateKey() && !walletId.isNullOrBlank() && !walletAddress.isNullOrBlank()

        fun toJson(): String = JSONObject().apply {
            put("encryptedPrivateKey", encryptedPrivateKey)
            put("iv", iv)
            put("challenge", challenge)
            put("verifier", verifier)
            put("walletId", walletId)
            put("walletAddress", walletAddress)
            put("signerAddress", signerAddress)
        }.toString()

        companion object {
            fun fromJson(source: String): PersistedSessionEnvelope {
                val jsonObject = JSONObject(source)
                return PersistedSessionEnvelope(
                    encryptedPrivateKey = jsonObject.optString("encryptedPrivateKey").ifBlank { null },
                    iv = jsonObject.optString("iv").ifBlank { null },
                    challenge = jsonObject.optString("challenge").ifBlank { null },
                    verifier = jsonObject.optString("verifier").ifBlank { null },
                    walletId = jsonObject.optString("walletId").ifBlank { null },
                    walletAddress = jsonObject.optString("walletAddress").ifBlank { null },
                    signerAddress = jsonObject.optString("signerAddress").ifBlank { null },
                )
            }
        }
    }

    private data class EncryptedPrivateKey(
        val ciphertext: String,
        val iv: String,
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val DEFAULT_KEY_ALIAS = "oms-client-session-key"
        private const val DEFAULT_FILE_NAME = "oms-client-session.json"
    }
}
