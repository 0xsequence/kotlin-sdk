package com.omsclient.kotlin_sdk.storage

import android.content.Context
import com.omsclient.kotlin_sdk.OMSClientEmailSessionAuth
import com.omsclient.kotlin_sdk.OMSClientOidcSessionAuth
import com.omsclient.kotlin_sdk.OMSClientOidcSessionAuthFlow
import com.omsclient.kotlin_sdk.OMSClientSessionAuth
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
                auth = persisted.auth,
            )
        }.getOrNull()
    }

    override fun save(snapshot: OMSClientSessionSnapshot) {
        require(!snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank()) {
            "Cannot persist pending OMS Client auth state"
        }
        require(snapshot.auth != null) {
            "Cannot persist OMS Client session without auth metadata"
        }
        sessionFile.parentFile?.mkdirs()
        val persisted =
            PersistedSessionEnvelope(
                walletId = snapshot.walletId,
                walletAddress = snapshot.walletAddress,
                signerAddress = snapshot.signerAddress,
                signerKeyType = snapshot.signerKeyType,
                expiresAt = snapshot.expiresAt,
                auth = snapshot.auth,
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
        val auth: OMSClientSessionAuth? = null,
    ) {
        fun isRestorable(): Boolean =
            !walletId.isNullOrBlank() &&
                !walletAddress.isNullOrBlank() &&
                !signerAddress.isNullOrBlank() &&
                signerKeyType == WalletSigningAlgorithm.ECDSA_P256_SHA256 &&
                auth != null

        fun toJson(): String =
            JSONObject()
                .apply {
                    put("walletId", walletId)
                    put("walletAddress", walletAddress)
                    put("signerAddress", signerAddress)
                    put("signerKeyType", signerKeyType?.wireValue)
                    put("expiresAt", expiresAt)
                    put("auth", auth?.toJson())
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
                            ?.let(WalletSigningAlgorithm::fromWireValue)
                            ?.takeIf { it != WalletSigningAlgorithm.UNKNOWN_DEFAULT },
                    expiresAt = jsonObject.optString("expiresAt").ifBlank { null },
                    auth = jsonObject.optJSONObject("auth")?.toSessionAuth(),
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "oms-client-session.json"
    }
}

private fun OMSClientSessionAuth.toJson(): JSONObject =
    when (this) {
        is OMSClientEmailSessionAuth -> {
            JSONObject()
                .put("type", "email")
                .put("email", email)
        }

        is OMSClientOidcSessionAuth -> {
            JSONObject()
                .put("type", "oidc")
                .put("flow", flow.wireValue)
                .put("issuer", issuer)
                .put("provider", provider)
                .put("providerLabel", providerLabel)
                .put("email", email)
        }
    }

private fun JSONObject.toSessionAuth(): OMSClientSessionAuth? =
    when (optionalString("type")) {
        "email" -> {
            OMSClientEmailSessionAuth(email = optionalString("email"))
        }

        "oidc" -> {
            val issuer = optionalString("issuer") ?: return null
            val flow = optionalString("flow")?.toSessionAuthFlow() ?: return null
            OMSClientOidcSessionAuth(
                flow = flow,
                issuer = issuer,
                provider = optionalString("provider"),
                providerLabel = optionalString("providerLabel"),
                email = optionalString("email"),
            )
        }

        else -> {
            null
        }
    }

private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) {
        null
    } else {
        optString(key).ifBlank { null }
    }

private val OMSClientOidcSessionAuthFlow.wireValue: String
    get() =
        when (this) {
            OMSClientOidcSessionAuthFlow.Redirect -> "redirect"
            OMSClientOidcSessionAuthFlow.IdToken -> "id-token"
        }

private fun String.toSessionAuthFlow(): OMSClientOidcSessionAuthFlow? =
    when (this) {
        "redirect" -> OMSClientOidcSessionAuthFlow.Redirect
        "id-token" -> OMSClientOidcSessionAuthFlow.IdToken
        else -> null
    }
