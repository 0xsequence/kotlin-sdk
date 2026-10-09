package technology.polygon.omswallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.session.OMSWalletSessionStateMachine
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.wallet.OidcAuthMode
import technology.polygon.omswallet.wallet.OidcRedirectAuthStore
import technology.polygon.omswallet.wallet.PendingOidcRedirectAuth
import technology.polygon.omswallet.wallet.TEST_CREDENTIAL_ID
import technology.polygon.omswallet.wallet.TEST_SESSION_EXPIRES_AT
import technology.polygon.omswallet.wallet.TrackingCredentialSigner
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm
import technology.polygon.omswallet.wallet.testWallet

class OMSWalletTest {
    @Test
    fun parsePublishableKeyDerivesProjectAndServiceUrls() {
        val cases =
            listOf(
                Triple("pk_dev_sdbx_project_key", "https://sandbox-api.dev.polygon-dev.technology", setOf("0".repeat(96))),
                Triple("pk_dev_live_project_key", "https://api.dev.polygon-dev.technology", setOf("0".repeat(96))),
                Triple(
                    "pk_stg_sdbx_project_key",
                    "https://sandbox-api.stg.polygon-dev.technology",
                    setOf("3d21c70519a0ea3d5e6af43c5323234d90755d1ca08431064bd9687ddde4a4788a0a4736701513eee6008f1ec17e0d23"),
                ),
                Triple(
                    "pk_stg_live_project_key",
                    "https://api.stg.polygon-dev.technology",
                    setOf("3d21c70519a0ea3d5e6af43c5323234d90755d1ca08431064bd9687ddde4a4788a0a4736701513eee6008f1ec17e0d23"),
                ),
                Triple(
                    "pk_sdbx_project_key",
                    "https://sandbox-api.polygon.technology",
                    setOf(
                        "1935cbc713f0b43060315689e87285f6ba76bcf06f26d0719735e8d674b71e0eff71dcf77fe90ab32870ef3c954973b7",
                        "66d0d20073ec8549b6eb1cd3cd53311495225ec79d68f168ab734b24a69a8ed0f890f85ff31d5f0a79486a4e3a303b3c",
                    ),
                ),
                Triple(
                    "pk_live_project_key",
                    "https://api.polygon.technology",
                    setOf(
                        "1935cbc713f0b43060315689e87285f6ba76bcf06f26d0719735e8d674b71e0eff71dcf77fe90ab32870ef3c954973b7",
                        "66d0d20073ec8549b6eb1cd3cd53311495225ec79d68f168ab734b24a69a8ed0f890f85ff31d5f0a79486a4e3a303b3c",
                    ),
                ),
            )

        cases.forEach { (publishableKey, apiUrl, walletImportPcr0s) ->
            assertEquals(
                ParsedPublishableKey(
                    projectId = "prj_project",
                    walletApiUrl = apiUrl,
                    indexerGatewayUrl = "$apiUrl/v1/IndexerGateway/",
                    solanaIndexerGatewayUrl = "$apiUrl/v1/SolanaIndexerGateway/",
                    tronIndexerGatewayUrl = "$apiUrl/v1/TronIndexerGateway/",
                    walletImportTrustedPcr0s = walletImportPcr0s,
                ),
                parsePublishableKey(publishableKey),
            )
        }
    }

    @Test
    fun constructorDerivesProjectIdFromPublishableKey() {
        val sdk = OMSWallet.createForTesting(publishableKey = "pk_live_project_key")

        assertNull(sdk.wallet.activeWallet)
    }

    @Test
    fun constructorRejectsUnsupportedPublishableKeyPrefix() {
        val error =
            runCatching {
                OMSWallet.createForTesting(publishableKey = "pk_test_sdbx_project_key")
            }.exceptionOrNull()

        assertTrue(error is OMSWalletValidationException)
        assertEquals("Invalid publishableKey.", error?.message)
    }

