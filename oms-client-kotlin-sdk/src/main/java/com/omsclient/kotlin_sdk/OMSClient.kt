package com.omsclient.kotlin_sdk

import android.content.Context
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSession
import com.omsclient.kotlin_sdk.storage.AndroidOidcRedirectAuthStore
import com.omsclient.kotlin_sdk.storage.AndroidSessionMetadataStore
import com.omsclient.kotlin_sdk.storage.OMSClientSessionMetadataStore
import com.omsclient.kotlin_sdk.wallet.AndroidKeystoreP256CredentialSigner
import com.omsclient.kotlin_sdk.wallet.CredentialSigner
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.WalletClient
import okhttp3.OkHttpClient
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/**
 * Main entry point for OMS Client.
 *
 * Wallet auth, session lifecycle, signing, and transaction methods live on
 * [wallet]. Indexer methods live on [indexer].
 */
class OMSClient internal constructor(
    publishableKey: String,
    projectId: String? = null,
    environment: OMSClientEnvironment? = null,
    okHttpClient: OkHttpClient = OkHttpClient(),
    walletSession: OMSClientSession = OMSClientSession(),
    sessionStore: OMSClientSessionMetadataStore? = null,
    oidcRedirectAuthStore: OidcRedirectAuthStore? = null,
    credentialSigner: CredentialSigner? = null,
) {
    private val resolvedProjectId: String = projectId ?: parsePublishableKey(publishableKey).projectId
    private val resolvedEnvironment: OMSClientEnvironment =
        environment
            ?: if (projectId == null) {
                OMSClientEnvironment.fromPublishableKey(publishableKey)
            } else {
                OMSClientEnvironment()
            }
    private val transport = OMSClientHttpClient(okHttpClient)

    val wallet: WalletClient =
        WalletClient(
            publishableKey = publishableKey,
            projectId = resolvedProjectId,
            environment = resolvedEnvironment,
            transport = transport,
            session = walletSession,
            sessionStore = sessionStore,
            oidcRedirectAuthStore = oidcRedirectAuthStore,
            credentialSigner = credentialSigner,
        )

    val indexer: IndexerClient =
        IndexerClient(
            publishableKey = publishableKey,
            environment = resolvedEnvironment,
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
        environment: OMSClientEnvironment = OMSClientEnvironment.fromPublishableKey(publishableKey),
        okHttpClient: OkHttpClient = OkHttpClient(),
    ) : this(
        publishableKey = publishableKey,
        projectId = parsePublishableKey(publishableKey).projectId,
        environment = environment,
        okHttpClient = okHttpClient,
        walletSession = OMSClientSession(),
        sessionStore =
            AndroidSessionMetadataStore(
                context = context.applicationContext,
                fileName = scopedSessionFileName(parsePublishableKey(publishableKey).projectId, environment),
            ),
        oidcRedirectAuthStore =
            AndroidOidcRedirectAuthStore(
                context = context.applicationContext,
                fileName = scopedOidcRedirectAuthFileName(parsePublishableKey(publishableKey).projectId, environment),
            ),
        credentialSigner =
            AndroidKeystoreP256CredentialSigner(
                context = context.applicationContext,
                alias = scopedCredentialKeyAlias(parsePublishableKey(publishableKey).projectId, environment),
                nonceStoreName = scopedCredentialNonceStoreName(parsePublishableKey(publishableKey).projectId, environment),
            ),
    )

    companion object {
        internal fun scopedSessionFileName(
            projectId: String,
            environment: OMSClientEnvironment,
        ): String = "oms-client-session-${scopedSessionSuffix(projectId, environment)}.json"

        internal fun scopedCredentialKeyAlias(
            projectId: String,
            environment: OMSClientEnvironment,
        ): String = "oms-client-credential-${scopedSessionSuffix(projectId, environment)}"

        internal fun scopedCredentialNonceStoreName(
            projectId: String,
            environment: OMSClientEnvironment,
        ): String = "oms-client-credential-nonces-${scopedSessionSuffix(projectId, environment)}"

        internal fun scopedOidcRedirectAuthFileName(
            projectId: String,
            environment: OMSClientEnvironment,
        ): String = "oms-client-oidc-redirect-auth-${scopedSessionSuffix(projectId, environment)}.json"

        private fun scopedSessionSuffix(
            projectId: String,
            environment: OMSClientEnvironment,
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

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
