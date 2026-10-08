package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletValidationException
import technology.polygon.omswallet.TronNetwork
import technology.polygon.omswallet.models.AbiArg
import technology.polygon.omswallet.models.FeeOptionSelector
import technology.polygon.omswallet.models.FeeOptionWithBalance
import technology.polygon.omswallet.models.TransactionMode
import technology.polygon.omswallet.models.TransactionStatus
import technology.polygon.omswallet.models.TransactionStatusResolution
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletKeyOrigin
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import java.math.BigInteger
import technology.polygon.omswallet.internal.generated.waas.WalletType as WaasWalletType

class WalletTronTest {
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
    fun sendTronTransactionSendsPlainTrxTransferWithoutDataInNativeMode() =
        runBlocking {
            enqueueJson(sponsoredPrepareResponse("txn-trx"))
            enqueueJson("""{"status":"pending"}""")
            enqueueJson("""{"status":"executed","txnHash":"tron-txid"}""")
            val client = tronWalletClient()

            val result =
                client.sendTronTransaction(
                    network = TronNetwork.Nile,
                    to = TRON_RECIPIENT,
                    value = BigInteger("1000000"),
                )
            val prepare = requireNotNull(server.takeRequest())
            val execute = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/PrepareTronTransaction", prepare.target)
            assertEquals(
                """{"network":"tron:nile","walletId":"wallet-tron","to":"$TRON_RECIPIENT","value":"1000000","mode":"native"}""",
                prepare.utf8Body(),
            )
            assertFalse(prepare.jsonBody().containsKey("data"))
            assertEquals("""{"txnId":"txn-trx"}""", execute.utf8Body())
            assertEquals("txn-trx", result.txnId)
            assertEquals(TransactionStatus.Executed, result.status)
            assertEquals("tron-txid", result.txnHash)
            assertEquals(TransactionStatusResolution.Resolved, result.statusResolution)
        }

    @Test
    fun sendTronTransactionForwardsEmptyHexDataAsPayableFallbackCall() =
        runBlocking {
            enqueueJson(sponsoredPrepareResponse("txn-fallback"))
            enqueueJson("""{"status":"pending"}""")
            val client = tronWalletClient()

            val result =
                client.sendTronTransaction(
                    network = TronNetwork.Nile,
                    to = TRON_NILE_USDT,
                    data = "0x",
                    waitForStatus = false,
                )
            val prepare = requireNotNull(server.takeRequest())

            assertEquals(
                """{"network":"tron:nile","walletId":"wallet-tron","to":"$TRON_NILE_USDT","value":"0","data":"0x","mode":"native"}""",
                prepare.utf8Body(),
            )
            assertEquals("txn-fallback", result.txnId)
            assertEquals(TransactionStatus.Pending, result.status)
        }

    @Test
    fun sendTronTransactionReportsSubmittedTransactionOncePendingStatusHasHash() =
        runBlocking {
            // Tron transactions stay pending until solidified (~1 minute); a hash ends polling.
            enqueueJson(sponsoredPrepareResponse("txn-pending"))
            enqueueJson("""{"status":"pending"}""")
            enqueueJson("""{"status":"pending","txnHash":"tron-pending-txid"}""")
            val client = tronWalletClient()

            val result = client.sendTronTransaction(TronNetwork.Nile, TRON_RECIPIENT, BigInteger.ONE)

            assertEquals(TransactionStatus.Pending, result.status)
            assertEquals("tron-pending-txid", result.txnHash)
            assertEquals(TransactionStatusResolution.Resolved, result.statusResolution)
        }

    @Test
    fun callTronContractPreparesTrc20CallThroughWalletServiceAbiEncoder() =
        runBlocking {
            enqueueJson(sponsoredPrepareResponse("txn-trc20"))
            enqueueJson("""{"status":"pending"}""")
            val client = tronWalletClient()

            client.callTronContract(
                network = TronNetwork.Mainnet,
                contractAddress = TRON_NILE_USDT,
                method = "transfer",
                args =
                    listOf(
                        AbiArg(type = "address", value = JsonPrimitive(TRON_RECIPIENT)),
                        AbiArg(type = "uint256", value = JsonPrimitive("1000000")),
                    ),
                waitForStatus = false,
            )
            val prepare = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/PrepareTronContractCall", prepare.target)
            assertEquals(
                """{"network":"tron:mainnet","walletId":"wallet-tron","contract":"$TRON_NILE_USDT","method":"transfer",""" +
                    """"args":[{"type":"address","value":"$TRON_RECIPIENT"},{"type":"uint256","value":"1000000"}],"mode":"native"}""",
                prepare.utf8Body(),
            )
        }

