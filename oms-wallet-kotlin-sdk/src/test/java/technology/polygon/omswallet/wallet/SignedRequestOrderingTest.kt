package technology.polygon.omswallet.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletRequestException
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SignedRequestOrderingTest {
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
    fun concurrentSignedCallsGoOutOneAtATimeAndNoneIsRejectedForItsNonce() =
        runBlocking {
            val waas = ConditionalWriteWaas()
            server.dispatcher = waas
            val client = walletClient(IncreasingNonceSigner())

            val signatures =
                List(8) { index ->
                    async(Dispatchers.IO) { client.signMessage(network = Network.AMOY, message = "message $index") }
                }.awaitAll()

            assertEquals(List(8) { "0xsigned" }, signatures)
            // One request at a time, in nonce order, so WaaS rejected none and nothing was resent.
            assertEquals(1, waas.maxInFlight.get())
            assertEquals(0, waas.rejectedNonces.get())
            assertEquals(8, waas.acceptedNonces.size)
            assertEquals(waas.acceptedNonces.sorted(), waas.acceptedNonces)
        }

    @Test
    fun requestWaitingForItsTurnPastItsDeadlineFailsUnsentWithoutWaiting() =
        runBlocking {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val waas =
                ConditionalWriteWaas(onRequest = {
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                })
            server.dispatcher = waas
            // Two clients of one scope share its turns; the second has a short deadline.
            val scopeKey = "ordering-test-${UUID.randomUUID()}"
            val holding = walletClient(IncreasingNonceSigner(), projectScopeKey = scopeKey)
            val waiting =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment(),
                    projectScopeKey = scopeKey,
                    signedRequestTimeoutMillis = 300,
                )

            val held = async(Dispatchers.IO) { holding.signMessage(network = Network.AMOY, message = "held") }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            waas.onRequest = {}
            val startedAt = System.nanoTime()
            val error = runCatching { waiting.signMessage(network = Network.AMOY, message = "waiting") }.exceptionOrNull()
            val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            release.countDown()

            assertTrue("got $error", error is OMSWalletRequestException)
            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletRequestException).code)
            assertTrue("waited $waitedMillis ms", waitedMillis < 1_000)
            assertEquals("0xsigned", held.await())
            // The waiting request never got a turn, so it was never sent.
            assertEquals(1, server.requestCount)
            assertEquals("0xsigned", waiting.signMessage(network = Network.AMOY, message = "later"))
        }

    private fun environment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )

    private fun walletClient(
        signer: CredentialSigner,
        projectScopeKey: String? = null,
    ): WalletClient =
        WalletClient
            .create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = environment(),
                sessionStore =
                    InMemorySessionStore(
                        OMSWalletSessionSnapshot(
                            wallet = testWallet("wallet-main", "0x9999999999999999999999999999999999999999", WalletType.Ethereum),
                            expiresAt = TEST_SESSION_EXPIRES_AT,
                            signerAddress = TEST_CREDENTIAL_ID,
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                            auth = emailSessionAuth(),
                        ),
                    ),
                credentialSigner = signer,
                credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                projectScopeKey = projectScopeKey,
            ).also { assertTrue(it.restorePersistedSession()) }

    /**
     * Answers SignMessage like WaaS's credential middleware: a nonce not above the last accepted one
     * is rejected with 7206 (the conditional write `:nonce > LastNonce`). Each request takes a moment,
     * so overlapping requests show up in [maxInFlight].
     */
    private class ConditionalWriteWaas(
        @Volatile var onRequest: () -> Unit = {},
    ) : Dispatcher() {
        private val inFlight = AtomicInteger()
        private val lastNonce = AtomicLong(0)
        val maxInFlight = AtomicInteger()
        val rejectedNonces = AtomicInteger()
        val acceptedNonces: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override fun dispatch(request: RecordedRequest): MockResponse {
            val current = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(current) { a, b -> maxOf(a, b) }
            try {
                onRequest()
                Thread.sleep(30)
                val nonce =
                    requireNotNull(
                        Regex("nonce=(\\d+)").find(requireNotNull(request.headers[OMSWalletEnvironment.walletSignatureHeaderName])),
                    ).groupValues[1]
                        .toLong()
                synchronized(this) {
                    if (nonce <= lastNonce.get()) {
                        rejectedNonces.incrementAndGet()
                        return MockResponse
                            .Builder()
                            .code(412)
                            .body("""{"error":"PreconditionFailed","code":7206,"msg":"stale nonce","status":412}""")
                            .build()
                    }
                    lastNonce.set(nonce)
                    acceptedNonces += nonce
                }
                return MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"signature":"0xsigned"}""")
                    .build()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** Draws nonces from one increasing source, like the Android Keystore nonce store. */
    private class IncreasingNonceSigner : TrackingCredentialSigner() {
        private val nonce = AtomicLong(1_000)

        override fun nextNonce(): String = nonce.incrementAndGet().toString()

        override fun sign(preimage: String): String = TEST_SIGNATURE
    }
}
