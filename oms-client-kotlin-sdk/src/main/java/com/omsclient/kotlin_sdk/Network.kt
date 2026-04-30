package com.omsclient.kotlin_sdk

/**
 * A network supported by the OMS Client SDK.
 *
 * Use values from [OMSClient.supportedNetworks] when calling wallet, utility,
 * or indexer APIs that need a network.
 */
enum class Network(
    val chainId: String,
    val displayName: String,
    internal val indexerName: String,
) {
    POLYGON(
        chainId = "137",
        displayName = "Polygon",
        indexerName = "polygon",
    ),

    POLYGON_AMOY(
        chainId = "80002",
        displayName = "Polygon Amoy",
        indexerName = "amoy",
    );

    override fun toString(): String = displayName
}

internal object OMSClientNetworks {
    val supportedNetworks: List<Network> = Network.entries.toList()

    fun requireSupported(chainId: String): Network =
        supportedNetworks.firstOrNull { it.chainId == chainId }
            ?: error("Unsupported chain id: $chainId")
}
