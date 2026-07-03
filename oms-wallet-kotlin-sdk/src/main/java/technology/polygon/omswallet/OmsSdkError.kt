package technology.polygon.omswallet

import kotlinx.coroutines.CancellationException
import technology.polygon.omswallet.internal.generated.waas.ErrorKind
import technology.polygon.omswallet.internal.generated.waas.WebRpcError
import technology.polygon.omswallet.internal.generated.waas.WebRpcTransportException

/**
 * Stable SDK-level error categories for app-facing error handling.
 */
enum class OmsSdkErrorCode {
    HttpError,
    InvalidResponse,
    RequestFailed,
    AuthCommitmentConsumed,
    SessionMissing,
    SessionExpired,
    WalletSelectionStale,
    WalletSelectionUnavailable,
    WalletSelectionInFlight,
    TransactionExecutionUnconfirmed,
    TransactionStatusLookupFailed,
    ValidationError,
    StorageError,
}

/**
 * Stable identifiers for SDK operations that can appear on [OmsSdkException].
 */
enum class OmsSdkOperation(
    val id: String,
) {
    PendingWalletSelection("wallet.pendingWalletSelection"),
    PendingWalletSelectionCreateAndSelectWallet("wallet.pendingWalletSelection.createAndSelectWallet"),
    PendingWalletSelectionSelectWallet("wallet.pendingWalletSelection.selectWallet"),
    IndexerGetBalances("indexer.getBalances"),
    IndexerGetTransactionHistory("indexer.getTransactionHistory"),
    WalletCallContract("wallet.callContract"),
    WalletCompleteEmailAuth("wallet.completeEmailAuth"),
    WalletCreateWallet("wallet.createWallet"),
    WalletExecute("wallet.execute"),
    WalletGetIdToken("wallet.getIdToken"),
    WalletHandleOidcRedirectCallback("wallet.handleOidcRedirectCallback"),
    WalletGetTransactionStatus("wallet.getTransactionStatus"),
    WalletIsValidMessageSignature("wallet.isValidMessageSignature"),
    WalletIsValidTypedDataSignature("wallet.isValidTypedDataSignature"),
    WalletListAccess("wallet.listAccess"),
    WalletListAccessPage("wallet.listAccessPage"),
    WalletListAccessPages("wallet.listAccessPages"),
    WalletListWallets("wallet.listWallets"),
    WalletRevokeAccess("wallet.revokeAccess"),
    WalletSendTransaction("wallet.sendTransaction"),
    WalletSignInWithOidcIdToken("wallet.signInWithOidcIdToken"),
    WalletSignMessage("wallet.signMessage"),
    WalletSignTypedData("wallet.signTypedData"),
    WalletStartEmailAuth("wallet.startEmailAuth"),
    WalletStartOidcRedirectAuth("wallet.startOidcRedirectAuth"),
    WalletTransactionStatus("wallet.transactionStatus"),
    WalletUseWallet("wallet.useWallet"),
}

/**
 * Remote OMS service that produced diagnostic failure details.
 */
enum class OmsUpstreamService {
    Waas,
    Indexer,
}

/**
 * Normalized diagnostic detail from a remote OMS service response or transport failure.
 *
 * Branch app behavior on [OmsSdkException.code]; use upstream details for logs and
 * service-specific troubleshooting.
 */
data class OmsUpstreamError(
    val service: OmsUpstreamService,
    val name: String? = null,
    val code: String? = null,
    val message: String? = null,
    val status: Int? = null,
)

/**
 * Base exception type thrown by public SDK APIs when a failure can be
 * categorized without exposing generated transport details.
 */
