package com.polygon_wallet.polygon_kotlin_sdk.storage

internal interface SequencePrivateKeyStore {
    fun savePrivateKey(privateKey: ByteArray)

    suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T

    fun clearPrivateKey()
}
