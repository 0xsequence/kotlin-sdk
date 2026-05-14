package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierResponse
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthResponse
import com.omsclient.kotlin_sdk.generated.waas.CreateWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.ExecuteRequest
import com.omsclient.kotlin_sdk.generated.waas.Identity
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.IsValidMessageSignatureRequest
import com.omsclient.kotlin_sdk.generated.waas.IsValidTypedDataSignatureRequest
import com.omsclient.kotlin_sdk.generated.waas.ListAccessRequest
import com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumContractCallRequest
import com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumTransactionRequest
import com.omsclient.kotlin_sdk.generated.waas.PrepareResponse
import com.omsclient.kotlin_sdk.generated.waas.RevokeAccessRequest
import com.omsclient.kotlin_sdk.generated.waas.SignMessageRequest
import com.omsclient.kotlin_sdk.generated.waas.SignMessageResponse
import com.omsclient.kotlin_sdk.generated.waas.SignTypedDataRequest
import com.omsclient.kotlin_sdk.generated.waas.TransactionStatus
import com.omsclient.kotlin_sdk.generated.waas.TransactionStatusRequest
import com.omsclient.kotlin_sdk.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletClient
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletPublicClient
import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.models.AbiArg
import com.omsclient.kotlin_sdk.models.CredentialInfo
import com.omsclient.kotlin_sdk.models.FeeOption
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.FeeOptionSelector
import com.omsclient.kotlin_sdk.models.FeeOptionWithBalance
import com.omsclient.kotlin_sdk.models.ListAccessResponse
import com.omsclient.kotlin_sdk.models.Page
import com.omsclient.kotlin_sdk.models.SignTypedDataResponse
import com.omsclient.kotlin_sdk.models.TokenBalance
import com.omsclient.kotlin_sdk.models.TransactionMode
import com.omsclient.kotlin_sdk.models.TransactionStatusResponse
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.network.OMSClientWebRpcTransport
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
import com.omsclient.kotlin_sdk.models.SendTransactionRequest as ClientSendTransactionRequest
import com.omsclient.kotlin_sdk.models.SendTransactionResponse as ClientSendTransactionResponse

