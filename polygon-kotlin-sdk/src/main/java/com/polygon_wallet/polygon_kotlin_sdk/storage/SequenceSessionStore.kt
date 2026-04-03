package com.polygon_wallet.polygon_kotlin_sdk.storage

import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceSessionSnapshot

interface SequenceSessionStore {
    fun load(): SequenceSessionSnapshot?

    fun save(snapshot: SequenceSessionSnapshot)

    fun clear()
}
