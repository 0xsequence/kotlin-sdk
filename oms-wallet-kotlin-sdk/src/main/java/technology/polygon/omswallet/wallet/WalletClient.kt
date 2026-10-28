package technology.polygon.omswallet.wallet

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletResponseException
import technology.polygon.omswallet.OMSWalletSelectionException
import technology.polygon.omswallet.OMSWalletSession
import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.OMSWalletSessionException
import technology.polygon.omswallet.OMSWalletSessionExpiredEvent
import technology.polygon.omswallet.OMSWalletStorageException
import technology.polygon.omswallet.OMSWalletTransactionException
import technology.polygon.omswallet.OMSWalletValidationException
import technology.polygon.omswallet.SolanaNetwork
import technology.polygon.omswallet.TronNetwork
import technology.polygon.omswallet.indexer.IndexerClient
import technology.polygon.omswallet.internal.generated.waas.AuthMode
import technology.polygon.omswallet.internal.generated.waas.AuthorizeRemoteAccessRequest
import technology.polygon.omswallet.internal.generated.waas.Ciphersuite
import technology.polygon.omswallet.internal.generated.waas.ClaimWalletRequest
import technology.polygon.omswallet.internal.generated.waas.CommitVerifierRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthResponse
import technology.polygon.omswallet.internal.generated.waas.CreateWalletRequest
import technology.polygon.omswallet.internal.generated.waas.ERC20TransferGrant
import technology.polygon.omswallet.internal.generated.waas.ErrorKind
import technology.polygon.omswallet.internal.generated.waas.ExecuteRequest
import technology.polygon.omswallet.internal.generated.waas.GetIDTokenRequest
import technology.polygon.omswallet.internal.generated.waas.GetRecipientKeyRequest
import technology.polygon.omswallet.internal.generated.waas.GetSessionRequest
import technology.polygon.omswallet.internal.generated.waas.GetSessionUsageRequest
import technology.polygon.omswallet.internal.generated.waas.Grant
import technology.polygon.omswallet.internal.generated.waas.GrantKind
import technology.polygon.omswallet.internal.generated.waas.Grants
import technology.polygon.omswallet.internal.generated.waas.HPKEPayload
import technology.polygon.omswallet.internal.generated.waas.Identity
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.ImportWalletRequest
import technology.polygon.omswallet.internal.generated.waas.InspectCredentialRequest
import technology.polygon.omswallet.internal.generated.waas.IsValidMessageSignatureRequest
import technology.polygon.omswallet.internal.generated.waas.IsValidTypedDataSignatureRequest
import technology.polygon.omswallet.internal.generated.waas.KeyFormat
import technology.polygon.omswallet.internal.generated.waas.LambdaWebRpcTransport
import technology.polygon.omswallet.internal.generated.waas.ListAccessRequest
import technology.polygon.omswallet.internal.generated.waas.ListWalletsRequest
import technology.polygon.omswallet.internal.generated.waas.NativeTransferGrant
import technology.polygon.omswallet.internal.generated.waas.PrepareEthereumContractCallRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareEthereumTransactionRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareResponse
import technology.polygon.omswallet.internal.generated.waas.PrepareSolanaTransferRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareTronContractCallRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareTronTransactionRequest
import technology.polygon.omswallet.internal.generated.waas.RevokeAccessRequest
import technology.polygon.omswallet.internal.generated.waas.SignMessageRequest
import technology.polygon.omswallet.internal.generated.waas.SignTypedDataRequest
import technology.polygon.omswallet.internal.generated.waas.SolanaRecipient
import technology.polygon.omswallet.internal.generated.waas.TransactionStatusRequest
import technology.polygon.omswallet.internal.generated.waas.TransportPurpose
import technology.polygon.omswallet.internal.generated.waas.UseWalletRequest
import technology.polygon.omswallet.internal.generated.waas.WEBRPC_SCHEMA_VERSION
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.WaasClient
import technology.polygon.omswallet.internal.generated.waas.WaasPublicClient
import technology.polygon.omswallet.internal.generated.waas.WebRpcError
import technology.polygon.omswallet.internal.generated.waas.WebRpcHttpResponse
import technology.polygon.omswallet.internal.generated.waas.WebRpcTransportException
import technology.polygon.omswallet.internal.generated.waas.decodeWebRpcError
import technology.polygon.omswallet.models.AbiArg
import technology.polygon.omswallet.models.AccessGrant
import technology.polygon.omswallet.models.AccessGrantPage
import technology.polygon.omswallet.models.AccessGrantType
import technology.polygon.omswallet.models.AuthorizedRemoteAccess
import technology.polygon.omswallet.models.EncryptedWalletImportKeyMaterial
import technology.polygon.omswallet.models.FeeOption
import technology.polygon.omswallet.models.FeeOptionSelection
import technology.polygon.omswallet.models.FeeOptionSelector
import technology.polygon.omswallet.models.FeeOptionWithBalance
import technology.polygon.omswallet.models.FeeToken
import technology.polygon.omswallet.models.Page
import technology.polygon.omswallet.models.RemoteAccessSession
import technology.polygon.omswallet.models.RemoteCredentialMetadata
import technology.polygon.omswallet.models.SendTransactionRequest
import technology.polygon.omswallet.models.SendTransactionResponse
import technology.polygon.omswallet.models.SmartSessionGrant
import technology.polygon.omswallet.models.SmartSessionGrantUsage
import technology.polygon.omswallet.models.SolanaBalance
import technology.polygon.omswallet.models.TokenBalance
import technology.polygon.omswallet.models.TransactionMode
import technology.polygon.omswallet.models.TransactionStatus
import technology.polygon.omswallet.models.TransactionStatusPollingOptions
import technology.polygon.omswallet.models.TransactionStatusResolution
import technology.polygon.omswallet.models.TransactionStatusResponse
import technology.polygon.omswallet.models.TronBalance
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletCredential
import technology.polygon.omswallet.models.WalletImportCipherSuite
import technology.polygon.omswallet.models.WalletImportPrivateKey
import technology.polygon.omswallet.models.WalletImportRecipientKey
import technology.polygon.omswallet.models.WalletKeyOrigin
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.runOMSWalletOperation
import technology.polygon.omswallet.session.OMSWalletSessionExpiryBounds
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.session.OMSWalletSessionStateMachine
import technology.polygon.omswallet.session.SessionRevisionChangedException
import technology.polygon.omswallet.storage.InvalidSessionMetadataException
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.toOMSWalletException
import technology.polygon.omswallet.utils.OMSWalletIsoTimestamps
import technology.polygon.omswallet.utils.OMSWalletTimestamps
import technology.polygon.omswallet.utils.formatUnits
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import technology.polygon.omswallet.internal.generated.waas.AbiArg as WaasAbiArg
import technology.polygon.omswallet.internal.generated.waas.CredentialInfo as WaasCredentialInfo
import technology.polygon.omswallet.internal.generated.waas.CredentialMetadata as WaasCredentialMetadata
import technology.polygon.omswallet.internal.generated.waas.CredentialType as WaasCredentialType
import technology.polygon.omswallet.internal.generated.waas.FeeOption as WaasFeeOption
import technology.polygon.omswallet.internal.generated.waas.FeeOptionSelection as WaasFeeOptionSelection
import technology.polygon.omswallet.internal.generated.waas.FeeToken as WaasFeeToken
import technology.polygon.omswallet.internal.generated.waas.GrantUsage as WaasGrantUsage
import technology.polygon.omswallet.internal.generated.waas.KeyOrigin as WaasKeyOrigin
import technology.polygon.omswallet.internal.generated.waas.ListAccessResponse as WaasListAccessResponse
import technology.polygon.omswallet.internal.generated.waas.NetworkFamily as WaasNetworkFamily
import technology.polygon.omswallet.internal.generated.waas.Page as WaasPage
import technology.polygon.omswallet.internal.generated.waas.SessionInfo as WaasSessionInfo
import technology.polygon.omswallet.internal.generated.waas.TransactionMode as WaasTransactionMode
import technology.polygon.omswallet.internal.generated.waas.TransactionStatus as WaasTransactionStatus
import technology.polygon.omswallet.internal.generated.waas.TransactionStatusResponse as WaasTransactionStatusResponse
import technology.polygon.omswallet.internal.generated.waas.Wallet as WaasWallet
import technology.polygon.omswallet.internal.generated.waas.WalletType as WaasWalletType

private class PendingEmailAuth(
    val email: String,
    val sessionRevision: Long,
    val sessionLifetimeSeconds: UInt,
)

private class WalletScopeRuntime(
    publishableKey: String,
    val walletSession: OMSWalletSessionStateMachine,
    val sessionStore: OMSWalletSessionMetadataStore?,
    val oidcRedirectAuthStore: OidcRedirectAuthStore?,
    val signer: CredentialSigner,
    val sessionExpiryScheduler: SessionExpiryScheduler,
    val sessionExpiryDispatcher: SessionExpiryDispatcher,
    val credentialRefreshLauncher: CredentialRefreshLauncher,
    val now: () -> Long,
) {
    val lifecycleLock = Any()

    /**
     * The publishable key the scope's requests carry: the one of the latest client created for it.
     * A scope is the wallet API origin and project, so a rotated key for the same project keeps the
     * session, and requests from every client of the scope switch to the new key (an older key may
     * have been revoked). A request in flight keeps the key it was sent with.
     */
    @Volatile
    var publishableKey: String = publishableKey
    val sessionExpiredListeners = mutableSetOf<(OMSWalletSessionExpiredEvent) -> Unit>()
    var initialized = false
    var initializationCleanupPending = false
    var latestSessionExpiredEvent: OMSWalletSessionExpiredEvent? = null
    var latestSessionExpiredRevision: Long? = null
    var sessionExpiryTask: SessionExpiryTask? = null

    /** Cancels the restore read still waiting for the app to come to the foreground. */
    var cancelRestoredSessionRead: (() -> Unit)? = null
    var pendingEmailAuth: PendingEmailAuth? = null

    /** The single in-flight GetCredential refresh shared by concurrent callers. */
    var credentialRefresh: CredentialRefreshInFlight? = null

    /**
     * When a WaaS server last answered GetCredential with WebrpcBadRoute (no sliding expiry), or
     * null. Refreshes are skipped for [CREDENTIAL_REFRESH_UNSUPPORTED_COOLDOWN_MILLIS] after it, then
     * probed again, so one misrouted answer does not disable them for the life of the process.
     */
    var credentialRefreshUnsupportedAt: Long? = null

    /**
     * When this process last read the current credential's expiry: the send time of the sign-in
     * CompleteAuth or the completion time of the last successful GetCredential. Null after a
     * restore until a read succeeds; failed or skipped refreshes do not move it.
     */
    var credentialReadAt: Long? = null

    /**
     * When GetCredential was last sent, or the session signed in; paces background refreshes. Null
     * after a restore until the restore read reaches WaaS, so the next successful signed request reads.
     */
    var credentialRefreshAttemptedAt: Long? = null

    /**
     * When the last request signed with the session credential completed (response received or
     * error thrown), GetCredential included (except the expiry timer's check), whatever its
     * outcome: WaaS slid the expiry no later than that. A restore sets it to the restore time,
     * which bounds the previous process's requests.
     */
    var lastSessionRequestAt: Long? = null

    /** Completion time of the current or last GetCredential; the same timestamp as its [lastSessionRequestAt]. */
    var credentialReadCompletedAt: Long? = null

    /**
     * Whether the current or last GetCredential is the expiry timer's check, which is not recorded
     * as a request. Set before every GetCredential is sent; refreshes never overlap.
     */
    var expiryTimerReadInFlight = false

    /** Next offline retry delay after the expiry timer could not reach WaaS. */
    var credentialRetryDelayMillis: Long? = null

    /** Keeps credential refreshes from overlapping other signed session requests. */
    val signedRequestGate = SignedRequestGate()
}

/**
 * Gives the requests signed with one credential one turn at a time, first come first served;
 * credential refreshes (GetCredential) take turns the same way. Requests signed with different
 * credentials (a new sign-in creates a new key) do not wait for each other.
 *
 * WaaS accepts a credential's nonce only above the last one it accepted, with an atomic conditional
 * write (`:nonce > LastNonce`, waas v1.2.0 data/credential.go:741,837,894), so concurrent requests
 * that reach it out of order are rejected with 7206. A request draws its nonce from the credential's
 * single nonce store, signs, and is sent within its turn, so its nonce is above that of every
 * request that turned before it and none of them is rejected. Requests from other processes or
 * devices with the same credential are not ordered, which the 7206 resends still cover.
 */
private class SignedRequestGate {
    private class CredentialTurns {
        val turn = Mutex()

        /** Requests and refreshes holding or waiting for [turn]; the entry is dropped at zero. */
        var users = 0
    }

    private val lock = Any()
    private val turnsByCredential = mutableMapOf<String, CredentialTurns>()

    suspend fun <T> request(
        credentialId: String,
        deadline: SignedRequestDeadline,
        block: suspend () -> T,
    ): T {
        val turns = join(credentialId)
        try {
            var holdsTurn = false
            try {
                withTimeout(deadline.remainingMillisBeforeSend()) {
                    turns.turn.lock()
                    holdsTurn = true
                }
            } catch (timedOut: TimeoutCancellationException) {
                // A turn that arrived as the deadline passed is kept: the deadline check before
                // sending stops the request. Without the turn, nothing was taken and nothing is sent.
                if (!holdsTurn) {
                    throw SignedRequestNotSentException(TimeoutException("Signed request timed out waiting for its turn"))
                }
            }
            try {
                return block()
            } finally {
                turns.turn.unlock()
            }
        } finally {
            leave(credentialId, turns)
        }
    }

    /** Takes a turn for a refresh only when no request holds or waits for one; false when busy. */
    fun tryBeginRefresh(credentialId: String): Boolean =
        synchronized(lock) {
            val turns = turnsByCredential.getOrPut(credentialId) { CredentialTurns() }
            if (!turns.turn.tryLock()) return@synchronized false
            turns.users += 1
            true
        }

    /** Waits for the turns of the requests already waiting, then takes one for a refresh. */
    suspend fun beginRefresh(credentialId: String) {
        val turns = join(credentialId)
        try {
            turns.turn.lock()
        } catch (cancelled: CancellationException) {
            leave(credentialId, turns)
            throw cancelled
        }
    }

    fun endRefresh(credentialId: String) {
        val turns = synchronized(lock) { requireNotNull(turnsByCredential[credentialId]) }
        turns.turn.unlock()
        leave(credentialId, turns)
    }

    /** Requests and refreshes holding or waiting for a turn of [credentialId]. */
    fun users(credentialId: String): Int = synchronized(lock) { turnsByCredential[credentialId]?.users ?: 0 }

    private fun join(credentialId: String): CredentialTurns =
        synchronized(lock) {
            turnsByCredential.getOrPut(credentialId) { CredentialTurns() }.also { it.users += 1 }
        }

    private fun leave(
        credentialId: String,
        turns: CredentialTurns,
    ) {
        synchronized(lock) {
            turns.users -= 1
            if (turns.users == 0 && turnsByCredential[credentialId] === turns) turnsByCredential.remove(credentialId)
        }
    }
}

/**
 * A signed request failed before its HTTP call started (at the gate, while signing, or because its
 * deadline passed), so WaaS never received it.
 */
private class SignedRequestNotSentException(
    cause: Throwable,
) : Exception(cause.message, cause)

/** The one deadline of a signed request: its gate wait, signing, nonce resends, and HTTP calls. */
private class SignedRequestDeadline(
    timeoutMillis: Long,
) {
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    /**
     * The milliseconds left. Once none are, throws [SignedRequestNotSentException], so a request
     * whose signing finished after the deadline is never sent.
     */
    fun remainingMillisBeforeSend(): Long {
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMillis <= 0) {
            throw SignedRequestNotSentException(TimeoutException("Signed request timed out before it was sent"))
        }
        return remainingMillis
    }
}

private class CredentialRefreshInFlight(
    val revision: Long,
    val result: CompletableDeferred<CredentialRefreshOutcome>,
)

/** Result of reading the session credential's current expiry with GetCredential. */
private sealed interface CredentialRefreshOutcome {
    /** WaaS confirmed the credential and its expiry was applied to the session. */
    data object Refreshed : CredentialRefreshOutcome

    /** WaaS rejected the credential (7202/7203, or 7207 from GetCredential) and the session was ended. */
    data object Ended : CredentialRefreshOutcome

    /** The server has no GetCredential; local expiry stays authoritative. */
    data object Unsupported : CredentialRefreshOutcome

    /** WaaS could not be reached, failed, or returned an unparseable expiry; the session is unchanged. */
    data class Unavailable(
        val cause: Throwable,
    ) : CredentialRefreshOutcome

    /** The session changed while the refresh ran; its result was not applied. */
    data object Stale : CredentialRefreshOutcome

    /**
     * A background refresh was skipped because a signed session request was in flight, or the
     * refresh shared by this caller was cancelled.
     */
    data object Busy : CredentialRefreshOutcome
}

/** Control flow for a session check that found the session locally expired. */
private class LocallyExpiredSession(
    val revision: Long,
) : RuntimeException(null, null, false, false)

private object WalletScopeRuntimeRegistry {
    private val registryLock = Any()
    private val runtimes = mutableMapOf<String, WalletScopeRuntime>()

    fun runtimeFor(
        scopeKey: String,
        create: () -> WalletScopeRuntime,
    ): WalletScopeRuntime =
        synchronized(registryLock) {
            runtimes.getOrPut(scopeKey, create)
        }
}

private fun OmsRelayOidcProvider.relayPathComponent(): String =
    when {
        this === OmsRelayOidcProviders.google -> "google"
        this === OmsRelayOidcProviders.apple -> "apple"
        else -> error("Unsupported OMS relay OIDC provider")
    }

private const val DEFAULT_GOOGLE_OIDC_CLIENT_ID: String =
    "913882656162-7l4ofa0ou2hqo90umlkenhdop1f5inba.apps.googleusercontent.com"
private const val DEFAULT_APPLE_OIDC_CLIENT_ID: String = "service.oms.polygon.technology"

