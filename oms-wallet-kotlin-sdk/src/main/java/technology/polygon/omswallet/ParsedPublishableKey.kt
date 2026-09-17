package technology.polygon.omswallet

/**
 * Service routing derived from an OMS publishable key.
 */
internal data class ParsedPublishableKey(
    val projectId: String,
    val walletApiUrl: String,
    val indexerGatewayUrl: String,
    val solanaIndexerGatewayUrl: String = "${walletApiUrl.trimEnd('/')}/v1/SolanaIndexerGateway/",
    val walletImportTrustedPcr0s: Set<String>,
)

/**
 * Parses an OMS publishable key into the project id and service URLs it routes to.
 *
 * @throws OMSWalletValidationException when [publishableKey] does not match a supported OMS key shape.
 */
internal fun parsePublishableKey(publishableKey: String): ParsedPublishableKey {
    val route =
        publishableKeyRoutes.firstOrNull { route ->
            publishableKey.startsWith(route.prefix)
        } ?: throw invalidPublishableKey()
    val keyParts = publishableKey.removePrefix(route.prefix).split('_')
    if (keyParts.size != 2 || keyParts.any { it.isEmpty() }) {
        throw invalidPublishableKey()
    }
    return ParsedPublishableKey(
        projectId = "prj_${keyParts[0]}",
        walletApiUrl = route.apiUrl,
        indexerGatewayUrl = "${route.apiUrl}/v1/IndexerGateway/",
        solanaIndexerGatewayUrl = "${route.apiUrl}/v1/SolanaIndexerGateway/",
        walletImportTrustedPcr0s = route.walletImportTrustedPcr0s,
    )
}

private data class PublishableKeyRoute(
    val prefix: String,
    val apiUrl: String,
    val walletImportTrustedPcr0s: Set<String>,
)

// Measurements are pinned to the deployed WaaS builds. Production measurements are published in
// WaaS GitHub releases; Staging can advance between releases. During rotation, publish an SDK that
// trusts both measurements before deploying the replacement, then remove the retired measurement.
private val debugWalletImportPcr0s = setOf("0".repeat(96))
private val stagingWalletImportPcr0s =
    setOf("e271fe4b26c9d58d6089b908ab713f888e6107e2cb4782ddaceea950bbec9971ccd9159e7a099bd506e04ce55c3da696")
private val productionWalletImportPcr0s =
    setOf("1935cbc713f0b43060315689e87285f6ba76bcf06f26d0719735e8d674b71e0eff71dcf77fe90ab32870ef3c954973b7")

private val publishableKeyRoutes =
    listOf(
        PublishableKeyRoute("pk_dev_sdbx_", "https://sandbox-api.dev.polygon-dev.technology", debugWalletImportPcr0s),
        PublishableKeyRoute("pk_dev_live_", "https://api.dev.polygon-dev.technology", debugWalletImportPcr0s),
        PublishableKeyRoute("pk_stg_sdbx_", "https://sandbox-api.stg.polygon-dev.technology", stagingWalletImportPcr0s),
        PublishableKeyRoute("pk_stg_live_", "https://api.stg.polygon-dev.technology", stagingWalletImportPcr0s),
        PublishableKeyRoute("pk_sdbx_", "https://sandbox-api.polygon.technology", productionWalletImportPcr0s),
        PublishableKeyRoute("pk_live_", "https://api.polygon.technology", productionWalletImportPcr0s),
    )

private fun invalidPublishableKey(): OMSWalletValidationException = OMSWalletValidationException(message = "Invalid publishableKey.")
