package com.omsclient.kotlin_sdk.models

typealias TransactionMode = com.omsclient.kotlin_sdk.generated.waas.TransactionMode
typealias TransactionStatus = com.omsclient.kotlin_sdk.generated.waas.TransactionStatus
typealias FeeOption = com.omsclient.kotlin_sdk.generated.waas.FeeOption
typealias FeeOptionSelection = com.omsclient.kotlin_sdk.generated.waas.FeeOptionSelection
typealias FeeOptionSelector = suspend (List<FeeOptionWithBalance>) -> FeeOptionSelection?

data class FeeOptionWithBalance(
    val feeOption: FeeOption,
    val balance: TokenBalance?,
    val available: String?,
    val availableRaw: String?,
    val decimals: UInt?,
)

data class SendTransactionRequest(
    val to: String,
    val value: String,
    val data: String? = null,
    val mode: TransactionMode = TransactionMode.Relayer,
)

data class SendTransactionResponse(
    val txnId: String,
    val status: TransactionStatus,
    val txHash: String?,
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
