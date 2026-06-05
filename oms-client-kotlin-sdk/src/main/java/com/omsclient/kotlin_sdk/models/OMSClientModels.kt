package com.omsclient.kotlin_sdk.models

import kotlinx.serialization.json.JsonElement
import java.math.BigInteger

enum class WalletType(
    val wireValue: String,
) {
    Ethereum("ethereum"),
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
    UNKNOWN_DEFAULT("UNKNOWN_DEFAULT"),
}

data class Wallet(
    val id: String,
    val type: WalletType,
    val address: String,
    val reference: String? = null,
)

data class FeeToken(
    val network: String,
    val name: String,
    val symbol: String,
    val type: String,
    val decimals: UInt? = null,
    val logoUrl: String,
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
) {
    constructor(feeOption: FeeOption) : this(token = feeOption.selectionToken())
}

data class Page(
    val limit: UInt? = null,
    val cursor: String? = null,
)

data class AbiArg(
    val type: String,
    val value: JsonElement,
)

data class CredentialInfo(
    val credentialId: String,
    val expiresAt: String,
    val isCaller: Boolean,
)

data class ListAccessResponse(
    val credentials: List<CredentialInfo>,
    val page: Page? = null,
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
        val firstAvailable =
            FeeOptionSelector { feeOptions ->
                feeOptions.firstOrNull { it.hasEnoughBalance() }?.selection
            }
    }
}

data class FeeOptionWithBalance(
    val feeOption: FeeOption,
    val balance: TokenBalance?,
    val available: String?,
    val availableRaw: String?,
    val decimals: Int?,
) {
    val selection: FeeOptionSelection
        get() = FeeOptionSelection(feeOption)
}

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
     * [com.omsclient.kotlin_sdk.utils.parseUnits] to convert decimal display
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
)

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

data class TokenBalancesPage(
    val page: Int,
    val pageSize: Int,
    val more: Boolean,
)

data class TokenContractInfo(
    val chainId: Long? = null,
    val address: String? = null,
    val source: String? = null,
    val name: String? = null,
    val type: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val logoURI: String? = null,
    val deployed: Boolean? = null,
    val bytecodeHash: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val updatedAt: String? = null,
    val queuedAt: String? = null,
    val status: String? = null,
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
    val tokenId: String? = null,
    val source: String? = null,
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
    val video: String? = null,
    val audio: String? = null,
    val properties: Map<String, JsonElement>? = null,
    val attributes: List<Map<String, JsonElement>>? = null,
    val imageData: String? = null,
    val externalUrl: String? = null,
    val backgroundColor: String? = null,
    val animationUrl: String? = null,
    val decimals: Int? = null,
    val updatedAt: String? = null,
    val assets: List<TokenMetadataAsset>? = null,
    val status: String? = null,
    val queuedAt: String? = null,
    val lastFetched: String? = null,
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
    val balanceUSD: String? = null,
    val priceUSD: String? = null,
    val priceUpdatedAt: String? = null,
    val uniqueCollectibles: String? = null,
    val isSummary: Boolean? = null,
    val contractInfo: TokenContractInfo? = null,
    val tokenMetadata: TokenMetadata? = null,
)

data class TokenBalancesResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val balances: List<TokenBalance>,
)
