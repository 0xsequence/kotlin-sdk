package com.omsclient.kotlin_sdk.network

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import java.net.URI

class OMSClientEnvironment(
    val walletApiUrl: String = walletApiUrlDefault,
    val apiRpcUrl: String = apiRpcUrlDefault,
    val indexerUrlTemplate: String = indexerUrlTemplateDefault,
    internal val authorizationScope: String = authorizationScopeDefault,
) {
    internal val defaultWalletType: WalletType = WalletType.Ethereum

    fun indexerUrlFor(network: Network): String = indexerUrlTemplate.replace("{value}", network.indexerName)

    internal fun walletApiBaseUrl(): String {
        val uri = URI(walletApiUrl)
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OMSClientEnvironment) return false

        return walletApiBaseUrl() == other.walletApiBaseUrl() &&
            apiRpcUrl == other.apiRpcUrl &&
            indexerUrlTemplate == other.indexerUrlTemplate &&
            authorizationScope == other.authorizationScope
    }

    override fun hashCode(): Int {
        var result = walletApiBaseUrl().hashCode()
        result = 31 * result + apiRpcUrl.hashCode()
        result = 31 * result + indexerUrlTemplate.hashCode()
        result = 31 * result + authorizationScope.hashCode()
        return result
    }

    override fun toString(): String =
        "OMSClientEnvironment(walletApiUrl=$walletApiUrl, apiRpcUrl=$apiRpcUrl, indexerUrlTemplate=$indexerUrlTemplate, authorizationScope=$authorizationScope)"

    companion object {
        internal const val accessKeyHeaderName: String = "X-Access-Key"
        internal const val walletSignatureHeaderName: String = "OMS-Wallet-Signature"
        internal const val walletSignatureHeaderPrefix: String = "$walletSignatureHeaderName: "
        internal const val authorizationScopeDefault: String = "proj_1"
        internal const val demoAuthorizationScopeDefault: String = "proj_1"
        const val walletApiUrlDefault: String = "https://d1sctl7y41hot5.cloudfront.net/rpc/Wallet"
        const val apiRpcUrlDefault: String = "https://api.sequence.app/rpc/API"
        const val indexerUrlTemplateDefault: String = "https://{value}-indexer.sequence.app/rpc/Indexer/"

        private const val devApiRpcUrlDefault: String = "https://dev-api.sequence.app/rpc/API"
        private const val devIndexerUrlTemplateDefault: String = "https://dev-{value}-indexer.sequence.app/rpc/Indexer/"

        fun demoDefaults(): OMSClientEnvironment =
            OMSClientEnvironment(
                apiRpcUrl = devApiRpcUrlDefault,
                indexerUrlTemplate = devIndexerUrlTemplateDefault,
                authorizationScope = demoAuthorizationScopeDefault,
            )
    }
}
