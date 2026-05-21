package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkOperation
import com.omsclient.kotlin_sdk.OmsSessionException
import com.omsclient.kotlin_sdk.OmsTransactionException
import com.omsclient.kotlin_sdk.OmsWalletSelectionException
import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthResponse
import com.omsclient.kotlin_sdk.generated.waas.CreateWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.ExecuteRequest
import com.omsclient.kotlin_sdk.generated.waas.GetIDTokenRequest
import com.omsclient.kotlin_sdk.generated.waas.Identity
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.IsValidMessageSignatureRequest
import com.omsclient.kotlin_sdk.generated.waas.IsValidTypedDataSignatureRequest
import com.omsclient.kotlin_sdk.generated.waas.ListAccessRequest
import com.omsclient.kotlin_sdk.generated.waas.ListWalletsRequest
import com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumContractCallRequest
import com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumTransactionRequest
import com.omsclient.kotlin_sdk.generated.waas.PrepareResponse
import com.omsclient.kotlin_sdk.generated.waas.RevokeAccessRequest
import com.omsclient.kotlin_sdk.generated.waas.SignMessageRequest
import com.omsclient.kotlin_sdk.generated.waas.SignTypedDataRequest
import com.omsclient.kotlin_sdk.generated.waas.SigningAlgorithm
import com.omsclient.kotlin_sdk.generated.waas.TransactionStatusRequest
import com.omsclient.kotlin_sdk.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletClient
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletPublicClient
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.models.AbiArg
import com.omsclient.kotlin_sdk.models.CredentialInfo
import com.omsclient.kotlin_sdk.models.FeeOption
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.FeeOptionSelector
import com.omsclient.kotlin_sdk.models.FeeOptionWithBalance
import com.omsclient.kotlin_sdk.models.FeeToken
import com.omsclient.kotlin_sdk.models.ListAccessResponse
import com.omsclient.kotlin_sdk.models.Page
import com.omsclient.kotlin_sdk.models.TokenBalance
import com.omsclient.kotlin_sdk.models.TransactionMode
import com.omsclient.kotlin_sdk.models.TransactionStatus
import com.omsclient.kotlin_sdk.models.TransactionStatusPollingOptions
import com.omsclient.kotlin_sdk.models.TransactionStatusResponse
import com.omsclient.kotlin_sdk.models.Wallet
import com.omsclient.kotlin_sdk.models.WalletType
import com.omsclient.kotlin_sdk.models.toModel
import com.omsclient.kotlin_sdk.models.toWaas
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.network.OMSClientWebRpcTransport
import com.omsclient.kotlin_sdk.runOmsOperation
import com.omsclient.kotlin_sdk.session.OMSClientSession
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.storage.OMSClientSecureSessionStore
import com.omsclient.kotlin_sdk.utils.OMSClientTimestamps
import com.omsclient.kotlin_sdk.utils.formatUnits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import java.math.BigInteger
import com.omsclient.kotlin_sdk.generated.waas.Wallet as WaasWallet
import com.omsclient.kotlin_sdk.models.SendTransactionRequest as ClientSendTransactionRequest
import com.omsclient.kotlin_sdk.models.SendTransactionResponse as ClientSendTransactionResponse

