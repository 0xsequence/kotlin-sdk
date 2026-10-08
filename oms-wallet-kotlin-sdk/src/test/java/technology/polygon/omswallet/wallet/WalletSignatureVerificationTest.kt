package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletSessionException
import technology.polygon.omswallet.OMSWalletValidationException
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot

class WalletSignatureVerificationTest {
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
    fun verifiesExplicitWalletAddressesWhileSignedOutWithoutWalletId() =
        runBlocking {
            repeat(VerificationMethod.entries.size) { enqueueJson("""{"isValid":true}""") }
            val client = signedOutWalletClient()

            VerificationMethod.entries.forEach { method ->
                assertTrue(method.verify(client, method.address))
                val request = requireNotNull(server.takeRequest())

                assertEquals(method.path, request.target)
                assertNull(request.headers["Authorization"])
                assertEquals(method.expectedBody(method.address), request.jsonBody())
                assertFalse(request.jsonBody().containsKey("walletId"))
            }
            assertNull(client.activeWallet)
        }

    @Test
    fun verifiesAgainstActiveWalletAddressWhenAddressIsOmitted() =
        runBlocking {
            VerificationMethod.entries.forEach { method ->
                enqueueJson("""{"isValid":false}""")
                val client = activeWalletClient(testWallet("wallet-${method.name}", method.address, method.walletType))

                assertFalse(method.verify(client, null))
                val request = requireNotNull(server.takeRequest())

                assertEquals(method.path, request.target)
                assertEquals(method.expectedBody(method.address), request.jsonBody())
                assertFalse(request.jsonBody().containsKey("walletId"))
            }
        }

