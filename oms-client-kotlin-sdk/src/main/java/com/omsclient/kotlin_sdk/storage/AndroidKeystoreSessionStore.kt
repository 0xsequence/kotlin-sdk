package com.omsclient.kotlin_sdk.storage

import android.content.Context
import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.generated.waas.KeyType
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import org.json.JSONObject
import java.io.File

internal class AndroidKeystoreSessionStore(
    context: Context,
    @Suppress("UNUSED_PARAMETER") alias: String = DEFAULT_KEY_ALIAS,
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
        val signerKeyType: KeyType? = null,
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
                            ?.let(KeyType::fromWireValue)
                            ?.takeIf { it != KeyType.UNKNOWN_DEFAULT },
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
        }
    }

    companion object {
        private const val DEFAULT_KEY_ALIAS = "oms-client-session-key"
        private const val DEFAULT_FILE_NAME = "oms-client-session.json"
    }
}
