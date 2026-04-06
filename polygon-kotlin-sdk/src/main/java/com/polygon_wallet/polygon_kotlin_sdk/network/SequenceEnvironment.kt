package com.polygon_wallet.polygon_kotlin_sdk.network

import com.polygon_wallet.polygon_kotlin_sdk.chains.SequenceChains
import com.polygon_wallet.polygon_kotlin_sdk.wallet.WalletApi
import java.net.URI

class SequenceEnvironment(
    val walletApiUrl: String = walletApiUrlDefault,
    val apiRpcUrl: String = apiRpcUrlDefault,
    val indexerUrlTemplate: String = indexerUrlTemplateDefault,
) {
    internal val authorizationScope: String = authorizationScopeDefault
    internal val defaultWalletType: String = WalletApi.defaultWalletType

    fun indexerUrlForChainId(chainId: String): String =
        indexerUrlTemplate.replace("{value}", SequenceChains.chainNameFor(chainId))

    internal fun walletRequestPathPrefix(): String =
        URI(walletApiUrl).path.ifBlank { walletApiPathDefault }.trimEnd('/')

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SequenceEnvironment) return false

        return walletApiUrl == other.walletApiUrl &&
            apiRpcUrl == other.apiRpcUrl &&
            indexerUrlTemplate == other.indexerUrlTemplate
    }

    override fun hashCode(): Int {
        var result = walletApiUrl.hashCode()
        result = 31 * result + apiRpcUrl.hashCode()
        result = 31 * result + indexerUrlTemplate.hashCode()
        return result
    }

    override fun toString(): String =
        "SequenceEnvironment(walletApiUrl=$walletApiUrl, apiRpcUrl=$apiRpcUrl, indexerUrlTemplate=$indexerUrlTemplate)"

    companion object {
        internal const val accessKeyHeaderName: String = "X-Access-Key"
        internal const val authorizationHeaderPrefix: String = "Authorization: "
        internal const val authorizationScopeDefault: String = "@1:test"
        internal const val walletApiPathDefault: String = "/rpc/Wallet"
        const val walletApiUrlDefault: String = "https://d1sctl7y41hot5.cloudfront.net/rpc/Wallet"
        const val apiRpcUrlDefault: String = "https://api.sequence.app/rpc/API"
        const val indexerUrlTemplateDefault: String = "https://{value}-indexer.sequence.app/rpc/Indexer/"

        private const val devApiRpcUrlDefault: String = "https://dev-api.sequence.app/rpc/API"
        private const val devIndexerUrlTemplateDefault: String = "https://dev-{value}-indexer.sequence.app/rpc/Indexer/"

        fun demoDefaults(): SequenceEnvironment =
            SequenceEnvironment(
                apiRpcUrl = devApiRpcUrlDefault,
                indexerUrlTemplate = devIndexerUrlTemplateDefault,
            )
    }
}
