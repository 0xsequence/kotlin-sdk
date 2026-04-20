package com.polygon_wallet.polygon_kotlin_sdk

import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceSessionSnapshot
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceWalletSession
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequenceSecureSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PolygonSdkTest {
    @Test
    fun constructorRestoresPersistedSessionAutomatically() {
        val snapshot = SequenceSessionSnapshot(
            walletAddress = "0xwallet",
            signerAddress = "0xsigner",
        )
        val sdk = PolygonSdk(
            projectAccessKey = "test-access-key",
            walletSession = SequenceWalletSession(),
            sessionStore = StubSecureSessionStore(snapshot),
        )

        assertEquals("0xwallet", sdk.wallet.walletAddress)
        assertEquals("0xsigner", sdk.wallet.signerAddress)
    }

    @Test
    fun scopedSessionStorageDiffersAcrossConfigs() {
        val defaultEnvironment = SequenceEnvironment()
        val demoEnvironment = SequenceEnvironment.demoDefaults()
        val differentWalletEnvironment = SequenceEnvironment(
            walletApiUrl = "https://wallet-2.example.com/rpc/Wallet",
            apiRpcUrl = defaultEnvironment.apiRpcUrl,
            indexerUrlTemplate = defaultEnvironment.indexerUrlTemplate,
        )

        assertNotEquals(
            PolygonSdk.scopedSessionKeyAlias(defaultEnvironment),
            PolygonSdk.scopedSessionKeyAlias(differentWalletEnvironment),
        )
        assertNotEquals(
            PolygonSdk.scopedSessionFileName(defaultEnvironment),
            PolygonSdk.scopedSessionFileName(differentWalletEnvironment),
        )
        assertEquals(
            PolygonSdk.scopedSessionKeyAlias(defaultEnvironment),
            PolygonSdk.scopedSessionKeyAlias(demoEnvironment),
        )
        assertEquals(
            PolygonSdk.scopedSessionFileName(defaultEnvironment),
            PolygonSdk.scopedSessionFileName(demoEnvironment),
        )
    }

    @Test
    fun scopedSessionStorageTreatsEquivalentWalletOriginsAsSameScope() {
        val withoutTrailingSlash = SequenceEnvironment(
            walletApiUrl = "https://wallet.example.com/rpc/Wallet",
        )
        val withTrailingSlash = SequenceEnvironment(
            walletApiUrl = "https://wallet.example.com/rpc/Wallet/",
        )
        val withDifferentPath = SequenceEnvironment(
            walletApiUrl = "https://wallet.example.com/custom/wallet",
        )
        val withQuery = SequenceEnvironment(
            walletApiUrl = "https://wallet.example.com/rpc/Wallet?foo=bar",
        )

        assertEquals(
            PolygonSdk.scopedSessionKeyAlias(withoutTrailingSlash),
            PolygonSdk.scopedSessionKeyAlias(withTrailingSlash),
        )
        assertEquals(
            PolygonSdk.scopedSessionFileName(withoutTrailingSlash),
            PolygonSdk.scopedSessionFileName(withTrailingSlash),
        )
        assertEquals(
            PolygonSdk.scopedSessionKeyAlias(withoutTrailingSlash),
            PolygonSdk.scopedSessionKeyAlias(withDifferentPath),
        )
        assertEquals(
            PolygonSdk.scopedSessionFileName(withoutTrailingSlash),
            PolygonSdk.scopedSessionFileName(withDifferentPath),
        )
        assertEquals(
            PolygonSdk.scopedSessionKeyAlias(withoutTrailingSlash),
            PolygonSdk.scopedSessionKeyAlias(withQuery),
        )
        assertEquals(
            PolygonSdk.scopedSessionFileName(withoutTrailingSlash),
            PolygonSdk.scopedSessionFileName(withQuery),
        )
    }

    private class StubSecureSessionStore(
        private val snapshot: SequenceSessionSnapshot?,
    ) : SequenceSecureSessionStore {
        override fun load(): SequenceSessionSnapshot? = snapshot

        override fun save(snapshot: SequenceSessionSnapshot, privateKey: ByteArray?) = Unit

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
            error("Not needed for this test")

        override fun clear() = Unit
    }
}
