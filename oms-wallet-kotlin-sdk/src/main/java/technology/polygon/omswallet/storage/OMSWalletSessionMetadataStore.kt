package technology.polygon.omswallet.storage

import technology.polygon.omswallet.session.OMSWalletSessionSnapshot

/**
 * Persists only completed-session metadata needed to restore wallet state.
 */
internal interface OMSWalletSessionMetadataStore {
    fun load(): OMSWalletSessionSnapshot?

    fun save(snapshot: OMSWalletSessionSnapshot)

    fun clear()
}

internal class InvalidSessionMetadataException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
