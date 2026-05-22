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
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals("0xabc", client.walletAddress)
        assertEquals(
            TEST_CREDENTIAL_ID,
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
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
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
    fun restorePersistedSessionClearsMetadataWhenSignerKeyTypeIsMissing() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = TEST_CREDENTIAL_ID,
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
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
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )
        assertTrue(client.restorePersistedSession())

        client.signOut()

        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
        assertNull(client.walletAddress)
        assertNull(client.signerAddress)
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
                                signerAddress = TEST_CREDENTIAL_ID,
                                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                            ),
                    ),
                credentialSigner = TrackingCredentialSigner(),
            )
        assertTrue(client.restorePersistedSession())

        assertEquals("0xwallet", client.walletAddress)
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun restorePersistedSessionClearsPendingSnapshots() {
        val store =
            InMemorySessionStore(
                snapshot =
                    OMSClientSessionSnapshot(
                        challenge = "challenge",
                        verifier = "verifier-123",
                        signerAddress = TEST_CREDENTIAL_ID,
                    ),
            )
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )
        assertFalse(client.restorePersistedSession())

        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.walletAddress)
        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
    }

    @Test
    fun hasPendingSignInIsTrueForInMemoryPendingAuth() {
        val client =
            WalletClient(
                publicApiKey = "test-access-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = InMemorySessionStore(),
                credentialSigner = TrackingCredentialSigner(),
            )
        client.restoreSession(
            OMSClientSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = TEST_CREDENTIAL_ID,
            ),
        )

        assertTrue(client.hasPendingSignIn)
        assertEquals(
            TEST_CREDENTIAL_ID,
            client.signerAddress,
        )
        assertNull(client.walletAddress)
    }
}
