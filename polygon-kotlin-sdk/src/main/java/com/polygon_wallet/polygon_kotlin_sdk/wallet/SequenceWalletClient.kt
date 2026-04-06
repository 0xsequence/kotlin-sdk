package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.chains.SequenceChains
import com.polygon_wallet.polygon_kotlin_sdk.models.CommitVerifierResponse
import com.polygon_wallet.polygon_kotlin_sdk.models.CompleteAuthResponse
import com.polygon_wallet.polygon_kotlin_sdk.models.SendTransactionResult
import com.polygon_wallet.polygon_kotlin_sdk.models.SequenceIdentity
import com.polygon_wallet.polygon_kotlin_sdk.models.SequenceWallet
import com.polygon_wallet.polygon_kotlin_sdk.models.SignMessageResult
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.network.arrayOrEmpty
import com.polygon_wallet.polygon_kotlin_sdk.network.int
import com.polygon_wallet.polygon_kotlin_sdk.network.objectOrNull
import com.polygon_wallet.polygon_kotlin_sdk.network.parseJsonObject
import com.polygon_wallet.polygon_kotlin_sdk.network.string
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
        val payload = WalletPayloadBuilder.buildCommitVerifierPayload(email)
        val response = parseCommitVerifier(
            postSignedWalletRequest(
                endpoint = WalletApi.Endpoints.commitVerifier,
                payload = payload,
                privateKey = privateKey,
            ),
        )

        val challenge = requireNotNull(response.challenge) { "CommitVerifier response missing challenge" }
        val verifier = requireNotNull(response.verifier) { "CommitVerifier response missing verifier" }

        session.replaceForPendingAuth(
            challenge = challenge,
            verifier = verifier,
            signerAddress = signerAddress,
        )
        persistCurrentSession(privateKey)
        privateKey.fill(0)

        return response
    }

    internal suspend fun confirmEmailSignIn(code: String): CompleteAuthResponse {
        val snapshot = session.requireSnapshot()
        val payload = WalletPayloadBuilder.buildCompleteAuthPayloadFromCode(
            verifier = snapshot.verifier,
            challenge = snapshot.challenge,
            code = code,
        )

        return withPrivateKey { privateKey ->
            parseCompleteAuth(
                postSignedWalletRequest(
                    endpoint = WalletApi.Endpoints.completeAuth,
                    payload = payload,
                    privateKey = privateKey,
                ),
            )
        }
    }

    suspend fun completeEmailSignIn(
        code: String,
        walletType: String = environment.defaultWalletType,
    ): SequenceWallet = completeEmailSignIn(
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
        walletType: String = environment.defaultWalletType,
        selectWallet: suspend (List<SequenceWallet>) -> SequenceWallet,
    ): SequenceWallet {
        val auth = confirmEmailSignIn(code)
        val candidateWallets = auth.wallets.filter { it.type == walletType }

        return when {
            candidateWallets.isEmpty() -> createWallet(walletType)
            candidateWallets.size == 1 -> {
                val selected = candidateWallets.single()
                useWallet(
                    walletType = requireNotNull(selected.type) {
                        "CompleteAuth wallet missing type"
                    },
                    walletIndex = selected.index ?: 0,
                )
            }
            else -> {
                val selected = selectWallet(candidateWallets)
                require(candidateWallets.contains(selected)) {
                    "Selected wallet is not one of the available options"
                }
                useWallet(
                    walletType = requireNotNull(selected.type) {
                        "Selected wallet is missing type"
                    },
                    walletIndex = selected.index ?: 0,
                )
            }
        }
    }

    internal suspend fun resolveWallet(
        completeAuth: CompleteAuthResponse,
        walletType: String = environment.defaultWalletType,
    ): SequenceWallet {
        val existingWallet = completeAuth.wallets.firstOrNull { it.type == walletType }
        return if (existingWallet != null) {
            useWallet(
                walletType = walletType,
                walletIndex = existingWallet.index ?: 0,
            )
        } else {
            createWallet(walletType)
        }
    }

    internal suspend fun useWallet(
        walletType: String = environment.defaultWalletType,
        walletIndex: Int = 0,
    ): SequenceWallet {
        session.requireSnapshot()
        val payload = WalletPayloadBuilder.buildUseWalletPayload(
            walletType = walletType,
            walletIndex = walletIndex,
        )
        val wallet = parseWallet(
            withPrivateKey { privateKey ->
                postSignedWalletRequest(
                    endpoint = WalletApi.Endpoints.useWallet,
                    payload = payload,
                    privateKey = privateKey,
                )
            },
        )

        session.updateWalletAddress(requireNotNull(wallet.address) { "UseWallet response missing address" })
        persistCurrentSession()
        return wallet
    }

    internal suspend fun createWallet(walletType: String = environment.defaultWalletType): SequenceWallet {
        session.requireSnapshot()
        val payload = WalletPayloadBuilder.buildCreateWalletPayload(walletType)
        val wallet = parseWallet(
            withPrivateKey { privateKey ->
                postSignedWalletRequest(
                    endpoint = WalletApi.Endpoints.createWallet,
                    payload = payload,
                    privateKey = privateKey,
                )
            },
        )

        session.updateWalletAddress(requireNotNull(wallet.address) { "CreateWallet response missing address" })
        persistCurrentSession()
        return wallet
    }

    private fun persistCurrentSession(privateKey: ByteArray? = null) {
        val snapshot = session.snapshot() ?: return
        privateKeyStore.save(snapshot, privateKey)
    }

    suspend fun signMessage(chainId: String, message: String): SignMessageResult {
        session.requireSnapshot()
        val payload = WalletPayloadBuilder.buildSignMessagePayload(
            wallet = requireWalletAddress(),
            network = SequenceChains.chainNameFor(chainId),
            message = message,
        )

        val body = withPrivateKey { privateKey ->
            postSignedWalletRequest(
                endpoint = WalletApi.Endpoints.signMessage,
                payload = payload,
                privateKey = privateKey,
            )
        }
        return SignMessageResult(
            signature = requireNotNull(parseJsonObject(body).string("signature")) {
                "SignMessage response missing signature"
            },
        )
    }

    suspend fun sendTransaction(
        chainId: String,
        to: String,
        value: String,
    ): SendTransactionResult {
        session.requireSnapshot()
        val payload = WalletPayloadBuilder.buildSendTransactionPayload(
            wallet = requireWalletAddress(),
            network = SequenceChains.chainNameFor(chainId),
            to = to,
            value = value,
        )

        val body = withPrivateKey { privateKey ->
            postSignedWalletRequest(
                endpoint = WalletApi.Endpoints.sendTransaction,
                payload = payload,
                privateKey = privateKey,
            )
        }
        val responseObject = parseJsonObject(body).objectOrNull("response")
        return SendTransactionResult(
            txHash = requireNotNull(responseObject?.string("txHash")) {
                "SendTransaction response missing response.txHash"
            },
        )
    }

    private suspend fun postSignedWalletRequest(
        endpoint: String,
        payload: String,
        privateKey: ByteArray,
    ): String {
        val signedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = endpoint,
            nonce = nonceGenerator().toString(),
            payload = payload,
            scope = environment.authorizationScope,
            privateKey = privateKey,
            requestPathPrefix = environment.walletRequestPathPrefix(),
        )

        val response = transport.postJson(
            baseUrl = environment.walletApiUrl,
            path = endpoint,
            body = payload,
            headers = mapOf(
                SequenceEnvironment.accessKeyHeaderName to projectAccessKey,
                "Origin" to "http://localhost:3000",
                "Accept" to "application/json",
                "Authorization" to authorizationHeaderValue(signedRequest.authorizationHeader),
            ),
        )
        return response.body
    }

    private suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
        privateKeyStore.withPrivateKey(block)

    private fun authorizationHeaderValue(headerLine: String): String =
        headerLine.removePrefix(SequenceEnvironment.authorizationHeaderPrefix)

    private fun parseCommitVerifier(body: String): CommitVerifierResponse {
        val root = parseJsonObject(body)
        return CommitVerifierResponse(
            verifier = root.string("verifier"),
            loginHint = root.string("loginHint"),
            challenge = root.string("challenge"),
        )
    }

    private fun parseCompleteAuth(body: String): CompleteAuthResponse {
        val root = parseJsonObject(body)
        val identityObject = root.objectOrNull("identity")
        val identity = identityObject?.let {
            SequenceIdentity(
                type = it.string("type"),
                sub = it.string("sub"),
                email = it.string("email"),
            )
        }

        val wallets = root.arrayOrEmpty("wallets").mapNotNull { element ->
            val objectValue = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            SequenceWallet(
                type = objectValue.string("type"),
                address = objectValue.string("address"),
                index = objectValue.int("index"),
                comment = objectValue.string("comment"),
            )
        }

        return CompleteAuthResponse(identity = identity, wallets = wallets)
    }

    private fun parseWallet(body: String): SequenceWallet {
        val walletObject = requireNotNull(parseJsonObject(body).objectOrNull("wallet")) {
            "Wallet response missing wallet"
        }
        return SequenceWallet(
            type = walletObject.string("type"),
            address = walletObject.string("address"),
            index = walletObject.int("index"),
            comment = walletObject.string("comment"),
        )
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