class WalletClient private constructor(
    private val publishableKey: String,
    private val projectId: String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient,
    private val runtime: WalletScopeRuntime,
    private val oidcNonceGenerator: () -> String,
    private val defaultStatusPolling: TransactionStatusPollingOptions,
    private val transactionStatusDelay: suspend (Long) -> Unit,
    private val walletImportTrustedPcr0s: Set<String>?,
    private val foregroundRunner: ForegroundRunner,
    private val signedRequestTimeoutMillis: Long,
) {
    companion object {
        /**
         * Default requested WaaS wallet session lifetime in seconds (180 days, the maximum).
         */
        const val DEFAULT_SESSION_LIFETIME_SECONDS: Long = 15_552_000L

        /**
         * Maximum requested WaaS wallet session lifetime in seconds (180 days). WaaS extends a
         * session's expiry with use up to the requested lifetime.
         */
        const val MAX_SESSION_LIFETIME_SECONDS: Long = 15_552_000L

        @JvmSynthetic
        internal fun create(
            publishableKey: String,
            projectId: String,
            environment: OMSWalletEnvironment,
            transport: OMSWalletHttpClient = OMSWalletHttpClient(),
            walletSession: OMSWalletSessionStateMachine? = null,
            sessionStore: OMSWalletSessionMetadataStore? = null,
            oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
            oidcNonceGenerator: () -> String = OidcRedirectAuth::generateNonce,
            credentialSigner: CredentialSigner? = null,
            defaultStatusPolling: TransactionStatusPollingOptions = TransactionStatusPollingOptions(),
            transactionStatusDelay: suspend (Long) -> Unit = { delay(it) },
            sessionExpiryScheduler: SessionExpiryScheduler = TimerSessionExpiryScheduler,
            sessionExpiryDispatcher: SessionExpiryDispatcher = AndroidMainThreadSessionExpiryDispatcher,
            credentialRefreshLauncher: CredentialRefreshLauncher = BackgroundCredentialRefreshLauncher,
            now: () -> Long = OMSWalletTimestamps::nowMilliseconds,
            projectScopeKey: String? = null,
            walletImportTrustedPcr0s: Set<String>? = null,
            foregroundRunner: ForegroundRunner = ImmediateForegroundRunner,
            signedRequestTimeoutMillis: Long = SIGNED_REQUEST_TIMEOUT_MILLIS,
        ): WalletClient {
            val createRuntime = {
                WalletScopeRuntime(
                    publishableKey = publishableKey,
                    walletSession = walletSession ?: OMSWalletSessionStateMachine(),
                    sessionStore = sessionStore,
                    oidcRedirectAuthStore = oidcRedirectAuthStore,
                    signer = credentialSigner ?: MissingCredentialSigner,
                    sessionExpiryScheduler = sessionExpiryScheduler,
                    sessionExpiryDispatcher = sessionExpiryDispatcher,
                    credentialRefreshLauncher = credentialRefreshLauncher,
                    now = now,
                )
            }
            val runtime =
                projectScopeKey?.let { scopeKey ->
                    WalletScopeRuntimeRegistry.runtimeFor(scopeKey, createRuntime)
                } ?: createRuntime()
            runtime.publishableKey = publishableKey
            return WalletClient(
                publishableKey = publishableKey,
                projectId = projectId,
                environment = environment,
                transport = transport,
                runtime = runtime,
                oidcNonceGenerator = oidcNonceGenerator,
                defaultStatusPolling = defaultStatusPolling,
                transactionStatusDelay = transactionStatusDelay,
                walletImportTrustedPcr0s = walletImportTrustedPcr0s,
                foregroundRunner = foregroundRunner,
                signedRequestTimeoutMillis = signedRequestTimeoutMillis,
            )
        }
    }

    private val walletSession: OMSWalletSessionStateMachine = runtime.walletSession
    private val sessionStore: OMSWalletSessionMetadataStore? = runtime.sessionStore
    private val oidcRedirectAuthStore: OidcRedirectAuthStore? = runtime.oidcRedirectAuthStore
    private val signer: CredentialSigner = runtime.signer
    private val gateway: WaasWalletGateway =
        WaasWalletGateway(
            publishableKey = { runtime.publishableKey },
            environment = environment,
            transport = transport,
            authorizeSignedRequest = ::authorizeSignedRequest,
            onSessionCredentialRejected = ::endSessionRejectedForCredential,
            onSessionCredentialAccepted = ::refreshCredentialIfDue,
            onSessionCredentialUnauthorized = ::launchCredentialRefresh,
            onSessionRequestCompleted = ::recordSessionRequestCompleted,
            signedRequestGate = runtime.signedRequestGate,
            signingCredentialId = ::signingCredentialId,
            walletImportTrustedPcr0s = walletImportTrustedPcr0s,
            signedRequestTimeoutMillis = signedRequestTimeoutMillis,
        )
    private val indexerClient: IndexerClient =
        IndexerClient.create(
            publishableKey = publishableKey,
            environment = environment,
            transport = transport,
        )
    internal val hasPendingSignIn: Boolean
        get() =
            synchronized(runtime.lifecycleLock) {
                val snapshot = walletSession.snapshot() ?: return@synchronized false
                snapshot.walletAddress.isNullOrBlank()
            }

    /**
     * The active wallet, or null until auth completes or a session is restored,
     * while a manual wallet selection is pending, and after sign-out or session
     * expiry. Same shape as [listWallets] entries;
     * branch on [Wallet.type] before using [Wallet.address] with family-specific
     * code.
     */
    val activeWallet: Wallet?
        get() = synchronized(runtime.lifecycleLock) { walletSession.snapshot()?.wallet }

    /**
     * Expiry and auth metadata for the active wallet session. Non-null exactly
     * when [activeWallet] is.
     *
     * Pending email OTP, OIDC redirect, and manual wallet-selection state are
     * intentionally not exposed here.
     */
    val session: OMSWalletSession?
        get() = synchronized(runtime.lifecycleLock) { walletSession.snapshot()?.toPublicSession() }

    /**
     * Registers a listener for expired wallet sessions.
     *
     * The latest expired-session event is replayed to new listeners until a new
     * auth flow, new wallet session, or [signOut] clears it. The returned
     * function unsubscribes the listener.
     */
    fun onSessionExpired(listener: (OMSWalletSessionExpiredEvent) -> Unit): () -> Unit {
        val replay =
            synchronized(runtime.lifecycleLock) {
                runtime.sessionExpiredListeners += listener
                runtime.latestSessionExpiredEvent?.let { event ->
                    runtime.latestSessionExpiredRevision?.let { revision -> event to revision }
                }
            }
        replay?.let { (event, revision) ->
            dispatchSessionExpiredListener(listener, event, revision)
        }
        return {
            synchronized(runtime.lifecycleLock) {
                runtime.sessionExpiredListeners -= listener
            }
        }
    }

    internal val signerAddress: String?
        get() = synchronized(runtime.lifecycleLock) { walletSession.snapshot()?.signerAddress }

    /**
     * Installs [snapshot] as the current session with its expiry read just now, as after sign-in.
     * Unlike [restorePersistedSession], it does not treat the expiry as possibly stale.
     */
    internal fun restoreSession(snapshot: OMSWalletSessionSnapshot) {
        synchronized(runtime.lifecycleLock) {
            runtime.initialized = true
            clearLatestSessionExpiredEventLocked()
            val revision = walletSession.restore(snapshot)
            markCredentialReadLocked(readAt = runtime.now())
            scheduleSessionExpiryLocked(snapshot, revision)
        }
    }

    internal fun snapshotSession(): OMSWalletSessionSnapshot? = synchronized(runtime.lifecycleLock) { walletSession.snapshot() }

    /** Signed requests and refreshes holding or waiting for a turn of the signer's credential. */
    internal fun signedRequestTurnUsers(): Int = runtime.signedRequestGate.users(signingCredentialId())

    /**
     * The credential whose turns a signed request takes: the signer's key, read before queueing, or
     * "" before a sign-in creates one.
     */
    private fun signingCredentialId(): String = runCatching { signer.existingCredentialId() }.getOrNull().orEmpty()

    internal fun restorePersistedSession(): Boolean {
        var expiredNotification: ExpiryNotification? = null
        var restoredRevision: Long? = null
        val restored =
            synchronized(runtime.lifecycleLock) {
                if (runtime.initialized) {
                    return@synchronized walletSession.snapshot()?.walletId?.isNotBlank() == true
                }
                if (runtime.initializationCleanupPending) {
                    clearInvalidPersistedSessionLocked()
                    runtime.initializationCleanupPending = false
                    runtime.initialized = true
                    return@synchronized false
                }
                val snapshot =
                    try {
                        sessionStore?.load()
                    } catch (throwable: InvalidSessionMetadataException) {
                        runtime.initializationCleanupPending = true
                        clearInvalidPersistedSessionLocked()
                        runtime.initializationCleanupPending = false
                        runtime.initialized = true
                        return@synchronized false
                    } catch (throwable: Throwable) {
                        throw OMSWalletStorageException(
                            message = "Failed to restore wallet session",
                            cause = throwable,
                        )
                    }
                if (snapshot == null) {
                    clearConsumedOidcRedirectAuthLocked()
                    runtime.initialized = true
                    return@synchronized false
                }
                if (snapshot.isExpired(runtime.now())) {
                    walletSession.clear()
                    clearSessionExpiryTaskLocked()
                    runCatching { oidcRedirectAuthStore?.clear() }
                    runCatching(signer::clear)
                    expiredNotification = recordSessionExpiredLocked(snapshot, walletSession.revision())
                    runtime.initialized = true
                    return@synchronized false
                }
                val existingSignerAddress =
                    try {
                        signer.existingCredentialId()
                    } catch (throwable: Throwable) {
                        throw OMSWalletStorageException(
                            message = "Failed to restore wallet session",
                            cause = throwable,
                        )
                    }
                val isRestorable =
                    snapshot.wallet != null &&
                        !snapshot.expiresAt.isNullOrBlank() &&
                        !snapshot.signerAddress.isNullOrBlank() &&
                        snapshot.auth != null &&
                        snapshot.signerKeyType == signer.signingAlgorithm &&
                        existingSignerAddress == snapshot.signerAddress
                if (!isRestorable) {
                    runtime.initializationCleanupPending = true
                    clearInvalidPersistedSessionLocked()
                    runtime.initializationCleanupPending = false
                    runtime.initialized = true
                    return@synchronized false
                }
                clearLatestSessionExpiredEventLocked()
                val revision = walletSession.restore(snapshot)
                markSessionRestoredLocked()
                scheduleSessionExpiryLocked(snapshot, revision)
                clearConsumedOidcRedirectAuthLocked()
                runtime.initialized = true
                restoredRevision = revision
                true
            }
        expiredNotification?.let(::dispatchSessionExpiredNotification)
        // The stored expiry may predate server-side sliding; read the current one in the background.
        // The read extends the session, so a process started in the background waits for the app to be used.
        restoredRevision?.let(::refreshRestoredSessionInForeground)
        return restored
    }

    private fun refreshRestoredSessionInForeground(restoredRevision: Long) {
        try {
            val cancel =
                foregroundRunner.runInForeground {
                    val current =
                        synchronized(runtime.lifecycleLock) {
                            runtime.cancelRestoredSessionRead = null
                            walletSession.revision() == restoredRevision
                        }
                    if (current) {
                        launchCredentialRefresh(restoredRevision) { outcome ->
                            if (outcome is CredentialRefreshOutcome.Unavailable || outcome == CredentialRefreshOutcome.Busy) {
                                // Leave the stored expiry due for a read on the next successful signed request.
                                synchronized(runtime.lifecycleLock) {
                                    if (walletSession.revision() == restoredRevision) runtime.credentialRefreshAttemptedAt = null
                                }
                            }
                        }
                    }
                }
            // Ending the session cancels the wait, so a process that never shows UI does not keep
            // this client registered with the Application.
            val stillCurrent =
                synchronized(runtime.lifecycleLock) {
                    (walletSession.revision() == restoredRevision).also { current ->
                        if (current) runtime.cancelRestoredSessionRead = cancel
                    }
                }
            if (!stillCurrent) cancel()
        } catch (_: Throwable) {
            // The restore read is best effort; local expiry stays in effect.
        }
    }

    private fun cancelRestoredSessionReadLocked() {
        runtime.cancelRestoredSessionRead.also { runtime.cancelRestoredSessionRead = null }?.invoke()
    }

    private fun clearInvalidPersistedSessionLocked() {
        clearSessionUnlocked(
            clearOidcRedirectAuth = true,
            operation = null,
            throwOnFailure = true,
            requiredSessionRevision = null,
        )
    }

    private fun clearConsumedOidcRedirectAuthLocked() {
        runCatching {
            if (oidcRedirectAuthStore?.load()?.consumed == true) {
                oidcRedirectAuthStore.clear()
            }
        }
    }

    fun signOut() {
        clearSession(
            clearOidcRedirectAuth = true,
            operation = OMSWalletOperation.WalletSignOut,
        )
    }

    private fun clearSession(
        clearOidcRedirectAuth: Boolean,
        operation: OMSWalletOperation? = null,
        throwOnFailure: Boolean = true,
        requiredSessionRevision: Long? = null,
    ): Long? =
        synchronized(runtime.lifecycleLock) {
            runtime.initialized = true
            clearSessionUnlocked(
                clearOidcRedirectAuth = clearOidcRedirectAuth,
                operation = operation,
                throwOnFailure = throwOnFailure,
                requiredSessionRevision = requiredSessionRevision,
            )
        }

    private fun clearSessionUnlocked(
        clearOidcRedirectAuth: Boolean,
        operation: OMSWalletOperation?,
        throwOnFailure: Boolean,
        requiredSessionRevision: Long?,
    ): Long? {
        if (!walletSession.clear(requiredSessionRevision)) {
            return null
        }
        runtime.pendingEmailAuth = null
        val clearedRevision = walletSession.revision()
        clearSessionExpiryTaskLocked()
        cancelRestoredSessionReadLocked()

        var cleanupFailure: Throwable? = null

        fun attemptCleanup(block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                cleanupFailure?.addSuppressed(throwable) ?: run { cleanupFailure = throwable }
            }
        }

        attemptCleanup { sessionStore?.clear() }
        if (clearOidcRedirectAuth) {
            attemptCleanup { oidcRedirectAuthStore?.clear() }
        }
        attemptCleanup(signer::clear)
        clearLatestSessionExpiredEventLocked()

        if (throwOnFailure) {
            cleanupFailure?.let { failure ->
                throw OMSWalletStorageException(
                    operation = operation,
                    message = "Failed to clear all wallet session data",
                    cause = failure,
                )
            }
        }
        return clearedRevision
    }

    private fun clearSessionAfterFailure(
        clearOidcRedirectAuth: Boolean = true,
        requiredSessionRevision: Long? = null,
    ) {
        clearSession(
            clearOidcRedirectAuth = clearOidcRedirectAuth,
            throwOnFailure = false,
            requiredSessionRevision = requiredSessionRevision,
        )
    }

    private fun saveNewPendingOidcRedirectAuth(
        store: OidcRedirectAuthStore,
        pending: PendingOidcRedirectAuth,
        requiredSessionRevision: Long,
    ) {
        synchronized(runtime.lifecycleLock) {
            walletSession.requireRevision(requiredSessionRevision)
            store.save(pending)
        }
    }

    private fun loadPendingOidcRedirectAuth(store: OidcRedirectAuthStore): PendingOidcRedirectAuth? =
        synchronized(runtime.lifecycleLock) {
            store.load()
        }

    private fun consumeOidcRedirectAuth(
        store: OidcRedirectAuthStore,
        pending: PendingOidcRedirectAuth,
    ): Boolean =
        synchronized(runtime.lifecycleLock) {
            val flowIdentifier = pending.flowIdentifier
            val current = store.load()
            if (
                current?.flowIdentifier != flowIdentifier ||
                current.consumed
            ) {
                false
            } else {
                store.save(current.copy(consumed = true))
                true
            }
        }

    private fun <T> withOidcRedirectAuthOwnership(
        pending: PendingOidcRedirectAuth,
        block: () -> T,
    ): T =
        synchronized(runtime.lifecycleLock) {
            val current = oidcRedirectAuthStore?.load()
            check(current?.flowIdentifier == pending.flowIdentifier && current.consumed) {
                "OIDC redirect auth flow is stale"
            }
            block()
        }

    private fun clearSessionAfterOidcRedirectFailure(
        pending: PendingOidcRedirectAuth,
        requiredSessionRevision: Long,
    ) {
        runCatching {
            withOidcRedirectAuthOwnership(pending) {
                clearSessionAfterFailure(
                    clearOidcRedirectAuth = false,
                    requiredSessionRevision = requiredSessionRevision,
                )
            }
        }
    }

    private fun clearPendingOidcRedirectAuthBestEffort(
        store: OidcRedirectAuthStore,
        pending: PendingOidcRedirectAuth,
    ) {
        runCatching {
            synchronized(runtime.lifecycleLock) {
                if (store.load()?.flowIdentifier == pending.flowIdentifier) {
                    store.clear()
                }
            }
        }
    }

    suspend fun startEmailAuth(
        email: String,
        sessionLifetimeSeconds: Long = DEFAULT_SESSION_LIFETIME_SECONDS,
    ): Unit =
        runOMSWalletOperation(OMSWalletOperation.WalletStartEmailAuth) {
            val validatedSessionLifetimeSeconds =
                requireWaasSessionLifetimeSeconds(sessionLifetimeSeconds)
            var ownedSessionRevision =
                checkNotNull(clearSession(clearOidcRedirectAuth = true)) {
                    "Unable to start wallet auth"
                }
            try {
                val response = gateway.commitEmailVerifier(email, ownedSessionRevision)

                ownedSessionRevision =
                    synchronized(runtime.lifecycleLock) {
                        walletSession.requireRevision(ownedSessionRevision)
                        val pendingSessionRevision =
                            walletSession.replaceForPendingAuth(
                                challenge = response.challenge,
                                verifier = response.verifier,
                                signerAddress = checkNotNull(signer.existingCredentialId()),
                                signerKeyType = signer.signingAlgorithm,
                                requiredRevision = ownedSessionRevision,
                            )
                        runtime.pendingEmailAuth =
                            PendingEmailAuth(
                                email = email,
                                sessionRevision = pendingSessionRevision,
                                sessionLifetimeSeconds = validatedSessionLifetimeSeconds,
                            )
                        pendingSessionRevision
                    }
            } catch (throwable: CancellationException) {
                clearSessionAfterFailure(requiredSessionRevision = ownedSessionRevision)
                throw throwable
            } catch (throwable: Throwable) {
                clearSessionAfterFailure(requiredSessionRevision = ownedSessionRevision)
                throw throwable
            }
        }

    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = WalletType.Ethereum,
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
        sessionLifetimeSeconds: Long = DEFAULT_SESSION_LIFETIME_SECONDS,
        provider: String? = null,
        providerLabel: String? = null,
    ): CompleteAuthResult =
        runOMSWalletOperation(OMSWalletOperation.WalletSignInWithOidcIdToken) {
            completeOidcIdTokenAuth(
                idToken = idToken,
                issuer = issuer,
                audience = audience,
                walletType = walletType,
                walletSelection = walletSelection,
                provider = provider,
                providerLabel = providerLabel,
                sessionLifetimeSeconds =
                    requireWaasSessionLifetimeSeconds(
                        sessionLifetimeSeconds,
                    ),
            )
        }

    private suspend fun completeOidcIdTokenAuth(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType,
        walletSelection: WalletSelectionBehavior,
        provider: String?,
        providerLabel: String?,
        sessionLifetimeSeconds: UInt,
    ): CompleteAuthResult {
        var ownedSessionRevision =
            checkNotNull(clearSession(clearOidcRedirectAuth = true)) {
                "Unable to start wallet auth"
            }
        try {
            val response =
                gateway.commitOidcIdTokenVerifier(
                    idToken = idToken,
                    issuer = issuer,
                    audience = audience,
                    requiredSessionRevision = ownedSessionRevision,
                )

            ownedSessionRevision =
                synchronized(runtime.lifecycleLock) {
                    walletSession.requireRevision(ownedSessionRevision)
                    walletSession.replaceForPendingAuth(
                        challenge = response.challenge,
                        verifier = response.verifier,
                        signerAddress = checkNotNull(signer.existingCredentialId()),
                        signerKeyType = signer.signingAlgorithm,
                        requiredRevision = ownedSessionRevision,
                    )
                }

            val signIn =
                completeOidcIdTokenSignIn(
                    idToken = idToken,
                    sessionLifetimeSeconds = sessionLifetimeSeconds,
                    requiredSessionRevision = ownedSessionRevision,
                )
            return completeWalletAuth(
                signIn = signIn,
                walletType = walletType,
                walletSelection = walletSelection,
                sessionAuth =
                    oidcIdTokenSessionAuth(
                        issuer = issuer,
                        provider = provider,
                        providerLabel = providerLabel,
                        completeAuth = signIn.auth,
                    ),
                requiredSessionRevision = ownedSessionRevision,
                onSessionRevisionChanged = { ownedSessionRevision = it },
            )
        } catch (throwable: CancellationException) {
            clearSessionAfterFailure(requiredSessionRevision = ownedSessionRevision)
            throw throwable
        } catch (throwable: Throwable) {
            clearSessionAfterFailure(requiredSessionRevision = ownedSessionRevision)
            throw throwable
        }
    }

    suspend fun startOidcRedirectAuth(
        provider: OmsRelayOidcProvider,
        omsRelayReturnUri: String,
        walletType: WalletType = WalletType.Ethereum,
        walletSelection: WalletSelectionBehavior? = null,
        sessionLifetimeSeconds: Long? = null,
        loginHint: String? = null,
    ): StartOidcRedirectAuthResult {
        require(omsRelayReturnUri.isNotBlank()) { "omsRelayReturnUri must not be blank" }
        val redirectUris =
            OidcRedirectUris(
                oauthRedirectUri = derivedRelayRedirectUri(provider.relayPathComponent()),
                expectedCallbackUri = omsRelayReturnUri,
                stateRedirectUri = omsRelayReturnUri,
            )
        return when (provider.relayPathComponent()) {
            "google" -> {
                startOidcRedirectAuth(
                    issuer = GOOGLE_ISSUER,
                    clientId = DEFAULT_GOOGLE_OIDC_CLIENT_ID,
                    authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                    providerName = "google",
                    providerLabel = "Google",
                    scopes = listOf("openid", "email", "profile"),
                    providerAuthorizeParams =
                        mapOf(
                            "access_type" to "offline",
                            "prompt" to "consent",
                        ),
                    authMode = OidcAuthMode.AuthCodePKCE,
                    redirectUris = redirectUris,
                    walletType = walletType,
                    walletSelection = walletSelection,
                    sessionLifetimeSeconds = sessionLifetimeSeconds,
                    authorizeParams = emptyMap(),
                    loginHint = loginHint,
                )
            }

            "apple" -> {
                startOidcRedirectAuth(
                    issuer = APPLE_ISSUER,
                    clientId = DEFAULT_APPLE_OIDC_CLIENT_ID,
                    authorizationUrl = "https://appleid.apple.com/auth/authorize",
                    providerName = "apple",
                    providerLabel = "Apple",
                    scopes = listOf("openid", "email"),
                    providerAuthorizeParams = mapOf("response_mode" to "form_post"),
                    authMode = OidcAuthMode.AuthCodePKCE,
                    redirectUris = redirectUris,
                    walletType = walletType,
                    walletSelection = walletSelection,
                    sessionLifetimeSeconds = sessionLifetimeSeconds,
                    authorizeParams = emptyMap(),
                    loginHint = loginHint,
                )
            }

            else -> {
                error("Unsupported OMS relay OIDC provider")
            }
        }
    }

    suspend fun startOidcRedirectAuth(
        provider: CustomOidcProviderConfig,
        walletType: WalletType = WalletType.Ethereum,
        walletSelection: WalletSelectionBehavior? = null,
        sessionLifetimeSeconds: Long? = null,
        loginHint: String? = null,
        authorizeParams: Map<String, String> = emptyMap(),
    ): StartOidcRedirectAuthResult {
        require(provider.providerRedirectUri.isNotBlank()) { "providerRedirectUri must not be blank" }
        return startOidcRedirectAuth(
            issuer = provider.issuer,
            clientId = provider.clientId,
            authorizationUrl = provider.authorizationUrl,
            providerName = provider.provider,
            providerLabel = provider.providerLabel,
            scopes = provider.scopes,
            providerAuthorizeParams = provider.authorizeParams,
            authMode = provider.authMode,
            redirectUris =
                OidcRedirectUris(
                    oauthRedirectUri = provider.providerRedirectUri,
                    expectedCallbackUri = provider.providerRedirectUri,
                    stateRedirectUri = null,
                ),
            walletType = walletType,
            walletSelection = walletSelection,
            sessionLifetimeSeconds = sessionLifetimeSeconds,
            authorizeParams = authorizeParams,
            loginHint = loginHint,
        )
    }

    private suspend fun startOidcRedirectAuth(
        issuer: String,
        clientId: String,
        authorizationUrl: String,
        providerName: String?,
        providerLabel: String?,
        scopes: List<String>,
        providerAuthorizeParams: Map<String, String>,
        authMode: OidcAuthMode,
        redirectUris: OidcRedirectUris,
        walletType: WalletType,
        walletSelection: WalletSelectionBehavior?,
        sessionLifetimeSeconds: Long?,
        authorizeParams: Map<String, String>,
        loginHint: String?,
    ): StartOidcRedirectAuthResult =
        runOMSWalletOperation(OMSWalletOperation.WalletStartOidcRedirectAuth) {
            val redirectAuthStore =
                requireNotNull(oidcRedirectAuthStore) {
                    "OIDC redirect auth requires an OIDC redirect auth store"
                }
            val previousSessionEmail =
                synchronized(runtime.lifecycleLock) { walletSession.snapshot()?.auth?.email }
            val requestedSessionLifetimeSeconds =
                sessionLifetimeSeconds?.also {
                    requireWaasSessionLifetimeSeconds(it)
                }
            var ownedSessionRevision =
                checkNotNull(clearSession(clearOidcRedirectAuth = true)) {
                    "Unable to start wallet auth"
                }
            try {
                val response =
                    gateway.commitOidcRedirectVerifier(
                        issuer = issuer,
                        clientId = clientId,
                        redirectUri = redirectUris.oauthRedirectUri,
                        authMode =
                            when (authMode) {
                                OidcAuthMode.AuthCode -> AuthMode.AuthCode
                                OidcAuthMode.AuthCodePKCE -> AuthMode.AuthCodePKCE
                            },
                        requiredSessionRevision = ownedSessionRevision,
                    )
                val nonce = oidcNonceGenerator()
                val state =
                    OidcRedirectAuth.encodeState(
                        nonce = nonce,
                        scope = projectId,
                        redirectUri = redirectUris.stateRedirectUri,
                    )

                ownedSessionRevision =
                    synchronized(runtime.lifecycleLock) {
                        walletSession.requireRevision(ownedSessionRevision)
                        walletSession.replaceForPendingAuth(
                            challenge = response.challenge,
                            verifier = response.verifier,
                            signerAddress = checkNotNull(signer.existingCredentialId()),
                            signerKeyType = signer.signingAlgorithm,
                            requiredRevision = ownedSessionRevision,
                        )
                    }
                val signerAddress =
                    synchronized(runtime.lifecycleLock) {
                        walletSession.requireRevision(ownedSessionRevision)
                        checkNotNull(signer.existingCredentialId())
                    }
                val pending =
                    PendingOidcRedirectAuth(
                        verifier = response.verifier,
                        challenge = response.challenge,
                        nonce = nonce,
                        authMode = authMode,
                        redirectUri = redirectUris.expectedCallbackUri,
                        issuer = issuer,
                        provider = providerName ?: builtInOidcProviderForIssuer(issuer),
                        providerLabel = providerLabel ?: builtInOidcProviderLabelForIssuer(issuer),
                        projectId = projectId,
                        walletType = walletType.wireValue,
                        walletSelection = walletSelection,
                        sessionLifetimeSeconds = requestedSessionLifetimeSeconds,
                        signerAddress = signerAddress,
                        signerKeyType = signer.signingAlgorithm,
                    )
                try {
                    saveNewPendingOidcRedirectAuth(
                        store = redirectAuthStore,
                        pending = pending,
                        requiredSessionRevision = ownedSessionRevision,
                    )
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    throw OMSWalletStorageException(
                        operation = OMSWalletOperation.WalletStartOidcRedirectAuth,
                        message = "OIDC redirect auth state persistence failed",
                        cause = throwable,
                    )
                }
                try {
                    walletSession.requireRevision(ownedSessionRevision)
                } catch (throwable: Throwable) {
                    clearPendingOidcRedirectAuthBestEffort(redirectAuthStore, pending)
                    throw throwable
                }

                val authorizationUrl =
                    OidcRedirectAuth.buildAuthorizationUrl(
                        authorizationUrl = authorizationUrl,
                        clientId = clientId,
                        scopes = scopes,
                        redirectUri = redirectUris.oauthRedirectUri,
                        state = state,
                        challenge = response.challenge,
                        usePkce = authMode.usesPkce,
                        loginHint = loginHintForProvider(issuer, loginHint ?: previousSessionEmail),
                        authorizeParams = providerAuthorizeParams + authorizeParams,
                    )

                StartOidcRedirectAuthResult(
                    authorizationUrl = authorizationUrl,
                )
            } catch (throwable: CancellationException) {
                clearSessionAfterFailure(requiredSessionRevision = ownedSessionRevision)
                throw throwable
            } catch (throwable: Throwable) {
                clearSessionAfterFailure(requiredSessionRevision = ownedSessionRevision)
                throw throwable
            }
        }

    suspend fun handleOidcRedirectCallback(
        callbackUrl: String?,
        walletSelection: WalletSelectionBehavior? = null,
        sessionLifetimeSeconds: Long? = null,
    ): OidcRedirectAuthResult =
        runOMSWalletOperation(OMSWalletOperation.WalletHandleOidcRedirectCallback) {
            if (callbackUrl.isNullOrBlank()) {
                return@runOMSWalletOperation OidcRedirectAuthResult.NotOidcRedirectCallback
            }

            val callback = OidcRedirectAuth.parseCallbackUrl(callbackUrl)
            if (!callback.hasOidcResponse) {
                return@runOMSWalletOperation OidcRedirectAuthResult.NotOidcRedirectCallback
            }

            val redirectAuthStore = oidcRedirectAuthStore ?: return@runOMSWalletOperation OidcRedirectAuthResult.NoPendingAuth
            val pending =
                loadPendingOidcRedirectAuth(redirectAuthStore)
                    ?: return@runOMSWalletOperation OidcRedirectAuthResult.NoPendingAuth
            if (!OidcRedirectAuth.matchesRedirectUri(callbackUrl, pending.redirectUri)) {
                return@runOMSWalletOperation OidcRedirectAuthResult.NotOidcRedirectCallback
            }

            val state = callback.state ?: return@runOMSWalletOperation OidcRedirectAuthResult.NotOidcRedirectCallback
            val stateMatches =
                runCatching {
                    OidcRedirectAuth.validateState(state, pending)
                }.isSuccess
            if (!stateMatches) {
                return@runOMSWalletOperation OidcRedirectAuthResult.NotOidcRedirectCallback
            }
            val consumed =
                try {
                    consumeOidcRedirectAuth(redirectAuthStore, pending)
                } catch (throwable: Throwable) {
                    throw OMSWalletStorageException(
                        operation = OMSWalletOperation.WalletHandleOidcRedirectCallback,
                        message = "OIDC redirect auth state consumption failed",
                        cause = throwable,
                    )
                }
            if (!consumed) {
                return@runOMSWalletOperation OidcRedirectAuthResult.NoPendingAuth
            }
            var ownedSessionRevision =
                synchronized(runtime.lifecycleLock) { walletSession.revision() }

            try {
                callback.error?.let { error ->
                    throw OMSWalletValidationException(
                        message = callback.errorDescription ?: "OIDC provider returned error: $error",
                    )
                }
                val code = requireNotNull(callback.code) { "OIDC callback URL is missing code" }
                ownedSessionRevision = restorePendingOidcRedirectAuth(pending, ownedSessionRevision)
                val resolvedWalletSelection =
                    walletSelection
                        ?: pending.walletSelection
                        ?: WalletSelectionBehavior.Automatic
                val validatedSessionLifetimeSeconds =
                    requireWaasSessionLifetimeSeconds(
                        sessionLifetimeSeconds
                            ?: pending.sessionLifetimeSeconds
                            ?: DEFAULT_SESSION_LIFETIME_SECONDS,
                    )

                val sentAt = runtime.now()
                val auth =
                    gateway.completeOidcRedirectAuth(
                        verifier = pending.verifier,
                        code = code,
                        authMode =
                            when (pending.authMode) {
                                OidcAuthMode.AuthCode -> AuthMode.AuthCode
                                OidcAuthMode.AuthCodePKCE -> AuthMode.AuthCodePKCE
                            },
                        sessionLifetimeSeconds = validatedSessionLifetimeSeconds,
                        requiredSessionRevision = ownedSessionRevision,
                    )
                OidcRedirectAuthResult.Completed(
                    result =
                        completeWalletAuth(
                            signIn = SignInCompletion(auth, sentAt, validatedSessionLifetimeSeconds),
                            walletType = pending.walletType.toWalletType(),
                            walletSelection = resolvedWalletSelection,
                            sessionAuth = oidcRedirectSessionAuth(pending = pending, completeAuth = auth),
                            requiredSessionRevision = ownedSessionRevision,
                            oidcRedirectAuthOwnership = pending,
                            onSessionRevisionChanged = { ownedSessionRevision = it },
                        ),
                )
            } catch (throwable: CancellationException) {
                clearSessionAfterOidcRedirectFailure(pending, ownedSessionRevision)
                throw throwable
            } catch (throwable: Throwable) {
                val failure = throwable.toOMSWalletException(OMSWalletOperation.WalletHandleOidcRedirectCallback)
                clearSessionAfterOidcRedirectFailure(pending, ownedSessionRevision)
                throw failure
            } finally {
                clearPendingOidcRedirectAuthBestEffort(redirectAuthStore, pending)
            }
        }

    private suspend fun completeEmailSignIn(
        code: String,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): SignInCompletion {
        val snapshot =
            try {
                synchronized(runtime.lifecycleLock) {
                    walletSession.requireRevision(requiredSessionRevision)
                    walletSession.requirePendingAuth()
                }
            } catch (throwable: IllegalStateException) {
                throw OMSWalletSessionException(
                    operation = OMSWalletOperation.WalletCompleteEmailAuth,
                    message = "No pending email auth attempt",
                    cause = throwable,
                )
            }
        val sentAt = runtime.now()
        val auth =
            gateway.completeEmailAuth(
                verifier = snapshot.verifier,
                challenge = snapshot.challenge,
                code = code,
                sessionLifetimeSeconds = sessionLifetimeSeconds,
                requiredSessionRevision = requiredSessionRevision,
            )
        return SignInCompletion(auth, sentAt, sessionLifetimeSeconds)
    }

    private suspend fun completeOidcIdTokenSignIn(
        idToken: String,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): SignInCompletion {
        val snapshot =
            synchronized(runtime.lifecycleLock) {
                walletSession.requireRevision(requiredSessionRevision)
                walletSession.requirePendingAuth()
            }
        val sentAt = runtime.now()
        val auth =
            gateway.completeOidcIdTokenAuth(
                verifier = snapshot.verifier,
                idToken = idToken,
                sessionLifetimeSeconds = sessionLifetimeSeconds,
                requiredSessionRevision = requiredSessionRevision,
            )
        return SignInCompletion(auth, sentAt, sessionLifetimeSeconds)
    }

    suspend fun completeEmailAuth(
        code: String,
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
        walletType: WalletType = WalletType.Ethereum,
    ): CompleteAuthResult =
        runOMSWalletOperation(OMSWalletOperation.WalletCompleteEmailAuth) {
            val requiredSessionRevision =
                synchronized(runtime.lifecycleLock) {
                    walletSession.revision()
                }
            val pendingEmailAuth =
                synchronized(runtime.lifecycleLock) {
                    runtime.pendingEmailAuth
                        ?.takeIf { it.sessionRevision == requiredSessionRevision }
                        ?: throw OMSWalletSessionException(
                            operation = OMSWalletOperation.WalletCompleteEmailAuth,
                            message = "No pending email auth attempt",
                        )
                }
            val signIn =
                completeEmailSignIn(
                    code = code,
                    sessionLifetimeSeconds = pendingEmailAuth.sessionLifetimeSeconds,
                    requiredSessionRevision = requiredSessionRevision,
                )
            val result =
                completeWalletAuth(
                    signIn = signIn,
                    walletType = walletType,
                    walletSelection = walletSelection,
                    sessionAuth = OMSWalletEmailSessionAuth(email = signIn.auth.email ?: pendingEmailAuth.email),
                    requiredSessionRevision = requiredSessionRevision,
                )
            synchronized(runtime.lifecycleLock) {
                if (runtime.pendingEmailAuth === pendingEmailAuth) {
                    runtime.pendingEmailAuth = null
                }
            }
            result
        }

    /**
     * Selects an existing wallet by its WaaS wallet id.
     */
    suspend fun useWallet(walletId: String): WalletActivationResult =
        runOMSWalletOperation(OMSWalletOperation.WalletUseWallet) {
            useWalletForCurrentSession(walletId, requireWalletSelectionOrActiveSession())
        }

    private suspend fun useWalletForCurrentSession(
        walletId: String,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): WalletActivationResult =
        activateWalletForCurrentSession(requiredSessionRevision, oidcRedirectAuthOwnership, onSessionRevisionChanged) {
            requestUseWallet(walletId, requiredSessionRevision)
        }

    /**
     * Claims a preallocated wallet offered in [CompleteAuthResult.WalletSelected.claimableWallets] or
     * [PendingWalletSelection.claimableWallets] and selects it, like [useWallet]. Claiming links the
     * wallet to the signed-in identity, so it is listed with the owned wallets afterwards. During a
     * pending manual selection, prefer [PendingWalletSelection.claimAndSelectWallet].
     */
    suspend fun claimWallet(walletId: String): WalletActivationResult =
        runOMSWalletOperation(OMSWalletOperation.WalletClaimWallet) {
            claimWalletForCurrentSession(walletId, requireWalletSelectionOrActiveSession())
        }

    private suspend fun claimWalletForCurrentSession(
        walletId: String,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): WalletActivationResult =
        activateWalletForCurrentSession(requiredSessionRevision, oidcRedirectAuthOwnership, onSessionRevisionChanged) {
            gateway.claimWallet(walletId, requiredSessionRevision)
        }

    /** Runs [requestWallet] and selects and persists the wallet it returns as the active wallet. */
    private suspend fun activateWalletForCurrentSession(
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth?,
        onSessionRevisionChanged: ((Long) -> Unit)?,
        requestWallet: suspend () -> Wallet,
    ): WalletActivationResult {
        requireWalletSelectionOrActiveSession(requiredSessionRevision)
        val wallet = requestWallet()
        return withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
            val selectedSessionRevision =
                walletSession.selectWallet(
                    wallet = wallet,
                    requiredRevision = requiredSessionRevision,
                )
            onSessionRevisionChanged?.invoke(selectedSessionRevision)
            persistSelectedWallet(wallet, selectedSessionRevision)
        }
    }

    /**
     * Creates and selects a new wallet for the authenticated user.
     */
    suspend fun createWallet(
        walletType: WalletType = WalletType.Ethereum,
        reference: String? = null,
    ): WalletActivationResult =
        runOMSWalletOperation(OMSWalletOperation.WalletCreateWallet) {
            createWalletForCurrentSession(
                walletType,
                reference,
                requireWalletSelectionOrActiveSession(),
            )
        }

    /** Imports and activates an Ethereum, Solana, or Tron private key through the attested import transport. */
    suspend fun importWallet(
        privateKey: WalletImportPrivateKey,
        reference: String? = null,
    ): WalletActivationResult =
        runOMSWalletOperation(OMSWalletOperation.WalletImportWallet) {
            val context = walletImportActivationContext(privateKey.walletType)
            WalletImportCrypto.validateReference(reference)
            val plaintext = WalletImportCrypto.plaintext(privateKey)
            try {
                val recipient =
                    gateway.getWalletImportRecipientKey(
                        WalletImportCipherSuite.P256Sha256Aes256Gcm,
                        context.revision,
                    )
                val sealed =
                    WalletImportCrypto.sealP256Aes256Gcm(
                        WalletImportBase64.decodeCanonical(recipient.publicKey, "recipient publicKey"),
                        plaintext,
                    )
                val wallet =
                    gateway.importWallet(
                        walletType = privateKey.walletType,
                        keyMaterial =
                            EncryptedWalletImportKeyMaterial(
                                keyId = recipient.keyId,
                                cipherSuite = recipient.cipherSuite,
                                encapsulatedKey = WalletImportBase64.encode(sealed.first),
                                ciphertext = WalletImportBase64.encode(sealed.second),
                            ),
                        reference = reference,
                        requiredSessionRevision = context.revision,
                    )
                activateImportedWallet(wallet, context)
            } finally {
                plaintext.fill(0)
            }
        }

    /** Fetches an attested recipient key for caller-managed wallet-import encryption. */
    suspend fun getWalletImportRecipientKey(cipherSuite: WalletImportCipherSuite): WalletImportRecipientKey =
        runOMSWalletOperation(OMSWalletOperation.WalletGetImportRecipientKey) {
            val revision = requireWalletSelectionOrActiveSession()
            gateway.getWalletImportRecipientKey(cipherSuite, revision).also {
                requireWalletSelectionOrActiveSession(revision)
            }
        }

    /** Imports and activates caller-encrypted private-key material. */
    suspend fun importEncryptedWallet(
        walletType: WalletType,
        keyMaterial: EncryptedWalletImportKeyMaterial,
        reference: String? = null,
    ): WalletActivationResult =
        runOMSWalletOperation(OMSWalletOperation.WalletImportEncryptedWallet) {
            val context = walletImportActivationContext(walletType)
            WalletImportCrypto.validateReference(reference)
            val wallet = gateway.importWallet(walletType, keyMaterial, reference, context.revision)
            activateImportedWallet(wallet, context)
        }

    private suspend fun walletImportActivationContext(walletType: WalletType): WalletImportActivationContext {
        val revision = requireWalletSelectionOrActiveSession()
        return synchronized(runtime.lifecycleLock) {
            val snapshot = walletSession.requireSnapshot(revision)
            val pendingId = snapshot.pendingWalletSelectionId
            if (pendingId != null) {
                require(snapshot.pendingWalletType == walletType) {
                    "Pending wallet selection requires a ${snapshot.pendingWalletType?.wireValue} wallet"
                }
                WalletImportActivationContext.Pending(
                    id = pendingId,
                    signerAddress = requireNotNull(snapshot.signerAddress),
                    signerKeyType = snapshot.signerKeyType,
                    revision = revision,
                )
            } else {
                WalletImportActivationContext.Active(
                    walletId =
                        snapshot.walletId?.takeIf(String::isNotBlank)
                            ?: throw OMSWalletSessionException(message = "No wallet selected"),
                    revision = revision,
                )
            }
        }
    }

    private fun activateImportedWallet(
        wallet: Wallet,
        context: WalletImportActivationContext,
    ): WalletActivationResult =
        synchronized(runtime.lifecycleLock) {
            walletSession.requireRevision(context.revision)
            val selectedRevision =
                when (context) {
                    is WalletImportActivationContext.Active -> {
                        check(walletSession.requireSnapshot().walletId == context.walletId) { "Active wallet session changed" }
                        walletSession.selectWallet(wallet, context.revision)
                    }

                    is WalletImportActivationContext.Pending -> {
                        walletSession.selectWalletForPendingSelection(
                            pendingWalletSelectionId = context.id,
                            signerAddress = context.signerAddress,
                            signerKeyType = context.signerKeyType,
                            wallet = wallet,
                        )
                    }
                }
            persistSelectedWallet(wallet, selectedRevision)
        }

    private suspend fun createWalletForCurrentSession(
        walletType: WalletType,
        reference: String?,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): WalletActivationResult =
        activateWalletForCurrentSession(requiredSessionRevision, oidcRedirectAuthOwnership, onSessionRevisionChanged) {
            requestCreateWallet(walletType, reference, requiredSessionRevision)
        }

    private suspend fun useWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletId: String,
    ): WalletActivationResult =
        activateWalletForPendingSelection(pendingWalletSelectionId, signerAddress, signerKeyType) { requiredSessionRevision ->
            requestUseWallet(walletId, requiredSessionRevision)
        }

    private suspend fun claimWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletId: String,
    ): WalletActivationResult =
        activateWalletForPendingSelection(pendingWalletSelectionId, signerAddress, signerKeyType) { requiredSessionRevision ->
            gateway.claimWallet(walletId, requiredSessionRevision)
        }

    /** Runs [requestWallet] and completes the pending selection with the wallet it returns. */
    private suspend fun activateWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        requestWallet: suspend (requiredSessionRevision: Long) -> Wallet,
    ): WalletActivationResult {
        val requiredSessionRevision =
            requirePendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
        val wallet = requestWallet(requiredSessionRevision)
        return synchronized(runtime.lifecycleLock) {
            walletSession.requireRevision(requiredSessionRevision)
            val selectedSessionRevision =
                walletSession.selectWalletForPendingSelection(
                    pendingWalletSelectionId = pendingWalletSelectionId,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    wallet = wallet,
                )
            persistSelectedWallet(wallet, selectedSessionRevision)
        }
    }

    private suspend fun createWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletType: WalletType,
        reference: String?,
    ): WalletActivationResult =
        activateWalletForPendingSelection(pendingWalletSelectionId, signerAddress, signerKeyType) { requiredSessionRevision ->
            requestCreateWallet(walletType, reference, requiredSessionRevision)
        }

    private suspend fun requirePendingWalletSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
    ): Long =
        refreshingLocalExpiry(OMSWalletOperation.PendingWalletSelection) { expireLocally ->
            checkPendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType, expireLocally)
        }

    private fun checkPendingWalletSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        expireLocally: Boolean,
    ): Long {
        var expiredNotification: ExpiryNotification? = null
        val revision =
            synchronized(runtime.lifecycleLock) {
                val snapshot = walletSession.snapshot()
                if (snapshot?.isExpired(runtime.now()) == true) {
                    if (!expireLocally) throw LocallyExpiredSession(walletSession.revision())
                    expiredNotification = expireSessionLocked(snapshot, walletSession.revision())
                    return@synchronized null
                }
                try {
                    walletSession.requirePendingWalletSelection(
                        pendingWalletSelectionId = pendingWalletSelectionId,
                        signerAddress = signerAddress,
                        signerKeyType = signerKeyType,
                    )
                    val currentRevision = walletSession.revision()
                    requireActiveCredentialLocked(currentRevision)
                    currentRevision
                } catch (throwable: IllegalStateException) {
                    throw OMSWalletSelectionException(
                        code = OMSWalletErrorCode.WalletSelectionStale,
                        operation = OMSWalletOperation.PendingWalletSelection,
                        message = throwable.message ?: "Pending wallet selection is no longer active",
                        cause = throwable,
                    )
                }
            }
        expiredNotification?.let(::dispatchSessionExpiredNotification)
        return revision
            ?: throw OMSWalletSessionException(
                code = OMSWalletErrorCode.SessionExpired,
                operation = OMSWalletOperation.PendingWalletSelection,
                message = "Wallet session expired",
            )
    }

    private suspend fun requestUseWallet(
        walletId: String,
        requiredSessionRevision: Long,
    ): Wallet = gateway.useWallet(walletId, requiredSessionRevision)

    private suspend fun requestCreateWallet(
        walletType: WalletType,
        reference: String?,
        requiredSessionRevision: Long,
    ): Wallet = gateway.createWallet(walletType, reference, requiredSessionRevision)

    private fun persistSelectedWallet(
        wallet: Wallet,
        selectedSessionRevision: Long,
    ): WalletActivationResult =
        synchronized(runtime.lifecycleLock) {
            try {
                persistCurrentSession(selectedSessionRevision)
                WalletActivationResult(wallet = wallet)
            } catch (throwable: Throwable) {
                clearSessionAfterFailure(requiredSessionRevision = selectedSessionRevision)
                throw throwable
            }
        }

    /**
     * Lists all wallets available to the authenticated credential.
     */
    suspend fun listWallets(): List<Wallet> =
        runOMSWalletOperation(OMSWalletOperation.WalletListWallets) {
            val requiredSessionRevision = requireWalletSelectionOrActiveSession()
            val wallets = mutableListOf<Wallet>()
            var cursor: String? = null
            do {
                val response =
                    gateway.listWallets(cursor, requiredSessionRevision)
                wallets += response.wallets
                cursor = response.nextCursor
            } while (cursor != null)
            wallets
        }

    private suspend fun walletsFromAuthResponse(
        completeAuth: WalletAuthCompletion,
        requiredSessionRevision: Long,
    ): List<Wallet> {
        val wallets = completeAuth.wallets.toMutableList()
        var cursor = completeAuth.nextWalletsCursor
        while (cursor != null) {
            val response = gateway.listWallets(cursor, requiredSessionRevision)
            wallets += response.wallets
            cursor = response.nextCursor
        }
        return wallets
    }

    private suspend fun completeWalletAuth(
        signIn: SignInCompletion,
        walletType: WalletType,
        walletSelection: WalletSelectionBehavior,
        sessionAuth: OMSWalletSessionAuth,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): CompleteAuthResult {
        val completeAuth = signIn.auth
        val (pendingWalletSelectionId, pendingSessionRevision) =
            withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
                walletSession
                    .markAuthVerified(
                        expiresAt = completeAuth.credential.expiresAt,
                        auth = sessionAuth,
                        walletType = walletType,
                        expiryBounds = signIn.expiryBounds(),
                        requiredRevision = requiredSessionRevision,
                    ).also { (_, revision) -> onSessionRevisionChanged?.invoke(revision) }
            }
        val pendingSnapshot =
            withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
                walletSession.requireRevision(pendingSessionRevision)
                walletSession.requireSnapshot().also {
                    markCredentialReadLocked(readAt = signIn.sentAt)
                    scheduleSessionExpiryLocked(it, pendingSessionRevision)
                }
            }
        val pendingSignerAddress = requireNotNull(pendingSnapshot.signerAddress) { "No active signer" }
        val pendingSignerKeyType = pendingSnapshot.signerKeyType
        return try {
            val wallets = walletsFromAuthResponse(completeAuth, pendingSessionRevision)
            withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
                walletSession.requireRevision(pendingSessionRevision)
            }
            if (walletSelection == WalletSelectionBehavior.Manual) {
                CompleteAuthResult.WalletSelection(
                    pendingSelection =
                        PendingWalletSelection(
                            walletType = walletType,
                            wallets = wallets.filter { it.type == walletType },
                            claimableWallets = completeAuth.claimableWallets.filter { it.type == walletType },
                            credential = completeAuth.credential,
                            selectWalletAction = { walletId ->
                                useWalletForPendingSelection(
                                    pendingWalletSelectionId = pendingWalletSelectionId,
                                    signerAddress = pendingSignerAddress,
                                    signerKeyType = pendingSignerKeyType,
                                    walletId = walletId,
                                )
                            },
                            createAndSelectWalletAction = { reference ->
                                createWalletForPendingSelection(
                                    pendingWalletSelectionId = pendingWalletSelectionId,
                                    signerAddress = pendingSignerAddress,
                                    signerKeyType = pendingSignerKeyType,
                                    walletType = walletType,
                                    reference = reference,
                                )
                            },
                            claimAndSelectWalletAction = { walletId ->
                                claimWalletForPendingSelection(
                                    pendingWalletSelectionId = pendingWalletSelectionId,
                                    signerAddress = pendingSignerAddress,
                                    signerKeyType = pendingSignerKeyType,
                                    walletId = walletId,
                                )
                            },
                        ),
                )
            } else {
                // Owned wallet first, then a claimable (preallocated) one, then a new wallet.
                val ownedWallet = wallets.firstOrNull { it.type == walletType }
                val claimableWallet =
                    if (ownedWallet == null) completeAuth.claimableWallets.firstOrNull { it.type == walletType } else null
                val selected =
                    when {
                        ownedWallet != null -> {
                            useWalletForCurrentSession(
                                walletId = ownedWallet.id,
                                requiredSessionRevision = pendingSessionRevision,
                                oidcRedirectAuthOwnership = oidcRedirectAuthOwnership,
                                onSessionRevisionChanged = onSessionRevisionChanged,
                            )
                        }

                        claimableWallet != null -> {
                            claimWalletForCurrentSession(
                                walletId = claimableWallet.id,
                                requiredSessionRevision = pendingSessionRevision,
                                oidcRedirectAuthOwnership = oidcRedirectAuthOwnership,
                                onSessionRevisionChanged = onSessionRevisionChanged,
                            )
                        }

                        else -> {
                            createWalletForCurrentSession(
                                walletType = walletType,
                                reference = null,
                                requiredSessionRevision = pendingSessionRevision,
                                oidcRedirectAuthOwnership = oidcRedirectAuthOwnership,
                                onSessionRevisionChanged = onSessionRevisionChanged,
                            )
                        }
                    }
                CompleteAuthResult.WalletSelected(
                    wallet = selected.wallet,
                    wallets = if (ownedWallet == null) wallets + selected.wallet else wallets,
                    credential = completeAuth.credential,
                    claimableWallets = completeAuth.claimableWallets.filter { it.id != claimableWallet?.id },
                )
            }
        } catch (throwable: CancellationException) {
            if (oidcRedirectAuthOwnership == null) {
                clearSessionAfterFailure(requiredSessionRevision = pendingSessionRevision)
            }
            throw throwable
        } catch (throwable: Throwable) {
            if (oidcRedirectAuthOwnership == null) {
                clearSessionAfterFailure(requiredSessionRevision = pendingSessionRevision)
            }
            throw throwable
        }
    }

    private fun <T> withOptionalOidcRedirectAuthOwnership(
        pending: PendingOidcRedirectAuth?,
        block: () -> T,
    ): T =
        synchronized(runtime.lifecycleLock) {
            if (pending == null) {
                block()
            } else {
                withOidcRedirectAuthOwnership(pending, block)
            }
        }

    private suspend fun restorePendingOidcRedirectAuth(
        pending: PendingOidcRedirectAuth,
        requiredSessionRevision: Long,
    ): Long =
        withOidcRedirectAuthOwnership(pending) {
            walletSession.requireRevision(requiredSessionRevision)
            val currentSignerAddress = signer.existingCredentialId()
            check(currentSignerAddress != null && currentSignerAddress == pending.signerAddress) {
                "OIDC redirect auth signer mismatch"
            }
            check(pending.signerKeyType == signer.signingAlgorithm) {
                "OIDC redirect auth signer mismatch"
            }
            walletSession.restore(
                OMSWalletSessionSnapshot(
                    challenge = pending.challenge,
                    verifier = pending.verifier,
                    signerAddress = pending.signerAddress,
                    signerKeyType = pending.signerKeyType,
                ),
                requiredRevision = requiredSessionRevision,
            )
        }

    private fun persistCurrentSession(requiredSessionRevision: Long) {
        synchronized(runtime.lifecycleLock) {
            val snapshot = walletSession.requireSnapshot(requiredSessionRevision)
            clearLatestSessionExpiredEventLocked()
            try {
                sessionStore?.save(snapshot)
            } catch (throwable: Throwable) {
                throw OMSWalletStorageException(
                    message = "Failed to persist wallet session",
                    cause = throwable,
                )
            }
            scheduleSessionExpiryLocked(snapshot, requiredSessionRevision)
        }
    }

    /**
     * Signs [message] with the currently selected wallet on [network].
     */
    suspend fun signMessage(
        network: Network,
        message: String,
    ): String =
        runOMSWalletOperation(OMSWalletOperation.WalletSignMessage) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSignMessage)
            activeSession.requireWalletType(WalletType.Ethereum)
            gateway.signMessage(
                walletId = activeSession.walletId,
                network = network,
                message = message,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /** Signs [message] with the currently selected Solana wallet. */
    suspend fun signSolanaMessage(message: String): String =
        runOMSWalletOperation(OMSWalletOperation.WalletSignSolanaMessage) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSignSolanaMessage)
            activeSession.requireWalletType(WalletType.Solana)
            gateway.signSolanaMessage(
                walletId = activeSession.walletId,
                message = message,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Signs EIP-712 [typedData] with the currently selected wallet on [network].
     */
    suspend fun signTypedData(
        network: Network,
        typedData: JsonElement,
    ): String =
        runOMSWalletOperation(OMSWalletOperation.WalletSignTypedData) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSignTypedData)
            activeSession.requireWalletType(WalletType.Ethereum)
            gateway.signTypedData(
                walletId = activeSession.walletId,
                network = network,
                typedData = typedData,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Validates [signature] for [message] through the WaaS public wallet RPC.
     * Pass the `0x` [walletAddress] to verify any Ethereum wallet without a
     * session; when it is omitted, the active Ethereum wallet's address is used.
     */
    suspend fun isValidMessageSignature(
        network: Network,
        message: String,
        signature: String,
        walletAddress: String? = null,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidMessageSignature) {
            gateway.isValidMessageSignature(
                walletAddress = verifierWalletAddress(walletAddress, WalletType.Ethereum, OMSWalletOperation.WalletIsValidMessageSignature),
                network = network,
                message = message,
                signature = signature,
            )
        }

    /**
     * Validates [signature] for a Solana [message] through the WaaS public wallet
     * RPC. Pass the base58 [walletAddress] to verify any Solana wallet without a
     * session; when it is omitted, the active Solana wallet's address is used.
     */
    suspend fun isValidSolanaMessageSignature(
        message: String,
        signature: String,
        walletAddress: String? = null,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidSolanaMessageSignature) {
            gateway.isValidSolanaMessageSignature(
                walletAddress =
                    verifierWalletAddress(
                        walletAddress,
                        WalletType.Solana,
                        OMSWalletOperation.WalletIsValidSolanaMessageSignature,
                    ),
                message = message,
                signature = signature,
            )
        }

    /**
     * Validates [signature] for EIP-712 [typedData] through the WaaS public
     * wallet RPC. Pass the `0x` [walletAddress] to verify any Ethereum wallet
     * without a session; when it is omitted, the active Ethereum wallet's address
     * is used.
     */
    suspend fun isValidTypedDataSignature(
        network: Network,
        typedData: JsonElement,
        signature: String,
        walletAddress: String? = null,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidTypedDataSignature) {
            gateway.isValidTypedDataSignature(
                walletAddress =
                    verifierWalletAddress(
                        walletAddress,
                        WalletType.Ethereum,
                        OMSWalletOperation.WalletIsValidTypedDataSignature,
                    ),
                network = network,
                typedData = typedData,
                signature = signature,
            )
        }

    /** Signs [message] with the currently selected Tron wallet. */
    suspend fun signTronMessage(message: String): String =
        runOMSWalletOperation(OMSWalletOperation.WalletSignTronMessage) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSignTronMessage)
            activeSession.requireWalletType(WalletType.Tron)
            gateway.signTronMessage(
                walletId = activeSession.walletId,
                message = message,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Signs TIP-712 [typedData] with the currently selected Tron wallet. Address
     * values may be Base58Check (`T…`).
     */
    suspend fun signTronTypedData(typedData: JsonElement): String =
        runOMSWalletOperation(OMSWalletOperation.WalletSignTronTypedData) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSignTronTypedData)
            activeSession.requireWalletType(WalletType.Tron)
            gateway.signTronTypedData(
                walletId = activeSession.walletId,
                typedData = typedData,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Validates a Tron [signature] for [message] through the WaaS public wallet
     * RPC. Pass the Base58Check [walletAddress] (`T…`) to verify any Tron wallet
     * without a session; when it is omitted, the active Tron wallet's address is
     * used.
     */
    suspend fun isValidTronMessageSignature(
        message: String,
        signature: String,
        walletAddress: String? = null,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidTronMessageSignature) {
            gateway.isValidTronMessageSignature(
                walletAddress = verifierWalletAddress(walletAddress, WalletType.Tron, OMSWalletOperation.WalletIsValidTronMessageSignature),
                message = message,
                signature = signature,
            )
        }

    /**
     * Validates a Tron [signature] for TIP-712 [typedData] through the WaaS
     * public wallet RPC. Pass the Base58Check [walletAddress] (`T…`) to verify
     * any Tron wallet without a session; when it is omitted, the active Tron
     * wallet's address is used.
     */
    suspend fun isValidTronTypedDataSignature(
        typedData: JsonElement,
        signature: String,
        walletAddress: String? = null,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidTronTypedDataSignature) {
            gateway.isValidTronTypedDataSignature(
                walletAddress =
                    verifierWalletAddress(
                        walletAddress,
                        WalletType.Tron,
                        OMSWalletOperation.WalletIsValidTronTypedDataSignature,
                    ),
                typedData = typedData,
                signature = signature,
            )
        }

    /**
     * Returns the address to verify against: [walletAddress] when provided (it
     * must not be blank), otherwise the active wallet's address.
     */
    private suspend fun verifierWalletAddress(
        walletAddress: String?,
        walletType: WalletType,
        operation: OMSWalletOperation,
    ): String {
        if (walletAddress != null) {
            require(walletAddress.isNotBlank()) { "walletAddress must not be empty" }
            return walletAddress
        }
        return activeVerifierWalletAddress(walletType, operation)
    }

    /**
     * Returns the active wallet's address for signature verification. Requires an
     * active session (not a usable credential) and rejects a wallet of another
     * family before any request.
     */
    private suspend fun activeVerifierWalletAddress(
        walletType: WalletType,
        operation: OMSWalletOperation,
    ): String {
        val activeSession = requireActiveWalletSession(operation, requireCredential = false)
        activeSession.requireWalletType(walletType)
        return activeSession.walletAddress
    }

    /**
     * Sends a transaction from the currently selected wallet on [network].
     *
     * This overload sends [value] to [to] without calldata. [mode] defaults to
     * [TransactionMode.Relayer].
     */
    suspend fun sendTransaction(
        network: Network,
        to: String,
        value: BigInteger,
        mode: TransactionMode = TransactionMode.Relayer,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        sendTransaction(
            network = network,
            request =
                SendTransactionRequest(
                    to = to,
                    value = value,
                    mode = mode,
                ),
            selectFeeOption = selectFeeOption,
            waitForStatus = waitForStatus,
            statusPolling = statusPolling,
        )

    /**
     * Sends a transaction from the currently selected wallet on [network].
     *
     * If the prepared transaction returns fee options, [selectFeeOption] is
     * called before execution. When no selector is provided, the first required
     * fee option is used, or no fee option when the transaction is sponsored.
     */
    suspend fun sendTransaction(
        network: Network,
        request: SendTransactionRequest,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletSendTransaction) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSendTransaction)
            activeSession.requireWalletType(WalletType.Ethereum)
            require(request.value.signum() >= 0) { "Transaction value must be non-negative" }
            val prepared =
                gateway.prepareEthereumTransaction(
                    walletId = activeSession.walletId,
                    network = network,
                    request = request,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                enrichFeeOptions = { feeOptions -> enrichFeeOptionsWithBalances(network, activeSession.walletAddress, feeOptions) },
                prepared = prepared,
                requiredSessionRevision = activeSession.revision,
                selectFeeOption = selectFeeOption,
                waitForStatus = waitForStatus,
                statusPolling = statusPolling,
            )
        }

    /** Sends a native SOL or SPL token transfer from the selected Solana wallet. */
    suspend fun sendSolanaTransfer(
        network: SolanaNetwork,
        asset: String,
        to: String,
        amount: BigInteger,
        mode: TransactionMode = TransactionMode.Relayer,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletSendSolanaTransfer) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSendSolanaTransfer)
            activeSession.requireWalletType(WalletType.Solana)
            require(amount.signum() >= 0) { "Transfer amount must be non-negative" }
            val prepared =
                gateway.prepareSolanaTransfer(
                    walletId = activeSession.walletId,
                    network = network,
                    asset = asset,
                    to = to,
                    amount = amount,
                    mode = mode,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                enrichFeeOptions = { feeOptions -> enrichSolanaFeeOptionsWithBalances(network, activeSession.walletAddress, feeOptions) },
                prepared = prepared,
                requiredSessionRevision = activeSession.revision,
                selectFeeOption = selectFeeOption,
                waitForStatus = waitForStatus,
                statusPolling = statusPolling,
            )
        }

    /**
     * Sends a Tron transaction from the selected Tron wallet. Tron wallets are
     * EOAs and always execute in native mode.
     *
     * [to] is a Base58Check address (`T…`) and [value] is in sun
     * (1 TRX = 1,000,000 sun). Omitting [data] sends a plain TRX transfer; any
     * [data] value, including `"0x"`, makes the transaction a contract call, and
     * `"0x"` calls the contract's payable fallback.
     */
    suspend fun sendTronTransaction(
        network: TronNetwork,
        to: String,
        value: BigInteger = BigInteger.ZERO,
        data: String? = null,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletSendTronTransaction) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletSendTronTransaction)
            activeSession.requireWalletType(WalletType.Tron)
            require(value.signum() >= 0) { "Transaction value must be non-negative" }
            val prepared =
                gateway.prepareTronTransaction(
                    walletId = activeSession.walletId,
                    network = network,
                    to = to,
                    value = value,
                    data = data,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                enrichFeeOptions = { feeOptions -> enrichTronFeeOptionsWithBalances(network, activeSession.walletAddress, feeOptions) },
                prepared = prepared,
                requiredSessionRevision = activeSession.revision,
                selectFeeOption = selectFeeOption,
                waitForStatus = waitForStatus,
                statusPolling = statusPolling,
                refusedBeforeSubmission = TRON_EXECUTE_REFUSED_BEFORE_SUBMISSION,
            )
        }

    /**
     * Calls a state-changing Tron contract function from the selected Tron
     * wallet in native mode. The wallet service ABI-encodes [args];
     * address-typed arguments accept Base58Check (`T…`) addresses.
     *
     * [contractAddress] is a Base58Check address. [method] is the function name
     * only, such as `"transfer"`; the wallet service builds the signature from
     * the [args] types.
     */
    suspend fun callTronContract(
        network: TronNetwork,
        contractAddress: String,
        method: String,
        args: List<AbiArg>? = null,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletCallTronContract) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletCallTronContract)
            activeSession.requireWalletType(WalletType.Tron)
            requireContractMethodName(method)
            val prepared =
                gateway.prepareTronContractCall(
                    walletId = activeSession.walletId,
                    network = network,
                    contract = contractAddress,
                    method = method,
                    args = args,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                enrichFeeOptions = { feeOptions -> enrichTronFeeOptionsWithBalances(network, activeSession.walletAddress, feeOptions) },
                prepared = prepared,
                requiredSessionRevision = activeSession.revision,
                selectFeeOption = selectFeeOption,
                waitForStatus = waitForStatus,
                statusPolling = statusPolling,
                refusedBeforeSubmission = TRON_EXECUTE_REFUSED_BEFORE_SUBMISSION,
            )
        }

    /**
     * Calls a state-changing smart contract function through the WaaS
     * prepare/execute flow.
     *
     * [method] is the function name only, such as `"transfer"`; the wallet
     * service builds the signature from the [args] types.
     */
    suspend fun callContract(
        network: Network,
        contractAddress: String,
        method: String,
        args: List<AbiArg>? = null,
        mode: TransactionMode = TransactionMode.Relayer,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletCallContract) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletCallContract)
            activeSession.requireWalletType(WalletType.Ethereum)
            requireContractMethodName(method)
            val prepared =
                gateway.prepareEthereumContractCall(
                    walletId = activeSession.walletId,
                    network = network,
                    contract = contractAddress,
                    method = method,
                    args = args,
                    mode = mode,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                enrichFeeOptions = { feeOptions -> enrichFeeOptionsWithBalances(network, activeSession.walletAddress, feeOptions) },
                prepared = prepared,
                requiredSessionRevision = activeSession.revision,
                selectFeeOption = selectFeeOption,
                waitForStatus = waitForStatus,
                statusPolling = statusPolling,
            )
        }

    /**
     * Returns the current WaaS execution status for a prepared or submitted
     * transaction.
     */
    suspend fun getTransactionStatus(txnId: String): TransactionStatusResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletGetTransactionStatus) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletGetTransactionStatus)
            gateway.transactionStatus(txnId, activeSession.revision)
        }

    /** Returns display metadata for a remote credential before the owner approves access. */
    suspend fun inspectRemoteCredential(credentialId: String): RemoteCredentialMetadata =
        runOMSWalletOperation(OMSWalletOperation.WalletInspectRemoteCredential) {
            require(credentialId.isNotBlank()) { "credentialId is required" }
            gateway.inspectRemoteCredential(projectId, credentialId)
        }

    /**
     * Authorizes owner-approved EVM smart-session grants for a remote credential. Omit [sessionId]
     * to create a session; pass an existing session ID to replace that session's grants and
     * requested expiry without changing its signer. WaaS caps the effective expiry at the remote
     * credential's expiry.
     */
    suspend fun authorizeRemoteAccess(
        credentialId: String,
        network: Network,
        grants: List<SmartSessionGrant>,
        expiresAt: String,
        sessionId: String? = null,
    ): AuthorizedRemoteAccess =
        runOMSWalletOperation(OMSWalletOperation.WalletAuthorizeRemoteAccess) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletAuthorizeRemoteAccess)
            activeSession.requireWalletType(WalletType.Ethereum)
            require(grants.isNotEmpty()) { "At least one grant is required" }
            val result =
                gateway.authorizeRemoteAccess(
                    walletId = activeSession.walletId,
                    credentialId = credentialId,
                    network = network,
                    grants = grants,
                    expiresAt = expiresAt,
                    sessionId = sessionId,
                    requiredSessionRevision = activeSession.revision,
                )
            val current = requireActiveWalletSession(OMSWalletOperation.WalletAuthorizeRemoteAccess)
            check(current.walletId == activeSession.walletId && current.revision == activeSession.revision) {
                "Active wallet session changed"
            }
            result
        }

    /** Returns all access grants, following WaaS cursors with the requested [pageSize]. */
    suspend fun listAccess(
        pageSize: UInt? = null,
        type: AccessGrantType? = null,
    ): List<AccessGrant> =
        runOMSWalletOperation(OMSWalletOperation.WalletListAccess) {
            val grants = mutableListOf<AccessGrant>()
            listAccessPages(pageSize = pageSize, type = type).collect { response ->
                grants += response.grants
            }
            grants
        }

    /**
     * Emits credential-access pages for the selected wallet until WaaS stops
     * returning a cursor.
     */
    fun listAccessPages(
        pageSize: UInt? = null,
        type: AccessGrantType? = null,
    ): Flow<AccessGrantPage> =
        flow {
            val activeSession =
                runOMSWalletOperation(OMSWalletOperation.WalletListAccessPages) {
                    requireActiveWalletSession(OMSWalletOperation.WalletListAccessPages)
                }
            var cursor: String? = null
            do {
                val response =
                    runOMSWalletOperation(OMSWalletOperation.WalletListAccessPages) {
                        requestListAccessPage(
                            pageSize = pageSize,
                            cursor = cursor,
                            type = type,
                            activeSession = activeSession,
                        )
                    }
                emit(response)
                cursor = response.page?.cursor?.takeIf { it.isNotBlank() }
            } while (cursor != null)
        }

    /**
     * Returns one credential-access page for the selected wallet.
     */
    suspend fun listAccessPage(
        pageSize: UInt? = null,
        cursor: String? = null,
        type: AccessGrantType? = null,
    ): AccessGrantPage =
        runOMSWalletOperation(OMSWalletOperation.WalletListAccessPage) {
            requestListAccessPage(
                pageSize,
                cursor,
                type,
                requireActiveWalletSession(OMSWalletOperation.WalletListAccessPage),
            )
        }

    private suspend fun requestListAccessPage(
        pageSize: UInt?,
        cursor: String?,
        type: AccessGrantType?,
        activeSession: ActiveWalletSession,
    ): AccessGrantPage =
        gateway.listAccessPage(
            walletId = activeSession.walletId,
            page = accessPage(pageSize, cursor),
            type = type,
            requiredSessionRevision = activeSession.revision,
        )

    /** Returns one owner-visible smart-session and checks that it belongs to the active wallet. */
    suspend fun getRemoteAccessSession(sessionId: String): RemoteAccessSession =
        runOMSWalletOperation(OMSWalletOperation.WalletGetRemoteAccessSession) {
            require(sessionId.isNotBlank()) { "sessionId is required" }
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletGetRemoteAccessSession)
            val session = gateway.getRemoteAccessSession(sessionId, activeSession.revision)
            require(session.walletId == activeSession.walletId) {
                "Session does not belong to the active wallet"
            }
            session
        }

    /** Returns grant usage for an owner-visible smart session on [network]. */
    suspend fun getRemoteAccessSessionUsage(
        sessionId: String,
        network: Network,
    ): List<SmartSessionGrantUsage> =
        runOMSWalletOperation(OMSWalletOperation.WalletGetRemoteAccessSessionUsage) {
            require(sessionId.isNotBlank()) { "sessionId is required" }
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletGetRemoteAccessSessionUsage)
            gateway.getRemoteAccessSessionUsage(
                sessionId = sessionId,
                network = network,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Returns an ID token for the currently selected wallet.
     */
    suspend fun getIdToken(
        ttlSeconds: UInt? = null,
        customClaims: Map<String, JsonElement>? = null,
    ): String =
        runOMSWalletOperation(OMSWalletOperation.WalletGetIdToken) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletGetIdToken)
            gateway.getIdToken(
                walletId = activeSession.walletId,
                ttlSeconds = ttlSeconds,
                customClaims = customClaims,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Revokes one access grant from the selected wallet. Use [listAccess] or [listAccessPage] to
     * find the direct or remote grant. Omit [sessionId] for a direct grant; for a remote grant,
     * pass its session ID to revoke exactly that session.
     */
    suspend fun revokeAccess(
        credentialId: String,
        sessionId: String? = null,
    ): Unit =
        runOMSWalletOperation(OMSWalletOperation.WalletRevokeAccess) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletRevokeAccess)
            gateway.revokeAccess(
                walletId = activeSession.walletId,
                targetCredentialId = credentialId,
                sessionId = sessionId,
                requiredSessionRevision = activeSession.revision,
            )
        }

    private suspend fun requireActiveWalletSession(
        operation: OMSWalletOperation?,
        requireCredential: Boolean = true,
    ): ActiveWalletSession =
        refreshingLocalExpiry(operation) { expireLocally ->
            checkActiveWalletSession(operation, requireCredential, expireLocally)
        }

    private fun checkActiveWalletSession(
        operation: OMSWalletOperation?,
        requireCredential: Boolean,
        expireLocally: Boolean,
    ): ActiveWalletSession {
        var expiredNotification: ExpiryNotification? = null
        val activeSession =
            synchronized(runtime.lifecycleLock) {
                val snapshot =
                    walletSession.snapshot()
                        ?: throw OMSWalletSessionException(operation = operation)
                val revision = walletSession.revision()
                if (snapshot.isExpired(runtime.now())) {
                    if (!expireLocally) throw LocallyExpiredSession(revision)
                    expiredNotification = expireSessionLocked(snapshot, revision)
                    return@synchronized null
                }
                val wallet =
                    snapshot.wallet
                        ?: throw OMSWalletSessionException(operation = operation, message = "No wallet selected")
                if (requireCredential) {
                    requireActiveCredentialLocked(revision, snapshot)
                }
                ActiveWalletSession(wallet, revision)
            }
        expiredNotification?.let(::dispatchSessionExpiredNotification)
        return activeSession
            ?: throw OMSWalletSessionException(
                code = OMSWalletErrorCode.SessionExpired,
                operation = operation,
                message = "Wallet session expired",
            )
    }

    private suspend fun requireWalletSelectionOrActiveSession(requiredSessionRevision: Long? = null): Long =
        refreshingLocalExpiry(operation = null) { expireLocally ->
            checkWalletSelectionOrActiveSession(requiredSessionRevision, expireLocally)
        }

    private fun checkWalletSelectionOrActiveSession(
        requiredSessionRevision: Long?,
        expireLocally: Boolean,
    ): Long {
        var expiredNotification: ExpiryNotification? = null
        val revision =
            synchronized(runtime.lifecycleLock) {
                requiredSessionRevision?.let(walletSession::requireRevision)
                val snapshot = walletSession.requireSnapshot()
                val currentRevision = walletSession.revision()
                if (snapshot.isExpired(runtime.now())) {
                    if (!expireLocally) throw LocallyExpiredSession(currentRevision)
                    expiredNotification = expireSessionLocked(snapshot, currentRevision)
                    return@synchronized null
                }
                if (snapshot.walletId.isNullOrBlank() && snapshot.expiresAt.isNullOrBlank()) {
                    throw OMSWalletSessionException(message = "No authenticated wallet session")
                }
                requireActiveCredentialLocked(currentRevision, snapshot)
                currentRevision
            }
        expiredNotification?.let(::dispatchSessionExpiredNotification)
        return revision
            ?: throw OMSWalletSessionException(
                code = OMSWalletErrorCode.SessionExpired,
                message = "Wallet session expired",
            )
    }

    private fun requireActiveCredentialLocked(
        requiredSessionRevision: Long,
        snapshot: OMSWalletSessionSnapshot = walletSession.requireSnapshot(requiredSessionRevision),
    ) {
        val actualSignerAddress = signer.existingCredentialId()
        if (
            actualSignerAddress == null ||
            actualSignerAddress != snapshot.signerAddress ||
            snapshot.signerKeyType != signer.signingAlgorithm
        ) {
            clearSessionUnlocked(
                clearOidcRedirectAuth = true,
                operation = null,
                throwOnFailure = false,
                requiredSessionRevision = requiredSessionRevision,
            )
            throw OMSWalletSessionException(message = "No active wallet session")
        }
    }

    private fun scheduleSessionExpiryLocked(
        snapshot: OMSWalletSessionSnapshot,
        requiredSessionRevision: Long,
    ) {
        walletSession.requireRevision(requiredSessionRevision)
        clearSessionExpiryTaskLocked()
        val expiresAt = snapshot.expiresAtEpochMillis() ?: return
        scheduleSessionExpiryTimerLocked(maxOf(0L, expiresAt - runtime.now()), requiredSessionRevision)
    }

    private fun scheduleSessionExpiryTimerLocked(
        delayMillis: Long,
        requiredSessionRevision: Long,
    ) {
        clearSessionExpiryTaskLocked()
        runtime.sessionExpiryTask =
            runtime.sessionExpiryScheduler.schedule(delayMillis) {
                runtime.sessionExpiryDispatcher.dispatch {
                    onSessionExpiryTimer(requiredSessionRevision)
                }
            }
    }

    /** Schedules a recheck after the expiry timer could not reach WaaS, backing off to a cap. */
    private fun scheduleCredentialRetry(requiredSessionRevision: Long) {
        synchronized(runtime.lifecycleLock) {
            if (walletSession.revision() != requiredSessionRevision) return
            val delayMillis = runtime.credentialRetryDelayMillis ?: CREDENTIAL_RETRY_INITIAL_DELAY_MILLIS
            runtime.credentialRetryDelayMillis = minOf(delayMillis * 2, CREDENTIAL_RETRY_MAX_DELAY_MILLIS)
            scheduleSessionExpiryTimerLocked(delayMillis, requiredSessionRevision)
        }
    }

    private fun clearSessionExpiryTaskLocked() {
        runtime.sessionExpiryTask.also { runtime.sessionExpiryTask = null }?.cancel()
    }

    /** Sign-in: the session's expiry was read by a request sent at [readAt], so both refresh clocks restart. */
    private fun markCredentialReadLocked(readAt: Long) {
        runtime.credentialReadAt = readAt
        runtime.credentialRefreshAttemptedAt = runtime.now()
        runtime.credentialRetryDelayMillis = null
    }

    /**
     * Restore: the stored expiry was read by an earlier process, which may have sent requests after
     * that read, so no read is recorded and the restore time stands in for the last request. No
     * refresh is recorded either, so the first successful signed request reads the expiry when the
     * restore read has not.
     */
    private fun markSessionRestoredLocked() {
        val now = runtime.now()
        runtime.credentialReadAt = null
        runtime.lastSessionRequestAt = now
        runtime.credentialRefreshAttemptedAt = null
        runtime.credentialRetryDelayMillis = null
    }

    /** Runs a session check; when it finds the session locally expired, refreshes the expiry first. */
    private suspend fun <T> refreshingLocalExpiry(
        operation: OMSWalletOperation?,
        check: (expireLocally: Boolean) -> T,
    ): T {
        val locallyExpired =
            try {
                return check(false)
            } catch (expired: LocallyExpiredSession) {
                expired
            }
        when (val outcome = refreshSessionCredential(locallyExpired.revision, background = false)) {
            is CredentialRefreshOutcome.Unavailable -> {
                throw outcome.cause
            }

            CredentialRefreshOutcome.Ended -> {
                throw OMSWalletSessionException(
                    code = OMSWalletErrorCode.SessionExpired,
                    operation = operation,
                    message = "Wallet session expired",
                )
            }

            else -> {
                Unit
            }
        }
        return check(true)
    }

    private fun launchCredentialRefresh(
        requiredSessionRevision: Long,
        fromExpiryTimer: Boolean = false,
        onOutcome: (CredentialRefreshOutcome) -> Unit = {},
    ) {
        try {
            runtime.credentialRefreshLauncher.launch {
                onOutcome(
                    refreshSessionCredential(requiredSessionRevision, background = true, fromExpiryTimer = fromExpiryTimer),
                )
            }
        } catch (_: Throwable) {
            // Background refresh is best effort; local expiry stays in effect.
        }
    }

    /**
     * Reads the session credential's current expiry from WaaS. Concurrent callers for the same
     * session share one request, and the result is applied only while that session is current.
     * [fromExpiryTimer] marks the expiry timer's check when this call sends the request.
     */
    private suspend fun refreshSessionCredential(
        requiredSessionRevision: Long,
        background: Boolean,
        fromExpiryTimer: Boolean = false,
    ): CredentialRefreshOutcome {
        var owner = false
        val inFlight =
            synchronized(runtime.lifecycleLock) {
                if (
                    walletSession.revision() != requiredSessionRevision ||
                    walletSession.snapshot()?.expiresAt.isNullOrBlank()
                ) {
                    return CredentialRefreshOutcome.Stale
                }
                if (isCredentialRefreshUnsupportedLocked()) {
                    return CredentialRefreshOutcome.Unsupported
                }
                runtime.credentialRefresh?.takeIf { it.revision == requiredSessionRevision }
                    ?: CredentialRefreshInFlight(requiredSessionRevision, CompletableDeferred()).also {
                        runtime.credentialRefresh = it
                        owner = true
                    }
            }
        if (!owner) {
            val shared = inFlight.result.await()
            // An awaited check must not settle for a background attempt that was skipped.
            return if (shared == CredentialRefreshOutcome.Busy && !background) {
                refreshSessionCredential(requiredSessionRevision, background = false)
            } else {
                shared
            }
        }
        val outcome =
            try {
                val credentialId = signingCredentialId()
                val hasTurn =
                    if (background) {
                        runtime.signedRequestGate.tryBeginRefresh(credentialId)
                    } else {
                        runtime.signedRequestGate.beginRefresh(credentialId)
                        true
                    }
                if (!hasTurn) {
                    CredentialRefreshOutcome.Busy
                } else {
                    try {
                        requestCredentialRefresh(requiredSessionRevision, fromExpiryTimer)
                    } finally {
                        runtime.signedRequestGate.endRefresh(credentialId)
                    }
                }
            } catch (throwable: Throwable) {
                CredentialRefreshOutcome.Unavailable(throwable)
            }
        synchronized(runtime.lifecycleLock) {
            if (runtime.credentialRefresh === inFlight) {
                runtime.credentialRefresh = null
            }
        }
        if (outcome is CredentialRefreshOutcome.Unavailable && outcome.cause is CancellationException) {
            // Only the owner was cancelled: waiters that need an answer retry, background ones skip.
            inFlight.result.complete(CredentialRefreshOutcome.Busy)
            throw outcome.cause
        }
        inFlight.result.complete(outcome)
        return outcome
    }

    private suspend fun requestCredentialRefresh(
        requiredSessionRevision: Long,
        fromExpiryTimer: Boolean,
    ): CredentialRefreshOutcome {
        synchronized(runtime.lifecycleLock) {
            runtime.credentialReadCompletedAt = null
            runtime.expiryTimerReadInFlight = fromExpiryTimer
            runtime.credentialRefreshAttemptedAt = runtime.now()
        }
        val expiresAt =
            try {
                gateway.getCredential(requiredSessionRevision).expiresAt
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: WebRpcError) {
                if (throwable.errorKind == ErrorKind.UNAUTHORIZED) {
                    // GetCredential involves no wallet, so 7207 means WaaS revoked this credential.
                    endSessionRejectedByServer(requiredSessionRevision)
                    return CredentialRefreshOutcome.Ended
                }
                if (throwable.errorKind != ErrorKind.WEBRPC_BAD_ROUTE) {
                    return CredentialRefreshOutcome.Unavailable(throwable)
                }
                // Servers without sliding expiry have no GetCredential; their local expiry is exact.
                synchronized(runtime.lifecycleLock) { runtime.credentialRefreshUnsupportedAt = runtime.now() }
                return CredentialRefreshOutcome.Unsupported
            } catch (throwable: WebRpcTransportException) {
                return when (val cause = throwable.cause.let { (it as? SignedRequestNotSentException)?.cause ?: it }) {
                    is OMSWalletSessionException -> {
                        if (cause.code == OMSWalletErrorCode.SessionExpired) {
                            CredentialRefreshOutcome.Ended
                        } else {
                            CredentialRefreshOutcome.Stale
                        }
                    }

                    is SessionRevisionChangedException -> {
                        CredentialRefreshOutcome.Stale
                    }

                    else -> {
                        CredentialRefreshOutcome.Unavailable(throwable)
                    }
                }
            } catch (_: SessionRevisionChangedException) {
                return CredentialRefreshOutcome.Stale
            } catch (throwable: Throwable) {
                return CredentialRefreshOutcome.Unavailable(throwable)
            }
        return synchronized(runtime.lifecycleLock) {
            if (walletSession.revision() != requiredSessionRevision) {
                return@synchronized CredentialRefreshOutcome.Stale
            }
            if (OMSWalletIsoTimestamps.parseEpochMillis(expiresAt) == null) {
                return@synchronized CredentialRefreshOutcome.Unavailable(
                    OMSWalletResponseException(message = "Credential expiry response is invalid"),
                )
            }
            val updated =
                walletSession.updateExpiresAt(expiresAt, requiredSessionRevision)
                    ?: return@synchronized CredentialRefreshOutcome.Stale
            if (updated.wallet != null) {
                try {
                    sessionStore?.save(updated)
                } catch (_: Throwable) {
                    // The in-memory expiry is still current; the next refresh persists it again.
                }
            }
            // The read's own completion time, so a read with no later request leaves lastSessionRequestAt <= credentialReadAt.
            runtime.credentialReadAt = runtime.credentialReadCompletedAt ?: runtime.now()
            runtime.credentialRetryDelayMillis = null
            runtime.credentialRefreshUnsupportedAt = null
            scheduleSessionExpiryLocked(updated, requiredSessionRevision)
            CredentialRefreshOutcome.Refreshed
        }
    }

    /** Whether a WebrpcBadRoute answer to GetCredential is recent enough that refreshes are skipped. */
    private fun isCredentialRefreshUnsupportedLocked(): Boolean {
        val unsupportedAt = runtime.credentialRefreshUnsupportedAt ?: return false
        return runtime.now() - unsupportedAt < CREDENTIAL_REFRESH_UNSUPPORTED_COOLDOWN_MILLIS
    }

    /** Whether [credentialId] signs the current verified session; switching wallets keeps it. */
    private fun isCurrentSessionCredentialLocked(credentialId: String): Boolean {
        val snapshot = walletSession.snapshot() ?: return false
        return snapshot.signerAddress == credentialId && !snapshot.expiresAt.isNullOrBlank()
    }

    /** Ends the session signed by [credentialId] when WaaS rejects it (7202 KeyNotFound, 7203 KeyExpired). */
    private fun endSessionRejectedForCredential(credentialId: String) {
        val revision =
            synchronized(runtime.lifecycleLock) {
                if (!isCurrentSessionCredentialLocked(credentialId)) return
                walletSession.revision()
            }
        endSessionRejectedByServer(revision)
    }

    /**
     * Ends the session when WaaS rejects its credential (7202 KeyNotFound, 7203 KeyExpired, or 7207
     * Unauthorized from GetCredential).
     */
    private fun endSessionRejectedByServer(requiredSessionRevision: Long) {
        val notification =
            synchronized(runtime.lifecycleLock) {
                if (walletSession.revision() != requiredSessionRevision) return
                val snapshot = walletSession.snapshot() ?: return
                expireSessionLocked(snapshot, requiredSessionRevision).also { notification ->
                    if (notification == null) {
                        // Without a parseable expiry there is no event to report; still end the session.
                        clearSessionUnlocked(
                            clearOidcRedirectAuth = true,
                            operation = null,
                            throwOnFailure = false,
                            requiredSessionRevision = requiredSessionRevision,
                        )
                    }
                }
            }
        notification?.let(::dispatchSessionExpiredNotification)
    }

    /** After a successful authenticated call, refreshes the expiry in the background when due. */
    private fun refreshCredentialIfDue(requiredSessionRevision: Long) {
        val due =
            synchronized(runtime.lifecycleLock) {
                val attemptedAt = runtime.credentialRefreshAttemptedAt
                !isCredentialRefreshUnsupportedLocked() &&
                    runtime.credentialRefresh == null &&
                    walletSession.revision() == requiredSessionRevision &&
                    (attemptedAt == null || runtime.now() - attemptedAt >= CREDENTIAL_REFRESH_INTERVAL_MILLIS)
            }
        if (due) {
            launchCredentialRefresh(requiredSessionRevision)
        }
    }

    /**
     * Records that a request signed with the session credential completed (response received or
     * error thrown). WaaS slides the expiry for every such request it receives, whatever its
     * outcome, and no later than its completion, so the completion bounds the slide. The expiry
     * timer's own check is not recorded: it is sent only once WaaS should have ended an idle
     * session, so it cannot slide the expiry. Refreshes never overlap other signed session
     * requests, so a GetCredential's completion is also the session's last request.
     */
    private fun recordSessionRequestCompleted(
        credentialId: String,
        endpoint: String,
    ) {
        synchronized(runtime.lifecycleLock) {
            if (!isCurrentSessionCredentialLocked(credentialId)) return
            val completedAt = runtime.now()
            val isCredentialRead = endpoint == WaasApi.GetCredential.path
            if (isCredentialRead) {
                runtime.credentialReadCompletedAt = completedAt
            }
            if (!isCredentialRead || !runtime.expiryTimerReadInFlight) {
                runtime.lastSessionRequestAt = completedAt
            }
        }
    }

    private fun clearLatestSessionExpiredEventLocked() {
        runtime.latestSessionExpiredEvent = null
        runtime.latestSessionExpiredRevision = null
    }

    /**
     * Expiry timer: a locally expired session is first refreshed from WaaS, which may have slid
     * the expiry. When WaaS is unreachable the session is kept and rechecked later.
     */
    private fun onSessionExpiryTimer(requiredSessionRevision: Long) {
        synchronized(runtime.lifecycleLock) {
            if (walletSession.revision() != requiredSessionRevision) return
            val snapshot = walletSession.snapshot() ?: return
            val now = runtime.now()
            if (!snapshot.isExpired(now)) {
                scheduleSessionExpiryLocked(snapshot, requiredSessionRevision)
                return
            }
            val checkAt = idleSessionCheckAtLocked(snapshot)
            if (checkAt != null && now < checkAt) {
                scheduleSessionExpiryTimerLocked(checkAt - now, requiredSessionRevision)
                return
            }
        }
        launchCredentialRefresh(requiredSessionRevision, fromExpiryTimer = true) { outcome ->
            if (outcome is CredentialRefreshOutcome.Unavailable || outcome == CredentialRefreshOutcome.Busy) {
                scheduleCredentialRetry(requiredSessionRevision)
            } else {
                runtime.sessionExpiryDispatcher.dispatch { expireSessionFromTimer(requiredSessionRevision) }
            }
        }
    }

    /**
     * When the expiry timer may ask WaaS about a locally expired wallet session at local expiry
     * `L`. GetCredential itself extends the expiry of a session WaaS has not ended yet, so the
     * timer waits until [IDLE_SESSION_CHECK_GRACE_MILLIS] (`G`) past an upper bound on WaaS's
     * expiry for the session. `A` is when the last request signed with the session credential
     * completed (the timer's own checks excluded: they are sent only past the bound, when WaaS
     * has ended an idle session and cannot slide it), and `C` is this process's last expiry read.
     * - No request since the last read (`A <= C`): WaaS's expiry is `L`, so `L + G`.
     * - Otherwise, with the sign-in's idle window `I` and ceiling `N`: each request moves WaaS's
     *   expiry toward `min(request time + I, N)` and never past it (WaaS's 6-hour dampening can
     *   only leave it lower), so `max(L, min(A + I, N)) + G`. A restore leaves `C` unset and sets
     *   `A` to the restore time, which bounds the previous process's requests.
     * - Bounds unknown (a sign-in whose expiry was not after its send time; stored sessions
     *   without bounds are not restored): `L + max(0, A - C) + 6 hours + G`, or `L + 6 hours + G`
     *   while `C` is unset.
     * Null to check right away: a pending wallet selection, or a WaaS without GetCredential, whose
     * local expiry is exact.
     */
    private fun idleSessionCheckAtLocked(snapshot: OMSWalletSessionSnapshot): Long? {
        if (snapshot.wallet == null || isCredentialRefreshUnsupportedLocked()) return null
        val expiresAt = snapshot.expiresAtEpochMillis() ?: return null
        val readAt = runtime.credentialReadAt
        val usedAt = runtime.lastSessionRequestAt
        val bounds =
            snapshot.expiryBounds ?: run {
                val usedSinceRead = if (readAt != null && usedAt != null) maxOf(0L, usedAt - readAt) else 0L
                return expiresAt + usedSinceRead + SLIDE_DAMPENING_MILLIS + IDLE_SESSION_CHECK_GRACE_MILLIS
            }
        if (usedAt == null || (readAt != null && usedAt <= readAt)) {
            return expiresAt + IDLE_SESSION_CHECK_GRACE_MILLIS
        }
        val slideTarget =
            if (bounds.ceilingEpochMillis - usedAt <= bounds.idleWindowMillis) {
                bounds.ceilingEpochMillis
            } else {
                usedAt + bounds.idleWindowMillis
            }
        return maxOf(expiresAt, slideTarget) + IDLE_SESSION_CHECK_GRACE_MILLIS
    }

    private fun expireSessionFromTimer(requiredSessionRevision: Long) {
        val notification =
            synchronized(runtime.lifecycleLock) {
                if (walletSession.revision() != requiredSessionRevision) {
                    return@synchronized null
                }
                val snapshot = walletSession.snapshot() ?: return@synchronized null
                if (!snapshot.isExpired(runtime.now())) {
                    scheduleSessionExpiryLocked(snapshot, requiredSessionRevision)
                    return@synchronized null
                }
                expireSessionLocked(snapshot, requiredSessionRevision)
            }
        notification?.let(::dispatchSessionExpiredNotification)
    }

    private fun expireSessionLocked(
        snapshot: OMSWalletSessionSnapshot,
        requiredSessionRevision: Long,
    ): ExpiryNotification? {
        snapshot.toSessionExpiredEvent() ?: return null
        if (!walletSession.clear(requiredSessionRevision)) {
            return null
        }
        val expiredRevision = walletSession.revision()
        clearSessionExpiryTaskLocked()
        cancelRestoredSessionReadLocked()
        try {
            oidcRedirectAuthStore?.clear()
        } catch (_: Throwable) {
            // Expiry notification should not depend on redirect-state cleanup succeeding.
        }
        try {
            signer.clear()
        } catch (_: Throwable) {
            // Expiry notification should not depend on credential cleanup succeeding.
        }
        return recordSessionExpiredLocked(snapshot, expiredRevision)
    }

    private fun recordSessionExpiredLocked(
        snapshot: OMSWalletSessionSnapshot,
        expiredRevision: Long,
    ): ExpiryNotification? {
        val event = snapshot.toSessionExpiredEvent() ?: return null
        runtime.latestSessionExpiredEvent = event
        runtime.latestSessionExpiredRevision = expiredRevision
        return ExpiryNotification(event, expiredRevision, runtime.sessionExpiredListeners.toList())
    }

    private fun dispatchSessionExpiredNotification(notification: ExpiryNotification) {
        notification.listeners.forEach { listener ->
            dispatchSessionExpiredListener(listener, notification.event, notification.revision)
        }
    }

    private fun dispatchSessionExpiredListener(
        listener: (OMSWalletSessionExpiredEvent) -> Unit,
        event: OMSWalletSessionExpiredEvent,
        revision: Long,
    ) {
        runtime.sessionExpiryDispatcher.dispatch {
            val shouldNotify =
                synchronized(runtime.lifecycleLock) {
                    runtime.sessionExpiredListeners.contains(listener) &&
                        runtime.latestSessionExpiredEvent == event &&
                        runtime.latestSessionExpiredRevision == revision
                }
            if (shouldNotify) {
                callSessionExpiredListener(listener, event)
            }
        }
    }

    private fun callSessionExpiredListener(
        listener: (OMSWalletSessionExpiredEvent) -> Unit,
        event: OMSWalletSessionExpiredEvent,
    ) {
        try {
            listener(event)
        } catch (_: Throwable) {
            // App callbacks must not change SDK operation results.
        }
    }

    private fun loginHintForProvider(
        issuer: String,
        loginHint: String?,
    ): String? = loginHint.takeIf { issuer == GOOGLE_ISSUER }

    private fun oidcIdTokenSessionAuth(
        issuer: String,
        provider: String?,
        providerLabel: String?,
        completeAuth: WalletAuthCompletion,
    ): OMSWalletOidcSessionAuth {
        val resolvedIssuer = completeAuth.identity.iss?.takeIf { it.isNotBlank() } ?: issuer
        return OMSWalletOidcSessionAuth(
            flow = OMSWalletOidcSessionAuthFlow.IdToken,
            issuer = resolvedIssuer,
            provider = provider ?: builtInOidcProviderForIssuer(resolvedIssuer),
            providerLabel = providerLabel ?: builtInOidcProviderLabelForIssuer(resolvedIssuer),
            email = completeAuth.email,
        )
    }

    private fun oidcRedirectSessionAuth(
        pending: PendingOidcRedirectAuth,
        completeAuth: WalletAuthCompletion,
    ): OMSWalletOidcSessionAuth {
        val resolvedIssuer = completeAuth.identity.iss?.takeIf { it.isNotBlank() } ?: pending.issuer
        return OMSWalletOidcSessionAuth(
            flow = OMSWalletOidcSessionAuthFlow.Redirect,
            issuer = resolvedIssuer,
            provider = pending.provider ?: builtInOidcProviderForIssuer(resolvedIssuer),
            providerLabel = pending.providerLabel ?: builtInOidcProviderLabelForIssuer(resolvedIssuer),
            email = completeAuth.email,
        )
    }

    private fun builtInOidcProviderForIssuer(issuer: String): String? =
        when (issuer) {
            GOOGLE_ISSUER -> "google"
            APPLE_ISSUER -> "apple"
            else -> null
        }

    private fun derivedRelayRedirectUri(relayProvider: String): String =
        "${environment.walletApiUrl.trimEnd('/')}/auth/waas/callback/$relayProvider"

    private fun builtInOidcProviderLabelForIssuer(issuer: String): String? =
        when (issuer) {
            GOOGLE_ISSUER -> "Google"
            APPLE_ISSUER -> "Apple"
            else -> null
        }

    private fun requireWaasSessionLifetimeSeconds(sessionLifetimeSeconds: Long): UInt {
        require(sessionLifetimeSeconds in 1L..MAX_SESSION_LIFETIME_SECONDS) {
            "sessionLifetimeSeconds must be an integer between 1 and $MAX_SESSION_LIFETIME_SECONDS"
        }
        return sessionLifetimeSeconds.toUInt()
    }

    private fun authorizeSignedRequest(
        requiredSessionRevision: Long,
        allowCredentialCreation: Boolean,
        endpoint: String,
        body: String,
    ): SignedRequestAuthorization =
        synchronized(runtime.lifecycleLock) {
            walletSession.requireRevision(requiredSessionRevision)
            val snapshot = walletSession.snapshot()
            val expectedSignerAddress = snapshot?.signerAddress
            val actualSignerAddress =
                if (allowCredentialCreation && snapshot == null) {
                    signer.credentialId()
                } else {
                    signer.existingCredentialId()
                }
            if (
                actualSignerAddress == null ||
                (!expectedSignerAddress.isNullOrBlank() && expectedSignerAddress != actualSignerAddress) ||
                (snapshot?.signerKeyType != null && snapshot.signerKeyType != signer.signingAlgorithm)
            ) {
                clearSessionUnlocked(
                    clearOidcRedirectAuth = true,
                    operation = null,
                    throwOnFailure = false,
                    requiredSessionRevision = requiredSessionRevision,
                )
                throw OMSWalletSessionException(message = "No active wallet session")
            }
            // Sign-in requests run before WaaS has verified the credential.
            val usesSessionCredential = !snapshot?.expiresAt.isNullOrBlank()
            val nonce = signer.nextNonce()
            val preimage =
                WalletRequestSigner.buildWalletRequestPreimage(
                    endpoint = endpoint,
                    nonce = nonce,
                    scope = projectId,
                    payload = body,
                    requestPathPrefix = WaasApi.basePath,
                )
            SignedRequestAuthorization(
                walletSignatureHeader =
                    WalletRequestSigner.buildWalletSignatureHeader(
                        signingAlgorithm = signer.signingAlgorithm,
                        scope = projectId,
                        credentialId = actualSignerAddress,
                        nonce = nonce,
                        signature = signer.sign(preimage),
                    ),
                usesSessionCredential = usesSessionCredential,
                credentialId = actualSignerAddress,
            )
        }

    /**
     * Executes [prepared]. [enrichFeeOptions] adds the wallet's balances to the
     * fee options; it is only called when the caller passes [selectFeeOption].
     */
    private suspend fun executePreparedTransaction(
        enrichFeeOptions: suspend (List<FeeOption>) -> List<FeeOptionWithBalance>,
        prepared: PreparedWalletTransaction,
        requiredSessionRevision: Long,
        selectFeeOption: FeeOptionSelector?,
        waitForStatus: Boolean,
        statusPolling: TransactionStatusPollingOptions?,
        refusedBeforeSubmission: Set<ErrorKind> = EXECUTE_REFUSED_BEFORE_SUBMISSION,
    ): SendTransactionResponse {
        if (waitForStatus) {
            requireValidTransactionStatusPollingOptions(
                statusPolling ?: defaultStatusPolling,
            )
        }
        val feeOption =
            when {
                prepared.sponsored -> {
                    selectFeeOption?.select(emptyList())
                    null
                }

                prepared.feeOptions.isEmpty() -> {
                    throw IllegalArgumentException(
                        "No fee options available for unsponsored transaction",
                    )
                }

                selectFeeOption == null -> {
                    prepared.feeOptions.defaultSelection()
                }

                else -> {
                    val options = enrichFeeOptions(prepared.feeOptions)
                    selectFeeOption.select(options) ?: throw IllegalArgumentException(
                        "No fee option selected for unsponsored transaction",
                    )
                }
            }
        val executed =
            try {
                gateway.execute(prepared.txnId, feeOption, requiredSessionRevision)
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                val notSent = (throwable as? WebRpcTransportException)?.cause as? SignedRequestNotSentException
                if (notSent != null) {
                    // Execute failed before its request was sent, so nothing was submitted.
                    throw (notSent.cause ?: notSent).toOMSWalletException(OMSWalletOperation.WalletExecute)
                }
                val sdkError = throwable.toOMSWalletException(OMSWalletOperation.WalletExecute)
                if (sdkError.code == OMSWalletErrorCode.SessionExpired) {
                    // WaaS rejected the credential before running Execute, so nothing was sent.
                    throw sdkError
                }
                if (throwable is WebRpcError && throwable.errorKind == ErrorKind.TRANSACTION_SIMULATION_FAILED) {
                    // The relayer refused to dispatch a reverting transaction; WaaS closed it as failed.
                    throw sdkError
                }
                if (throwable is WebRpcError && throwable.errorKind in refusedBeforeSubmission) {
                    // WaaS refused Execute before submitting anything: its credential middleware
                    // rejected the signature (7201), usage (7205), or nonce (7206, still after the
                    // resends), or it was not authorized (7207, which Execute only raises before
                    // submission); for Tron, also its re-pricing (7200, 7310).
                    throw sdkError
                }
                throw OMSWalletTransactionException(
                    code = OMSWalletErrorCode.TransactionExecutionUnconfirmed,
                    operation = OMSWalletOperation.WalletExecute,
                    status = sdkError.status,
                    txnId = prepared.txnId,
                    retryable = false,
                    upstreamError = sdkError.upstreamError,
                    message = "Transaction execution failed before status could be confirmed",
                    cause = sdkError,
                )
            }
        if (!waitForStatus) {
            return SendTransactionResponse(
                txnId = prepared.txnId,
                status = executed.status,
                txnHash = null,
                statusResolution = TransactionStatusResolution.NotRequested,
            )
        }
        val status =
            waitForTransactionStatus(
                txnId = prepared.txnId,
                fallbackStatus = executed.status,
                options = statusPolling ?: defaultStatusPolling,
                requiredSessionRevision = requiredSessionRevision,
            )
        return SendTransactionResponse(
            txnId = prepared.txnId,
            status = status.response.status,
            txnHash = status.response.txnHash,
            statusResolution = status.resolution,
        )
    }

    private fun accessPage(
        pageSize: UInt?,
        cursor: String?,
    ): Page? =
        if (pageSize == null && cursor == null) {
            null
        } else {
            Page(limit = pageSize, cursor = cursor)
        }

    private suspend fun enrichFeeOptionsWithBalances(
        network: Network,
        walletAddress: String,
        feeOptions: List<FeeOption>,
    ): List<FeeOptionWithBalance> {
        val contractAddresses =
            feeOptions
                .mapNotNull { it.token.contractAddress?.normalizeAddress() }
                .distinct()
        val balances =
            runCatching {
                indexerClient.getBalances(
                    networks = listOf(network),
                    contractAddresses = contractAddresses,
                    walletAddress = walletAddress,
                    includeMetadata = false,
                )
            }.getOrNull()
        val nativeBalance =
            if (feeOptions.any { it.token.isNativeToken() }) {
                balances?.nativeBalances?.firstOrNull { balance ->
                    balance.chainId == network.id.toLong()
                }
            } else {
                null
            }
        val balancesByContract =
            contractAddresses.associateWith { contractAddress ->
                balances?.balances?.firstOrNull { balance ->
                    balance.contractAddress.normalizeAddress() == contractAddress
                }
            }

        return feeOptions.mapIndexed { index, feeOption ->
            val balance =
                if (feeOption.token.isNativeToken()) {
                    nativeBalance
                } else {
                    feeOption.token.contractAddress
                        ?.normalizeAddress()
                        ?.let { balancesByContract[it] }
                }
            val decimals = feeOption.token.balanceDecimals()
            FeeOptionWithBalance(
                feeOption = feeOption,
                selection = FeeOptionSelection(feeOption, index.toUInt()),
                balance = balance,
                available = balance?.balance?.formatTokenAmount(decimals),
                availableRaw = balance?.balance,
                decimals = decimals,
            )
        }
    }

    private suspend fun enrichSolanaFeeOptionsWithBalances(
        network: SolanaNetwork,
        walletAddress: String,
        feeOptions: List<FeeOption>,
    ): List<FeeOptionWithBalance> {
        val balances =
            runCatching {
                indexerClient.getSolanaBalances(
                    walletAddress = walletAddress,
                    networks = listOf(network),
                    includeMetadata = false,
                    omitNativeBalances = !feeOptions.includesNativeToken(),
                    mintAddresses = feeOptions.gatewayTokenAddresses(),
                )
            }.getOrNull()
        val nativeBalance =
            balances?.balances?.firstOrNull { balance ->
                balance is SolanaBalance.Native && balance.network == network
            }
        val balancesByMint =
            balances
                ?.balances
                ?.filterIsInstance<SolanaBalance.FungibleToken>()
                ?.filter { it.network == network }
                ?.associateBy { it.mintAddress }
                .orEmpty()
        return feeOptions.withGatewayBalances(nativeBalance, balancesByMint, { it.balance }, { it.decimals })
    }

    private suspend fun enrichTronFeeOptionsWithBalances(
        network: TronNetwork,
        walletAddress: String,
        feeOptions: List<FeeOption>,
    ): List<FeeOptionWithBalance> {
        val balances =
            runCatching {
                indexerClient.getTronBalances(
                    walletAddress = walletAddress,
                    networks = listOf(network),
                    includeMetadata = false,
                    omitNativeBalances = !feeOptions.includesNativeToken(),
                    contractAddresses = feeOptions.gatewayTokenAddresses(),
                )
            }.getOrNull()
        val nativeBalance =
            balances?.balances?.firstOrNull { balance ->
                balance is TronBalance.Native && balance.network == network
            }
        val balancesByContract =
            balances
                ?.balances
                ?.filterIsInstance<TronBalance.FungibleToken>()
                ?.filter { it.network == network }
                ?.associateBy { it.contractAddress }
                .orEmpty()
        return feeOptions.withGatewayBalances(nativeBalance, balancesByContract, { it.balance }, { it.decimals })
    }

    private fun List<FeeOption>.includesNativeToken(): Boolean = any { it.token.isNativeToken() }

    /** Distinct case-sensitive token addresses (Solana mints, Tron contracts) of the non-native fee options. */
    private fun List<FeeOption>.gatewayTokenAddresses(): List<String> =
        filterNot { it.token.isNativeToken() }
            .mapNotNull { it.token.contractAddress.normalizeCaseSensitiveAddress() }
            .distinct()

    /**
     * Pairs each fee option with its Solana or Tron gateway balance: [nativeBalance]
     * for the native token, otherwise the [balancesByAddress] entry for its
     * case-sensitive token address.
     */
    private fun <B : Any> List<FeeOption>.withGatewayBalances(
        nativeBalance: B?,
        balancesByAddress: Map<String, B>,
        rawBalance: (B) -> String,
        balanceDecimals: (B) -> Int,
    ): List<FeeOptionWithBalance> =
        mapIndexed { index, feeOption ->
            val balance =
                if (feeOption.token.isNativeToken()) {
                    nativeBalance
                } else {
                    feeOption.token.contractAddress
                        .normalizeCaseSensitiveAddress()
                        ?.let { balancesByAddress[it] }
                }
            val decimals = balance?.let(balanceDecimals) ?: feeOption.token.decimals?.toInt()
            val available = balance?.let(rawBalance)
            FeeOptionWithBalance(
                feeOption = feeOption,
                selection = FeeOptionSelection(feeOption, index.toUInt()),
                available = available?.formatTokenAmount(decimals),
                availableRaw = available,
                decimals = decimals,
            )
        }

    private fun String?.normalizeAddress(): String? =
        this
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()

    private fun String?.normalizeCaseSensitiveAddress(): String? =
        this
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun FeeToken.isNativeToken(): Boolean =
        type.equals("native", ignoreCase = true) ||
            (contractAddress.isNullOrBlank() && tokenId.isNullOrBlank())

    private fun FeeToken.balanceDecimals(): Int? = decimals?.toInt() ?: if (isNativeToken()) 18 else null

    private fun String.formatTokenAmount(decimals: Int?): String =
        decimals?.let { scale ->
            runCatching { formatUnits(BigInteger(this), scale) }.getOrDefault(this)
        } ?: this

    private fun List<FeeOption>.defaultSelection(): FeeOptionSelection = FeeOptionSelection(first(), 0u)

    private suspend fun waitForTransactionStatus(
        txnId: String,
        fallbackStatus: TransactionStatus,
        options: TransactionStatusPollingOptions,
        requiredSessionRevision: Long,
    ): ResolvedTransactionStatus {
        val deadline = System.currentTimeMillis() + options.timeoutMs
        var lastStatus = TransactionStatusResponse(status = fallbackStatus)
        var completedStatusPolls = 0

        do {
            lastStatus =
                try {
                    gateway.transactionStatus(txnId, requiredSessionRevision)
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    val sdkError = throwable.toOMSWalletException(OMSWalletOperation.WalletTransactionStatus)
                    throw OMSWalletTransactionException(
                        operation = OMSWalletOperation.WalletTransactionStatus,
                        status = sdkError.status,
                        txnId = txnId,
                        retryable = true,
                        upstreamError = sdkError.upstreamError,
                        message = "Transaction was submitted, but status polling failed",
                        cause = sdkError,
                    )
                }
            completedStatusPolls += 1
            if (lastStatus.status == TransactionStatus.Executed ||
                lastStatus.status == TransactionStatus.Failed ||
                !lastStatus.txnHash.isNullOrBlank()
            ) {
                return ResolvedTransactionStatus(
                    response = lastStatus,
                    resolution = TransactionStatusResolution.Resolved,
                )
            }
            val remainingMillis = deadline - System.currentTimeMillis()
            if (remainingMillis <= 0L) {
                return ResolvedTransactionStatus(
                    response = lastStatus,
                    resolution = TransactionStatusResolution.TimedOut,
                )
            }
            val nextDelayMillis =
                if (completedStatusPolls < options.fastPollCount) {
                    options.fastIntervalMs
                } else {
                    options.intervalMs
                }
            transactionStatusDelay(minOf(nextDelayMillis, remainingMillis))
        } while (true)
    }

    private fun requireValidTransactionStatusPollingOptions(options: TransactionStatusPollingOptions) {
        require(options.fastIntervalMs > 0L) {
            "fastIntervalMs must be greater than zero"
        }
        require(options.fastPollCount >= 0) {
            "fastPollCount must not be negative"
        }
        require(options.intervalMs > 0L) {
            "intervalMs must be greater than zero"
        }
        require(options.timeoutMs >= 0L) {
            "timeoutMs must not be negative"
        }
    }

    private data class ResolvedTransactionStatus(
        val response: TransactionStatusResponse,
        val resolution: TransactionStatusResolution,
    )
}

