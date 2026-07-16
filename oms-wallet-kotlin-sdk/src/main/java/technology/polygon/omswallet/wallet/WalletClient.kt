package technology.polygon.omswallet.wallet

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletSelectionException
import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.OMSWalletSessionException
import technology.polygon.omswallet.OMSWalletSessionExpiredEvent
import technology.polygon.omswallet.OMSWalletSessionState
import technology.polygon.omswallet.OMSWalletStorageException
import technology.polygon.omswallet.OMSWalletTransactionException
import technology.polygon.omswallet.OMSWalletValidationException
import technology.polygon.omswallet.indexer.IndexerClient
import technology.polygon.omswallet.internal.generated.waas.AuthMode
import technology.polygon.omswallet.internal.generated.waas.CommitVerifierRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthResponse
import technology.polygon.omswallet.internal.generated.waas.CreateWalletRequest
import technology.polygon.omswallet.internal.generated.waas.ExecuteRequest
import technology.polygon.omswallet.internal.generated.waas.GetIDTokenRequest
import technology.polygon.omswallet.internal.generated.waas.Identity
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.IsValidMessageSignatureRequest
import technology.polygon.omswallet.internal.generated.waas.IsValidTypedDataSignatureRequest
import technology.polygon.omswallet.internal.generated.waas.LambdaWebRpcTransport
import technology.polygon.omswallet.internal.generated.waas.ListAccessRequest
import technology.polygon.omswallet.internal.generated.waas.ListWalletsRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareEthereumContractCallRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareEthereumTransactionRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareResponse
import technology.polygon.omswallet.internal.generated.waas.RevokeAccessRequest
import technology.polygon.omswallet.internal.generated.waas.SignMessageRequest
import technology.polygon.omswallet.internal.generated.waas.SignTypedDataRequest
import technology.polygon.omswallet.internal.generated.waas.TransactionStatusRequest
import technology.polygon.omswallet.internal.generated.waas.UseWalletRequest
import technology.polygon.omswallet.internal.generated.waas.WEBRPC_SCHEMA_VERSION
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.WaasClient
import technology.polygon.omswallet.internal.generated.waas.WaasPublicClient
import technology.polygon.omswallet.internal.generated.waas.WebRpcHttpResponse
import technology.polygon.omswallet.models.AbiArg
import technology.polygon.omswallet.models.CredentialInfo
import technology.polygon.omswallet.models.FeeOption
import technology.polygon.omswallet.models.FeeOptionSelection
import technology.polygon.omswallet.models.FeeOptionSelector
import technology.polygon.omswallet.models.FeeOptionWithBalance
import technology.polygon.omswallet.models.FeeToken
import technology.polygon.omswallet.models.ListAccessResponse
import technology.polygon.omswallet.models.Page
import technology.polygon.omswallet.models.SendTransactionRequest
import technology.polygon.omswallet.models.SendTransactionResponse
import technology.polygon.omswallet.models.TokenBalance
import technology.polygon.omswallet.models.TransactionMode
import technology.polygon.omswallet.models.TransactionStatus
import technology.polygon.omswallet.models.TransactionStatusPollingOptions
import technology.polygon.omswallet.models.TransactionStatusResolution
import technology.polygon.omswallet.models.TransactionStatusResponse
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.runOMSWalletOperation
import technology.polygon.omswallet.session.OMSWalletSession
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.InvalidSessionMetadataException
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.toOMSWalletException
import technology.polygon.omswallet.utils.OMSWalletIsoTimestamps
import technology.polygon.omswallet.utils.OMSWalletTimestamps
import technology.polygon.omswallet.utils.formatUnits
import java.math.BigInteger
import java.util.Timer
import java.util.TimerTask
import technology.polygon.omswallet.internal.generated.waas.AbiArg as WaasAbiArg
import technology.polygon.omswallet.internal.generated.waas.CredentialInfo as WaasCredentialInfo
import technology.polygon.omswallet.internal.generated.waas.FeeOption as WaasFeeOption
import technology.polygon.omswallet.internal.generated.waas.FeeOptionSelection as WaasFeeOptionSelection
import technology.polygon.omswallet.internal.generated.waas.FeeToken as WaasFeeToken
import technology.polygon.omswallet.internal.generated.waas.ListAccessResponse as WaasListAccessResponse
import technology.polygon.omswallet.internal.generated.waas.Page as WaasPage
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
    val walletSession: OMSWalletSession,
    val sessionStore: OMSWalletSessionMetadataStore?,
    val oidcRedirectAuthStore: OidcRedirectAuthStore?,
    val signer: CredentialSigner,
    val sessionExpiryScheduler: SessionExpiryScheduler,
    val sessionExpiryDispatcher: SessionExpiryDispatcher,
    val now: () -> Long,
) {
    val lifecycleLock = Any()
    val sessionExpiredListeners = mutableSetOf<(OMSWalletSessionExpiredEvent) -> Unit>()
    var initialized = false
    var initializationCleanupPending = false
    var latestSessionExpiredEvent: OMSWalletSessionExpiredEvent? = null
    var latestSessionExpiredRevision: Long? = null
    var sessionExpiryTask: SessionExpiryTask? = null
    var pendingEmailAuth: PendingEmailAuth? = null
}

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
    private val fastTransactionStatusPollIntervalMillis: Long,
    private val fastTransactionStatusPollCount: Int,
    private val transactionStatusPollIntervalMillis: Long,
    private val transactionStatusPollTimeoutMillis: Long,
    private val transactionStatusDelay: suspend (Long) -> Unit,
) {
    companion object {
        /**
         * Default requested WaaS wallet session lifetime in seconds.
         */
        const val DEFAULT_SESSION_LIFETIME_SECONDS: Long = 604_800L

        /**
         * Maximum requested WaaS wallet session lifetime in seconds.
         */
        const val MAX_SESSION_LIFETIME_SECONDS: Long = 2_592_000L

        @JvmSynthetic
        internal fun create(
            publishableKey: String,
            projectId: String,
            environment: OMSWalletEnvironment,
            transport: OMSWalletHttpClient = OMSWalletHttpClient(),
            walletSession: OMSWalletSession? = null,
            sessionStore: OMSWalletSessionMetadataStore? = null,
            oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
            oidcNonceGenerator: () -> String = OidcRedirectAuth::generateNonce,
            credentialSigner: CredentialSigner? = null,
            fastTransactionStatusPollIntervalMillis: Long = 400L,
            fastTransactionStatusPollCount: Int = 5,
            transactionStatusPollIntervalMillis: Long = 2_000L,
            transactionStatusPollTimeoutMillis: Long = 60_000L,
            transactionStatusDelay: suspend (Long) -> Unit = { delay(it) },
            sessionExpiryScheduler: SessionExpiryScheduler = TimerSessionExpiryScheduler,
            sessionExpiryDispatcher: SessionExpiryDispatcher = AndroidMainThreadSessionExpiryDispatcher,
            now: () -> Long = OMSWalletTimestamps::nowMilliseconds,
            projectScopeKey: String? = null,
        ): WalletClient {
            val createRuntime = {
                WalletScopeRuntime(
                    walletSession = walletSession ?: OMSWalletSession(),
                    sessionStore = sessionStore,
                    oidcRedirectAuthStore = oidcRedirectAuthStore,
                    signer = credentialSigner ?: MissingCredentialSigner,
                    sessionExpiryScheduler = sessionExpiryScheduler,
                    sessionExpiryDispatcher = sessionExpiryDispatcher,
                    now = now,
                )
            }
            val runtime =
                projectScopeKey?.let { scopeKey ->
                    WalletScopeRuntimeRegistry.runtimeFor(scopeKey, createRuntime)
                } ?: createRuntime()
            return WalletClient(
                publishableKey = publishableKey,
                projectId = projectId,
                environment = environment,
                transport = transport,
                runtime = runtime,
                oidcNonceGenerator = oidcNonceGenerator,
                fastTransactionStatusPollIntervalMillis = fastTransactionStatusPollIntervalMillis,
                fastTransactionStatusPollCount = fastTransactionStatusPollCount,
                transactionStatusPollIntervalMillis = transactionStatusPollIntervalMillis,
                transactionStatusPollTimeoutMillis = transactionStatusPollTimeoutMillis,
                transactionStatusDelay = transactionStatusDelay,
            )
        }
    }

    private val walletSession: OMSWalletSession = runtime.walletSession
    private val sessionStore: OMSWalletSessionMetadataStore? = runtime.sessionStore
    private val oidcRedirectAuthStore: OidcRedirectAuthStore? = runtime.oidcRedirectAuthStore
    private val signer: CredentialSigner = runtime.signer
    private val gateway: WaasWalletGateway =
        WaasWalletGateway(
            publishableKey = publishableKey,
            environment = environment,
            transport = transport,
            authorizeSignedRequest = ::authorizeSignedRequest,
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
     * Address of the currently selected wallet, or null when no wallet is selected.
     */
    val walletAddress: String?
        get() = synchronized(runtime.lifecycleLock) { walletSession.snapshot()?.walletAddress }

    /**
     * Snapshot of the current completed wallet-session state.
     *
     * Pending email OTP, OIDC redirect, and manual wallet-selection state are
     * intentionally not exposed here.
     */
    val session: OMSWalletSessionState
        get() = synchronized(runtime.lifecycleLock) { walletSession.snapshot().toSessionState() }

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

    internal fun restoreSession(snapshot: OMSWalletSessionSnapshot) {
        synchronized(runtime.lifecycleLock) {
            runtime.initialized = true
            clearLatestSessionExpiredEventLocked()
            val revision = walletSession.restore(snapshot)
            scheduleSessionExpiryLocked(snapshot, revision)
        }
    }

    internal fun snapshotSession(): OMSWalletSessionSnapshot? = synchronized(runtime.lifecycleLock) { walletSession.snapshot() }

    internal fun restorePersistedSession(): Boolean {
        var expiredNotification: ExpiryNotification? = null
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
                    !snapshot.walletId.isNullOrBlank() &&
                        !snapshot.walletAddress.isNullOrBlank() &&
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
                scheduleSessionExpiryLocked(snapshot, revision)
                clearConsumedOidcRedirectAuthLocked()
                runtime.initialized = true
                true
            }
        expiredNotification?.let(::dispatchSessionExpiredNotification)
        return restored
    }

    private fun clearInvalidPersistedSessionLocked() {
        clearSessionUnlocked(
            clearOidcRedirectAuth = true,
            clearSessionStore = true,
            clearExpiredEvent = true,
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
        clearSessionStore: Boolean = true,
        clearExpiredEvent: Boolean = true,
        operation: OMSWalletOperation? = null,
        throwOnFailure: Boolean = true,
        requiredSessionRevision: Long? = null,
    ): Long? =
        synchronized(runtime.lifecycleLock) {
            runtime.initialized = true
            clearSessionUnlocked(
                clearOidcRedirectAuth = clearOidcRedirectAuth,
                clearSessionStore = clearSessionStore,
                clearExpiredEvent = clearExpiredEvent,
                operation = operation,
                throwOnFailure = throwOnFailure,
                requiredSessionRevision = requiredSessionRevision,
            )
        }

    private fun clearSessionUnlocked(
        clearOidcRedirectAuth: Boolean,
        clearSessionStore: Boolean,
        clearExpiredEvent: Boolean,
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

        var cleanupFailure: Throwable? = null

        fun attemptCleanup(block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                cleanupFailure?.addSuppressed(throwable) ?: run { cleanupFailure = throwable }
            }
        }

        if (clearSessionStore) {
            attemptCleanup { sessionStore?.clear() }
        }
        if (clearOidcRedirectAuth) {
            attemptCleanup { oidcRedirectAuthStore?.clear() }
        }
        attemptCleanup(signer::clear)
        if (clearExpiredEvent) {
            clearLatestSessionExpiredEventLocked()
        }

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

    private fun clearPendingOidcRedirectAuth() {
        synchronized(runtime.lifecycleLock) {
            oidcRedirectAuthStore?.clear()
        }
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
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
        walletType: WalletType = WalletType.Ethereum,
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

            val auth =
                completeOidcIdTokenSignIn(
                    idToken = idToken,
                    sessionLifetimeSeconds = sessionLifetimeSeconds,
                    requiredSessionRevision = ownedSessionRevision,
                )
            return completeWalletAuth(
                completeAuth = auth,
                walletType = walletType,
                walletSelection = walletSelection,
                sessionAuth =
                    oidcIdTokenSessionAuth(
                        issuer = issuer,
                        provider = provider,
                        providerLabel = providerLabel,
                        completeAuth = auth,
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
                    authMode = OidcRedirectAuthMode.AuthCodePKCE,
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
                    issuer = "https://appleid.apple.com",
                    clientId = DEFAULT_APPLE_OIDC_CLIENT_ID,
                    authorizationUrl = "https://appleid.apple.com/auth/authorize",
                    providerName = "apple",
                    providerLabel = "Apple",
                    scopes = listOf("openid", "email"),
                    providerAuthorizeParams = mapOf("response_mode" to "form_post"),
                    authMode = OidcRedirectAuthMode.AuthCodePKCE,
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
        authorizeParams: Map<String, String> = emptyMap(),
        loginHint: String? = null,
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
        authMode: OidcRedirectAuthMode,
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
                                OidcRedirectAuthMode.AuthCode -> AuthMode.AuthCode
                                OidcRedirectAuthMode.AuthCodePKCE -> AuthMode.AuthCodePKCE
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

            var clearPendingAuth = false
            try {
                clearPendingAuth = true
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

                val auth =
                    gateway.completeOidcRedirectAuth(
                        verifier = pending.verifier,
                        code = code,
                        authMode =
                            when (pending.authMode) {
                                OidcRedirectAuthMode.AuthCode -> AuthMode.AuthCode
                                OidcRedirectAuthMode.AuthCodePKCE -> AuthMode.AuthCodePKCE
                            },
                        sessionLifetimeSeconds = validatedSessionLifetimeSeconds,
                        requiredSessionRevision = ownedSessionRevision,
                    )
                OidcRedirectAuthResult.Completed(
                    result =
                        completeWalletAuth(
                            completeAuth = auth,
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
                if (clearPendingAuth) {
                    clearPendingOidcRedirectAuthBestEffort(redirectAuthStore, pending)
                }
            }
        }

    private suspend fun completeEmailSignIn(
        code: String,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): WalletAuthCompletion {
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
        return gateway.completeEmailAuth(
            verifier = snapshot.verifier,
            challenge = snapshot.challenge,
            code = code,
            sessionLifetimeSeconds = sessionLifetimeSeconds,
            requiredSessionRevision = requiredSessionRevision,
        )
    }

    private suspend fun completeOidcIdTokenSignIn(
        idToken: String,
        sessionLifetimeSeconds: UInt,
        requiredSessionRevision: Long,
    ): WalletAuthCompletion {
        val snapshot =
            synchronized(runtime.lifecycleLock) {
                walletSession.requireRevision(requiredSessionRevision)
                walletSession.requirePendingAuth()
            }
        return gateway.completeOidcIdTokenAuth(
            verifier = snapshot.verifier,
            idToken = idToken,
            sessionLifetimeSeconds = sessionLifetimeSeconds,
            requiredSessionRevision = requiredSessionRevision,
        )
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
            val auth =
                completeEmailSignIn(
                    code = code,
                    sessionLifetimeSeconds = pendingEmailAuth.sessionLifetimeSeconds,
                    requiredSessionRevision = requiredSessionRevision,
                )
            val result =
                completeWalletAuth(
                    completeAuth = auth,
                    walletType = walletType,
                    walletSelection = walletSelection,
                    sessionAuth = OMSWalletEmailSessionAuth(email = auth.email ?: pendingEmailAuth.email),
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
    suspend fun useWallet(walletId: String): WalletSelectionResult =
        runOMSWalletOperation(OMSWalletOperation.WalletUseWallet) {
            useWalletForCurrentSession(walletId, requireWalletSelectionOrActiveSession())
        }

    private suspend fun useWalletForCurrentSession(
        walletId: String,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): WalletSelectionResult {
        requireWalletSelectionOrActiveSession(requiredSessionRevision)
        val wallet = requestUseWallet(walletId, requiredSessionRevision)
        return withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
            val selectedSessionRevision =
                walletSession.selectWallet(
                    walletId = wallet.id,
                    walletAddress = wallet.address,
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
    ): WalletSelectionResult =
        runOMSWalletOperation(OMSWalletOperation.WalletCreateWallet) {
            createWalletForCurrentSession(
                walletType,
                reference,
                requireWalletSelectionOrActiveSession(),
            )
        }

    private suspend fun createWalletForCurrentSession(
        walletType: WalletType,
        reference: String?,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): WalletSelectionResult {
        requireWalletSelectionOrActiveSession(requiredSessionRevision)
        val wallet = requestCreateWallet(walletType, reference, requiredSessionRevision)
        return withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
            val selectedSessionRevision =
                walletSession.selectWallet(
                    walletId = wallet.id,
                    walletAddress = wallet.address,
                    requiredRevision = requiredSessionRevision,
                )
            onSessionRevisionChanged?.invoke(selectedSessionRevision)
            persistSelectedWallet(wallet, selectedSessionRevision)
        }
    }

    private suspend fun useWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletId: String,
    ): WalletSelectionResult {
        val requiredSessionRevision =
            requirePendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
        val wallet = requestUseWallet(walletId, requiredSessionRevision)
        return synchronized(runtime.lifecycleLock) {
            walletSession.requireRevision(requiredSessionRevision)
            val selectedSessionRevision =
                walletSession.selectWalletForPendingSelection(
                    pendingWalletSelectionId = pendingWalletSelectionId,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    walletId = wallet.id,
                    walletAddress = wallet.address,
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
    ): WalletSelectionResult {
        val requiredSessionRevision =
            requirePendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
        val wallet = requestCreateWallet(walletType, reference, requiredSessionRevision)
        return synchronized(runtime.lifecycleLock) {
            walletSession.requireRevision(requiredSessionRevision)
            val selectedSessionRevision =
                walletSession.selectWalletForPendingSelection(
                    pendingWalletSelectionId = pendingWalletSelectionId,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    walletId = wallet.id,
                    walletAddress = wallet.address,
                )
            persistSelectedWallet(wallet, selectedSessionRevision)
        }
    }

    private fun requirePendingWalletSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
    ): Long {
        var expiredNotification: ExpiryNotification? = null
        val revision =
            synchronized(runtime.lifecycleLock) {
                val snapshot = walletSession.snapshot()
                if (snapshot?.isExpired(runtime.now()) == true) {
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
    ): WalletSelectionResult =
        synchronized(runtime.lifecycleLock) {
            try {
                persistCurrentSession(selectedSessionRevision)
                WalletSelectionResult(
                    walletAddress = wallet.address,
                    wallet = wallet,
                )
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
        completeAuth: WalletAuthCompletion,
        walletType: WalletType,
        walletSelection: WalletSelectionBehavior,
        sessionAuth: OMSWalletSessionAuth,
        requiredSessionRevision: Long,
        oidcRedirectAuthOwnership: PendingOidcRedirectAuth? = null,
        onSessionRevisionChanged: ((Long) -> Unit)? = null,
    ): CompleteAuthResult {
        val (pendingWalletSelectionId, pendingSessionRevision) =
            withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
                walletSession
                    .markAuthVerified(
                        expiresAt = completeAuth.credential.expiresAt,
                        auth = sessionAuth,
                        requiredRevision = requiredSessionRevision,
                    ).also { (_, revision) -> onSessionRevisionChanged?.invoke(revision) }
            }
        val pendingSnapshot =
            withOptionalOidcRedirectAuthOwnership(oidcRedirectAuthOwnership) {
                walletSession.requireRevision(pendingSessionRevision)
                walletSession.requireSnapshot().also {
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
                        ),
                )
            } else {
                val candidateWallets = wallets.filter { it.type == walletType }
                val selected =
                    when {
                        candidateWallets.isEmpty() -> {
                            createWalletForCurrentSession(
                                walletType = walletType,
                                reference = null,
                                requiredSessionRevision = pendingSessionRevision,
                                oidcRedirectAuthOwnership = oidcRedirectAuthOwnership,
                                onSessionRevisionChanged = onSessionRevisionChanged,
                            )
                        }

                        else -> {
                            useWalletForCurrentSession(
                                walletId = candidateWallets.first().id,
                                requiredSessionRevision = pendingSessionRevision,
                                oidcRedirectAuthOwnership = oidcRedirectAuthOwnership,
                                onSessionRevisionChanged = onSessionRevisionChanged,
                            )
                        }
                    }
                CompleteAuthResult.WalletSelected(
                    walletAddress = selected.walletAddress,
                    wallet = selected.wallet,
                    wallets = if (candidateWallets.isEmpty()) wallets + selected.wallet else wallets,
                    credential = completeAuth.credential,
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
            gateway.signMessage(
                walletId = activeSession.walletId,
                network = network,
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
            gateway.signTypedData(
                walletId = activeSession.walletId,
                network = network,
                typedData = typedData,
                requiredSessionRevision = activeSession.revision,
            )
        }

    /**
     * Validates [signature] for [message] through the WaaS public wallet RPC.
     */
    suspend fun isValidMessageSignature(
        network: Network,
        message: String,
        signature: String,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidMessageSignature) {
            val activeSession =
                requireActiveWalletSession(
                    OMSWalletOperation.WalletIsValidMessageSignature,
                    requireCredential = false,
                )
            gateway.isValidMessageSignature(
                walletId = activeSession.walletId,
                network = network,
                message = message,
                signature = signature,
            )
        }

    /**
     * Validates [signature] for EIP-712 [typedData] through the WaaS public wallet RPC.
     */
    suspend fun isValidTypedDataSignature(
        network: Network,
        typedData: JsonElement,
        signature: String,
    ): Boolean =
        runOMSWalletOperation(OMSWalletOperation.WalletIsValidTypedDataSignature) {
            val activeSession =
                requireActiveWalletSession(
                    OMSWalletOperation.WalletIsValidTypedDataSignature,
                    requireCredential = false,
                )
            gateway.isValidTypedDataSignature(
                walletId = activeSession.walletId,
                network = network,
                typedData = typedData,
                signature = signature,
            )
        }

    /**
     * Sends a transaction from the currently selected wallet on [network].
     *
     * This overload sends [value] to [to] without calldata.
     */
    suspend fun sendTransaction(
        network: Network,
        to: String,
        value: BigInteger,
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
            require(request.value.signum() >= 0) { "Transaction value must be non-negative" }
            val prepared =
                gateway.prepareEthereumTransaction(
                    walletId = activeSession.walletId,
                    network = network,
                    request = request,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                network = network,
                walletAddress = activeSession.walletAddress,
                prepared = prepared,
                requiredSessionRevision = activeSession.revision,
                selectFeeOption = selectFeeOption,
                waitForStatus = waitForStatus,
                statusPolling = statusPolling,
            )
        }

    /**
     * Calls a state-changing smart contract function through the WaaS
     * prepare/execute flow.
     */
    suspend fun callContract(
        network: Network,
        contract: String,
        method: String,
        args: List<AbiArg>? = null,
        mode: TransactionMode = TransactionMode.Relayer,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): SendTransactionResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletCallContract) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletCallContract)
            val prepared =
                gateway.prepareEthereumContractCall(
                    walletId = activeSession.walletId,
                    network = network,
                    contract = contract,
                    method = method,
                    args = args,
                    mode = mode,
                    requiredSessionRevision = activeSession.revision,
                )
            executePreparedTransaction(
                network = network,
                walletAddress = activeSession.walletAddress,
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

    /**
     * Returns all credentials that currently have access to the selected wallet.
     *
     * When [pageSize] is provided, the SDK follows WaaS cursors using that page
     * size and returns the combined credential list.
     */
    suspend fun listAccess(pageSize: UInt? = null): List<CredentialInfo> =
        runOMSWalletOperation(OMSWalletOperation.WalletListAccess) {
            val credentials = mutableListOf<CredentialInfo>()
            listAccessPages(pageSize = pageSize).collect { response ->
                credentials += response.credentials
            }
            credentials
        }

    /**
     * Emits credential-access pages for the selected wallet until WaaS stops
     * returning a cursor.
     */
    fun listAccessPages(pageSize: UInt? = null): Flow<ListAccessResponse> =
        flow {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletListAccessPages)
            var cursor: String? = null
            do {
                val response =
                    runOMSWalletOperation(OMSWalletOperation.WalletListAccessPages) {
                        requestListAccessPage(
                            pageSize = pageSize,
                            cursor = cursor,
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
    ): ListAccessResponse =
        runOMSWalletOperation(OMSWalletOperation.WalletListAccessPage) {
            requestListAccessPage(
                pageSize,
                cursor,
                requireActiveWalletSession(OMSWalletOperation.WalletListAccessPage),
            )
        }

    private suspend fun requestListAccessPage(
        pageSize: UInt?,
        cursor: String?,
        activeSession: ActiveWalletSession,
    ): ListAccessResponse =
        gateway.listAccessPage(
            walletId = activeSession.walletId,
            page = accessPage(pageSize, cursor),
            requiredSessionRevision = activeSession.revision,
        )

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
     * Revokes a credential's access to the selected wallet.
     *
     * Use [listAccess] or [listAccessPage] to find credential IDs.
     */
    suspend fun revokeAccess(targetCredentialId: String): Unit =
        runOMSWalletOperation(OMSWalletOperation.WalletRevokeAccess) {
            val activeSession = requireActiveWalletSession(OMSWalletOperation.WalletRevokeAccess)
            gateway.revokeAccess(
                walletId = activeSession.walletId,
                targetCredentialId = targetCredentialId,
                requiredSessionRevision = activeSession.revision,
            )
        }

    private fun requireActiveWalletSession(
        operation: OMSWalletOperation?,
        requireCredential: Boolean = true,
    ): ActiveWalletSession {
        var expiredNotification: ExpiryNotification? = null
        val activeSession =
            synchronized(runtime.lifecycleLock) {
                val snapshot =
                    walletSession.snapshot()
                        ?: throw OMSWalletSessionException(operation = operation)
                val revision = walletSession.revision()
                if (snapshot.isExpired(runtime.now())) {
                    expiredNotification = expireSessionLocked(snapshot, revision)
                    return@synchronized null
                }
                val walletId =
                    snapshot.walletId?.takeIf(String::isNotBlank)
                        ?: throw OMSWalletSessionException(operation = operation, message = "No wallet selected")
                val walletAddress =
                    snapshot.walletAddress?.takeIf(String::isNotBlank)
                        ?: throw OMSWalletSessionException(operation = operation, message = "No wallet selected")
                if (requireCredential) {
                    requireActiveCredentialLocked(revision, snapshot)
                }
                ActiveWalletSession(walletId, walletAddress, revision)
            }
        expiredNotification?.let(::dispatchSessionExpiredNotification)
        return activeSession
            ?: throw OMSWalletSessionException(
                code = OMSWalletErrorCode.SessionExpired,
                operation = operation,
                message = "Wallet session expired",
            )
    }

    private fun requireWalletSelectionOrActiveSession(requiredSessionRevision: Long? = null): Long {
        var expiredNotification: ExpiryNotification? = null
        val revision =
            synchronized(runtime.lifecycleLock) {
                requiredSessionRevision?.let(walletSession::requireRevision)
                val snapshot = walletSession.requireSnapshot()
                val currentRevision = walletSession.revision()
                if (snapshot.isExpired(runtime.now())) {
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
                clearSessionStore = true,
                clearExpiredEvent = true,
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
        val delayMillis = maxOf(0L, expiresAt - runtime.now())
        val task =
            runtime.sessionExpiryScheduler.schedule(delayMillis) {
                runtime.sessionExpiryDispatcher.dispatch {
                    expireSessionFromTimer(requiredSessionRevision)
                }
            }
        runtime.sessionExpiryTask = task
    }

    private fun clearSessionExpiryTaskLocked() {
        runtime.sessionExpiryTask.also { runtime.sessionExpiryTask = null }?.cancel()
    }

    private fun clearLatestSessionExpiredEventLocked() {
        runtime.latestSessionExpiredEvent = null
        runtime.latestSessionExpiredRevision = null
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
    ): String =
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
                    clearSessionStore = true,
                    clearExpiredEvent = true,
                    operation = null,
                    throwOnFailure = false,
                    requiredSessionRevision = requiredSessionRevision,
                )
                throw OMSWalletSessionException(message = "No active wallet session")
            }
            val nonce = signer.nextNonce()
            val preimage =
                WalletRequestSigner.buildWalletRequestPreimage(
                    endpoint = endpoint,
                    nonce = nonce,
                    scope = projectId,
                    payload = body,
                    requestPathPrefix = WaasApi.basePath,
                )
            WalletRequestSigner.buildWalletSignatureHeader(
                signingAlgorithm = signer.signingAlgorithm,
                scope = projectId,
                credentialId = actualSignerAddress,
                nonce = nonce,
                signature = signer.sign(preimage),
            )
        }

    private suspend fun executePreparedTransaction(
        network: Network,
        walletAddress: String,
        prepared: PreparedWalletTransaction,
        requiredSessionRevision: Long,
        selectFeeOption: FeeOptionSelector?,
        waitForStatus: Boolean,
        statusPolling: TransactionStatusPollingOptions?,
    ): SendTransactionResponse {
        if (waitForStatus) {
            requireValidTransactionStatusPollingOptions(
                statusPolling ?: defaultTransactionStatusPollingOptions(),
            )
        }
        val feeOption =
            when {
                prepared.sponsored -> {
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
                    selectFeeOption.select(
                        enrichFeeOptionsWithBalances(
                            network = network,
                            walletAddress = walletAddress,
                            feeOptions = prepared.feeOptions,
                        ),
                    ) ?: throw IllegalArgumentException(
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
                val sdkError = throwable.toOMSWalletException(OMSWalletOperation.WalletExecute)
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
                options = statusPolling ?: defaultTransactionStatusPollingOptions(),
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

        return feeOptions.map { feeOption ->
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
                balance = balance,
                available = balance?.balance?.formatTokenAmount(decimals),
                availableRaw = balance?.balance,
                decimals = decimals,
            )
        }
    }

    private fun String?.normalizeAddress(): String? =
        this
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()

    private fun FeeToken.isNativeToken(): Boolean =
        type.equals("native", ignoreCase = true) ||
            (contractAddress.isNullOrBlank() && tokenId.isNullOrBlank())

    private fun FeeToken.balanceDecimals(): Int? = decimals?.toInt() ?: if (isNativeToken()) 18 else null

    private fun String.formatTokenAmount(decimals: Int?): String =
        decimals?.let { scale ->
            runCatching { formatUnits(BigInteger(this), scale) }.getOrDefault(this)
        } ?: this

    private fun List<FeeOption>.defaultSelection(): FeeOptionSelection = FeeOptionSelection(first())

    private suspend fun waitForTransactionStatus(
        txnId: String,
        fallbackStatus: TransactionStatus,
        options: TransactionStatusPollingOptions,
        requiredSessionRevision: Long,
    ): ResolvedTransactionStatus {
        val deadline = System.currentTimeMillis() + options.timeoutMillis
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
                    options.fastPollIntervalMillis
                } else {
                    options.pollIntervalMillis
                }
            transactionStatusDelay(minOf(nextDelayMillis, remainingMillis))
        } while (true)
    }

    private fun requireValidTransactionStatusPollingOptions(options: TransactionStatusPollingOptions) {
        require(options.fastPollIntervalMillis > 0L) {
            "fastPollIntervalMillis must be greater than zero"
        }
        require(options.fastPollCount >= 0) {
            "fastPollCount must not be negative"
        }
        require(options.pollIntervalMillis > 0L) {
            "pollIntervalMillis must be greater than zero"
        }
        require(options.timeoutMillis >= 0L) {
            "timeoutMillis must not be negative"
        }
    }

    private data class ResolvedTransactionStatus(
        val response: TransactionStatusResponse,
        val resolution: TransactionStatusResolution,
    )

    private fun defaultTransactionStatusPollingOptions(): TransactionStatusPollingOptions =
        TransactionStatusPollingOptions(
            fastPollIntervalMillis = fastTransactionStatusPollIntervalMillis,
            fastPollCount = fastTransactionStatusPollCount,
            pollIntervalMillis = transactionStatusPollIntervalMillis,
            timeoutMillis = transactionStatusPollTimeoutMillis,
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
    val email: String?,
    val identity: Identity,
    val credential: CredentialInfo,
)

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
    val walletId: String,
    val walletAddress: String,
    val revision: Long,
)

private data class ExpiryNotification(
    val event: OMSWalletSessionExpiredEvent,
    val revision: Long,
    val listeners: List<(OMSWalletSessionExpiredEvent) -> Unit>,
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
    private val publishableKey: String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient,
    private val authorizeSignedRequest: (
        requiredSessionRevision: Long,
        allowCredentialCreation: Boolean,
        endpoint: String,
        body: String,
    ) -> String,
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

    suspend fun createWallet(
        walletType: WalletType,
        reference: String?,
        requiredSessionRevision: Long,
    ): Wallet =
        signedClient(requiredSessionRevision)
            .createWallet(
                CreateWalletRequest(
                    type = walletType.toWaas(),
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
        walletId: String,
        network: Network,
        message: String,
        signature: String,
    ): Boolean =
        publicClient
            .isValidMessageSignature(
                IsValidMessageSignatureRequest(
                    network = network.id.toString(),
                    walletId = walletId,
                    message = message,
                    signature = signature,
                ),
            ).isValid

    suspend fun isValidTypedDataSignature(
        walletId: String,
        network: Network,
        typedData: JsonElement,
        signature: String,
    ): Boolean =
        publicClient
            .isValidTypedDataSignature(
                IsValidTypedDataSignatureRequest(
                    network = network.id.toString(),
                    walletId = walletId,
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

    suspend fun listAccessPage(
        walletId: String,
        page: Page?,
        requiredSessionRevision: Long,
    ): ListAccessResponse =
        signedClient(requiredSessionRevision)
            .listAccess(
                ListAccessRequest(
                    walletId = walletId,
                    page = page?.toWaas(),
                ),
            ).toModel()

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

    suspend fun revokeAccess(
        walletId: String,
        targetCredentialId: String,
        requiredSessionRevision: Long,
    ) {
        signedClient(requiredSessionRevision).revokeAccess(
            RevokeAccessRequest(
                targetCredentialId = targetCredentialId,
                walletId = walletId,
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

    private fun signedTransport(
        requiredSessionRevision: Long,
        allowCredentialCreation: Boolean,
    ): LambdaWebRpcTransport =
        LambdaWebRpcTransport { baseUrl, path, body, headers ->
            val endpoint = resolveEndpoint(path)
            val walletSignatureHeader =
                withContext(Dispatchers.IO) {
                    authorizeSignedRequest(
                        requiredSessionRevision,
                        allowCredentialCreation,
                        endpoint,
                        body,
                    )
                }
            val response =
                transport.postJsonWithStatus(
                    baseUrl = baseUrl,
                    path = WaasApi.basePath + endpoint,
                    body = body,
                    headers = defaultSignedHeaders(headers, walletSignatureHeader),
                )
            WebRpcHttpResponse(
                statusCode = response.statusCode,
                body = response.body,
            )
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
            OMSWalletEnvironment.accessKeyHeaderName to publishableKey,
            "Accept" to "application/json",
            "Webrpc" to waasWebrpcHeaderValue,
            OMSWalletEnvironment.walletSignatureHeaderName to
                walletSignatureHeader.removePrefix(OMSWalletEnvironment.walletSignatureHeaderPrefix),
        ).apply {
            putAll(headers)
        }

    private fun defaultPublicHeaders(): Map<String, String> =
        mapOf(
            OMSWalletEnvironment.accessKeyHeaderName to publishableKey,
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
            email = email,
            identity = identity,
            credential = credential.toModel(),
        )

    private fun WalletType.toWaas(): WaasWalletType =
        when (this) {
            WalletType.Ethereum -> WaasWalletType.Ethereum
            WalletType.UNKNOWN_DEFAULT -> WaasWalletType.UNKNOWN_DEFAULT
        }

    private fun WaasWalletType.toModel(): WalletType =
        when (this) {
            WaasWalletType.Ethereum -> WalletType.Ethereum
            WaasWalletType.UNKNOWN_DEFAULT -> WalletType.UNKNOWN_DEFAULT
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

    private fun WaasWallet.toModel(): Wallet =
        Wallet(
            id = id,
            type = type.toModel(),
            address = address,
            reference = reference,
        )

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

    private fun FeeOptionSelection.toWaas(): WaasFeeOptionSelection = WaasFeeOptionSelection(token = token)

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

    private fun WaasCredentialInfo.toModel(): CredentialInfo =
        CredentialInfo(
            credentialId = credentialId,
            expiresAt = expiresAt,
            isCaller = isCaller,
        )

    private fun WaasListAccessResponse.toModel(): ListAccessResponse =
        ListAccessResponse(
            credentials = credentials.map { it.toModel() },
            page =
                page?.let {
                    Page(
                        limit = it.limit,
                        cursor = it.cursor,
                    )
                },
        )

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
        private const val waasWebrpcHeaderValue =
            "webrpc@v0.37.2;gen-kotlin@v0.3.2;waas@$WEBRPC_SCHEMA_VERSION"
    }
}

private fun String.toWalletType(): WalletType =
    when (this) {
        WalletType.Ethereum.wireValue -> WalletType.Ethereum
        else -> WalletType.UNKNOWN_DEFAULT
    }

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

private fun OMSWalletSessionSnapshot?.toSessionState(): OMSWalletSessionState {
    val snapshot = this ?: return OMSWalletSessionState(walletAddress = null)
    val walletAddress = snapshot.walletAddress
    if (snapshot.walletId.isNullOrBlank() || walletAddress.isNullOrBlank()) {
        return OMSWalletSessionState(walletAddress = null)
    }
    return OMSWalletSessionState(
        walletAddress = walletAddress,
        expiresAt = snapshot.expiresAt,
        auth = snapshot.auth,
    )
}

private fun OMSWalletSessionSnapshot.toSessionExpiredEvent(): OMSWalletSessionExpiredEvent? {
    expiresAtEpochMillis() ?: return null
    val expiredAt = expiresAt ?: return null
    return OMSWalletSessionExpiredEvent(
        session = toSessionState(),
        expiredAt = expiredAt,
    )
}

private const val GOOGLE_ISSUER: String = "https://accounts.google.com"
private const val APPLE_ISSUER: String = "https://appleid.apple.com"
