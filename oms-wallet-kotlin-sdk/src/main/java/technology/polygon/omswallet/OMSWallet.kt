package technology.polygon.omswallet

import android.content.Context
import okhttp3.OkHttpClient
import technology.polygon.omswallet.indexer.IndexerClient
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSession
import technology.polygon.omswallet.storage.AndroidOidcRedirectAuthStore
import technology.polygon.omswallet.storage.AndroidSessionMetadataStore
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.wallet.AndroidKeystoreP256CredentialSigner
import technology.polygon.omswallet.wallet.CredentialSigner
import technology.polygon.omswallet.wallet.OidcRedirectAuthStore
import technology.polygon.omswallet.wallet.WalletClient
import java.net.URI
import java.security.MessageDigest

/**
 * Main entry point for OMS Wallet.
 *
 * Wallet auth, session lifecycle, signing, and transaction methods live on
 * [wallet]. Indexer methods live on [indexer].
 */
class OMSWallet private constructor(
    publishableKey: String,
    projectId: String?,
    environment: OMSWalletEnvironment?,
    okHttpClient: OkHttpClient,
    walletSession: OMSWalletSession?,
    sessionStore: OMSWalletSessionMetadataStore?,
    oidcRedirectAuthStore: OidcRedirectAuthStore?,
    credentialSigner: CredentialSigner?,
    projectScopeKey: String?,
    walletImport: WalletImportConfiguration?,
) {
    private val resolvedProjectId: String = projectId ?: parsePublishableKey(publishableKey).projectId
    private val resolvedEnvironment: OMSWalletEnvironment =
        environment
            ?: OMSWalletEnvironment.fromPublishableKey(publishableKey)
    private val transport = OMSWalletHttpClient(okHttpClient)

    val wallet: WalletClient =
        WalletClient.create(
            publishableKey = publishableKey,
            projectId = resolvedProjectId,
            environment = resolvedEnvironment,
            transport = transport,
            walletSession = walletSession,
            sessionStore = sessionStore,
            oidcRedirectAuthStore = oidcRedirectAuthStore,
            credentialSigner = credentialSigner,
            projectScopeKey = projectScopeKey,
            walletImport = walletImport,
        )

    val indexer: IndexerClient =
        IndexerClient.create(
            publishableKey = publishableKey,
            environment = resolvedEnvironment,
            transport = transport,
        )

    init {
        wallet.restorePersistedSession()
    }

    /**
     * Creates an Android-backed client with separate session metadata storage
     * and Android Keystore request signing.
     *
     * The metadata store can restore completed wallet/session state, but it
     * cannot authorize wallet API requests. Request authorization uses a
     * separate non-extractable Android Keystore P-256 credential.
     */
    constructor(
        context: Context,
        publishableKey: String,
        okHttpClient: OkHttpClient = OkHttpClient(),
        walletImport: WalletImportConfiguration? = null,
    ) : this(
        publishableKey = publishableKey,
        projectId = projectIdFromPublishableKey(publishableKey),
        environment = environmentFromPublishableKey(publishableKey),
        okHttpClient = okHttpClient,
        walletSession = null,
        sessionStore =
            AndroidSessionMetadataStore(
                context = context.applicationContext,
                fileName = scopedSessionFileName(publishableKey),
            ),
        oidcRedirectAuthStore =
            AndroidOidcRedirectAuthStore(
                context = context.applicationContext,
                fileName = scopedOidcRedirectAuthFileName(publishableKey),
            ),
        credentialSigner =
            AndroidKeystoreP256CredentialSigner(
                context = context.applicationContext,
                alias = scopedCredentialKeyAlias(publishableKey),
                nonceStoreName = scopedCredentialNonceStoreName(publishableKey),
            ),
        projectScopeKey = scopedSessionSuffix(publishableKey),
        walletImport = walletImport,
    )

    companion object {
        @JvmSynthetic
        internal fun createForTesting(
            publishableKey: String,
            projectId: String? = null,
            environment: OMSWalletEnvironment? = null,
            okHttpClient: OkHttpClient = OkHttpClient(),
            walletSession: OMSWalletSession = OMSWalletSession(),
            sessionStore: OMSWalletSessionMetadataStore? = null,
            oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
            credentialSigner: CredentialSigner? = null,
            projectScopeKey: String? = null,
            walletImport: WalletImportConfiguration? = null,
        ): OMSWallet =
            OMSWallet(
                publishableKey = publishableKey,
                projectId = projectId,
                environment = environment,
                okHttpClient = okHttpClient,
                walletSession = walletSession,
                sessionStore = sessionStore,
                oidcRedirectAuthStore = oidcRedirectAuthStore,
                credentialSigner = credentialSigner,
                projectScopeKey = projectScopeKey,
                walletImport = walletImport,
            )

        private fun projectIdFromPublishableKey(publishableKey: String): String = parsePublishableKey(publishableKey).projectId

        private fun environmentFromPublishableKey(publishableKey: String): OMSWalletEnvironment =
            OMSWalletEnvironment.fromPublishableKey(publishableKey)

        private fun scopedSessionFileName(publishableKey: String): String =
            scopedSessionFileName(projectIdFromPublishableKey(publishableKey), environmentFromPublishableKey(publishableKey))

        private fun scopedCredentialKeyAlias(publishableKey: String): String =
            scopedCredentialKeyAlias(projectIdFromPublishableKey(publishableKey), environmentFromPublishableKey(publishableKey))

        private fun scopedCredentialNonceStoreName(publishableKey: String): String =
            scopedCredentialNonceStoreName(projectIdFromPublishableKey(publishableKey), environmentFromPublishableKey(publishableKey))

        private fun scopedOidcRedirectAuthFileName(publishableKey: String): String =
            scopedOidcRedirectAuthFileName(projectIdFromPublishableKey(publishableKey), environmentFromPublishableKey(publishableKey))

        private fun scopedSessionSuffix(publishableKey: String): String =
            scopedSessionSuffix(projectIdFromPublishableKey(publishableKey), environmentFromPublishableKey(publishableKey))

        internal fun scopedSessionFileName(
            projectId: String,
            environment: OMSWalletEnvironment,
        ): String = "oms-wallet-session-${scopedSessionSuffix(projectId, environment)}.json"

        internal fun scopedCredentialKeyAlias(
            projectId: String,
            environment: OMSWalletEnvironment,
        ): String = "oms-wallet-credential-${scopedSessionSuffix(projectId, environment)}"

        internal fun scopedCredentialNonceStoreName(
            projectId: String,
            environment: OMSWalletEnvironment,
        ): String = "oms-wallet-credential-nonces-${scopedSessionSuffix(projectId, environment)}"

        internal fun scopedOidcRedirectAuthFileName(
            projectId: String,
            environment: OMSWalletEnvironment,
        ): String = "oms-wallet-oidc-redirect-auth-${scopedSessionSuffix(projectId, environment)}.json"

        private fun scopedSessionSuffix(
            projectId: String,
            environment: OMSWalletEnvironment,
        ): String {
            val source =
                buildString {
                    append(normalizedWalletApiOrigin(environment.walletApiUrl))
                    append('\u0000')
                    append(projectId)
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