private data class VerifierCommitment(
    val verifier: String,
    val loginHint: String?,
    val challenge: String,
)

private data class WalletAuthCompletion(
    val wallets: List<Wallet>,
    val nextWalletsCursor: String?,
    val claimableWallets: List<Wallet>,
    val email: String?,
    val identity: Identity,
    val credential: WalletCredential,
)

/** A CompleteAuth response with its request's send time and requested lifetime in seconds. */
private class SignInCompletion(
    val auth: WalletAuthCompletion,
    val sentAt: Long,
    val lifetimeSeconds: UInt,
) {
    /**
     * WaaS sets a new credential's expiry to `min(sign-in + idle window, sign-in + lifetime)`, so
     * the idle window is the expiry less the send time, and the ceiling is the later of the
     * lifetime from the send time and the expiry itself. Null when the expiry does not parse or
     * is not after the send time.
     */
    fun expiryBounds(): OMSWalletSessionExpiryBounds? {
        val expiresAt = OMSWalletIsoTimestamps.parseEpochMillis(auth.credential.expiresAt) ?: return null
        val idleWindowMillis = expiresAt - sentAt
        if (idleWindowMillis <= 0) return null
        return OMSWalletSessionExpiryBounds(
            idleWindowMillis = idleWindowMillis,
            ceilingEpochMillis = maxOf(sentAt + lifetimeSeconds.toLong() * 1_000L, expiresAt),
        )
    }
}

