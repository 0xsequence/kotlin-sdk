package technology.polygon.omswallet.wallet

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletSession
import technology.polygon.omswallet.OMSWalletSessionChangeEvent
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.WebRpcJson
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.session.OMSWalletSessionExpiryBounds
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.storage.PersistedSessionRecord
import technology.polygon.omswallet.utils.OMSWalletIsoTimestamps
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
                        expiryBounds = TEST_EXPIRY_BOUNDS,
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
                // The expiry is the lifetime cap, so nothing can have extended it.
                expiryBounds = OMSWalletSessionExpiryBounds(epochMillis("2026-01-01T00:00:00Z")),
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
                now = { epochMillis("2026-01-01T00:01:00Z") },
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )

        val restored = client.restorePersistedSession()

        assertFalse(restored)
        assertNull(client.snapshotSession())
        assertNull(client.activeWallet)
        assertEquals(snapshot, store.snapshot)
        assertFalse(signer.hasCredential())

        val changes = client.recordSessionChanges()

        val event = requireNotNull(changes.lastExpired)
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
                // The expiry is the lifetime cap, so nothing can have extended it.
                expiryBounds = OMSWalletSessionExpiryBounds(epochMillis("2026-01-01T00:00:00Z")),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                now = { epochMillis("2026-01-01T00:01:00Z") },
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )
        assertFalse(client.restorePersistedSession())

        assertEquals(listOf("Expired"), client.recordSessionChanges().kinds)

        // Signing out changes nothing visible (nothing was active), yet ends the replay.
        client.signOut()

        assertEquals(listOf<String>(), client.recordSessionChanges().kinds)
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

            val changes = client.recordSessionChanges()

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
            assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(changes.lastExpired).wallet?.address)
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
            val changes = client.recordSessionChanges()

            val signature = client.signMessage(network = Network.AMOY, message = "hello")

            assertEquals("0xsigned", signature)
            assertEquals("2026-01-11T00:00:00Z", client.session?.expiresAt)
            assertEquals("2026-01-11T00:00:00Z", store.snapshot?.expiresAt)
            assertEquals(10 * 24 * 60 * 60 * 1000L - 1000L, scheduler.scheduledTasks.single().delayMillis)
            assertEquals(listOf("ExpiryUpdated"), changes.kinds)
            assertEquals(WaasApi.GetCredential.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)
            assertEquals(WaasApi.SignMessage.urlPath, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target)
        }

    @Test
    fun expiryTimerRefreshExtendsPersistsAndReschedulesSession() {
        server.enqueueJson(getCredentialResponseBody("2026-01-11T00:02:00Z"))
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
        val changes = client.recordSessionChanges()

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertEquals("2026-01-11T00:02:00Z", client.session?.expiresAt)
        assertEquals(snapshot.copy(expiresAt = "2026-01-11T00:02:00Z"), store.snapshot)
        assertEquals(10 * 24 * 60 * 60 * 1000L, scheduler.scheduledTasks.single().delayMillis)
        assertEquals(listOf("ExpiryUpdated"), changes.kinds)
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
        val changes = client.recordSessionChanges()

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertEquals(listOf<String>(), changes.kinds)
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
                val changes = client.recordSessionChanges()

                val first = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()
                val second = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

                assertEquals(OMSWalletErrorCode.SessionExpired, (first as OMSWalletException).code)
                assertEquals("wallet.signMessage", first.operation?.id)
                assertEquals(OMSWalletErrorCode.SessionMissing, (second as OMSWalletException).code)
                assertNull(client.snapshotSession())
                assertEquals(snapshot, store.snapshot)
                assertEquals(listOf("Expired"), changes.kinds)
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
            val changes = client.recordSessionChanges()

            val error = runCatching { client.inspectRemoteCredential("remote-credential") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletException).code)
            assertEquals(snapshot, client.snapshotSession())
            assertEquals(listOf<String>(), changes.kinds)
        }

    @Test
    fun expiredSessionKeepsAKeyANewerSignInStoredWhileItsReadWasInFlight() {
        // Another sign-in replaces the shared key while this session's expiry read is at WaaS.
        val signer = ReplaceableCredentialSigner()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    signer.replaceKey("0x04" + "22".repeat(64))
                    return errorResponse(400, "KeyExpired", 7203)
                }
            }
        val scheduler = RecordingSessionExpiryScheduler()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:02:00Z")
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = signer,
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)
        val changes = client.recordSessionChanges()

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(listOf("Expired"), changes.kinds)
        assertEquals("0x04" + "22".repeat(64), signer.existingCredentialId())
        assertEquals(0, signer.clearCalls)
    }

    @Test
    fun expiredSessionStillDeletesItsOwnKey() {
        val signer = ReplaceableCredentialSigner()
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
                credentialSigner = signer,
                sessionExpiryScheduler = scheduler,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertNull(signer.existingCredentialId())
        assertEquals(1, signer.clearCalls)
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
        val changes = client.recordSessionChanges()

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        assertEquals(snapshot, client.snapshotSession())
        assertEquals(60_000L, scheduler.scheduledTasks.single().delayMillis)

        currentTime += 60_000L
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertFalse(signer.hasCredential())
        assertEquals("2026-01-01T00:02:00Z", requireNotNull(changes.lastExpired).expiredAt)
        assertEquals(listOf("Expired"), changes.kinds)
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
            val changes = client.recordSessionChanges()

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.SessionExpired, (error as OMSWalletException).code)
            assertEquals("wallet.signMessage", error.operation?.id)
            assertNull(error.upstreamError)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
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
            val changes = client.recordSessionChanges()

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletException).code)
            assertEquals("7207", error.upstreamError?.code)
            assertEquals(snapshot, client.snapshotSession())
            assertEquals(listOf<String>(), changes.kinds)
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
        val changes = client.recordSessionChanges()

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals(listOf<String>(), changes.kinds)
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
            val releaseQuick = CountDownLatch(1)
            val signRequests = AtomicInteger()
            val credentialReads = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target == WaasApi.GetCredential.urlPath) {
                            credentialReads.incrementAndGet()
                            return MockResponse
                                .Builder()
                                .code(200)
                                .body(getCredentialResponseBody("2026-01-11T00:00:00Z"))
                                .build()
                        }
                        when (signRequests.incrementAndGet()) {
                            1 -> {
                                heldArrived.countDown()
                                releaseHeld.await(5, TimeUnit.SECONDS)
                            }

                            2 -> {
                                releaseQuick.await(5, TimeUnit.SECONDS)
                            }
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
            // This call waits for the held request's turn; when the held one completes and makes a
            // refresh due, the waiting call holds the turn, so the refresh is skipped.
            val quick = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "quick") }
            // Wait until the quick call queues behind the held one (the held one plus the quick one).
            withTimeout(5_000) { while (client.signedRequestTurnUsers() < 2) delay(5) }
            val requestsWhileHeld = server.requestCount
            assertEquals(snapshot, client.snapshotSession())
            releaseHeld.countDown()
            // The held call returns after its due refresh was tried; the quick call held the turn.
            assertEquals("0xsigned", held.await())
            assertEquals(0, credentialReads.get())
            releaseQuick.countDown()

            // The skipped refresh did not restart the 6-hour clock, so the waiting call's completion runs it.
            assertEquals("0xsigned", quick.await())
            assertEquals(1, requestsWhileHeld)
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
            val changes = client.recordSessionChanges()

            val commitError = runCatching { client.startEmailAuth("user@example.com") }.exceptionOrNull()
            client.startEmailAuth("user@example.com")
            val completeError = runCatching { client.completeEmailAuth("123456") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (commitError as OMSWalletException).code)
            assertEquals("7202", commitError.upstreamError?.code)
            assertEquals(OMSWalletErrorCode.RequestFailed, (completeError as OMSWalletException).code)
            assertEquals("7203", completeError.upstreamError?.code)
            // Starting the sign-in ended the restored session; neither rejection is an expiry.
            assertEquals(listOf("SignedOut"), changes.kinds)
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

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertEquals(replacement, client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals(28 * 60 * 1000L, scheduler.scheduledTasks.single().delayMillis)
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
                expiryBounds = TEST_EXPIRY_BOUNDS,
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

        val changes = client.recordSessionChanges()

        assertEquals(1, scheduler.scheduledTasks.size)
        assertEquals(120_000L, scheduler.scheduledTasks.single().delayMillis)
        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertNull(client.snapshotSession())
        assertEquals(snapshot, store.snapshot)
        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(changes.lastExpired).wallet?.address)
        assertEquals("2026-01-01T00:02:00Z", changes.lastExpired?.expiredAt)
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

        val changes = client.recordSessionChanges()
        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        val event = requireNotNull(changes.lastExpired)
        assertNull(event.wallet)
        assertEquals("2026-01-01T00:02:00Z", event.session.expiresAt)
        assertEmailSessionAuth(event.session.auth)
        assertEquals("2026-01-01T00:02:00Z", event.expiredAt)
    }

    @Test
    fun sessionExpiryTaskDispatchesStateChangeAndListenerNotification() {
        server.enqueue(badRouteResponse())
        val scheduler = RecordingSessionExpiryScheduler()
        val dispatcher = RecordingSessionChangeDispatcher()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                auth = googleRedirectSessionAuth(),
                expiryBounds = TEST_EXPIRY_BOUNDS,
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionChangeDispatcher = dispatcher,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        val changes = client.recordSessionChanges()

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()

        assertEquals(snapshot, client.snapshotSession())
        assertEquals(listOf<String>(), changes.kinds)
        assertEquals(1, dispatcher.actions.size)

        // The timer reads first; the unsupported server leaves the local expiry final.
        dispatcher.runNext()
        assertNull(client.snapshotSession())
        assertEquals(listOf<String>(), changes.kinds)
        assertEquals(1, dispatcher.actions.size)

        dispatcher.runNext()
        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(changes.lastExpired).wallet?.address)
    }

    @Test
    fun unsubscribedSessionExpiredListenerDoesNotReceivePendingDispatch() {
        server.enqueue(badRouteResponse())
        val scheduler = RecordingSessionExpiryScheduler()
        val dispatcher = RecordingSessionChangeDispatcher()
        var currentTime = epochMillis("2026-01-01T00:00:00Z")
        val snapshot =
            OMSWalletSessionSnapshot(
                wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
                signerAddress = TEST_CREDENTIAL_ID,
                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                expiresAt = "2026-01-01T00:02:00Z",
                auth = emailSessionAuth(),
                expiryBounds = TEST_EXPIRY_BOUNDS,
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = scheduler,
                sessionChangeDispatcher = dispatcher,
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { currentTime },
            )
        client.restoreSession(snapshot)

        val changes = mutableListOf<OMSWalletSessionChangeEvent>()
        val unsubscribe = client.onSessionChange { changes += it }

        currentTime = epochMillis("2026-01-01T00:02:00Z")
        scheduler.scheduledTasks.single().action()
        dispatcher.runNext()
        unsubscribe()
        dispatcher.runNext()

        assertEquals(listOf<String>(), changes.kinds)
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
                // The expiry is the lifetime cap, so nothing can have extended it.
                expiryBounds = OMSWalletSessionExpiryBounds(epochMillis("2026-01-01T00:00:00Z")),
            )
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(snapshot),
                credentialSigner = ThrowingClearCredentialSigner(),
                now = { epochMillis("2026-01-01T00:01:00Z") },
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )

        assertFalse(client.restorePersistedSession())

        val changes = client.recordSessionChanges()

        assertEquals("0xabc0000000000000000000000000000000000000", requireNotNull(changes.lastExpired).wallet?.address)
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

    @Test
    fun expiryTimerEndsASessionAtItsLifetimeCapWithoutARead() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-01T00:01:10Z")
            server.dispatcher = waas
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(InMemorySessionStore(), scheduler) { now }
            // A 70-second lifetime puts the expiry at the cap, which nothing extends.
            client.signInWithEmail(sessionLifetimeSeconds = 70L)
            val changes = client.recordSessionChanges()

            now = T0 + 70_000L
            scheduler.fire()

            assertEquals(emptyList<Long>(), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
            assertEquals("2026-01-01T00:01:10Z", requireNotNull(changes.lastExpired).expiredAt)
        }

    @Test
    fun walletCallPastTheLifetimeCapThrowsSessionExpiredWithoutARead() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-01T00:01:10Z")
            server.dispatcher = waas
            val client = expiryTimerClient(InMemorySessionStore(), RecordingSessionExpiryScheduler()) { now }
            client.signInWithEmail(sessionLifetimeSeconds = 70L)
            val changes = client.recordSessionChanges()

            now = T0 + 71_000L
            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.SessionExpired, (error as OMSWalletException).code)
            assertEquals(emptyList<Long>(), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
        }

    @Test
    fun expiryTimerEndsASessionAtTheCapOnceUseMovedItsExpiryThere() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(InMemorySessionStore(), scheduler) { now }
            client.signInWithEmail(sessionLifetimeSeconds = 12 * DAY_MILLIS / 1_000L)
            // Used on day 9: the read that follows moves the expiry to the cap, not 10 days later.
            now = T0 + 9 * DAY_MILLIS
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-13T00:00:00Z"))
            client.signMessage(network = Network.AMOY, message = "hello")
            assertEquals("2026-01-13T00:00:00Z", client.session?.expiresAt)
            val changes = client.recordSessionChanges()

            now = T0 + 12 * DAY_MILLIS
            scheduler.fire()

            assertEquals(listOf(T0 + 9 * DAY_MILLIS), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
        }

    @Test
    fun lifetimeCapIsPersistedAndEndsTheSessionAfterRestore() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-01T01:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }
                .signInWithEmail(sessionLifetimeSeconds = 3_600L)
            val bounds = OMSWalletSessionExpiryBounds(ceilingEpochMillis = T0 + HOUR_MILLIS)
            assertEquals(bounds, PersistedSessionRecord.decode(requireNotNull(store.record)).expiryBounds)

            now = T0 + 10 * MINUTE_MILLIS
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-01T01:00:00Z"))
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(store, scheduler) { now }
            assertTrue(client.restorePersistedSession())
            assertEquals(bounds, client.snapshotSession()?.expiryBounds)
            val changes = client.recordSessionChanges()

            now = T0 + HOUR_MILLIS
            scheduler.fire()

            assertEquals(listOf(T0 + 10 * MINUTE_MILLIS), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
        }

    @Test
    fun storedRecordWithoutLifetimeCapIsNotRestored() {
        val record = PersistedSessionRecord.encode(expiringSnapshot(TEST_SESSION_EXPIRES_AT, expiryBounds = null))
        assertFalse(record.contains("expiryCeilingEpochMillis"))
        val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = TEST_SESSION_EXPIRES_AT)
        server.dispatcher = waas
        val store = RecordBackedSessionStore(record)
        val client = expiryTimerClient(store, RecordingSessionExpiryScheduler()) { T0 }

        assertFalse(client.restorePersistedSession())

        assertNull(client.snapshotSession())
        assertNull(store.record)
        assertEquals(emptyList<Long>(), waas.credentialReadTimes)
    }

    @Test
    fun expiryTimerReadsAtExpiryWhenTheCapIsUnknown() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = TEST_SESSION_EXPIRES_AT)
            server.dispatcher = waas
            val snapshot = expiringSnapshot("2026-01-01T00:02:00Z", expiryBounds = null)
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(InMemorySessionStore(snapshot), scheduler) { now }
            client.restoreSession(snapshot)
            val changes = client.recordSessionChanges()
            waas.credentialAnswers += errorResponse(400, "KeyExpired", 7203)

            now = epochMillis("2026-01-01T00:02:00Z")
            scheduler.fire()

            assertEquals(listOf(now), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
        }

    @Test
    fun expiryTimerReadThatFailsIsRetriedWithBackoff() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(InMemorySessionStore(), scheduler) { now }
            client.signInWithEmail()
            val expiresAt = epochMillis("2026-01-11T00:00:00Z")

            now = expiresAt
            scheduler.fire()
            assertEquals(60_000L, scheduler.scheduledTasks.single().delayMillis)
            now += 60_000L
            scheduler.fire()
            assertEquals(120_000L, scheduler.scheduledTasks.single().delayMillis)

            assertEquals(listOf(expiresAt, expiresAt + 60_000L), waas.credentialReadTimes)
            assertTrue(client.snapshotSession() != null)
        }

    @Test
    fun restoreWhoseReadFailsRetriesItInTheForegroundUntilItSucceeds() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            now = T0 + 2 * DAY_MILLIS
            val restoredAt = now
            waas.credentialAnswers += errorResponse(500, "InternalError", 5000)
            waas.credentialAnswers += errorResponse(500, "InternalError", 5000)
            waas.credentialAnswers += errorResponse(400, "KeyExpired", 7203)
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(store, scheduler) { now }
            val changes = client.recordSessionChanges()
            assertTrue(client.restorePersistedSession())

            // Retries back off from 1 minute, doubling, alongside the expiry timer's task.
            now += MINUTE_MILLIS
            scheduler.scheduledTasks.single { it.delayMillis == MINUTE_MILLIS }.action()
            now += 2 * MINUTE_MILLIS
            scheduler.scheduledTasks.single { it.delayMillis == 2 * MINUTE_MILLIS }.action()

            assertEquals(listOf(restoredAt, restoredAt + MINUTE_MILLIS, restoredAt + 3 * MINUTE_MILLIS), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
            assertTrue(scheduler.scheduledTasks.none { it.delayMillis == 4 * MINUTE_MILLIS })
        }

    @Test
    fun restoreReadRetryStopsOnceAWalletCallReadsTheExpiry() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            now = T0 + 2 * DAY_MILLIS
            waas.credentialAnswers += errorResponse(500, "InternalError", 5000)
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(store, scheduler) { now }
            assertTrue(client.restorePersistedSession())
            val retry = scheduler.scheduledTasks.single { it.delayMillis == MINUTE_MILLIS }

            // A wallet call after the restore reads the expiry in the background, which succeeds.
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-13T00:00:00Z"))
            client.signMessage(network = Network.AMOY, message = "hello")
            assertEquals("2026-01-13T00:00:00Z", client.session?.expiresAt)
            val reads = waas.credentialReadTimes.size
            now += MINUTE_MILLIS
            retry.action()

            assertEquals(reads, waas.credentialReadTimes.size)
            assertTrue(scheduler.scheduledTasks.none { it.delayMillis == 2 * MINUTE_MILLIS })
        }

    @Test
    fun signOutCancelsAPendingRestoreReadRetry() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            now = T0 + 2 * DAY_MILLIS
            waas.credentialAnswers += errorResponse(500, "InternalError", 5000)
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(store, scheduler) { now }
            assertTrue(client.restorePersistedSession())
            assertTrue(scheduler.scheduledTasks.any { it.delayMillis == MINUTE_MILLIS })

            client.signOut()

            assertTrue(scheduler.scheduledTasks.isEmpty())
        }

    @Test
    fun restoreReadWaitsUntilTheAppIsInTheForeground() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            // A process started in the background (a push message or job) restores the session.
            now = T0 + 2 * DAY_MILLIS
            val foreground = ManualForegroundRunner()
            val scheduler = RecordingSessionExpiryScheduler()
            val client = expiryTimerClient(store, scheduler, foreground) { now }
            assertTrue(client.restorePersistedSession())
            assertEquals(emptyList<Long>(), waas.credentialReadTimes)
            // The expiry timer joins the read still waiting for the foreground instead of starting another.
            now = epochMillis("2026-01-11T00:00:00Z")
            scheduler.fire()
            assertEquals(emptyList<Long>(), waas.credentialReadTimes)
            assertEquals(1, foreground.pendingCount)

            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-21T00:00:00Z"))
            foreground.enterForeground()

            assertEquals(listOf(now), waas.credentialReadTimes)
            assertEquals("2026-01-21T00:00:00Z", client.session?.expiresAt)
        }

    @Test
    fun restoreReadIsSkippedWhenTheSessionChangesBeforeTheAppIsInTheForeground() =
        runBlocking {
            val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { T0 }.signInWithEmail()
            val foreground = ManualForegroundRunner()
            var refreshLaunches = 0
            val launcher =
                CredentialRefreshLauncher { block ->
                    refreshLaunches += 1
                    runBlocking { block() }
                }
            val client = expiryTimerClient(store, RecordingSessionExpiryScheduler(), foreground, launcher) { T0 }
            assertTrue(client.restorePersistedSession())

            assertEquals(1, foreground.pendingCount)

            client.signOut()
            // Signing out stops the wait, so nothing keeps the client registered.
            assertEquals(0, foreground.pendingCount)
            foreground.enterForeground()

            assertEquals(0, refreshLaunches)
            assertEquals(emptyList<Long>(), waas.credentialReadTimes)
        }

    @Test
    fun failedRestoreReadLeavesTheNextSuccessfulSignedRequestToReadTheExpiry() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            now = T0 + HOUR_MILLIS
            waas.credentialAnswers += errorResponse(500, "InternalError", 5000)
            val client = expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }
            assertTrue(client.restorePersistedSession())

            now = T0 + 2 * HOUR_MILLIS
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-11T02:00:00Z"))
            client.signMessage(network = Network.AMOY, message = "hello")

            assertEquals(listOf(T0 + HOUR_MILLIS, T0 + 2 * HOUR_MILLIS), waas.credentialReadTimes)
            assertEquals("2026-01-11T02:00:00Z", client.session?.expiresAt)
        }

    @Test
    fun routingErrorSkipsExpiryReadsForFifteenMinutesThenProbesAgain() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            // A misrouted answer to the restore read looks like a WaaS without GetCredential.
            now = T0 + HOUR_MILLIS
            waas.credentialAnswers += errorResponse(404, "WebrpcBadRoute", -2)
            val client = expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }
            assertTrue(client.restorePersistedSession())
            waas.signMessageResponse = errorResponse(401, "Unauthorized", 7207)

            // Within the cooldown, a 7207 answer does not read the credential.
            now = T0 + HOUR_MILLIS + 10 * MINUTE_MILLIS
            runCatching { client.signMessage(network = Network.AMOY, message = "hello") }
            assertEquals(listOf(T0 + HOUR_MILLIS), waas.credentialReadTimes)

            // After it, the next read probes again, and its answer clears the latch.
            now = T0 + HOUR_MILLIS + 16 * MINUTE_MILLIS
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-11T01:16:00Z"))
            runCatching { client.signMessage(network = Network.AMOY, message = "hello") }
            assertEquals(listOf(T0 + HOUR_MILLIS, now), waas.credentialReadTimes)
            assertEquals("2026-01-11T01:16:00Z", client.session?.expiresAt)
        }

    @Test
    fun restoreReadNotRunYetLeavesTheNextSuccessfulSignedRequestToReadTheExpiry() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = RecordBackedSessionStore(null)
            expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }.signInWithEmail()
            now = T0 + HOUR_MILLIS
            val client = expiryTimerClient(store, RecordingSessionExpiryScheduler(), ManualForegroundRunner()) { now }
            assertTrue(client.restorePersistedSession())

            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-11T01:00:00Z"))
            client.signMessage(network = Network.AMOY, message = "hello")

            assertEquals(listOf(T0 + HOUR_MILLIS), waas.credentialReadTimes)
        }

    @Test
    fun nonceWriteFailureDuringExpiryReadKeepsTheSession() =
        runBlocking {
            val snapshot = expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")
            val signer = NonceFailingCredentialSigner()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    now = { epochMillis("2026-01-01T00:00:01Z") },
                )
            client.restoreSession(snapshot)
            val changes = client.recordSessionChanges()
            signer.failNonce = true

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertTrue((error as OMSWalletException).code != OMSWalletErrorCode.SessionExpired)
            assertEquals(snapshot, client.snapshotSession())
            assertTrue(signer.hasCredential())
            assertEquals(listOf<String>(), changes.kinds)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun credentialRejectedWhileAWalletSwitchWaitsEndsTheSession() =
        runBlocking {
            val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val client = expiryTimerClient(InMemorySessionStore(), RecordingSessionExpiryScheduler()) { T0 }
            client.signInWithEmail()
            val changes = client.recordSessionChanges()
            waas.signMessageResponse = errorResponse(400, "KeyExpired", 7203)

            val error = client.signMessageAcrossWalletSwitch(waas) {}.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.SessionExpired, (error as OMSWalletException).code)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
        }

    @Test
    fun walletActivationKeepsTheExpiryReadDuringTheCall() =
        runBlocking {
            var now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val store = InMemorySessionStore()
            val client = expiryTimerClient(store, RecordingSessionExpiryScheduler()) { now }
            client.signInWithEmail()
            val bounds = requireNotNull(client.snapshotSession()?.expiryBounds)
            // Locally expired: the activation first reads the slid expiry from WaaS.
            now = epochMillis("2026-01-11T00:01:00Z")
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-21T00:00:00Z"))

            client.useWallet(waas.wallet.id)

            assertEquals("2026-01-21T00:00:00Z", client.session?.expiresAt)
            assertEquals("2026-01-21T00:00:00Z", store.snapshot?.expiresAt)
            assertEquals(bounds, store.snapshot?.expiryBounds)
            assertEquals(listOf(now), waas.credentialReadTimes)
        }

    @Test
    fun unauthorizedWalletCallReadsTheCredentialAndEndsARevokedSession() =
        runBlocking {
            val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val client = expiryTimerClient(InMemorySessionStore(), RecordingSessionExpiryScheduler()) { T0 }
            client.signInWithEmail()
            val changes = client.recordSessionChanges()
            waas.signMessageResponse = errorResponse(401, "Unauthorized", 7207)
            waas.credentialAnswers += errorResponse(401, "Unauthorized", 7207)

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletException).code)
            assertEquals(listOf(T0), waas.credentialReadTimes)
            assertNull(client.snapshotSession())
            assertEquals(listOf("Expired"), changes.kinds)
        }

    @Test
    fun unauthorizedWalletCallReadsTheCredentialAndKeepsAValidSession() =
        runBlocking {
            val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val client = expiryTimerClient(InMemorySessionStore(), RecordingSessionExpiryScheduler()) { T0 }
            client.signInWithEmail()
            val changes = client.recordSessionChanges()
            // A wallet access error: the credential itself is still valid.
            waas.signMessageResponse = errorResponse(401, "Unauthorized", 7207)
            waas.credentialAnswers += okJson(getCredentialResponseBody("2026-01-12T00:00:00Z"))

            runCatching { client.signMessage(network = Network.AMOY, message = "hello") }

            assertEquals(listOf(T0), waas.credentialReadTimes)
            assertEquals("2026-01-12T00:00:00Z", client.session?.expiresAt)
            assertEquals(listOf("ExpiryUpdated"), changes.kinds)
        }

    /**
     * Starts signMessage, selects the same wallet again (a new session revision with the same
     * credential) while WaaS holds the request, then runs [beforeAnswer] and lets WaaS answer.
     */
    private suspend fun WalletClient.signMessageAcrossWalletSwitch(
        waas: ExpiryTimerWaas,
        beforeAnswer: () -> Unit,
    ): Result<String> =
        coroutineScope {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            waas.onSignMessage = {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val client = this@signMessageAcrossWalletSwitch
            val pending = async(Dispatchers.IO) { runCatching { client.signMessage(network = Network.AMOY, message = "hello") } }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            waas.onSignMessage = {}
            // Requests signed with one credential go one at a time, so the switch waits for the message.
            val switching = async(Dispatchers.IO) { runCatching { client.useWallet(waas.wallet.id) } }
            beforeAnswer()
            release.countDown()
            pending.await().also { switching.await() }
        }

    @Test
    fun everySignInPathKeepsTheLifetimeCap() =
        runBlocking {
            val now = T0
            val waas = ExpiryTimerWaas(clock = { now }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas

            // Email OTP with a manual selection completed later.
            val emailStore = InMemorySessionStore()
            val emailClient = expiryTimerClient(emailStore, RecordingSessionExpiryScheduler()) { now }
            emailClient.startEmailAuth("user@example.com")
            val selection = emailClient.completeEmailAuth("123456", walletSelection = WalletSelectionBehavior.Manual)
            val pending = (selection as CompleteAuthResult.WalletSelection).pendingSelection
            val defaultBounds = OMSWalletSessionExpiryBounds(T0 + 180 * DAY_MILLIS)
            assertEquals(defaultBounds, emailClient.snapshotSession()?.expiryBounds)
            pending.selectWallet(waas.wallet.id)
            assertEquals(defaultBounds, emailStore.snapshot?.expiryBounds)

            // OIDC ID token.
            val idTokenStore = InMemorySessionStore()
            expiryTimerClient(idTokenStore, RecordingSessionExpiryScheduler()) { now }.signInWithOidcIdToken(
                idToken = fakeJwt(exp = T0 / 1_000L + 3_600L),
                issuer = "https://accounts.google.com",
                audience = "demo-web-client-id",
                sessionLifetimeSeconds = 12 * DAY_MILLIS / 1_000L,
            )
            assertEquals(
                OMSWalletSessionExpiryBounds(T0 + 12 * DAY_MILLIS),
                idTokenStore.snapshot?.expiryBounds,
            )

            // OIDC redirect callback.
            val redirectStore = InMemorySessionStore()
            val redirectClient =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = redirectStore,
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                    now = { now },
                )
            val started =
                redirectClient.startOidcRedirectAuth(
                    provider =
                        CustomOidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                            providerRedirectUri = "omsclientkotlindemo://auth/callback",
                        ),
                )
            val state = requireNotNull(queryParams(started.authorizationUrl)["state"])
            redirectClient.handleOidcRedirectCallback(
                callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=$state",
                sessionLifetimeSeconds = 11 * DAY_MILLIS / 1_000L,
            )
            assertEquals(
                OMSWalletSessionExpiryBounds(T0 + 11 * DAY_MILLIS),
                redirectStore.snapshot?.expiryBounds,
            )
        }

    @Test
    fun requestOfANewCredentialDoesNotWaitForAnOldCredentialsRequestInFlight() =
        runBlocking {
            val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val signer = RotatingKeySigner()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                    now = { T0 },
                )
            client.signInWithEmail()
            val oldCredential = signer.existingCredentialId()
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            waas.onSignMessage = {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val held = async(Dispatchers.IO) { runCatching { client.signMessage(network = Network.AMOY, message = "old") } }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            waas.onSignMessage = {}

            // Signing out drops the key, and the next sign-in creates another: its requests take the
            // new credential's turns, not the old one's held at WaaS.
            client.signOut()
            withTimeout(3_000) { client.signInWithEmail() }

            assertNotEquals(oldCredential, signer.existingCredentialId())
            assertEquals("0xsigned", withTimeout(3_000) { client.signMessage(network = Network.AMOY, message = "new") })
            release.countDown()
            assertTrue(held.await().isSuccess)
        }

    @Test
    fun awaitedExpiryReadRunsAfterTheBackgroundAttemptItSharedWasSkippedAsBusy() =
        runBlocking {
            val arrivedA = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val arrivedB = CountDownLatch(1)
            val releaseB = CountDownLatch(1)
            val signRequests = AtomicInteger()
            val credentialReads = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target == WaasApi.GetCredential.urlPath) {
                            credentialReads.incrementAndGet()
                            return okJson(getCredentialResponseBody("2026-01-11T00:00:00Z"))
                        }
                        when (signRequests.incrementAndGet()) {
                            1 -> {
                                arrivedA.countDown()
                                releaseA.await(5, TimeUnit.SECONDS)
                            }

                            2 -> {
                                arrivedB.countDown()
                                releaseB.await(5, TimeUnit.SECONDS)
                            }
                        }
                        return okJson("""{"signature":"0xsigned"}""")
                    }
                }
            var currentTime = epochMillis("2026-01-01T00:00:00Z")
            val snapshot = expiringSnapshot(expiresAt = "2026-01-05T00:00:00Z")
            val signer = RefreshPausingSigner()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(snapshot),
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher =
                        CredentialRefreshLauncher { block ->
                            thread(name = REFRESH_THREAD) { runBlocking { block() } }
                        },
                    now = { currentTime },
                )
            client.restoreSession(snapshot)
            currentTime += SIX_HOURS_MILLIS

            // A holds the turn and B waits for it.
            val a = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "a") }
            assertTrue(arrivedA.await(5, TimeUnit.SECONDS))
            val b = async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "b") }
            withTimeout(5_000) { while (client.signedRequestTurnUsers() < 2) delay(5) }
            // A's completion starts a background refresh, held before it tries for a turn; B holds the turn.
            signer.pauseNextRefresh = true
            releaseA.countDown()
            assertTrue(signer.refreshPaused.await(5, TimeUnit.SECONDS))
            assertTrue(arrivedB.await(5, TimeUnit.SECONDS))

            // C finds the session locally expired and joins the background refresh in flight.
            currentTime = epochMillis("2026-01-06T00:00:00Z")
            val c = async(start = CoroutineStart.UNDISPATCHED) { client.signMessage(network = Network.AMOY, message = "c") }
            // The background attempt finds B holding the turn and is skipped as busy, so C reads itself.
            signer.resumeRefresh.countDown()
            releaseB.countDown()

            assertEquals("0xsigned", a.await())
            assertEquals("0xsigned", b.await())
            assertEquals("0xsigned", c.await())
            assertEquals(1, credentialReads.get())
            assertEquals("2026-01-11T00:00:00Z", client.session?.expiresAt)
        }

    @Test
    fun restoredSessionPastSavedExpiryContinuesWhenTheServerExtendedIt() {
        // Requests after the last read may have extended the session on WaaS without the saved expiry
        // showing it. However long ago the saved expiry passed, the read decides while the cap has not.
        for ((restoredAt, serverExpiry) in listOf(
            "2026-01-01T04:00:00Z" to "2026-01-11T04:00:00Z",
            "2026-01-31T00:00:00Z" to "2026-02-10T00:00:00Z",
        )) {
            server.enqueueJson(getCredentialResponseBody(serverExpiry))
            val client =
                expiryTimerClient(
                    InMemorySessionStore(expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")),
                    RecordingSessionExpiryScheduler(),
                    clock = { epochMillis(restoredAt) },
                )
            val changes = client.recordSessionChanges()

            assertTrue(client.restorePersistedSession())

            assertEquals(serverExpiry, client.session?.expiresAt)
            assertEquals(listOf("ExpiryUpdated"), changes.kinds)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun restoredSessionPastItsLifetimeCapEndsWithoutARead() {
        val store =
            InMemorySessionStore(
                expiringSnapshot(
                    expiresAt = "2026-01-01T00:00:00Z",
                    expiryBounds = OMSWalletSessionExpiryBounds(epochMillis("2026-01-01T00:00:00Z")),
                ),
            )
        val signer = TrackingCredentialSigner()
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = serverEnvironment(),
                sessionStore = store,
                credentialSigner = signer,
                sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                now = { epochMillis("2026-01-01T00:01:00Z") },
                foregroundRunner = ImmediateForegroundRunner,
            )
        val changes = client.recordSessionChanges()

        assertFalse(client.restorePersistedSession())

        assertNull(client.snapshotSession())
        assertEquals(0, server.requestCount)
        assertEquals(listOf("Expired"), changes.kinds)
        assertFalse(signer.hasCredential())
        assertNotNull(store.snapshot)
    }

    @Test
    fun restoredSessionPastSavedExpiryEndsWhenTheServerEndedIt() {
        server.enqueue(errorResponse(400, "KeyExpired", 7203))
        val client =
            expiryTimerClient(
                InMemorySessionStore(expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")),
                RecordingSessionExpiryScheduler(),
                clock = { epochMillis("2026-01-01T04:00:00Z") },
            )
        val changes = client.recordSessionChanges()

        client.restorePersistedSession()

        assertNull(client.snapshotSession())
        assertEquals(1, server.requestCount)
        assertEquals(listOf("Expired"), changes.kinds)
    }

    @Test
    fun restoredSessionPastSavedExpiryEndsWhenTheServerHasNoGetCredential() {
        server.enqueue(badRouteResponse())
        val foreground = ManualForegroundRunner()
        val scheduler = RecordingSessionExpiryScheduler()
        val client =
            expiryTimerClient(
                InMemorySessionStore(expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z")),
                scheduler,
                foreground,
                clock = { epochMillis("2026-01-01T04:00:00Z") },
            )
        val changes = client.recordSessionChanges()
        assertTrue(client.restorePersistedSession())
        // The expiry timer runs before the read, which waits for the foreground.
        scheduler.fire()
        assertTrue(client.snapshotSession() != null)

        // The read finds no GetCredential, so the local expiry is final.
        foreground.enterForeground()

        assertNull(client.snapshotSession())
        assertEquals(listOf("Expired"), changes.kinds)
    }

    @Test
    fun restoredSessionPastSavedExpiryReplaysOnTheNextLaunchAfterTheServerEndedIt() {
        server.enqueue(errorResponse(400, "KeyExpired", 7203))
        val store = InMemorySessionStore(expiringSnapshot(expiresAt = "2026-01-01T00:00:00Z"))
        val signer = TrackingCredentialSigner()

        fun launch(at: String): Pair<WalletClient, () -> List<String>> {
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = store,
                    credentialSigner = signer,
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    credentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
                    now = { epochMillis(at) },
                    foregroundRunner = ImmediateForegroundRunner,
                )
            val changes = client.recordSessionChanges()
            return client to { changes.kinds }
        }
        val (first, firstChanges) = launch("2026-01-01T04:00:00Z")
        first.restorePersistedSession()
        assertNull(first.snapshotSession())
        assertEquals(listOf("Expired"), firstChanges())

        // The expiry kept the record without its key: the next launch replays it without a read.
        val (next, nextChanges) = launch("2026-01-01T05:00:00Z")
        assertFalse(next.restorePersistedSession())
        assertEquals(listOf("Expired"), nextChanges())
        assertEquals(1, server.requestCount)
        assertNotNull(store.snapshot)
    }

    @Test
    fun sessionChangesReportEachChangeOnceWithTheStateAfterIt() =
        runBlocking {
            val waas = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            server.dispatcher = waas
            val client = expiryTimerClient(InMemorySessionStore(), RecordingSessionExpiryScheduler()) { T0 }
            val changes = client.recordSessionChanges()

            client.signInWithEmail()
            val other = walletFixture("wallet-def", "0xdef0000000000000000000000000000000000000")
            waas.activatedWallet = other
            client.useWallet(other.id)
            // Activating the active wallet again changes nothing.
            client.useWallet(other.id)
            client.signOut()
            client.signOut()

            val session = OMSWalletSession(expiresAt = "2026-01-11T00:00:00Z", auth = emailSessionAuth())
            assertEquals(
                listOf(
                    OMSWalletSessionChangeEvent.SignedIn(testWallet(waas.wallet.id, waas.wallet.address), session),
                    OMSWalletSessionChangeEvent.WalletChanged(testWallet(other.id, other.address), session),
                    OMSWalletSessionChangeEvent.SignedOut,
                ),
                changes,
            )
        }

    @Test
    fun sessionChangesArriveAfterTheCallInOrderIncludingOnesAListenerCauses() =
        runBlocking {
            server.dispatcher = ExpiryTimerWaas(clock = { T0 }, credentialExpiresAt = "2026-01-11T00:00:00Z")
            val dispatcher = RecordingSessionChangeDispatcher()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                    sessionChangeDispatcher = dispatcher,
                    now = { T0 },
                )
            client.onSessionChange { if (it is OMSWalletSessionChangeEvent.SignedIn) client.signOut() }
            val second = client.recordSessionChanges()

            client.signInWithEmail()
            // Nothing is delivered during the call that made the change.
            assertEquals(listOf<String>(), second.kinds)

            while (dispatcher.actions.isNotEmpty()) dispatcher.runNext()
            assertEquals(listOf("SignedIn", "SignedOut"), second.kinds)
            assertNull(client.activeWallet)
        }

    @Test
    fun aListenerAddedAfterAChangeDoesNotReceiveIt() {
        val dispatcher = RecordingSessionChangeDispatcher()
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                sessionStore = InMemorySessionStore(activeSessionSnapshot()),
                credentialSigner = TrackingCredentialSigner(),
                sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                sessionChangeDispatcher = dispatcher,
            )
        client.restoreSession(activeSessionSnapshot())
        val early = client.recordSessionChanges()

        client.signOut()
        // Added after the sign-out happened, before it was delivered: it reads the state instead.
        val late = client.recordSessionChanges()
        while (dispatcher.actions.isNotEmpty()) dispatcher.runNext()

        assertEquals(listOf("SignedOut"), early.kinds)
        assertEquals(listOf<String>(), late.kinds)
    }

    @Test
    fun aListenerAddedInsideAnotherReceivesAnExpiryOnceThroughReplay() {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                // The expiry is the lifetime cap, so nothing can have extended it.
                sessionStore =
                    InMemorySessionStore(
                        expiringSnapshot(
                            expiresAt = "2026-01-01T00:00:00Z",
                            expiryBounds = OMSWalletSessionExpiryBounds(epochMillis("2026-01-01T00:00:00Z")),
                        ),
                    ),
                credentialSigner = TrackingCredentialSigner(),
                now = { epochMillis("2026-01-01T00:01:00Z") },
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
            )
        assertFalse(client.restorePersistedSession())
        val late = mutableListOf<OMSWalletSessionChangeEvent>()
        client.onSessionChange {
            if (it is OMSWalletSessionChangeEvent.Expired && late.isEmpty()) client.onSessionChange { change -> late += change }
        }

        assertEquals(listOf("Expired"), late.kinds)
    }

    @Test
    fun aSessionWhoseKeyAnotherSignInReplacedIsSignedOut() =
        runBlocking {
            val store = InMemorySessionStore(activeSessionSnapshot())
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(credentialIdValue = "0xnewer-credential"),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(activeSessionSnapshot())
            val changes = client.recordSessionChanges()

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.SessionMissing, (error as OMSWalletException).code)
            assertEquals(listOf("SignedOut"), changes.kinds)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun aSessionWhoseKeyIsGoneWhileItsRecordStaysEndsAsExpired() =
        runBlocking {
            // An expiry or a rejection in another process cleared the key and kept the record.
            val store = InMemorySessionStore(activeSessionSnapshot())
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = serverEnvironment(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(available = false),
                    sessionExpiryScheduler = RecordingSessionExpiryScheduler(),
                )
            client.restoreSession(activeSessionSnapshot())
            val changes = client.recordSessionChanges()

            val error = runCatching { client.signMessage(network = Network.AMOY, message = "hello") }.exceptionOrNull()

            assertEquals(OMSWalletErrorCode.SessionExpired, (error as OMSWalletException).code)
            assertEquals(listOf("Expired"), changes.kinds)
            assertEquals(activeSessionSnapshot(), store.snapshot)
            assertEquals(0, server.requestCount)
        }

    private fun expiryTimerClient(
        store: OMSWalletSessionMetadataStore,
        scheduler: RecordingSessionExpiryScheduler,
        foregroundRunner: ForegroundRunner = ImmediateForegroundRunner,
        credentialRefreshLauncher: CredentialRefreshLauncher = ImmediateCredentialRefreshLauncher,
        clock: () -> Long,
    ): WalletClient =
        WalletClient.create(
            publishableKey = "test-publishable-key",
            projectId = "test-project-id",
            environment = serverEnvironment(),
            sessionStore = store,
            credentialSigner = TrackingCredentialSigner(),
            sessionExpiryScheduler = scheduler,
            credentialRefreshLauncher = credentialRefreshLauncher,
            now = clock,
            foregroundRunner = foregroundRunner,
        )

    private fun serverEnvironment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )
}

