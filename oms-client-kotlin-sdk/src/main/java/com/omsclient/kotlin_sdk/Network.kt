package com.omsclient.kotlin_sdk

/**
 * A network supported by the OMS Client SDK.
 *
 * Use values from [OMSClient.supportedNetworks] when calling wallet, utility,
 * or indexer APIs that need a network.
 */
data class Network(
    val id: Int,
    val name: String,
    val nativeTokenSymbol: String,
    val explorerUrl: String,
) {
    val chainId: String
        get() = id.toString()

    val displayName: String
        get() = name

    override fun toString(): String = name

    companion object {
        val MAINNET: Network =
            Network(
                id = 1,
                name = "mainnet",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://etherscan.io",
            )

        val SEPOLIA: Network =
            Network(
                id = 11_155_111,
                name = "sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia.etherscan.io",
            )

        val POLYGON: Network =
            Network(
                id = 137,
                name = "polygon",
                nativeTokenSymbol = "POL",
                explorerUrl = "https://polygonscan.com",
            )

        val AMOY: Network =
            Network(
                id = 80_002,
                name = "amoy",
                nativeTokenSymbol = "POL",
                explorerUrl = "https://amoy.polygonscan.com",
            )

        val ARBITRUM: Network =
            Network(
                id = 42_161,
                name = "arbitrum",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://arbiscan.io",
            )

        val ARBITRUM_SEPOLIA: Network =
            Network(
                id = 421_614,
                name = "arbitrum-sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia.arbiscan.io",
            )

        val OPTIMISM: Network =
            Network(
                id = 10,
                name = "optimism",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://optimistic.etherscan.io",
            )

        val OPTIMISM_SEPOLIA: Network =
            Network(
                id = 11_155_420,
                name = "optimism-sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia-optimism.etherscan.io",
            )

        val BASE: Network =
            Network(
                id = 8_453,
                name = "base",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://basescan.org",
            )

        val BASE_SEPOLIA: Network =
            Network(
                id = 84_532,
                name = "base-sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia.basescan.org",
            )

        val BSC: Network =
            Network(
                id = 56,
                name = "bsc",
                nativeTokenSymbol = "BNB",
                explorerUrl = "https://bscscan.com",
            )

        val BSC_TESTNET: Network =
            Network(
                id = 97,
                name = "bsc-testnet",
                nativeTokenSymbol = "BNB",
                explorerUrl = "https://testnet.bscscan.com",
            )

        val ARBITRUM_NOVA: Network =
            Network(
                id = 42_170,
                name = "arbitrum-nova",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://nova.arbiscan.io",
            )

        val AVALANCHE: Network =
            Network(
                id = 43_114,
                name = "avalanche",
                nativeTokenSymbol = "AVAX",
                explorerUrl = "https://subnets.avax.network/c-chain",
            )

        val AVALANCHE_TESTNET: Network =
            Network(
                id = 43_113,
                name = "avalanche-testnet",
                nativeTokenSymbol = "AVAX",
                explorerUrl = "https://subnets-test.avax.network/c-chain",
            )

        val KATANA: Network =
            Network(
                id = 747_474,
                name = "katana",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://katanascan.com",
            )

        /**
         * Backward-compatible alias for the former enum entry name.
         */
        val POLYGON_AMOY: Network = AMOY

        val entries: List<Network> =
            listOf(
                MAINNET,
                SEPOLIA,
                POLYGON,
                AMOY,
                ARBITRUM,
                ARBITRUM_SEPOLIA,
                OPTIMISM,
                OPTIMISM_SEPOLIA,
                BASE,
                BASE_SEPOLIA,
                BSC,
                BSC_TESTNET,
                ARBITRUM_NOVA,
                AVALANCHE,
                AVALANCHE_TESTNET,
                KATANA,
            )
    }
}

internal object OMSClientNetworks {
    val supportedNetworks: List<Network> = Network.entries

    fun requireSupported(chainId: String): Network =
        supportedNetworks.firstOrNull { it.chainId == chainId }
            ?: error("Unsupported chain id: $chainId")

    fun findById(id: Int): Network? = supportedNetworks.firstOrNull { it.id == id }

    fun findByName(name: String): Network? = supportedNetworks.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

/**
 * Networks currently supported by this SDK build.
 */
val supportedNetworks: List<Network>
    get() = OMSClientNetworks.supportedNetworks

/**
 * Returns a supported network by numeric chain id.
 */
fun findNetworkById(chainId: Int): Network? = OMSClientNetworks.findById(chainId)

/**
 * Returns a supported network by registry name.
 */
fun findNetworkByName(name: String): Network? = OMSClientNetworks.findByName(name)