private data class WalletsPage(
    val wallets: List<Wallet>,
    val nextCursor: String?,
)

private data class PreparedWalletTransaction(
    val txnId: String,
    val sponsored: Boolean,
    val feeOptions: List<FeeOption>,
)

private data class ActiveWalletSession(
    val wallet: Wallet,
    val revision: Long,
) {
    val walletId: String get() = wallet.id

    val walletAddress: String get() = wallet.address

    /** Rejects the operation before any request when the stored wallet type differs. */
    fun requireWalletType(type: WalletType) {
        require(wallet.type == type) { "An active ${type.label} wallet is required" }
    }
}

private val WalletType.label: String
    get() =
        when (this) {
            WalletType.Ethereum -> "Ethereum"
            WalletType.Solana -> "Solana"
            WalletType.Tron -> "Tron"
            WalletType.UNKNOWN_DEFAULT -> wireValue
        }

// Mirrors the wallet service: it builds the signature from the arg types and accepts a bare name.
private val contractMethodNamePattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

private fun requireContractMethodName(method: String) {
    require(contractMethodNamePattern.matches(method)) {
        "method must be a function name such as 'transfer', not a signature; got '$method'"
    }
}

private sealed interface WalletImportActivationContext {
    val revision: Long

    data class Active(
        val walletId: String,
        override val revision: Long,
    ) : WalletImportActivationContext

