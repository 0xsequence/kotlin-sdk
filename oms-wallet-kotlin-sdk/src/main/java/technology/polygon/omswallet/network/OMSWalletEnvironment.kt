package technology.polygon.omswallet.network

import technology.polygon.omswallet.parsePublishableKey
import java.net.URI

internal class OMSWalletEnvironment(
    val walletApiUrl: String,
    val indexerGatewayUrl: String,
    val solanaIndexerGatewayUrl: String = "${walletApiUrl.trimEnd('/')}/v1/SolanaIndexerGateway/",
) {
    internal fun walletApiBaseUrl(): String {
        val uri = URI(walletApiUrl)
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OMSWalletEnvironment) return false

        return walletApiBaseUrl() == other.walletApiBaseUrl() &&
            indexerGatewayUrl == other.indexerGatewayUrl &&
            solanaIndexerGatewayUrl == other.solanaIndexerGatewayUrl
    }

    override fun hashCode(): Int {
        var result = walletApiBaseUrl().hashCode()
        result = 31 * result + indexerGatewayUrl.hashCode()
        result = 31 * result + solanaIndexerGatewayUrl.hashCode()
        return result
    }

    override fun toString(): String =
        "OMSWalletEnvironment(walletApiUrl=$walletApiUrl, indexerGatewayUrl=$indexerGatewayUrl, " +
            "solanaIndexerGatewayUrl=$solanaIndexerGatewayUrl)"

    companion object {
        internal const val accessKeyHeaderName: String = "Api-Key"
        internal const val walletSignatureHeaderName: String = "OMS-Wallet-Signature"
        internal const val walletSignatureHeaderPrefix: String = "$walletSignatureHeaderName: "

        fun fromPublishableKey(publishableKey: String): OMSWalletEnvironment {
            val parsed = parsePublishableKey(publishableKey)
            return OMSWalletEnvironment(
                walletApiUrl = parsed.walletApiUrl,
                indexerGatewayUrl = parsed.indexerGatewayUrl,
                solanaIndexerGatewayUrl = parsed.solanaIndexerGatewayUrl,
            )
        }
    }
}
