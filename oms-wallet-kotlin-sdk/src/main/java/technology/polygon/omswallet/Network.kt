package technology.polygon.omswallet

/**
 * A network supported by the OMS Wallet SDK.
 *
 * Use values from [OMSWalletNetworks.supportedNetworks] when calling wallet, utility,
 * or indexer APIs that need a network.
 */
data class Network(
    val id: Int,
    val name: String,
    val nativeTokenSymbol: String,
    val explorerUrl: String,
    val displayName: String = name,
) {
    override fun toString(): String = name

    companion object {
        val MAINNET: Network =
            Network(
                id = 1,
                name = "mainnet",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://etherscan.io",
                displayName = "Ethereum",
            )

        val SEPOLIA: Network =
            Network(
                id = 11_155_111,
                name = "sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia.etherscan.io",
                displayName = "Sepolia",
            )

        val POLYGON: Network =
            Network(
                id = 137,
                name = "polygon",
                nativeTokenSymbol = "POL",
                explorerUrl = "https://polygonscan.com",
                displayName = "Polygon",
            )

        val AMOY: Network =
            Network(
                id = 80_002,
                name = "amoy",
                nativeTokenSymbol = "POL",
                explorerUrl = "https://amoy.polygonscan.com",
                displayName = "Polygon Amoy",
            )

        val ARBITRUM: Network =
            Network(
                id = 42_161,
                name = "arbitrum",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://arbiscan.io",
                displayName = "Arbitrum",
            )

        val ARBITRUM_SEPOLIA: Network =
            Network(
                id = 421_614,
                name = "arbitrum-sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia.arbiscan.io",
                displayName = "Arbitrum Sepolia",
            )

        val OPTIMISM: Network =
            Network(
                id = 10,
                name = "optimism",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://optimistic.etherscan.io",
                displayName = "Optimism",
            )

        val OPTIMISM_SEPOLIA: Network =
            Network(
                id = 11_155_420,
                name = "optimism-sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia-optimism.etherscan.io",
                displayName = "Optimism Sepolia",
            )

        val BASE: Network =
            Network(
                id = 8_453,
                name = "base",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://basescan.org",
                displayName = "Base",
            )

        val BASE_SEPOLIA: Network =
            Network(
                id = 84_532,
                name = "base-sepolia",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://sepolia.basescan.org",
                displayName = "Base Sepolia",
            )

        val BSC: Network =
            Network(
                id = 56,
                name = "bsc",
                nativeTokenSymbol = "BNB",
                explorerUrl = "https://bscscan.com",
                displayName = "BSC",
            )

        val BSC_TESTNET: Network =
            Network(
                id = 97,
                name = "bsc-testnet",
                nativeTokenSymbol = "BNB",
                explorerUrl = "https://testnet.bscscan.com",
                displayName = "BSC Testnet",
            )

        val ARBITRUM_NOVA: Network =
            Network(
                id = 42_170,
                name = "arbitrum-nova",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://nova.arbiscan.io",
                displayName = "Arbitrum Nova",
            )

        val AVALANCHE: Network =
            Network(
                id = 43_114,
                name = "avalanche",
                nativeTokenSymbol = "AVAX",
                explorerUrl = "https://subnets.avax.network/c-chain",
                displayName = "Avalanche",
            )

        val AVALANCHE_TESTNET: Network =
            Network(
                id = 43_113,
                name = "avalanche-testnet",
                nativeTokenSymbol = "AVAX",
                explorerUrl = "https://subnets-test.avax.network/c-chain",
                displayName = "Avalanche Testnet",
            )

        val KATANA: Network =
            Network(
                id = 747_474,
                name = "katana",
                nativeTokenSymbol = "ETH",
                explorerUrl = "https://katanascan.com",
                displayName = "Katana",
            )

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

/**
 * Networks currently supported by this SDK build.
 */
object OMSWalletNetworks {
    /**
     * All networks currently supported by this SDK build.
     */
    val supportedNetworks: List<Network> = Network.entries

    /**
     * Returns a supported network by chain id.
     */
    fun findById(id: Int): Network? = supportedNetworks.firstOrNull { it.id == id }

    /**
     * Returns a supported network by registry name.
     */
    fun findByName(name: String): Network? = supportedNetworks.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}
