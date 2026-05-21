package com.omsclient.kotlin_sdk.models

import kotlinx.serialization.json.JsonElement
import java.math.BigInteger
import com.omsclient.kotlin_sdk.generated.waas.AbiArg as WaasAbiArg
import com.omsclient.kotlin_sdk.generated.waas.CredentialInfo as WaasCredentialInfo
import com.omsclient.kotlin_sdk.generated.waas.FeeOption as WaasFeeOption
import com.omsclient.kotlin_sdk.generated.waas.FeeOptionSelection as WaasFeeOptionSelection
import com.omsclient.kotlin_sdk.generated.waas.FeeToken as WaasFeeToken
import com.omsclient.kotlin_sdk.generated.waas.ListAccessResponse as WaasListAccessResponse
import com.omsclient.kotlin_sdk.generated.waas.Page as WaasPage
import com.omsclient.kotlin_sdk.generated.waas.TransactionMode as WaasTransactionMode
import com.omsclient.kotlin_sdk.generated.waas.TransactionStatus as WaasTransactionStatus
import com.omsclient.kotlin_sdk.generated.waas.TransactionStatusResponse as WaasTransactionStatusResponse
import com.omsclient.kotlin_sdk.generated.waas.Wallet as WaasWallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType as WaasWalletType

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
)

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
}

data class FeeOptionWithBalance(
    val feeOption: FeeOption,
    val balance: TokenBalance?,
    val available: String?,
    val availableRaw: String?,
    val decimals: Int?,
)

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

internal fun WalletType.toWaas(): WaasWalletType =
    when (this) {
        WalletType.Ethereum -> WaasWalletType.Ethereum
        WalletType.UNKNOWN_DEFAULT -> WaasWalletType.UNKNOWN_DEFAULT
    }

internal fun WaasWalletType.toModel(): WalletType =
    when (this) {
        WaasWalletType.Ethereum -> WalletType.Ethereum
        WaasWalletType.UNKNOWN_DEFAULT -> WalletType.UNKNOWN_DEFAULT
    }

internal fun TransactionMode.toWaas(): WaasTransactionMode =
    when (this) {
        TransactionMode.Native -> WaasTransactionMode.Native
        TransactionMode.Relayer -> WaasTransactionMode.Relayer
        TransactionMode.UNKNOWN_DEFAULT -> WaasTransactionMode.UNKNOWN_DEFAULT
    }

internal fun WaasTransactionStatus.toModel(): TransactionStatus =
    when (this) {
        WaasTransactionStatus.Quoted -> TransactionStatus.Quoted
        WaasTransactionStatus.Pending -> TransactionStatus.Pending
        WaasTransactionStatus.Executed -> TransactionStatus.Executed
        WaasTransactionStatus.UNKNOWN_DEFAULT -> TransactionStatus.UNKNOWN_DEFAULT
    }

internal fun WaasWallet.toModel(): Wallet =
    Wallet(
        id = id,
        type = type.toModel(),
        address = address,
        reference = reference,
    )

internal fun WaasFeeToken.toModel(): FeeToken =
    FeeToken(
        network = network,
        name = name,
        symbol = symbol,
        type = type,
        decimals = decimals,
        logoUrl = logoUrl,
        contractAddress = contractAddress,
        tokenId = tokenId,
    )

internal fun WaasFeeOption.toModel(): FeeOption =
    FeeOption(
        token = token.toModel(),
        value = value,
        displayValue = displayValue,
    )

internal fun FeeOptionSelection.toWaas(): WaasFeeOptionSelection = WaasFeeOptionSelection(token = token)

internal fun Page.toWaas(): WaasPage =
    WaasPage(
        limit = limit,
        cursor = cursor,
    )

internal fun WaasPage.toModel(): Page =
    Page(
        limit = limit,
        cursor = cursor,
    )

internal fun AbiArg.toWaas(): WaasAbiArg =
    WaasAbiArg(
        type = type,
        value = value,
    )

internal fun WaasCredentialInfo.toModel(): CredentialInfo =
    CredentialInfo(
        credentialId = credentialId,
        expiresAt = expiresAt,
        isCaller = isCaller,
    )

internal fun WaasListAccessResponse.toModel(): ListAccessResponse =
    ListAccessResponse(
        credentials = credentials.map { it.toModel() },
        page = page?.toModel(),
    )

internal fun WaasTransactionStatusResponse.toModel(): TransactionStatusResponse =
    TransactionStatusResponse(
        status = status.toModel(),
        txnHash = txnHash,
    )
