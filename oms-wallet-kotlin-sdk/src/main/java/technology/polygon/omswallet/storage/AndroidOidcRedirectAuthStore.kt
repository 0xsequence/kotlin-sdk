package technology.polygon.omswallet.storage

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import technology.polygon.omswallet.internal.generated.waas.WebRpcJson
import technology.polygon.omswallet.wallet.OidcRedirectAuthStore
import technology.polygon.omswallet.wallet.PendingOidcRedirectAuth
import java.io.File

internal class AndroidOidcRedirectAuthStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : OidcRedirectAuthStore {
    private val pendingFile = File(context.noBackupFilesDir, fileName)

    override fun load(): PendingOidcRedirectAuth? =
        runCatching {
            if (!pendingFile.exists()) {
                return null
            }
            WebRpcJson.decodeFromString<PendingOidcRedirectAuth>(pendingFile.readText())
        }.getOrNull()

    override fun save(pending: PendingOidcRedirectAuth) {
        pendingFile.parentFile?.mkdirs()
        writeTextAtomically(pendingFile, WebRpcJson.encodeToString(pending))
    }

    override fun clear() {
        if (pendingFile.exists()) {
            pendingFile.delete()
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "oms-wallet-oidc-redirect-auth.json"
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
