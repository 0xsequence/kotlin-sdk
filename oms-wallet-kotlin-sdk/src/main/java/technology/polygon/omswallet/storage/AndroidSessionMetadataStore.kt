package technology.polygon.omswallet.storage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm
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
) : OMSWalletSessionMetadataStore {
    private val sessionFile = File(context.noBackupFilesDir, fileName)

    override fun load(): OMSWalletSessionSnapshot? {
        return runCatching {
            if (!sessionFile.exists()) {
                return null
            }

            val persisted = PersistedSessionEnvelope.fromJson(sessionFile.readText())
            if (!persisted.isRestorable()) {
                return null
            }
            OMSWalletSessionSnapshot(
                walletId = persisted.walletId,
                walletAddress = persisted.walletAddress,
                signerAddress = persisted.signerAddress,
                signerKeyType = persisted.signerKeyType,
                expiresAt = persisted.expiresAt,
                auth = persisted.auth,
            )
        }.getOrNull()
    }

    override fun save(snapshot: OMSWalletSessionSnapshot) {
        require(!snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank()) {
            "Cannot persist pending OMS Wallet auth state"
        }
        require(snapshot.auth != null) {
            "Cannot persist OMS Wallet session without auth metadata"
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
        writeTextAtomically(sessionFile, persisted.toJson())
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
        val auth: OMSWalletSessionAuth? = null,
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
        private const val DEFAULT_FILE_NAME = "oms-wallet-session.json"
    }
}

private fun writeTextAtomically(
    file: File,
    value: String,
) {
    val atomicFile = AtomicFile(file)
    val output = atomicFile.startWrite()
    try {
        output.write(value.toByteArray(Charsets.UTF_8))
        atomicFile.finishWrite(output)
    } catch (throwable: Throwable) {
        atomicFile.failWrite(output)
        throw throwable
    }
}

private fun OMSWalletSessionAuth.toJson(): JSONObject =
    when (this) {
        is OMSWalletEmailSessionAuth -> {
            JSONObject()
                .put("type", "email")
                .put("email", email)
        }

        is OMSWalletOidcSessionAuth -> {
            JSONObject()
                .put("type", "oidc")
                .put("flow", flow.wireValue)
                .put("issuer", issuer)
                .put("provider", provider)
                .put("providerLabel", providerLabel)
                .put("email", email)
        }
    }

private fun JSONObject.toSessionAuth(): OMSWalletSessionAuth? =
    when (optionalString("type")) {
        "email" -> {
            OMSWalletEmailSessionAuth(email = optionalString("email"))
        }

        "oidc" -> {
            val issuer = optionalString("issuer") ?: return null
            val flow = optionalString("flow")?.toSessionAuthFlow() ?: return null
            OMSWalletOidcSessionAuth(
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

private val OMSWalletOidcSessionAuthFlow.wireValue: String
    get() =
        when (this) {
            OMSWalletOidcSessionAuthFlow.Redirect -> "redirect"
            OMSWalletOidcSessionAuthFlow.IdToken -> "id-token"
        }

private fun String.toSessionAuthFlow(): OMSWalletOidcSessionAuthFlow? =
    when (this) {
        "redirect" -> OMSWalletOidcSessionAuthFlow.Redirect
        "id-token" -> OMSWalletOidcSessionAuthFlow.IdToken
        else -> null
    }
