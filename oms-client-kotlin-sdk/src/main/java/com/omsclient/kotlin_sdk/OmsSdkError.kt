package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.internal.generated.waas.ErrorKind
import com.omsclient.kotlin_sdk.internal.generated.waas.WebRpcError
import com.omsclient.kotlin_sdk.internal.generated.waas.WebRpcTransportException

/**
 * Stable SDK-level error categories for app-facing error handling.
 */
enum class OmsSdkErrorCode {
    HttpError,
    InvalidResponse,
    RequestFailed,
    AuthCommitmentConsumed,
    SessionMissing,
    WalletSelectionStale,
    WalletSelectionUnavailable,
    WalletSelectionInFlight,
    TransactionStatusLookupFailed,
    ValidationError,
}

/**
 * Stable identifiers for SDK operations that can appear on [OmsSdkException].
 */
enum class OmsSdkOperation(
    val id: String,
) {
    PendingWalletSelection("pendingWalletSelection"),
    PendingWalletSelectionCreateAndSelectWallet("pendingWalletSelection.createAndSelectWallet"),
    PendingWalletSelectionSelectWallet("pendingWalletSelection.selectWallet"),
    WalletCallContract("wallet.callContract"),
    WalletCompleteEmailAuth("wallet.completeEmailAuth"),
    WalletCreateWallet("wallet.createWallet"),
    WalletGetIdToken("wallet.getIdToken"),
    WalletHandleOidcRedirectCallback("wallet.handleOidcRedirectCallback"),
    WalletGetTransactionStatus("wallet.getTransactionStatus"),
    WalletIsValidMessageSignature("wallet.isValidMessageSignature"),
    WalletIsValidTypedDataSignature("wallet.isValidTypedDataSignature"),
    WalletListAccess("wallet.listAccess"),
    WalletListAccessPage("wallet.listAccessPage"),
    WalletListWallets("wallet.listWallets"),
    WalletRevokeAccess("wallet.revokeAccess"),
    WalletSendTransaction("wallet.sendTransaction"),
    WalletSignInWithOidcIdToken("wallet.signInWithOidcIdToken"),
    WalletSignMessage("wallet.signMessage"),
    WalletSignTypedData("wallet.signTypedData"),
    WalletStartEmailAuth("wallet.startEmailAuth"),
    WalletStartOidcRedirectAuth("wallet.startOidcRedirectAuth"),
    WalletUseWallet("wallet.useWallet"),
}

/**
 * Base exception type thrown by public SDK APIs when a failure can be
 * categorized without exposing generated transport details.
 */
open class OmsSdkException(
    val code: OmsSdkErrorCode,
    val operation: OmsSdkOperation? = null,
    val status: Int? = null,
    val txnId: String? = null,
    val retryable: Boolean = false,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OmsSessionException(
    operation: OmsSdkOperation? = null,
    message: String = "No active wallet session",
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.SessionMissing,
        operation = operation,
        message = message,
        cause = cause,
    )

class OmsRequestException(
    operation: OmsSdkOperation? = null,
    status: Int? = null,
    message: String = "OMS request failed",
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.RequestFailed,
        operation = operation,
        status = status,
        retryable = status == null || status >= 500,
        message = message,
        cause = cause,
    )

class OmsResponseException(
    operation: OmsSdkOperation? = null,
    status: Int? = null,
    message: String = "OMS response was invalid",
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.InvalidResponse,
        operation = operation,
        status = status,
        message = message,
        cause = cause,
    )

class OmsTransactionException(
    operation: OmsSdkOperation? = null,
    txnId: String? = null,
    message: String = "Transaction status lookup failed",
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.TransactionStatusLookupFailed,
        operation = operation,
        txnId = txnId,
        retryable = true,
        message = message,
        cause = cause,
    )

class OmsWalletSelectionException(
    code: OmsSdkErrorCode,
    operation: OmsSdkOperation? = null,
    message: String,
    cause: Throwable? = null,
) : OmsSdkException(
        code = code,
        operation = operation,
        message = message,
        cause = cause,
    )

class OmsValidationException(
    operation: OmsSdkOperation? = null,
    message: String,
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.ValidationError,
        operation = operation,
        message = message,
        cause = cause,
    )

internal suspend fun <T> runOmsOperation(
    operation: OmsSdkOperation,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (throwable: kotlinx.coroutines.CancellationException) {
        throw throwable
    } catch (throwable: OmsSdkException) {
        if (throwable.operation == operation) {
            throw throwable
        }
        throw OmsSdkException(
            code = throwable.code,
            operation = operation,
            status = throwable.status,
            txnId = throwable.txnId,
            retryable = throwable.retryable,
            message = throwable.message ?: operation.id,
            cause = throwable,
        )
    } catch (throwable: WebRpcError) {
        throw throwable.toOmsSdkException(operation)
    } catch (throwable: WebRpcTransportException) {
        throw OmsRequestException(
            operation = operation,
            message = throwable.message ?: "WebRPC transport failed",
            cause = throwable,
        )
    } catch (throwable: IllegalArgumentException) {
        throw OmsValidationException(
            operation = operation,
            message = throwable.message ?: "Validation failed",
            cause = throwable,
        )
    } catch (throwable: IllegalStateException) {
        throw OmsSessionException(
            operation = operation,
            message = throwable.message ?: "No active wallet session",
            cause = throwable,
        )
    }

private fun WebRpcError.toOmsSdkException(operation: OmsSdkOperation): OmsSdkException =
    when (errorKind) {
        ErrorKind.COMMITMENT_CONSUMED -> {
            OmsSdkException(
                code = OmsSdkErrorCode.AuthCommitmentConsumed,
                operation = operation,
                status = status,
                message = message,
                cause = this,
            )
        }

        ErrorKind.WEBRPC_BAD_RESPONSE,
        -> {
            OmsResponseException(
                operation = operation,
                status = status,
                message = message,
                cause = this,
            )
        }

        ErrorKind.UNKNOWN -> {
            if (code == ErrorKind.UNKNOWN.code) {
                OmsResponseException(
                    operation = operation,
                    status = status,
                    message = message,
                    cause = this,
                )
            } else {
                OmsRequestException(
                    operation = operation,
                    status = status,
                    message = message,
                    cause = this,
                )
            }
        }

        else -> {
            OmsRequestException(
                operation = operation,
                status = status,
                message = message,
                cause = this,
            )
        }
    }

internal fun Throwable.toOmsSdkException(operation: OmsSdkOperation): OmsSdkException =
    when (this) {
        is OmsSdkException -> {
            if (this.operation == operation) {
                this
            } else {
                OmsSdkException(
                    code = code,
                    operation = operation,
                    status = status,
                    txnId = txnId,
                    retryable = retryable,
                    message = message ?: operation.id,
                    cause = this,
                )
            }
        }

        is WebRpcError -> {
            toOmsSdkException(operation)
        }

        is WebRpcTransportException -> {
            OmsRequestException(
                operation = operation,
                message = message ?: "WebRPC transport failed",
                cause = this,
            )
        }

        is IllegalArgumentException -> {
            OmsValidationException(
                operation = operation,
                message = message ?: "Validation failed",
                cause = this,
            )
        }

        is IllegalStateException -> {
            OmsSessionException(
                operation = operation,
                message = message ?: "No active wallet session",
                cause = this,
            )
        }

        else -> {
            OmsRequestException(
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }
    }
