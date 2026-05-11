package com.omsclient.kotlin_sdk.storage

import android.content.Context
import com.omsclient.kotlin_sdk.generated.waas.WebRpcJson
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.PendingOidcRedirectAuth
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

internal class AndroidOidcRedirectAuthStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : OidcRedirectAuthStore {
    private val pendingFile = File(context.noBackupFilesDir, fileName)

    override fun load(): PendingOidcRedirectAuth? = runCatching {
        if (!pendingFile.exists()) {
            return null
        }
        WebRpcJson.decodeFromString<PendingOidcRedirectAuth>(pendingFile.readText())
    }.getOrNull()

    override fun save(pending: PendingOidcRedirectAuth) {
        pendingFile.parentFile?.mkdirs()
        pendingFile.writeText(WebRpcJson.encodeToString(pending))
    }

    override fun clear() {
        if (pendingFile.exists()) {
            pendingFile.delete()
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "oms-client-oidc-redirect-auth.json"
    }
}
