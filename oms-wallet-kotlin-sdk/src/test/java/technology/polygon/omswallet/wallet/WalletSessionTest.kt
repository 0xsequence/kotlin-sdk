package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.storage.PersistedSessionRecord
import technology.polygon.omswallet.utils.OMSWalletIsoTimestamps
import java.io.IOException

class WalletSessionTest {
    @Test
    fun restorePersistedSessionLoadsFromStore() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                expiresAt = TEST_SESSION_EXPIRES_AT,
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                auth = emailSessionAuth(),
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals(snapshot.wallet, client.activeWallet)
        assertEquals(TEST_SESSION_EXPIRES_AT, client.session?.expiresAt)
        assertEmailSessionAuth(client.session?.auth)
        assertEquals(
            TEST_CREDENTIAL_ID,
            client.signerAddress,
        )
    }

    @Test
    fun restorePersistedSessionRestoresFullTronWalletFromStoredRecord() {
        val wallet =
            testWallet(
                id = "wallet-tron",
                address = "TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL",
                type = technology.polygon.omswallet.models.WalletType.Tron,
                reference = "imported",
                keyOrigin = technology.polygon.omswallet.models.WalletKeyOrigin.Imported,
            )
        val store =
            RecordBackedSessionStore(
                PersistedSessionRecord.encode(
                    OMSWalletSessionSnapshot(
                        wallet = wallet,
                        expiresAt = TEST_SESSION_EXPIRES_AT,
                        signerAddress = TEST_CREDENTIAL_ID,
                        signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                        auth = emailSessionAuth(),
                    ),
                ),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )

        assertTrue(client.restorePersistedSession())
        assertEquals(wallet, client.activeWallet)
        assertEquals(TEST_SESSION_EXPIRES_AT, client.session?.expiresAt)
        assertEmailSessionAuth(client.session?.auth)
    }

    @Test
    fun restorePersistedSessionDiscardsRecordsSavedBySdk03x() {
        val legacyRecord =
            """
            {"walletId":"wallet-abc","walletAddress":"0xabc0000000000000000000000000000000000000",
             "signerAddress":"$TEST_CREDENTIAL_ID","signerKeyType":"ecdsa-p256-sha256",
             "expiresAt":"$TEST_SESSION_EXPIRES_AT","auth":{"type":"email","email":"user@example.com"}}
            """.trimIndent()
        val store = RecordBackedSessionStore(legacyRecord)
        val signer = TrackingCredentialSigner()
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = signer,
            )

        assertFalse(client.restorePersistedSession())
        assertNull(client.activeWallet)
        assertNull(client.session)
        assertNull(store.record)
        assertFalse(signer.hasCredential())
    }

    @Test
    fun restorePersistedSessionClearsMetadataWhenCredentialIsMissing() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                expiresAt = TEST_SESSION_EXPIRES_AT,
                signerAddress = "0x04" + "11".repeat(64),
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                auth = emailSessionAuth(),
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
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
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:00:00Z",
                auth = emailSessionAuth(),
            )
        val store = InMemorySessionStore(snapshot)
        val signer = TrackingCredentialSigner()
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = signer,
                now = { epochMillis("2026-01-01T00:00:01Z") },
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(client.activeWallet)
        assertEquals(snapshot, store.snapshot)
        assertFalse(signer.hasCredential())

        var replayedEvent: technology.polygon.omswallet.OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { replayedEvent = it }

        val event = requireNotNull(replayedEvent)
        assertEquals("0xabc0000000000000000000000000000000000000", event.wallet?.address)
        assertEquals("2026-01-01T00:00:00Z", event.session.expiresAt)
        assertEmailSessionAuth(event.session.auth)
        assertEquals("2026-01-01T00:00:00Z", event.expiredAt)
    }

    @Test
    fun signOutClearsLatestSessionExpiredReplay() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:00:00Z",
                auth = emailSessionAuth(),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                now = { epochMillis("2026-01-01T00:00:01Z") },
            )
        assertFalse(client.restorePersistedSession())

        var replayCount = 0
        client.onSessionExpired { replayCount += 1 }
        assertEquals(1, replayCount)

        client.signOut()
        client.onSessionExpired { replayCount += 1 }

        assertEquals(1, replayCount)
    }

    @Test
    fun expiredActiveSessionThrowsSessionExpiredAndKeepsStoredMetadata() =
        runBlocking {
            val snapshot =
                OMSWalletSessionSnapshot(
                    wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                    signerAddress = TEST_CREDENTIAL_ID,
                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                    expiresAt = "2026-01-01T00:00:00Z",
                    auth = emailSessionAuth(),
                )
            val store = InMemorySessionStore(snapshot)
            val signer = TrackingCredentialSigner()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = testEnvironment(),
                    sessionStore = store,
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)

            var expiredEvent: technology.polygon.omswallet.OMSWalletSessionExpiredEvent? = null
            client.onSessionExpired { expiredEvent = it }

            val error =
                runCatching {
                    client.signMessage(
                        network = Network.AMOY,
                        message = "hello",
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            error as OMSWalletException
            assertEquals(OMSWalletErrorCode.SessionExpired, error.code)
            assertEquals("wallet.signMessage", error.operation?.id)
            assertEquals("Wallet session expired", error.message)
            assertNull(client.snapshotSession())
            assertEquals(snapshot, store.snapshot)
            assertFalse(signer.hasCredential())
            assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(expiredEvent).wallet?.address)
        }

    @Test
    fun sessionExpiryTaskClearsActiveSessionAndNotifiesListeners() {
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                auth = googleRedirectSessionAuth(),
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var expiredEvent: technology.polygon.omswallet.OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        assertEquals(1, scheduler.scheduledTasks.size)
        assertEquals(120_000L, scheduler.scheduledTasks.single().delayMillis)
        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(expiredEvent).wallet?.address)
        assertEquals("2026-01-01T00:02:00Z", expiredEvent?.expiredAt)
    }

    @Test
    fun pendingWalletSelectionExpiryReportsSessionWithoutWallet() {
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                now = { currentTime },
            )
        // Authenticated, but the app has not completed manual wallet selection yet.
        client.restoreSession(
            OMSWalletSessionSnapshot(
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                auth = emailSessionAuth(),
            ),
        )
        assertNull(client.activeWallet)
        assertNull(client.session)

        var expiredEvent: technology.polygon.omswallet.OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }
        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        val event = requireNotNull(expiredEvent)
        assertNull(event.wallet)
        assertEquals("2026-01-01T00:02:00Z", event.session.expiresAt)
        assertEmailSessionAuth(event.session.auth)
        assertEquals("2026-01-01T00:02:00Z", event.expiredAt)
    }

    @Test
    fun sessionExpiryTaskDispatchesStateChangeAndListenerNotification() {
        val scheduler = RecordingSessionExpiryScheduler()
        val dispatcher = RecordingSessionExpiryDispatcher()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                auth = googleRedirectSessionAuth(),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionExpiryDispatcher = dispatcher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var expiredEvent: technology.polygon.omswallet.OMSWalletSessionExpiredEvent? = null
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
        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(expiredEvent).wallet?.address)
    }

    @Test
    fun unsubscribedSessionExpiredListenerDoesNotReceivePendingDispatch() {
        val scheduler = RecordingSessionExpiryScheduler()
        val dispatcher = RecordingSessionExpiryDispatcher()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                auth = emailSessionAuth(),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionExpiryDispatcher = dispatcher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var notificationCount = 0
        val unsubscribe = client.onSessionExpired { notificationCount += 1 }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        dispatcher.runNext()
        unsubscribe()
        dispatcher.runNext()

        assertEquals(0, notificationCount)
    }

    @Test
    fun sessionExpiryEventStillNotifiesWhenCredentialCleanupFails() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:00:00Z",
                auth = emailSessionAuth(),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = ThrowingClearCredentialSigner(),
                now = { epochMillis("2026-01-01T00:00:01Z") },
            )

        assertFalse(client.restorePersistedSession())

        var expiredEvent: technology.polygon.omswallet.OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(expiredEvent).wallet?.address)
    }

    @Test
    fun invalidSessionExpiryDoesNotCrashOrExpireSession() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "not-a-timestamp",
                auth = emailSessionAuth(),
            )
        val scheduler = RecordingSessionExpiryScheduler()
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
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
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                expiresAt = TEST_SESSION_EXPIRES_AT,
                signerAddress = TEST_CREDENTIAL_ID,
                auth = emailSessionAuth(),
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
    }

    @Test
    fun sessionStateOnlyReflectsCompletedWalletSession() {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(),
                credentialSigner = TrackingCredentialSigner(),
            )
        client.restoreSession(
            OMSWalletSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2099-01-01T00:00:00Z",
                auth = emailSessionAuth(),
            ),
        )

        assertNull(client.activeWallet)
        assertNull(client.session)
    }

    @Test
    fun signOutClearsPersistedStore() {
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                expiresAt = TEST_SESSION_EXPIRES_AT,
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                auth = emailSessionAuth(),
            )
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )
        assertTrue(client.restorePersistedSession())

        client.signOut()

        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
        assertNull(client.activeWallet)
        assertNull(client.activeWallet)
        assertNull(client.signerAddress)
    }

    @Test
    fun addressReturnsSelectedWallet() {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore =
                    InMemorySessionStore(
                        snapshot =
                            OMSWalletSessionSnapshot(
                                wallet = testWallet("wallet-main", "0xwallet"),
                                expiresAt = TEST_SESSION_EXPIRES_AT,
                                signerAddress = TEST_CREDENTIAL_ID,
                                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                auth = emailSessionAuth(),
                            ),
                    ),
                credentialSigner = TrackingCredentialSigner(),
            )
        assertTrue(client.restorePersistedSession())

        assertEquals("0xwallet", client.activeWallet?.address)
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun restorePersistedSessionClearsPendingSnapshots() {
        val store =
            InMemorySessionStore(
                snapshot =
                    OMSWalletSessionSnapshot(
                        challenge = "challenge",
                        verifier = "verifier-123",
                        signerAddress = TEST_CREDENTIAL_ID,
                    ),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
            )
        assertFalse(client.restorePersistedSession())

        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.activeWallet)
        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
    }

    @Test
    fun hasPendingSignInIsTrueForInMemoryPendingAuth() {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(),
                credentialSigner = TrackingCredentialSigner(),
            )
        client.restoreSession(
            OMSWalletSessionSnapshot(
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
        assertNull(client.activeWallet)
        assertNull(client.activeWallet)
        assertNull(client.session)
    }
}

private fun epochMillis(value: String): Long = requireNotNull(OMSWalletIsoTimestamps.parseEpochMillis(value))

/** Session store backed by the persisted record codec, mirroring the Android file store. */
private class RecordBackedSessionStore(
    var record: String?,
) : OMSWalletSessionMetadataStore {
    override fun load(): OMSWalletSessionSnapshot? = record?.let(PersistedSessionRecord::decode)

    override fun save(snapshot: OMSWalletSessionSnapshot) {
        record = PersistedSessionRecord.encode(snapshot)
    }

    override fun clear() {
        record = null
    }
}
