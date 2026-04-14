package com.polygon_wallet.polygon_kotlin_sdk

import android.content.Context
import com.polygon_wallet.polygon_kotlin_sdk.indexer.SequenceIndexerClient
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceWalletSession
import com.polygon_wallet.polygon_kotlin_sdk.storage.AndroidKeystoreSessionStore
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequenceSecureSessionStore
import com.polygon_wallet.polygon_kotlin_sdk.utils.PolygonSdkUtils
import com.polygon_wallet.polygon_kotlin_sdk.wallet.SequenceWalletClient
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.net.URI

class PolygonSdk internal constructor(
    projectAccessKey: String,
    environment: SequenceEnvironment = SequenceEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
    walletSession: SequenceWalletSession = SequenceWalletSession(),
    sessionStore: SequenceSecureSessionStore? = null,
) {
    private val transport = SequenceHttpClient(okHttpClient)

    val wallet: SequenceWalletClient = SequenceWalletClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
        session = walletSession,
        sessionStore = sessionStore,
    )

    val utils: PolygonSdkUtils = PolygonSdkUtils(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    val indexer: SequenceIndexerClient = SequenceIndexerClient(
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
        environment: SequenceEnvironment = SequenceEnvironment(),
        okHttpClient: OkHttpClient = OkHttpClient(),
    ) : this(
        projectAccessKey = projectAccessKey,
        environment = environment,
        okHttpClient = okHttpClient,
        walletSession = SequenceWalletSession(),
        sessionStore = AndroidKeystoreSessionStore(
            context = context.applicationContext,
            alias = scopedSessionKeyAlias(environment),
            fileName = scopedSessionFileName(environment),
        ),
    )

    companion object {
        internal fun scopedSessionKeyAlias(
            environment: SequenceEnvironment,
        ): String = "polygon-wallet-session-${scopedSessionSuffix(environment)}"

        internal fun scopedSessionFileName(
            environment: SequenceEnvironment,
        ): String = "polygon-wallet-session-${scopedSessionSuffix(environment)}.json"

        private fun scopedSessionSuffix(
            environment: SequenceEnvironment,
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
