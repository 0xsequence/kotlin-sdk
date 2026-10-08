package technology.polygon.omswallet.storage

import android.content.Context
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import java.io.File
import java.io.IOException

/**
 * Stores completed wallet-session metadata in an app-private no-backup file.
 *
 * This store does not create, store, or sign with wallet credentials. Wallet
 * request authorization is handled by the Android Keystore credential signer.
 * The record format is owned by [PersistedSessionRecord].
 */
internal class AndroidSessionMetadataStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : OMSWalletSessionMetadataStore {
    private val sessionFile = File(context.noBackupFilesDir, fileName)

    override fun load(): OMSWalletSessionSnapshot? {
        if (!sessionFile.exists()) {
            return null
        }
        return PersistedSessionRecord.decode(sessionFile.readText())
    }

    override fun save(snapshot: OMSWalletSessionSnapshot) {
        val record = PersistedSessionRecord.encode(snapshot)
        sessionFile.parentFile?.mkdirs()
        writeTextAtomically(sessionFile, record)
    }

    override fun clear() {
        if (sessionFile.exists() && !sessionFile.delete()) {
            throw IOException("Unable to delete OMS Wallet session metadata")
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "oms-wallet-session.json"
    }
}
