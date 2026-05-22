package com.omsclient.kotlin_sdk.storage

import android.content.Context
import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.wallet.WalletSigningAlgorithm
import org.json.JSONObject
import java.io.File

/**
 * Stores completed wallet-session metadata in an app-private no-backup file.
 *
 * This store does not create, store, or sign with wallet credentials. Wallet
 * request authorization is handled by the Android Keystore credential signer.
 */
internal class AndroidSessionMetadataStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : OMSClientSessionMetadataStore {
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
                signerKeyType = persisted.signerKeyType,
                expiresAt = persisted.expiresAt,
                loginType = persisted.loginType,
                sessionEmail = persisted.sessionEmail,
            )
        }.getOrNull()
    }

    override fun save(snapshot: OMSClientSessionSnapshot) {
        require(!snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank()) {
            "Cannot persist pending OMS Client auth state"
        }
        sessionFile.parentFile?.mkdirs()
        val persisted =
            PersistedSessionEnvelope(
                walletId = snapshot.walletId,
                walletAddress = snapshot.walletAddress,
                signerAddress = snapshot.signerAddress,
                signerKeyType = snapshot.signerKeyType,
                expiresAt = snapshot.expiresAt,
                loginType = snapshot.loginType,
                sessionEmail = snapshot.sessionEmail,
            )
        sessionFile.writeText(persisted.toJson())
    }

    override fun clear() {
        if (sessionFile.exists()) {
            sessionFile.delete()
        }
    }

    private data class PersistedSessionEnvelope(
        val walletId: String? = null,
        val walletAddress: String? = null,
        val signerAddress: String? = null,
        val signerKeyType: WalletSigningAlgorithm? = null,
        val expiresAt: String? = null,
        val loginType: OMSClientSessionLoginType? = null,
        val sessionEmail: String? = null,
    ) {
        fun isRestorable(): Boolean = !walletId.isNullOrBlank() && !walletAddress.isNullOrBlank()

        fun toJson(): String =
            JSONObject()
                .apply {
                    put("walletId", walletId)
                    put("walletAddress", walletAddress)
                    put("signerAddress", signerAddress)
                    put("signerKeyType", signerKeyType?.wireValue)
                    put("expiresAt", expiresAt)
                    put("loginType", loginType?.name)
                    put("sessionEmail", sessionEmail)
                }.toString()

        companion object {
            fun fromJson(source: String): PersistedSessionEnvelope {
                val jsonObject = JSONObject(source)
                return PersistedSessionEnvelope(
                    walletId = jsonObject.optString("walletId").ifBlank { null },
                    walletAddress = jsonObject.optString("walletAddress").ifBlank { null },
                    signerAddress = jsonObject.optString("signerAddress").ifBlank { null },
                    signerKeyType =
                        jsonObject
                            .optString("signerKeyType")
                            .ifBlank { null }
                            ?.let(::signingAlgorithmFromPersistedWireValue)
                            ?.takeIf { it != WalletSigningAlgorithm.UNKNOWN_DEFAULT },
                    expiresAt = jsonObject.optString("expiresAt").ifBlank { null },
                    loginType =
                        jsonObject
                            .optString("loginType")
                            .ifBlank { null }
                            ?.let { value ->
                                runCatching {
                                    OMSClientSessionLoginType.valueOf(value)
                                }.getOrNull()
                            },
                    sessionEmail = jsonObject.optString("sessionEmail").ifBlank { null },
                )
            }

            private fun signingAlgorithmFromPersistedWireValue(value: String): WalletSigningAlgorithm =
                when (value) {
                    "ethereum-secp256k1" -> WalletSigningAlgorithm.ECDSA_P256K_EIP191
                    "webcrypto-secp256r1" -> WalletSigningAlgorithm.ECDSA_P256_SHA256
                    else -> WalletSigningAlgorithm.fromWireValue(value)
                }
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "oms-client-session.json"
    }
}
