package com.omsclient.kotlin_sdk

import android.content.Context
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSession
import com.omsclient.kotlin_sdk.storage.AndroidKeystoreSessionStore
import com.omsclient.kotlin_sdk.storage.OMSClientSecureSessionStore
import com.omsclient.kotlin_sdk.utils.OMSClientUtils
import com.omsclient.kotlin_sdk.wallet.WalletClient
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.net.URI

class OMSClient internal constructor(
    projectAccessKey: String,
    private val environment: OMSClientEnvironment = OMSClientEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
    walletSession: OMSClientSession = OMSClientSession(),
    sessionStore: OMSClientSecureSessionStore? = null,
) {
    private val transport = OMSClientHttpClient(okHttpClient)

    val wallet: WalletClient = WalletClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
        session = walletSession,
        sessionStore = sessionStore,
    )

    val utils: OMSClientUtils = OMSClientUtils(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    val indexer: IndexerClient = IndexerClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    init {
        wallet.restorePersistedSession()
    }

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
        sessionStore = AndroidKeystoreSessionStore(
            context = context.applicationContext,
            alias = scopedSessionKeyAlias(environment),
            fileName = scopedSessionFileName(environment),
        ),
    )

    fun signOut() {
        wallet.signOut()
    }

    companion object {
        internal fun scopedSessionKeyAlias(
            environment: OMSClientEnvironment,
        ): String = "oms-client-session-${scopedSessionSuffix(environment)}"

        internal fun scopedSessionFileName(
            environment: OMSClientEnvironment,
        ): String = "oms-client-session-${scopedSessionSuffix(environment)}.json"

        private fun scopedSessionSuffix(
            environment: OMSClientEnvironment,
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
