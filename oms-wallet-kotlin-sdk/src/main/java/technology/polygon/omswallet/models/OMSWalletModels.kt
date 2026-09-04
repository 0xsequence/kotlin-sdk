package technology.polygon.omswallet.models

import kotlinx.serialization.json.JsonElement
import java.math.BigInteger

enum class WalletType(
    val wireValue: String,
) {
    Ethereum("ethereum"),
    Solana("solana"),
    UNKNOWN_DEFAULT("UNKNOWN_DEFAULT"),
}

/** Whether a wallet key was created in WaaS custody or imported by its owner. */
enum class WalletKeyOrigin(
    val wireValue: String,
) {
    Enclave("enclave"),
    Imported("imported"),
    UNKNOWN_DEFAULT("UNKNOWN_DEFAULT"),
}

enum class TransactionMode(
    val wireValue: String,
) {
    Native("native"),
    Relayer("relayer"),
    UNKNOWN_DEFAULT("UNKNOWN_DEFAULT"),
}

enum class TransactionStatus(
    val wireValue: String,
) {
    Quoted("quoted"),
    Pending("pending"),
    Executed("executed"),
    Failed("failed"),
    UNKNOWN_DEFAULT("UNKNOWN_DEFAULT"),
}

data class Wallet(
    val id: String,
    val type: WalletType,
    val address: String,
    val reference: String? = null,
    val keyOrigin: WalletKeyOrigin,
)

data class FeeToken(
    val network: String,
    val name: String,
    val symbol: String,
    val type: String,
    val decimals: UInt? = null,
    val logoUrl: String? = null,
    val contractAddress: String? = null,
    val tokenId: String? = null,
)

data class FeeOption(
    val token: FeeToken,
    val value: String,
    val displayValue: String,
)

data class FeeOptionSelection(
    val token: String,
    val index: UInt? = null,
) {
    constructor(feeOption: FeeOption, index: UInt? = null) : this(
        token = feeOption.selectionToken(),
        index = index,
    )
}

data class Page(
    val limit: UInt? = null,
    val cursor: String? = null,
)

data class AbiArg(
    val type: String,
    val value: JsonElement,
)

/** A credential currently authorized to use the selected wallet. */
data class WalletCredential(
    val credentialId: String,
    val expiresAt: String,
    val isCaller: Boolean,
)

/** Display metadata supplied by a remote application credential. */
data class RemoteCredentialMetadata(
    val appUrl: String,
    val appName: String,
    val appLogoUrl: String,
    val custom: Map<String, String>,
)

/** Owner-approved EVM operation allowed during a bounded smart session. */
sealed interface SmartSessionGrant {
    data class NativeTransfer(
        val to: String,
        val limit: BigInteger,
    ) : SmartSessionGrant

    data class Erc20Transfer(
        val token: String,
        val to: String? = null,
        val limit: BigInteger,
        val cumulative: Boolean? = null,
    ) : SmartSessionGrant
}

/** Filter for direct or remotely authorized wallet access. */
enum class AccessGrantType {
    Direct,
    Remote,
}

/** Direct or remote credential access associated with a wallet. */
sealed interface AccessGrant {
    val credential: WalletCredential

    data class Direct(
        override val credential: WalletCredential,
    ) : AccessGrant

    data class Remote(
        override val credential: WalletCredential,
        val sessionId: String,
        val metadata: RemoteCredentialMetadata,
        val grants: List<SmartSessionGrant>,
    ) : AccessGrant
}

/** One page of wallet access grants and its continuation cursor. */
data class AccessGrantPage(
    val grants: List<AccessGrant>,
    val page: Page? = null,
)

/** Identifiers returned after an owner authorizes a remote smart session. */
data class AuthorizedRemoteAccess(
    val walletId: String,
    val sessionId: String,
    val expiresAt: String,
)

/** Owner-visible details for one authorized smart session. */
data class RemoteAccessSession(
    val sessionId: String,
    val walletId: String,
    val signerAddress: String,
    val grants: List<SmartSessionGrant>,
    val chainId: Int,
    val expiresAt: String,
)

/** Current usage for one bounded smart-session grant. */
data class SmartSessionGrantUsage(
    val grant: SmartSessionGrant,
    val used: BigInteger? = null,
)

data class TransactionStatusResponse(
    val status: TransactionStatus,
    val txnHash: String? = null,
)

fun interface FeeOptionSelector {
    suspend fun select(feeOptions: List<FeeOptionWithBalance>): FeeOptionSelection?

    companion object {
        /**
         * Selects the first fee option whose available raw balance covers the
         * quoted fee value. Returns null when no option has sufficient balance.
         */
        val firstAvailable: FeeOptionSelector =
            FeeOptionSelector { feeOptions ->
                feeOptions.firstOrNull { it.hasEnoughBalance() }?.selection
            }
    }
}

data class FeeOptionWithBalance(
    val feeOption: FeeOption,
    val selection: FeeOptionSelection = FeeOptionSelection(feeOption),
    val balance: TokenBalance? = null,
    val available: String? = null,
    val availableRaw: String? = null,
    val decimals: Int? = null,
)

private fun FeeOptionWithBalance.hasEnoughBalance(): Boolean {
    val balance = availableRaw?.toBigIntegerOrNull() ?: return false
    val fee = feeOption.value.toBigIntegerOrNull() ?: return false
    return balance >= fee
}

