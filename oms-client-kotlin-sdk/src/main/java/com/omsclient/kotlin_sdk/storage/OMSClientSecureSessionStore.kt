package com.omsclient.kotlin_sdk.storage

import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot

internal interface OMSClientSecureSessionStore {
    fun load(): OMSClientSessionSnapshot?

    fun save(snapshot: OMSClientSessionSnapshot)

    fun clear()
}
