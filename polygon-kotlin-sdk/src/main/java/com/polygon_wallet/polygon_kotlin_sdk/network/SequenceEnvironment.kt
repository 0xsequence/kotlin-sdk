package com.polygon_wallet.polygon_kotlin_sdk.network

import com.polygon_wallet.polygon_kotlin_sdk.chains.SequenceChains
import com.polygon_wallet.polygon_kotlin_sdk.wallet.WalletApi
import java.net.URI

data class SequenceEnvironment(
    val walletApiUrl: String = walletApiUrlDefault,
    val apiRpcUrl: String = apiRpcUrlDefault,
    val indexerUrlTemplate: String = indexerUrlTemplateDefault,
    val authorizationScope: String = authorizationScopeDefault,
    val defaultWalletType: String = WalletApi.defaultWalletType,
) {
    fun indexerUrlForChainId(chainId: String): String =
        indexerUrlTemplate.replace("{value}", SequenceChains.chainNameFor(chainId))

    fun walletRequestPathPrefix(): String =
        URI(walletApiUrl).path.ifBlank { walletApiPathDefault }.trimEnd('/')

    companion object {
        const val accessKeyHeaderName: String = "X-Access-Key"
        const val authorizationHeaderPrefix: String = "Authorization: "
        const val authorizationScopeDefault: String = "@1:test"
        const val walletApiPathDefault: String = "/rpc/Wallet"
        const val walletApiUrlDefault: String = "https://d1sctl7y41hot5.cloudfront.net/rpc/Wallet"
        const val apiRpcUrlDefault: String = "https://api.sequence.app/rpc/API"
        const val indexerUrlTemplateDefault: String = "https://{value}-indexer.sequence.app/rpc/Indexer/"

        const val devApiRpcUrlDefault: String = "https://dev-api.sequence.app/rpc/API"
        const val devIndexerUrlTemplateDefault: String = "https://dev-{value}-indexer.sequence.app/rpc/Indexer/"

        fun demoDefaults(): SequenceEnvironment =
            SequenceEnvironment(
                apiRpcUrl = devApiRpcUrlDefault,
                indexerUrlTemplate = devIndexerUrlTemplateDefault,
            )
    }
}
