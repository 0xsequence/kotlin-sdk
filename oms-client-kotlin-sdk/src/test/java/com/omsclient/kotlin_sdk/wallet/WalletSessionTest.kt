package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSessionTest {
    @Test
    fun restorePersistedSessionLoadsFromStore() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            )
        val store = InMemorySessionStore(snapshot, FIXED_PRIVATE_KEY_HEX)
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                privateKeyFactory = ::fixedPrivateKeyBytes,
            )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals("0xabc", client.walletAddress)
        assertEquals(
            WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            client.signerAddress,
        )
    }

    @Test
    fun restorePersistedSessionClearsMetadataWhenCredentialIsMissing() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = "0x04" + "11".repeat(64),
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = MockWebCryptoCredentialSigner(available = false),
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
    }

    @Test
    fun signOutClearsPersistedStore() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            )
        val store = InMemorySessionStore(snapshot, FIXED_PRIVATE_KEY_HEX)
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
            )
        client.restorePersistedSession()

        client.signOut()

        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
        assertNull(client.walletAddress)
        assertNull(client.signerAddress)
        assertNull(store.privateKeyHex)
    }

    @Test
    fun addressReturnsSelectedWallet() {
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore =
                    InMemorySessionStore(
                        snapshot =
                            OMSClientSessionSnapshot(
                                walletId = "wallet-main",
                                walletAddress = "0xwallet",
                                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                            ),
                        privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                    ),
                privateKeyFactory = ::fixedPrivateKeyBytes,
            )
        assertTrue(client.restorePersistedSession())

        assertEquals("0xwallet", client.walletAddress)
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun restorePersistedSessionIgnoresPendingSnapshots() {
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore =
                    InMemorySessionStore(
                        snapshot =
                            OMSClientSessionSnapshot(
                                challenge = "challenge",
                                verifier = "verifier-123",
                                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                            ),
                        privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                    ),
                privateKeyFactory = ::fixedPrivateKeyBytes,
            )
        assertFalse(client.restorePersistedSession())

        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.walletAddress)
    }

    @Test
    fun hasPendingSignInIsTrueForInMemoryPendingAuth() {
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
                privateKeyFactory = ::fixedPrivateKeyBytes,
            )
        client.restoreSession(
            OMSClientSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        assertTrue(client.hasPendingSignIn)
        assertEquals(
            WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            client.signerAddress,
        )
        assertNull(client.walletAddress)
    }
}
