package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkException
import com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumContractCallRequest
import com.omsclient.kotlin_sdk.generated.waas.TransactionStatusRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.models.AbiArg
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.SendTransactionRequest
import com.omsclient.kotlin_sdk.models.TransactionMode
import com.omsclient.kotlin_sdk.models.TransactionStatus
import com.omsclient.kotlin_sdk.models.TransactionStatusPollingOptions
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import com.omsclient.kotlin_sdk.generated.waas.AbiArg as WaasAbiArg
import com.omsclient.kotlin_sdk.generated.waas.FeeOptionSelection as WaasFeeOptionSelection
import com.omsclient.kotlin_sdk.generated.waas.TransactionMode as WaasTransactionMode

class WalletTransactionTest {
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
    fun sendTransactionRejectsNegativeValue() =
        runBlocking {
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000106L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            assertTrue(client.restorePersistedSession())

            val error =
                runCatching {
                    client.sendTransaction(
                        network = Network.AMOY,
                        request =
                            SendTransactionRequest(
                                to = "0xabc",
                                value = BigInteger.ONE.negate(),
                            ),
                    )
                }.exceptionOrNull()

            assertTrue(error is OmsSdkException)
            assertEquals(OmsSdkErrorCode.ValidationError, (error as OmsSdkException).code)
        }

