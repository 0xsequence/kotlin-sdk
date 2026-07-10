package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.internal.generated.waas.ExecuteRequest
import technology.polygon.omswallet.internal.generated.waas.PrepareEthereumContractCallRequest
import technology.polygon.omswallet.internal.generated.waas.TransactionStatusRequest
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.models.AbiArg
import technology.polygon.omswallet.models.FeeOptionSelector
import technology.polygon.omswallet.models.SendTransactionRequest
import technology.polygon.omswallet.models.TransactionMode
import technology.polygon.omswallet.models.TransactionStatus
import technology.polygon.omswallet.models.TransactionStatusPollingOptions
import technology.polygon.omswallet.models.TransactionStatusResolution
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import java.math.BigInteger
import technology.polygon.omswallet.internal.generated.waas.AbiArg as WaasAbiArg
import technology.polygon.omswallet.internal.generated.waas.FeeOptionSelection as WaasFeeOptionSelection
import technology.polygon.omswallet.internal.generated.waas.TransactionMode as WaasTransactionMode

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
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000106"),
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

            assertTrue(error is OMSWalletException)
            assertEquals(OMSWalletErrorCode.ValidationError, (error as OMSWalletException).code)
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
                                "contractAddress": "0xusdc",
                                "tokenID": "usdc"
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
                          "page": {"page": 0, "pageSize": 40, "more": false},
                          "nativeBalances": [
                            {
                              "chainId": 80002,
                              "results": [
                                {
                                  "accountAddress": "0xwallet",
                                  "chainId": 80002,
                                  "symbol": "POL",
                                  "balance": "100"
                                }
                              ]
                            }
                          ],
                          "balances": [
                            {
                              "chainId": 80002,
                              "results": [
                                {
                                  "contractType": "ERC20",
                                  "contractAddress": "0xUSDC",
                                  "accountAddress": "0xwallet",
                                  "balance": "2000",
                                  "chainId": 80002
                                }
                              ]
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
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/indexer-gateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000107"),
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
                    feeOptions[1].selection
                }
            val prepareRequest = requireNotNull(server.takeRequest())
            val balanceRequest = requireNotNull(server.takeRequest())
            val executeRequest = requireNotNull(server.takeRequest())
            val pendingStatusRequest = requireNotNull(server.takeRequest())
            val executedStatusRequest = requireNotNull(server.takeRequest())

            assertEquals("txn-1", result.txnId)
            assertEquals("0xdeadbeef", result.txnHash)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals(TransactionStatusResolution.Resolved, result.statusResolution)
            assertEquals("/v1/Waas/PrepareEthereumTransaction", prepareRequest.target)
            assertEquals(
                WaasApi.PrepareEthereumTransaction.encodeRequest(
                    technology.polygon.omswallet.internal.generated.waas.PrepareEthereumTransactionRequest(
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
            assertEquals("/indexer-gateway/GetTokenBalancesDetails", balanceRequest.target)
            assertEquals("test-publishable-key", balanceRequest.headers["Api-Key"])
            assertEquals(
                "{\"chainIds\":[80002],\"filter\":{\"accountAddresses\":[\"0xwallet\"],\"contractWhitelist\":[\"0xusdc\"],\"omitNativeBalances\":false},\"omitMetadata\":true,\"page\":{\"page\":0,\"pageSize\":40}}",
                requireNotNull(balanceRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/Execute", executeRequest.target)
            assertEquals(
                WaasApi.Execute.encodeRequest(
                    technology.polygon.omswallet.internal.generated.waas.ExecuteRequest(
                        txnId = "txn-1",
                        feeOption = WaasFeeOptionSelection(token = "usdc"),
                    ),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/TransactionStatus", pendingStatusRequest.target)
            assertEquals(
                WaasApi.TransactionStatusMethod.encodeRequest(
                    technology.polygon.omswallet.internal.generated.waas
                        .TransactionStatusRequest(txnId = "txn-1"),
                ),
                requireNotNull(pendingStatusRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/TransactionStatus", executedStatusRequest.target)
            assertEquals(
                WaasApi.TransactionStatusMethod.encodeRequest(
                    technology.polygon.omswallet.internal.generated.waas
                        .TransactionStatusRequest(txnId = "txn-1"),
                ),
                requireNotNull(executedStatusRequest.body).utf8(),
            )
        }

    @Test
    fun sendTransactionDefaultSelectionUsesFeeTokenIdWhenPresent() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-token-id",
                    feeOptions =
                        """
                        [
                          ${feeOptionJson(
                            symbol = "USDC",
                            value = "1000",
                            contractAddress = "0xusdc",
                            tokenId = "usdc",
                        )}
                        ]
                        """.trimIndent(),
                    sponsored = false,
                ),
            )
            enqueueJson("""{"status":"executed"}""")

            val client = restoredWalletClient(nonceValue = "1710000115")

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc",
                            value = BigInteger.ZERO,
                        ),
                    waitForStatus = false,
                )
            requireNotNull(server.takeRequest())
            val executeRequest = requireNotNull(server.takeRequest())

            assertEquals("txn-token-id", result.txnId)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals(TransactionStatusResolution.NotRequested, result.statusResolution)
            assertEquals(
                WaasApi.Execute.encodeRequest(
                    ExecuteRequest(
                        txnId = "txn-token-id",
                        feeOption = WaasFeeOptionSelection(token = "usdc"),
                    ),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals(2, server.requestCount)
        }

    @Test
    fun sendTransactionSponsoredSkipsCustomFeeSelector() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-sponsored",
                    feeOptions =
                        """
                        [
                          ${feeOptionJson(
                            symbol = "USDC",
                            value = "1000",
                            contractAddress = "0xusdc",
                            tokenId = "usdc",
                        )}
                        ]
                        """.trimIndent(),
                    sponsored = true,
                ),
            )
            enqueueJson("""{"status":"executed"}""")
            val client = restoredWalletClient(nonceValue = "1710000116")
            var selectorCalled = false

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc",
                            value = BigInteger.ZERO,
                        ),
                    waitForStatus = false,
                    selectFeeOption =
                        FeeOptionSelector {
                            selectorCalled = true
                            it.firstOrNull()?.selection
                        },
                )
            requireNotNull(server.takeRequest())
            val executeRequest = requireNotNull(server.takeRequest())

            assertEquals("txn-sponsored", result.txnId)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals(false, selectorCalled)
            assertEquals(
                WaasApi.Execute.encodeRequest(
                    ExecuteRequest(txnId = "txn-sponsored"),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals(2, server.requestCount)
        }

    @Test
    fun sendTransactionUnsponsoredWithoutFeeOptionsFailsBeforeExecute() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-no-fees",
                    feeOptions = "[]",
                    sponsored = false,
                ),
            )
            enqueueJson("""{"status":"executed"}""")
            val client = restoredWalletClient(nonceValue = "1710000117")

            val error =
                runCatching {
                    client.sendTransaction(
                        network = Network.AMOY,
                        request =
                            SendTransactionRequest(
                                to = "0xabc",
                                value = BigInteger.ZERO,
                            ),
                        waitForStatus = false,
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            assertEquals(OMSWalletErrorCode.ValidationError, (error as OMSWalletException).code)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun sendTransactionUnsponsoredCustomSelectorRequiresSelection() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-no-selection",
                    feeOptions =
                        """
                        [
                          ${feeOptionJson(
                            symbol = "USDC",
                            value = "1000",
                            contractAddress = "0xusdc",
                            tokenId = "usdc",
                        )}
                        ]
                        """.trimIndent(),
                    sponsored = false,
                ),
            )
            enqueueJson(gatewayTokenBalancesResponse("0xUSDC" to "2000"))
            enqueueJson("""{"status":"executed"}""")
            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/indexer-gateway/").toString(),
                )
            val client = restoredWalletClient(nonceValue = "1710000118", environment = environment)
            var selectorCalled = false

            val error =
                runCatching {
                    client.sendTransaction(
                        network = Network.AMOY,
                        request =
                            SendTransactionRequest(
                                to = "0xabc",
                                value = BigInteger.ZERO,
                            ),
                        waitForStatus = false,
                        selectFeeOption =
                            FeeOptionSelector {
                                selectorCalled = true
                                null
                            },
                    )
                }.exceptionOrNull()

            assertTrue(selectorCalled)
            assertTrue(error is OMSWalletException)
            assertEquals(OMSWalletErrorCode.ValidationError, (error as OMSWalletException).code)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun sendTransactionFirstAvailableSelectsFirstAffordableFeeTokenId() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-first-available",
                    feeOptions =
                        """
                        [
                          ${feeOptionJson(
                            symbol = "DAI",
                            value = "1000",
                            contractAddress = "0xdai",
                            tokenId = "dai",
                        )},
                          ${feeOptionJson(
                            symbol = "USDC",
                            value = "20",
                            contractAddress = "0xusdc",
                            tokenId = "usdc",
                        )}
                        ]
                        """.trimIndent(),
                    sponsored = false,
                ),
            )
            enqueueJson(gatewayTokenBalancesResponse("0xDAI" to "100", "0xUSDC" to "2000"))
            enqueueJson("""{"status":"executed"}""")
            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/indexer-gateway/").toString(),
                )
            val client = restoredWalletClient(nonceValue = "1710000119", environment = environment)

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc",
                            value = BigInteger.ZERO,
                        ),
                    waitForStatus = false,
                    selectFeeOption = FeeOptionSelector.firstAvailable,
                )
            requireNotNull(server.takeRequest())
            val balanceRequest = requireNotNull(server.takeRequest())
            val executeRequest = requireNotNull(server.takeRequest())

            assertEquals("txn-first-available", result.txnId)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("/indexer-gateway/GetTokenBalancesDetails", balanceRequest.target)
            assertEquals(
                "{\"chainIds\":[80002],\"filter\":{\"accountAddresses\":[\"0xwallet\"],\"contractWhitelist\":[\"0xdai\",\"0xusdc\"],\"omitNativeBalances\":false},\"omitMetadata\":true,\"page\":{\"page\":0,\"pageSize\":40}}",
                requireNotNull(balanceRequest.body).utf8(),
            )
            assertEquals(
                WaasApi.Execute.encodeRequest(
                    ExecuteRequest(
                        txnId = "txn-first-available",
                        feeOption = WaasFeeOptionSelection(token = "usdc"),
                    ),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals(3, server.requestCount)
        }

    @Test
    fun sendTransactionFirstAvailableRequiresAffordableFeeOption() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-no-affordable-fee",
                    feeOptions =
                        """
                        [
                          ${feeOptionJson(
                            symbol = "USDC",
                            value = "1000",
                            contractAddress = "0xusdc",
                            tokenId = "usdc",
                        )}
                        ]
                        """.trimIndent(),
                    sponsored = false,
                ),
            )
            enqueueJson(gatewayTokenBalancesResponse("0xUSDC" to "100"))
            enqueueJson("""{"status":"executed"}""")
            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/indexer-gateway/").toString(),
                )
            val client = restoredWalletClient(nonceValue = "1710000120", environment = environment)

            val error =
                runCatching {
                    client.sendTransaction(
                        network = Network.AMOY,
                        request =
                            SendTransactionRequest(
                                to = "0xabc",
                                value = BigInteger.ZERO,
                            ),
                        waitForStatus = false,
                        selectFeeOption = FeeOptionSelector.firstAvailable,
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            assertEquals(OMSWalletErrorCode.ValidationError, (error as OMSWalletException).code)
            assertEquals(2, server.requestCount)
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
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000108"),
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
    fun sendTransactionTreatsFailedStatusAsTerminal() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-failed",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            enqueueJson("""{"status":"pending"}""")
            enqueueJson("""{"status":"failed"}""")
            enqueueJson("""{"status":"executed","txnHash":"0xshould-not-be-read"}""")

            val delays = mutableListOf<Long>()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000121"),
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

            assertEquals(TransactionStatus.Failed, result.status)
            assertEquals(null, result.txnHash)
            assertEquals(TransactionStatusResolution.Resolved, result.statusResolution)
            assertEquals(emptyList<Long>(), delays)
            assertEquals(3, server.requestCount)
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
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000114"),
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
                            pollIntervalMillis = 1L,
                            timeoutMillis = 1_000L,
                        ),
                )

            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("0xdeadbeef", result.txnHash)
            assertEquals(listOf(1L, 1L), delays)
            assertEquals(5, server.requestCount)
        }

    @Test
    fun sendTransactionReportsWhenStatusPollingTimesOut() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-timeout",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            enqueueJson("""{"status":"pending"}""")
            enqueueJson("""{"status":"pending"}""")

            val client = restoredWalletClient(nonceValue = "1710000121")
            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request = SendTransactionRequest(to = "0xabc", value = BigInteger.ZERO),
                    statusPolling =
                        TransactionStatusPollingOptions(
                            fastPollCount = 0,
                            timeoutMillis = 0L,
                        ),
                )

            assertEquals("txn-timeout", result.txnId)
            assertEquals(TransactionStatus.Pending, result.status)
            assertNull(result.txnHash)
            assertEquals(TransactionStatusResolution.TimedOut, result.statusResolution)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun sendTransactionKeepsUnknownStatusUnresolvedUntilTimeout() =
        runBlocking {
            enqueueJson(
                prepareResponse(
                    txnId = "txn-unknown-timeout",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            enqueueJson("""{"status":"pending"}""")
            enqueueJson("""{"status":"future-status"}""")

            val client = restoredWalletClient(nonceValue = "1710000122")
            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request = SendTransactionRequest(to = "0xabc", value = BigInteger.ZERO),
                    statusPolling = TransactionStatusPollingOptions(timeoutMillis = 0L),
                )

            assertEquals("txn-unknown-timeout", result.txnId)
            assertEquals(TransactionStatus.UNKNOWN_DEFAULT, result.status)
            assertNull(result.txnHash)
            assertEquals(TransactionStatusResolution.TimedOut, result.statusResolution)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun sendTransactionRejectsInvalidPollingOptionsBeforeExecute() =
        runBlocking {
            val invalidOptions =
                listOf(
                    TransactionStatusPollingOptions(fastPollIntervalMillis = 0L),
                    TransactionStatusPollingOptions(fastPollCount = -1),
                    TransactionStatusPollingOptions(pollIntervalMillis = 0L),
                    TransactionStatusPollingOptions(timeoutMillis = -1L),
                )

            invalidOptions.forEachIndexed { index, options ->
                enqueueJson(
                    prepareResponse(
                        txnId = "txn-invalid-polling-$index",
                        feeOptions = "[]",
                        sponsored = true,
                    ),
                )
                val client = restoredWalletClient(nonceValue = "17100002$index")

                val failure =
                    runCatching {
                        client.sendTransaction(
                            network = Network.AMOY,
                            request = SendTransactionRequest(to = "0xabc", value = BigInteger.ZERO),
                            statusPolling = options,
                        )
                    }.exceptionOrNull() as OMSWalletException

                assertEquals(OMSWalletErrorCode.ValidationError, failure.code)
                assertEquals(OMSWalletOperation.WalletSendTransaction, failure.operation)
            }

            assertEquals(invalidOptions.size, server.requestCount)
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
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000109"),
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
            assertEquals("/v1/Waas/PrepareEthereumContractCall", prepareRequest.target)
            assertEquals(
                WaasApi.PrepareEthereumContractCall.encodeRequest(
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
            assertEquals("/v1/Waas/Execute", executeRequest.target)
            assertEquals("/v1/Waas/TransactionStatus", statusRequest.target)
            assertEquals(
                WaasApi.TransactionStatusMethod.encodeRequest(TransactionStatusRequest(txnId = "contract-txn")),
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
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            assertTrue(client.restorePersistedSession())

            val result = client.getTransactionStatus(txnId = "txn-1")
            val request = requireNotNull(server.takeRequest())

            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("0xstatus", result.txnHash)
            assertEquals("/v1/Waas/TransactionStatus", request.target)
            assertEquals(
                WaasApi.TransactionStatusMethod.encodeRequest(TransactionStatusRequest(txnId = "txn-1")),
                requireNotNull(request.body).utf8(),
            )
            assertEquals("test-publishable-key", request.headers[OMSWalletEnvironment.accessKeyHeaderName])
            assertNotNull(request.headers[OMSWalletEnvironment.walletSignatureHeaderName])
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
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000113"),
                )
            assertTrue(client.restorePersistedSession())

            val pending = client.getTransactionStatus(txnId = "txn-pending")
            val unknown = client.getTransactionStatus(txnId = "txn-unknown")

            assertEquals(TransactionStatus.Pending, pending.status)
            assertEquals(null, pending.txnHash)
            assertEquals(TransactionStatus.UNKNOWN_DEFAULT, unknown.status)
            assertEquals(null, unknown.txnHash)
        }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(body)
                .build(),
        )
    }

    private fun restoredWalletClient(
        nonceValue: String,
        environment: OMSWalletEnvironment =
            OMSWalletEnvironment(
                walletApiUrl = server.url("/v1/Waas/").toString(),
                indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
            ),
    ): WalletClient {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = environment,
                transport = OMSWalletHttpClient(),
                sessionStore =
                    InMemorySessionStore(
                        snapshot =
                            OMSWalletSessionSnapshot(
                                walletId = "wallet-main",
                                walletAddress = "0xwallet",
                                signerAddress = TEST_CREDENTIAL_ID,
                                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                auth = emailSessionAuth(),
                            ),
                    ),
                credentialSigner = TrackingCredentialSigner(nonceValue = nonceValue),
            )
        assertTrue(client.restorePersistedSession())
        return client
    }

    private fun prepareResponse(
        txnId: String,
        feeOptions: String,
        sponsored: Boolean,
    ): String =
        """
        {
          "txnId": "$txnId",
          "status": "quoted",
          "feeOptions": $feeOptions,
          "sponsored": $sponsored,
          "expiresAt": "2026-04-27T00:00:00Z"
        }
        """.trimIndent()

    private fun feeOptionJson(
        symbol: String,
        value: String,
        contractAddress: String,
        tokenId: String,
    ): String =
        """
        {
          "token": {
            "network": "amoy",
            "name": "$symbol",
            "symbol": "$symbol",
            "type": "erc20",
            "decimals": 6,
            "logoURL": "https://example.com/${symbol.lowercase()}.png",
            "contractAddress": "$contractAddress",
            "tokenID": "$tokenId"
          },
          "value": "$value",
          "displayValue": "$value"
        }
        """.trimIndent()

    private fun gatewayTokenBalancesResponse(vararg balances: Pair<String, String>): String {
        val balanceJson =
            balances.joinToString(",") { (contractAddress, balance) ->
                """
                {
                  "contractType": "ERC20",
                  "contractAddress": "$contractAddress",
                  "accountAddress": "0xwallet",
                  "balance": "$balance",
                  "chainId": 80002
                }
                """.trimIndent()
            }
        return """
            {
              "page": {"page": 0, "pageSize": 40, "more": false},
              "nativeBalances": [],
              "balances": [
                {
                  "chainId": 80002,
                  "results": [$balanceJson]
                }
              ]
            }
            """.trimIndent()
    }
}
