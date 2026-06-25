package com.omsclient.kotlin_sdk.network

import com.omsclient.kotlin_sdk.models.WalletType
import com.omsclient.kotlin_sdk.parsePublishableKey
import java.net.URI

internal class OMSClientEnvironment(
    val walletApiUrl: String = walletApiUrlDefault,
    val indexerGatewayUrl: String = indexerGatewayUrlDefault,
) {
    internal val defaultWalletType: WalletType = WalletType.Ethereum

    internal fun walletApiBaseUrl(): String {
        val uri = URI(walletApiUrl)
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OMSClientEnvironment) return false

        return walletApiBaseUrl() == other.walletApiBaseUrl() &&
            indexerGatewayUrl == other.indexerGatewayUrl
    }

    override fun hashCode(): Int {
        var result = walletApiBaseUrl().hashCode()
        result = 31 * result + indexerGatewayUrl.hashCode()
        return result
    }

    override fun toString(): String = "OMSClientEnvironment(walletApiUrl=$walletApiUrl, indexerGatewayUrl=$indexerGatewayUrl)"

    companion object {
        internal const val accessKeyHeaderName: String = "Api-Key"
        internal const val walletSignatureHeaderName: String = "OMS-Wallet-Signature"
        internal const val walletSignatureHeaderPrefix: String = "$walletSignatureHeaderName: "
        const val walletApiUrlDefault: String = "https://d26giflyqapd29.cloudfront.net"
        const val indexerGatewayUrlDefault: String = "https://api.polygon.technology/v1/IndexerGateway/"

        fun fromPublishableKey(publishableKey: String): OMSClientEnvironment {
            val parsed = parsePublishableKey(publishableKey)
            return OMSClientEnvironment(
                walletApiUrl = parsed.walletApiUrl,
                indexerGatewayUrl = parsed.indexerGatewayUrl,
            )
        }
    }
}
