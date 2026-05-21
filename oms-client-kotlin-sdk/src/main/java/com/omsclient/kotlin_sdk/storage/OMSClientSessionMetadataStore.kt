package com.omsclient.kotlin_sdk.storage

import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot

/**
 * Persists only completed-session metadata needed to restore wallet state.
 */
internal interface OMSClientSessionMetadataStore {
    fun load(): OMSClientSessionSnapshot?

    fun save(snapshot: OMSClientSessionSnapshot)

    fun clear()
}
