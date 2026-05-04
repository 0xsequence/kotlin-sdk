package com.omsclient.kotlin_sdk.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.omsclient.kotlin_sdk.generated.waas.KeyType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

internal class AndroidKeystoreP256CredentialSigner(
    context: Context,
    private val alias: String = DEFAULT_KEY_ALIAS,
    nonceStoreName: String = DEFAULT_NONCE_STORE_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialSigner {
    override val keyType: KeyType = KeyType.WebCrypto_Secp256r1

    private val appContext = context.applicationContext
    private val noncePreferences = appContext.getSharedPreferences(nonceStoreName, Context.MODE_PRIVATE)
    private val nonceLock = nonceLockFor(nonceStoreName, alias)

    override suspend fun credentialId(): String =
        withContext(ioDispatcher) {
            credentialId(getOrCreateKeyPair().public)
        }

    override suspend fun nextNonce(): String =
        withContext(ioDispatcher) {
            synchronized(nonceLock) {
                val previous = noncePreferences.getString(alias, null)?.toLongOrNull() ?: 0L
                val next = maxOf(System.currentTimeMillis(), previous + 1)
                check(noncePreferences.edit().putString(alias, next.toString()).commit()) {
                    "Unable to persist OMS Client credential nonce"
                }
                next.toString()
            }
        }

    override suspend fun sign(preimage: String): String =
        withContext(ioDispatcher) {
            val signature = Signature.getInstance(SHA256_WITH_ECDSA)
            signature.initSign(requirePrivateKey())
            signature.update(preimage.toByteArray(Charsets.UTF_8))
            Numeric.toHexString(P256EcdsaSignatureEncoding.derToRaw(signature.sign()))
        }

    override fun hasCredential(): Boolean = keyStore().containsAlias(alias)

    override fun clear() {
        val store = keyStore()
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
        }
        synchronized(nonceLock) {
            noncePreferences.edit().remove(alias).apply()
        }
    }

    private fun requirePrivateKey(): PrivateKey =
        requireNotNull(keyStore().getKey(alias, null) as? PrivateKey) {
            "No active OMS Client signing credential"
        }

    private fun getOrCreateKeyPair(): KeyPair {
        val store = keyStore()
        val privateKey = store.getKey(alias, null) as? PrivateKey
        val publicKey = store.getCertificate(alias)?.publicKey
        if (privateKey != null && publicKey != null) {
            return KeyPair(publicKey, privateKey)
        }

        val keyPairGenerator =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
        val spec =
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setAlgorithmParameterSpec(ECGenParameterSpec(SECP256R1))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair()
    }

    private fun credentialId(publicKey: PublicKey): String {
        val ecPublicKey = publicKey as ECPublicKey
        val point = ecPublicKey.w
        return "0x04" +
            point.affineX.toFixedHex(P256_FIELD_SIZE_BYTES) +
            point.affineY.toFixedHex(P256_FIELD_SIZE_BYTES)
    }

    private fun BigInteger.toFixedHex(size: Int): String {
        val raw = toByteArray()
        val unsigned =
            if (raw.size > 1 && raw[0] == 0.toByte()) {
                raw.copyOfRange(1, raw.size)
            } else {
                raw
            }
        require(unsigned.size <= size) { "Invalid P-256 public key coordinate" }
        return Numeric.toHexStringNoPrefix(ByteArray(size - unsigned.size) + unsigned)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private data class NonceLockKey(
            val storeName: String,
            val alias: String,
        )

        private val nonceLocks = ConcurrentHashMap<NonceLockKey, Any>()

        private fun nonceLockFor(
            storeName: String,
            alias: String,
        ): Any = nonceLocks.computeIfAbsent(NonceLockKey(storeName, alias)) { Any() }

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SECP256R1 = "secp256r1"
        private const val SHA256_WITH_ECDSA = "SHA256withECDSA"
        private const val P256_FIELD_SIZE_BYTES = 32
        private const val DEFAULT_KEY_ALIAS = "oms-client-webcrypto-p256"
        private const val DEFAULT_NONCE_STORE_NAME = "oms-client-credential-nonces"
    }
}
