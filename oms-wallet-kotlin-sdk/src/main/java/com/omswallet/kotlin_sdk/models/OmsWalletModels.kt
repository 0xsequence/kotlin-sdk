package com.omswallet.kotlin_sdk.models

typealias TransactionMode = com.omswallet.kotlin_sdk.generated.waas.TransactionMode

data class SendTransactionRequest(
    val to: String,
    val value: String,
    val data: String? = null,
    val mode: TransactionMode = TransactionMode.Relayer,
    val feeCeiling: String? = null,
    val nonce: String? = null,
)

data class VerifySignatureResult(
    val status: Int,
    val isValid: Boolean,
)

data class TokenBalancesPage(
    val page: Int,
    val pageSize: Int,
    val more: Boolean,
)

data class TokenBalance(
    val contractType: String?,
    val contractAddress: String?,
    val accountAddress: String?,
    val tokenId: String?,
    val balance: String?,
    val blockHash: String?,
    val blockNumber: Long?,
    val chainId: Long?,
)

data class TokenBalancesResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val balances: List<TokenBalance>,
)
