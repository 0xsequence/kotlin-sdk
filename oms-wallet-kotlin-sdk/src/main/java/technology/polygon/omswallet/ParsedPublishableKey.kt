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

// Staging and Production measurements come from the corresponding WaaS GitHub releases. During
// rotation, publish an SDK that trusts both the current and replacement measurements before the
// replacement enclave is deployed, then remove the retired measurement in a later SDK release.
private val debugWalletImportPcr0s = setOf("0".repeat(96))
private val stagingWalletImportPcr0s =
    setOf("e4da1f70f6e781d7196dff36d21e57bb5603ec4bcacefb7061493049292b76b620b0ad23b82e280d6130f67384051e9f")
private val productionWalletImportPcr0s =
    setOf("671f22183eed852f4051a50ee54b45153499501538cbd64a277b8ff22a012b37f1905ebfcf7a6be8ce00ec0c8db7bbd2")

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
