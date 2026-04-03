package com.polygon_wallet.polygon_kotlin_sdk.models

data class CommitVerifierResponse(
    val verifier: String?,
    val loginHint: String?,
    val challenge: String?,
)

data class SequenceIdentity(
    val type: String?,
    val sub: String?,
    val email: String?,
)

data class SequenceWallet(
    val type: String?,
    val address: String?,
    val index: Int?,
    val comment: String?,
)

data class CompleteAuthResponse(
    val identity: SequenceIdentity?,
    val wallets: List<SequenceWallet>,
)

data class SignMessageResult(
    val signature: String,
)

data class SendTransactionResult(
    val txHash: String,
)

data class IsValidMessageSignatureResult(
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
