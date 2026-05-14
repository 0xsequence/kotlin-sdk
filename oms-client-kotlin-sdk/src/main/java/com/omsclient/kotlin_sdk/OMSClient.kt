package com.omsclient.kotlin_sdk

import android.content.Context
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierResponse
import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSession
import com.omsclient.kotlin_sdk.storage.AndroidKeystoreSessionStore
import com.omsclient.kotlin_sdk.storage.AndroidOidcRedirectAuthStore
import com.omsclient.kotlin_sdk.storage.OMSClientSecureSessionStore
import com.omsclient.kotlin_sdk.wallet.AndroidKeystoreP256CredentialSigner
import com.omsclient.kotlin_sdk.wallet.CompleteAuthResult
import com.omsclient.kotlin_sdk.wallet.CredentialSigner
import com.omsclient.kotlin_sdk.wallet.OidcProviderConfig
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthResult
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.StartOidcRedirectAuthResult
import com.omsclient.kotlin_sdk.wallet.WalletClient
import okhttp3.OkHttpClient
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/**
 * Main entry point for OMS Client.
 *
 * Auth and session lifecycle methods live on this class. Wallet operations for
 * the currently selected wallet are available through [wallet].
 */
class OMSClient internal constructor(
    projectAccessKey: String,
    private val environment: OMSClientEnvironment = OMSClientEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
    walletSession: OMSClientSession = OMSClientSession(),
    sessionStore: OMSClientSecureSessionStore? = null,
    oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
    credentialSigner: CredentialSigner? = null,
) {
    private val transport = OMSClientHttpClient(okHttpClient)

    val wallet: WalletClient =
        WalletClient(
            projectAccessKey = projectAccessKey,
            environment = environment,
            transport = transport,
            session = walletSession,
            sessionStore = sessionStore,
            oidcRedirectAuthStore = oidcRedirectAuthStore,
            credentialSigner = credentialSigner,
        )

    val indexer: IndexerClient =
        IndexerClient(
            projectAccessKey = projectAccessKey,
            environment = environment,
            transport = transport,
        )

    init {
        wallet.restorePersistedSession()
    }

    /**
     * Snapshot of the current durable wallet-session state.
     */
    val session: OMSClientSessionState
        get() {
            val snapshot = wallet.snapshotSession()
            val walletAddress = snapshot?.walletAddress
            if (walletAddress.isNullOrBlank()) {
                return OMSClientSessionState(walletAddress = null)
            }
            return OMSClientSessionState(
                walletAddress = walletAddress,
                expiresAt = snapshot.expiresAt?.toInstantOrNull(),
                loginType = snapshot.loginType,
                sessionEmail = snapshot.sessionEmail,
            )
        }

    /**
     * Networks currently supported by this SDK build.
     */
    val supportedNetworks: List<Network>
        get() = OMSClientNetworks.supportedNetworks

    /**
     * Returns a supported network by chain id, or null when the chain id is not
     * supported by this SDK build.
     */
    fun network(chainId: String): Network? = supportedNetworks.firstOrNull { it.chainId == chainId }

    /**
     * Creates an Android-backed client with persisted secure storage for
     * completed wallet sessions.
     */
    constructor(
        context: Context,
        projectAccessKey: String,
        environment: OMSClientEnvironment = OMSClientEnvironment(),
        okHttpClient: OkHttpClient = OkHttpClient(),
    ) : this(
        projectAccessKey = projectAccessKey,
        environment = environment,
        okHttpClient = okHttpClient,
        walletSession = OMSClientSession(),
        sessionStore =
            AndroidKeystoreSessionStore(
                context = context.applicationContext,
                alias = scopedSessionKeyAlias(environment),
                fileName = scopedSessionFileName(environment),
            ),
        oidcRedirectAuthStore =
            AndroidOidcRedirectAuthStore(
                context = context.applicationContext,
                fileName = scopedOidcRedirectAuthFileName(environment),
            ),
        credentialSigner =
            AndroidKeystoreP256CredentialSigner(
                context = context.applicationContext,
                alias = scopedCredentialKeyAlias(environment),
                nonceStoreName = scopedCredentialNonceStoreName(environment),
            ),
    )

    /**
     * Starts email OTP authentication.
     *
     * The returned verifier response can be shown or inspected by the app, and
     * the OTP can later be completed with [completeEmailAuth].
     */
    suspend fun startEmailAuth(email: String): CommitVerifierResponse = wallet.startEmailAuth(email)

    /**
     * Signs in with an OIDC ID token and resolves the only available wallet for
     * the requested [walletType].
     */
    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet =
        wallet.signInWithOidcIdToken(
            idToken = idToken,
            issuer = issuer,
            audience = audience,
            walletType = walletType,
        )

    /**
     * Signs in with an OIDC ID token and either activates a wallet automatically
     * or returns the available wallets for app-driven selection.
     */
    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        autoActivate: Boolean,
        walletType: WalletType = environment.defaultWalletType,
    ): CompleteAuthResult =
        wallet.signInWithOidcIdToken(
            idToken = idToken,
            issuer = issuer,
            audience = audience,
            autoActivate = autoActivate,
            walletType = walletType,
        )

    /**
     * Signs in with an OIDC ID token and lets the app select from multiple
     * available wallets when more than one wallet matches [walletType].
     */
    suspend fun signInWithOidcIdToken(
        idToken: String,
        issuer: String,
        audience: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet =
        wallet.signInWithOidcIdToken(
            idToken = idToken,
            issuer = issuer,
            audience = audience,
            walletType = walletType,
            selectWallet = selectWallet,
        )

    /**
     * Starts OIDC authorization-code PKCE redirect authentication.
     *
     * Open the returned [StartOidcRedirectAuthResult.authorizationUrl] in a
     * browser or Custom Tabs. After the provider redirects back to the app,
     * pass the callback URL to [handleOidcRedirectCallback].
     */
    suspend fun startOidcRedirectAuth(
        provider: OidcProviderConfig,
        redirectUri: String,
        walletType: WalletType = environment.defaultWalletType,
        relayRedirectUri: String? = provider.relayRedirectUri,
        authorizeParams: Map<String, String> = emptyMap(),
    ): StartOidcRedirectAuthResult =
        wallet.startOidcRedirectAuth(
            provider = provider,
            redirectUri = redirectUri,
            walletType = walletType,
            relayRedirectUri = relayRedirectUri,
            authorizeParams = authorizeParams,
        )

    /**
     * Safely handles an incoming OIDC authorization-code PKCE redirect callback.
     *
     * This method is idempotent and safe to call for every incoming app link.
     * Unrelated links return [OidcRedirectAuthResult.NotOidcRedirectCallback],
     * stale callbacks return [OidcRedirectAuthResult.NoPendingAuth], and a
     * successful callback returns [OidcRedirectAuthResult.Completed] or
     * [OidcRedirectAuthResult.WalletSelection] when [autoActivate] is false.
     */
    suspend fun handleOidcRedirectCallback(
        callbackUrl: String?,
        selectWallet: suspend (List<Wallet>) -> Wallet = { wallets ->
            require(wallets.size == 1) {
                "Multiple wallets are available. Provide selectWallet to choose one."
            }
            wallets.single()
        },
    ): OidcRedirectAuthResult =
        wallet.handleOidcRedirectCallback(
            callbackUrl = callbackUrl,
            selectWallet = selectWallet,
        )

    /**
     * Safely handles an incoming OIDC authorization-code PKCE redirect callback
     * and optionally returns wallets for app-driven selection.
     */
    suspend fun handleOidcRedirectCallback(
        callbackUrl: String?,
        autoActivate: Boolean,
        selectWallet: suspend (List<Wallet>) -> Wallet = { wallets ->
            require(wallets.size == 1) {
                "Multiple wallets are available. Provide selectWallet to choose one."
            }
            wallets.single()
        },
    ): OidcRedirectAuthResult =
        wallet.handleOidcRedirectCallback(
            callbackUrl = callbackUrl,
            autoActivate = autoActivate,
            selectWallet = selectWallet,
        )

    /**
     * Completes email OTP authentication and resolves the only available wallet
     * for the requested [walletType].
     */
    suspend fun completeEmailAuth(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
    ): Wallet =
        wallet.completeEmailAuth(
            code = code,
            walletType = walletType,
        )

    /**
     * Completes email OTP authentication and either activates a wallet
     * automatically or returns the available wallets for app-driven selection.
     */
    suspend fun completeEmailAuth(
        code: String,
        autoActivate: Boolean,
        walletType: WalletType = environment.defaultWalletType,
    ): CompleteAuthResult =
        wallet.completeEmailAuth(
            code = code,
            autoActivate = autoActivate,
            walletType = walletType,
        )

    /**
     * Completes email OTP authentication and lets the app select from multiple
     * available wallets when more than one wallet matches [walletType].
     */
    suspend fun completeEmailAuth(
        code: String,
        walletType: WalletType = environment.defaultWalletType,
        selectWallet: suspend (List<Wallet>) -> Wallet,
    ): Wallet =
        wallet.completeEmailAuth(
            code = code,
            walletType = walletType,
            selectWallet = selectWallet,
        )

    /**
     * Signs out of the current account and clears all in-memory and persisted
     * session material.
     */
    fun signOut() {
        wallet.signOut()
    }

    companion object {
        internal fun scopedSessionKeyAlias(environment: OMSClientEnvironment): String =
            "oms-client-session-${scopedSessionSuffix(environment)}"

        internal fun scopedSessionFileName(environment: OMSClientEnvironment): String =
            "oms-client-session-${scopedSessionSuffix(environment)}.json"

        internal fun scopedCredentialKeyAlias(environment: OMSClientEnvironment): String =
            "oms-client-credential-${scopedSessionSuffix(environment)}"

        internal fun scopedCredentialNonceStoreName(environment: OMSClientEnvironment): String =
            "oms-client-credential-nonces-${scopedSessionSuffix(environment)}"

        internal fun scopedOidcRedirectAuthFileName(environment: OMSClientEnvironment): String =
            "oms-client-oidc-redirect-auth-${scopedSessionSuffix(environment)}.json"

        private fun scopedSessionSuffix(environment: OMSClientEnvironment): String {
            val source =
                buildString {
                    append(normalizedWalletApiOrigin(environment.walletApiUrl))
                    append('\u0000')
                    append(environment.authorizationScope)
                }
            return MessageDigest
                .getInstance("SHA-256")
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

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