    data class Pending(
        val id: Long,
        val signerAddress: String,
        val signerKeyType: WalletSigningAlgorithm?,
        override val revision: Long,
    ) : WalletImportActivationContext
}

private data class ExpiryNotification(
    val event: OMSWalletSessionExpiredEvent,
    val revision: Long,
    val listeners: List<(OMSWalletSessionExpiredEvent) -> Unit>,
)

/** A WaaS answer to a request signed with the session credential that needs a follow-up. */
private enum class SessionCredentialAnswer {
    /** Accepted: a due background expiry read may run. */
    Accepted,

    /** 7207 Unauthorized: a background expiry read tells a revoked credential from an access error. */
    Unauthorized,
}

private class SignedRequestAuthorization(
    val walletSignatureHeader: String,
    /** True when the request carries the authenticated wallet session's credential. */
    val usesSessionCredential: Boolean,
    /** The signing credential, which identifies the session across wallet switches. */
    val credentialId: String,
)

/** A signed request's HTTP response with the authorization it was sent with. */
private class SignedResponse(
    val authorization: SignedRequestAuthorization,
    val statusCode: Int,
    val body: String,
)

private data class ExecutedWalletTransaction(
    val status: TransactionStatus,
)

private data class OidcRedirectUris(
    val oauthRedirectUri: String,
    val expectedCallbackUri: String,
    val stateRedirectUri: String?,
)