class WalletClient internal constructor(
    private val publicApiKey: String,
    private val projectId: String,
    private val environment: OMSClientEnvironment,
    private val transport: OMSClientHttpClient = OMSClientHttpClient(),
    private val session: OMSClientSession = OMSClientSession(),
    private val sessionStore: OMSClientSecureSessionStore? = null,
    private val oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
    private val nonceGenerator: () -> Long = OMSClientTimestamps::nextNonce,
    private val oidcNonceGenerator: () -> String = OidcRedirectAuth::generateNonce,
    private val privateKeyFactory: () -> ByteArray = WalletRequestSigner::generatePrivateKeyBytes,
    private val credentialSigner: CredentialSigner? = null,
    private val fastTransactionStatusPollIntervalMillis: Long = 400L,
    private val fastTransactionStatusPollCount: Int = 5,
    private val transactionStatusPollIntervalMillis: Long = 2_000L,
    private val transactionStatusPollTimeoutMillis: Long = 60_000L,
    private val transactionStatusDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val signer: CredentialSigner =
        credentialSigner ?: EthereumPrivateKeyCredentialSigner(
            privateKeyFactory = privateKeyFactory,
            nonceGenerator = { nonceGenerator().toString() },
        )
    private val indexerClient: IndexerClient =
        IndexerClient(
            publicApiKey = publicApiKey,
            environment = environment,
            transport = transport,
        )
    private val publicClient: WaasWalletPublicClient =
        WaasWalletPublicClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport = OMSClientWebRpcTransport(transport),
            headers = { defaultPublicHeaders() },
        )

    internal val hasPendingSignIn: Boolean
        get() {
            val snapshot = session.snapshot() ?: return false
            return snapshot.walletAddress.isNullOrBlank()
        }

    val canResumeOidcRedirectAuth: Boolean
        get() = oidcRedirectAuthStore?.load() != null

    /**
     * Address of the currently selected wallet, or null when no wallet is selected.
     */
    val walletAddress: String?
        get() = session.snapshot()?.walletAddress

    internal val signerAddress: String?
        get() = session.snapshot()?.signerAddress

    internal fun restoreSession(snapshot: OMSClientSessionSnapshot) {
        session.restore(snapshot)
    }

    internal fun snapshotSession(): OMSClientSessionSnapshot? = session.snapshot()

    internal fun restorePersistedSession(): Boolean {
        val snapshot = sessionStore?.load() ?: return false
        if (snapshot.walletId.isNullOrBlank() || snapshot.walletAddress.isNullOrBlank()) {
            return false
        }
        if (!signer.hasCredential()) {
            sessionStore.clear()
            return false
        }
        session.restore(snapshot)
        return true
    }

    fun signOut() {
        clearSession(clearOidcRedirectAuth = true)
    }

    private fun clearSession(clearOidcRedirectAuth: Boolean) {
        session.clear()
        signer.clear()
        sessionStore?.clear()
        if (clearOidcRedirectAuth) {
            clearPendingOidcRedirectAuth()
        }
    }

    private fun clearPendingOidcRedirectAuth() {
        oidcRedirectAuthStore?.clear()
    }

    private fun requireWalletId(): String = session.snapshot()?.walletId ?: throw OmsSessionException(message = "No wallet selected")

    private fun requireWalletAddress(): String = walletAddress ?: throw OmsSessionException(message = "No wallet selected")

    suspend fun startEmailAuth(email: String): Unit =
        runOmsOperation(OmsSdkOperation.WalletStartEmailAuth) {
            clearSession(clearOidcRedirectAuth = true)
            try {
                val signerAddress = signer.credentialId()
                val response =
                    waasClient().commitVerifier(
                        CommitVerifierRequest(
                            identityType = IdentityType.Email,
                            authMode = AuthMode.OTP,
                            metadata = emptyMap(),
                            handle = email,
                        ),
                    )

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
    ): CompleteAuthResult =
        runOmsOperation(OmsSdkOperation.WalletSignInWithOidcIdToken) {
            completeOidcIdTokenAuth(
                idToken = idToken,
                issuer = issuer,
                audience = audience,
                walletType = walletType,
                walletSelection = walletSelection,
            )
        }

    private suspend fun completeOidcIdTokenAuth(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType,
        walletSelection: WalletSelectionBehavior,
    ): CompleteAuthResult {
        clearSession(clearOidcRedirectAuth = true)
        try {
            val signerAddress = signer.credentialId()
            val response =
                waasClient().commitVerifier(
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
                )

            session.replaceForPendingAuth(
                challenge = response.challenge,
                verifier = response.verifier,
                signerAddress = signerAddress,
                signerKeyType = signer.signingAlgorithm,
            )

            val auth =
                try {
                    confirmOidcIdTokenSignIn(idToken)
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    signOut()
                    throw throwable
                }
            return completeWalletAuth(auth, walletType, walletSelection)
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
        relayRedirectUri: String? = provider.relayRedirectUri,
        authorizeParams: Map<String, String> = emptyMap(),
    ): StartOidcRedirectAuthResult =
        runOmsOperation(OmsSdkOperation.WalletStartOidcRedirectAuth) {
            val redirectAuthStore =
                requireNotNull(oidcRedirectAuthStore) {
                    "OIDC redirect auth requires an OIDC redirect auth store"
                }
            clearSession(clearOidcRedirectAuth = true)
            try {
                val signerAddress = signer.credentialId()
                val oauthRedirectUri = relayRedirectUri ?: redirectUri
                val response =
                    waasClient().commitVerifier(
                        CommitVerifierRequest(
                            identityType = IdentityType.OIDC,
                            authMode = AuthMode.AuthCodePKCE,
                            metadata =
                                mapOf(
                                    "iss" to provider.issuer,
                                    "aud" to provider.clientId,
                                    "redirect_uri" to oauthRedirectUri,
                                ),
                        ),
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
                        redirectUri = redirectUri,
                        issuer = provider.issuer,
                        projectId = projectId,
                        walletType = walletType.toWaas(),
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
                        loginHint = response.loginHint,
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
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
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

            val auth =
                waasClient().completeAuth(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        verifier = pending.verifier,
                        answer = code,
                        lifetime = DEFAULT_SESSION_LIFETIME_SECONDS,
                    ),
                )
            when (val result = completeWalletAuth(auth, pending.walletType.toModel(), walletSelection)) {
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
            clearSession(clearOidcRedirectAuth = false)
            OidcRedirectAuthResult.Failed(throwable)
        } finally {
            if (clearPendingAuth) {
                redirectAuthStore.clear()
            }
        }
    }

    internal suspend fun confirmEmailSignIn(code: String): CompleteAuthResponse {
        val snapshot = session.requirePendingAuth()
        return waasClient().completeAuth(
            CompleteAuthRequest(
                identityType = IdentityType.Email,
                authMode = AuthMode.OTP,
                verifier = snapshot.verifier,
                answer = WalletAuthChallenge.hashAnswer(snapshot.challenge, code),
                lifetime = DEFAULT_SESSION_LIFETIME_SECONDS,
            ),
        )
    }

    internal suspend fun confirmOidcIdTokenSignIn(idToken: String): CompleteAuthResponse {
        val snapshot = session.requirePendingAuth()
        return waasClient().completeAuth(
            CompleteAuthRequest(
                identityType = IdentityType.OIDC,
                authMode = AuthMode.IDToken,
                verifier = snapshot.verifier,
                answer = idToken,
                lifetime = DEFAULT_SESSION_LIFETIME_SECONDS,
            ),
        )
    }

    suspend fun completeEmailAuth(
        code: String,
        walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
        walletType: WalletType = environment.defaultWalletType,
    ): CompleteAuthResult =
        runOmsOperation(OmsSdkOperation.WalletCompleteEmailAuth) {
            val auth = confirmEmailSignIn(code)
            completeWalletAuth(
                completeAuth = auth,
                walletType = walletType,
                walletSelection = walletSelection,
            )
        }

    internal suspend fun resolveWallet(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet =
        resolveAuthenticatedWallet(
            completeAuth = completeAuth,
            walletType = walletType,
        )

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
        signerKeyType: SigningAlgorithm?,
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
        signerKeyType: SigningAlgorithm?,
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
        signerKeyType: SigningAlgorithm?,
    ) {
        try {
            session.requirePendingWalletSelection(
                pendingWalletSelectionId = pendingWalletSelectionId,
                signerAddress = signerAddress,
                signerKeyType = signerKeyType,
            )
        } catch (throwable: IllegalStateException) {
            throw OmsWalletSelectionException(
                code = OmsSdkErrorCode.WalletSelectionStale,
                operation = OmsSdkOperation.PendingWalletSelection,
                message = throwable.message ?: "Pending wallet selection is no longer active",
                cause = throwable,
            )
        }
    }

    private suspend fun requestUseWallet(walletId: String): WaasWallet =
        waasClient()
            .useWallet(
                UseWalletRequest(
                    walletId = walletId,
                ),
            ).wallet

    private suspend fun requestCreateWallet(
        walletType: WalletType,
        reference: String?,
    ): WaasWallet =
        waasClient()
            .createWallet(
                CreateWalletRequest(
                    type = walletType.toWaas(),
                    reference = reference,
                ),
            ).wallet

    private fun persistSelectedWallet(wallet: WaasWallet): WalletSelectionResult {
        persistCurrentSession()
        return WalletSelectionResult(
            walletAddress = wallet.address,
            wallet = wallet.toModel(),
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
                    waasClient().listWallets(
                        ListWalletsRequest(
                            page = cursor?.let { Page(cursor = it).toWaas() },
                        ),
                    )
                wallets += response.wallets.map { it.toModel() }
                cursor = response.page?.cursor?.takeIf { it.isNotBlank() }
            } while (cursor != null)
            wallets
        }

    private fun requireWalletSelectionOrActiveSession() {
        val snapshot = session.requireSnapshot()
        if (snapshot.walletId.isNullOrBlank() && snapshot.expiresAt.isNullOrBlank()) {
            throw OmsSessionException(message = "No authenticated wallet session")
        }
    }

    private suspend fun walletsFromAuthResponse(completeAuth: CompleteAuthResponse): List<Wallet> {
        val wallets = completeAuth.wallets.map { it.toModel() }.toMutableList()
        var cursor = completeAuth.page?.cursor?.takeIf { it.isNotBlank() }
        while (cursor != null) {
            val response =
                waasClient().listWallets(
                    ListWalletsRequest(
                        page = Page(cursor = cursor).toWaas(),
                    ),
                )
            wallets += response.wallets.map { it.toModel() }
            cursor = response.page?.cursor?.takeIf { it.isNotBlank() }
        }
        return wallets
    }

    private suspend fun completeWalletAuth(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType,
        walletSelection: WalletSelectionBehavior,
    ): CompleteAuthResult {
        val pendingWalletSelectionId =
            session.markAuthVerified(
                expiresAt = completeAuth.credential.expiresAt,
                loginType = completeAuth.identity.toSessionLoginType(),
                sessionEmail = completeAuth.email,
            )
        val pendingSnapshot = session.requireSnapshot()
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
                            credential = completeAuth.credential.toModel(),
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
                    credential = completeAuth.credential.toModel(),
                )
            }
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            signOut()
            throw throwable
        }
    }

    private suspend fun resolveAuthenticatedWallet(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType,
    ): Wallet =
        completeWalletAuth(
            completeAuth = completeAuth,
            walletType = walletType,
            walletSelection = WalletSelectionBehavior.Automatic,
        ).requireSelectedWallet()

    private fun CompleteAuthResult.requireSelectedWallet(): Wallet =
        when (this) {
            is CompleteAuthResult.WalletSelected -> wallet
            is CompleteAuthResult.WalletSelection -> error("Auth completed without wallet selection")
        }

    private suspend fun restorePendingOidcRedirectAuth(pending: PendingOidcRedirectAuth) {
        requireActiveCredential()
        val currentSignerAddress = signer.credentialId()
        check(currentSignerAddress == pending.signerAddress) {
            "OIDC redirect auth signer mismatch"
        }
        session.restore(
            OMSClientSessionSnapshot(
                challenge = pending.challenge,
                verifier = pending.verifier,
                signerAddress = pending.signerAddress,
                signerKeyType = pending.signerKeyType,
            ),
        )
    }

    private fun persistCurrentSession() {
        val snapshot = session.snapshot() ?: return
        sessionStore?.save(snapshot)
    }

    private fun Identity.toSessionLoginType(): OMSClientSessionLoginType? =
        when (type) {
            IdentityType.Email -> {
                OMSClientSessionLoginType.Email
            }

            IdentityType.OIDC -> {
                if (iss == GOOGLE_ISSUER) {
                    OMSClientSessionLoginType.GoogleAuth
                } else {
                    OMSClientSessionLoginType.Oidc
                }
            }

            IdentityType.Phone,
            IdentityType.Passkey,
            IdentityType.UNKNOWN_DEFAULT,
            -> {
                null
            }
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
            waasClient()
                .signMessage(
                    SignMessageRequest(
                        walletId = requireWalletId(),
                        network = network.id.toString(),
                        message = message,
                    ),
                ).signature
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
            waasClient()
                .signTypedData(
                    SignTypedDataRequest(
                        walletId = requireWalletId(),
                        network = network.id.toString(),
                        typedData = typedData,
                    ),
                ).signature
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
            val response =
                publicClient.isValidMessageSignature(
                    IsValidMessageSignatureRequest(
                        network = network.id.toString(),
                        walletId = requireWalletId(),
                        message = message,
                        signature = signature,
                    ),
                )
            response.isValid
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
            val response =
                publicClient.isValidTypedDataSignature(
                    IsValidTypedDataSignatureRequest(
                        network = network.id.toString(),
                        walletId = requireWalletId(),
                        typedData = typedData,
                        signature = signature,
                    ),
                )
            response.isValid
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
            val client = waasClient()
            val prepared =
                client.prepareEthereumTransaction(
                    PrepareEthereumTransactionRequest(
                        walletId = requireWalletId(),
                        network = network.id.toString(),
                        to = request.to,
                        value = request.value.toString(),
                        data = request.data,
                        mode = request.mode.toWaas(),
                    ),
                )
            executePreparedTransaction(
                client = client,
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
            val client = waasClient()
            val prepared =
                client.prepareEthereumContractCall(
                    PrepareEthereumContractCallRequest(
                        walletId = requireWalletId(),
                        network = network.id.toString(),
                        contract = contract,
                        method = method,
                        args = args?.map { it.toWaas() },
                        mode = mode.toWaas(),
                    ),
                )
            executePreparedTransaction(
                client = client,
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
            session.requireSnapshot()
            requireActiveCredential()
            waasClient().transactionStatus(TransactionStatusRequest(txnId = txnId)).toModel()
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
                    listAccessPage(
                        pageSize = pageSize,
                        cursor = cursor,
                    )
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
            session.requireSnapshot()
            requireActiveCredential()
            waasClient()
                .listAccess(
                    ListAccessRequest(
                        walletId = requireWalletId(),
                        page = accessPage(pageSize, cursor)?.toWaas(),
                    ),
                ).toModel()
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
            waasClient()
                .getIDToken(
                    GetIDTokenRequest(
                        walletId = requireWalletId(),
                        ttlSeconds = ttlSeconds,
                        customClaims = customClaims,
                    ),
                ).idToken
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
            waasClient().revokeAccess(
                RevokeAccessRequest(
                    targetCredentialId = targetCredentialId,
                    walletId = requireWalletId(),
                ),
            )
        }

    private fun requireActiveCredential() {
        if (!signer.hasCredential()) {
            signOut()
            throw OmsSessionException(message = "No active wallet session")
        }
    }

    private suspend fun executePreparedTransaction(
        client: WaasWalletClient,
        network: Network,
        walletAddress: String,
        prepared: PrepareResponse,
        selectFeeOption: FeeOptionSelector?,
        waitForStatus: Boolean,
        statusPolling: TransactionStatusPollingOptions?,
    ): ClientSendTransactionResponse {
        val feeOptions = prepared.feeOptions.map { it.toModel() }
        val feeOption =
            feeOptions
                .takeIf { it.isNotEmpty() }
                ?.let { feeOptions ->
                    if (selectFeeOption == null) {
                        feeOptions.defaultSelection(sponsored = prepared.sponsored)
                    } else {
                        selectFeeOption.select(
                            enrichFeeOptionsWithBalances(
                                network = network,
                                walletAddress = walletAddress,
                                feeOptions = feeOptions,
                            ),
                        )
                    }
                }
        val executed =
            client.execute(
                ExecuteRequest(
                    txnId = prepared.txnId,
                    feeOption = feeOption?.toWaas(),
                ),
            )
        if (!waitForStatus) {
            return ClientSendTransactionResponse(
                txnId = prepared.txnId,
                status = executed.status.toModel(),
                txnHash = null,
            )
        }
        val status =
            client.waitForTransactionStatus(
                txnId = prepared.txnId,
                fallbackStatus = executed.status.toModel(),
                options = statusPolling ?: defaultTransactionStatusPollingOptions(),
            )
        return ClientSendTransactionResponse(
            txnId = prepared.txnId,
            status = status.status.takeIf { it != TransactionStatus.UNKNOWN_DEFAULT } ?: executed.status.toModel(),
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
        val nativeBalance =
            if (feeOptions.any { it.token.isNativeToken() }) {
                loadNativeTokenBalance(network = network, walletAddress = walletAddress)
            } else {
                null
            }
        val balancesByContract =
            feeOptions
                .mapNotNull { it.token.contractAddress?.normalizeAddress() }
                .distinct()
                .associateWith { contractAddress ->
                    loadTokenBalanceOrZero(
                        network = network,
                        contractAddress = contractAddress,
                        walletAddress = walletAddress,
                    )
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

    private suspend fun loadNativeTokenBalance(
        network: Network,
        walletAddress: String,
    ): TokenBalance? =
        runCatching {
            indexerClient.getNativeTokenBalance(
                network = network,
                walletAddress = walletAddress,
            )
        }.getOrNull()

    private suspend fun loadTokenBalanceOrZero(
        network: Network,
        contractAddress: String,
        walletAddress: String,
    ): TokenBalance? =
        runCatching {
            indexerClient
                .getTokenBalances(
                    network = network,
                    contractAddress = contractAddress,
                    walletAddress = walletAddress,
                    includeMetadata = false,
                ).balances
                .firstOrNull { balance ->
                    balance.contractAddress.normalizeAddress() == contractAddress
                } ?: TokenBalance(
                contractType = "ERC20",
                contractAddress = contractAddress,
                accountAddress = walletAddress,
                tokenId = null,
                balance = "0",
                blockHash = null,
                blockNumber = null,
                chainId = network.id.toLong(),
            )
        }.getOrNull()

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

    private fun List<FeeOption>.defaultSelection(sponsored: Boolean): FeeOptionSelection? =
        if (sponsored) null else firstOrNull()?.let { FeeOptionSelection(token = it.token.symbol) }

    private suspend fun WaasWalletClient.waitForTransactionStatus(
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
                    transactionStatus(TransactionStatusRequest(txnId = txnId)).toModel()
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    throw OmsTransactionException(
                        operation = OmsSdkOperation.WalletGetTransactionStatus,
                        txnId = txnId,
                        message = throwable.message ?: "Transaction status lookup failed",
                        cause = throwable,
                    )
                }
            completedStatusPolls += 1
            if (lastStatus.status == TransactionStatus.Executed || !lastStatus.txnHash.isNullOrBlank()) {
                return lastStatus
            }
            if (lastStatus.status == TransactionStatus.UNKNOWN_DEFAULT) {
                return lastStatus
            }
            if (options.pollIntervalMillis <= 0L) {
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

    private fun waasClient(): WaasWalletClient =
        WaasWalletClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport =
                WalletSignedWaasTransport(
                    publicApiKey = publicApiKey,
                    projectId = projectId,
                    httpClient = transport,
                    signer = signer,
                ),
        )

    private fun defaultPublicHeaders(): Map<String, String> =
        mapOf(
            OMSClientEnvironment.accessKeyHeaderName to publicApiKey,
            "Accept" to "application/json",
        )
}

private val DEFAULT_SESSION_LIFETIME_SECONDS: UInt = 604_800u
private const val GOOGLE_ISSUER: String = "https://accounts.google.com"
