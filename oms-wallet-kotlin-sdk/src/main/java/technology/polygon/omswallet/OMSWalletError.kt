package technology.polygon.omswallet

import kotlinx.coroutines.CancellationException
import technology.polygon.omswallet.internal.generated.waas.ErrorKind
import technology.polygon.omswallet.internal.generated.waas.WebRpcError
import technology.polygon.omswallet.internal.generated.waas.WebRpcTransportException

/**
 * Stable SDK-level error categories for app-facing error handling.
 */
enum class OMSWalletErrorCode(
    val id: String,
) {
    HttpError("OMS_HTTP_ERROR"),
    InvalidResponse("OMS_INVALID_RESPONSE"),
    RequestFailed("OMS_REQUEST_FAILED"),
    AuthCommitmentConsumed("OMS_AUTH_COMMITMENT_CONSUMED"),
    SessionMissing("OMS_SESSION_MISSING"),
    SessionExpired("OMS_SESSION_EXPIRED"),
    WalletSelectionStale("OMS_WALLET_SELECTION_STALE"),
    WalletSelectionUnavailable("OMS_WALLET_SELECTION_UNAVAILABLE"),
    WalletSelectionInFlight("OMS_WALLET_SELECTION_IN_FLIGHT"),
    TransactionExecutionUnconfirmed("OMS_TRANSACTION_EXECUTION_UNCONFIRMED"),
    TransactionStatusLookupFailed("OMS_TRANSACTION_STATUS_LOOKUP_FAILED"),
    ValidationError("OMS_VALIDATION_ERROR"),
    StorageError("OMS_STORAGE_ERROR"),
}

/**
 * Stable identifiers for SDK operations that can appear on [OMSWalletException].
 */
enum class OMSWalletOperation(
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
    WalletSignOut("wallet.signOut"),
    WalletSignTypedData("wallet.signTypedData"),
    WalletStartEmailAuth("wallet.startEmailAuth"),
    WalletStartOidcRedirectAuth("wallet.startOidcRedirectAuth"),
    WalletTransactionStatus("wallet.transactionStatus"),
    WalletUseWallet("wallet.useWallet"),
}

/**
 * Remote OMS service that produced diagnostic failure details.
 */
enum class OMSWalletUpstreamService {
    Waas,
    Indexer,
}

/**
 * Normalized diagnostic detail from a remote OMS service response or transport failure.
 *
 * Branch app behavior on [OMSWalletException.code]; use upstream details for logs and
 * service-specific troubleshooting.
 */
data class OMSWalletUpstreamError(
    val service: OMSWalletUpstreamService,
    val name: String? = null,
    val code: String? = null,
    val message: String? = null,
    val status: Int? = null,
)

/**
 * Base exception type thrown by public SDK APIs when a failure can be
 * categorized without exposing generated transport details.
 */
