package com.omsclient.kotlin_sdk.network

import com.omsclient.kotlin_sdk.OMSClient
import com.omsclient.kotlin_sdk.OMSClientNetworks
import com.omsclient.kotlin_sdk.generated.waas.WebRpcError
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServiceClientsTest {
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
    fun walletSignatureValidationUsesGeneratedPublicClient() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"isValid":true}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"isValid":false}""")
                    .build(),
            )

            val environment =
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client = OMSClient(projectAccessKey = "test-access-key", environment = environment)
            client.wallet.restoreSession(
                OMSClientSessionSnapshot(
                    walletId = "wallet-id",
                    walletAddress = "0xwallet",
                ),
            )

            val messageIsValid =
                client.wallet.isValidMessageSignature(
                    network = OMSClientNetworks.requireSupported("80002"),
                    message = "hello",
                    signature = "0xmessage",
                )
            val messageRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/WalletPublic/IsValidMessageSignature", messageRequest.target)
            assertEquals("test-access-key", messageRequest.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals(null, messageRequest.headers["Authorization"])
            assertEquals(null, messageRequest.headers[OMSClientEnvironment.walletSignatureHeaderName])
            assertEquals(
                """{"network":"80002","walletId":"wallet-id","message":"hello","signature":"0xmessage"}""",
                requireNotNull(messageRequest.body).utf8(),
            )
            assertEquals(true, messageIsValid)

            val typedDataIsValid =
                client.wallet.isValidTypedDataSignature(
                    network = OMSClientNetworks.requireSupported("80002"),
                    typedData =
                        buildJsonObject {
                            put("contents", "hello")
                        },
                    signature = "0xtyped",
                )
            val typedDataRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/WalletPublic/IsValidTypedDataSignature", typedDataRequest.target)
            assertEquals("test-access-key", typedDataRequest.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals(null, typedDataRequest.headers["Authorization"])
            assertEquals(null, typedDataRequest.headers[OMSClientEnvironment.walletSignatureHeaderName])
            assertEquals(
                """{"network":"80002","walletId":"wallet-id","typedData":{"contents":"hello"},"signature":"0xtyped"}""",
                requireNotNull(typedDataRequest.body).utf8(),
            )
            assertEquals(false, typedDataIsValid)
        }

    @Test
    fun getTokenBalancesParsesIndexerResponse() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "page": {"page": 1, "pageSize": 25, "more": false},
                          "balances": [
                            {
                              "contractType": "ERC20",
                              "contractAddress": "0xcontract",
                              "accountAddress": "0xwallet",
                              "tokenID": "0",
                              "balance": "1000",
                              "blockHash": "0xhash",
                              "blockNumber": 12345,
                              "chainId": 137
                            }
                          ]
                        }
                        """.trimIndent(),
                    ).build(),
            )

            val template = server.url("/polygon/rpc/Indexer/").toString().replace("/polygon/", "/{value}/")
            val environment =
                OMSClientEnvironment(
                    indexerUrlTemplate = template,
                )
            val client = IndexerClient("test-access-key", environment, OMSClientHttpClient())

            val response =
                client.getTokenBalances(
                    network = OMSClientNetworks.requireSupported("137"),
                    contractAddress = "0xcontract",
                    walletAddress = "0xwallet",
                    includeMetadata = true,
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/polygon/rpc/Indexer/GetTokenBalances", request.target)
            assertEquals("test-access-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals(
                "{\"page\":{\"page\":0,\"pageSize\":40,\"more\":false},\"contractAddress\":\"0xcontract\",\"accountAddress\":\"0xwallet\",\"includeMetadata\":true}",
                requireNotNull(request.body).utf8(),
            )
            assertEquals(1, response.page?.page)
            assertEquals(25, response.page?.pageSize)
            assertEquals(false, response.page?.more)
            assertEquals(1, response.balances.size)
            assertEquals("1000", response.balances.single().balance)
            assertEquals(137L, response.balances.single().chainId)
        }

    @Test
    fun getTokenBalancesTreatsNullPageAndBalancesAsEmpty() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"page":null,"balances":null}""")
                    .build(),
            )

            val template = server.url("/polygon/rpc/Indexer/").toString().replace("/polygon/", "/{value}/")
            val environment =
                OMSClientEnvironment(
                    indexerUrlTemplate = template,
                )
            val client = IndexerClient("test-access-key", environment, OMSClientHttpClient())

            val response =
                client.getTokenBalances(
                    network = OMSClientNetworks.requireSupported("137"),
                    contractAddress = "0xcontract",
                    walletAddress = "0xwallet",
                    includeMetadata = true,
                )

            assertEquals(null, response.page)
            assertTrue(response.balances.isEmpty())
        }

    @Test
    fun getNativeTokenBalanceParsesIndexerResponse() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "balance": {
                            "accountAddress": "0xwallet",
                            "chainId": 137,
                            "name": "POL",
                            "symbol": "POL",
                            "balance": "123",
                            "balanceUSD": "1"
                          }
                        }
                        """.trimIndent(),
                    ).build(),
            )

            val template = server.url("/polygon/rpc/Indexer/").toString().replace("/polygon/", "/{value}/")
            val environment =
                OMSClientEnvironment(
                    indexerUrlTemplate = template,
                )
            val client = IndexerClient("test-access-key", environment, OMSClientHttpClient())

            val response =
                client.getNativeTokenBalance(
                    network = OMSClientNetworks.requireSupported("137"),
                    walletAddress = "0xwallet",
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/polygon/rpc/Indexer/GetNativeTokenBalance", request.target)
            assertEquals(
                "{\"accountAddress\":\"0xwallet\"}",
                requireNotNull(request.body).utf8(),
            )
            assertEquals("NATIVE", response?.contractType)
            assertEquals("123", response?.balance)
            assertEquals(137L, response?.chainId)
        }

    @Test
    fun generatedWalletPublicErrorMessageDoesNotIncludeRawBody() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .body("""{"detail":"sensitive backend context"}""")
                    .build(),
            )

            val client =
                OMSClient(
                    projectAccessKey = "test-access-key",
                    environment = OMSClientEnvironment(walletApiUrl = server.url("/rpc/Wallet/").toString()),
                )
            client.wallet.restoreSession(
                OMSClientSessionSnapshot(
                    walletId = "wallet-id",
                    walletAddress = "0xwallet",
                ),
            )

            val failure =
                runCatching {
                    client.wallet.isValidMessageSignature(
                        network = OMSClientNetworks.requireSupported("80002"),
                        message = "hello",
                        signature = "0xsig",
                    )
                }.exceptionOrNull() as? WebRpcError

            requireNotNull(failure)
            assertEquals("endpoint error", failure.message)
            assertEquals(400, failure.status)
            assertFalse(requireNotNull(failure.message).contains("sensitive backend context"))
        }
}
