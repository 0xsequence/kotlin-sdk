package technology.polygon.omswallet.wallet

import kotlinx.coroutines.CancellationException
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
import technology.polygon.omswallet.SolanaNetwork
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
                                  "name": "Polygon",
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
                                  "tokenID": "0",
                                  "balance": "2000",
                                  "blockHash": "0xblock",
                                  "blockNumber": 1,
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000107"),
                    defaultStatusPolling = TransactionStatusPollingOptions(fastIntervalMs = 1L, intervalMs = 1L, timeoutMs = 1_000L),
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc0000000000000000000000000000000000000",
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
                        to = "0xabc0000000000000000000000000000000000000",
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
                        feeOption = WaasFeeOptionSelection(token = "usdc", index = 1u),
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
            server.enqueueJson(
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
            server.enqueueJson("""{"status":"executed"}""")

            val client = restoredWalletClient(nonceValue = "1710000115")

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc0000000000000000000000000000000000000",
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
                        feeOption = WaasFeeOptionSelection(token = "usdc", index = 0u),
                    ),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals(2, server.requestCount)
        }

    @Test
    fun sendTransactionSponsoredInvokesCustomFeeSelectorWithEmptyOptions() =
        runBlocking {
            val usdcFeeOptions =
                """
                [
                  ${feeOptionJson(
                    symbol = "USDC",
                    value = "1000",
                    contractAddress = "0xusdc",
                    tokenId = "usdc",
                )}
                ]
                """.trimIndent()
            val selectorInputs = mutableListOf<List<technology.polygon.omswallet.models.FeeOptionWithBalance>>()
            val recordingSelector =
                FeeOptionSelector {
                    selectorInputs += it
                    it.firstOrNull()?.selection
                }
            // A custom selector over a quote that still lists fee options, and the built-in
            // firstAvailable selector over an empty quote: both must execute without a fee option.
            listOf(
                Triple("txn-sponsored", usdcFeeOptions, recordingSelector),
                Triple("txn-sponsored-first-available", "[]", FeeOptionSelector.firstAvailable),
            ).forEach { (txnId, feeOptions, selector) ->
                server.enqueueJson(prepareResponse(txnId = txnId, feeOptions = feeOptions, sponsored = true))
                server.enqueueJson("""{"status":"executed"}""")
                val requestsBefore = server.requestCount
                val client = restoredWalletClient(nonceValue = "1710000116")

                val result =
                    client.sendTransaction(
                        network = Network.AMOY,
                        request =
                            SendTransactionRequest(
                                to = "0xabc0000000000000000000000000000000000000",
                                value = BigInteger.ZERO,
                            ),
                        waitForStatus = false,
                        selectFeeOption = selector,
                    )
                requireNotNull(server.takeRequest())
                val executeRequest = requireNotNull(server.takeRequest())

                assertEquals(txnId, result.txnId)
                assertEquals(TransactionStatus.Executed, result.status)
                assertEquals("""{"txnId":"$txnId"}""", requireNotNull(executeRequest.body).utf8())
                assertEquals(2, server.requestCount - requestsBefore)
            }
            // Sponsored sends acknowledge the selector with no options, even when WaaS quotes some.
            assertEquals(listOf(emptyList<technology.polygon.omswallet.models.FeeOptionWithBalance>()), selectorInputs)
        }

    @Test
    fun sendTransactionSponsoredDoesNotExecuteWhenAcknowledgementThrows() =
        runBlocking {
            server.enqueueJson(
                prepareResponse(
                    txnId = "txn-sponsored-cancelled",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            val client = restoredWalletClient(nonceValue = "1710000118")

            val error =
                runCatching {
                    client.sendTransaction(
                        network = Network.AMOY,
                        request = SendTransactionRequest(to = "0xabc0000000000000000000000000000000000000", value = BigInteger.ZERO),
                        selectFeeOption =
                            FeeOptionSelector { feeOptions ->
                                assertTrue(feeOptions.isEmpty())
                                throw CancellationException("Transaction cancelled")
                            },
                    )
                }.exceptionOrNull()

            assertTrue(error is CancellationException)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun sendTransactionUnsponsoredWithoutFeeOptionsFailsBeforeExecute() =
        runBlocking {
            server.enqueueJson(
                prepareResponse(
                    txnId = "txn-no-fees",
                    feeOptions = "[]",
                    sponsored = false,
                ),
            )
            server.enqueueJson("""{"status":"executed"}""")
            val client = restoredWalletClient(nonceValue = "1710000117")

            val error =
                runCatching {
                    client.sendTransaction(
                        network = Network.AMOY,
                        request =
                            SendTransactionRequest(
                                to = "0xabc0000000000000000000000000000000000000",
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
            server.enqueueJson(
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
            server.enqueueJson(gatewayTokenBalancesResponse("0xUSDC" to "2000"))
            server.enqueueJson("""{"status":"executed"}""")
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
                                to = "0xabc0000000000000000000000000000000000000",
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
            server.enqueueJson(
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
            server.enqueueJson(gatewayTokenBalancesResponse("0xDAI" to "100", "0xUSDC" to "2000"))
            server.enqueueJson("""{"status":"executed"}""")
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
                            to = "0xabc0000000000000000000000000000000000000",
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
                        feeOption = WaasFeeOptionSelection(token = "usdc", index = 1u),
                    ),
                ),
                requireNotNull(executeRequest.body).utf8(),
            )
            assertEquals(3, server.requestCount)
        }

    @Test
    fun sendTransactionFirstAvailableRequiresAffordableFeeOption() =
        runBlocking {
            server.enqueueJson(
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
            server.enqueueJson(gatewayTokenBalancesResponse("0xUSDC" to "100"))
            server.enqueueJson("""{"status":"executed"}""")
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
                                to = "0xabc0000000000000000000000000000000000000",
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000108"),
                    transactionStatusDelay = { delayMillis -> delays += delayMillis },
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc0000000000000000000000000000000000000",
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
            server.enqueueJson(
                prepareResponse(
                    txnId = "txn-failed",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            server.enqueueJson("""{"status":"pending"}""")
            server.enqueueJson("""{"status":"failed"}""")
            server.enqueueJson("""{"status":"executed","txnHash":"0xshould-not-be-read"}""")

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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000121"),
                    transactionStatusDelay = { delayMillis -> delays += delayMillis },
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc0000000000000000000000000000000000000",
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
    fun sendTransactionResendsExecuteWhenWaasRejectsItsNonce() =
        runBlocking {
            // WaaS rejects a stale nonce before running Execute, so the resent Execute is the only one that ran.
            server.enqueueJson(prepareResponse(txnId = "txn-resent", feeOptions = "[]", sponsored = true))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(412)
                    .body("""{"error":"PreconditionFailed","code":7206,"msg":"nonce replay detected","status":412}""")
                    .build(),
            )
            server.enqueueJson("""{"status":"pending"}""")
            server.enqueueJson("""{"status":"executed","txnHash":"0xresent"}""")
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000122"),
                    transactionStatusDelay = { },
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc0000000000000000000000000000000000000",
                            value = BigInteger.ZERO,
                        ),
                )

            assertEquals("txn-resent", result.txnId)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("0xresent", result.txnHash)
            val targets = List(server.requestCount) { requireNotNull(server.takeRequest()).target }
            assertEquals(
                listOf(WaasApi.Execute.urlPath, WaasApi.Execute.urlPath, WaasApi.TransactionStatusMethod.urlPath),
                targets.drop(1),
            )
        }

    @Test
    fun sendTransactionSwitchesFromFastToSlowIntervalAfterFastPollCount() =
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
            repeat(4) {
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000114"),
                    transactionStatusDelay = { delayMillis -> delays += delayMillis },
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                )
            assertTrue(client.restorePersistedSession())

            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request =
                        SendTransactionRequest(
                            to = "0xabc0000000000000000000000000000000000000",
                            value = BigInteger.ZERO,
                        ),
                    statusPolling =
                        TransactionStatusPollingOptions(
                            fastIntervalMs = 10L,
                            fastPollCount = 2,
                            intervalMs = 50L,
                            timeoutMs = 1_000L,
                        ),
                )

            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("0xdeadbeef", result.txnHash)
            // Two fast polls (one fast delay between them), then the slow interval for the rest.
            assertEquals(listOf(10L, 50L, 50L, 50L), delays)
            assertEquals(7, server.requestCount)
        }

    @Test
    fun sendTransactionReportsWhenStatusPollingTimesOut() =
        runBlocking {
            server.enqueueJson(
                prepareResponse(
                    txnId = "txn-timeout",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            server.enqueueJson("""{"status":"pending"}""")
            server.enqueueJson("""{"status":"pending"}""")

            val client = restoredWalletClient(nonceValue = "1710000121")
            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request = SendTransactionRequest(to = "0xabc0000000000000000000000000000000000000", value = BigInteger.ZERO),
                    statusPolling =
                        TransactionStatusPollingOptions(
                            fastPollCount = 0,
                            timeoutMs = 0L,
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
            server.enqueueJson(
                prepareResponse(
                    txnId = "txn-unknown-timeout",
                    feeOptions = "[]",
                    sponsored = true,
                ),
            )
            server.enqueueJson("""{"status":"pending"}""")
            server.enqueueJson("""{"status":"future-status"}""")

            val client = restoredWalletClient(nonceValue = "1710000122")
            val result =
                client.sendTransaction(
                    network = Network.AMOY,
                    request = SendTransactionRequest(to = "0xabc0000000000000000000000000000000000000", value = BigInteger.ZERO),
                    statusPolling = TransactionStatusPollingOptions(timeoutMs = 0L),
                )

            assertEquals("txn-unknown-timeout", result.txnId)
            assertEquals(TransactionStatus.UNKNOWN_DEFAULT, result.status)
            assertNull(result.txnHash)
            assertEquals(TransactionStatusResolution.TimedOut, result.statusResolution)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun transactionStatusPollingOptionsKeepDefaults() {
        val options = TransactionStatusPollingOptions()

        assertEquals(400L, options.fastIntervalMs)
        assertEquals(5, options.fastPollCount)
        assertEquals(2_000L, options.intervalMs)
        assertEquals(60_000L, options.timeoutMs)
    }

    @Test
    fun sendTransactionValueOverloadForwardsMode() =
        runBlocking {
            listOf(null, TransactionMode.Native, TransactionMode.Relayer).forEachIndexed { index, mode ->
                server.enqueueJson(prepareResponse(txnId = "txn-mode-$index", feeOptions = "[]", sponsored = true))
                server.enqueueJson("""{"status":"pending"}""")
                val client = restoredWalletClient(nonceValue = "17100003$index")

                if (mode == null) {
                    client.sendTransaction(
                        network = Network.AMOY,
                        to = "0xabc0000000000000000000000000000000000000",
                        value = BigInteger.ONE,
                        waitForStatus = false,
                    )
                } else {
                    client.sendTransaction(
                        network = Network.AMOY,
                        to = "0xabc0000000000000000000000000000000000000",
                        value = BigInteger.ONE,
                        mode = mode,
                        waitForStatus = false,
                    )
                }
                val prepare = requireNotNull(server.takeRequest())
                requireNotNull(server.takeRequest())

                assertEquals("/v1/Waas/PrepareEthereumTransaction", prepare.target)
                assertTrue(
                    requireNotNull(prepare.body)
                        .utf8()
                        .contains("\"mode\":\"${(mode ?: TransactionMode.Relayer).wireValue}\""),
                )
            }
        }

    @Test
    fun sendTransactionRejectsInvalidPollingOptionsBeforeExecute() =
        runBlocking {
            val invalidOptions =
                listOf(
                    TransactionStatusPollingOptions(fastIntervalMs = 0L),
                    TransactionStatusPollingOptions(fastPollCount = -1),
                    TransactionStatusPollingOptions(intervalMs = 0L),
                    TransactionStatusPollingOptions(timeoutMs = -1L),
                )

            invalidOptions.forEachIndexed { index, options ->
                server.enqueueJson(
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
                            request = SendTransactionRequest(to = "0xabc0000000000000000000000000000000000000", value = BigInteger.ZERO),
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000109"),
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
                    contractAddress = "0xcontract",
                    method = "transfer",
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
                        method = "transfer",
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
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
                                    wallet = testWallet("wallet-main", "0xwallet"),
                                    expiresAt = TEST_SESSION_EXPIRES_AT,
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000113"),
                    credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
                )
            assertTrue(client.restorePersistedSession())

            val pending = client.getTransactionStatus(txnId = "txn-pending")
            val unknown = client.getTransactionStatus(txnId = "txn-unknown")

            assertEquals(TransactionStatus.Pending, pending.status)
            assertEquals(null, pending.txnHash)
            assertEquals(TransactionStatus.UNKNOWN_DEFAULT, unknown.status)
            assertEquals(null, unknown.txnHash)
        }

    @Test
    fun solanaOperationsUseSolanaRequestShapes() =
        runBlocking {
            val recipient = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
            server.enqueueJson("""{"signature":"solana-signature"}""")
            server.enqueueJson(
                """{"txnId":"solana-txn","status":"quoted","feeOptions":[],"sponsored":true,"expiresAt":"2099-01-01T00:00:00Z"}""",
            )
            server.enqueueJson("""{"status":"executed"}""")
            val client =
                restoredWalletClient(
                    nonceValue = "1710000120",
                    walletAddress = "3gFktQX6vki5M2DzN8Y1ESPUJ4fJ8o6hVQWf8vYvPypD",
                    walletType = technology.polygon.omswallet.models.WalletType.Solana,
                )

            val signature = client.signSolanaMessage("hello")
            val transaction =
                client.sendSolanaTransfer(
                    network = SolanaNetwork.Devnet,
                    asset = "SOL",
                    to = recipient,
                    amount = BigInteger("1000000"),
                    waitForStatus = false,
                )
            val sign = requireNotNull(server.takeRequest())
            val prepare = requireNotNull(server.takeRequest())
            val execute = requireNotNull(server.takeRequest())

            assertEquals("solana-signature", signature)
            assertEquals("/v1/Waas/SignMessage", sign.target)
            assertEquals(
                """{"network":"","walletId":"wallet-main","message":"hello"}""",
                requireNotNull(sign.body).utf8(),
            )
            assertEquals("/v1/Waas/PrepareSolanaTransfer", prepare.target)
            assertEquals(
                """{"network":"solana:devnet","walletId":"wallet-main","asset":"SOL",""" +
                    """"recipient":{"address":"$recipient"},"amount":"1000000","mode":"relayer"}""",
                requireNotNull(prepare.body).utf8(),
            )
            assertEquals("/v1/Waas/Execute", execute.target)
            assertEquals("""{"txnId":"solana-txn"}""", requireNotNull(execute.body).utf8())
            assertEquals(3, server.requestCount)
            assertEquals("solana-txn", transaction.txnId)
            assertEquals(TransactionStatusResolution.NotRequested, transaction.statusResolution)
        }

    @Test
    fun solanaFirstAvailableUsesIndexerBalances() =
        runBlocking {
            val walletAddress = "4Nd1mYQbqjVU2aR7cJNPyqW9XjHnBYvWQd7ZxYxvT6uP"
            val usdcMint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
            server.enqueueJson(
                """
                {
                  "txnId": "solana-first-available",
                  "status": "quoted",
                  "feeOptions": [
                    {
                      "token": {
                        "network": "solana:devnet",
                        "name": "SOL",
                        "symbol": "SOL",
                        "type": "native"
                      },
                      "value": "5000",
                      "displayValue": "0.000005"
                    },
                    {
                      "token": {
                        "network": "solana:devnet",
                        "name": "USD Coin",
                        "symbol": "USDC",
                        "type": "spl",
                        "contractAddress": "$usdcMint"
                      },
                      "value": "10000",
                      "displayValue": "0.01"
                    }
                  ],
                  "sponsored": false,
                  "expiresAt": "2099-01-01T00:00:00Z"
                }
                """.trimIndent(),
            )
            server.enqueueJson(
                """
                {
                  "balances": [
                    {
                      "network": "solana:devnet",
                      "accountAddress": "$walletAddress",
                      "assetType": "native",
                      "name": "Solana",
                      "symbol": "SOL",
                      "decimals": 9,
                      "balance": "1000",
                      "formattedBalance": "0.000001",
                      "verificationStatus": "unknown",
                      "verificationSource": "none"
                    },
                    {
                      "network": "solana:devnet",
                      "accountAddress": "$walletAddress",
                      "assetType": "fungible-token",
                      "tokenProgram": "spl-token",
                      "mintAddress": "$usdcMint",
                      "name": "USD Coin",
                      "symbol": "USDC",
                      "decimals": 6,
                      "balance": "20000",
                      "formattedBalance": "0.02",
                      "verificationStatus": "verified",
                      "verificationSource": "jupiter"
                    }
                  ],
                  "errors": []
                }
                """.trimIndent(),
            )
            server.enqueueJson("""{"status":"pending"}""")
            val client =
                restoredWalletClient(
                    nonceValue = "1710000121",
                    walletAddress = walletAddress,
                    walletType = technology.polygon.omswallet.models.WalletType.Solana,
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                            solanaIndexerGatewayUrl = server.url("/v1/SolanaIndexerGateway/").toString(),
                        ),
                )

            val result =
                client.sendSolanaTransfer(
                    network = SolanaNetwork.Devnet,
                    asset = "SOL",
                    to = "recipient",
                    amount = BigInteger("1000000"),
                    selectFeeOption = FeeOptionSelector.firstAvailable,
                    waitForStatus = false,
                )
            val prepare = requireNotNull(server.takeRequest())
            val balances = requireNotNull(server.takeRequest())
            val execute = requireNotNull(server.takeRequest())

            assertEquals("solana-first-available", result.txnId)
            assertEquals("/v1/Waas/PrepareSolanaTransfer", prepare.target)
            assertEquals("/v1/SolanaIndexerGateway/GetTokenBalancesDetails", balances.target)
            assertTrue(requireNotNull(balances.body).utf8().contains("\"contractWhitelist\":[\"$usdcMint\"]"))
            assertEquals(
                WaasApi.Execute.encodeRequest(
                    ExecuteRequest(
                        txnId = "solana-first-available",
                        feeOption = WaasFeeOptionSelection(token = "USDC", index = 1u),
                    ),
                ),
                requireNotNull(execute.body).utf8(),
            )
        }

    private fun restoredWalletClient(
        nonceValue: String,
        walletAddress: String = "0xwallet",
        walletType: technology.polygon.omswallet.models.WalletType = technology.polygon.omswallet.models.WalletType.Ethereum,
        environment: OMSWalletEnvironment =
            OMSWalletEnvironment(
                walletApiUrl = server.url("/v1/Waas/").toString(),
                indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
            ),
    ): WalletClient = restoredTestWalletClient(environment, testWallet("wallet-main", walletAddress, walletType), nonceValue)

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
                  "tokenID": "0",
                  "balance": "$balance",
                  "blockHash": "0xblock",
                  "blockNumber": 1,
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