sealed class OMSWalletException(
    val code: OMSWalletErrorCode,
    val operation: OMSWalletOperation? = null,
    val status: Int? = null,
    val txnId: String? = null,
    val retryable: Boolean? = null,
    val upstreamError: OMSWalletUpstreamError? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OMSWalletSessionException(
    code: OMSWalletErrorCode = OMSWalletErrorCode.SessionMissing,
    operation: OMSWalletOperation? = null,
    message: String = "No active wallet session",
    cause: Throwable? = null,
) : OMSWalletException(
        code = code,
        operation = operation,
        message = message,
        cause = cause,
    ) {
    init {
        require(code == OMSWalletErrorCode.SessionMissing || code == OMSWalletErrorCode.SessionExpired) {
            "OMSWalletSessionException requires a session error code"
        }
    }
}

class OMSWalletRequestException(
    code: OMSWalletErrorCode = OMSWalletErrorCode.RequestFailed,
    operation: OMSWalletOperation? = null,
    status: Int? = null,
    retryable: Boolean? = status == null || status >= 500,
    upstreamError: OMSWalletUpstreamError? = null,
    message: String = "OMS request failed",
    cause: Throwable? = null,
) : OMSWalletException(
        code = code,
        operation = operation,
        status = status,
        retryable = retryable,
        upstreamError = upstreamError,
        message = message,
        cause = cause,
    ) {
    init {
        require(
            code == OMSWalletErrorCode.RequestFailed ||
                code == OMSWalletErrorCode.HttpError ||
                code == OMSWalletErrorCode.AuthCommitmentConsumed,
        ) {
            "OMSWalletRequestException requires a request error code"
        }
    }
}

class OMSWalletResponseException(
    operation: OMSWalletOperation? = null,
    status: Int? = null,
    upstreamError: OMSWalletUpstreamError? = null,
    message: String = "OMS response was invalid",
    cause: Throwable? = null,
) : OMSWalletException(
        code = OMSWalletErrorCode.InvalidResponse,
        operation = operation,
        status = status,
        upstreamError = upstreamError,
        message = message,
        cause = cause,
    )

class OMSWalletTransactionException(
    code: OMSWalletErrorCode = OMSWalletErrorCode.TransactionStatusLookupFailed,
    operation: OMSWalletOperation? = null,
    status: Int? = null,
    txnId: String? = null,
    retryable: Boolean? = true,
    upstreamError: OMSWalletUpstreamError? = null,
    message: String = "Transaction status lookup failed",
    cause: Throwable? = null,
) : OMSWalletException(
        code = code,
        operation = operation,
        status = status,
        txnId = txnId,
        retryable = retryable,
        upstreamError = upstreamError,
        message = message,
        cause = cause,
    ) {
    init {
        require(
            code == OMSWalletErrorCode.TransactionExecutionUnconfirmed ||
                code == OMSWalletErrorCode.TransactionStatusLookupFailed,
        ) {
            "OMSWalletTransactionException requires a transaction error code"
        }
    }
}

class OMSWalletSelectionException(
    code: OMSWalletErrorCode,
    operation: OMSWalletOperation? = null,
    message: String,
    cause: Throwable? = null,
) : OMSWalletException(
        code = code,
        operation = operation,
        message = message,
        cause = cause,
    ) {
    init {
        require(
            code == OMSWalletErrorCode.WalletSelectionStale ||
                code == OMSWalletErrorCode.WalletSelectionUnavailable ||
                code == OMSWalletErrorCode.WalletSelectionInFlight,
        ) {
            "OMSWalletSelectionException requires a wallet selection error code"
        }
    }
}

class OMSWalletValidationException(
    operation: OMSWalletOperation? = null,
    message: String,
    cause: Throwable? = null,
) : OMSWalletException(
        code = OMSWalletErrorCode.ValidationError,
        operation = operation,
        message = message,
        cause = cause,
    )

class OMSWalletStorageException(
    operation: OMSWalletOperation? = null,
    message: String,
    cause: Throwable? = null,
) : OMSWalletException(
        code = OMSWalletErrorCode.StorageError,
        operation = operation,
        message = message,
        cause = cause,
    )

internal suspend fun <T> runOMSWalletOperation(
    operation: OMSWalletOperation,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (throwable: OMSWalletException) {
        if (throwable.operation == operation || throwable.isNestedTransactionBoundary()) {
            throw throwable
        }
        throw throwable.withOperation(operation)
    } catch (throwable: WebRpcError) {
        throw throwable.toOMSWalletException(operation)
    } catch (throwable: WebRpcTransportException) {
        throw OMSWalletRequestException(
            operation = operation,
            upstreamError = throwable.toWaasUpstreamError(),
            message = throwable.message ?: "WebRPC transport failed",
            cause = throwable,
        )
    } catch (throwable: IllegalArgumentException) {
        throw OMSWalletValidationException(
            operation = operation,
            message = throwable.message ?: "Validation failed",
            cause = throwable,
        )
    } catch (throwable: IllegalStateException) {
        throw OMSWalletSessionException(
            operation = operation,
            message = throwable.message ?: "No active wallet session",
            cause = throwable,
        )
    }

private fun WebRpcError.toOMSWalletException(operation: OMSWalletOperation): OMSWalletException {
    val normalizedStatus = normalizedStatus()
    val upstreamError = toWaasUpstreamError(normalizedStatus)
    val normalizedMessage = normalizedMessage()

    return when {
        errorKind == ErrorKind.COMMITMENT_CONSUMED -> {
            OMSWalletRequestException(
                code = OMSWalletErrorCode.AuthCommitmentConsumed,
                operation = operation,
                status = normalizedStatus,
                retryable = false,
                upstreamError = upstreamError,
                message = normalizedMessage,
                cause = this,
            )
        }

        isHttpWebRpcError(normalizedStatus) -> {
            OMSWalletRequestException(
                code = OMSWalletErrorCode.HttpError,
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
            OMSWalletResponseException(
                operation = operation,
                status = normalizedStatus,
                upstreamError = upstreamError,
                message = normalizedMessage,
                cause = this,
            )
        }

        else -> {
            OMSWalletRequestException(
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

private fun OMSWalletException.isNestedTransactionBoundary(): Boolean =
    code == OMSWalletErrorCode.TransactionExecutionUnconfirmed ||
        code == OMSWalletErrorCode.TransactionStatusLookupFailed

internal fun Throwable.toOMSWalletException(operation: OMSWalletOperation): OMSWalletException =
    when (this) {
        is OMSWalletException -> {
            if (this.operation == operation) {
                this
            } else {
                withOperation(operation)
            }
        }

        is WebRpcError -> {
            toOMSWalletException(operation)
        }

        is WebRpcTransportException -> {
            OMSWalletRequestException(
                operation = operation,
                upstreamError = toWaasUpstreamError(),
                message = message ?: "WebRPC transport failed",
                cause = this,
            )
        }

        is IllegalArgumentException -> {
            OMSWalletValidationException(
                operation = operation,
                message = message ?: "Validation failed",
                cause = this,
            )
        }

        is IllegalStateException -> {
            OMSWalletSessionException(
                operation = operation,
                message = message ?: "No active wallet session",
                cause = this,
            )
        }

        else -> {
            OMSWalletRequestException(
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }
    }

private fun OMSWalletException.withOperation(operation: OMSWalletOperation): OMSWalletException =
    when (this) {
        is OMSWalletRequestException -> {
            OMSWalletRequestException(
                code = code,
                operation = operation,
                status = status,
                retryable = retryable,
                upstreamError = upstreamError,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OMSWalletResponseException -> {
            OMSWalletResponseException(
                operation = operation,
                status = status,
                upstreamError = upstreamError,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OMSWalletTransactionException -> {
            OMSWalletTransactionException(
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

        is OMSWalletSessionException -> {
            OMSWalletSessionException(
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

        is OMSWalletValidationException -> {
            OMSWalletValidationException(
                operation = operation,
                message = message ?: operation.id,
                cause = this,
            )
        }

        is OMSWalletStorageException -> {
            OMSWalletStorageException(
                operation = operation,
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

private fun WebRpcError.toWaasUpstreamError(status: Int? = normalizedStatus()): OMSWalletUpstreamError =
    OMSWalletUpstreamError(
        service = OMSWalletUpstreamService.Waas,
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

private fun WebRpcTransportException.toWaasUpstreamError(): OMSWalletUpstreamError =
    OMSWalletUpstreamError(
        service = OMSWalletUpstreamService.Waas,
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
                errorKind == ErrorKind.WEBRPC_SERVER_PANIC ||
                errorKind == ErrorKind.WEBRPC_INTERNAL_ERROR ||
                error == "WebrpcBadResponse"
        )
