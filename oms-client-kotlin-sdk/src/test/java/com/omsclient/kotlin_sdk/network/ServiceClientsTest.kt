package com.omsclient.kotlin_sdk.network

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClient
import com.omsclient.kotlin_sdk.OMSClientEmailSessionAuth
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkException
import com.omsclient.kotlin_sdk.OmsSdkOperation
import com.omsclient.kotlin_sdk.OmsUpstreamService
import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.models.TokenBalancesPageRequest
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
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                )
            val client =
                OMSClient(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                )
            client.wallet.restoreSession(
                OMSClientSessionSnapshot(
                    walletId = "wallet-id",
                    walletAddress = "0xwallet",
                    auth = OMSClientEmailSessionAuth(email = "user@example.com"),
                ),
            )

            val messageIsValid =
                client.wallet.isValidMessageSignature(
                    network = Network.AMOY,
                    message = "hello",
                    signature = "0xmessage",
                )
            val messageRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/WaasPublic/IsValidMessageSignature", messageRequest.target)
            assertEquals("test-publishable-key", messageRequest.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals(null, messageRequest.headers["Authorization"])
            assertEquals(null, messageRequest.headers[OMSClientEnvironment.walletSignatureHeaderName])
            assertEquals(
                """{"network":"80002","walletId":"wallet-id","message":"hello","signature":"0xmessage"}""",
                requireNotNull(messageRequest.body).utf8(),
            )
            assertEquals(true, messageIsValid)

            val typedDataIsValid =
                client.wallet.isValidTypedDataSignature(
                    network = Network.AMOY,
                    typedData =
                        buildJsonObject {
                            put("contents", "hello")
                        },
                    signature = "0xtyped",
                )
            val typedDataRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/WaasPublic/IsValidTypedDataSignature", typedDataRequest.target)
            assertEquals("test-publishable-key", typedDataRequest.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals(null, typedDataRequest.headers["Authorization"])
            assertEquals(null, typedDataRequest.headers[OMSClientEnvironment.walletSignatureHeaderName])
            assertEquals(
                """{"network":"80002","walletId":"wallet-id","typedData":{"contents":"hello"},"signature":"0xtyped"}""",
                requireNotNull(typedDataRequest.body).utf8(),
            )
            assertEquals(false, typedDataIsValid)
        }

    @Test
    fun getBalancesRequestsIndexerGatewayAndFlattensGroupedResults() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "page": {"page": 1, "pageSize": 25, "more": false},
                          "nativeBalances": [
                            {
                              "chainId": 137,
                              "results": [
                                {
                                  "accountAddress": "0xwallet",
                                  "chainId": 137,
                                  "name": "Polygon",
                                  "symbol": "POL",
                                  "balance": "1000000000000000000",
                                  "balanceUSD": "0.20",
                                  "priceUSD": "0.20"
                                }
                              ]
                            }
                          ],
                          "balances": [
                            {
                              "chainId": 137,
                              "results": [
                                {
                                  "contractType": "ERC20",
                                  "contractAddress": "0xcontract",
                                  "accountAddress": "0xwallet",
                                  "tokenID": "0",
                                  "balance": "141799",
                                  "balanceUSD": "0.141799",
                                  "priceUSD": "1",
                                  "chainId": 137,
                                  "contractInfo": {
                                    "name": "USDC",
                                    "symbol": "USDC",
                                    "decimals": 6
                                  }
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    ).build(),
            )

            val environment =
                OMSClientEnvironment(
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client = IndexerClient("test-publishable-key", environment, OMSClientHttpClient())

            val response =
                client.getBalances(
                    networks = listOf(Network.POLYGON),
                    contractAddresses = listOf("0xcontract"),
                    walletAddress = "0xwallet",
                    includeMetadata = true,
                    page = TokenBalancesPageRequest(page = 1, pageSize = 25),
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/v1/IndexerGateway/GetTokenBalancesDetails", request.target)
            assertEquals("test-publishable-key", request.headers["Api-Key"])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(null, request.headers["Origin"])
            assertEquals(
                "webrpc@v0.31.2;gen-typescript@v0.23.1;sequence-indexer@v0.4.0",
                request.headers["Webrpc"],
            )
            assertEquals(null, request.headers["X-Access-Key"])
            assertEquals(
                "{\"chainIds\":[137],\"filter\":{\"accountAddresses\":[\"0xwallet\"],\"contractWhitelist\":[\"0xcontract\"],\"omitNativeBalances\":false},\"omitMetadata\":false,\"page\":{\"page\":1,\"pageSize\":25}}",
                requireNotNull(request.body).utf8(),
            )
            assertEquals(1, response.page?.page)
            assertEquals(25, response.page?.pageSize)
            assertEquals(false, response.page?.more)
            assertEquals(1, response.nativeBalances.size)
            val nativeBalance = response.nativeBalances.single()
            assertEquals("NATIVE", nativeBalance.contractType)
            assertEquals("Polygon", nativeBalance.name)
            assertEquals("POL", nativeBalance.symbol)
            assertEquals("1000000000000000000", nativeBalance.balance)
            assertEquals("0.20", nativeBalance.balanceUSD)
            assertEquals(1, response.balances.size)
            val balance = response.balances.single()
            assertEquals("0", balance.tokenId)
            assertEquals("141799", balance.balance)
            assertEquals("USDC", balance.contractInfo?.symbol)
            assertEquals(6, balance.contractInfo?.decimals)
        }

    @Test
    fun getBalancesDefaultsToMainnetsWhenNetworksAreOmitted() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"page":{"page":0,"pageSize":40,"more":false},"nativeBalances":[],"balances":[]}""")
                    .build(),
            )

            val environment =
                OMSClientEnvironment(
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client = IndexerClient("test-publishable-key", environment, OMSClientHttpClient())

            val response =
                client.getBalances(
                    walletAddress = "0xwallet",
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals(
                "{\"networkType\":\"MAINNETS\",\"filter\":{\"accountAddresses\":[\"0xwallet\"],\"omitNativeBalances\":false},\"omitMetadata\":false,\"page\":{\"page\":0,\"pageSize\":40}}",
                requireNotNull(request.body).utf8(),
            )
            assertTrue(response.nativeBalances.isEmpty())
            assertTrue(response.balances.isEmpty())
        }

    @Test
    fun getTransactionHistoryRequestsIndexerGatewayAndMapsWireFields() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "page": {"page": 0, "pageSize": 1, "more": true},
                          "transactions": [
                            {
                              "chainId": 1,
                              "results": [
                                {
                                  "txnHash": "0xabc",
                                  "blockNumber": 123,
                                  "blockHash": "0xdef",
                                  "chainId": 1,
                                  "metaTxnID": "meta-1",
                                  "transfers": [
                                    {
                                      "transferType": "RECEIVE",
                                      "contractAddress": "0x0000000000000000000000000000000000000000",
                                      "contractType": "NATIVE",
                                      "from": "0xfrom",
                                      "to": "0xwallet",
                                      "tokenIDs": ["0"],
                                      "amounts": ["1"],
                                      "logIndex": 0
                                    }
                                  ],
                                  "timestamp": "2026-06-17T00:00:00Z"
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    ).build(),
            )

            val environment =
                OMSClientEnvironment(
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client = IndexerClient("test-publishable-key", environment, OMSClientHttpClient())

            val response =
                client.getTransactionHistory(
                    networks = listOf(Network.MAINNET),
                    walletAddress = "0xwallet",
                    includeMetadata = true,
                    page = TokenBalancesPageRequest(page = 0, pageSize = 1),
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/v1/IndexerGateway/GetTransactionHistory", request.target)
            assertEquals(
                "{\"chainIds\":[1],\"filter\":{\"accountAddresses\":[\"0xwallet\"]},\"includeMetadata\":true,\"page\":{\"page\":0,\"pageSize\":1}}",
                requireNotNull(request.body).utf8(),
            )
            assertEquals(0, response.page?.page)
            assertEquals(true, response.page?.more)
            val transaction = response.transactions.single()
            assertEquals("0xabc", transaction.txnHash)
            assertEquals("meta-1", transaction.metaTxnId)
            assertEquals(listOf("0"), transaction.transfers?.single()?.tokenIds)
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
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = OMSClientEnvironment(walletApiUrl = server.url("/v1/Waas/").toString()),
                )
            client.wallet.restoreSession(
                OMSClientSessionSnapshot(
                    walletId = "wallet-id",
                    walletAddress = "0xwallet",
                    auth = OMSClientEmailSessionAuth(email = "user@example.com"),
                ),
            )

            val failure =
                runCatching {
                    client.wallet.isValidMessageSignature(
                        network = Network.AMOY,
                        message = "hello",
                        signature = "0xsig",
                    )
                }.exceptionOrNull() as? OmsSdkException

            requireNotNull(failure)
            assertEquals(OmsSdkErrorCode.InvalidResponse, failure.code)
            assertEquals(OmsSdkOperation.WalletIsValidMessageSignature, failure.operation)
            assertEquals("endpoint error", failure.message)
            assertEquals(400, failure.status)
            assertFalse(requireNotNull(failure.message).contains("sensitive backend context"))
            assertEquals(OmsUpstreamService.Waas, failure.upstreamError?.service)
            assertEquals("WebrpcEndpoint", failure.upstreamError?.name)
            assertEquals("-999", failure.upstreamError?.code)
            assertEquals("endpoint error", failure.upstreamError?.message)
            assertEquals(400, failure.upstreamError?.status)
        }

    @Test
    fun generatedWalletPublicUnknownBackendErrorCodeIsRequestFailed() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(409)
                    .body("""{"error":"NewBackendError","code":7999,"msg":"Backend rollout error","status":409}""")
                    .build(),
            )

            val client =
                OMSClient(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = OMSClientEnvironment(walletApiUrl = server.url("/v1/Waas/").toString()),
                )
            client.wallet.restoreSession(
                OMSClientSessionSnapshot(
                    walletId = "wallet-id",
                    walletAddress = "0xwallet",
                    auth = OMSClientEmailSessionAuth(email = "user@example.com"),
                ),
            )

            val failure =
                runCatching {
                    client.wallet.isValidMessageSignature(
                        network = Network.AMOY,
                        message = "hello",
                        signature = "0xsig",
                    )
                }.exceptionOrNull() as? OmsSdkException

            requireNotNull(failure)
            assertEquals(OmsSdkErrorCode.RequestFailed, failure.code)
            assertEquals(OmsSdkOperation.WalletIsValidMessageSignature, failure.operation)
            assertEquals("Backend rollout error", failure.message)
            assertEquals(409, failure.status)
            assertEquals(false, failure.retryable)
            assertEquals(OmsUpstreamService.Waas, failure.upstreamError?.service)
            assertEquals("NewBackendError", failure.upstreamError?.name)
            assertEquals("7999", failure.upstreamError?.code)
            assertEquals("Backend rollout error", failure.upstreamError?.message)
            assertEquals(409, failure.upstreamError?.status)
        }
}
