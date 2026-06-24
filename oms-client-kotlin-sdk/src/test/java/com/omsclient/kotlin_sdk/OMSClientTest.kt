package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.session.OMSClientSession
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.storage.OMSClientSessionMetadataStore
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.PendingOidcRedirectAuth
import com.omsclient.kotlin_sdk.wallet.TrackingCredentialSigner
import com.omsclient.kotlin_sdk.wallet.WalletSigningAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OMSClientTest {
    @Test
    fun parsePublishableKeyDerivesProjectAndServiceUrls() {
        val cases =
            listOf(
                "pk_dev_sdbx_project_key" to "https://sandbox-api.dev.polygon-dev.technology",
                "pk_dev_live_project_key" to "https://api.dev.polygon-dev.technology",
                "pk_stg_sdbx_project_key" to "https://sandbox-api.stg.polygon-dev.technology",
                "pk_stg_live_project_key" to "https://api.stg.polygon-dev.technology",
                "pk_sdbx_project_key" to "https://sandbox-api.polygon.technology",
                "pk_live_project_key" to "https://api.polygon.technology",
            )

        cases.forEach { (publishableKey, apiUrl) ->
            assertEquals(
                ParsedPublishableKey(
                    projectId = "prj_project",
                    walletApiUrl = apiUrl,
                    indexerGatewayUrl = "$apiUrl/v1/IndexerGateway/",
                ),
                parsePublishableKey(publishableKey),
            )
        }
    }

    @Test
    fun constructorDerivesProjectIdFromPublishableKey() {
        val sdk = OMSClient(publishableKey = "pk_live_project_key")

        assertEquals(supportedNetworks, sdk.supportedNetworks)
    }

    @Test
    fun constructorRejectsUnsupportedPublishableKeyPrefix() {
        val error =
            runCatching {
                OMSClient(publishableKey = "pk_test_sdbx_project_key")
            }.exceptionOrNull()

        assertTrue(error is OmsValidationException)
        assertEquals("Invalid publishableKey.", error?.message)
    }

    @Test
    fun constructorRestoresPersistedSessionAutomatically() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-main",
                walletAddress = "0xwallet",
                signerAddress = "0xsigner",
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2099-01-01T00:00:00Z",
                loginType = OMSClientSessionLoginType.Email,
                sessionEmail = "user@example.com",
            )
        val sdk =
            OMSClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                walletSession = OMSClientSession(),
                sessionStore = StubSessionMetadataStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
            )

        assertEquals("0xwallet", sdk.wallet.walletAddress)
        assertEquals("0xwallet", sdk.session.walletAddress)
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), sdk.session.expiresAt)
        assertEquals(OMSClientSessionLoginType.Email, sdk.session.loginType)
        assertEquals("user@example.com", sdk.session.sessionEmail)
    }

    @Test
    fun sessionStateOnlyReflectsCompletedWalletSession() {
        val sdk =
            OMSClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                walletSession = OMSClientSession(),
                oidcRedirectAuthStore =
                    StubOidcRedirectAuthStore(
                        PendingOidcRedirectAuth(
                            verifier = "verifier-123",
                            challenge = "challenge-123",
                            nonce = "nonce-123",
                            redirectUri = "omsclientkotlindemo://auth/callback",
                            issuer = "https://issuer.example",
                            projectId = "test-project-id",
                            walletType = "ethereum",
                            signerAddress = "0xsigner",
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                        ),
                    ),
            )

        assertNull(sdk.session.walletAddress)
        assertNull(sdk.session.expiresAt)
        assertNull(sdk.session.loginType)
        assertNull(sdk.session.sessionEmail)
    }

    @Test
    fun signOutClearsWalletSessionAndStore() {
        val store =
            MutableSessionMetadataStore(
                OMSClientSessionSnapshot(
                    walletId = "wallet-main",
                    walletAddress = "0xwallet",
                    signerAddress = "0xsigner",
                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                ),
            )
        val sdk =
            OMSClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                walletSession = OMSClientSession(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )

        sdk.wallet.signOut()

        assertNull(sdk.wallet.walletAddress)
        assertNull(sdk.session.walletAddress)
        assertNull(sdk.session.expiresAt)
        assertNull(sdk.session.loginType)
        assertNull(sdk.session.sessionEmail)
        assertNull(store.snapshot)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun exposesSupportedNetworks() {
        val sdk = OMSClient(publishableKey = "test-publishable-key", projectId = "test-project-id")

        assertEquals(supportedNetworks, sdk.supportedNetworks)
        assertEquals(16, sdk.supportedNetworks.size)
        assertEquals(Network.MAINNET, findNetworkById(1))
        assertEquals(Network.MAINNET, findNetworkByName("mainnet"))
        assertEquals(Network.POLYGON, findNetworkById(137))
        assertEquals(Network.AMOY, findNetworkById(80_002))
        assertEquals(Network.BASE, findNetworkByName("base"))
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
            sdk.supportedNetworks.map { it.displayName },
        )
        assertNull(findNetworkById(999_999))
    }

    @Test
    fun scopedAndroidStorageDiffersAcrossConfigs() {
        val defaultEnvironment = OMSClientEnvironment()
        val differentIndexerEnvironment =
            OMSClientEnvironment(
                indexerGatewayUrl = "https://indexer-2.example.com/v1/IndexerGateway/",
            )
        val projectId = "test-project-id"
        val otherProjectId = "other-project-id"
        val differentWalletEnvironment =
            OMSClientEnvironment(
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
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/v1/Waas",
            )
        val withTrailingSlash =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/v1/Waas/",
            )
        val withDifferentPath =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/custom/wallet",
            )
        val withQuery =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/v1/Waas?foo=bar",
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
        environment: OMSClientEnvironment,
    ): ScopedAndroidStorageIds =
        ScopedAndroidStorageIds(
            sessionFileName = OMSClient.scopedSessionFileName(projectId, environment),
            credentialKeyAlias = OMSClient.scopedCredentialKeyAlias(projectId, environment),
            credentialNonceStoreName = OMSClient.scopedCredentialNonceStoreName(projectId, environment),
            oidcRedirectAuthFileName = OMSClient.scopedOidcRedirectAuthFileName(projectId, environment),
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

    private data class ScopedAndroidStorageIds(
        val sessionFileName: String,
        val credentialKeyAlias: String,
        val credentialNonceStoreName: String,
        val oidcRedirectAuthFileName: String,
    )

    private class StubSessionMetadataStore(
        private val snapshot: OMSClientSessionSnapshot?,
    ) : OMSClientSessionMetadataStore {
        override fun load(): OMSClientSessionSnapshot? = snapshot

        override fun save(snapshot: OMSClientSessionSnapshot) = Unit

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
        var snapshot: OMSClientSessionSnapshot?,
    ) : OMSClientSessionMetadataStore {
        var clearCalls = 0

        override fun load(): OMSClientSessionSnapshot? = snapshot

        override fun save(snapshot: OMSClientSessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun clear() {
            clearCalls += 1
            snapshot = null
        }
    }
}
