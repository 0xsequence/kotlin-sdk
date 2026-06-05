package com.omsclient.kotlin_sdk.network

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClient
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkException
import com.omsclient.kotlin_sdk.OmsSdkOperation
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
                ),
            )

            val messageIsValid =
                client.wallet.isValidMessageSignature(
                    network = Network.AMOY,
                    message = "hello",
                    signature = "0xmessage",
                )
            val messageRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/WalletPublic/IsValidMessageSignature", messageRequest.target)
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

            assertEquals("/rpc/WalletPublic/IsValidTypedDataSignature", typedDataRequest.target)
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
                              "balanceUSD": "12.34",
                              "priceUSD": "0.01234",
                              "priceUpdatedAt": "2026-01-01T00:00:00Z",
                              "blockHash": "0xhash",
                              "blockNumber": 12345,
                              "chainId": 137,
                              "uniqueCollectibles": "1",
                              "isSummary": false,
                              "contractInfo": {
                                "chainId": 137,
                                "address": "0xcontract",
                                "source": "metadata",
                                "name": "Example Token",
                                "type": "ERC20",
                                "symbol": "EXM",
                                "decimals": 18,
                                "logoURI": "https://example.com/logo.png",
                                "deployed": true,
                                "bytecodeHash": "0xbytecode",
                                "extensions": {"verified": true},
                                "updatedAt": "2026-01-02T00:00:00Z",
                                "queuedAt": null,
                                "status": "available"
                              },
                              "tokenMetadata": {
                                "chainId": 137,
                                "contractAddress": "0xcontract",
                                "tokenId": "0",
                                "source": "metadata",
                                "name": "Example Token Metadata",
                                "description": "Example description",
                                "image": "ipfs://image",
                                "video": "ipfs://video",
                                "audio": "ipfs://audio",
                                "properties": {"rarity": "rare"},
                                "attributes": [{"trait_type": "Level", "value": 7}],
                                "image_data": "<svg></svg>",
                                "external_url": "https://example.com/token/0",
                                "background_color": "ffffff",
                                "animation_url": "ipfs://animation",
                                "decimals": 18,
                                "updatedAt": "2026-01-03T00:00:00Z",
                                "assets": [
                                  {
                                    "id": 1,
                                    "collectionId": 2,
                                    "tokenID": "asset-token",
                                    "url": "https://example.com/asset.png",
                                    "metadataField": "image",
                                    "name": "Asset",
                                    "filesize": 123456,
                                    "mimeType": "image/png",
                                    "width": 640,
                                    "height": 480,
                                    "updatedAt": "2026-01-04T00:00:00Z"
                                  }
                                ],
                                "status": "available",
                                "queuedAt": null,
                                "lastFetched": "2026-01-05T00:00:00Z"
                              }
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
            val client = IndexerClient("test-publishable-key", environment, OMSClientHttpClient())

            val response =
                client.getTokenBalances(
                    network = Network.POLYGON,
                    contractAddress = "0xcontract",
                    walletAddress = "0xwallet",
                    includeMetadata = true,
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/polygon/rpc/Indexer/GetTokenBalances", request.target)
            assertEquals("test-publishable-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals(
                "{\"page\":{\"page\":0,\"pageSize\":40,\"more\":false},\"contractAddress\":\"0xcontract\",\"accountAddress\":\"0xwallet\",\"includeMetadata\":true}",
                requireNotNull(request.body).utf8(),
            )
            assertEquals(1, response.page?.page)
            assertEquals(25, response.page?.pageSize)
            assertEquals(false, response.page?.more)
            assertEquals(1, response.balances.size)
            val balance = response.balances.single()
            assertEquals("0", balance.tokenId)
            assertEquals("1000", balance.balance)
            assertEquals("12.34", balance.balanceUSD)
            assertEquals("0.01234", balance.priceUSD)
            assertEquals("2026-01-01T00:00:00Z", balance.priceUpdatedAt)
            assertEquals(137L, balance.chainId)
            assertEquals("1", balance.uniqueCollectibles)
            assertEquals(false, balance.isSummary)
            assertEquals("EXM", balance.contractInfo?.symbol)
            assertEquals(18, balance.contractInfo?.decimals)
            assertEquals("https://example.com/logo.png", balance.contractInfo?.logoURI)
            assertEquals("true", balance.contractInfo?.extensions?.get("verified").toString())
            assertEquals("0", balance.tokenMetadata?.tokenId)
            assertEquals("Example Token Metadata", balance.tokenMetadata?.name)
            assertEquals("<svg></svg>", balance.tokenMetadata?.imageData)
            assertEquals("https://example.com/token/0", balance.tokenMetadata?.externalUrl)
            assertEquals("\"rare\"", balance.tokenMetadata?.properties?.get("rarity").toString())
            assertEquals("asset-token", balance.tokenMetadata?.assets?.single()?.tokenId)
            assertEquals("https://example.com/asset.png", balance.tokenMetadata?.assets?.single()?.url)
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
            val client = IndexerClient("test-publishable-key", environment, OMSClientHttpClient())

            val response =
                client.getTokenBalances(
                    network = Network.POLYGON,
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
            val client = IndexerClient("test-publishable-key", environment, OMSClientHttpClient())

            val response =
                client.getNativeTokenBalance(
                    network = Network.POLYGON,
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
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
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
            assertFalse(failure.retryable)
        }
}
