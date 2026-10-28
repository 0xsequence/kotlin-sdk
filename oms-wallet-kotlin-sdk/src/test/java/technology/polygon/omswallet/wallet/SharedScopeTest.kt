package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWallet
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import java.util.Collections
import java.util.UUID

class SharedScopeTest {
    private lateinit var server: MockWebServer
    private val apiKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var rejectCredential = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    apiKeys += requireNotNull(request.headers[OMSWalletEnvironment.accessKeyHeaderName])
                    return if (rejectCredential) {
                        MockResponse
                            .Builder()
                            .code(400)
                            .body("""{"error":"KeyExpired","code":7203,"msg":"expired","status":400}""")
                            .build()
                    } else {
                        MockResponse
                            .Builder()
                            .code(200)
                            .body("""{"signature":"0xsigned"}""")
                            .build()
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun sessionScopeIsTheWalletApiOriginAndProjectNotTheKey() {
        val scope = OMSWallet.scopedSessionSuffix("pk_sdbx_01m2mwxcn8p59_01m2n33tshe52vt5jyjyt0kc6g")

        // A rotated key for the same project keeps the session.
        assertEquals(scope, OMSWallet.scopedSessionSuffix("pk_sdbx_01m2mwxcn8p59_01m3rotatedkey0000000000000"))
        // Another project, or another environment, is another session.
        assertNotEquals(scope, OMSWallet.scopedSessionSuffix("pk_sdbx_01m9otherproject_01m2n33tshe52vt5jyjyt0kc6g"))
        assertNotEquals(scope, OMSWallet.scopedSessionSuffix("pk_live_01m2mwxcn8p59_01m2n33tshe52vt5jyjyt0kc6g"))
    }

    @Test
    fun clientsOfOneScopeShareTheSessionAndSendTheNewestKey() =
        runBlocking {
            val scopeKey = "shared-scope-test-${UUID.randomUUID()}"
            val dispatcher = RecordingSessionExpiryDispatcher()
            val first =
                WalletClient
                    .create(
                        publishableKey = "pk_old",
                        projectId = "test-project-id",
                        environment = environment(),
                        sessionStore = InMemorySessionStore(restoredSnapshot()),
                        credentialSigner = TrackingCredentialSigner(),
                        sessionExpiryDispatcher = dispatcher,
                        credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                        projectScopeKey = scopeKey,
                    ).also { it.restorePersistedSession() }
            // The same project with a rotated key, created later in the process.
            val second =
                WalletClient.create(
                    publishableKey = "pk_new",
                    projectId = "test-project-id",
                    environment = environment(),
                    projectScopeKey = scopeKey,
                )
            val otherScope =
                WalletClient.create(
                    publishableKey = "pk_old",
                    projectId = "other-project-id",
                    environment = environment(),
                    projectScopeKey = "shared-scope-test-${UUID.randomUUID()}",
                )

            assertNotNull(first.activeWallet)
            assertEquals(first.activeWallet, second.activeWallet)
            assertNull(otherScope.activeWallet)

            // Every client of the scope sends the newest key; the session is kept.
            first.signMessage(network = Network.AMOY, message = "hello")
            second.signMessage(network = Network.AMOY, message = "hello")
            assertEquals(listOf("pk_new", "pk_new"), apiKeys)

            // A session one client ends is ended for all, and their listeners hear it.
            var heardBySecond = 0
            second.onSessionExpired { heardBySecond += 1 }
            rejectCredential = true
            runCatching { first.signMessage(network = Network.AMOY, message = "hello") }
            while (dispatcher.actions.isNotEmpty()) dispatcher.runNext()

            assertEquals(1, heardBySecond)
            assertNull(first.activeWallet)
            assertNull(second.activeWallet)
        }

    private fun environment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )

    private fun restoredSnapshot(): OMSWalletSessionSnapshot =
        OMSWalletSessionSnapshot(
            wallet = testWallet("wallet-main", "0x9999999999999999999999999999999999999999", WalletType.Ethereum),
            expiresAt = TEST_SESSION_EXPIRES_AT,
            signerAddress = TEST_CREDENTIAL_ID,
            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
            auth = emailSessionAuth(),
        )
}