class WalletClient internal constructor(
    private val projectAccessKey: String,
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
            projectAccessKey = projectAccessKey,
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

    internal val canResumeOidcRedirectAuth: Boolean
        get() = oidcRedirectAuthStore?.load() != null

    /**
     * Address of the currently selected wallet, or null when no wallet is selected.
     */
    val address: String?
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

    internal fun signOut() {
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

    private fun requireWalletId(): String = requireNotNull(session.snapshot()?.walletId) { "No wallet selected" }

    private fun requireWalletAddress(): String = requireNotNull(address) { "No wallet selected" }

    internal suspend fun startEmailAuth(email: String): CommitVerifierResponse {
        requireNoActiveWalletSession()
        clearPendingOidcRedirectAuth()
        return try {
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
                signerKeyType = signer.keyType,
            )

            response
        } catch (throwable: Throwable) {
            signOut()
            throw throwable
        }
    }

    internal suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet =
        signInWithOidcIdToken(
            idToken = idToken,
            issuer = issuer,
            audience = audience,
            walletType = walletType,
            selectWallet = { wallets ->
                require(wallets.size == 1) {
                    "Multiple wallets are available. Call signInWithOidcIdToken(idToken, issuer, audience, walletType, selectWallet) to choose one."
                }
                wallets.single()
            },
        )

    internal suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet {
        requireNoActiveWalletSession()
        clearPendingOidcRedirectAuth()
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
                signerKeyType = signer.keyType,
            )

            val auth =
                try {
                    confirmOidcIdTokenSignIn(idToken)
                } catch (throwable: Throwable) {
                    signOut()
                    throw throwable
                }
            return resolveAuthenticatedWallet(auth, walletType, selectWallet)
        } catch (throwable: Throwable) {
            signOut()
            throw throwable
        }
    }

    internal suspend fun startOidcRedirectAuth(
        provider: OidcProviderConfig,
        redirectUri: String,
        walletType: WalletType = environment.defaultWalletType,
        relayRedirectUri: String? = provider.relayRedirectUri,
        authorizeParams: Map<String, String> = emptyMap(),
    ): StartOidcRedirectAuthResult {
        requireNoActiveWalletSession()
        val redirectAuthStore =
            requireNotNull(oidcRedirectAuthStore) {
                "OIDC redirect auth requires an OIDC redirect auth store"
            }
        return try {
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
                    scope = environment.authorizationScope,
                    redirectUri = redirectUri.takeIf { oauthRedirectUri != redirectUri },
                )

            session.replaceForPendingAuth(
                challenge = response.challenge,
                verifier = response.verifier,
                signerAddress = signerAddress,
                signerKeyType = signer.keyType,
            )
            redirectAuthStore.save(
                PendingOidcRedirectAuth(
                    verifier = response.verifier,
                    challenge = response.challenge,
                    nonce = nonce,
                    redirectUri = redirectUri,
                    issuer = provider.issuer,
                    authorizationScope = environment.authorizationScope,
                    walletType = walletType,
                    signerAddress = signerAddress,
                    signerKeyType = signer.keyType,
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
        } catch (throwable: Throwable) {
            signOut()
            throw throwable
        }
    }

    internal suspend fun handleOidcRedirectCallback(
        callbackUrl: String?,
        selectWallet: suspend (List<Wallet>) -> Wallet,
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
            OidcRedirectAuthResult.Completed(
                resolveAuthenticatedWallet(auth, pending.walletType, selectWallet),
            )
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

    internal suspend fun completeEmailAuth(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet =
        completeEmailAuth(
            code = code,
            walletType = walletType,
            selectWallet = { wallets ->
                require(wallets.size == 1) {
                    "Multiple wallets are available. Call completeEmailAuth(code, selectWallet) to choose one."
                }
                wallets.single()
            },
        )

    /**
     * Completes the email OTP flow and returns the selected wallet.
     *
     * If multiple wallets are available for the requested type, [selectWallet]
     * is called so the app can choose the wallet to use.
     */
    internal suspend fun completeEmailAuth(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet {
        val auth = confirmEmailSignIn(code)
        return resolveAuthenticatedWallet(auth, walletType, selectWallet)
    }

    internal suspend fun resolveWallet(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet =
        resolveAuthenticatedWallet(
            completeAuth = completeAuth,
            walletType = walletType,
            selectWallet = { wallets ->
                require(wallets.size == 1) {
                    "Multiple wallets are available. Call resolveWallet with an explicit selector to choose one."
                }
                wallets.single()
            },
        )

    internal suspend fun useWallet(walletId: String): Wallet {
        session.requireSnapshot()
        val wallet =
            waasClient()
                .useWallet(
                    UseWalletRequest(
                        walletId = walletId,
                    ),
                ).wallet

        session.activateWallet(
            walletId = wallet.id,
            walletAddress = wallet.address,
        )
        persistCurrentSession()
        return wallet
    }

    internal suspend fun createWallet(walletType: WalletType = environment.defaultWalletType): Wallet {
        session.requireSnapshot()
        val wallet =
            waasClient()
                .createWallet(
                    CreateWalletRequest(type = walletType),
                ).wallet

        session.activateWallet(
            walletId = wallet.id,
            walletAddress = wallet.address,
        )
        persistCurrentSession()
        return wallet
    }

    private suspend fun resolveAuthenticatedWallet(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet {
        session.markAuthVerified(
            expiresAt = completeAuth.credential.expiresAt,
            loginType = completeAuth.identity.toSessionLoginType(),
            sessionEmail = completeAuth.email,
        )
        return try {
            val candidateWallets = completeAuth.wallets.filter { it.type == walletType }
            when {
                candidateWallets.isEmpty() -> {
                    createWallet(walletType)
                }

                candidateWallets.size == 1 -> {
                    val selected = candidateWallets.single()
                    useWallet(selected.id)
                }

                else -> {
                    val selected = selectWallet(candidateWallets)
                    require(candidateWallets.contains(selected)) {
                        "Selected wallet is not one of the available options"
                    }
                    useWallet(selected.id)
                }
            }
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

    private fun requireNoActiveWalletSession() {
        val snapshot = session.snapshot()
        check(snapshot?.walletId.isNullOrBlank() || snapshot.walletAddress.isNullOrBlank()) {
            "Cannot start a new login while a wallet session is active"
        }
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
    ): SignMessageResponse {
        session.requireSnapshot()
        requireActiveCredential()
        return waasClient().signMessage(
            SignMessageRequest(
                walletId = requireWalletId(),
                network = network.chainId,
                message = message,
            ),
        )
    }

    /**
     * Signs EIP-712 [typedData] with the currently selected wallet on [network].
     */
    suspend fun signTypedData(
        network: Network,
        typedData: JsonElement,
    ): SignTypedDataResponse {
        session.requireSnapshot()
        requireActiveCredential()
        return waasClient().signTypedData(
            SignTypedDataRequest(
                walletId = requireWalletId(),
                network = network.chainId,
                typedData = typedData,
            ),
        )
    }

    /**
     * Validates [signature] for [message] through the WaaS public wallet RPC.
     */
    suspend fun isValidMessageSignature(
        network: Network,
        message: String,
        signature: String,
    ): Boolean {
        val response =
            publicClient.isValidMessageSignature(
                IsValidMessageSignatureRequest(
                    network = network.chainId,
                    walletId = requireWalletId(),
                    message = message,
                    signature = signature,
                ),
            )
        return response.isValid
    }

    /**
     * Validates [signature] for EIP-712 [typedData] through the WaaS public wallet RPC.
     */
    suspend fun isValidTypedDataSignature(
        network: Network,
        typedData: JsonElement,
        signature: String,
    ): Boolean {
        val response =
            publicClient.isValidTypedDataSignature(
                IsValidTypedDataSignatureRequest(
                    network = network.chainId,
                    walletId = requireWalletId(),
                    typedData = typedData,
                    signature = signature,
                ),
            )
        return response.isValid
    }

    /**
     * Sends a native-value transaction from the currently selected wallet on
     * [network].
     */
    suspend fun sendTransaction(
        network: Network,
        to: String,
        value: BigInteger,
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
        selectFeeOption: FeeOptionSelector? = null,
    ): ClientSendTransactionResponse {
        val snapshot = session.requireSnapshot()
        require(request.value.signum() >= 0) { "Transaction value must be non-negative" }
        requireActiveCredential()
        val client = waasClient()
        val prepared =
            client.prepareEthereumTransaction(
                PrepareEthereumTransactionRequest(
                    walletId = requireWalletId(),
                    network = network.chainId,
                    to = request.to,
                    value = request.value.toString(),
                    data = request.data,
                    mode = request.mode,
                ),
            )
        return executePreparedTransaction(
            client = client,
            network = network,
            walletAddress = requireNotNull(snapshot.walletAddress) { "No wallet selected" },
            prepared = prepared,
            selectFeeOption = selectFeeOption,
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
        selectFeeOption: FeeOptionSelector? = null,
    ): ClientSendTransactionResponse {
        val snapshot = session.requireSnapshot()
        requireActiveCredential()
        val client = waasClient()
        val prepared =
            client.prepareEthereumContractCall(
                PrepareEthereumContractCallRequest(
                    walletId = requireWalletId(),
                    network = network.chainId,
                    contract = contract,
                    method = method,
                    args = args,
                    mode = mode,
                ),
            )
        return executePreparedTransaction(
            client = client,
            network = network,
            walletAddress = requireNotNull(snapshot.walletAddress) { "No wallet selected" },
            prepared = prepared,
            selectFeeOption = selectFeeOption,
        )
    }

    /**
     * Returns the current WaaS execution status for a prepared or submitted
     * transaction.
     */
    suspend fun getTransactionStatus(txnId: String): TransactionStatusResponse {
        session.requireSnapshot()
        requireActiveCredential()
        return waasClient().transactionStatus(TransactionStatusRequest(txnId = txnId))
    }

    /**
     * Returns all credentials that currently have access to the selected wallet.
     *
     * When [pageSize] is provided, the SDK follows WaaS cursors using that page
     * size and returns the combined credential list.
     */
    suspend fun listAccess(pageSize: UInt? = null): List<CredentialInfo> {
        val credentials = mutableListOf<CredentialInfo>()
        listAccessPages(pageSize = pageSize).collect { response ->
            credentials += response.credentials
        }
        return credentials
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
                cursor = response.page.cursor?.takeIf { it.isNotBlank() }
            } while (cursor != null)
        }

    /**
     * Returns one credential-access page for the selected wallet.
     */
    suspend fun listAccessPage(
        pageSize: UInt? = null,
        cursor: String? = null,
    ): ListAccessResponse {
        session.requireSnapshot()
        requireActiveCredential()
        return waasClient().listAccess(
            ListAccessRequest(
                walletId = requireWalletId(),
                page = accessPage(pageSize, cursor),
            ),
        )
    }

    /**
     * Revokes a credential's access to the selected wallet.
     *
     * Use [listAccess] or [listAccessPage] to find credential IDs.
     */
    suspend fun revokeAccess(targetCredentialId: String) {
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
            error("No active wallet session")
        }
    }

    private suspend fun executePreparedTransaction(
        client: WaasWalletClient,
        network: Network,
        walletAddress: String,
        prepared: PrepareResponse,
        selectFeeOption: FeeOptionSelector?,
    ): ClientSendTransactionResponse {
        val feeOption =
            prepared.feeOptions
                .takeIf { it.isNotEmpty() }
                ?.let { feeOptions ->
                    if (selectFeeOption == null) {
                        feeOptions.defaultSelection(sponsored = prepared.sponsored)
                    } else {
                        selectFeeOption(
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
                    feeOption = feeOption,
                ),
            )
        val status =
            client.waitForTransactionStatus(
                txnId = prepared.txnId,
                fallbackStatus = executed.status,
            )
        return ClientSendTransactionResponse(
            txnId = prepared.txnId,
            status = status.status.takeIf { it != TransactionStatus.UNKNOWN_DEFAULT } ?: executed.status,
            txHash = status.txnHash,
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
                chainId = network.chainId.toLongOrNull(),
            )
        }.getOrNull()

    private fun String?.normalizeAddress(): String? =
        this
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()

    private fun com.omsclient.kotlin_sdk.generated.waas.FeeToken.isNativeToken(): Boolean =
        type.equals("native", ignoreCase = true) ||
            (contractAddress.isNullOrBlank() && tokenId.isNullOrBlank())

    private fun com.omsclient.kotlin_sdk.generated.waas.FeeToken.balanceDecimals(): Int? =
        decimals?.toInt() ?: if (isNativeToken()) 18 else null

    private fun String.formatTokenAmount(decimals: Int?): String =
        decimals?.let { scale ->
            runCatching { formatUnits(BigInteger(this), scale) }.getOrDefault(this)
        } ?: this

    private fun List<FeeOption>.defaultSelection(sponsored: Boolean): FeeOptionSelection? =
        if (sponsored) null else firstOrNull()?.let { FeeOptionSelection(token = it.token.symbol) }

    private suspend fun WaasWalletClient.waitForTransactionStatus(
        txnId: String,
        fallbackStatus: TransactionStatus,
    ): TransactionStatusResponse {
        val deadline = System.currentTimeMillis() + transactionStatusPollTimeoutMillis
        var lastStatus = TransactionStatusResponse(status = fallbackStatus)
        var completedStatusPolls = 0

        do {
            lastStatus = transactionStatus(TransactionStatusRequest(txnId = txnId))
            completedStatusPolls += 1
            if (lastStatus.status == TransactionStatus.Executed || !lastStatus.txnHash.isNullOrBlank()) {
                return lastStatus
            }
            if (lastStatus.status == TransactionStatus.UNKNOWN_DEFAULT) {
                return lastStatus
            }
            if (transactionStatusPollIntervalMillis <= 0L) {
                return lastStatus
            }
            val remainingMillis = deadline - System.currentTimeMillis()
            if (remainingMillis <= 0L) {
                return lastStatus
            }
            val nextDelayMillis =
                if (completedStatusPolls < fastTransactionStatusPollCount) {
                    fastTransactionStatusPollIntervalMillis
                } else {
                    transactionStatusPollIntervalMillis
                }
            transactionStatusDelay(minOf(nextDelayMillis, remainingMillis))
        } while (true)
    }

    private fun waasClient(): WaasWalletClient =
        WaasWalletClient(
            baseUrl = environment.walletApiBaseUrl(),
            transport =
                WalletSignedWaasTransport(
                    projectAccessKey = projectAccessKey,
                    environment = environment,
                    httpClient = transport,
                    signer = signer,
                ),
        )

    private fun defaultPublicHeaders(): Map<String, String> =
        mapOf(
            OMSClientEnvironment.accessKeyHeaderName to projectAccessKey,
            "Accept" to "application/json",
        )
}

private val DEFAULT_SESSION_LIFETIME_SECONDS: UInt = 604_800u
private const val GOOGLE_ISSUER: String = "https://accounts.google.com"
