package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.generated.waas.SigningAlgorithm
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.session.OMSClientSession
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.storage.OMSClientSecureSessionStore
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.PendingOidcRedirectAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class OMSClientTest {
    @Test
    fun constructorRestoresPersistedSessionAutomatically() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-main",
                walletAddress = "0xwallet",
                signerAddress = "0xsigner",
                expiresAt = "2026-01-01T00:00:00Z",
                loginType = OMSClientSessionLoginType.Email,
                sessionEmail = "user@example.com",
            )
        val sdk =
            OMSClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                walletSession = OMSClientSession(),
                sessionStore = StubSecureSessionStore(snapshot),
            )

        assertEquals("0xwallet", sdk.wallet.address)
        assertEquals("0xwallet", sdk.session.walletAddress)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), sdk.session.expiresAt)
        assertEquals(OMSClientSessionLoginType.Email, sdk.session.loginType)
        assertEquals("user@example.com", sdk.session.sessionEmail)
    }

    @Test
    fun sessionStateOnlyReflectsCompletedWalletSession() {
        val sdk =
            OMSClient(
                publicApiKey = "test-access-key",
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
                            walletType = WalletType.Ethereum,
                            signerAddress = "0xsigner",
                            signerKeyType = SigningAlgorithm.ECDSA_P256K_EIP191,
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
            MutableSecureSessionStore(
                OMSClientSessionSnapshot(
                    walletId = "wallet-main",
                    walletAddress = "0xwallet",
                    signerAddress = "0xsigner",
                ),
            )
        val sdk =
            OMSClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                walletSession = OMSClientSession(),
                sessionStore = store,
            )

        sdk.signOut()

        assertNull(sdk.wallet.address)
        assertNull(sdk.session.walletAddress)
        assertNull(sdk.session.expiresAt)
        assertNull(sdk.session.loginType)
        assertNull(sdk.session.sessionEmail)
        assertNull(store.snapshot)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun exposesSupportedNetworks() {
        val sdk = OMSClient(publicApiKey = "test-access-key", projectId = "test-project-id")

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
    fun scopedSessionStorageDiffersAcrossConfigs() {
        val defaultEnvironment = OMSClientEnvironment()
        val demoEnvironment = OMSClientEnvironment.demoDefaults()
        val projectId = "test-project-id"
        val otherProjectId = "other-project-id"
        val differentWalletEnvironment =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet-2.example.com/rpc/Wallet",
                apiRpcUrl = defaultEnvironment.apiRpcUrl,
                indexerUrlTemplate = defaultEnvironment.indexerUrlTemplate,
            )

        assertNotEquals(
            OMSClient.scopedSessionKeyAlias(projectId, defaultEnvironment),
            OMSClient.scopedSessionKeyAlias(projectId, differentWalletEnvironment),
        )
        assertNotEquals(
            OMSClient.scopedSessionFileName(projectId, defaultEnvironment),
            OMSClient.scopedSessionFileName(projectId, differentWalletEnvironment),
        )
        assertNotEquals(
            OMSClient.scopedSessionKeyAlias(projectId, defaultEnvironment),
            OMSClient.scopedSessionKeyAlias(otherProjectId, defaultEnvironment),
        )
        assertEquals(
            OMSClient.scopedSessionKeyAlias(projectId, defaultEnvironment),
            OMSClient.scopedSessionKeyAlias(projectId, demoEnvironment),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(projectId, defaultEnvironment),
            OMSClient.scopedSessionFileName(projectId, demoEnvironment),
        )
    }

    @Test
    fun scopedSessionStorageTreatsEquivalentWalletOriginsAsSameScope() {
        val withoutTrailingSlash =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/rpc/Wallet",
            )
        val withTrailingSlash =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/rpc/Wallet/",
            )
        val withDifferentPath =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/custom/wallet",
            )
        val withQuery =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet.example.com/rpc/Wallet?foo=bar",
            )
        val projectId = "test-project-id"

        assertEquals(
            OMSClient.scopedSessionKeyAlias(projectId, withoutTrailingSlash),
            OMSClient.scopedSessionKeyAlias(projectId, withTrailingSlash),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(projectId, withoutTrailingSlash),
            OMSClient.scopedSessionFileName(projectId, withTrailingSlash),
        )
        assertEquals(
            OMSClient.scopedSessionKeyAlias(projectId, withoutTrailingSlash),
            OMSClient.scopedSessionKeyAlias(projectId, withDifferentPath),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(projectId, withoutTrailingSlash),
            OMSClient.scopedSessionFileName(projectId, withDifferentPath),
        )
        assertEquals(
            OMSClient.scopedSessionKeyAlias(projectId, withoutTrailingSlash),
            OMSClient.scopedSessionKeyAlias(projectId, withQuery),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(projectId, withoutTrailingSlash),
            OMSClient.scopedSessionFileName(projectId, withQuery),
        )
    }

    private class StubSecureSessionStore(
        private val snapshot: OMSClientSessionSnapshot?,
    ) : OMSClientSecureSessionStore {
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

    private class MutableSecureSessionStore(
        var snapshot: OMSClientSessionSnapshot?,
    ) : OMSClientSecureSessionStore {
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
