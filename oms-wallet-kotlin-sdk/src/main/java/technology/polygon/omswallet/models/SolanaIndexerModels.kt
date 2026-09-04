package technology.polygon.omswallet.models

import technology.polygon.omswallet.SolanaNetwork

/** Verification state assigned to Solana asset metadata. */
enum class SolanaVerificationStatus {
    Verified,
    Unverified,
    Unknown,
}

/** Source used to verify Solana asset metadata. */
enum class SolanaVerificationSource {
    Jupiter,
    SolflareUtl,
    None,
}

/** Token program owning a Solana mint. */
enum class SolanaTokenProgram {
    SplToken,
    Token2022,
}

/** Common public fields returned for a Solana balance. */
sealed interface SolanaBalance {
    val network: SolanaNetwork
    val accountAddress: String
    val name: String
    val symbol: String
    val decimals: Int
    val balance: String
    val formattedBalance: String
    val imageUrl: String?
    val metadataUri: String?
    val verificationStatus: SolanaVerificationStatus
    val verificationSource: SolanaVerificationSource
    val priceUSD: String?
    val balanceUSD: String?

    /** Native SOL balance. */
    data class Native(
        override val network: SolanaNetwork,
        override val accountAddress: String,
        override val name: String,
        override val symbol: String,
        override val decimals: Int,
        override val balance: String,
        override val formattedBalance: String,
        override val imageUrl: String?,
        override val metadataUri: String?,
        override val verificationStatus: SolanaVerificationStatus,
        override val verificationSource: SolanaVerificationSource,
        override val priceUSD: String?,
        override val balanceUSD: String?,
    ) : SolanaBalance

    /** SPL Token or Token-2022 balance. */
    data class FungibleToken(
        override val network: SolanaNetwork,
        override val accountAddress: String,
        val tokenProgram: SolanaTokenProgram,
        val mintAddress: String,
        override val name: String,
        override val symbol: String,
        override val decimals: Int,
        override val balance: String,
        override val formattedBalance: String,
        override val imageUrl: String?,
        override val metadataUri: String?,
        override val verificationStatus: SolanaVerificationStatus,
        override val verificationSource: SolanaVerificationSource,
        override val priceUSD: String?,
        override val balanceUSD: String?,
    ) : SolanaBalance
}

/** Per-network failure returned alongside partial Solana balance results. */
data class SolanaNetworkError(
    val network: SolanaNetwork,
    val reason: String,
)

/** Solana balances and partial network errors returned by the gateway. */
data class SolanaBalancesResult(
    val status: Int,
    val balances: List<SolanaBalance>,
    val errors: List<SolanaNetworkError>,
)
