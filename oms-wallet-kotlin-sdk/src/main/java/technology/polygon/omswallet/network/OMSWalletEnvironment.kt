package technology.polygon.omswallet.network

import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.parsePublishableKey
import java.net.URI

internal class OMSWalletEnvironment(
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
        if (other !is OMSWalletEnvironment) return false

        return walletApiBaseUrl() == other.walletApiBaseUrl() &&
            indexerGatewayUrl == other.indexerGatewayUrl
    }

    override fun hashCode(): Int {
        var result = walletApiBaseUrl().hashCode()
        result = 31 * result + indexerGatewayUrl.hashCode()
        return result
    }

    override fun toString(): String = "OMSWalletEnvironment(walletApiUrl=$walletApiUrl, indexerGatewayUrl=$indexerGatewayUrl)"

    companion object {
        internal const val accessKeyHeaderName: String = "Api-Key"
        internal const val walletSignatureHeaderName: String = "OMS-Wallet-Signature"
        internal const val walletSignatureHeaderPrefix: String = "$walletSignatureHeaderName: "
        const val walletApiUrlDefault: String = "https://d26giflyqapd29.cloudfront.net"
        const val indexerGatewayUrlDefault: String = "https://api.polygon.technology/v1/IndexerGateway/"

        fun fromPublishableKey(publishableKey: String): OMSWalletEnvironment {
            val parsed = parsePublishableKey(publishableKey)
            return OMSWalletEnvironment(
                walletApiUrl = parsed.walletApiUrl,
                indexerGatewayUrl = parsed.indexerGatewayUrl,
            )
        }
    }
}