private class WaasWalletGateway(
    /** The scope's current publishable key, read for each request. */
    private val publishableKey: () -> String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient,
    private val authorizeSignedRequest: (
        requiredSessionRevision: Long,
        allowCredentialCreation: Boolean,
        endpoint: String,
        body: String,
    ) -> SignedRequestAuthorization,
    private val onSessionCredentialRejected: (credentialId: String) -> Unit,
    private val onSessionCredentialAccepted: (requiredSessionRevision: Long) -> Unit,
    private val onSessionCredentialUnauthorized: (requiredSessionRevision: Long) -> Unit,
    private val onSessionRequestCompleted: (credentialId: String, endpoint: String) -> Unit,
    private val signedRequestGate: SignedRequestGate,
    private val signingCredentialId: () -> String,
    private val walletImportTrustedPcr0s: Set<String>?,
    private val signedRequestTimeoutMillis: Long,
) {
    private val publicClient: WaasPublicClient =
        WaasPublicClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport =
                LambdaWebRpcTransport { baseUrl, path, body, headers ->
                    val response =
                        transport.postJsonWithStatus(
                            baseUrl = baseUrl,
                            path = path,
                            body = body,
                            headers = headers,
                        )
                    WebRpcHttpResponse(
                        statusCode = response.statusCode,
                        body = response.body,
                    )
                },
            headers = { defaultPublicHeaders() },
        )

    suspend fun commitEmailVerifier(
        email: String,
        requiredSessionRevision: Long,
    ): VerifierCommitment =
        signedClient(requiredSessionRevision, allowCredentialCreation = true)
            .commitVerifier(
                CommitVerifierRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    metadata = emptyMap(),
                    handle = email,
                ),
            ).toVerifierCommitment()

    suspend fun commitOidcIdTokenVerifier(
        idToken: String,
        issuer: String,
        audience: String,
        requiredSessionRevision: Long,
    ): VerifierCommitment =
        signedClient(requiredSessionRevision, allowCredentialCreation = true)
            .commitVerifier(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    metadata =
                        mapOf(
                            "iss" to issuer,
                            "aud" to audience,
                            "exp" to OidcIdToken.expiresAtEpochSeconds(idToken).toString(),
                        ),
                    handle = OidcIdToken.handleHash(idToken),
                ),
            ).toVerifierCommitment()

    suspend fun commitOidcRedirectVerifier(
        issuer: String,
        clientId: String,
        redirectUri: String,
        authMode: AuthMode,
        requiredSessionRevision: Long,
    ): VerifierCommitment =
        signedClient(requiredSessionRevision, allowCredentialCreation = true)
            .commitVerifier(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = authMode,
                    metadata =
                        mapOf(
                            "iss" to issuer,
                            "aud" to clientId,
                            "redirect_uri" to redirectUri,
                        ),
                ),
            ).toVerifierCommitment()

    suspend fun completeEmailAuth(
        verifier: String,
        challenge: String,
        code: String,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): WalletAuthCompletion =
        signedClient(requiredSessionRevision)
            .completeAuth(
                CompleteAuthRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    verifier = verifier,
                    answer =
                        WalletAuthChallenge.hashAnswer(
                            challenge = challenge,
                            code = code,
                        ),
                    lifetime = sessionLifetimeSeconds,
                ),
            ).toWalletAuthCompletion()

    suspend fun completeOidcIdTokenAuth(
        verifier: String,
        idToken: String,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): WalletAuthCompletion =
        signedClient(requiredSessionRevision)
            .completeAuth(
                CompleteAuthRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    verifier = verifier,
                    answer = idToken,
                    lifetime = sessionLifetimeSeconds,
                ),
            ).toWalletAuthCompletion()

    suspend fun completeOidcRedirectAuth(
        verifier: String,
        code: String,
        authMode: AuthMode,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): WalletAuthCompletion =
        signedClient(requiredSessionRevision)
            .completeAuth(
                CompleteAuthRequest(
                    identityType = IdentityType.OIDC,
                    authMode = authMode,
                    verifier = verifier,
                    answer = code,
                    lifetime = sessionLifetimeSeconds,
                ),
            ).toWalletAuthCompletion()

    suspend fun useWallet(
        walletId: String,
        requiredSessionRevision: Long,
    ): Wallet =
        signedClient(requiredSessionRevision)
            .useWallet(
                UseWalletRequest(
                    walletId = walletId,
                ),
            ).wallet
            .toModel()

    suspend fun claimWallet(
        walletId: String,
        requiredSessionRevision: Long,
    ): Wallet =
        signedClient(requiredSessionRevision)
            .claimWallet(
                ClaimWalletRequest(
                    walletId = walletId,
                ),
            ).wallet
            .toModel()

    suspend fun getWalletImportRecipientKey(
        cipherSuite: WalletImportCipherSuite,
        requiredSessionRevision: Long,
    ): WalletImportRecipientKey {
        val response =
            walletImportClient(requiredSessionRevision).getRecipientKey(
                GetRecipientKeyRequest(
                    purpose = TransportPurpose.WalletImport,
                    suite = cipherSuite.toWaas(),
                ),
            )
        if (response.keyId.isBlank() || response.publicKey.isBlank()) {
            throw OMSWalletResponseException(message = "Wallet import recipient-key response is incomplete")
        }
        WalletImportBase64.decodeCanonical(response.publicKey, "recipient publicKey")
        return WalletImportRecipientKey(response.keyId, cipherSuite, response.publicKey)
    }

    suspend fun importWallet(
        walletType: WalletType,
        keyMaterial: EncryptedWalletImportKeyMaterial,
        reference: String?,
        requiredSessionRevision: Long,
    ): Wallet {
        require(keyMaterial.keyId.isNotBlank()) { "keyMaterial.keyId is required" }
        WalletImportCrypto.validateReference(reference)
        WalletImportBase64.decodeCanonical(keyMaterial.encapsulatedKey, "keyMaterial.encapsulatedKey")
        WalletImportBase64.decodeCanonical(keyMaterial.ciphertext, "keyMaterial.ciphertext")
        val wallet =
            walletImportClient(requiredSessionRevision)
                .importWallet(
                    ImportWalletRequest(
                        networkFamily = walletType.toNetworkFamily(),
                        format = KeyFormat.PrivateKey,
                        keyMaterial =
                            HPKEPayload(
                                keyId = keyMaterial.keyId,
                                suite = keyMaterial.cipherSuite.toWaas(),
                                encapsulatedKey = keyMaterial.encapsulatedKey,
                                ciphertext = keyMaterial.ciphertext,
                            ),
                        reference = reference,
                    ),
                ).wallet
                .toModel()
        if (wallet.type != walletType) {
            throw OMSWalletResponseException(message = "Imported wallet network family does not match the request")
        }
        return wallet
    }

    suspend fun createWallet(
        walletType: WalletType,
        reference: String?,
        requiredSessionRevision: Long,
    ): Wallet =
        signedClient(requiredSessionRevision)
            .createWallet(
                CreateWalletRequest(
                    networkFamily = walletType.toNetworkFamily(),
                    reference = reference,
                ),
            ).wallet
            .toModel()

    suspend fun listWallets(
        cursor: String?,
        requiredSessionRevision: Long,
    ): WalletsPage {
        val response =
            signedClient(requiredSessionRevision).listWallets(
                ListWalletsRequest(
                    page = cursor?.let { WaasPage(cursor = it) },
                ),
            )
        return WalletsPage(
            wallets = response.wallets.map { it.toModel() },
            nextCursor = response.page?.cursor?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun signMessage(
        walletId: String,
        network: Network,
        message: String,
        requiredSessionRevision: Long,
    ): String =
        signedClient(requiredSessionRevision)
            .signMessage(
                SignMessageRequest(
                    walletId = walletId,
                    network = network.id.toString(),
                    message = message,
                ),
            ).signature

    suspend fun signSolanaMessage(
        walletId: String,
        message: String,
        requiredSessionRevision: Long,
    ): String =
        signedClient(requiredSessionRevision)
            .signMessage(
                SignMessageRequest(
                    walletId = walletId,
                    network = "",
                    message = message,
                ),
            ).signature

    suspend fun signTronMessage(
        walletId: String,
        message: String,
        requiredSessionRevision: Long,
    ): String =
        signedClient(requiredSessionRevision)
            .signMessage(
                SignMessageRequest(
                    walletId = walletId,
                    network = "",
                    message = message,
                ),
            ).signature

    suspend fun signTronTypedData(
        walletId: String,
        typedData: JsonElement,
        requiredSessionRevision: Long,
    ): String =
        signedClient(requiredSessionRevision)
            .signTypedData(
                SignTypedDataRequest(
                    walletId = walletId,
                    network = "",
                    typedData = typedData,
                ),
            ).signature

    suspend fun signTypedData(
        walletId: String,
        network: Network,
        typedData: JsonElement,
        requiredSessionRevision: Long,
    ): String =
        signedClient(requiredSessionRevision)
            .signTypedData(
                SignTypedDataRequest(
                    walletId = walletId,
                    network = network.id.toString(),
                    typedData = typedData,
                ),
            ).signature

    suspend fun isValidMessageSignature(
        walletAddress: String,
        network: Network,
        message: String,
        signature: String,
    ): Boolean =
        publicClient
            .isValidMessageSignature(
                IsValidMessageSignatureRequest(
                    network = network.id.toString(),
                    networkFamily = WaasNetworkFamily.EVM,
                    walletAddress = walletAddress,
                    message = message,
                    signature = signature,
                ),
            ).isValid

    suspend fun isValidSolanaMessageSignature(
        walletAddress: String,
        message: String,
        signature: String,
    ): Boolean =
        publicClient
            .isValidMessageSignature(
                IsValidMessageSignatureRequest(
                    networkFamily = WaasNetworkFamily.Solana,
                    walletAddress = walletAddress,
                    message = message,
                    signature = signature,
                ),
            ).isValid

    suspend fun isValidTypedDataSignature(
        walletAddress: String,
        network: Network,
        typedData: JsonElement,
        signature: String,
    ): Boolean =
        publicClient
            .isValidTypedDataSignature(
                IsValidTypedDataSignatureRequest(
                    network = network.id.toString(),
                    networkFamily = WaasNetworkFamily.EVM,
                    walletAddress = walletAddress,
                    typedData = typedData,
                    signature = signature,
                ),
            ).isValid

    suspend fun isValidTronMessageSignature(
        walletAddress: String,
        message: String,
        signature: String,
    ): Boolean =
        publicClient
            .isValidMessageSignature(
                IsValidMessageSignatureRequest(
                    networkFamily = WaasNetworkFamily.Tron,
                    walletAddress = walletAddress,
                    message = message,
                    signature = signature,
                ),
            ).isValid

    suspend fun isValidTronTypedDataSignature(
        walletAddress: String,
        typedData: JsonElement,
        signature: String,
    ): Boolean =
        publicClient
            .isValidTypedDataSignature(
                IsValidTypedDataSignatureRequest(
                    networkFamily = WaasNetworkFamily.Tron,
                    walletAddress = walletAddress,
                    typedData = typedData,
                    signature = signature,
                ),
            ).isValid

    suspend fun prepareEthereumTransaction(
        walletId: String,
        network: Network,
        request: SendTransactionRequest,
        requiredSessionRevision: Long,
    ): PreparedWalletTransaction =
        signedClient(requiredSessionRevision)
            .prepareEthereumTransaction(
                PrepareEthereumTransactionRequest(
                    walletId = walletId,
                    network = network.id.toString(),
                    to = request.to,
                    value = request.value.toString(),
                    data = request.data,
                    mode = request.mode.toWaas(),
                ),
            ).toPreparedWalletTransaction()

    suspend fun prepareEthereumContractCall(
        walletId: String,
        network: Network,
        contract: String,
        method: String,
        args: List<AbiArg>?,
        mode: TransactionMode,
        requiredSessionRevision: Long,
    ): PreparedWalletTransaction =
        signedClient(requiredSessionRevision)
            .prepareEthereumContractCall(
                PrepareEthereumContractCallRequest(
                    walletId = walletId,
                    network = network.id.toString(),
                    contract = contract,
                    method = method,
                    args = args?.map { it.toWaas() },
                    mode = mode.toWaas(),
                ),
            ).toPreparedWalletTransaction()

    suspend fun prepareSolanaTransfer(
        walletId: String,
        network: SolanaNetwork,
        asset: String,
        to: String,
        amount: BigInteger,
        mode: TransactionMode,
        requiredSessionRevision: Long,
    ): PreparedWalletTransaction =
        signedClient(requiredSessionRevision)
            .prepareSolanaTransfer(
                PrepareSolanaTransferRequest(
                    walletId = walletId,
                    network = network.wireValue,
                    asset = asset,
                    recipient = SolanaRecipient(address = to),
                    amount = amount.toString(),
                    mode = mode.toWaas(),
                ),
            ).toPreparedWalletTransaction()

    suspend fun prepareTronTransaction(
        walletId: String,
        network: TronNetwork,
        to: String,
        value: BigInteger,
        data: String?,
        requiredSessionRevision: Long,
    ): PreparedWalletTransaction =
        signedClient(requiredSessionRevision)
            .prepareTronTransaction(
                // `data` is forwarded as given: null is omitted from the request (a TRX
                // transfer), while any value, even "0x", makes this a contract call.
                PrepareTronTransactionRequest(
                    network = network.wireValue,
                    walletId = walletId,
                    to = to,
                    value = value.toString(),
                    data = data,
                    mode = WaasTransactionMode.Native,
                ),
            ).toPreparedWalletTransaction()

    suspend fun prepareTronContractCall(
        walletId: String,
        network: TronNetwork,
        contract: String,
        method: String,
        args: List<AbiArg>?,
        requiredSessionRevision: Long,
    ): PreparedWalletTransaction =
        signedClient(requiredSessionRevision)
            .prepareTronContractCall(
                PrepareTronContractCallRequest(
                    network = network.wireValue,
                    walletId = walletId,
                    contract = contract,
                    method = method,
                    args = args?.map { it.toWaas() },
                    mode = WaasTransactionMode.Native,
                ),
            ).toPreparedWalletTransaction()

    suspend fun execute(
        txnId: String,
        feeOption: FeeOptionSelection?,
        requiredSessionRevision: Long,
    ): ExecutedWalletTransaction =
        ExecutedWalletTransaction(
            status =
                signedClient(requiredSessionRevision)
                    .execute(
                        ExecuteRequest(
                            txnId = txnId,
                            feeOption = feeOption?.toWaas(),
                        ),
                    ).status
                    .toModel(),
        )

    suspend fun transactionStatus(
        txnId: String,
        requiredSessionRevision: Long,
    ): TransactionStatusResponse =
        signedClient(requiredSessionRevision)
            .transactionStatus(TransactionStatusRequest(txnId = txnId))
            .toModel()

    suspend fun inspectRemoteCredential(
        scope: String,
        credentialId: String,
    ): RemoteCredentialMetadata =
        publicClient
            .inspectCredential(
                InspectCredentialRequest(
                    scope = scope,
                    credentialId = credentialId,
                ),
            ).metadata
            .toModel()

    suspend fun authorizeRemoteAccess(
        walletId: String,
        credentialId: String,
        network: Network,
        grants: List<SmartSessionGrant>,
        expiresAt: String,
        sessionId: String?,
        requiredSessionRevision: Long,
    ): AuthorizedRemoteAccess {
        val response =
            signedClient(requiredSessionRevision)
                .authorizeRemoteAccess(
                    AuthorizeRemoteAccessRequest(
                        credentialId = credentialId,
                        walletId = walletId,
                        grants = Grants(entries = grants.map { it.toWaas() }),
                        expiry = expiresAt,
                        chainId = network.id.toString(),
                        sessionId = sessionId,
                    ),
                )
        return AuthorizedRemoteAccess(
            walletId = walletId,
            sessionId = response.sessionId,
            expiresAt = response.expiry,
        )
    }

    suspend fun listAccessPage(
        walletId: String,
        page: Page?,
        type: AccessGrantType?,
        requiredSessionRevision: Long,
    ): AccessGrantPage =
        signedClient(requiredSessionRevision)
            .listAccess(
                ListAccessRequest(
                    walletId = walletId,
                    page = page?.toWaas(),
                    type = type?.toWaas(),
                ),
            ).toModel()

    suspend fun getRemoteAccessSession(
        sessionId: String,
        requiredSessionRevision: Long,
    ): RemoteAccessSession =
        signedClient(requiredSessionRevision)
            .getSession(GetSessionRequest(sessionId = sessionId))
            .session
            .toModel()

    suspend fun getRemoteAccessSessionUsage(
        sessionId: String,
        network: Network,
        requiredSessionRevision: Long,
    ): List<SmartSessionGrantUsage> =
        signedClient(requiredSessionRevision)
            .getSessionUsage(
                GetSessionUsageRequest(
                    sessionId = sessionId,
                    network = network.id.toString(),
                ),
            ).entries
            .map { it.toModel() }

    suspend fun getIdToken(
        walletId: String,
        ttlSeconds: UInt?,
        customClaims: Map<String, JsonElement>?,
        requiredSessionRevision: Long,
    ): String =
        signedClient(requiredSessionRevision)
            .getIDToken(
                GetIDTokenRequest(
                    walletId = walletId,
                    ttlSeconds = ttlSeconds,
                    customClaims = customClaims,
                ),
            ).idToken

    suspend fun getCredential(requiredSessionRevision: Long): WaasCredentialInfo =
        signedClient(requiredSessionRevision).getCredential().credential

    suspend fun revokeAccess(
        walletId: String,
        targetCredentialId: String,
        sessionId: String?,
        requiredSessionRevision: Long,
    ) {
        signedClient(requiredSessionRevision).revokeAccess(
            RevokeAccessRequest(
                targetCredentialId = targetCredentialId,
                walletId = walletId,
                sessionId = sessionId,
            ),
        )
    }

    private fun signedClient(
        requiredSessionRevision: Long,
        allowCredentialCreation: Boolean = false,
    ): WaasClient =
        WaasClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport = signedTransport(requiredSessionRevision, allowCredentialCreation),
        )

    private fun walletImportClient(requiredSessionRevision: Long): WaasClient {
        require(walletImportTrustedPcr0s != null) { "Wallet import is unavailable for this WaaS environment" }
        return WaasClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport = attestedSignedTransport(requiredSessionRevision, walletImportTrustedPcr0s),
        )
    }

    private fun attestedSignedTransport(
        requiredSessionRevision: Long,
        trustedPcr0s: Set<String>,
    ): LambdaWebRpcTransport =
        LambdaWebRpcTransport { baseUrl, path, body, headers ->
            val endpoint = resolveEndpoint(path)
            val requestPath = WaasApi.basePath + endpoint
            val deadline = SignedRequestDeadline(signedRequestTimeoutMillis)
            gatedSignedRequest(requiredSessionRevision, endpoint, deadline) {
                var sessionCredentialId: String? = null
                val signed =
                    try {
                        resendingRejectedNonce {
                            val authorization =
                                beforeSend {
                                    withContext(Dispatchers.IO) {
                                        authorizeSignedRequest(requiredSessionRevision, false, endpoint, body)
                                    }
                                }
                            val timeoutMillis = deadline.remainingMillisBeforeSend()
                            if (authorization.usesSessionCredential) sessionCredentialId = authorization.credentialId
                            val nonceBytes = ByteArray(18).also(SecureRandom()::nextBytes)
                            val nonce = WalletImportBase64.encode(nonceBytes)
                            val requestHeaders =
                                defaultSignedHeaders(headers, authorization.walletSignatureHeader).toMutableMap().apply {
                                    put("X-Attestation-Nonce", nonce)
                                }
                            val response =
                                transport.postJsonWithStatus(
                                    baseUrl = baseUrl,
                                    path = requestPath,
                                    body = body,
                                    headers = requestHeaders,
                                    timeoutMillis = timeoutMillis,
                                )
                            val document =
                                response.headers["x-attestation-document"]
                                    ?: throw technology.polygon.omswallet.OMSWalletAttestationException(
                                        message = "WaaS response is missing its attestation document",
                                    )
                            AttestationVerifier.verify(
                                encodedDocument = document,
                                method = "POST",
                                path = requestPath,
                                requestBody = body,
                                responseBody = response.body,
                                nonce = nonce,
                                trustedPcr0s = trustedPcr0s,
                            )
                            SignedResponse(authorization, response.statusCode, response.body)
                        }
                    } finally {
                        sessionCredentialId?.let { onSessionRequestCompleted(it, endpoint) }
                    }
                val answer =
                    observeSessionCredentialResponse(
                        signed.authorization,
                        endpoint,
                        signed.statusCode,
                        signed.body,
                    )
                WebRpcHttpResponse(signed.statusCode, signed.body) to answer
            }
        }

    private fun signedTransport(
        requiredSessionRevision: Long,
        allowCredentialCreation: Boolean,
    ): LambdaWebRpcTransport =
        LambdaWebRpcTransport { baseUrl, path, body, headers ->
            val endpoint = resolveEndpoint(path)
            val deadline = SignedRequestDeadline(signedRequestTimeoutMillis)
            gatedSignedRequest(requiredSessionRevision, endpoint, deadline) {
                var sessionCredentialId: String? = null
                val signed =
                    try {
                        resendingRejectedNonce {
                            val authorization =
                                beforeSend {
                                    withContext(Dispatchers.IO) {
                                        authorizeSignedRequest(
                                            requiredSessionRevision,
                                            allowCredentialCreation,
                                            endpoint,
                                            body,
                                        )
                                    }
                                }
                            val timeoutMillis = deadline.remainingMillisBeforeSend()
                            if (authorization.usesSessionCredential) sessionCredentialId = authorization.credentialId
                            val response =
                                transport.postJsonWithStatus(
                                    baseUrl = baseUrl,
                                    path = WaasApi.basePath + endpoint,
                                    body = body,
                                    headers = defaultSignedHeaders(headers, authorization.walletSignatureHeader),
                                    timeoutMillis = timeoutMillis,
                                )
                            SignedResponse(authorization, response.statusCode, response.body)
                        }
                    } finally {
                        sessionCredentialId?.let { onSessionRequestCompleted(it, endpoint) }
                    }
                val answer =
                    observeSessionCredentialResponse(
                        signed.authorization,
                        endpoint,
                        signed.statusCode,
                        signed.body,
                    )
                WebRpcHttpResponse(
                    statusCode = signed.statusCode,
                    body = signed.body,
                ) to answer
            }
        }

    /**
     * Sends a signed request, signing it again with a fresh nonce when WaaS rejects its nonce (412,
     * 7206 PreconditionFailed) because a concurrent request with a later nonce reached WaaS first.
     * WaaS rejects the nonce before running the method, so resending cannot repeat its effect.
     * After [REJECTED_NONCE_MAX_RETRIES] resends the last rejection is returned as is.
     */
    private suspend fun resendingRejectedNonce(send: suspend () -> SignedResponse): SignedResponse {
        repeat(REJECTED_NONCE_MAX_RETRIES) {
            val response = send()
            if (!response.isRejectedNonce()) return response
        }
        return send()
    }

    /** Runs a step before the HTTP call starts, reporting its failure as [SignedRequestNotSentException]. */
    private suspend fun <T> beforeSend(step: suspend () -> T): T =
        try {
            step()
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: SignedRequestNotSentException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw SignedRequestNotSentException(throwable)
        }

    private fun SignedResponse.isRejectedNonce(): Boolean =
        statusCode == 412 && decodeWebRpcError(statusCode, body).errorKind == ErrorKind.PRECONDITION_FAILED

    /**
     * Signs and sends one request through [signedRequestGate] (GetCredential is the refresh the
     * gate serializes, so it bypasses it), then reports an accepted or unauthorized session
     * credential once the request has left the gate so a background refresh can start.
     */
    private suspend fun gatedSignedRequest(
        requiredSessionRevision: Long,
        endpoint: String,
        deadline: SignedRequestDeadline,
        block: suspend () -> Pair<WebRpcHttpResponse, SessionCredentialAnswer?>,
    ): WebRpcHttpResponse {
        val (response, answer) =
            if (endpoint == WaasApi.GetCredential.path) {
                block()
            } else {
                signedRequestGate.request(signingCredentialId(), deadline, block)
            }
        when (answer) {
            SessionCredentialAnswer.Accepted -> onSessionCredentialAccepted(requiredSessionRevision)
            SessionCredentialAnswer.Unauthorized -> onSessionCredentialUnauthorized(requiredSessionRevision)
            null -> Unit
        }
        return response
    }

    /**
     * Reports how WaaS answered a request signed with the wallet session's credential, or null
     * when there is nothing to follow up. A 7202 KeyNotFound or 7203 KeyExpired rejection ends the
     * session and fails the request as expired.
     */
    private fun observeSessionCredentialResponse(
        authorization: SignedRequestAuthorization,
        endpoint: String,
        statusCode: Int,
        body: String,
    ): SessionCredentialAnswer? {
        if (!authorization.usesSessionCredential) return null
        val isCredentialRead = endpoint == WaasApi.GetCredential.path
        if (statusCode in 200..299) {
            return if (isCredentialRead) null else SessionCredentialAnswer.Accepted
        }
        val error = decodeWebRpcError(statusCode, body)
        if (error.errorKind == ErrorKind.KEY_NOT_FOUND || error.errorKind == ErrorKind.KEY_EXPIRED) {
            onSessionCredentialRejected(authorization.credentialId)
            throw OMSWalletSessionException(
                code = OMSWalletErrorCode.SessionExpired,
                message = "Wallet session expired",
                cause = error,
            )
        }
        // 7207 means a revoked credential or only a wallet access error; a credential read tells which.
        return if (!isCredentialRead && error.errorKind == ErrorKind.UNAUTHORIZED) SessionCredentialAnswer.Unauthorized else null
    }

    private fun resolveEndpoint(path: String): String =
        when {
            path.startsWith(WaasApi.basePath) -> path.removePrefix(WaasApi.basePath)
            path.startsWith("/") -> path
            else -> "/$path"
        }

    private fun defaultSignedHeaders(
        headers: Map<String, String>,
        walletSignatureHeader: String,
    ): Map<String, String> =
        linkedMapOf(
            OMSWalletEnvironment.accessKeyHeaderName to publishableKey(),
            "Accept" to "application/json",
            "Webrpc" to waasWebrpcHeaderValue,
            OMSWalletEnvironment.walletSignatureHeaderName to
                walletSignatureHeader.removePrefix(OMSWalletEnvironment.walletSignatureHeaderPrefix),
        ).apply {
            putAll(headers)
        }

    private fun defaultPublicHeaders(): Map<String, String> =
        mapOf(
            OMSWalletEnvironment.accessKeyHeaderName to publishableKey(),
            "Accept" to "application/json",
            "Webrpc" to waasWebrpcHeaderValue,
        )

    private fun technology.polygon.omswallet.internal.generated.waas.CommitVerifierResponse.toVerifierCommitment(): VerifierCommitment =
        VerifierCommitment(
            verifier = verifier,
            loginHint = loginHint,
            challenge = challenge,
        )

    private fun CompleteAuthResponse.toWalletAuthCompletion(): WalletAuthCompletion =
        WalletAuthCompletion(
            wallets = wallets.map { it.toModel() },
            nextWalletsCursor = page?.cursor?.takeIf { it.isNotBlank() },
            claimableWallets = claimable.orEmpty().map { it.toModel() },
            email = email,
            identity = identity,
            credential = credential.toWalletCredential(),
        )

    private fun WalletType.toWaas(): WaasWalletType =
        when (this) {
            WalletType.Ethereum -> WaasWalletType.Ethereum
            WalletType.Solana -> WaasWalletType.Solana
            WalletType.Tron -> WaasWalletType.Tron
            WalletType.UNKNOWN_DEFAULT -> WaasWalletType.UNKNOWN_DEFAULT
        }

    private fun WalletType.toNetworkFamily(): WaasNetworkFamily =
        when (this) {
            WalletType.Ethereum -> WaasNetworkFamily.EVM
            WalletType.Solana -> WaasNetworkFamily.Solana
            WalletType.Tron -> WaasNetworkFamily.Tron
            WalletType.UNKNOWN_DEFAULT -> WaasNetworkFamily.UNKNOWN_DEFAULT
        }

    private fun WalletImportCipherSuite.toWaas(): Ciphersuite =
        when (this) {
            WalletImportCipherSuite.X25519Sha256Aes256Gcm -> Ciphersuite.X25519_SHA256_AES_256_GCM
            WalletImportCipherSuite.X25519Sha256ChaCha20Poly1305 -> Ciphersuite.X25519_SHA256_ChaCha20_Poly1305
            WalletImportCipherSuite.P256Sha256Aes256Gcm -> Ciphersuite.P256_SHA256_AES_256_GCM
            WalletImportCipherSuite.P256Sha256ChaCha20Poly1305 -> Ciphersuite.P256_SHA256_ChaCha20_Poly1305
        }

    private fun WaasWalletType.toModel(): WalletType =
        when (this) {
            WaasWalletType.Ethereum -> WalletType.Ethereum
            WaasWalletType.Solana -> WalletType.Solana
            WaasWalletType.Tron -> WalletType.Tron
            WaasWalletType.UNKNOWN_DEFAULT -> WalletType.UNKNOWN_DEFAULT
        }

    private fun WaasNetworkFamily.toWalletType(): WalletType =
        when (this) {
            WaasNetworkFamily.EVM -> WalletType.Ethereum

            WaasNetworkFamily.Solana -> WalletType.Solana

            WaasNetworkFamily.Tron -> WalletType.Tron

            WaasNetworkFamily.UNKNOWN_DEFAULT -> throw OMSWalletResponseException(
                message = "Wallet response has an invalid networkFamily",
            )
        }

    private fun WaasKeyOrigin.toModel(): WalletKeyOrigin =
        when (this) {
            WaasKeyOrigin.Enclave -> WalletKeyOrigin.Enclave

            WaasKeyOrigin.Imported -> WalletKeyOrigin.Imported

            WaasKeyOrigin.UNKNOWN_DEFAULT -> throw OMSWalletResponseException(
                message = "Wallet response has an invalid keyOrigin",
            )
        }

    private fun TransactionMode.toWaas(): WaasTransactionMode =
        when (this) {
            TransactionMode.Native -> WaasTransactionMode.Native
            TransactionMode.Relayer -> WaasTransactionMode.Relayer
            TransactionMode.UNKNOWN_DEFAULT -> WaasTransactionMode.UNKNOWN_DEFAULT
        }

    private fun WaasTransactionStatus.toModel(): TransactionStatus =
        when (this) {
            WaasTransactionStatus.Quoted -> TransactionStatus.Quoted
            WaasTransactionStatus.Pending -> TransactionStatus.Pending
            WaasTransactionStatus.Executed -> TransactionStatus.Executed
            WaasTransactionStatus.Failed -> TransactionStatus.Failed
            WaasTransactionStatus.UNKNOWN_DEFAULT -> TransactionStatus.UNKNOWN_DEFAULT
        }

    private fun WaasWallet.toModel(): Wallet {
        val type =
            networkFamily?.toWalletType() ?: throw OMSWalletResponseException(
                message = "Wallet response is missing networkFamily",
            )
        // Checks the shape only; EIP-55 checksum casing is not enforced.
        if (type == WalletType.Ethereum && !address.isEthereumHexAddress()) {
            throw OMSWalletResponseException(message = "Ethereum wallet response has an invalid address")
        }
        return Wallet(
            id = id,
            type = type,
            address = address,
            reference = reference,
            keyOrigin =
                keyOrigin?.toModel() ?: throw OMSWalletResponseException(
                    message = "Wallet response is missing keyOrigin",
                ),
        )
    }

    private fun WaasFeeToken.toModel(): FeeToken =
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

    private fun WaasFeeOption.toModel(): FeeOption =
        FeeOption(
            token = token.toModel(),
            value = value,
            displayValue = displayValue,
        )

    private fun FeeOptionSelection.toWaas(): WaasFeeOptionSelection =
        WaasFeeOptionSelection(
            token = token,
            index = index,
        )

    private fun Page.toWaas(): WaasPage =
        WaasPage(
            limit = limit,
            cursor = cursor,
        )

    private fun AbiArg.toWaas(): WaasAbiArg =
        WaasAbiArg(
            type = type,
            value = value,
        )

    private fun WaasCredentialInfo.toWalletCredential(): WalletCredential =
        WalletCredential(
            credentialId = credentialId,
            expiresAt = expiresAt,
            isCaller = isCaller,
        )

    private fun WaasCredentialInfo.toModel(): AccessGrant =
        when (type) {
            WaasCredentialType.Direct -> {
                AccessGrant.Direct(toWalletCredential())
            }

            WaasCredentialType.Remote -> {
                AccessGrant.Remote(
                    credential = toWalletCredential(),
                    sessionId =
                        sessionId?.takeIf(String::isNotBlank)
                            ?: throw OMSWalletResponseException(message = "Remote access credential is missing sessionId"),
                    metadata =
                        metadata?.toModel()
                            ?: throw OMSWalletResponseException(message = "Remote access credential is missing metadata"),
                    grants =
                        grants?.entries?.map { it.toModel() }
                            ?: throw OMSWalletResponseException(message = "Remote access credential is missing grants"),
                )
            }

            WaasCredentialType.UNKNOWN_DEFAULT -> {
                throw OMSWalletResponseException(message = "Access response has an invalid credential type")
            }
        }

    private fun WaasListAccessResponse.toModel(): AccessGrantPage =
        AccessGrantPage(
            grants = credentials.map { it.toModel() },
            page =
                page?.let {
                    Page(
                        limit = it.limit,
                        cursor = it.cursor,
                    )
                },
        )

    private fun AccessGrantType.toWaas(): WaasCredentialType =
        when (this) {
            AccessGrantType.Direct -> WaasCredentialType.Direct
            AccessGrantType.Remote -> WaasCredentialType.Remote
        }

    private fun WaasCredentialMetadata.toModel(): RemoteCredentialMetadata =
        RemoteCredentialMetadata(
            appUrl = appUrl,
            appName = appName,
            appLogoUrl = appLogoUrl,
            custom = custom,
        )

    private fun SmartSessionGrant.toWaas(): Grant =
        when (this) {
            is SmartSessionGrant.NativeTransfer -> {
                require(to.isEthereumHexAddress()) { "Invalid native transfer recipient" }
                require(limit.signum() >= 0) { "Native transfer limit must be non-negative" }
                Grant(
                    kind = GrantKind.NativeTransfer,
                    nativeTransfer = NativeTransferGrant(to = to, limit = limit.toString()),
                )
            }

            is SmartSessionGrant.Erc20Transfer -> {
                require(token.isEthereumHexAddress()) { "Invalid ERC-20 token address" }
                require(to?.isEthereumHexAddress() != false) { "Invalid ERC-20 recipient" }
                require(limit.signum() >= 0) { "ERC-20 transfer limit must be non-negative" }
                Grant(
                    kind = GrantKind.ERC20Transfer,
                    erc20transfer =
                        ERC20TransferGrant(
                            token = token,
                            to = to,
                            limit = limit.toString(),
                            cumulative = cumulative,
                        ),
                )
            }
        }

    private fun Grant.toModel(): SmartSessionGrant =
        when (kind) {
            GrantKind.NativeTransfer -> {
                val entry =
                    nativeTransfer
                        ?: throw OMSWalletResponseException(message = "Session contains an invalid native transfer grant")
                requireResponseAddress(entry.to, "native transfer recipient")
                SmartSessionGrant.NativeTransfer(
                    to = entry.to,
                    limit = entry.limit.toUnsignedBigInteger("native transfer limit"),
                )
            }

            GrantKind.ERC20Transfer -> {
                val entry =
                    erc20transfer
                        ?: throw OMSWalletResponseException(message = "Session contains an invalid ERC-20 transfer grant")
                requireResponseAddress(entry.token, "ERC-20 token")
                entry.to?.let { requireResponseAddress(it, "ERC-20 recipient") }
                SmartSessionGrant.Erc20Transfer(
                    token = entry.token,
                    to = entry.to,
                    limit = entry.limit.toUnsignedBigInteger("ERC-20 transfer limit"),
                    cumulative = entry.cumulative,
                )
            }

            GrantKind.UNKNOWN_DEFAULT -> {
                throw OMSWalletResponseException(message = "Session contains an invalid grant")
            }
        }

    private fun WaasSessionInfo.toModel(): RemoteAccessSession {
        val parsedChainId = chainId.toIntOrNull()
        if (parsedChainId == null || parsedChainId <= 0 || parsedChainId.toString() != chainId) {
            throw OMSWalletResponseException(message = "Session contains an invalid chain ID")
        }
        requireResponseAddress(signerAddress, "signer address")
        if (sessionId.isBlank() || walletId.isBlank() || expiresAt.isBlank()) {
            throw OMSWalletResponseException(message = "Session response is missing required fields")
        }
        return RemoteAccessSession(
            sessionId = sessionId,
            walletId = walletId,
            signerAddress = signerAddress,
            grants = grants.entries.map { it.toModel() },
            chainId = parsedChainId,
            expiresAt = expiresAt,
        )
    }

    private fun WaasGrantUsage.toModel(): SmartSessionGrantUsage =
        SmartSessionGrantUsage(
            grant = grant.toModel(),
            used = used?.toUnsignedBigInteger("grant usage"),
        )

    private fun String.toUnsignedBigInteger(field: String): BigInteger {
        val parsed = toBigIntegerOrNull()
        if (parsed == null || parsed.signum() < 0 || parsed.toString() != this) {
            throw OMSWalletResponseException(message = "Session contains an invalid $field")
        }
        return parsed
    }

    private fun requireResponseAddress(
        value: String,
        field: String,
    ) {
        if (!value.isEthereumHexAddress()) {
            throw OMSWalletResponseException(message = "Session contains an invalid $field")
        }
    }

    private fun WaasTransactionStatusResponse.toModel(): TransactionStatusResponse =
        TransactionStatusResponse(
            status = status.toModel(),
            txnHash = txnHash,
        )

    private fun PrepareResponse.toPreparedWalletTransaction(): PreparedWalletTransaction =
        PreparedWalletTransaction(
            txnId = txnId,
            sponsored = sponsored,
            feeOptions = feeOptions.map { it.toModel() },
        )

    companion object {
        // Mirrors the generated TypeScript/Swift WebrpcHeaderValue; the Kotlin target emits no
        // header constant, so the generator versions come from the WaasWalletClient.kt header.
        private const val waasWebrpcHeaderValue =
            "webrpc@v0.47.0;kotlin@v0.47.0;waas@$WEBRPC_SCHEMA_VERSION"
    }
}

