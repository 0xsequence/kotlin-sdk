package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.chains.SequenceChains
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.AuthMode
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CommitVerifierRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CommitVerifierResponse
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CompleteAuthRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CompleteAuthResponse
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CreateWalletRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.IdentityType
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SendTransactionRequest as WaasSendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SendTransactionResponse
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SignMessageRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SignMessageResponse
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.UseWalletRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WaasWalletClient
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.Wallet
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WalletType
import com.polygon_wallet.polygon_kotlin_sdk.models.SendTransactionRequest as SequenceSendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceSessionSnapshot
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceWalletSession
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequenceSecureSessionStore
import com.polygon_wallet.polygon_kotlin_sdk.utils.SequenceTimestamps

internal data class SequenceWalletState(
    val hasPendingSignIn: Boolean,
    val walletAddress: String?,
    val signerAddress: String?,
)

class SequenceWalletClient internal constructor(
    private val projectAccessKey: String,
    private val environment: SequenceEnvironment,
    private val transport: SequenceHttpClient = SequenceHttpClient(),
    private val session: SequenceWalletSession = SequenceWalletSession(),
    private val sessionStore: SequenceSecureSessionStore? = null,
    private val nonceGenerator: () -> Long = SequenceTimestamps::nextNonce,
    private val privateKeyFactory: () -> ByteArray = WalletRequestSigner::generatePrivateKeyBytes,
) {
    private val privateKeyStore: SequenceSecureSessionStore =
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

    internal fun currentState(): SequenceWalletState = SequenceWalletState(
        hasPendingSignIn = hasPendingSignIn,
        walletAddress = walletAddress,
        signerAddress = signerAddress,
    )

    internal fun restoreSession(snapshot: SequenceSessionSnapshot) {
        session.restore(snapshot)
    }

    internal fun snapshotSession(): SequenceSessionSnapshot? = session.snapshot()

    internal fun restorePersistedSession(): Boolean {
        val snapshot = sessionStore?.load() ?: return false
        session.restore(snapshot)
        return true
    }

    fun clearSession() {
        session.clear()
        privateKeyStore.clear()
    }

    private fun requireWalletAddress(): String =
        requireNotNull(walletAddress) { "No wallet selected" }

    suspend fun signInWithEmail(email: String): CommitVerifierResponse {
        val privateKey = privateKeyFactory()
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
        persistCurrentSession(privateKey)
        privateKey.fill(0)

        return response
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
        persistCurrentSession(privateKey)
        privateKey.fill(0)

        val auth = try {
            confirmOidcIdTokenSignIn(idToken)
        } catch (throwable: Throwable) {
            clearSession()
            throw throwable
        }
        val candidateWallets = auth.wallets.filter { it.type == walletType }

        return when {
            candidateWallets.isEmpty() -> createWallet(walletType)
            candidateWallets.size == 1 -> {
                val selected = candidateWallets.single()
                useWallet(
                    walletType = selected.type,
                    walletIndex = selected.index.toInt(),
                )
            }
            else -> {
                val selected = selectWallet(candidateWallets)
                require(candidateWallets.contains(selected)) {
                    "Selected wallet is not one of the available options"
                }
                useWallet(
                    walletType = selected.type,
                    walletIndex = selected.index.toInt(),
                )
            }
        }
    }

    internal suspend fun confirmEmailSignIn(code: String): CompleteAuthResponse {
        val snapshot = session.requireSnapshot()
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
        val snapshot = session.requireSnapshot()
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
        val candidateWallets = auth.wallets.filter { it.type == walletType }

        return when {
            candidateWallets.isEmpty() -> createWallet(walletType)
            candidateWallets.size == 1 -> {
                val selected = candidateWallets.single()
                useWallet(
                    walletType = selected.type,
                    walletIndex = selected.index.toInt(),
                )
            }
            else -> {
                val selected = selectWallet(candidateWallets)
                require(candidateWallets.contains(selected)) {
                    "Selected wallet is not one of the available options"
                }
                useWallet(
                    walletType = selected.type,
                    walletIndex = selected.index.toInt(),
                )
            }
        }
    }

    internal suspend fun resolveWallet(
        completeAuth: CompleteAuthResponse,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet {
        val existingWallet = completeAuth.wallets.firstOrNull { it.type == walletType }
        return if (existingWallet != null) {
            useWallet(
                walletType = walletType,
                walletIndex = existingWallet.index.toInt(),
            )
        } else {
            createWallet(walletType)
        }
    }

    internal suspend fun useWallet(
        walletType: WalletType = environment.defaultWalletType,
        walletIndex: Int = 0,
    ): Wallet {
        session.requireSnapshot()
        val wallet = withPrivateKey { privateKey ->
            waasClient(privateKey).useWallet(
                UseWalletRequest(
                    walletType = walletType,
                    walletIndex = walletIndex.toCheckedUByte(),
                ),
            ).wallet
        }

        session.updateWalletAddress(wallet.address)
        persistCurrentSession()
        return wallet
    }

    internal suspend fun createWallet(walletType: WalletType = environment.defaultWalletType): Wallet {
        session.requireSnapshot()
        val wallet = withPrivateKey { privateKey ->
            waasClient(privateKey).createWallet(
                CreateWalletRequest(walletType = walletType),
            ).wallet
        }

        session.updateWalletAddress(wallet.address)
        persistCurrentSession()
        return wallet
    }

    private fun persistCurrentSession(privateKey: ByteArray? = null) {
        val snapshot = session.snapshot() ?: return
        privateKeyStore.save(snapshot, privateKey)
    }

    suspend fun signMessage(chainId: String, message: String): SignMessageResponse {
        session.requireSnapshot()
        return withPrivateKey { privateKey ->
            waasClient(privateKey).signMessage(
                SignMessageRequest(
                    wallet = requireWalletAddress(),
                    network = SequenceChains.chainNameFor(chainId),
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
        request = SequenceSendTransactionRequest(
            to = to,
            value = value,
        ),
    )

    suspend fun sendTransaction(
        chainId: String,
        request: SequenceSendTransactionRequest,
    ): SendTransactionResponse {
        session.requireSnapshot()
        return withPrivateKey { privateKey ->
            waasClient(privateKey).sendTransaction(
                WaasSendTransactionRequest(
                    wallet = requireWalletAddress(),
                    network = SequenceChains.chainNameFor(chainId),
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

    private suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
        privateKeyStore.withPrivateKey(block)

    private fun waasClient(privateKey: ByteArray): WaasWalletClient = WaasWalletClient(
        baseUrl = environment.walletApiBaseUrl(),
        transport = SequenceSignedWaasTransport(
            projectAccessKey = projectAccessKey,
            environment = environment,
            httpClient = transport,
            nonceGenerator = nonceGenerator,
            privateKey = privateKey,
        ),
    )

    private fun Int.toCheckedUByte(): UByte {
        require(this in 0..UByte.MAX_VALUE.toInt()) {
            "walletIndex must be between 0 and ${UByte.MAX_VALUE.toInt()}"
        }
        return toUByte()
    }

    private class InMemoryPrivateKeyStore : SequenceSecureSessionStore {
        private var snapshot: SequenceSessionSnapshot? = null
        private var privateKey: ByteArray? = null

        override fun load(): SequenceSessionSnapshot? = snapshot

        override fun save(snapshot: SequenceSessionSnapshot, privateKey: ByteArray?) {
            this.snapshot = snapshot
            if (privateKey == null) {
                return
            }
            this.privateKey?.fill(0)
            this.privateKey = privateKey.copyOf()
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T {
            val keyCopy = requireNotNull(privateKey) { "No active Sequence signing key" }.copyOf()
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