    @Test
    fun contractCallsRejectFullFunctionSignaturesBeforeAnyRequest() =
        runBlocking {
            val tronError =
                runCatching {
                    tronWalletClient().callTronContract(
                        network = TronNetwork.Nile,
                        contractAddress = TRON_NILE_USDT,
                        method = "transfer(address,uint256)",
                    )
                }.exceptionOrNull()
            val evmError =
                runCatching {
                    walletClient(testWallet("wallet-eth", ETHEREUM_ADDRESS)).callContract(
                        network = Network.AMOY,
                        contractAddress = ETHEREUM_ADDRESS,
                        method = "transfer(address,uint256)",
                    )
                }.exceptionOrNull()

            assertValidationError(tronError, OMSWalletOperation.WalletCallTronContract)
            assertValidationError(evmError, OMSWalletOperation.WalletCallContract)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun sponsoredTronTransactionAcknowledgesEmptyFeeOptionList() =
        runBlocking {
            enqueueJson(sponsoredPrepareResponse("txn-sponsored"))
            enqueueJson("""{"status":"pending"}""")
            val client = tronWalletClient()
            val seenOptions = mutableListOf<List<FeeOptionWithBalance>>()

            client.sendTronTransaction(
                network = TronNetwork.Nile,
                to = TRON_RECIPIENT,
                value = BigInteger.ONE,
                waitForStatus = false,
                selectFeeOption = { options ->
                    seenOptions += options
                    null
                },
            )
            requireNotNull(server.takeRequest())
            val execute = requireNotNull(server.takeRequest())

            assertEquals(listOf(emptyList<FeeOptionWithBalance>()), seenOptions)
            assertEquals("""{"txnId":"txn-sponsored"}""", execute.utf8Body())
        }

    @Test
    fun firstAvailablePaysUnsponsoredTronFeeUsingTrxBalance() =
        runBlocking {
            enqueueJson(
                """
                {
                  "txnId": "txn-burn",
                  "status": "quoted",
                  "feeOptions": [
                    {
                      "token": {"network": "tron:nile", "name": "TRX", "symbol": "TRX", "type": "NATIVE"},
                      "value": "345000",
                      "displayValue": "0.345"
                    }
                  ],
                  "sponsored": false,
                  "expiresAt": "2099-01-01T00:00:00Z"
                }
                """.trimIndent(),
            )
            enqueueJson(
                """
                {
                  "balances": [
                    {
                      "network": "tron:nile",
                      "accountAddress": "$TRON_WALLET",
                      "assetType": "native",
                      "name": "Tron",
                      "symbol": "TRX",
                      "decimals": 6,
                      "balance": "2000000",
                      "formattedBalance": "2",
                      "verificationStatus": "unknown",
                      "verificationSource": "none"
                    }
                  ],
                  "errors": []
                }
                """.trimIndent(),
            )
            enqueueJson("""{"status":"pending"}""")
            val client = tronWalletClient()
            var seenOptions: List<FeeOptionWithBalance> = emptyList()

            val result =
                client.sendTronTransaction(
                    network = TronNetwork.Nile,
                    to = TRON_RECIPIENT,
                    value = BigInteger("1000000"),
                    waitForStatus = false,
                    selectFeeOption = { options ->
                        seenOptions = options
                        FeeOptionSelector.firstAvailable.select(options)
                    },
                )
            requireNotNull(server.takeRequest())
            val balances = requireNotNull(server.takeRequest())
            val execute = requireNotNull(server.takeRequest())

            assertEquals("txn-burn", result.txnId)
            assertEquals("/v1/TronIndexerGateway/GetTokenBalancesDetails", balances.target)
            assertEquals(
                """{"networks":["tron:nile"],"filter":{"accountAddresses":["$TRON_WALLET"],"omitNativeBalances":false},"omitMetadata":true}""",
                balances.utf8Body(),
            )
            assertEquals("2000000", seenOptions.single().availableRaw)
            assertEquals(6, seenOptions.single().decimals)
            assertEquals("""{"txnId":"txn-burn","feeOption":{"token":"TRX","index":0}}""", execute.utf8Body())
        }

    @Test
    fun signsTronMessagesAndTypedDataWithoutEvmNetwork() =
        runBlocking {
            enqueueJson("""{"signature":"0xtron-signature"}""")
            enqueueJson("""{"signature":"0xtron-signature"}""")
            val client = tronWalletClient()
            val typedData =
                buildJsonObject {
                    put("primaryType", "Mail")
                    put("message", buildJsonObject { put("to", TRON_RECIPIENT) })
                }

            assertEquals("0xtron-signature", client.signTronMessage("hello"))
            assertEquals("0xtron-signature", client.signTronTypedData(typedData))
            val signMessage = requireNotNull(server.takeRequest())
            val signTypedData = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/SignMessage", signMessage.target)
            assertEquals("""{"network":"","walletId":"wallet-tron","message":"hello"}""", signMessage.utf8Body())
            assertEquals("/v1/Waas/SignTypedData", signTypedData.target)
            assertEquals(
                buildJsonObject {
                    put("walletId", "wallet-tron")
                    put("network", "")
                    put("typedData", typedData)
                },
                signTypedData.jsonBody(),
            )
        }

    @Test
    fun validatesTronSignaturesWithTronNetworkFamily() =
        runBlocking {
            enqueueJson("""{"isValid":true}""")
            enqueueJson("""{"isValid":true}""")
            val client = tronWalletClient()
            val typedData = buildJsonObject { put("primaryType", "Mail") }

            assertTrue(
                client.isValidTronMessageSignature(
                    message = "hello",
                    signature = "0xsig",
                    walletAddress = TRON_WALLET,
                ),
            )
            assertTrue(client.isValidTronTypedDataSignature(typedData = typedData, signature = "0xsig"))
            val messageRequest = requireNotNull(server.takeRequest())
            val typedDataRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/WaasPublic/IsValidMessageSignature", messageRequest.target)
            assertEquals(
                parseJson(
                    """{"networkFamily":"tron","walletAddress":"$TRON_WALLET","message":"hello","signature":"0xsig"}""",
                ),
                messageRequest.jsonBody(),
            )
            assertEquals("/v1/WaasPublic/IsValidTypedDataSignature", typedDataRequest.target)
            assertEquals(
                parseJson(
                    """{"networkFamily":"tron","walletAddress":"$TRON_WALLET","typedData":{"primaryType":"Mail"},"signature":"0xsig"}""",
                ),
                typedDataRequest.jsonBody(),
            )
        }

    @Test
    fun rejectsTronOperationsForActiveEthereumOrSolanaWalletBeforeAnyRequest() =
        runBlocking {
            listOf(
                testWallet("wallet-eth", ETHEREUM_ADDRESS),
                testWallet("wallet-sol", SOLANA_ADDRESS, WalletType.Solana),
            ).forEach { wallet ->
                val client = walletClient(wallet)
                assertValidationError(
                    runCatching { client.sendTronTransaction(TronNetwork.Nile, TRON_RECIPIENT, BigInteger.ONE) }.exceptionOrNull(),
                    OMSWalletOperation.WalletSendTronTransaction,
                )
                assertValidationError(
                    runCatching { client.callTronContract(TronNetwork.Nile, TRON_NILE_USDT, "transfer") }.exceptionOrNull(),
                    OMSWalletOperation.WalletCallTronContract,
                )
                assertValidationError(
                    runCatching { client.signTronMessage("hello") }.exceptionOrNull(),
                    OMSWalletOperation.WalletSignTronMessage,
                )
                assertValidationError(
                    runCatching { client.signTronTypedData(buildJsonObject {}) }.exceptionOrNull(),
                    OMSWalletOperation.WalletSignTronTypedData,
                )
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun rejectsEvmSolanaAndSmartSessionOperationsForActiveTronWalletBeforeAnyRequest() =
        runBlocking {
            val client = tronWalletClient()

            assertValidationError(
                runCatching { client.sendTransaction(Network.POLYGON, TRON_WALLET_HEX, BigInteger.ONE) }.exceptionOrNull(),
                OMSWalletOperation.WalletSendTransaction,
            )
            assertValidationError(
                runCatching { client.callContract(Network.POLYGON, TRON_WALLET_HEX, "transfer") }.exceptionOrNull(),
                OMSWalletOperation.WalletCallContract,
            )
            assertValidationError(
                runCatching { client.signMessage(Network.POLYGON, "hello") }.exceptionOrNull(),
                OMSWalletOperation.WalletSignMessage,
            )
            assertValidationError(
                runCatching { client.signTypedData(Network.POLYGON, buildJsonObject {}) }.exceptionOrNull(),
                OMSWalletOperation.WalletSignTypedData,
            )
            assertValidationError(
                runCatching { client.signSolanaMessage("hello") }.exceptionOrNull(),
                OMSWalletOperation.WalletSignSolanaMessage,
            )
            assertValidationError(
                runCatching {
                    client.sendSolanaTransfer(
                        network = technology.polygon.omswallet.SolanaNetwork.Devnet,
                        asset = "SOL",
                        to = SOLANA_ADDRESS,
                        amount = BigInteger.ONE,
                        mode = TransactionMode.Native,
                    )
                }.exceptionOrNull(),
                OMSWalletOperation.WalletSendSolanaTransfer,
            )
            assertValidationError(
                runCatching {
                    client.authorizeRemoteAccess(
                        credentialId = "remote-credential",
                        network = Network.AMOY,
                        grants =
                            listOf(
                                technology.polygon.omswallet.models.SmartSessionGrant.NativeTransfer(
                                    to = ETHEREUM_ADDRESS,
                                    limit = BigInteger.ONE,
                                ),
                            ),
                        expiresAt = "2099-01-01T00:00:00Z",
                    )
                }.exceptionOrNull(),
                OMSWalletOperation.WalletAuthorizeRemoteAccess,
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun createsAndActivatesTronWalletThroughTronNetworkFamily() =
        runBlocking {
            enqueueJson(walletResponseBody(walletId = "wallet-new-tron", address = TRON_WALLET, type = WaasWalletType.Tron))
            val client = walletClient(testWallet("wallet-eth", ETHEREUM_ADDRESS))

            val result = client.createWallet(walletType = WalletType.Tron)
            val create = requireNotNull(server.takeRequest())

            val expected =
                Wallet(
                    id = "wallet-new-tron",
                    type = WalletType.Tron,
                    address = TRON_WALLET,
                    keyOrigin = WalletKeyOrigin.Enclave,
                )
            assertEquals("/v1/Waas/CreateWallet", create.target)
            assertEquals("tron", (create.jsonBody()["networkFamily"] as JsonPrimitive).content)
            assertEquals(expected, result.wallet)
            assertEquals(expected, client.activeWallet)
        }

    @Test
    fun rejectsEthereumWalletResponsesWithNonHexAddress() =
        runBlocking {
            enqueueJson(walletResponseBody(walletId = "wallet-bad", address = TRON_WALLET))
            val client = tronWalletClient()

            val error = runCatching { client.useWallet("wallet-bad") }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            error as OMSWalletException
            assertEquals(OMSWalletErrorCode.InvalidResponse, error.code)
            assertEquals("Ethereum wallet response has an invalid address", error.message)
            assertEquals(TRON_WALLET, client.activeWallet?.address)
        }

    private fun tronWalletClient(): WalletClient = walletClient(testWallet("wallet-tron", TRON_WALLET, WalletType.Tron))

    private fun walletClient(wallet: Wallet): WalletClient {
        val client =
            WalletClient.create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment =
                    OMSWalletEnvironment(
                        walletApiUrl = server.url("/v1/Waas/").toString(),
                        indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        solanaIndexerGatewayUrl = server.url("/v1/SolanaIndexerGateway/").toString(),
                        tronIndexerGatewayUrl = server.url("/v1/TronIndexerGateway/").toString(),
                    ),
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
                credentialSigner = TrackingCredentialSigner(nonceValue = "1710000300"),
            )
        assertTrue(client.restorePersistedSession())
        return client
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

    private fun sponsoredPrepareResponse(txnId: String): String =
        """{"txnId":"$txnId","status":"quoted","feeOptions":[],"sponsored":true,"expiresAt":"2099-01-01T00:00:00Z"}"""

    private fun assertValidationError(
        error: Throwable?,
        operation: OMSWalletOperation,
    ) {
        assertTrue("expected validation error for ${operation.id}, got $error", error is OMSWalletValidationException)
        error as OMSWalletException
        assertEquals(OMSWalletErrorCode.ValidationError, error.code)
        assertEquals(operation, error.operation)
    }

    private fun RecordedRequest.utf8Body(): String = requireNotNull(body).utf8()

    private fun RecordedRequest.jsonBody(): JsonObject = parseJson(utf8Body())

    private fun parseJson(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private companion object {
        const val TRON_WALLET: String = "TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL"
        const val TRON_WALLET_HEX: String = "0x8840e6c55b9ada326d211d818c34a994aeced808"
        const val TRON_RECIPIENT: String = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
        const val TRON_NILE_USDT: String = "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf"
        const val ETHEREUM_ADDRESS: String = "0x9999999999999999999999999999999999999999"
        const val SOLANA_ADDRESS: String = "4Nd1mYQbqjVU2aR7cJNPyqW9XjHnBYvWQd7ZxYxvT6uP"
    }
}
