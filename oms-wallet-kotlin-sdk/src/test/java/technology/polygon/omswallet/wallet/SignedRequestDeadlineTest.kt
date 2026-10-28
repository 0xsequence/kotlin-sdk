package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletRequestException
import technology.polygon.omswallet.OMSWalletTransactionException
import technology.polygon.omswallet.models.SendTransactionRequest
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import java.math.BigInteger
import java.security.KeyStoreException
import java.util.concurrent.TimeUnit

class SignedRequestDeadlineTest {
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
    fun executeThatFailsToSignIsARequestFailureNotAnUnconfirmedExecution() =
        runBlocking {
            server.enqueueJson(SPONSORED_PREPARE)
            // Prepare signs once; the Execute signature fails before its request is sent.
            val client = walletClient(SigningAtCallSigner { call -> if (call == 2) throw KeyStoreException("keystore unavailable") })

            val error = runCatching { client.sendTransaction(Network.POLYGON, TRANSFER) }.exceptionOrNull()

            assertTrue("got $error", error is OMSWalletRequestException)
            assertEquals(OMSWalletErrorCode.RequestFailed, (error as OMSWalletRequestException).code)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun signatureFinishedAfterTheDeadlineIsNeverSent() =
        runBlocking {
            server.enqueueJson(SPONSORED_PREPARE)
            val client =
                walletClient(
                    SigningAtCallSigner { call -> if (call == 2) Thread.sleep(TIMEOUT_MILLIS + 300) },
                    signedRequestTimeoutMillis = TIMEOUT_MILLIS,
                )

            val error = runCatching { client.sendTransaction(Network.POLYGON, TRANSFER) }.exceptionOrNull()

            assertTrue("got $error", error is OMSWalletRequestException)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun executeThatOutlivesTheDeadlineAfterSendingStaysUnconfirmed() =
        runBlocking {
            server.enqueueJson(SPONSORED_PREPARE)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .headersDelay(TIMEOUT_MILLIS + 2_000, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val client = walletClient(SigningAtCallSigner { }, signedRequestTimeoutMillis = TIMEOUT_MILLIS)
            val startedAt = System.nanoTime()

            val error = runCatching { client.sendTransaction(Network.POLYGON, TRANSFER) }.exceptionOrNull()

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("got $error", error is OMSWalletTransactionException)
            error as OMSWalletTransactionException
            assertEquals(OMSWalletErrorCode.TransactionExecutionUnconfirmed, error.code)
            assertEquals("txn-deadline", error.txnId)
            assertTrue("took $elapsedMillis ms", elapsedMillis < TIMEOUT_MILLIS + 1_500)
            assertEquals(2, server.requestCount)
        }

    private fun walletClient(
        signer: CredentialSigner,
        signedRequestTimeoutMillis: Long = 30_000L,
    ): WalletClient =
        restoredTestWalletClient(
            environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                ),
            wallet = testWallet("wallet-main", "0x9999999999999999999999999999999999999999", WalletType.Ethereum),
            nonceValue = NONCE,
            credentialSigner = signer,
            signedRequestTimeoutMillis = signedRequestTimeoutMillis,
        )

    /** Runs [onSign] with the 1-based number of each signature before signing. */
    private class SigningAtCallSigner(
        private val onSign: (call: Int) -> Unit,
    ) : TrackingCredentialSigner(nonceValue = NONCE) {
        private var calls = 0

        override fun sign(preimage: String): String {
            calls += 1
            onSign(calls)
            return super.sign(preimage)
        }
    }

    private companion object {
        const val NONCE: String = "1710000400"
        const val TIMEOUT_MILLIS: Long = 300L
        const val SPONSORED_PREPARE: String =
            """{"txnId":"txn-deadline","status":"quoted","feeOptions":[],"sponsored":true,"expiresAt":"2099-01-01T00:00:00Z"}"""
        val TRANSFER = SendTransactionRequest(to = "0x1111111111111111111111111111111111111111", value = BigInteger.ZERO)
    }
}