private const val SIX_HOURS_MILLIS = 6 * 60 * 60 * 1000L
private const val MINUTE_MILLIS = 60 * 1000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private val T0 = epochMillis("2026-01-01T00:00:00Z")

/** The lifetime cap of a session signed in with a lifetime past every test clock. */
private val TEST_EXPIRY_BOUNDS = OMSWalletSessionExpiryBounds(epochMillis(TEST_SESSION_EXPIRES_AT))

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

private fun expiringSnapshot(
    expiresAt: String,
    expiryBounds: OMSWalletSessionExpiryBounds? = TEST_EXPIRY_BOUNDS,
): OMSWalletSessionSnapshot =
    OMSWalletSessionSnapshot(
        wallet = testWallet("wallet-abc", "0xabc0000000000000000000000000000000000000"),
        signerAddress = TEST_CREDENTIAL_ID,
        signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
        expiresAt = expiresAt,
        auth = emailSessionAuth(),
        expiryBounds = expiryBounds,
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

private fun RecordingSessionExpiryScheduler.fire() {
    scheduledTasks.single().action()
}

private suspend fun WalletClient.signInWithEmail(sessionLifetimeSeconds: Long = WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS) {
    startEmailAuth("user@example.com", sessionLifetimeSeconds = sessionLifetimeSeconds)
    completeEmailAuth("123456")
}

private fun okJson(body: String): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .body(body)
        .build()

/**
 * WaaS stand-in for expiry timer tests: sign-in, wallet use, and signing succeed, and each
 * GetCredential takes the next scripted answer and records the [clock] time it arrived.
 */
private class ExpiryTimerWaas(
    private val clock: () -> Long,
    private val credentialExpiresAt: String,
) : Dispatcher() {
    val wallet = walletFixture("wallet-abc", "0xabc0000000000000000000000000000000000000")

    /** The wallet UseWallet answers with; [wallet] unless a test switches to another. */
    @Volatile
    var activatedWallet = wallet
    val credentialAnswers = ConcurrentLinkedQueue<MockResponse>()
    val credentialReadTimes = CopyOnWriteArrayList<Long>()

    /** Runs while WaaS handles SignMessage, before it answers. */
    @Volatile
    var onSignMessage: () -> Unit = {}

    /** WaaS's answer to SignMessage; a signature when null. */
    @Volatile
    var signMessageResponse: MockResponse? = null

    override fun dispatch(request: RecordedRequest): MockResponse =
        when (request.target) {
            WaasApi.CommitVerifier.urlPath -> {
                okJson("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
            }

            WaasApi.CompleteAuth.urlPath -> {
                okJson(completeAuthResponseBody(wallets = listOf(wallet), credentialExpiresAt = credentialExpiresAt))
            }

            WaasApi.UseWallet.urlPath -> {
                okJson(walletResponseBody(walletId = activatedWallet.id, address = activatedWallet.address))
            }

            WaasApi.SignMessage.urlPath -> {
                onSignMessage()
                signMessageResponse ?: okJson("""{"signature":"0xsigned"}""")
            }

            WaasApi.GetCredential.urlPath -> {
                credentialReadTimes += clock()
                credentialAnswers.poll() ?: errorResponse(500, "InternalError", 5000)
            }

            else -> {
                errorResponse(404, "WebrpcBadRoute", -2)
            }
        }
}

/** Holds work until the test says the app entered the foreground. */
private class ManualForegroundRunner : ForegroundRunner {
    private val pending = mutableListOf<() -> Unit>()

    /** Work still waiting, as the Application would keep its lifecycle callbacks registered. */
    val pendingCount: Int get() = pending.size

    override fun runInForeground(action: () -> Unit): () -> Unit {
        pending += action
        return { pending -= action }
    }

    fun enterForeground() {
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it() }
    }
}

/** Fails to persist its nonce while [failNonce] is set, as the Android Keystore signer does when its write fails. */
private class NonceFailingCredentialSigner : TrackingCredentialSigner() {
    @Volatile
    var failNonce = false

    override fun nextNonce(): String {
        check(!failNonce) { "Unable to persist OMS Wallet credential nonce" }
        return super.nextNonce()
    }
}

/** Creates a new key on first use after each clear, as the Android Keystore signer does after sign-out. */
private class RotatingKeySigner : TrackingCredentialSigner() {
    private var keys = 0

    @Volatile
    private var current: String? = null

    @Synchronized
    override fun credentialId(): String = current ?: ("0x04" + (++keys).toString(16).padStart(128, '0')).also { current = it }

    override fun existingCredentialId(): String? = current

    override fun hasCredential(): Boolean = current != null

    override fun clear() {
        current = null
    }

    override fun sign(preimage: String): String = TEST_SIGNATURE
}

private const val REFRESH_THREAD = "test-background-refresh"

/** Pauses one background refresh, on [REFRESH_THREAD], where it reads the signing credential. */
private class RefreshPausingSigner : TrackingCredentialSigner() {
    @Volatile
    var pauseNextRefresh = false
    val refreshPaused = CountDownLatch(1)
    val resumeRefresh = CountDownLatch(1)

    override fun existingCredentialId(): String? {
        if (pauseNextRefresh && Thread.currentThread().name.startsWith(REFRESH_THREAD)) {
            pauseNextRefresh = false
            refreshPaused.countDown()
            resumeRefresh.await(5, TimeUnit.SECONDS)
        }
        return super.existingCredentialId()
    }
}

/** A signer whose key another sign-in (another client, or another process) can replace. */
private class ReplaceableCredentialSigner : CredentialSigner {
    override val signingAlgorithm: WalletSigningAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256

    @Volatile
    private var key: String? = TEST_CREDENTIAL_ID

    @Volatile
    var clearCalls: Int = 0
        private set

    fun replaceKey(credentialId: String) {
        key = credentialId
    }

    override fun credentialId(): String = key ?: TEST_CREDENTIAL_ID.also { key = it }

    override fun existingCredentialId(): String? = key

    override fun nextNonce(): String = "1710000107"

    override fun sign(preimage: String): String = TEST_SIGNATURE

    override fun hasCredential(): Boolean = key != null

    override fun clear() {
        clearCalls += 1
        key = null
    }
}