    @Test
    fun rejectsOmittedAddressWithoutSessionBeforeAnyRequest() =
        runBlocking {
            val client = signedOutWalletClient()

            VerificationMethod.entries.forEach { method ->
                val failure = runCatching { method.verify(client, null) }.exceptionOrNull()

                assertTrue("expected session error for ${method.operation.id}, got $failure", failure is OMSWalletSessionException)
                failure as OMSWalletSessionException
                assertEquals(OMSWalletErrorCode.SessionMissing, failure.code)
                assertEquals(method.operation, failure.operation)
                assertNull(failure.upstreamError)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun rejectsOmittedAddressForActiveWalletOfAnotherFamilyBeforeAnyRequest() =
        runBlocking {
            val activeWallets =
                listOf(
                    testWallet("wallet-eth", ETHEREUM_ADDRESS, WalletType.Ethereum),
                    testWallet("wallet-sol", SOLANA_ADDRESS, WalletType.Solana),
                    testWallet("wallet-tron", TRON_ADDRESS, WalletType.Tron),
                )

            activeWallets.forEach { wallet ->
                val client = activeWalletClient(wallet)
                VerificationMethod.entries
                    .filter { it.walletType != wallet.type }
                    .forEach { method ->
                        val failure = runCatching { method.verify(client, null) }.exceptionOrNull()

                        assertTrue(
                            "expected validation error for ${method.operation.id} with ${wallet.type}, got $failure",
                            failure is OMSWalletValidationException,
                        )
                        failure as OMSWalletValidationException
                        assertEquals(OMSWalletErrorCode.ValidationError, failure.code)
                        assertEquals(method.operation, failure.operation)
                        assertNull(failure.upstreamError)
                    }
            }
            assertEquals(0, server.requestCount)
        }

    private enum class VerificationMethod(
        val walletType: WalletType,
        val address: String,
        val operation: OMSWalletOperation,
        val path: String,
    ) {
        EvmMessage(
            WalletType.Ethereum,
            ETHEREUM_ADDRESS,
            OMSWalletOperation.WalletIsValidMessageSignature,
            "/v1/WaasPublic/IsValidMessageSignature",
        ) {
            override suspend fun verify(
                client: WalletClient,
                walletAddress: String?,
            ): Boolean = client.isValidMessageSignature(Network.AMOY, "hello", "0xsig", walletAddress = walletAddress)

            override fun expectedBody(walletAddress: String): JsonObject =
                buildJsonObject {
                    put("network", "80002")
                    put("networkFamily", "evm")
                    put("walletAddress", walletAddress)
                    put("message", "hello")
                    put("signature", "0xsig")
                }
        },
        EvmTypedData(
            WalletType.Ethereum,
            ETHEREUM_ADDRESS,
            OMSWalletOperation.WalletIsValidTypedDataSignature,
            "/v1/WaasPublic/IsValidTypedDataSignature",
        ) {
            override suspend fun verify(
                client: WalletClient,
                walletAddress: String?,
            ): Boolean = client.isValidTypedDataSignature(Network.AMOY, TYPED_DATA, "0xsig", walletAddress = walletAddress)

            override fun expectedBody(walletAddress: String): JsonObject =
                buildJsonObject {
                    put("network", "80002")
                    put("networkFamily", "evm")
                    put("walletAddress", walletAddress)
                    put("typedData", TYPED_DATA)
                    put("signature", "0xsig")
                }
        },
        SolanaMessage(
            WalletType.Solana,
            SOLANA_ADDRESS,
            OMSWalletOperation.WalletIsValidSolanaMessageSignature,
            "/v1/WaasPublic/IsValidMessageSignature",
        ) {
            override suspend fun verify(
                client: WalletClient,
                walletAddress: String?,
            ): Boolean = client.isValidSolanaMessageSignature("hello", "solana-sig", walletAddress = walletAddress)

            override fun expectedBody(walletAddress: String): JsonObject =
                buildJsonObject {
                    put("networkFamily", "solana")
                    put("walletAddress", walletAddress)
                    put("message", "hello")
                    put("signature", "solana-sig")
                }
        },
        TronMessage(
            WalletType.Tron,
            TRON_ADDRESS,
            OMSWalletOperation.WalletIsValidTronMessageSignature,
            "/v1/WaasPublic/IsValidMessageSignature",
        ) {
            override suspend fun verify(
                client: WalletClient,
                walletAddress: String?,
            ): Boolean = client.isValidTronMessageSignature("hello", "0xsig", walletAddress = walletAddress)

            override fun expectedBody(walletAddress: String): JsonObject =
                buildJsonObject {
                    put("networkFamily", "tron")
                    put("walletAddress", walletAddress)
                    put("message", "hello")
                    put("signature", "0xsig")
                }
        },
        TronTypedData(
            WalletType.Tron,
            TRON_ADDRESS,
            OMSWalletOperation.WalletIsValidTronTypedDataSignature,
            "/v1/WaasPublic/IsValidTypedDataSignature",
        ) {
            override suspend fun verify(
                client: WalletClient,
                walletAddress: String?,
            ): Boolean = client.isValidTronTypedDataSignature(TYPED_DATA, "0xsig", walletAddress = walletAddress)

            override fun expectedBody(walletAddress: String): JsonObject =
                buildJsonObject {
                    put("networkFamily", "tron")
                    put("walletAddress", walletAddress)
                    put("typedData", TYPED_DATA)
                    put("signature", "0xsig")
                }
        },
        ;

        abstract suspend fun verify(
            client: WalletClient,
            walletAddress: String?,
        ): Boolean

        abstract fun expectedBody(walletAddress: String): JsonObject
    }

    private fun signedOutWalletClient(): WalletClient =
        WalletClient.create(
            publishableKey = "test-publishable-key",
            projectId = "test-project-id",
            environment = environment(),
            transport = OMSWalletHttpClient(),
        )

    private fun activeWalletClient(wallet: Wallet): WalletClient {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = environment(),
                transport = OMSWalletHttpClient(),
                sessionStore =
                    InMemorySessionStore(
                        snapshot =
                            OMSWalletSessionSnapshot(
                                wallet = wallet,
                                expiresAt = TEST_SESSION_EXPIRES_AT,
                                signerAddress = TEST_CREDENTIAL_ID,
                                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                auth = emailSessionAuth(),
                            ),
                    ),
                credentialSigner = TrackingCredentialSigner(nonceValue = "1710000400"),
            )
        assertTrue(client.restorePersistedSession())
        return client
    }

    private fun environment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(body)
                .build(),
        )
    }

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(requireNotNull(body).utf8()).jsonObject

    private companion object {
        const val ETHEREUM_ADDRESS: String = "0x9999999999999999999999999999999999999999"
        const val SOLANA_ADDRESS: String = "4Nd1mYQbqjVU2aR7cJNPyqW9XjHnBYvWQd7ZxYxvT6uP"
        const val TRON_ADDRESS: String = "TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL"
        val TYPED_DATA: JsonObject = buildJsonObject { put("primaryType", "Mail") }
    }
}
