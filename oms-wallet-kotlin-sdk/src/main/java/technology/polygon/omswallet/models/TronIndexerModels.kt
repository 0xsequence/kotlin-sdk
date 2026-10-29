package technology.polygon.omswallet.models

import technology.polygon.omswallet.TronNetwork

/** Verification state assigned to Tron asset metadata. */
enum class TronVerificationStatus {
    Verified,
    Unverified,
    Unknown,
}

/** Token standard of a Tron fungible token. TRC-10 tokens are not supported. */
enum class TronTokenStandard {
    Trc20,
}

/** Common public fields returned for a Tron balance. */
sealed interface TronBalance {
    val network: TronNetwork

    /** Base58Check account address (`T…`). */
    val accountAddress: String
    val name: String
    val symbol: String
    val decimals: Int

    /** Raw balance in the token's base units (sun for TRX). */
    val balance: String
    val formattedBalance: String
    val imageUrl: String?
    val metadataUri: String?
    val verificationStatus: TronVerificationStatus

    /** Source reported by the gateway for [verificationStatus], such as `none`. */
    val verificationSource: String
    val priceUSD: String?
    val balanceUSD: String?

    /** Native TRX balance. */
    data class Native(
        override val network: TronNetwork,
        override val accountAddress: String,
        override val name: String,
        override val symbol: String,
        override val decimals: Int,
        override val balance: String,
        override val formattedBalance: String,
        override val imageUrl: String?,
        override val metadataUri: String?,
        override val verificationStatus: TronVerificationStatus,
        override val verificationSource: String,
        override val priceUSD: String?,
        override val balanceUSD: String?,
    ) : TronBalance

    /** TRC-20 token balance. */
    data class FungibleToken(
        override val network: TronNetwork,
        override val accountAddress: String,
        val tokenStandard: TronTokenStandard,
        /** Base58Check TRC-20 contract address (`T…`). */
        val contractAddress: String,
        override val name: String,
        override val symbol: String,
        override val decimals: Int,
        override val balance: String,
        override val formattedBalance: String,
        override val imageUrl: String?,
        override val metadataUri: String?,
        override val verificationStatus: TronVerificationStatus,
        override val verificationSource: String,
        override val priceUSD: String?,
        override val balanceUSD: String?,
    ) : TronBalance
}

/** Per-network failure returned alongside partial Tron balance results. */
data class TronNetworkError(
    val network: TronNetwork,
    val reason: String,
)

/** Tron balances and partial network errors returned by the gateway. */
data class TronBalancesResult(
    val status: Int,
    val balances: List<TronBalance>,
    val errors: List<TronNetworkError>,
)
