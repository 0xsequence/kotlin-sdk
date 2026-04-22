package com.omswallet.kotlin_sdk.wallet

import com.omswallet.kotlin_sdk.chains.OmsWalletChains
import com.omswallet.kotlin_sdk.generated.waas.AuthMode
import com.omswallet.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omswallet.kotlin_sdk.generated.waas.CommitVerifierResponse
import com.omswallet.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omswallet.kotlin_sdk.generated.waas.CompleteAuthResponse
import com.omswallet.kotlin_sdk.generated.waas.CreateWalletRequest
import com.omswallet.kotlin_sdk.generated.waas.IdentityType
import com.omswallet.kotlin_sdk.generated.waas.SendTransactionRequest as WaasSendTransactionRequest
import com.omswallet.kotlin_sdk.generated.waas.SendTransactionResponse
import com.omswallet.kotlin_sdk.generated.waas.SignMessageRequest
import com.omswallet.kotlin_sdk.generated.waas.SignMessageResponse
import com.omswallet.kotlin_sdk.generated.waas.UseWalletRequest
import com.omswallet.kotlin_sdk.generated.waas.WaasWalletClient
import com.omswallet.kotlin_sdk.generated.waas.Wallet
import com.omswallet.kotlin_sdk.generated.waas.WalletType
import com.omswallet.kotlin_sdk.models.SendTransactionRequest as OmsWalletSendTransactionRequest
import com.omswallet.kotlin_sdk.network.OmsWalletEnvironment
import com.omswallet.kotlin_sdk.network.OmsWalletHttpClient
import com.omswallet.kotlin_sdk.session.OmsWalletSessionSnapshot
import com.omswallet.kotlin_sdk.session.OmsWalletSession
import com.omswallet.kotlin_sdk.storage.OmsWalletSecureSessionStore
import com.omswallet.kotlin_sdk.utils.OmsWalletTimestamps

internal data class OmsWalletState(
    val hasPendingSignIn: Boolean,
    val walletAddress: String?,
    val signerAddress: String?,
)

