package com.polygon_wallet.polygon_kotlin_sdk.storage

import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceSessionSnapshot

internal interface SequenceSecureSessionStore {
    fun load(): SequenceSessionSnapshot?

    fun save(snapshot: SequenceSessionSnapshot, privateKey: ByteArray? = null)

    suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T

    fun clear()
}
