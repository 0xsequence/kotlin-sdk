package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkException
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.utils.OMSClientIsoTimestamps
import kotlinx.coroutines.runBlocking
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
                publishableKey = "test-publishable-key",
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
                publishableKey = "test-publishable-key",
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
    fun restorePersistedSessionRetainsExpiredMetadataAndReplaysExpiryEvent() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:00:00Z",
                loginType = OMSClientSessionLoginType.Email,
                sessionEmail = "user@example.com",
            )
        val store = InMemorySessionStore(snapshot)
        val signer = TrackingCredentialSigner()
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = signer,
                now = { epochMillis("2026-01-01T00:00:01Z") },
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(client.walletAddress)
        assertEquals(snapshot, store.snapshot)
        assertFalse(signer.hasCredential())

        var replayedEvent: com.omsclient.kotlin_sdk.OMSClientSessionExpiredEvent? = null
        client.onSessionExpired { replayedEvent = it }

        val event = requireNotNull(replayedEvent)
        assertEquals("0xabc", event.session.walletAddress)
        assertEquals("2026-01-01T00:00:00Z", event.session.expiresAt)
        assertEquals(OMSClientSessionLoginType.Email, event.session.loginType)
        assertEquals("user@example.com", event.session.sessionEmail)
        assertEquals("2026-01-01T00:00:00Z", event.expiredAt)
    }

    @Test
    fun expiredActiveSessionThrowsSessionExpiredAndKeepsStoredMetadata() =
        runBlocking {
            val snapshot =
                OMSClientSessionSnapshot(
                    walletId = "wallet-abc",
                    walletAddress = "0xabc",
                    signerAddress = TEST_CREDENTIAL_ID,
                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                    expiresAt = "2026-01-01T00:00:00Z",
                    loginType = OMSClientSessionLoginType.Email,
                    sessionEmail = "user@example.com",
                )
            val store = InMemorySessionStore(snapshot)
            val signer = TrackingCredentialSigner()
            val client =
                WalletClient(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = OMSClientEnvironment(),
                    sessionStore = store,
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)

            var expiredEvent: com.omsclient.kotlin_sdk.OMSClientSessionExpiredEvent? = null
            client.onSessionExpired { expiredEvent = it }

            val error =
                runCatching {
                    client.signMessage(
                        network = Network.AMOY,
                        message = "hello",
                    )
                }.exceptionOrNull()

            assertTrue(error is OmsSdkException)
            error as OmsSdkException
            assertEquals(OmsSdkErrorCode.SessionExpired, error.code)
            assertEquals("wallet.signMessage", error.operation?.id)
            assertEquals("Wallet session expired", error.message)
            assertNull(client.snapshotSession())
            assertEquals(snapshot, store.snapshot)
            assertFalse(signer.hasCredential())
            assertEquals("0xabc", requireNotNull(expiredEvent).session.walletAddress)
        }

    @Test
    fun sessionExpiryTaskClearsActiveSessionAndNotifiesListeners() {
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                loginType = OMSClientSessionLoginType.GoogleAuth,
                sessionEmail = "user@example.com",
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var expiredEvent: com.omsclient.kotlin_sdk.OMSClientSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        assertEquals(1, scheduler.scheduledTasks.size)
        assertEquals(120_000L, scheduler.scheduledTasks.single().delayMillis)
        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals("0xabc", requireNotNull(expiredEvent).session.walletAddress)
        assertEquals("2026-01-01T00:02:00Z", expiredEvent?.expiredAt)
    }

    @Test
    fun sessionExpiryTaskDispatchesStateChangeAndListenerNotification() {
        val scheduler = RecordingSessionExpiryScheduler()
        val dispatcher = RecordingSessionExpiryDispatcher()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                loginType = OMSClientSessionLoginType.GoogleAuth,
                sessionEmail = "user@example.com",
            )
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionExpiryDispatcher = dispatcher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var expiredEvent: com.omsclient.kotlin_sdk.OMSClientSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertNull(expiredEvent)
        assertEquals(1, dispatcher.actions.size)

        dispatcher.runNext()
        assertNull(client.snapshotSession())
        assertNull(expiredEvent)
        assertEquals(1, dispatcher.actions.size)

        dispatcher.runNext()
        assertEquals("0xabc", requireNotNull(expiredEvent).session.walletAddress)
    }

    @Test
    fun sessionExpiryEventStillNotifiesWhenCredentialCleanupFails() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:00:00Z",
                loginType = OMSClientSessionLoginType.Email,
                sessionEmail = "user@example.com",
            )
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = ThrowingClearCredentialSigner(),
                now = { epochMillis("2026-01-01T00:00:01Z") },
            )

        assertFalse(client.restorePersistedSession())

        var expiredEvent: com.omsclient.kotlin_sdk.OMSClientSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        assertEquals("0xabc", requireNotNull(expiredEvent).session.walletAddress)
    }

    @Test
    fun invalidSessionExpiryDoesNotCrashOrExpireSession() {
        val snapshot =
            OMSClientSessionSnapshot(
                walletId = "wallet-abc",
                walletAddress = "0xabc",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "not-a-timestamp",
            )
        val scheduler = RecordingSessionExpiryScheduler()
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = OMSClientEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                now = { epochMillis("2026-01-01T00:00:01Z") },
            )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertTrue(scheduler.scheduledTasks.isEmpty())
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
                publishableKey = "test-publishable-key",
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
                publishableKey = "test-publishable-key",
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
                publishableKey = "test-publishable-key",
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
                publishableKey = "test-publishable-key",
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
                publishableKey = "test-publishable-key",
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

private fun epochMillis(value: String): Long = requireNotNull(OMSClientIsoTimestamps.parseEpochMillis(value))
