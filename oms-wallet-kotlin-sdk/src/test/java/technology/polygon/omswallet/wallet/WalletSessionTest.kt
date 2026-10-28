package technology.polygon.omswallet.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletSessionExpiredEvent
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.WebRpcJson
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.storage.PersistedSessionRecord
import technology.polygon.omswallet.utils.OMSWalletIsoTimestamps
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WalletSessionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals(snapshot.wallet, client.activeWallet)
        assertFalse(client.hasPendingSignIn)
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
                credentialSigner = TrackingCredentialSigner(available = false, nonceValue = "42"),
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(client.activeWallet)
        assertEquals(snapshot, store.snapshot)
        assertFalse(signer.hasCredential())

        var replayedEvent: OMSWalletSessionExpiredEvent? = null
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
            // A WaaS server without GetCredential keeps local expiry authoritative.
            server.enqueue(badRouteResponse())
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
                    environment = serverEnvironment(),
                    sessionStore = store,
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)

            var expiredEvent: OMSWalletSessionExpiredEvent? = null
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
            assertEquals(WaasApi.GetCredential.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)

            // The unsupported server is remembered: the next expired session is not rechecked.
            signer.credentialId()
            client.restoreSession(snapshot)
            val secondError = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()
            assertEquals(OMSWalletErrorCode.SessionExpired, (secondError as OMSWalletException).code)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun locallyExpiredOperationRefreshesExpiryAndContinues() =
        runBlocking {
            server.enqueueJson(getCredentialResponseBody("2026-01-11T00:00:00Z"))
            server.enqueueJson("""{"signature":"0xsigned"}""")
            val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")
            val store = InMemorySessionStore(snapshot)
            val scheduler = RecordingSessionExpiryScheduler()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = scheduler,
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)
            var expiredEvent: OMSWalletSessionExpiredEvent? = null
            client.onSessionExpired { expiredEvent = it }

            val signature = client.signMessage(network = Network.AMOY, message = "hello")

            assertEquals("0xsigned", signature)
            assertEquals("2026-01-11T00:00:00Z", client.session?.expiresAt)
            assertEquals("2026-01-11T00:00:00Z", store.snapshot?.expiresAt)
            assertEquals(10 * 24 * 60 * 60 * 1000L - 1000L, scheduler.scheduledTasks.single().delayMillis)
            assertNull(expiredEvent)
            assertEquals(WaasApi.GetCredential.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)
            assertEquals(WaasApi.SignMessage.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)
        }

    @Test
    fun expiryTimerRefreshExtendsPersistsAndReschedulesSession() {
        server.enqueueJson(getCredentialResponseBody("2026-01-11T00:07:00Z"))
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)
        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        // The idle check waits out the grace period before reading the credential.
        assertEquals(IDLE_CHECK_GRACE_MILLIS, scheduler.scheduledTasks.single().delayMillis)
        assertEquals(0, server.requestCount)
        currentTime += IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()

        assertEquals("2026-01-11T00:07:00Z", client.session?.expiresAt)
        assertEquals(snapshot.copy(expiresAt = "2026-01-11T00:07:00Z"), store.snapshot)
        assertEquals(10 * 24 * 60 * 60 * 1000L, scheduler.scheduledTasks.single().delayMillis)
        assertNull(expiredEvent)
        assertEquals(WaasApi.GetCredential.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)
    }

    @Test
    fun expiryTimerKeepsSessionAndRetriesWhenWaasIsUnreachable() {
        val environment = serverEnvironment()
        server.close()
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = environment,
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)
        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        assertEquals(IDLE_CHECK_GRACE_MILLIS, scheduler.scheduledTasks.single().delayMillis)
        currentTime += IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertNull(expiredEvent)
        assertEquals(60_000L, scheduler.scheduledTasks.single().delayMillis)

        currentTime += 60_000L
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertEquals(120_000L, scheduler.scheduledTasks.single().delayMillis)
    }

    @Test
    fun serverRejectedCredentialEndsSessionAndNotifiesOnce() =
        runBlocking {
            for ((status, error) in listOf(404 to """"KeyNotFound","code":7202""", 400 to """"KeyExpired","code":7203""")) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(status)
                        .body("""{"error":$error,"msg":"credential rejected","status":$status}""")
                        .build(),
                )
                val snapshot = expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)
                val store = InMemorySessionStore(snapshot)
                val client =
                    WalletClient.create(
                        publishableKey = "test-publishable-key",
                        projectId = "test-project-id",
                        environment = serverEnvironment(),
                        sessionStore = store,
                        credentialSigner = TrackingCredentialSigner(),
                        sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    )
                client.restoreSession(snapshot)
                var notifications = 0
                client.onSessionExpired { notifications += 1 }

                val first = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()
                val second = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

                assertEquals(OMSWalletErrorCode.SessionExpired, (first as OMSWalletException).code)
                assertEquals("wallet.signMessage", first.operation?.id)
                assertEquals(OMSWalletErrorCode.SessionMissing, (second as OMSWalletException).code)
                assertNull(client.snapshotSession())
                assertEquals(snapshot, store.snapshot)
                assertEquals(1, notifications)
            }
        }

    @Test
    fun publicCallRejectingRemoteCredentialKeepsSession() =
        runBlocking {
            // InspectCredential is unsigned; its 7202 refers to the remote credential, not this session.
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"error":"KeyNotFound","code":7202,"msg":"credential not found","status":404}""")
                    .build(),
            )
            val snapshot = expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(snapshot)
            var notifications = 0
            client.onSessionExpired { notifications += 1 }

            val error = runCatching { client.inspectRemoteCredential("remote-credential") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletException).code)
            assertEquals(snapshot, client.snapshotSession())
            assertEquals(0, notifications)
        }

    @Test
    fun expiryTimerEndsSessionWhenGetCredentialReportsRevokedCredential() {
        // The gateway's own "Unauthorized" (code 1000) is not WaaS 7207: the session is kept and rechecked.
        server.enqueue(errorResponse(401, "Unauthorized", 1000))
        server.enqueue(errorResponse(401, "Unauthorized", 7207))
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val store = InMemorySessionStore(snapshot)
        val signer = TrackingCredentialSigner()
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = store,
                credentialSigner = signer,
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)
        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        var notifications = 0
        client.onSessionExpired {
            expiredEvent = it
            notifications += 1
        }

        currentTime = epochMillis("2026-01-01T00:02:00Z") + IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()
        assertEquals(snapshot, client.snapshotSession())
        assertEquals(60_000L, scheduler.scheduledTasks.single().delayMillis)

        currentTime += 60_000L
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertFalse(signer.hasCredential())
        assertEquals("2026-01-01T00:02:00Z", requireNotNull(expiredEvent).expiredAt)
        assertEquals(1, notifications)
        assertTrue(scheduler.scheduledTasks.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun locallyExpiredOperationThrowsSessionExpiredWhenGetCredentialReportsRevokedCredential() =
        runBlocking {
            server.enqueue(errorResponse(401, "Unauthorized", 7207))
            val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)
            var notifications = 0
            client.onSessionExpired { notifications += 1 }

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.SessionExpired, (error as OMSWalletException).code)
            assertEquals("wallet.signMessage", error.operation?.id)
            assertNull(error.upstreamError)
            assertNull(client.snapshotSession())
            assertEquals(1, notifications)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun walletCallRejectedWith7207KeepsSession() =
        runBlocking {
            server.enqueue(errorResponse(401, "Unauthorized", 7207))
            val snapshot = expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(snapshot)
            var notifications = 0
            client.onSessionExpired { notifications += 1 }

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletException).code)
            assertEquals("7207", error.upstreamError?.code)
            assertEquals(snapshot, client.snapshotSession())
            assertEquals(0, notifications)
        }

    @Test
    fun expiryTimerReschedulesWithoutReadingCredentialWhenSessionWasUsedAfterLastRead() =
        runBlocking {
            server.enqueueJson("""{"signature":"0xsigned"}""")
            server.enqueue(errorResponse(400, "KeyExpired", 7203))
            val scheduler = RecordingSessionExpiryScheduler()
            var currentTime = epochMillis("2026-01-01T00:00:00Z")
            val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = scheduler,
                    credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                    now = { currentTime },
                )
            client.restoreSession(snapshot)
            var notifications = 0
            client.onSessionExpired { notifications += 1 }
            // Used one minute after its expiry was read, so WaaS slid the expiry by one minute.
            currentTime += 60_000L
            client.signMessage(network = Network.AMOY, message = "hello")

            currentTime = epochMillis("2026-01-01T00:02:00Z")
            scheduler.scheduledTasks.single().action()

            assertEquals(snapshot, client.snapshotSession())
            assertEquals(60_000L + IDLE_CHECK_GRACE_MILLIS, scheduler.scheduledTasks.single().delayMillis)
            assertEquals(1, server.requestCount)

            currentTime += 60_000L + IDLE_CHECK_GRACE_MILLIS
            scheduler.scheduledTasks.single().action()

            assertNull(client.snapshotSession())
            assertEquals(1, notifications)
            assertEquals(
                listOf(WaasApi.SignMessage.urlPath, WaasApi.GetCredential.urlPath),
                List(server.requestCount) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target },
            )
        }

    @Test
    fun expiryTimerReadsIdleSessionCredentialOnlyAfterGracePeriod() {
        server.enqueue(errorResponse(400, "KeyExpired", 7203))
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)
        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        assertEquals(IDLE_CHECK_GRACE_MILLIS, scheduler.scheduledTasks.single().delayMillis)
        // A timer that fires early is rescheduled again instead of reading the credential.
        currentTime += IDLE_CHECK_GRACE_MILLIS - 1
        scheduler.scheduledTasks.single().action()
        assertEquals(1L, scheduler.scheduledTasks.single().delayMillis)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals(0, server.requestCount)

        currentTime += 1
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals("2026-01-01T00:02:00Z", requireNotNull(expiredEvent).expiredAt)
        assertEquals(WaasApi.GetCredential.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun expiryTimerKeepsSessionAndRetriesWhenCredentialExpiryIsInvalid() {
        server.enqueueJson(getCredentialResponseBody("not-a-timestamp"))
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val store = InMemorySessionStore(snapshot)
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)
        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        currentTime = epochMillis("2026-01-01T00:02:00Z") + IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertNull(expiredEvent)
        assertEquals(60_000L, scheduler.scheduledTasks.single().delayMillis)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancelledRefreshOwnerDoesNotCancelOperationSharingTheRefresh() =
        runBlocking {
            val firstRefreshArrived = CountDownLatch(1)
            val releaseFirstRefresh = CountDownLatch(1)
            val refreshes = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target != WaasApi.GetCredential.urlPath) {
                            return MockResponse
                                .Builder()
                                .code(200)
                                .body("""{"signature":"0xsigned"}""")
                                .build()
                        }
                        if (refreshes.incrementAndGet() == 1) {
                            firstRefreshArrived.countDown()
                            releaseFirstRefresh.await(5, TimeUnit.SECONDS)
                        }
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body(getCredentialResponseBody("2026-01-11T00:00:00Z"))
                            .build()
                    }
                }
            val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)

            val owner = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "owner") }
            assertTrue(firstRefreshArrived.await(5, TimeUnit.SECONDS))
            val waiter = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "waiter") }
            // Let the second operation start waiting on the shared refresh.
            Thread.sleep(300)
            owner.cancel()
            releaseFirstRefresh.countDown()

            assertEquals("0xsigned", waiter.await())
            assertTrue(owner.isCancelled)
            assertEquals("2026-01-11T00:00:00Z", client.session?.expiresAt)
        }

    @Test
    fun signedRequestIsResignedWithFreshNonceWhenWaasRejectsItsNonce() =
        runBlocking {
            server.enqueue(errorResponse(412, "PreconditionFailed", 7206))
            server.enqueueJson("""{"signature":"0xsigned"}""")
            val snapshot = expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = IncreasingNonceCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(snapshot)

            assertEquals("0xsigned", client.signMessage(network = Network.AMOY, message = "hello"))

            assertEquals(2, server.requestCount)
            val first = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val second = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(WaasApi.SignMessage.urlPath, first.target)
            assertEquals(WaasApi.SignMessage.urlPath, second.target)
            assertEquals(first.utf8Body(), second.utf8Body())
            assertTrue(first.signatureNonce() < second.signatureNonce())
        }

    @Test
    fun signedRequestSurfacesNonceRejectionAfterTwoResends() =
        runBlocking {
            repeat(3) { server.enqueue(errorResponse(412, "PreconditionFailed", 7206)) }
            server.enqueueJson("""{"signature":"0xsigned"}""")
            val snapshot = expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = IncreasingNonceCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(snapshot)

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletException).code)
            assertEquals(412, error.status)
            assertEquals("7206", error.upstreamError?.code)
            assertEquals(3, server.requestCount)
            assertEquals(snapshot, client.snapshotSession())
        }

    @Test
    fun signedRequestWaitsForInFlightBackgroundRefresh() =
        runBlocking {
            // WaaS needs increasing nonces per credential, so requests must not overlap a refresh.
            val refreshArrived = CountDownLatch(1)
            val releaseRefresh = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target != WaasApi.GetCredential.urlPath) {
                            return MockResponse
                                .Builder()
                                .code(200)
                                .body("""{"signature":"0xsigned"}""")
                                .build()
                        }
                        refreshArrived.countDown()
                        releaseRefresh.await(5, TimeUnit.SECONDS)
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body(getCredentialResponseBody(TEST_SESSION_EXPIRES_AT))
                            .build()
                    }
                }
            val signer = TrackingCredentialSigner()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)),
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher = { block -> thread { runBlocking { block() } } },
                )
            assertTrue(client.restorePersistedSession())
            assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))

            val signing = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "hello") }
            Thread.sleep(300)

            assertEquals(1, server.requestCount)
            assertEquals(1, signer.signCalls)
            releaseRefresh.countDown()
            assertEquals("0xsigned", signing.await())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun backgroundRefreshSkippedWhileSignedRequestIsInFlightRunsOnNextActivity() =
        runBlocking {
            // A refresh signs with the session credential, so it must not overlap another request's nonce.
            val heldArrived = CountDownLatch(1)
            val releaseHeld = CountDownLatch(1)
            val signRequests = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target == WaasApi.GetCredential.urlPath) {
                            return MockResponse
                                .Builder()
                                .code(200)
                                .body(getCredentialResponseBody("2026-01-11T00:00:00Z"))
                                .build()
                        }
                        if (signRequests.incrementAndGet() == 1) {
                            heldArrived.countDown()
                            releaseHeld.await(5, TimeUnit.SECONDS)
                        }
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body("""{"signature":"0xsigned"}""")
                            .build()
                    }
                }
            var currentTime = epochMillis("2026-01-01T00:00:00Z")
            val snapshot = expiringSnapshot(expiresAt = "2026-01-05T00:00:00Z")
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                    now = { currentTime },
                )
            client.restoreSession(snapshot)
            currentTime += SIX_HOURS_MILLIS

            val held = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "held") }
            assertTrue(heldArrived.await(5, TimeUnit.SECONDS))
            // This call makes a refresh due while the held request is still in flight.
            val signature = client.signMessage(network = Network.AMOY, message = "quick")
            val requestsWhileHeld = server.requestCount
            assertEquals(snapshot, client.snapshotSession())
            releaseHeld.countDown()

            assertEquals("0xsigned", signature)
            // The skipped refresh did not restart the 6-hour clock, so the held call's completion runs it.
            assertEquals("0xsigned", held.await())
            assertEquals(2, requestsWhileHeld)
            assertEquals(
                listOf(WaasApi.SignMessage.urlPath, WaasApi.SignMessage.urlPath, WaasApi.GetCredential.urlPath),
                List(server.requestCount) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target },
            )
            assertEquals("2026-01-11T00:00:00Z", client.session?.expiresAt)
        }

    @Test
    fun backgroundRefreshRunsAfterSignedCallOnceSixHoursElapsed() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.target == WaasApi.GetCredential.urlPath) {
                            MockResponse
                                .Builder()
                                .code(500)
                                .body("""{"error":"InternalError","code":5000,"msg":"unavailable","status":500}""")
                                .build()
                        } else {
                            MockResponse
                                .Builder()
                                .code(200)
                                .body("""{"signature":"0xsigned"}""")
                                .build()
                        }
                }
            var currentTime = epochMillis("2026-01-01T00:00:00Z")
            val snapshot = expiringSnapshot(expiresAt = "2026-01-05T00:00:00Z")
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                    now = { currentTime },
                )
            client.restoreSession(snapshot)
            val restoredAt = currentTime

            currentTime = restoredAt + SIX_HOURS_MILLIS - 1
            client.signMessage(network = Network.AMOY, message = "not due")
            currentTime = restoredAt + SIX_HOURS_MILLIS
            client.signMessage(network = Network.AMOY, message = "due")
            // The failed attempt still restarts the 6-hour clock instead of retrying on every call.
            currentTime = restoredAt + SIX_HOURS_MILLIS + 1
            client.signMessage(network = Network.AMOY, message = "after attempt")

            assertEquals(
                listOf(
                    WaasApi.SignMessage.urlPath,
                    WaasApi.SignMessage.urlPath,
                    WaasApi.GetCredential.urlPath,
                    WaasApi.SignMessage.urlPath,
                ),
                List(server.requestCount) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target },
            )
            assertEquals(snapshot, client.snapshotSession())
        }

    @Test
    fun signInRequestsRejectedWith7202Or7203AreRequestFailures() =
        runBlocking {
            // Sign-in requests carry a credential WaaS has not verified yet, so these codes do not mean a session ended.
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"error":"KeyNotFound","code":7202,"msg":"credential not found","status":404}""")
                    .build(),
            )
            server.enqueueJson("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .body("""{"error":"KeyExpired","code":7203,"msg":"key expired","status":400}""")
                    .build(),
            )
            val snapshot = expiringSnapshot(expiresAt = TEST_SESSION_EXPIRES_AT)
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(snapshot)
            var notifications = 0
            client.onSessionExpired { notifications += 1 }

            val commitError = runCatching { client.startEmailAuth("user@example.com") }.exceptionOrNull()
            client.startEmailAuth("user@example.com")
            val completeError = runCatching { client.completeEmailAuth("123456") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (commitError as OMSWalletException).code)
            assertEquals("7202", commitError.upstreamError?.code)
            assertEquals(OMSWalletErrorCode.RequestFailed, (completeError as OMSWalletException).code)
            assertEquals("7203", completeError.upstreamError?.code)
            assertEquals(0, notifications)
        }

    @Test
    fun staleRefreshResultForReplacedSessionIsIgnored() {
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val replacement =
            snapshot.copy(
                wallet = testWallet("wallet-next", "0xdef0000000000000000000000000000000000000"),
                expiresAt = "2026-01-01T00:30:00Z",
            )
        val store = InMemorySessionStore(snapshot)
        lateinit var client: WalletClient
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // The session is replaced while GetCredential for the old one is in flight.
                    client.restoreSession(replacement)
                    return MockResponse
                        .Builder()
                        .code(200)
                        .body(getCredentialResponseBody("2026-01-11T00:02:00Z"))
                        .build()
                }
            }
        client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        currentTime = epochMillis("2026-01-01T00:02:00Z") + IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()

        assertEquals(replacement, client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals(23 * 60 * 1000L, scheduler.scheduledTasks.single().delayMillis)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sessionExpiryTaskClearsActiveSessionAndNotifiesListeners() {
        server.enqueue(badRouteResponse())
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
                environment = serverEnvironment(),
                sessionStore = store,
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        assertEquals(1, scheduler.scheduledTasks.size)
        assertEquals(120_000L, scheduler.scheduledTasks.single().delayMillis)
        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        assertEquals(snapshot, client.snapshotSession())
        currentTime += IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(expiredEvent).wallet?.address)
        assertEquals("2026-01-01T00:02:00Z", expiredEvent?.expiredAt)
    }

    @Test
    fun pendingWalletSelectionExpiryReportsSessionWithoutWallet() {
        server.enqueue(badRouteResponse())
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
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

        var expiredEvent: OMSWalletSessionExpiredEvent? = null
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
        server.enqueue(badRouteResponse())
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
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionExpiryDispatcher = dispatcher,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var expiredEvent: OMSWalletSessionExpiredEvent? = null
        client.onSessionExpired { expiredEvent = it }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        dispatcher.runNext()
        currentTime += IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertNull(expiredEvent)
        assertEquals(1, dispatcher.actions.size)

        // The timer refreshes first; the unsupported server hands expiry back to the dispatcher.
        dispatcher.runNext()
        assertEquals(snapshot, client.snapshotSession())
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
        server.enqueue(badRouteResponse())
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
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionExpiryDispatcher = dispatcher,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        var notificationCount = 0
        val unsubscribe = client.onSessionExpired { notificationCount += 1 }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        dispatcher.runNext()
        currentTime += IDLE_CHECK_GRACE_MILLIS
        scheduler.scheduledTasks.single().action()
        dispatcher.runNext()
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )

        assertFalse(client.restorePersistedSession())

        var expiredEvent: OMSWalletSessionExpiredEvent? = null
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
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
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
            // Pending auth exposes no wallet or public session even when expiry and auth are present.
            OMSWalletSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2099-01-01T00:00:00Z",
                auth = emailSessionAuth(),
            ),
        )

        assertTrue(client.hasPendingSignIn)
        assertEquals(
            TEST_CREDENTIAL_ID,
            client.signerAddress,
        )
        assertNull(client.activeWallet)
        assertNull(client.session)
    }

    private fun serverEnvironment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )
}

private const val SIX_HOURS_MILLIS = 6 * 60 * 60 * 1000L
private const val IDLE_CHECK_GRACE_MILLIS = 5 * 60 * 1000L

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

private fun expiringSnapshot(expiresAt: String): OMSWalletSessionSnapshot =
    OMSWalletSessionSnapshot(
        wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
        signerAddress = TEST_CREDENTIAL_ID,
        signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
        expiresAt = expiresAt,
        auth = emailSessionAuth(),
    )

private fun getCredentialResponseBody(expiresAt: String): String =
    WebRpcJson.encodeToString(WaasApi.GetCredential.Response(credentialFixture().copy(expiresAt = expiresAt)))

private fun errorResponse(
    status: Int,
    name: String,
    code: Int,
): MockResponse =
    MockResponse
        .Builder()
        .code(status)
        .body("""{"error":"$name","code":$code,"msg":"rejected","status":$status}""")
        .build()

/** Signs with a nonce that increases on every request, as the Android Keystore signer does. */
private class IncreasingNonceCredentialSigner : TrackingCredentialSigner() {
    private val nonce = AtomicInteger(1_000)

    override fun nextNonce(): String = nonce.incrementAndGet().toString()

    override fun sign(preimage: String): String = TEST_SIGNATURE
}

private fun RecordedRequest.signatureNonce(): Long =
    requireNotNull(Regex("nonce=(\\d+)").find(requireNotNull(headers[OMSWalletEnvironment.walletSignatureHeaderName])))
        .groupValues[1]
        .toLong()

/** What WaaS servers released before GetCredential return for an unknown method. */
private fun badRouteResponse(): MockResponse =
    MockResponse
        .Builder()
        .code(404)
        .body("""{"error":"WebrpcBadRoute","code":-2,"msg":"bad route","status":404}""")
        .build()