private fun String.toWalletType(): WalletType =
    when (this) {
        WalletType.Ethereum.wireValue -> WalletType.Ethereum
        WalletType.Solana.wireValue -> WalletType.Solana
        WalletType.Tron.wireValue -> WalletType.Tron
        else -> WalletType.UNKNOWN_DEFAULT
    }

/** Shape-only `0x` + 40 hex check; EIP-55 checksum casing is not enforced. */
@JvmSynthetic
internal fun String.isEthereumHexAddress(): Boolean = length == 42 && startsWith("0x") && drop(2).all { it.digitToIntOrNull(16) != null }

internal fun interface SessionExpiryScheduler {
    fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): SessionExpiryTask
}

internal fun interface SessionExpiryTask {
    fun cancel()
}

internal fun interface SessionExpiryDispatcher {
    fun dispatch(action: () -> Unit)
}

/** Starts fire-and-forget credential expiry refreshes off the caller's thread. */
internal fun interface CredentialRefreshLauncher {
    fun launch(block: suspend () -> Unit)
}

private object BackgroundCredentialRefreshLauncher : CredentialRefreshLauncher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (_: Throwable) {
                // Refresh outcomes are handled by the block; nothing may escape to the app.
            }
        }
    }
}

private object TimerSessionExpiryScheduler : SessionExpiryScheduler {
    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): SessionExpiryTask {
        val timer = Timer("oms-wallet-session-expiry", true)
        val task =
            object : TimerTask() {
                override fun run() {
                    action()
                }
            }
        timer.schedule(task, delayMillis)
        return SessionExpiryTask {
            task.cancel()
            timer.cancel()
        }
    }
}

