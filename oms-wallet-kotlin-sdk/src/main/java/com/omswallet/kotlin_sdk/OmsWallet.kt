package com.omswallet.kotlin_sdk

import android.content.Context
import com.omswallet.kotlin_sdk.generated.waas.CommitVerifierResponse
import com.omswallet.kotlin_sdk.generated.waas.SendTransactionResponse
import com.omswallet.kotlin_sdk.generated.waas.SignMessageResponse
import com.omswallet.kotlin_sdk.generated.waas.Wallet
import com.omswallet.kotlin_sdk.generated.waas.WalletType
import com.omswallet.kotlin_sdk.indexer.OmsWalletIndexerClient
import com.omswallet.kotlin_sdk.models.SendTransactionRequest
import com.omswallet.kotlin_sdk.network.OmsWalletEnvironment
import com.omswallet.kotlin_sdk.network.OmsWalletHttpClient
import com.omswallet.kotlin_sdk.session.OmsWalletSession
import com.omswallet.kotlin_sdk.storage.AndroidKeystoreSessionStore
import com.omswallet.kotlin_sdk.storage.OmsWalletSecureSessionStore
import com.omswallet.kotlin_sdk.utils.OmsWalletUtils
import com.omswallet.kotlin_sdk.wallet.OmsWalletClient
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.net.URI

class OmsWallet internal constructor(
    projectAccessKey: String,
    private val environment: OmsWalletEnvironment = OmsWalletEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
    walletSession: OmsWalletSession = OmsWalletSession(),
    sessionStore: OmsWalletSecureSessionStore? = null,
) {
    private val transport = OmsWalletHttpClient(okHttpClient)

    private val walletClient: OmsWalletClient = OmsWalletClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
        session = walletSession,
        sessionStore = sessionStore,
    )

    val hasPendingSignIn: Boolean
        get() = walletClient.hasPendingSignIn

    val walletAddress: String?
        get() = walletClient.walletAddress

    val signerAddress: String?
        get() = walletClient.signerAddress

    val utils: OmsWalletUtils = OmsWalletUtils(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    val indexer: OmsWalletIndexerClient = OmsWalletIndexerClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    init {
        walletClient.restorePersistedSession()
    }

    constructor(
        context: Context,
        projectAccessKey: String,
        environment: OmsWalletEnvironment = OmsWalletEnvironment(),
        okHttpClient: OkHttpClient = OkHttpClient(),
    ) : this(
        projectAccessKey = projectAccessKey,
        environment = environment,
        okHttpClient = okHttpClient,
        walletSession = OmsWalletSession(),
        sessionStore = AndroidKeystoreSessionStore(
            context = context.applicationContext,
            alias = scopedSessionKeyAlias(environment),
            fileName = scopedSessionFileName(environment),
        ),
    )

    fun clearSession() {
        walletClient.clearSession()
    }

    suspend fun signInWithEmail(email: String): CommitVerifierResponse =
        walletClient.signInWithEmail(email)

    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet = walletClient.signInWithOidcIdToken(
        idToken = idToken,
        issuer = issuer,
        audience = audience,
        walletType = walletType,
    )

    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet = walletClient.signInWithOidcIdToken(
        idToken = idToken,
        issuer = issuer,
        audience = audience,
        walletType = walletType,
        selectWallet = selectWallet,
    )

    suspend fun completeEmailSignIn(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet = walletClient.completeEmailSignIn(
        code = code,
        walletType = walletType,
    )

    suspend fun completeEmailSignIn(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet = walletClient.completeEmailSignIn(
        code = code,
        walletType = walletType,
        selectWallet = selectWallet,
    )

    suspend fun signMessage(chainId: String, message: String): SignMessageResponse =
        walletClient.signMessage(
            chainId = chainId,
            message = message,
        )

    suspend fun sendTransaction(
        chainId: String,
        to: String,
        value: String,
    ): SendTransactionResponse = walletClient.sendTransaction(
        chainId = chainId,
        to = to,
        value = value,
    )

    suspend fun sendTransaction(
        chainId: String,
        request: SendTransactionRequest,
    ): SendTransactionResponse = walletClient.sendTransaction(
        chainId = chainId,
        request = request,
    )

    companion object {
        internal fun scopedSessionKeyAlias(
            environment: OmsWalletEnvironment,
        ): String = "oms-wallet-session-${scopedSessionSuffix(environment)}"

        internal fun scopedSessionFileName(
            environment: OmsWalletEnvironment,
        ): String = "oms-wallet-session-${scopedSessionSuffix(environment)}.json"

        private fun scopedSessionSuffix(
            environment: OmsWalletEnvironment,
        ): String {
            val source = buildString {
                append(normalizedWalletApiOrigin(environment.walletApiUrl))
                append('\u0000')
                append(environment.authorizationScope)
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
        }

        private fun normalizedWalletApiOrigin(walletApiUrl: String): String {
            val uri = URI(walletApiUrl)
            return URI(
                uri.scheme?.lowercase(),
                uri.userInfo,
                uri.host?.lowercase(),
                uri.port,
                null,
                null,
                null,
            ).toString()
        }
    }
}
