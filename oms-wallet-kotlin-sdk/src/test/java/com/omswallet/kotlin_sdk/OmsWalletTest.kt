package com.omswallet.kotlin_sdk

import com.omswallet.kotlin_sdk.network.OmsWalletEnvironment
import com.omswallet.kotlin_sdk.session.OmsWalletSessionSnapshot
import com.omswallet.kotlin_sdk.session.OmsWalletSession
import com.omswallet.kotlin_sdk.storage.OmsWalletSecureSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OmsWalletTest {
    @Test
    fun constructorRestoresPersistedSessionAutomatically() {
        val snapshot = OmsWalletSessionSnapshot(
            walletId = "wallet-main",
            walletAddress = "0xwallet",
            signerAddress = "0xsigner",
        )
        val sdk = OmsWallet(
            projectAccessKey = "test-access-key",
            walletSession = OmsWalletSession(),
            sessionStore = StubSecureSessionStore(snapshot),
        )

        assertEquals("0xwallet", sdk.walletAddress)
        assertEquals("0xsigner", sdk.signerAddress)
    }

    @Test
    fun scopedSessionStorageDiffersAcrossConfigs() {
        val defaultEnvironment = OmsWalletEnvironment()
        val demoEnvironment = OmsWalletEnvironment.demoDefaults()
        val differentWalletEnvironment = OmsWalletEnvironment(
            walletApiUrl = "https://wallet-2.example.com/rpc/Wallet",
            apiRpcUrl = defaultEnvironment.apiRpcUrl,
            indexerUrlTemplate = defaultEnvironment.indexerUrlTemplate,
        )

        assertNotEquals(
            OmsWallet.scopedSessionKeyAlias(defaultEnvironment),
            OmsWallet.scopedSessionKeyAlias(differentWalletEnvironment),
        )
        assertNotEquals(
            OmsWallet.scopedSessionFileName(defaultEnvironment),
            OmsWallet.scopedSessionFileName(differentWalletEnvironment),
        )
        assertEquals(
            OmsWallet.scopedSessionKeyAlias(defaultEnvironment),
            OmsWallet.scopedSessionKeyAlias(demoEnvironment),
        )
        assertEquals(
            OmsWallet.scopedSessionFileName(defaultEnvironment),
            OmsWallet.scopedSessionFileName(demoEnvironment),
        )
    }

    @Test
    fun scopedSessionStorageTreatsEquivalentWalletOriginsAsSameScope() {
        val withoutTrailingSlash = OmsWalletEnvironment(
            walletApiUrl = "https://wallet.example.com/rpc/Wallet",
        )
        val withTrailingSlash = OmsWalletEnvironment(
            walletApiUrl = "https://wallet.example.com/rpc/Wallet/",
        )
        val withDifferentPath = OmsWalletEnvironment(
            walletApiUrl = "https://wallet.example.com/custom/wallet",
        )
        val withQuery = OmsWalletEnvironment(
            walletApiUrl = "https://wallet.example.com/rpc/Wallet?foo=bar",
        )

        assertEquals(
            OmsWallet.scopedSessionKeyAlias(withoutTrailingSlash),
            OmsWallet.scopedSessionKeyAlias(withTrailingSlash),
        )
        assertEquals(
            OmsWallet.scopedSessionFileName(withoutTrailingSlash),
            OmsWallet.scopedSessionFileName(withTrailingSlash),
        )
        assertEquals(
            OmsWallet.scopedSessionKeyAlias(withoutTrailingSlash),
            OmsWallet.scopedSessionKeyAlias(withDifferentPath),
        )
        assertEquals(
            OmsWallet.scopedSessionFileName(withoutTrailingSlash),
            OmsWallet.scopedSessionFileName(withDifferentPath),
        )
        assertEquals(
            OmsWallet.scopedSessionKeyAlias(withoutTrailingSlash),
            OmsWallet.scopedSessionKeyAlias(withQuery),
        )
        assertEquals(
            OmsWallet.scopedSessionFileName(withoutTrailingSlash),
            OmsWallet.scopedSessionFileName(withQuery),
        )
    }

    private class StubSecureSessionStore(
        private val snapshot: OmsWalletSessionSnapshot?,
    ) : OmsWalletSecureSessionStore {
        override fun load(): OmsWalletSessionSnapshot? = snapshot

        override fun save(snapshot: OmsWalletSessionSnapshot, privateKey: ByteArray?) = Unit

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
            error("Not needed for this test")

        override fun clear() = Unit
    }
}
