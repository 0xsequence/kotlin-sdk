package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.KeyType

internal interface CredentialSigner {
    val keyType: KeyType

    suspend fun credentialId(): String

    suspend fun nextNonce(): String

    suspend fun sign(preimage: String): String

    fun hasCredential(): Boolean

    fun clear()
}