    @Test
    fun sendTransactionMatchesWaasRequestShape() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "txnId": "txn-1",
                          "status": "quoted",
                          "feeOptions": [
                            {
                              "token": {
                                "network": "amoy",
                                "name": "Polygon",
                                "symbol": "POL",
                                "type": "0",
                                "logoURL": "https://example.com/pol.png"
                              },
                              "value": "10",
                              "displayValue": "0.00000000000000001"
                            },
                            {
                              "token": {
                                "network": "amoy",
                                "name": "USD Coin",
                                "symbol": "USDC",
                                "type": "erc20",
                                "decimals": 6,
                                "logoURL": "https://example.com/usdc.png",
                                "contractAddress": "0xusdc"
                              },
                              "value": "1000",
                              "displayValue": "0.001"
                            }
                          ],
                          "sponsored": false,
                          "expiresAt": "2026-04-27T00:00:00Z"
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "balance": {
                            "accountAddress": "0xwallet",
                            "chainId": 80002,
                            "symbol": "POL",
                            "balance": "100"
                          }
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "page": {"page": 0, "pageSize": 40, "more": false},
                          "balances": [
                            {
                              "contractType": "ERC20",
                              "contractAddress": "0xUSDC",
                              "accountAddress": "0xwallet",
                              "balance": "2000",
                              "chainId": 80002
                            }
                          ]
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"executed","txnHash":"0xdeadbeef"}""")
                    .build(),
            )

            val environment =
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                    indexerUrlTemplate = server.url("/indexer/").toString() + "{value}/rpc/Indexer/",
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000107L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                    fastTransactionStatusPollIntervalMillis = 1L,
                    transactionStatusPollIntervalMillis = 1L,
                    transactionStatusPollTimeoutMillis = 1_000L,
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc",
                            value = BigInteger.ZERO,
                            data = "0x1234",
                            mode = TransactionMode.Native,
                        ),
                ) { feeOptions ->
                    assertEquals(2, feeOptions.size)
                    assertEquals("POL", feeOptions[0].feeOption.token.symbol)
                    assertEquals("100", feeOptions[0].balance?.balance)
                    assertEquals("0.0000000000000001", feeOptions[0].available)
                    assertEquals("100", feeOptions[0].availableRaw)
                    assertEquals(18, feeOptions[0].decimals)
                    assertEquals("USDC", feeOptions[1].feeOption.token.symbol)
                    assertEquals("2000", feeOptions[1].balance?.balance)
                    assertEquals("0.002", feeOptions[1].available)
                    assertEquals("2000", feeOptions[1].availableRaw)
                    assertEquals(6, feeOptions[1].decimals)
                    FeeOptionSelection(token = feeOptions[1].feeOption.token.symbol)
                }
            val prepareRequest = requireNotNull(server.takeRequest())
            val nativeBalanceRequest = requireNotNull(server.takeRequest())
            val balanceRequest = requireNotNull(server.takeRequest())
            val executeRequest = requireNotNull(server.takeRequest())
            val pendingStatusRequest = requireNotNull(server.takeRequest())
            val executedStatusRequest = requireNotNull(server.takeRequest())

            assertEquals("txn-1", result.txnId)
            assertEquals("0xdeadbeef", result.txnHash)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("/rpc/Wallet/PrepareEthereumTransaction", prepareRequest.target)
            assertEquals(
                WaasWalletApi.PrepareEthereumTransaction.encodeRequest(
                    com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumTransactionRequest(
                        walletId = "wallet-main",
                        network = "80002",
                        to = "0xabc",
                        value = "0",
                        data = "0x1234",
                        mode = WaasTransactionMode.Native,
                    ),
                ),
                requireNotNull(prepareRequest.body).utf8(),
            )
            assertEquals("/indexer/amoy/rpc/Indexer/GetNativeTokenBalance", nativeBalanceRequest.target)
            assertEquals(
                "{\"accountAddress\":\"0xwallet\"}",
                requireNotNull(nativeBalanceRequest.body).utf8(),
            )
            assertEquals("/indexer/amoy/rpc/Indexer/GetTokenBalances", balanceRequest.target)
            assertEquals(
                "{\"page\":{\"page\":0,\"pageSize\":40,\"more\":false},\"contractAddress\":\"0xusdc\",\"accountAddress\":\"0xwallet\",\"includeMetadata\":false}",
                requireNotNull(balanceRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/Execute", executeRequest.target)
            assertEquals(
                WaasWalletApi.Execute.encodeRequest(
                    com.omsclient.kotlin_sdk.generated.waas.ExecuteRequest(
                        txnId = "txn-1",
                        feeOption = WaasFeeOptionSelection(token = "USDC"),
                    ),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/TransactionStatus", pendingStatusRequest.target)
            assertEquals(
                WaasWalletApi.TransactionStatus.encodeRequest(
                    com.omsclient.kotlin_sdk.generated.waas
                        .TransactionStatusRequest(txnId = "txn-1"),
                ),
                requireNotNull(pendingStatusRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/TransactionStatus", executedStatusRequest.target)
            assertEquals(
                WaasWalletApi.TransactionStatus.encodeRequest(
                    com.omsclient.kotlin_sdk.generated.waas
                        .TransactionStatusRequest(txnId = "txn-1"),
                ),
                requireNotNull(executedStatusRequest.body).utf8(),
            )
        }

    @Test
    fun sendTransactionUsesFastStatusPollsBeforeDefaultInterval() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "txnId": "txn-1",
                          "status": "quoted",
                          "feeOptions": [],
                          "sponsored": true,
                          "expiresAt": "2026-04-27T00:00:00Z"
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            repeat(6) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("""{"status":"pending"}""")
                        .build(),
                )
            }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"executed","txnHash":"0xdeadbeef"}""")
                    .build(),
            )

            val delays = mutableListOf<Long>()
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000108L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                    transactionStatusDelay = { delayMillis -> delays += delayMillis },
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc",
                            value = BigInteger.ZERO,
                        ),
                )

            assertEquals("0xdeadbeef", result.txnHash)
            assertEquals(6, delays.size)
            assertEquals(400L, delays[0])
            assertEquals(400L, delays[3])
            assertEquals(2_000L, delays[4])
            assertEquals(2_000L, delays[5])
        }

    @Test
    fun sendTransactionAppliesFastPollsWhenSlowPollingIsDisabled() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "txnId": "txn-1",
                          "status": "quoted",
                          "feeOptions": [],
                          "sponsored": true,
                          "expiresAt": "2026-04-27T00:00:00Z"
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"executed","txnHash":"0xdeadbeef"}""")
                    .build(),
            )

            val delays = mutableListOf<Long>()
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000114L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                    transactionStatusDelay = { delayMillis -> delays += delayMillis },
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc",
                            value = BigInteger.ZERO,
                        ),
                    statusPolling =
                        TransactionStatusPollingOptions(
                            fastPollIntervalMillis = 1L,
                            fastPollCount = 3,
                            pollIntervalMillis = 0L,
                            timeoutMillis = 1_000L,
                        ),
                )

            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("0xdeadbeef", result.txnHash)
            assertEquals(listOf(1L, 1L), delays)
            assertEquals(5, server.requestCount)
        }

    @Test
    fun callContractMatchesWaasRequestShape() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "txnId": "contract-txn",
                          "status": "quoted",
                          "feeOptions": [],
                          "sponsored": true,
                          "expiresAt": "2026-04-27T00:00:00Z"
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"executed","txnHash":"0xcontract"}""")
                    .build(),
            )

            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000109L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            assertTrue(client.restorePersistedSession())

            val args =
                listOf(
                    AbiArg(type = "address", value = JsonPrimitive("0xrecipient")),
                    AbiArg(type = "uint256", value = JsonPrimitive("1000")),
                )
            val result =
                client.callContract(
                    network = Network.AMOY,
                    contract = "0xcontract",
                    method = "transfer(address,uint256)",
                    args = args,
                    mode = TransactionMode.Native,
                )
            val prepareRequest = requireNotNull(server.takeRequest())
            val executeRequest = requireNotNull(server.takeRequest())
            val statusRequest = requireNotNull(server.takeRequest())

            assertEquals("contract-txn", result.txnId)
            assertEquals("0xcontract", result.txnHash)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("/rpc/Wallet/PrepareEthereumContractCall", prepareRequest.target)
            assertEquals(
                WaasWalletApi.PrepareEthereumContractCall.encodeRequest(
                    PrepareEthereumContractCallRequest(
                        walletId = "wallet-main",
                        network = "80002",
                        contract = "0xcontract",
                        method = "transfer(address,uint256)",
                        args = args.map { WaasAbiArg(type = it.type, value = it.value) },
                        mode = WaasTransactionMode.Native,
                    ),
                ),
                requireNotNull(prepareRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/Execute", executeRequest.target)
            assertEquals("/rpc/Wallet/TransactionStatus", statusRequest.target)
            assertEquals(
                WaasWalletApi.TransactionStatus.encodeRequest(TransactionStatusRequest(txnId = "contract-txn")),
                requireNotNull(statusRequest.body).utf8(),
            )
        }

    @Test
    fun getTransactionStatusUsesSignedWaasRequest() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"executed","txnHash":"0xstatus"}""")
                    .build(),
            )

            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000110L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            assertTrue(client.restorePersistedSession())

            val result = client.getTransactionStatus(txnId = "txn-1")
            val request = requireNotNull(server.takeRequest())

            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("0xstatus", result.txnHash)
            assertEquals("/rpc/Wallet/TransactionStatus", request.target)
            assertEquals(
                WaasWalletApi.TransactionStatus.encodeRequest(TransactionStatusRequest(txnId = "txn-1")),
                requireNotNull(request.body).utf8(),
            )
            assertEquals("test-access-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertNotNull(request.headers[OMSClientEnvironment.walletSignatureHeaderName])
        }

    @Test
    fun getTransactionStatusReturnsPendingAndUnknownStatuses() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"status":"unexpected"}""")
                    .build(),
            )

            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000113L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            assertTrue(client.restorePersistedSession())

            val pending = client.getTransactionStatus(txnId = "txn-pending")
            val unknown = client.getTransactionStatus(txnId = "txn-unknown")

            assertEquals(TransactionStatus.Pending, pending.status)
            assertEquals(null, pending.txnHash)
            assertEquals(TransactionStatus.UNKNOWN_DEFAULT, unknown.status)
            assertEquals(null, unknown.txnHash)
        }
}