open class OmsSdkException(
    val code: OmsSdkErrorCode,
    val operation: OmsSdkOperation? = null,
    val status: Int? = null,
    val txnId: String? = null,
    val retryable: Boolean? = null,
    val upstreamError: OmsUpstreamError? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OmsSessionException(
    code: OmsSdkErrorCode = OmsSdkErrorCode.SessionMissing,
    operation: OmsSdkOperation? = null,
    message: String = "No active wallet session",
    cause: Throwable? = null,
) : OmsSdkException(
        code = code,
        operation = operation,
        message = message,
        cause = cause,
    )

class OmsRequestException(
    code: OmsSdkErrorCode = OmsSdkErrorCode.RequestFailed,
    operation: OmsSdkOperation? = null,
    status: Int? = null,
    retryable: Boolean? = status == null || status >= 500,
    upstreamError: OmsUpstreamError? = null,
    message: String = "OMS request failed",
    cause: Throwable? = null,
) : OmsSdkException(
        code = code,
        operation = operation,
        status = status,
        retryable = retryable,
        upstreamError = upstreamError,
        message = message,
        cause = cause,
    )

class OmsResponseException(
    operation: OmsSdkOperation? = null,
    status: Int? = null,
    upstreamError: OmsUpstreamError? = null,
    message: String = "OMS response was invalid",
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.InvalidResponse,
        operation = operation,
        status = status,
        upstreamError = upstreamError,
        message = message,
        cause = cause,
    )

class OmsTransactionException(
    code: OmsSdkErrorCode = OmsSdkErrorCode.TransactionStatusLookupFailed,
    operation: OmsSdkOperation? = null,
    status: Int? = null,
    txnId: String? = null,
    retryable: Boolean? = true,
    upstreamError: OmsUpstreamError? = null,
    message: String = "Transaction status lookup failed",
    cause: Throwable? = null,
) : OmsSdkException(
        code = code,
        operation = operation,
        status = status,
        txnId = txnId,
        retryable = retryable,
        upstreamError = upstreamError,
        message = message,
        cause = cause,
    )

class OMSWalletSelectionException(
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

class OmsStorageException(
    operation: OmsSdkOperation? = null,
    message: String,
    cause: Throwable? = null,
) : OmsSdkException(
        code = OmsSdkErrorCode.StorageError,
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
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (throwable: OmsSdkException) {
        if (throwable.operation == operation || throwable.isNestedTransactionBoundary()) {
            throw throwable
        }
        throw throwable.withOperation(operation)
    } catch (throwable: WebRpcError) {
        throw throwable.toOmsSdkException(operation)
    } catch (throwable: WebRpcTransportException) {
        throw OmsRequestException(
            operation = operation,
            upstreamError = throwable.toWaasUpstreamError(),
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

private fun WebRpcError.toOmsSdkException(operation: OmsSdkOperation): OmsSdkException {
    val normalizedStatus = normalizedStatus()
    val upstreamError = toWaasUpstreamError(normalizedStatus)
    val normalizedMessage = normalizedMessage()

    return when {
        errorKind == ErrorKind.COMMITMENT_CONSUMED -> {
            OmsRequestException(
                code = OmsSdkErrorCode.AuthCommitmentConsumed,
                operation = operation,
                status = normalizedStatus,
                retryable = false,
                upstreamError = upstreamError,
                message = normalizedMessage,
                cause = this,
            )
        }

        isHttpWebRpcError(normalizedStatus) -> {
            OmsRequestException(
                code = OmsSdkErrorCode.HttpError,
                operation = operation,
                status = normalizedStatus,
                retryable = normalizedStatus != null && normalizedStatus >= 500,
                upstreamError = upstreamError,
                message = normalizedMessage,
                cause = this,
            )
        }

        errorKind == ErrorKind.WEBRPC_BAD_RESPONSE ||
            (errorKind == ErrorKind.UNKNOWN && code == ErrorKind.UNKNOWN.code) -> {
            OmsResponseException(
                operation = operation,
                status = normalizedStatus,
                upstreamError = upstreamError,
                message = normalizedMessage,
                cause = this,
            )
        }

        else -> {
            OmsRequestException(
                operation = operation,
                status = normalizedStatus,
                retryable = normalizedStatus == null || normalizedStatus >= 500,
                upstreamError = upstreamError,
                message = normalizedMessage,
                cause = this,
            )
        }
    }
}

private fun OmsSdkException.isNestedTransactionBoundary(): Boolean =
    code == OmsSdkErrorCode.TransactionExecutionUnconfirmed ||
        code == OmsSdkErrorCode.TransactionStatusLookupFailed

internal fun Throwable.toOmsSdkException(operation: OmsSdkOperation): OmsSdkException =
    when (this) {
        is OmsSdkException -> {
            if (this.operation == operation) {
                this
            } else {
                withOperation(operation)
            }
        }

        is WebRpcError -> {
            toOmsSdkException(operation)
        }

        is WebRpcTransportException -> {
            OmsRequestException(
                operation = operation,
                upstreamError = toWaasUpstreamError(),
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

private fun OmsSdkException.withOperation(operation: OmsSdkOperation): OmsSdkException =
    when (this) {
        is OmsRequestException -> {
            OmsRequestException(
                code = code,
                operation = operation,
                status = status,
                retryable = retryable,
                upstreamError = upstreamError,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OmsResponseException -> {
            OmsResponseException(
                operation = operation,
                status = status,
                upstreamError = upstreamError,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OmsTransactionException -> {
            OmsTransactionException(
                code = code,
                operation = operation,
                status = status,
                txnId = txnId,
                retryable = retryable,
                upstreamError = upstreamError,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OmsSessionException -> {
            OmsSessionException(
                code = code,
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OMSWalletSelectionException -> {
            OMSWalletSelectionException(
                code = code,
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OmsValidationException -> {
            OmsValidationException(
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OmsStorageException -> {
            OmsStorageException(
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }

        else -> {
            OmsSdkException(
                code = code,
                operation = operation,
                status = status,
                txnId = txnId,
                retryable = retryable,
                upstreamError = upstreamError,
                message = message ?: operation.id,
                cause = this,
            )
        }
    }

private fun WebRpcError.normalizedStatus(): Int? {
    if (error == "WebrpcRequestFailed" && code == ErrorKind.WEBRPC_REQUEST_FAILED.code && status == 400) {
        return null
    }
    return status
}

private fun WebRpcError.toWaasUpstreamError(status: Int? = normalizedStatus()): OmsUpstreamError =
    OmsUpstreamError(
        service = OmsUpstreamService.Waas,
        name = error,
        code = normalizedCode(),
        message = normalizedMessage(),
        status = status,
    )

private fun WebRpcError.normalizedCode(): String =
    if (error == "WebrpcBadResponse" && code == ErrorKind.UNKNOWN.code) {
        ErrorKind.WEBRPC_BAD_RESPONSE.code.toString()
    } else {
        code.toString()
    }

private fun WebRpcError.normalizedMessage(): String =
    if (error == "WebrpcBadResponse" && code == ErrorKind.UNKNOWN.code) {
        "bad response"
    } else {
        message
    }

private fun WebRpcTransportException.toWaasUpstreamError(): OmsUpstreamError =
    OmsUpstreamError(
        service = OmsUpstreamService.Waas,
        name = "WebrpcRequestFailed",
        code = ErrorKind.WEBRPC_REQUEST_FAILED.code.toString(),
        message = message ?: "WebRPC transport failed",
        status = null,
    )

private fun WebRpcError.isHttpWebRpcError(status: Int?): Boolean =
    status != null &&
        status >= 400 &&
        (
            errorKind == ErrorKind.WEBRPC_BAD_ROUTE ||
                errorKind == ErrorKind.WEBRPC_BAD_METHOD ||
                errorKind == ErrorKind.WEBRPC_BAD_REQUEST ||
                errorKind == ErrorKind.WEBRPC_BAD_RESPONSE ||
                error == "WebrpcBadResponse"
        )
