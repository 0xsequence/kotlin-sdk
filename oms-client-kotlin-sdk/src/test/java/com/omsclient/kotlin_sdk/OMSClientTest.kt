package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.generated.waas.KeyType
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
                projectAccessKey = "test-access-key",
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
                projectAccessKey = "test-access-key",
                walletSession = OMSClientSession(),
                oidcRedirectAuthStore =
                    StubOidcRedirectAuthStore(
                        PendingOidcRedirectAuth(
                            verifier = "verifier-123",
                            challenge = "challenge-123",
                            nonce = "nonce-123",
                            redirectUri = "omsclientkotlindemo://auth/callback",
                            issuer = "https://issuer.example",
                            authorizationScope = "proj_1",
                            walletType = WalletType.Ethereum,
                            signerAddress = "0xsigner",
                            signerKeyType = KeyType.Ethereum_Secp256k1,
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
                projectAccessKey = "test-access-key",
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
        val sdk = OMSClient(projectAccessKey = "test-access-key")

        assertEquals(listOf("137", "80002"), sdk.supportedNetworks.map { it.chainId })
        assertEquals("Polygon Amoy", sdk.network("80002")?.displayName)
        assertNull(sdk.network("1"))
    }

    @Test
    fun scopedSessionStorageDiffersAcrossConfigs() {
        val defaultEnvironment = OMSClientEnvironment()
        val demoEnvironment = OMSClientEnvironment.demoDefaults()
        val differentWalletEnvironment =
            OMSClientEnvironment(
                walletApiUrl = "https://wallet-2.example.com/rpc/Wallet",
                apiRpcUrl = defaultEnvironment.apiRpcUrl,
                indexerUrlTemplate = defaultEnvironment.indexerUrlTemplate,
            )

        assertNotEquals(
            OMSClient.scopedSessionKeyAlias(defaultEnvironment),
            OMSClient.scopedSessionKeyAlias(differentWalletEnvironment),
        )
        assertNotEquals(
            OMSClient.scopedSessionFileName(defaultEnvironment),
            OMSClient.scopedSessionFileName(differentWalletEnvironment),
        )
        assertEquals(
            OMSClient.scopedSessionKeyAlias(defaultEnvironment),
            OMSClient.scopedSessionKeyAlias(demoEnvironment),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(defaultEnvironment),
            OMSClient.scopedSessionFileName(demoEnvironment),
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

        assertEquals(
            OMSClient.scopedSessionKeyAlias(withoutTrailingSlash),
            OMSClient.scopedSessionKeyAlias(withTrailingSlash),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(withoutTrailingSlash),
            OMSClient.scopedSessionFileName(withTrailingSlash),
        )
        assertEquals(
            OMSClient.scopedSessionKeyAlias(withoutTrailingSlash),
            OMSClient.scopedSessionKeyAlias(withDifferentPath),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(withoutTrailingSlash),
            OMSClient.scopedSessionFileName(withDifferentPath),
        )
        assertEquals(
            OMSClient.scopedSessionKeyAlias(withoutTrailingSlash),
            OMSClient.scopedSessionKeyAlias(withQuery),
        )
        assertEquals(
            OMSClient.scopedSessionFileName(withoutTrailingSlash),
            OMSClient.scopedSessionFileName(withQuery),
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
