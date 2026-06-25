package com.omsclient.kotlin_sdk

/**
 * Service routing derived from an OMS publishable key.
 */
internal data class ParsedPublishableKey(
    val projectId: String,
    val walletApiUrl: String,
    val indexerGatewayUrl: String,
)

/**
 * Parses an OMS publishable key into the project id and service URLs it routes to.
 *
 * @throws OmsValidationException when [publishableKey] does not match a supported OMS key shape.
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
    )
}

private data class PublishableKeyRoute(
    val prefix: String,
    val apiUrl: String,
)

private val publishableKeyRoutes =
    listOf(
        PublishableKeyRoute("pk_dev_sdbx_", "https://sandbox-api.dev.polygon-dev.technology"),
        PublishableKeyRoute("pk_dev_live_", "https://api.dev.polygon-dev.technology"),
        PublishableKeyRoute("pk_stg_sdbx_", "https://sandbox-api.stg.polygon-dev.technology"),
        PublishableKeyRoute("pk_stg_live_", "https://api.stg.polygon-dev.technology"),
        PublishableKeyRoute("pk_sdbx_", "https://sandbox-api.polygon.technology"),
        PublishableKeyRoute("pk_live_", "https://api.polygon.technology"),
    )

private fun invalidPublishableKey(): OmsValidationException = OmsValidationException(message = "Invalid publishableKey.")