private fun FeeOption.selectionToken(): String =
    token.tokenId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: token.symbol

data class SendTransactionRequest(
    val to: String,
    /**
     * Raw base-unit transaction value. Use
     * [technology.polygon.omswallet.utils.parseUnits] to convert decimal display
     * values before sending.
     */
    val value: BigInteger,
    val data: String? = null,
    val mode: TransactionMode = TransactionMode.Relayer,
)

data class SendTransactionResponse(
    val txnId: String,
    val status: TransactionStatus,
    val txnHash: String?,
    val statusResolution: TransactionStatusResolution,
)

enum class TransactionStatusResolution {
    NotRequested,
    Resolved,
    TimedOut,
}

data class TransactionStatusPollingOptions(
    val fastPollIntervalMillis: Long = 400L,
    val fastPollCount: Int = 5,
    val pollIntervalMillis: Long = 2_000L,
    val timeoutMillis: Long = 60_000L,
)

data class TokenBalancesPageRequest(
    val page: Int = 0,
    val pageSize: Int = 40,
)

enum class IndexerNetworkType(
    val wireValue: String,
) {
    MAINNETS("MAINNETS"),
    TESTNETS("TESTNETS"),
    ALL("ALL"),
}

enum class ContractVerificationStatus(
    val wireValue: String,
) {
    VERIFIED("VERIFIED"),
    UNVERIFIED("UNVERIFIED"),
    ALL("ALL"),
}

data class TokenBalancesPage(
    val page: Int,
    val pageSize: Int,
    val more: Boolean,
)

data class MetadataOptions(
    val verifiedOnly: Boolean? = null,
    val unverifiedOnly: Boolean? = null,
    val includeContracts: List<String> = emptyList(),
)

data class TokenContractInfo(
    val chainId: Long,
    val address: String,
    val source: String,
    val name: String,
    val type: String,
    val symbol: String,
    val decimals: Int? = null,
    val logoURI: String? = null,
    val deployed: Boolean,
    val bytecodeHash: String,
    val extensions: Map<String, JsonElement>,
    val updatedAt: String,
    val queuedAt: String? = null,
    val status: String,
)

data class TokenMetadataAsset(
    val id: Long? = null,
    val collectionId: Long? = null,
    val tokenId: String? = null,
    val url: String? = null,
    val metadataField: String? = null,
    val name: String? = null,
    val filesize: Long? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val updatedAt: String? = null,
)

data class TokenMetadata(
    val chainId: Long? = null,
    val contractAddress: String? = null,
    val tokenId: String,
    val source: String,
    val name: String,
    val description: String? = null,
    val image: String? = null,
    val video: String? = null,
    val audio: String? = null,
    val properties: Map<String, JsonElement>? = null,
    val attributes: List<Map<String, JsonElement>>,
    val imageData: String? = null,
    val externalUrl: String? = null,
    val backgroundColor: String? = null,
    val animationUrl: String? = null,
    val decimals: Int? = null,
    val updatedAt: String? = null,
    val assets: List<TokenMetadataAsset>? = null,
    val status: String,
    val queuedAt: String? = null,
    val lastFetched: String? = null,
)

sealed interface TokenBalance {
    val contractType: String
    val accountAddress: String
    val balance: String
    val chainId: Long
    val balanceUSD: String?
    val priceUSD: String?
    val priceUpdatedAt: String?
}

data class NativeTokenBalance(
    override val accountAddress: String,
    val name: String,
    val symbol: String,
    override val balance: String,
    override val chainId: Long,
    override val balanceUSD: String? = null,
    override val priceUSD: String? = null,
    override val priceUpdatedAt: String? = null,
) : TokenBalance {
    override val contractType: String = "NATIVE"
}

data class ContractTokenBalance(
    override val contractType: String,
    val contractAddress: String,
    override val accountAddress: String,
    val tokenId: String,
    override val balance: String,
    val blockHash: String,
    val blockNumber: Long,
    override val chainId: Long,
    override val balanceUSD: String? = null,
    override val priceUSD: String? = null,
    override val priceUpdatedAt: String? = null,
    val uniqueCollectibles: String? = null,
    val isSummary: Boolean? = null,
    val contractInfo: TokenContractInfo? = null,
    val tokenMetadata: TokenMetadata? = null,
) : TokenBalance

data class TokenBalancesResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val balances: List<ContractTokenBalance>,
    val nativeBalances: List<NativeTokenBalance> = emptyList(),
)

data class TransactionTransfer(
    val transferType: String,
    val contractAddress: String,
    val contractType: String,
    val from: String,
    val to: String,
    val tokenIds: List<String>? = null,
    val amounts: List<String>,
    val logIndex: Long,
    val amountsUSD: List<String>? = null,
    val pricesUSD: List<String>? = null,
    val contractInfo: TokenContractInfo? = null,
    val tokenMetadata: Map<String, TokenMetadata>? = null,
)

data class Transaction(
    val txnHash: String,
    val blockNumber: Long,
    val blockHash: String,
    val chainId: Long,
    val metaTxnId: String? = null,
    val transfers: List<TransactionTransfer> = emptyList(),
    val timestamp: String,
)

data class TransactionHistoryResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val transactions: List<Transaction>,
)