private object AndroidMainThreadSessionExpiryDispatcher : SessionExpiryDispatcher {
    override fun dispatch(action: () -> Unit) {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (mainLooper == null || runCatching { Looper.myLooper() }.getOrNull() == mainLooper) {
            action()
            return
        }

        val posted = runCatching { Handler(mainLooper).post(action) }.getOrDefault(false)
        if (!posted) {
            action()
        }
    }
}

private fun OMSWalletSessionSnapshot.isExpired(referenceTime: Long): Boolean {
    val expiresAt = expiresAtEpochMillis() ?: return false
    return expiresAt <= referenceTime
}

private fun OMSWalletSessionSnapshot.expiresAtEpochMillis(): Long? = expiresAt?.let(OMSWalletIsoTimestamps::parseEpochMillis)

/** Public session metadata for an active wallet session; null while signed out or pending. */
private fun OMSWalletSessionSnapshot.toPublicSession(): OMSWalletSession? {
    if (wallet == null) return null
    val expiresAt = expiresAt?.takeIf(String::isNotBlank) ?: return null
    val auth = auth ?: return null
    return OMSWalletSession(expiresAt = expiresAt, auth = auth)
}

/** [OMSWalletSessionExpiredEvent.wallet] is null when a manual wallet selection was pending. */
private fun OMSWalletSessionSnapshot.toSessionExpiredEvent(): OMSWalletSessionExpiredEvent? {
    expiresAtEpochMillis() ?: return null
    val expiredAt = expiresAt ?: return null
    val auth = auth ?: return null
    return OMSWalletSessionExpiredEvent(
        wallet = wallet,
        session = OMSWalletSession(expiresAt = expiredAt, auth = auth),
        expiredAt = expiredAt,
    )
}

private const val CREDENTIAL_REFRESH_INTERVAL_MILLIS: Long = 6 * 60 * 60 * 1000L
private const val CREDENTIAL_RETRY_INITIAL_DELAY_MILLIS: Long = 60_000L
private const val CREDENTIAL_RETRY_MAX_DELAY_MILLIS: Long = 15 * 60_000L

/** How long after a WebrpcBadRoute answer to GetCredential the SDK skips refreshes before probing again. */
private const val CREDENTIAL_REFRESH_UNSUPPORTED_COOLDOWN_MILLIS: Long = 15 * 60_000L

/** How long after the predicted WaaS expiry the expiry timer waits before reading an idle session's credential. */
private const val IDLE_SESSION_CHECK_GRACE_MILLIS: Long = 5 * 60_000L

/** Execute errors WaaS raises before it submits anything, so they are request errors. */
private val EXECUTE_REFUSED_BEFORE_SUBMISSION =
    setOf(
        ErrorKind.INVALID_SIGNATURE,
        ErrorKind.USAGE_LIMIT_EXCEEDED,
        ErrorKind.PRECONDITION_FAILED,
        ErrorKind.UNAUTHORIZED,
    )

/**
 * Tron Execute also refuses before submitting with InvalidRequest (7200) and InvalidFeeOption (7310):
 * WaaS v1.2.0 raises them only while re-pricing the transaction, before it marks it pending and
 * broadcasts (rpc/tron_transaction.go:220-262, after the checks in rpc/transaction.go:246-298).
 * TransactionExpired is not included: WaaS also returns it for a transaction an earlier Execute
 * already claimed, which may have been broadcast.
 */
private val TRON_EXECUTE_REFUSED_BEFORE_SUBMISSION =
    EXECUTE_REFUSED_BEFORE_SUBMISSION + setOf(ErrorKind.INVALID_REQUEST, ErrorKind.INVALID_FEE_OPTION)

/** One deadline per signed request, covering its gate wait, signing, nonce resends, and HTTP calls. */
private const val SIGNED_REQUEST_TIMEOUT_MILLIS: Long = 30_000L

/** WaaS's SlideDampening: it skips an expiry slide shorter than this unless it reaches the ceiling. */
private const val SLIDE_DAMPENING_MILLIS: Long = 6 * 60 * 60_000L

/** How many times a signed request is re-signed after WaaS rejects its nonce (412, 7206 PreconditionFailed). */
private const val REJECTED_NONCE_MAX_RETRIES: Int = 2

private const val GOOGLE_ISSUER: String = "https://accounts.google.com"
private const val APPLE_ISSUER: String = "https://appleid.apple.com"
