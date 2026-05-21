package com.omsclient.kotlin_sdk.storage

import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot

internal interface OMSClientSessionMetadataStore {
    fun load(): OMSClientSessionSnapshot?

    fun save(snapshot: OMSClientSessionSnapshot)

    fun clear()
}