class OmsWalletClient internal constructor(
    private val projectAccessKey: String,
    private val environment: OmsWalletEnvironment,
    private val transport: OmsWalletHttpClient = OmsWalletHttpClient(),
    private val session: OmsWalletSession = OmsWalletSession(),
    private val sessionStore: OmsWalletSecureSessionStore? = null,
    private val nonceGenerator: () -> Long = OmsWalletTimestamps::nextNonce,
    private val privateKeyFactory: () -> ByteArray = WalletRequestSigner::generatePrivateKeyBytes,
) {
    private var transientPrivateKey: ByteArray? = null

    private val privateKeyStore: OmsWalletSecureSessionStore =
        sessionStore ?: InMemoryPrivateKeyStore()

    val hasPendingSignIn: Boolean
        get() {
            val snapshot = session.snapshot() ?: return false
            return snapshot.walletAddress.isNullOrBlank()
        }

    val walletAddress: String?
        get() = session.snapshot()?.walletAddress

    val signerAddress: String?
        get() = session.snapshot()?.signerAddress

    internal fun currentState(): OmsWalletState = OmsWalletState(
        hasPendingSignIn = hasPendingSignIn,
        walletAddress = walletAddress,
        signerAddress = signerAddress,
    )

    internal fun restoreSession(snapshot: OmsWalletSessionSnapshot) {
        session.restore(snapshot)
    }

    internal fun snapshotSession(): OmsWalletSessionSnapshot? = session.snapshot()

    internal fun restorePersistedSession(): Boolean {
        val snapshot = sessionStore?.load() ?: return false
        if (snapshot.walletId.isNullOrBlank() || snapshot.walletAddress.isNullOrBlank()) {
            return false
        }
        session.restore(snapshot)
        return true
    }

    fun clearSession() {
        session.clear()
        clearTransientPrivateKey()
        privateKeyStore.clear()
    }

    private fun requireWalletId(): String =
        requireNotNull(session.snapshot()?.walletId) { "No wallet selected" }

    private fun requireWalletAddress(): String =
        requireNotNull(walletAddress) { "No wallet selected" }

    suspend fun signInWithEmail(email: String): CommitVerifierResponse {
        val privateKey = privateKeyFactory()
        return try {
            val signerAddress = WalletRequestSigner.walletAddressFromPrivateKey(privateKey)
            val response = waasClient(privateKey).commitVerifier(
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
            )
            replaceTransientPrivateKey(privateKey)

            response
        } finally {
            privateKey.fill(0)
        }
    }

    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet = signInWithOidcIdToken(
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

    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet {
        val privateKey = privateKeyFactory()
        try {
            val signerAddress = WalletRequestSigner.walletAddressFromPrivateKey(privateKey)
            val response = waasClient(privateKey).commitVerifier(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    metadata = mapOf(
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
            )
            replaceTransientPrivateKey(privateKey)

            val auth = try {
                confirmOidcIdTokenSignIn(idToken)
            } catch (throwable: Throwable) {
                clearSession()
                throw throwable
            }
            return resolveAuthenticatedWallet(auth, walletType, selectWallet)
        } finally {
            privateKey.fill(0)
        }
    }

    internal suspend fun confirmEmailSignIn(code: String): CompleteAuthResponse {
        val snapshot = session.requirePendingAuth()
        return withPrivateKey { privateKey ->
            waasClient(privateKey).completeAuth(
                CompleteAuthRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    verifier = snapshot.verifier,
                    answer = WalletAuthChallenge.hashAnswer(snapshot.challenge, code),
                ),
            )
        }
    }

    internal suspend fun confirmOidcIdTokenSignIn(idToken: String): CompleteAuthResponse {
        val snapshot = session.requirePendingAuth()
        return withPrivateKey { privateKey ->
            waasClient(privateKey).completeAuth(
                CompleteAuthRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    verifier = snapshot.verifier,
                    answer = idToken,
                ),
            )
        }
    }

    suspend fun completeEmailSignIn(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet = completeEmailSignIn(
        code = code,
        walletType = walletType,
        selectWallet = { wallets ->
            require(wallets.size == 1) {
                "Multiple wallets are available. Call completeEmailSignIn(code, selectWallet) to choose one."
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
    suspend fun completeEmailSignIn(
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
    ): Wallet {
        return resolveAuthenticatedWallet(
            completeAuth = completeAuth,
            walletType = walletType,
            selectWallet = { wallets ->
                require(wallets.size == 1) {
                    "Multiple wallets are available. Call resolveWallet with an explicit selector to choose one."
                }
                wallets.single()
            },
        )
    }

    internal suspend fun useWallet(walletId: String): Wallet {
        session.requireSnapshot()
        val wallet = withPrivateKey { privateKey ->
            waasClient(privateKey).useWallet(
                UseWalletRequest(
                    walletId = walletId,
                ),
            ).wallet
        }

        session.activateWallet(
            walletId = wallet.id,
            walletAddress = wallet.address,
        )
        persistCurrentSession()
        clearTransientPrivateKey()
        return wallet
    }

    internal suspend fun createWallet(walletType: WalletType = environment.defaultWalletType): Wallet {
        session.requireSnapshot()
        val wallet = withPrivateKey { privateKey ->
            waasClient(privateKey).createWallet(
                CreateWalletRequest(type = walletType),
            ).wallet
        }

        session.activateWallet(
            walletId = wallet.id,
            walletAddress = wallet.address,
        )
        persistCurrentSession()
        clearTransientPrivateKey()
        return wallet
    }

    private suspend fun resolveAuthenticatedWallet(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet {
        session.markAuthVerified()
        return try {
            val candidateWallets = completeAuth.wallets.filter { it.type == walletType }
            when {
                candidateWallets.isEmpty() -> createWallet(walletType)
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
            clearSession()
            throw throwable
        }
    }

    private fun persistCurrentSession(privateKey: ByteArray? = null) {
        val snapshot = session.snapshot() ?: return
        privateKeyStore.save(snapshot, privateKey ?: transientPrivateKey)
    }

    suspend fun signMessage(chainId: String, message: String): SignMessageResponse {
        session.requireSnapshot()
        return withPrivateKey { privateKey ->
            waasClient(privateKey).signMessage(
                SignMessageRequest(
                    walletId = requireWalletId(),
                    network = OmsWalletChains.chainNameFor(chainId),
                    message = message,
                ),
            )
        }
    }

    suspend fun sendTransaction(
        chainId: String,
        to: String,
        value: String,
    ): SendTransactionResponse = sendTransaction(
        chainId = chainId,
        request = OmsWalletSendTransactionRequest(
            to = to,
            value = value,
        ),
    )

    suspend fun sendTransaction(
        chainId: String,
        request: OmsWalletSendTransactionRequest,
    ): SendTransactionResponse {
        session.requireSnapshot()
        return withPrivateKey { privateKey ->
            waasClient(privateKey).sendTransaction(
                WaasSendTransactionRequest(
                    walletId = requireWalletId(),
                    network = OmsWalletChains.chainNameFor(chainId),
                    to = request.to,
                    value = request.value,
                    data = request.data,
                    mode = request.mode,
                    feeCeiling = request.feeCeiling,
                    nonce = request.nonce,
                ),
            )
        }
    }

    private suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T {
        val inMemoryKey = transientPrivateKey
        if (inMemoryKey != null) {
            val privateKey = inMemoryKey.copyOf()
            return try {
                block(privateKey)
            } finally {
                privateKey.fill(0)
            }
        }
        return privateKeyStore.withPrivateKey(block)
    }

    private fun replaceTransientPrivateKey(privateKey: ByteArray) {
        clearTransientPrivateKey()
        transientPrivateKey = privateKey.copyOf()
    }

    private fun clearTransientPrivateKey() {
        transientPrivateKey?.fill(0)
        transientPrivateKey = null
    }

    private fun waasClient(privateKey: ByteArray): WaasWalletClient = WaasWalletClient(
        baseUrl = environment.walletApiBaseUrl(),
        transport = OmsWalletSignedWaasTransport(
            projectAccessKey = projectAccessKey,
            environment = environment,
            httpClient = transport,
            nonceGenerator = nonceGenerator,
            privateKey = privateKey,
        ),
    )

    private class InMemoryPrivateKeyStore : OmsWalletSecureSessionStore {
        private var snapshot: OmsWalletSessionSnapshot? = null
        private var privateKey: ByteArray? = null

        override fun load(): OmsWalletSessionSnapshot? = snapshot

        override fun save(snapshot: OmsWalletSessionSnapshot, privateKey: ByteArray?) {
            this.snapshot = snapshot
            if (privateKey == null) {
                return
            }
            this.privateKey?.fill(0)
            this.privateKey = privateKey.copyOf()
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T {
            val keyCopy = requireNotNull(privateKey) { "No active OMS Wallet signing key" }.copyOf()
            return try {
                block(keyCopy)
            } finally {
                keyCopy.fill(0)
            }
        }

        override fun clear() {
            snapshot = null
            privateKey?.fill(0)
            privateKey = null
        }
    }
}
