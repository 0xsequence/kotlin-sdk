package com.omswallet.kotlin_sdk.storage

import com.omswallet.kotlin_sdk.session.OmsWalletSessionSnapshot

internal interface OmsWalletSecureSessionStore {
    fun load(): OmsWalletSessionSnapshot?

    fun save(snapshot: OmsWalletSessionSnapshot, privateKey: ByteArray? = null)

    suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T

    fun clear()
}
