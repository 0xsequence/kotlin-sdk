package com.polygon_wallet.polygon_kotlin_sdk

import android.content.Context
import com.polygon_wallet.polygon_kotlin_sdk.api.SequenceApiClient
import com.polygon_wallet.polygon_kotlin_sdk.indexer.SequenceIndexerClient
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceWalletSession
import com.polygon_wallet.polygon_kotlin_sdk.storage.AndroidKeystoreSessionStore
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequenceSecureSessionStore
import com.polygon_wallet.polygon_kotlin_sdk.wallet.SequenceWalletClient
import okhttp3.OkHttpClient

class SequenceSdk(
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

    val api: SequenceApiClient = SequenceApiClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    val indexer: SequenceIndexerClient = SequenceIndexerClient(
        projectAccessKey = projectAccessKey,
        environment = environment,
        transport = transport,
    )

    constructor(
        context: Context,
        projectAccessKey: String,
        environment: SequenceEnvironment = SequenceEnvironment(),
        okHttpClient: OkHttpClient = OkHttpClient(),
        walletSession: SequenceWalletSession = SequenceWalletSession(),
        sessionStore: SequenceSecureSessionStore? = null,
    ) : this(
        projectAccessKey = projectAccessKey,
        environment = environment,
        okHttpClient = okHttpClient,
        walletSession = walletSession,
        sessionStore = sessionStore ?: AndroidKeystoreSessionStore(context.applicationContext),
    )
}