    @Test
    fun constructorRestoresPersistedSessionAutomatically() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-main", "0xwallet"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2099-01-01T00:00:00Z",
                auth = OMSWalletEmailSessionAuth(email = "user@example.com"),
            )
        val sdk =
            OMSWallet.createForTesting(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                walletSession = OMSWalletSessionStateMachine(),
                sessionStore = StubSessionMetadataStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
            )

        assertEquals("0xwallet", sdk.wallet.activeWallet?.address)
        assertEquals("0xwallet", sdk.wallet.activeWallet?.address)
        assertEquals("2099-01-01T00:00:00Z", sdk.wallet.session?.expiresAt)
        assertEquals(OMSWalletEmailSessionAuth(email = "user@example.com"), sdk.wallet.session?.auth)
    }

    @Test
    fun sessionStateOnlyReflectsCompletedWalletSession() {
        val sdk =
            OMSWallet.createForTesting(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                walletSession = OMSWalletSessionStateMachine(),
                oidcRedirectAuthStore =
                    StubOidcRedirectAuthStore(
                        PendingOidcRedirectAuth(
                            verifier = "verifier-123",
                            challenge = "challenge-123",
                            nonce = "nonce-123",
                            authMode = OidcAuthMode.AuthCodePKCE,
                            redirectUri = "omsclientkotlindemo://auth/callback",
                            issuer = "https://issuer.example",
                            projectId = "test-project-id",
                            walletType = "ethereum",
                            walletSelection = null,
                            sessionLifetimeSeconds = null,
                            signerAddress = TEST_CREDENTIAL_ID,
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                        ),
                    ),
            )

        assertNull(sdk.wallet.activeWallet)
        assertNull(sdk.wallet.session)
    }

    @Test
    fun signOutClearsWalletSessionAndStore() {
        val store =
            MutableSessionMetadataStore(
                OMSWalletSessionSnapshot(
                    wallet = testWallet("wallet-main", "0xwallet"),
                    expiresAt = TEST_SESSION_EXPIRES_AT,
                    signerAddress = TEST_CREDENTIAL_ID,
                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                    auth = OMSWalletEmailSessionAuth(email = "user@example.com"),
                ),
            )
        val sdk =
            OMSWallet.createForTesting(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                walletSession = OMSWalletSessionStateMachine(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )

        sdk.wallet.signOut()

        assertNull(sdk.wallet.activeWallet)
        assertNull(sdk.wallet.activeWallet)
        assertNull(sdk.wallet.session)
        assertNull(store.snapshot)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun exposesSupportedNetworks() {
        assertEquals(Network.entries, OMSWalletNetworks.supportedNetworks)
        assertEquals(16, OMSWalletNetworks.supportedNetworks.size)
        assertEquals(Network.MAINNET, OMSWalletNetworks.findById(1))
        assertEquals(Network.MAINNET, OMSWalletNetworks.findByName("mainnet"))
        assertEquals(Network.POLYGON, OMSWalletNetworks.findById(137))
        assertEquals(Network.AMOY, OMSWalletNetworks.findById(80_002))
        assertEquals(Network.BASE, OMSWalletNetworks.findByName("base"))
        assertEquals("POL", Network.POLYGON.nativeTokenSymbol)
        assertEquals("https://amoy.polygonscan.com", Network.AMOY.explorerUrl)
        assertEquals(
            listOf(
                "Ethereum",
                "Sepolia",
                "Polygon",
                "Polygon Amoy",
                "Arbitrum",
                "Arbitrum Sepolia",
                "Optimism",
                "Optimism Sepolia",
                "Base",
                "Base Sepolia",
                "BSC",
                "BSC Testnet",
                "Arbitrum Nova",
                "Avalanche",
                "Avalanche Testnet",
                "Katana",
            ),
            OMSWalletNetworks.supportedNetworks.map { it.displayName },
        )
        assertNull(OMSWalletNetworks.findById(999_999))
    }

    @Test
    fun scopedAndroidStorageDiffersAcrossConfigs() {
        val defaultEnvironment = testEnvironment()
        val differentIndexerEnvironment =
            OMSWalletEnvironment(
                walletApiUrl = defaultEnvironment.walletApiUrl,
                indexerGatewayUrl = "https://indexer-2.example.com/v1/IndexerGateway/",
            )
        val projectId = "test-project-id"
        val otherProjectId = "other-project-id"
        val differentWalletEnvironment =
            OMSWalletEnvironment(
                walletApiUrl = "https://wallet-2.example.com/v1/Waas",
                indexerGatewayUrl = defaultEnvironment.indexerGatewayUrl,
            )

        assertScopedAndroidStorageIdsDiffer(
            scopedAndroidStorageIds(projectId, defaultEnvironment),
            scopedAndroidStorageIds(projectId, differentWalletEnvironment),
        )
        assertScopedAndroidStorageIdsDiffer(
            scopedAndroidStorageIds(projectId, defaultEnvironment),
            scopedAndroidStorageIds(otherProjectId, defaultEnvironment),
        )
        assertEquals(
            scopedAndroidStorageIds(projectId, defaultEnvironment),
            scopedAndroidStorageIds(projectId, differentIndexerEnvironment),
        )
    }

    @Test
    fun scopedAndroidStorageTreatsEquivalentWalletOriginsAsSameScope() {
        val withoutTrailingSlash =
            OMSWalletEnvironment(
                walletApiUrl = "https://wallet.example.com/v1/Waas",
                indexerGatewayUrl = "https://indexer.example.com/v1/IndexerGateway/",
            )
        val withTrailingSlash =
            OMSWalletEnvironment(
                walletApiUrl = "https://wallet.example.com/v1/Waas/",
                indexerGatewayUrl = "https://indexer.example.com/v1/IndexerGateway/",
            )
        val withDifferentPath =
            OMSWalletEnvironment(
                walletApiUrl = "https://wallet.example.com/custom/wallet",
                indexerGatewayUrl = "https://indexer.example.com/v1/IndexerGateway/",
            )
        val withQuery =
            OMSWalletEnvironment(
                walletApiUrl = "https://wallet.example.com/v1/Waas?foo=bar",
                indexerGatewayUrl = "https://indexer.example.com/v1/IndexerGateway/",
            )
        val projectId = "test-project-id"

        assertEquals(
            scopedAndroidStorageIds(projectId, withoutTrailingSlash),
            scopedAndroidStorageIds(projectId, withTrailingSlash),
        )
        assertEquals(
            scopedAndroidStorageIds(projectId, withoutTrailingSlash),
            scopedAndroidStorageIds(projectId, withDifferentPath),
        )
        assertEquals(
            scopedAndroidStorageIds(projectId, withoutTrailingSlash),
            scopedAndroidStorageIds(projectId, withQuery),
        )
    }

    private fun scopedAndroidStorageIds(
        projectId: String,
        environment: OMSWalletEnvironment,
    ): ScopedAndroidStorageIds =
        ScopedAndroidStorageIds(
            sessionFileName = OMSWallet.scopedSessionFileName(projectId, environment),
            credentialKeyAlias = OMSWallet.scopedCredentialKeyAlias(projectId, environment),
            credentialNonceStoreName = OMSWallet.scopedCredentialNonceStoreName(projectId, environment),
            oidcRedirectAuthFileName = OMSWallet.scopedOidcRedirectAuthFileName(projectId, environment),
        )

    private fun assertScopedAndroidStorageIdsDiffer(
        first: ScopedAndroidStorageIds,
        second: ScopedAndroidStorageIds,
    ) {
        assertNotEquals(first.sessionFileName, second.sessionFileName)
        assertNotEquals(first.credentialKeyAlias, second.credentialKeyAlias)
        assertNotEquals(first.credentialNonceStoreName, second.credentialNonceStoreName)
        assertNotEquals(first.oidcRedirectAuthFileName, second.oidcRedirectAuthFileName)
    }

    private fun testEnvironment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = "https://wallet.example.com/v1/Waas",
            indexerGatewayUrl = "https://indexer.example.com/v1/IndexerGateway/",
        )

    private data class ScopedAndroidStorageIds(
        val sessionFileName: String,
        val credentialKeyAlias: String,
        val credentialNonceStoreName: String,
        val oidcRedirectAuthFileName: String,
    )

    private class StubSessionMetadataStore(
        private val snapshot: OMSWalletSessionSnapshot?,
    ) : OMSWalletSessionMetadataStore {
        override fun load(): OMSWalletSessionSnapshot? = snapshot

        override fun save(snapshot: OMSWalletSessionSnapshot) = Unit

        override fun clear() = Unit
    }

    private class StubOidcRedirectAuthStore(
        private val pending: PendingOidcRedirectAuth?,
    ) : OidcRedirectAuthStore {
        override fun load(): PendingOidcRedirectAuth? = pending

        override fun save(pending: PendingOidcRedirectAuth) = Unit

        override fun clear() = Unit
    }

    private class MutableSessionMetadataStore(
        var snapshot: OMSWalletSessionSnapshot?,
    ) : OMSWalletSessionMetadataStore {
        var clearCalls = 0

        override fun load(): OMSWalletSessionSnapshot? = snapshot

        override fun save(snapshot: OMSWalletSessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun clear() {
            clearCalls += 1
            snapshot = null
        }
    }
}
