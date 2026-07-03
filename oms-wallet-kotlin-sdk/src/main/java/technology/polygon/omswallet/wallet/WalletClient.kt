package technology.polygon.omswallet.wallet

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletSelectionException
import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.OMSWalletSessionExpiredEvent
import technology.polygon.omswallet.OMSWalletSessionState
import technology.polygon.omswallet.OmsSdkErrorCode
import technology.polygon.omswallet.OmsSdkOperation
import technology.polygon.omswallet.OmsSessionException
import technology.polygon.omswallet.OmsTransactionException
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
import technology.polygon.omswallet.models.TokenBalance
import technology.polygon.omswallet.models.TransactionMode
import technology.polygon.omswallet.models.TransactionStatus
import technology.polygon.omswallet.models.TransactionStatusPollingOptions
import technology.polygon.omswallet.models.TransactionStatusResponse
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.runOmsOperation
import technology.polygon.omswallet.session.OMSWalletSession
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.toOmsSdkException
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
import technology.polygon.omswallet.models.SendTransactionRequest as ClientSendTransactionRequest
import technology.polygon.omswallet.models.SendTransactionResponse as ClientSendTransactionResponse

class WalletClient internal constructor(
    private val publishableKey: String,
    private val projectId: String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient = OMSWalletHttpClient(),
    private val session: OMSWalletSession = OMSWalletSession(),
    private val sessionStore: OMSWalletSessionMetadataStore? = null,
    private val oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
    private val oidcNonceGenerator: () -> String = OidcRedirectAuth::generateNonce,
    private val credentialSigner: CredentialSigner? = null,
    private val fastTransactionStatusPollIntervalMillis: Long = 400L,
    private val fastTransactionStatusPollCount: Int = 5,
    private val transactionStatusPollIntervalMillis: Long = 2_000L,
    private val transactionStatusPollTimeoutMillis: Long = 60_000L,
    private val transactionStatusDelay: suspend (Long) -> Unit = { delay(it) },
    private val sessionExpiryScheduler: SessionExpiryScheduler = TimerSessionExpiryScheduler,
    private val sessionExpiryDispatcher: SessionExpiryDispatcher = AndroidMainThreadSessionExpiryDispatcher,
    private val now: () -> Long = OMSWalletTimestamps::nowMilliseconds,
) {
    companion object {
        /**
         * Default requested WaaS wallet session lifetime in seconds.
         */
        const val DEFAULT_SESSION_LIFETIME_SECONDS: Long = 604_800L
    }

    private val signer: CredentialSigner = credentialSigner ?: MissingCredentialSigner
    private val gateway: WaasWalletGateway =
        WaasWalletGateway(
            publishableKey = publishableKey,
            projectId = projectId,
            environment = environment,
            transport = transport,
            signer = signer,
        )
    private val indexerClient: IndexerClient =
        IndexerClient(
            publishableKey = publishableKey,
            environment = environment,
            transport = transport,
        )
    private val sessionExpiryLock = Any()
    private val sessionExpiredListeners = mutableSetOf<(OMSWalletSessionExpiredEvent) -> Unit>()
    private var latestSessionExpiredEvent: OMSWalletSessionExpiredEvent? = null
    private var sessionExpiryTask: SessionExpiryTask? = null

    internal val hasPendingSignIn: Boolean
        get() {
            val snapshot = session.snapshot() ?: return false
            return snapshot.walletAddress.isNullOrBlank()
        }

    /**
     * Address of the currently selected wallet, or null when no wallet is selected.
     */
    val walletAddress: String?
        get() = session.snapshot()?.walletAddress

    /**
     * Registers a listener for expired wallet sessions.
     *
     * The latest expired-session event is replayed to new listeners until a new
     * auth flow, new wallet session, or [signOut] clears it. The returned
     * function unsubscribes the listener.
     */
    fun onSessionExpired(listener: (OMSWalletSessionExpiredEvent) -> Unit): () -> Unit {
        val replayEvent =
            synchronized(sessionExpiryLock) {
                sessionExpiredListeners += listener
                latestSessionExpiredEvent
            }
        replayEvent?.let { event ->
            dispatchSessionExpiredListener(listener, event)
        }
        return {
            synchronized(sessionExpiryLock) {
                sessionExpiredListeners -= listener
            }
        }
    }

    internal val signerAddress: String?
        get() = session.snapshot()?.signerAddress

    internal fun restoreSession(snapshot: OMSWalletSessionSnapshot) {
        clearLatestSessionExpiredEvent()
        session.restore(snapshot)
        scheduleSessionExpiry(snapshot)
    }

    internal fun snapshotSession(): OMSWalletSessionSnapshot? = session.snapshot()

    internal fun restorePersistedSession(): Boolean {
        val snapshot = sessionStore?.load() ?: return false
        if (snapshot.isExpired(now())) {
            expireSession(snapshot)
            return false
        }
        val isRestorable =
            !snapshot.walletId.isNullOrBlank() &&
                !snapshot.walletAddress.isNullOrBlank() &&
                !snapshot.signerAddress.isNullOrBlank() &&
                snapshot.auth != null &&
                snapshot.signerKeyType == signer.signingAlgorithm &&
                signer.hasCredential()
        if (!isRestorable) {
            sessionStore.clear()
            return false
        }
        clearLatestSessionExpiredEvent()
        session.restore(snapshot)
        scheduleSessionExpiry(snapshot)
        return true
    }

    fun signOut() {
        clearSession(clearOidcRedirectAuth = true)
    }

    private fun clearSession(
        clearOidcRedirectAuth: Boolean,
        clearSessionStore: Boolean = true,
        clearExpiredEvent: Boolean = true,
    ) {
        session.clear()
        clearSessionExpiryTask()
        signer.clear()
        if (clearSessionStore) {
            sessionStore?.clear()
        }
        if (clearOidcRedirectAuth) {
            clearPendingOidcRedirectAuth()
        }
        if (clearExpiredEvent) {
            clearLatestSessionExpiredEvent()
        }
    }

    private fun clearPendingOidcRedirectAuth() {
        oidcRedirectAuthStore?.clear()
    }

    private fun requireWalletId(): String =
        requireActiveWalletSession(operation = null).walletId
            ?: throw OmsSessionException(message = "No wallet selected")

    private fun requireWalletAddress(): String =
        requireActiveWalletSession(operation = null).walletAddress
            ?: throw OmsSessionException(message = "No wallet selected")

    suspend fun startEmailAuth(email: String): Unit =
        runOmsOperation(OmsSdkOperation.WalletStartEmailAuth) {
            clearSession(clearOidcRedirectAuth = true)
            try {
                val signerAddress = signer.credentialId()
                val response = gateway.commitEmailVerifier(email)

                session.replaceForPendingAuth(
                    challenge = response.challenge,
                    verifier = response.verifier,
                    signerAddress = signerAddress,
                    signerKeyType = signer.signingAlgorithm,
                )
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                signOut()
                throw throwable
            }
        }

    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
        walletType: WalletType = environment.defaultWalletType,
        sessionLifetimeSeconds: Long = DEFAULT_SESSION_LIFETIME_SECONDS,
        provider: String? = null,
        providerLabel: String? = null,
    ): CompleteAuthResult =
        runOmsOperation(OmsSdkOperation.WalletSignInWithOidcIdToken) {
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
        clearSession(clearOidcRedirectAuth = true)
        try {
            val signerAddress = signer.credentialId()
            val response =
                gateway.commitOidcIdTokenVerifier(
                    idToken = idToken,
                    issuer = issuer,
                    audience = audience,
                )

            session.replaceForPendingAuth(
                challenge = response.challenge,
                verifier = response.verifier,
                signerAddress = signerAddress,
                signerKeyType = signer.signingAlgorithm,
            )

            val auth =
                try {
                    completeOidcIdTokenSignIn(
                        idToken = idToken,
                        sessionLifetimeSeconds = sessionLifetimeSeconds,
                    )
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    signOut()
                    throw throwable
                }
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
            )
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            signOut()
            throw throwable
        }
    }

    suspend fun startOidcRedirectAuth(
        provider: OidcProviderConfig,
        redirectUri: String,
        walletType: WalletType = environment.defaultWalletType,
        walletSelection: WalletSelectionBehavior? = null,
        sessionLifetimeSeconds: Long? = null,
        relayRedirectUri: String? = provider.relayRedirectUri,
        authorizeParams: Map<String, String> = emptyMap(),
        loginHint: String? = null,
    ): StartOidcRedirectAuthResult =
        runOmsOperation(OmsSdkOperation.WalletStartOidcRedirectAuth) {
            val redirectAuthStore =
                requireNotNull(oidcRedirectAuthStore) {
                    "OIDC redirect auth requires an OIDC redirect auth store"
                }
            val previousSessionEmail = session.snapshot()?.auth?.email
            val requestedSessionLifetimeSeconds =
                sessionLifetimeSeconds?.also {
                    requireWaasSessionLifetimeSeconds(it)
                }
            clearSession(clearOidcRedirectAuth = true)
            try {
                val signerAddress = signer.credentialId()
                val authMode = provider.authMode
                val oauthRedirectUri = relayRedirectUri ?: redirectUri
                val response =
                    gateway.commitOidcRedirectVerifier(
                        provider = provider,
                        redirectUri = oauthRedirectUri,
                        authMode =
                            when (authMode) {
                                OidcRedirectAuthMode.AuthCode -> AuthMode.AuthCode
                                OidcRedirectAuthMode.AuthCodePKCE -> AuthMode.AuthCodePKCE
                            },
                    )
                val nonce = oidcNonceGenerator()
                val state =
                    OidcRedirectAuth.encodeState(
                        nonce = nonce,
                        scope = projectId,
                        redirectUri = redirectUri.takeIf { oauthRedirectUri != redirectUri },
                    )

                session.replaceForPendingAuth(
                    challenge = response.challenge,
                    verifier = response.verifier,
                    signerAddress = signerAddress,
                    signerKeyType = signer.signingAlgorithm,
                )
                redirectAuthStore.save(
                    PendingOidcRedirectAuth(
                        verifier = response.verifier,
                        challenge = response.challenge,
                        nonce = nonce,
                        authMode = authMode,
                        redirectUri = redirectUri,
                        issuer = provider.issuer,
                        provider = provider.provider ?: builtInOidcProviderForIssuer(provider.issuer),
                        providerLabel = provider.providerLabel ?: builtInOidcProviderLabelForIssuer(provider.issuer),
                        projectId = projectId,
                        walletType = walletType.wireValue,
                        walletSelection = walletSelection,
                        sessionLifetimeSeconds = requestedSessionLifetimeSeconds,
                        signerAddress = signerAddress,
                        signerKeyType = signer.signingAlgorithm,
                    ),
                )

                val authorizationUrl =
                    OidcRedirectAuth.buildAuthorizationUrl(
                        provider = provider,
                        redirectUri = oauthRedirectUri,
                        state = state,
                        challenge = response.challenge,
                        usePkce = authMode.usesPkce,
                        loginHint = loginHintForProvider(provider, loginHint ?: previousSessionEmail),
                        authorizeParams = provider.authorizeParams + authorizeParams,
                    )

                StartOidcRedirectAuthResult(
                    authorizationUrl = authorizationUrl,
                    state = state,
                    challenge = response.challenge,
                )
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                signOut()
                throw throwable
            }
        }

    suspend fun handleOidcRedirectCallback(
        callbackUrl: String?,
        walletSelection: WalletSelectionBehavior? = null,
        sessionLifetimeSeconds: Long? = null,
    ): OidcRedirectAuthResult {
        if (callbackUrl.isNullOrBlank()) {
            return OidcRedirectAuthResult.NotOidcRedirectCallback
        }

        val callback = OidcRedirectAuth.parseCallbackUrl(callbackUrl)
        if (!callback.hasOidcResponse) {
            return OidcRedirectAuthResult.NotOidcRedirectCallback
        }

        val redirectAuthStore = oidcRedirectAuthStore ?: return OidcRedirectAuthResult.NoPendingAuth
        val pending = redirectAuthStore.load() ?: return OidcRedirectAuthResult.NoPendingAuth
        if (!OidcRedirectAuth.matchesRedirectUri(callbackUrl, pending.redirectUri)) {
            return OidcRedirectAuthResult.NotOidcRedirectCallback
        }

        val state = callback.state ?: return OidcRedirectAuthResult.NotOidcRedirectCallback
        val stateMatches =
            runCatching {
                OidcRedirectAuth.validateState(state, pending)
            }.isSuccess
        if (!stateMatches) {
            return OidcRedirectAuthResult.NotOidcRedirectCallback
        }

        var clearPendingAuth = false
        return try {
            clearPendingAuth = true
            callback.error?.let { error ->
                throw IllegalStateException(callback.errorDescription ?: "OIDC provider returned error: $error")
            }
            val code = requireNotNull(callback.code) { "OIDC callback URL is missing code" }
            restorePendingOidcRedirectAuth(pending)
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
                )
            when (
                val result =
                    completeWalletAuth(
                        completeAuth = auth,
                        walletType = pending.walletType.toWalletType(),
                        walletSelection = resolvedWalletSelection,
                        sessionAuth = oidcRedirectSessionAuth(pending = pending, completeAuth = auth),
                    )
            ) {
                is CompleteAuthResult.WalletSelected -> {
                    OidcRedirectAuthResult.Completed(result.wallet)
                }

                is CompleteAuthResult.WalletSelection -> {
                    OidcRedirectAuthResult.WalletSelection(
                        pendingSelection = result.pendingSelection,
                    )
                }
            }
        } catch (throwable: CancellationException) {
            clearPendingAuth = false
            throw throwable
        } catch (throwable: Throwable) {
            val failure = throwable.toOmsSdkException(OmsSdkOperation.WalletHandleOidcRedirectCallback)
            clearSession(clearOidcRedirectAuth = false)
            OidcRedirectAuthResult.Failed(failure)
        } finally {
            if (clearPendingAuth) {
                redirectAuthStore.clear()
            }
        }
    }

    private suspend fun completeEmailSignIn(
        code: String,
        sessionLifetimeSeconds: UInt,
    ): WalletAuthCompletion {
        val snapshot =
            try {
                session.requirePendingAuth()
            } catch (throwable: IllegalStateException) {
                throw OmsSessionException(
                    operation = OmsSdkOperation.WalletCompleteEmailAuth,
                    message = "No pending email auth attempt",
                    cause = throwable,
                )
            }
        return gateway.completeEmailAuth(
            verifier = snapshot.verifier,
            challenge = snapshot.challenge,
            code = code,
            sessionLifetimeSeconds = sessionLifetimeSeconds,
        )
    }

    private suspend fun completeOidcIdTokenSignIn(
        idToken: String,
        sessionLifetimeSeconds: UInt,
    ): WalletAuthCompletion {
        val snapshot = session.requirePendingAuth()
        return gateway.completeOidcIdTokenAuth(
            verifier = snapshot.verifier,
            idToken = idToken,
            sessionLifetimeSeconds = sessionLifetimeSeconds,
        )
    }

    suspend fun completeEmailAuth(
        code: String,
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
        walletType: WalletType = environment.defaultWalletType,
        sessionLifetimeSeconds: Long = DEFAULT_SESSION_LIFETIME_SECONDS,
    ): CompleteAuthResult =
        runOmsOperation(OmsSdkOperation.WalletCompleteEmailAuth) {
            val auth =
                completeEmailSignIn(
                    code = code,
                    sessionLifetimeSeconds =
                        requireWaasSessionLifetimeSeconds(
                            sessionLifetimeSeconds,
                        ),
                )
            completeWalletAuth(
                completeAuth = auth,
                walletType = walletType,
                walletSelection = walletSelection,
                sessionAuth = OMSWalletEmailSessionAuth(email = auth.email),
            )
        }

    /**
     * Selects an existing wallet by its WaaS wallet id.
     */
    suspend fun useWallet(walletId: String): WalletSelectionResult =
        runOmsOperation(OmsSdkOperation.WalletUseWallet) {
            requireWalletSelectionOrActiveSession()
            requireActiveCredential()
            val wallet = requestUseWallet(walletId)
            session.selectWallet(
                walletId = wallet.id,
                walletAddress = wallet.address,
            )
            persistSelectedWallet(wallet)
        }

    /**
     * Creates and selects a new wallet for the authenticated user.
     */
    suspend fun createWallet(
        walletType: WalletType = environment.defaultWalletType,
        reference: String? = null,
    ): WalletSelectionResult =
        runOmsOperation(OmsSdkOperation.WalletCreateWallet) {
            requireWalletSelectionOrActiveSession()
            requireActiveCredential()
            val wallet = requestCreateWallet(walletType, reference)
            session.selectWallet(
                walletId = wallet.id,
                walletAddress = wallet.address,
            )
            persistSelectedWallet(wallet)
        }

    private suspend fun useWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletId: String,
    ): WalletSelectionResult {
        requirePendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
        requireActiveCredential()
        val wallet = requestUseWallet(walletId)
        session.selectWalletForPendingSelection(
            pendingWalletSelectionId = pendingWalletSelectionId,
            signerAddress = signerAddress,
            signerKeyType = signerKeyType,
            walletId = wallet.id,
            walletAddress = wallet.address,
        )
        return persistSelectedWallet(wallet)
    }

    private suspend fun createWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletType: WalletType,
        reference: String?,
    ): WalletSelectionResult {
        requirePendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
        requireActiveCredential()
        val wallet = requestCreateWallet(walletType, reference)
        session.selectWalletForPendingSelection(
            pendingWalletSelectionId = pendingWalletSelectionId,
            signerAddress = signerAddress,
            signerKeyType = signerKeyType,
            walletId = wallet.id,
            walletAddress = wallet.address,
        )
        return persistSelectedWallet(wallet)
    }

    private fun requirePendingWalletSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
    ) {
        expireCurrentSessionIfNeeded(operation = OmsSdkOperation.PendingWalletSelection)
        try {
            session.requirePendingWalletSelection(
                pendingWalletSelectionId = pendingWalletSelectionId,
                signerAddress = signerAddress,
                signerKeyType = signerKeyType,
            )
        } catch (throwable: IllegalStateException) {
            throw OMSWalletSelectionException(
                code = OmsSdkErrorCode.WalletSelectionStale,
                operation = OmsSdkOperation.PendingWalletSelection,
                message = throwable.message ?: "Pending wallet selection is no longer active",
                cause = throwable,
            )
        }
    }

    private suspend fun requestUseWallet(walletId: String): Wallet = gateway.useWallet(walletId)

    private suspend fun requestCreateWallet(
        walletType: WalletType,
        reference: String?,
    ): Wallet = gateway.createWallet(walletType, reference)

    private fun persistSelectedWallet(wallet: Wallet): WalletSelectionResult {
        persistCurrentSession()
        return WalletSelectionResult(
            walletAddress = wallet.address,
            wallet = wallet,
        )
    }

    /**
     * Lists all wallets available to the authenticated credential.
     */
    suspend fun listWallets(): List<Wallet> =
        runOmsOperation(OmsSdkOperation.WalletListWallets) {
            requireWalletSelectionOrActiveSession()
            requireActiveCredential()
            val wallets = mutableListOf<Wallet>()
            var cursor: String? = null
            do {
                val response =
                    gateway.listWallets(cursor)
                wallets += response.wallets
                cursor = response.nextCursor
            } while (cursor != null)
            wallets
        }

    private fun requireWalletSelectionOrActiveSession() {
        val snapshot = session.requireSnapshot()
        expireSnapshotIfNeeded(snapshot, operation = null)
        if (snapshot.walletId.isNullOrBlank() && snapshot.expiresAt.isNullOrBlank()) {
            throw OmsSessionException(message = "No authenticated wallet session")
        }
    }

    private suspend fun walletsFromAuthResponse(completeAuth: WalletAuthCompletion): List<Wallet> {
        val wallets = completeAuth.wallets.toMutableList()
        var cursor = completeAuth.nextWalletsCursor
        while (cursor != null) {
            val response = gateway.listWallets(cursor)
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
    ): CompleteAuthResult {
        val pendingWalletSelectionId =
            session.markAuthVerified(
                expiresAt = completeAuth.credential.expiresAt,
                auth = sessionAuth,
            )
        val pendingSnapshot = session.requireSnapshot()
        scheduleSessionExpiry(pendingSnapshot)
        val pendingSignerAddress = requireNotNull(pendingSnapshot.signerAddress) { "No active signer" }
        val pendingSignerKeyType = pendingSnapshot.signerKeyType
        return try {
            val wallets = walletsFromAuthResponse(completeAuth)
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
                            createWallet(walletType)
                        }

                        else -> {
                            useWallet(candidateWallets.first().id)
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
            throw throwable
        } catch (throwable: Throwable) {
            signOut()
            throw throwable
        }
    }

    private suspend fun restorePendingOidcRedirectAuth(pending: PendingOidcRedirectAuth) {
        requireActiveCredential()
        val currentSignerAddress = signer.credentialId()
        check(currentSignerAddress == pending.signerAddress) {
            "OIDC redirect auth signer mismatch"
        }
        check(pending.signerKeyType == signer.signingAlgorithm) {
            "OIDC redirect auth signer mismatch"
        }
        session.restore(
            OMSWalletSessionSnapshot(
                challenge = pending.challenge,
                verifier = pending.verifier,
                signerAddress = pending.signerAddress,
                signerKeyType = pending.signerKeyType,
            ),
        )
    }

    private fun persistCurrentSession() {
        val snapshot = session.snapshot() ?: return
        clearLatestSessionExpiredEvent()
        sessionStore?.save(snapshot)
        scheduleSessionExpiry(snapshot)
    }

    /**
     * Signs [message] with the currently selected wallet on [network].
     */
    suspend fun signMessage(
        network: Network,
        message: String,
    ): String =
        runOmsOperation(OmsSdkOperation.WalletSignMessage) {
            session.requireSnapshot()
            requireActiveCredential()
            gateway.signMessage(
                walletId = requireWalletId(),
                network = network,
                message = message,
            )
        }

    /**
     * Signs EIP-712 [typedData] with the currently selected wallet on [network].
     */
    suspend fun signTypedData(
        network: Network,
        typedData: JsonElement,
    ): String =
        runOmsOperation(OmsSdkOperation.WalletSignTypedData) {
            session.requireSnapshot()
            requireActiveCredential()
            gateway.signTypedData(
                walletId = requireWalletId(),
                network = network,
                typedData = typedData,
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
        runOmsOperation(OmsSdkOperation.WalletIsValidMessageSignature) {
            gateway.isValidMessageSignature(
                walletId = requireWalletId(),
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
        runOmsOperation(OmsSdkOperation.WalletIsValidTypedDataSignature) {
            gateway.isValidTypedDataSignature(
                walletId = requireWalletId(),
                network = network,
                typedData = typedData,
                signature = signature,
            )
        }

    /**
     * Sends a native-value transaction from the currently selected wallet on
     * [network].
     */
    suspend fun sendTransaction(
        network: Network,
        to: String,
        value: BigInteger,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): ClientSendTransactionResponse =
        sendTransaction(
            network = network,
            request =
                ClientSendTransactionRequest(
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
        request: ClientSendTransactionRequest,
        waitForStatus: Boolean = true,
        statusPolling: TransactionStatusPollingOptions? = null,
        selectFeeOption: FeeOptionSelector? = null,
    ): ClientSendTransactionResponse =
        runOmsOperation(OmsSdkOperation.WalletSendTransaction) {
            val snapshot = session.requireSnapshot()
            require(request.value.signum() >= 0) { "Transaction value must be non-negative" }
            requireActiveCredential()
            val prepared =
                gateway.prepareEthereumTransaction(
                    walletId = requireWalletId(),
                    network = network,
                    request = request,
                )
            executePreparedTransaction(
                network = network,
                walletAddress = snapshot.walletAddress ?: throw OmsSessionException(message = "No wallet selected"),
                prepared = prepared,
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
    ): ClientSendTransactionResponse =
        runOmsOperation(OmsSdkOperation.WalletCallContract) {
            val snapshot = session.requireSnapshot()
            requireActiveCredential()
            val prepared =
                gateway.prepareEthereumContractCall(
                    walletId = requireWalletId(),
                    network = network,
                    contract = contract,
                    method = method,
                    args = args,
                    mode = mode,
                )
            executePreparedTransaction(
                network = network,
                walletAddress = snapshot.walletAddress ?: throw OmsSessionException(message = "No wallet selected"),
                prepared = prepared,
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
        runOmsOperation(OmsSdkOperation.WalletGetTransactionStatus) {
            requireActiveWalletSession(OmsSdkOperation.WalletGetTransactionStatus)
            requireActiveCredential()
            gateway.transactionStatus(txnId)
        }

    /**
     * Returns all credentials that currently have access to the selected wallet.
     *
     * When [pageSize] is provided, the SDK follows WaaS cursors using that page
     * size and returns the combined credential list.
     */
    suspend fun listAccess(pageSize: UInt? = null): List<CredentialInfo> =
        runOmsOperation(OmsSdkOperation.WalletListAccess) {
            session.requireSnapshot()
            requireActiveCredential()
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
            var cursor: String? = null
            do {
                val response =
                    runOmsOperation(OmsSdkOperation.WalletListAccessPages) {
                        requestListAccessPage(
                            pageSize = pageSize,
                            cursor = cursor,
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
        runOmsOperation(OmsSdkOperation.WalletListAccessPage) {
            requestListAccessPage(pageSize, cursor)
        }

    private suspend fun requestListAccessPage(
        pageSize: UInt?,
        cursor: String?,
    ): ListAccessResponse {
        session.requireSnapshot()
        requireActiveCredential()
        return gateway.listAccessPage(
            walletId = requireWalletId(),
            page = accessPage(pageSize, cursor),
        )
    }

    /**
     * Returns an ID token for the currently selected wallet.
     */
    suspend fun getIdToken(
        ttlSeconds: UInt? = null,
        customClaims: Map<String, JsonElement>? = null,
    ): String =
        runOmsOperation(OmsSdkOperation.WalletGetIdToken) {
            session.requireSnapshot()
            requireActiveCredential()
            gateway.getIdToken(
                walletId = requireWalletId(),
                ttlSeconds = ttlSeconds,
                customClaims = customClaims,
            )
        }

    /**
     * Revokes a credential's access to the selected wallet.
     *
     * Use [listAccess] or [listAccessPage] to find credential IDs.
     */
    suspend fun revokeAccess(targetCredentialId: String): Unit =
        runOmsOperation(OmsSdkOperation.WalletRevokeAccess) {
            session.requireSnapshot()
            requireActiveCredential()
            gateway.revokeAccess(
                walletId = requireWalletId(),
                targetCredentialId = targetCredentialId,
            )
        }

    private fun requireActiveCredential() {
        if (!signer.hasCredential()) {
            signOut()
            throw OmsSessionException(message = "No active wallet session")
        }
    }

    private fun requireActiveWalletSession(operation: OmsSdkOperation?): OMSWalletSessionSnapshot {
        val snapshot =
            session.snapshot()
                ?: throw OmsSessionException(operation = operation)
        expireSnapshotIfNeeded(snapshot, operation)
        if (snapshot.walletId.isNullOrBlank() || snapshot.walletAddress.isNullOrBlank()) {
            throw OmsSessionException(operation = operation, message = "No wallet selected")
        }
        return snapshot
    }

    private fun expireCurrentSessionIfNeeded(operation: OmsSdkOperation?) {
        val snapshot = session.snapshot() ?: return
        expireSnapshotIfNeeded(snapshot, operation)
    }

    private fun expireSnapshotIfNeeded(
        snapshot: OMSWalletSessionSnapshot,
        operation: OmsSdkOperation?,
    ) {
        if (!snapshot.isExpired(now())) {
            return
        }
        expireSession(snapshot)
        throw OmsSessionException(
            code = OmsSdkErrorCode.SessionExpired,
            operation = operation,
            message = "Wallet session expired",
        )
    }

    private fun scheduleSessionExpiry(snapshot: OMSWalletSessionSnapshot) {
        clearSessionExpiryTask()
        val expiresAt = snapshot.expiresAtEpochMillis() ?: return
        val delayMillis = maxOf(0L, expiresAt - now())
        val task =
            sessionExpiryScheduler.schedule(delayMillis) {
                sessionExpiryDispatcher.dispatch {
                    expireSessionFromTimer(snapshot)
                }
            }
        synchronized(sessionExpiryLock) {
            sessionExpiryTask = task
        }
    }

    private fun clearSessionExpiryTask() {
        val task =
            synchronized(sessionExpiryLock) {
                sessionExpiryTask.also {
                    sessionExpiryTask = null
                }
            }
        task?.cancel()
    }

    private fun clearLatestSessionExpiredEvent() {
        synchronized(sessionExpiryLock) {
            latestSessionExpiredEvent = null
        }
    }

    private fun expireSessionFromTimer(snapshot: OMSWalletSessionSnapshot) {
        if (session.snapshot() != snapshot) {
            return
        }
        if (!snapshot.isExpired(now())) {
            scheduleSessionExpiry(snapshot)
            return
        }
        expireSession(snapshot)
    }

    private fun expireSession(snapshot: OMSWalletSessionSnapshot) {
        val event = snapshot.toSessionExpiredEvent() ?: return
        session.clear()
        clearSessionExpiryTask()
        clearPendingOidcRedirectAuth()
        try {
            signer.clear()
        } catch (_: Throwable) {
            // Expiry notification should not depend on credential cleanup succeeding.
        }
        notifySessionExpired(event)
    }

    private fun notifySessionExpired(event: OMSWalletSessionExpiredEvent) {
        val listeners =
            synchronized(sessionExpiryLock) {
                latestSessionExpiredEvent = event
                sessionExpiredListeners.toList()
            }
        listeners.forEach { listener -> dispatchSessionExpiredListener(listener, event) }
    }

    private fun dispatchSessionExpiredListener(
        listener: (OMSWalletSessionExpiredEvent) -> Unit,
        event: OMSWalletSessionExpiredEvent,
    ) {
        sessionExpiryDispatcher.dispatch {
            val shouldNotify =
                synchronized(sessionExpiryLock) {
                    sessionExpiredListeners.contains(listener) && latestSessionExpiredEvent == event
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
        provider: OidcProviderConfig,
        loginHint: String?,
    ): String? = loginHint.takeIf { provider.issuer == GOOGLE_ISSUER }

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

    private fun builtInOidcProviderLabelForIssuer(issuer: String): String? =
        when (issuer) {
            GOOGLE_ISSUER -> "Google"
            APPLE_ISSUER -> "Apple"
            else -> null
        }

    private fun requireWaasSessionLifetimeSeconds(sessionLifetimeSeconds: Long): UInt {
        require(sessionLifetimeSeconds > 0L) {
            "sessionLifetimeSeconds must be a positive whole number"
        }
        require(sessionLifetimeSeconds <= MAX_WAAS_SESSION_LIFETIME_SECONDS) {
            "sessionLifetimeSeconds must be less than or equal to $MAX_WAAS_SESSION_LIFETIME_SECONDS"
        }
        return sessionLifetimeSeconds.toUInt()
    }

    private suspend fun executePreparedTransaction(
        network: Network,
        walletAddress: String,
        prepared: PreparedWalletTransaction,
        selectFeeOption: FeeOptionSelector?,
        waitForStatus: Boolean,
        statusPolling: TransactionStatusPollingOptions?,
    ): ClientSendTransactionResponse {
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
                gateway.execute(prepared.txnId, feeOption)
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                val sdkError = throwable.toOmsSdkException(OmsSdkOperation.WalletExecute)
                throw OmsTransactionException(
                    code = OmsSdkErrorCode.TransactionExecutionUnconfirmed,
                    operation = OmsSdkOperation.WalletExecute,
                    status = sdkError.status,
                    txnId = prepared.txnId,
                    retryable = false,
                    upstreamError = sdkError.upstreamError,
                    message = "Transaction execution failed before status could be confirmed",
                    cause = sdkError,
                )
            }
        if (!waitForStatus) {
            return ClientSendTransactionResponse(
                txnId = prepared.txnId,
                status = executed.status,
                txnHash = null,
            )
        }
        val status =
            waitForTransactionStatus(
                txnId = prepared.txnId,
                fallbackStatus = executed.status,
                options = statusPolling ?: defaultTransactionStatusPollingOptions(),
            )
        return ClientSendTransactionResponse(
            txnId = prepared.txnId,
            status = status.status.takeIf { it != TransactionStatus.UNKNOWN_DEFAULT } ?: executed.status,
            txnHash = status.txnHash,
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
                } ?: balances?.let {
                    TokenBalance(
                        contractType = "ERC20",
                        contractAddress = contractAddress,
                        accountAddress = walletAddress,
                        tokenId = null,
                        balance = "0",
                        blockHash = null,
                        blockNumber = null,
                        chainId = network.id.toLong(),
                    )
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
    ): TransactionStatusResponse {
        val deadline = System.currentTimeMillis() + options.timeoutMillis
        var lastStatus = TransactionStatusResponse(status = fallbackStatus)
        var completedStatusPolls = 0

        do {
            lastStatus =
                try {
                    gateway.transactionStatus(txnId)
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    val sdkError = throwable.toOmsSdkException(OmsSdkOperation.WalletTransactionStatus)
                    throw OmsTransactionException(
                        operation = OmsSdkOperation.WalletTransactionStatus,
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
                return lastStatus
            }
            if (lastStatus.status == TransactionStatus.UNKNOWN_DEFAULT) {
                return lastStatus
            }
            val remainingMillis = deadline - System.currentTimeMillis()
            if (remainingMillis <= 0L) {
                return lastStatus
            }
            val nextDelayMillis =
                if (completedStatusPolls < options.fastPollCount) {
                    options.fastPollIntervalMillis
                } else {
                    options.pollIntervalMillis
                }
            if (nextDelayMillis <= 0L) {
                return lastStatus
            }
            transactionStatusDelay(minOf(nextDelayMillis, remainingMillis))
        } while (true)
    }

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

private data class ExecutedWalletTransaction(
    val status: TransactionStatus,
)

private class WaasWalletGateway(
    private val publishableKey: String,
    private val projectId: String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient,
    private val signer: CredentialSigner,
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

    suspend fun commitEmailVerifier(email: String): VerifierCommitment =
        signedClient()
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
    ): VerifierCommitment =
        signedClient()
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
        provider: OidcProviderConfig,
        redirectUri: String,
        authMode: AuthMode,
    ): VerifierCommitment =
        signedClient()
            .commitVerifier(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = authMode,
                    metadata =
                        mapOf(
                            "iss" to provider.issuer,
                            "aud" to provider.clientId,
                            "redirect_uri" to redirectUri,
                        ),
                ),
            ).toVerifierCommitment()

    suspend fun completeEmailAuth(
        verifier: String,
        challenge: String,
        code: String,
        sessionLifetimeSeconds: UInt,
    ): WalletAuthCompletion =
        signedClient()
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
    ): WalletAuthCompletion =
        signedClient()
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
    ): WalletAuthCompletion =
        signedClient()
            .completeAuth(
                CompleteAuthRequest(
                    identityType = IdentityType.OIDC,
                    authMode = authMode,
                    verifier = verifier,
                    answer = code,
                    lifetime = sessionLifetimeSeconds,
                ),
            ).toWalletAuthCompletion()

    suspend fun useWallet(walletId: String): Wallet =
        signedClient()
            .useWallet(
                UseWalletRequest(
                    walletId = walletId,
                ),
            ).wallet
            .toModel()

    suspend fun createWallet(
        walletType: WalletType,
        reference: String?,
    ): Wallet =
        signedClient()
            .createWallet(
                CreateWalletRequest(
                    type = walletType.toWaas(),
                    reference = reference,
                ),
            ).wallet
            .toModel()

    suspend fun listWallets(cursor: String?): WalletsPage {
        val response =
            signedClient().listWallets(
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
    ): String =
        signedClient()
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
    ): String =
        signedClient()
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
        request: ClientSendTransactionRequest,
    ): PreparedWalletTransaction =
        signedClient()
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
    ): PreparedWalletTransaction =
        signedClient()
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
    ): ExecutedWalletTransaction =
        ExecutedWalletTransaction(
            status =
                signedClient()
                    .execute(
                        ExecuteRequest(
                            txnId = txnId,
                            feeOption = feeOption?.toWaas(),
                        ),
                    ).status
                    .toModel(),
        )

    suspend fun transactionStatus(txnId: String): TransactionStatusResponse =
        signedClient()
            .transactionStatus(TransactionStatusRequest(txnId = txnId))
            .toModel()

    suspend fun listAccessPage(
        walletId: String,
        page: Page?,
    ): ListAccessResponse =
        signedClient()
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
    ): String =
        signedClient()
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
    ) {
        signedClient().revokeAccess(
            RevokeAccessRequest(
                targetCredentialId = targetCredentialId,
                walletId = walletId,
            ),
        )
    }

    private fun signedClient(): WaasClient =
        WaasClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport = signedTransport(),
        )

    private fun signedTransport(): LambdaWebRpcTransport =
        LambdaWebRpcTransport { baseUrl, path, body, headers ->
            val endpoint = resolveEndpoint(path)
            val nonce = signer.nextNonce()
            val preimage =
                WalletRequestSigner.buildWalletRequestPreimage(
                    endpoint = endpoint,
                    nonce = nonce,
                    scope = projectId,
                    payload = body,
                    requestPathPrefix = WaasApi.basePath,
                )
            val walletSignatureHeader =
                WalletRequestSigner.buildWalletSignatureHeader(
                    signingAlgorithm = signer.signingAlgorithm,
                    scope = projectId,
                    credentialId = signer.credentialId(),
                    nonce = nonce,
                    signature = signer.sign(preimage),
                )
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

private fun OMSWalletSessionSnapshot.toSessionExpiredEvent(): OMSWalletSessionExpiredEvent? {
    expiresAtEpochMillis() ?: return null
    val expiredAt = expiresAt ?: return null
    return OMSWalletSessionExpiredEvent(
        session =
            OMSWalletSessionState(
                walletAddress = walletAddress,
                expiresAt = expiredAt,
                auth = auth,
            ),
        expiredAt = expiredAt,
    )
}

private const val MAX_WAAS_SESSION_LIFETIME_SECONDS: Long = 4_294_967_295L
private const val GOOGLE_ISSUER: String = "https://accounts.google.com"
private const val APPLE_ISSUER: String = "https://appleid.apple.com"
