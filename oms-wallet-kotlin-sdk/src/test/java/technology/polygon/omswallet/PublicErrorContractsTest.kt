package technology.polygon.omswallet

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.indexer.IndexerClient
import technology.polygon.omswallet.models.AbiArg
import technology.polygon.omswallet.models.SendTransactionRequest
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.wallet.CompleteAuthResult
import technology.polygon.omswallet.wallet.CredentialSigner
import technology.polygon.omswallet.wallet.InMemoryOidcRedirectAuthStore
import technology.polygon.omswallet.wallet.InMemorySessionStore
import technology.polygon.omswallet.wallet.OidcProviderConfig
import technology.polygon.omswallet.wallet.OidcRedirectAuthResult
import technology.polygon.omswallet.wallet.OidcRedirectAuthStore
import technology.polygon.omswallet.wallet.PendingOidcRedirectAuth
import technology.polygon.omswallet.wallet.TEST_CREDENTIAL_ID
import technology.polygon.omswallet.wallet.TrackingCredentialSigner
import technology.polygon.omswallet.wallet.WalletClient
import technology.polygon.omswallet.wallet.WalletSelectionBehavior
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm
import technology.polygon.omswallet.wallet.activeSessionSnapshot
import technology.polygon.omswallet.wallet.completeAuthResponseBody
import technology.polygon.omswallet.wallet.walletFixture
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PublicErrorContractsTest {
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
    fun snapshotsWaasTransportFailuresWithUpstreamDetails() =
        runBlocking {
            val client = createOmsClient(okHttpClient = failingOkHttpClient("request failed"))

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_REQUEST_FAILED",
                    operation = "wallet.startEmailAuth",
                    message = "WebRPC request failed",
                    retryable = true,
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "WebrpcRequestFailed",
                            code = "-1",
                            message = "WebRPC request failed",
                        ),
                ),
                publicError {
                    client.wallet.startEmailAuth("user@example.com")
                },
            )
        }

    @Test
    fun snapshotsWaasDomainErrorsWithUpstreamDetails() =
        runBlocking {
            enqueueJson("""{"verifier":"verifier-1","loginHint":"user@example.com","challenge":"challenge-1"}""")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .body(
                        """
                        {
                          "error": "CommitmentConsumed",
                          "code": 7008,
                          "msg": "The authentication commitment has already been used",
                          "status": 400
                        }
                        """.trimIndent(),
                    ).build(),
            )

            val client = createOmsClient()
            client.wallet.startEmailAuth("user@example.com")

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_AUTH_COMMITMENT_CONSUMED",
                    operation = "wallet.completeEmailAuth",
                    message = "The authentication commitment has already been used",
                    status = 400,
                    retryable = false,
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "CommitmentConsumed",
                            code = "7008",
                            message = "The authentication commitment has already been used",
                            status = 400,
                        ),
                ),
                publicError {
                    client.wallet.completeEmailAuth("123456")
                },
            )
        }

    @Test
    fun snapshotsWaasHttpResponsesWithUpstreamDetails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .body("<html>Bad Gateway</html>")
                    .build(),
            )

            val client = createOmsClient()

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_HTTP_ERROR",
                    operation = "wallet.startEmailAuth",
                    message = "bad response",
                    status = 502,
                    retryable = true,
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "WebrpcBadResponse",
                            code = "-5",
                            message = "bad response",
                            status = 502,
                        ),
                ),
                publicError {
                    client.wallet.startEmailAuth("user@example.com")
                },
            )

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":"WebrpcServerPanic","code":-6,"msg":"server panic","status":500}""")
                    .build(),
            )

            val serverPanicClient = createOmsClient()

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_HTTP_ERROR",
                    operation = "wallet.startEmailAuth",
                    message = "server panic",
                    status = 500,
                    retryable = true,
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "WebrpcServerPanic",
                            code = "-6",
                            message = "server panic",
                            status = 500,
                        ),
                ),
                publicError {
                    serverPanicClient.wallet.startEmailAuth("user@example.com")
                },
            )
        }

    @Test
    fun snapshotsEmailAuthCompletionLocalStateErrors() =
        runBlocking {
            val client = createOmsClient()

            assertEquals(
                listOf(
                    labeled(
                        "wallet.completeEmailAuth.noPendingAuth",
                        error(
                            name = "OMSWalletSessionException",
                            code = "OMS_SESSION_MISSING",
                            operation = "wallet.completeEmailAuth",
                            message = "No pending email auth attempt",
                        ),
                    ),
                    labeled(
                        "wallet.completeEmailAuth.invalidLifetime",
                        error(
                            name = "OMSWalletValidationException",
                            code = "OMS_VALIDATION_ERROR",
                            operation = "wallet.completeEmailAuth",
                            message = "sessionLifetimeSeconds must be an integer between 1 and 2592000",
                        ),
                    ),
                ),
                publicErrors(
                    "wallet.completeEmailAuth.noPendingAuth" to {
                        client.wallet.completeEmailAuth("123456")
                    },
                    "wallet.completeEmailAuth.invalidLifetime" to {
                        client.wallet.completeEmailAuth(
                            code = "123456",
                            sessionLifetimeSeconds = 0L,
                        )
                    },
                ),
            )
        }

    @Test
    fun snapshotsPendingWalletSelectionLocalStateErrors() =
        runBlocking {
            val errors = mutableListOf<LabeledError>()

            enqueueJson("""{"verifier":"verifier-unavailable","loginHint":"user@example.com","challenge":"challenge"}""")
            enqueueJson(completeAuthResponseBody(listOf(walletFixture("wallet-1", "0x1111111111111111111111111111111111111111"))))
            val unavailableClient = createOmsClient()
            unavailableClient.wallet.startEmailAuth("user@example.com")
            val unavailableSelection =
                (
                    unavailableClient.wallet.completeEmailAuth(
                        code = "123456",
                        walletSelection = WalletSelectionBehavior.Manual,
                    ) as CompleteAuthResult.WalletSelection
                ).pendingSelection
            errors +=
                labeled(
                    "wallet.pendingWalletSelection.selectWallet.unavailable",
                    publicError {
                        unavailableSelection.selectWallet("wallet-missing")
                    },
                )

            enqueueJson("""{"verifier":"old-verifier","loginHint":"old@example.com","challenge":"old-challenge"}""")
            enqueueJson(completeAuthResponseBody(listOf(walletFixture("wallet-old", "0x2222222222222222222222222222222222222222"))))
            enqueueJson("""{"verifier":"new-verifier","loginHint":"new@example.com","challenge":"new-challenge"}""")
            enqueueJson(completeAuthResponseBody(listOf(walletFixture("wallet-new", "0x3333333333333333333333333333333333333333"))))
            val staleClient = createOmsClient()
            staleClient.wallet.startEmailAuth("old@example.com")
            val staleSelection =
                (
                    staleClient.wallet.completeEmailAuth(
                        code = "111111",
                        walletSelection = WalletSelectionBehavior.Manual,
                    ) as CompleteAuthResult.WalletSelection
                ).pendingSelection
            staleClient.wallet.startEmailAuth("new@example.com")
            staleClient.wallet.completeEmailAuth(
                code = "222222",
                walletSelection = WalletSelectionBehavior.Manual,
            )
            errors +=
                labeled(
                    "wallet.pendingWalletSelection.selectWallet.stale",
                    publicError {
                        staleSelection.selectWallet("wallet-old")
                    },
                )
            errors +=
                labeled(
                    "wallet.pendingWalletSelection.createAndSelectWallet.stale",
                    publicError {
                        staleSelection.createAndSelectWallet("stale")
                    },
                )

            enqueueJson("""{"verifier":"in-flight-verifier","loginHint":"user@example.com","challenge":"challenge"}""")
            enqueueJson(completeAuthResponseBody(emptyList()))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"wallet":{"id":"wallet-created","type":"ethereum","address":"0x4444444444444444444444444444444444444444"}}""")
                    .bodyDelay(300, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val inFlightClient = createOmsClient()
            inFlightClient.wallet.startEmailAuth("user@example.com")
            val inFlightSelection =
                (
                    inFlightClient.wallet.completeEmailAuth(
                        code = "333333",
                        walletSelection = WalletSelectionBehavior.Manual,
                    ) as CompleteAuthResult.WalletSelection
                ).pendingSelection
            val firstSelection =
                async {
                    inFlightSelection.createAndSelectWallet("fresh")
                }
            yield()
            errors +=
                labeled(
                    "wallet.pendingWalletSelection.selectWallet.inFlight",
                    publicError {
                        inFlightSelection.selectWallet("wallet-created")
                    },
                )
            errors +=
                labeled(
                    "wallet.pendingWalletSelection.createAndSelectWallet.inFlight",
                    publicError {
                        inFlightSelection.createAndSelectWallet("duplicate")
                    },
                )
            firstSelection.await()

            assertEquals(
                listOf(
                    labeled(
                        "wallet.pendingWalletSelection.selectWallet.unavailable",
                        error(
                            name = "OMSWalletSelectionException",
                            code = "OMS_WALLET_SELECTION_UNAVAILABLE",
                            operation = "wallet.pendingWalletSelection.selectWallet",
                            message = "Selected wallet is not one of the available options",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.selectWallet.stale",
                        error(
                            name = "OMSWalletSelectionException",
                            code = "OMS_WALLET_SELECTION_STALE",
                            operation = "wallet.pendingWalletSelection.selectWallet",
                            message = "Pending wallet selection is no longer active",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.createAndSelectWallet.stale",
                        error(
                            name = "OMSWalletSelectionException",
                            code = "OMS_WALLET_SELECTION_STALE",
                            operation = "wallet.pendingWalletSelection.createAndSelectWallet",
                            message = "Pending wallet selection is no longer active",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.selectWallet.inFlight",
                        error(
                            name = "OMSWalletSelectionException",
                            code = "OMS_WALLET_SELECTION_IN_FLIGHT",
                            operation = "wallet.pendingWalletSelection.selectWallet",
                            message = "Pending wallet selection already has an action in flight",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.createAndSelectWallet.inFlight",
                        error(
                            name = "OMSWalletSelectionException",
                            code = "OMS_WALLET_SELECTION_IN_FLIGHT",
                            operation = "wallet.pendingWalletSelection.createAndSelectWallet",
                            message = "Pending wallet selection already has an action in flight",
                        ),
                    ),
                ),
                errors,
            )
        }

    @Test
    fun snapshotsMissingSessionContractsForProtectedWalletMethods() =
        runBlocking {
            val client = createOmsClient()

            assertEquals(
                listOf(
                    missingSession("wallet.listWallets"),
                    missingSession("wallet.useWallet"),
                    missingSession("wallet.createWallet"),
                    missingSession("wallet.getIdToken"),
                    missingSession("wallet.signMessage"),
                    missingSession("wallet.signTypedData"),
                    missingSession("wallet.sendTransaction"),
                    missingSession("wallet.callContract"),
                    missingSession("wallet.getTransactionStatus"),
                    missingSession("wallet.listAccess"),
                    missingSession("wallet.listAccessPages"),
                    missingSession("wallet.revokeAccess"),
                ),
                publicErrors(
                    "wallet.listWallets" to {
                        client.wallet.listWallets()
                    },
                    "wallet.useWallet" to {
                        client.wallet.useWallet("wallet-1")
                    },
                    "wallet.createWallet" to {
                        client.wallet.createWallet()
                    },
                    "wallet.getIdToken" to {
                        client.wallet.getIdToken()
                    },
                    "wallet.signMessage" to {
                        client.wallet.signMessage(Network.POLYGON, "hello")
                    },
                    "wallet.signTypedData" to {
                        client.wallet.signTypedData(
                            network = Network.POLYGON,
                            typedData =
                                buildJsonObject {
                                    put("contents", "hello")
                                },
                        )
                    },
                    "wallet.sendTransaction" to {
                        client.wallet.sendTransaction(
                            network = Network.POLYGON,
                            to = "0x1111111111111111111111111111111111111111",
                            value = BigInteger.ZERO,
                        )
                    },
                    "wallet.callContract" to {
                        client.wallet.callContract(
                            network = Network.POLYGON,
                            contract = "0x2222222222222222222222222222222222222222",
                            method = "transfer(address,uint256)",
                            args =
                                listOf(
                                    AbiArg("address", JsonPrimitive("0x3333333333333333333333333333333333333333")),
                                    AbiArg("uint256", JsonPrimitive("1")),
                                ),
                        )
                    },
                    "wallet.getTransactionStatus" to {
                        client.wallet.getTransactionStatus("txn-1")
                    },
                    "wallet.listAccess" to {
                        client.wallet.listAccess()
                    },
                    "wallet.listAccessPages" to {
                        client.wallet.listAccessPages().toList()
                    },
                    "wallet.revokeAccess" to {
                        client.wallet.revokeAccess("credential-1")
                    },
                ),
            )
        }

    @Test
    fun snapshotsOidcLocalErrorContractsWithoutUpstreamDetails() =
        runBlocking {
            val missingStoreClient = createOmsClient(oidcRedirectAuthStore = null)
            val providerErrorClient = createOmsClient()
            val invalidLifetimeClient = createOmsClient()
            val signerMismatchSigner = MutableCredentialSigner()
            val signerMismatchClient = createOmsClient(credentialSigner = signerMismatchSigner)
            val storageFailureClient =
                createOmsClient(
                    oidcRedirectAuthStore =
                        ThrowingOidcRedirectAuthStore(
                            IOException("OIDC redirect state save failed"),
                        ),
                )

            enqueueJson("""{"verifier":"verifier-oidc","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
            val providerErrorStart =
                providerErrorClient.wallet.startOidcRedirectAuth(
                    provider = testOidcProvider(),
                )
            val providerFailure =
                providerErrorClient.wallet.handleOidcRedirectCallback(
                    callbackUrl =
                        "omsclientkotlindemo://auth/callback" +
                            "?error=access_denied&error_description=User%20cancelled&state=${providerErrorStart.state}",
                )

            enqueueJson("""{"verifier":"verifier-oidc","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
            val invalidLifetimeStart =
                invalidLifetimeClient.wallet.startOidcRedirectAuth(
                    provider = testOidcProvider(),
                )
            val invalidLifetimeFailure =
                invalidLifetimeClient.wallet.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${invalidLifetimeStart.state}",
                    sessionLifetimeSeconds = 0L,
                )

            enqueueJson("""{"verifier":"verifier-oidc","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
            val signerMismatchStart =
                signerMismatchClient.wallet.startOidcRedirectAuth(
                    provider = testOidcProvider(),
                )
            signerMismatchSigner.credentialIdValue = "0x04" + "99".repeat(64)
            val signerMismatchFailure =
                signerMismatchClient.wallet.handleOidcRedirectCallback(
                    callbackUrl =
                        "omsclientkotlindemo://auth/callback" +
                            "?code=auth-code&state=${signerMismatchStart.state}",
                )

            enqueueJson("""{"verifier":"verifier-oidc","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
            val storageFailure =
                publicError {
                    storageFailureClient.wallet.startOidcRedirectAuth(
                        provider = testOidcProvider(),
                    )
                }

            assertEquals(
                listOf(
                    labeled(
                        "wallet.startOidcRedirectAuth.missingRedirectStorage",
                        error(
                            name = "OMSWalletValidationException",
                            code = "OMS_VALIDATION_ERROR",
                            operation = "wallet.startOidcRedirectAuth",
                            message = "OIDC redirect auth requires an OIDC redirect auth store",
                        ),
                    ),
                    labeled(
                        "wallet.startOidcRedirectAuth.redirectStorageWriteFailure",
                        error(
                            name = "OMSWalletStorageException",
                            code = "OMS_STORAGE_ERROR",
                            operation = "wallet.startOidcRedirectAuth",
                            message = "OIDC redirect auth state persistence failed",
                        ),
                    ),
                    labeled(
                        "wallet.handleOidcRedirectCallback.providerError",
                        error(
                            name = "OMSWalletValidationException",
                            code = "OMS_VALIDATION_ERROR",
                            operation = "wallet.handleOidcRedirectCallback",
                            message = "User cancelled",
                        ),
                    ),
                    labeled(
                        "wallet.handleOidcRedirectCallback.invalidLifetime",
                        error(
                            name = "OMSWalletValidationException",
                            code = "OMS_VALIDATION_ERROR",
                            operation = "wallet.handleOidcRedirectCallback",
                            message = "sessionLifetimeSeconds must be an integer between 1 and 2592000",
                        ),
                    ),
                    labeled(
                        "wallet.handleOidcRedirectCallback.signerMismatch",
                        error(
                            name = "OMSWalletSessionException",
                            code = "OMS_SESSION_MISSING",
                            operation = "wallet.handleOidcRedirectCallback",
                            message = "OIDC redirect auth signer mismatch",
                        ),
                    ),
                ),
                listOf(
                    labeled(
                        "wallet.startOidcRedirectAuth.missingRedirectStorage",
                        publicError {
                            missingStoreClient.wallet.startOidcRedirectAuth(
                                provider = testOidcProvider(),
                            )
                        },
                    ),
                    labeled("wallet.startOidcRedirectAuth.redirectStorageWriteFailure", storageFailure),
                    labeled("wallet.handleOidcRedirectCallback.providerError", oidcFailure(providerFailure)),
                    labeled("wallet.handleOidcRedirectCallback.invalidLifetime", oidcFailure(invalidLifetimeFailure)),
                    labeled("wallet.handleOidcRedirectCallback.signerMismatch", oidcFailure(signerMismatchFailure)),
                ),
            )
        }

    @Test
    fun snapshotsSdkLocalErrorsWithoutUpstreamDetails() =
        runBlocking {
            val client = createOmsClientWithSession()

            assertEquals(
                error(
                    name = "OMSWalletValidationException",
                    code = "OMS_VALIDATION_ERROR",
                    operation = "wallet.sendTransaction",
                    message = "Transaction value must be non-negative",
                ),
                publicError {
                    client.wallet.sendTransaction(
                        network = Network.POLYGON,
                        to = "0x1111111111111111111111111111111111111111",
                        value = BigInteger.ONE.negate(),
                    )
                },
            )
        }

    @Test
    fun snapshotsSignatureValidationBackendFailuresWithUpstreamDetails() =
        runBlocking {
            val client = createOmsClientWithSession(okHttpClient = failingOkHttpClient("request failed"))

            assertEquals(
                listOf(
                    labeled(
                        "wallet.isValidMessageSignature",
                        error(
                            name = "OMSWalletRequestException",
                            code = "OMS_REQUEST_FAILED",
                            operation = "wallet.isValidMessageSignature",
                            message = "WebRPC request failed",
                            retryable = true,
                            upstreamError =
                                upstream(
                                    service = "Waas",
                                    name = "WebrpcRequestFailed",
                                    code = "-1",
                                    message = "WebRPC request failed",
                                ),
                        ),
                    ),
                    labeled(
                        "wallet.isValidTypedDataSignature",
                        error(
                            name = "OMSWalletRequestException",
                            code = "OMS_REQUEST_FAILED",
                            operation = "wallet.isValidTypedDataSignature",
                            message = "WebRPC request failed",
                            retryable = true,
                            upstreamError =
                                upstream(
                                    service = "Waas",
                                    name = "WebrpcRequestFailed",
                                    code = "-1",
                                    message = "WebRPC request failed",
                                ),
                        ),
                    ),
                ),
                publicErrors(
                    "wallet.isValidMessageSignature" to {
                        client.wallet.isValidMessageSignature(
                            network = Network.POLYGON,
                            message = "hello",
                            signature = "0xmessage",
                        )
                    },
                    "wallet.isValidTypedDataSignature" to {
                        client.wallet.isValidTypedDataSignature(
                            network = Network.POLYGON,
                            typedData =
                                buildJsonObject {
                                    put("contents", "hello")
                                },
                            signature = "0xtyped",
                        )
                    },
                ),
            )
        }

    @Test
    fun snapshotsDirectTransactionStatusBackendErrorsWithUpstreamDetails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"error":"TransactionNotFound","code":7308,"msg":"Transaction not found","status":404}""")
                    .build(),
            )

            val client = createRestoredWalletClient()

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_REQUEST_FAILED",
                    operation = "wallet.getTransactionStatus",
                    message = "Transaction not found",
                    status = 404,
                    retryable = false,
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "TransactionNotFound",
                            code = "7308",
                            message = "Transaction not found",
                            status = 404,
                        ),
                ),
                publicError {
                    client.getTransactionStatus("txn-missing")
                },
            )
        }

    @Test
    fun snapshotsTransactionLocalValidationErrorsWithoutUpstreamDetails() =
        runBlocking {
            enqueueJson(prepareTransactionResponse(txnId = "txn-no-fee-options", feeOptions = "[]", sponsored = false))

            val client = createRestoredWalletClient()

            assertEquals(
                error(
                    name = "OMSWalletValidationException",
                    code = "OMS_VALIDATION_ERROR",
                    operation = "wallet.sendTransaction",
                    message = "No fee options available for unsponsored transaction",
                ),
                publicError {
                    client.sendTransaction(
                        network = Network.POLYGON,
                        request =
                            SendTransactionRequest(
                                to = "0x1111111111111111111111111111111111111111",
                                value = BigInteger.ZERO,
                            ),
                    )
                },
            )
        }

    @Test
    fun snapshotsTransactionExecuteFailuresAsUnconfirmedWrites() =
        runBlocking {
            enqueueJson(prepareTransactionResponse(txnId = "txn-execute", feeOptions = "[]", sponsored = true))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .body("<html>Bad Gateway</html>")
                    .build(),
            )

            val client = createRestoredWalletClient()

            assertEquals(
                error(
                    name = "OMSWalletTransactionException",
                    code = "OMS_TRANSACTION_EXECUTION_UNCONFIRMED",
                    operation = "wallet.execute",
                    message = "Transaction execution failed before status could be confirmed",
                    status = 502,
                    retryable = false,
                    txnId = "txn-execute",
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "WebrpcBadResponse",
                            code = "-5",
                            message = "bad response",
                            status = 502,
                        ),
                ),
                publicError {
                    client.sendTransaction(
                        network = Network.POLYGON,
                        request =
                            SendTransactionRequest(
                                to = "0x1111111111111111111111111111111111111111",
                                value = BigInteger.ZERO,
                            ),
                    )
                },
            )
        }

    @Test
    fun snapshotsTransactionStatusPollingFailuresWithTxnAndUpstreamDetails() =
        runBlocking {
            enqueueJson(prepareTransactionResponse(txnId = "txn-status", feeOptions = "[]", sponsored = true))
            enqueueJson("""{"status":"pending"}""")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"error":"TransactionNotFound","code":7308,"msg":"Transaction not found","status":404}""")
                    .build(),
            )

            val client = createRestoredWalletClient()

            assertEquals(
                error(
                    name = "OMSWalletTransactionException",
                    code = "OMS_TRANSACTION_STATUS_LOOKUP_FAILED",
                    operation = "wallet.transactionStatus",
                    message = "Transaction was submitted, but status polling failed",
                    status = 404,
                    retryable = true,
                    txnId = "txn-status",
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "TransactionNotFound",
                            code = "7308",
                            message = "Transaction not found",
                            status = 404,
                        ),
                ),
                publicError {
                    client.sendTransaction(
                        network = Network.POLYGON,
                        request =
                            SendTransactionRequest(
                                to = "0x1111111111111111111111111111111111111111",
                                value = BigInteger.ZERO,
                            ),
                    )
                },
            )
        }

    @Test
    fun snapshotsTransactionStatusPollingTransportFailuresWithTxnAndUpstreamDetails() =
        runBlocking {
            enqueueJson(prepareTransactionResponse(txnId = "txn-transport", feeOptions = "[]", sponsored = true))
            enqueueJson("""{"status":"pending"}""")

            val client = createRestoredWalletClient(okHttpClient = failingOnRequestOkHttpClient(3, "request failed"))

            assertEquals(
                error(
                    name = "OMSWalletTransactionException",
                    code = "OMS_TRANSACTION_STATUS_LOOKUP_FAILED",
                    operation = "wallet.transactionStatus",
                    message = "Transaction was submitted, but status polling failed",
                    retryable = true,
                    txnId = "txn-transport",
                    upstreamError =
                        upstream(
                            service = "Waas",
                            name = "WebrpcRequestFailed",
                            code = "-1",
                            message = "WebRPC request failed",
                        ),
                ),
                publicError {
                    client.sendTransaction(
                        network = Network.POLYGON,
                        request =
                            SendTransactionRequest(
                                to = "0x1111111111111111111111111111111111111111",
                                value = BigInteger.ZERO,
                            ),
                    )
                },
            )
        }

    @Test
    fun snapshotsAccessBackendErrorsWithUpstreamDetails() =
        runBlocking {
            repeat(3) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(401)
                        .body("""{"error":"Unauthorized","code":7207,"msg":"Unauthorized","status":401}""")
                        .build(),
                )
            }

            val client = createRestoredWalletClient()
            val expected =
                listOf(
                    "wallet.listAccess",
                    "wallet.listAccessPages",
                    "wallet.revokeAccess",
                ).map { operation ->
                    labeled(
                        operation,
                        error(
                            name = "OMSWalletRequestException",
                            code = "OMS_REQUEST_FAILED",
                            operation = operation,
                            message = "Unauthorized",
                            status = 401,
                            retryable = false,
                            upstreamError =
                                upstream(
                                    service = "Waas",
                                    name = "Unauthorized",
                                    code = "7207",
                                    message = "Unauthorized",
                                    status = 401,
                                ),
                        ),
                    )
                }

            assertEquals(
                expected,
                publicErrors(
                    "wallet.listAccess" to {
                        client.listAccess()
                    },
                    "wallet.listAccessPages" to {
                        client.listAccessPages().toList()
                    },
                    "wallet.revokeAccess" to {
                        client.revokeAccess("credential-1")
                    },
                ),
            )
        }

    @Test
    fun snapshotsIndexerBackendErrorsWithUpstreamDetails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .body("""{"error":"Unavailable","code":"INDEXER_UNAVAILABLE","message":"Indexer is unavailable"}""")
                    .build(),
            )

            val client = createIndexerClient()

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_HTTP_ERROR",
                    operation = "indexer.getBalances",
                    message = "Indexer is unavailable",
                    status = 503,
                    retryable = true,
                    upstreamError =
                        upstream(
                            service = "Indexer",
                            name = "Unavailable",
                            code = "INDEXER_UNAVAILABLE",
                            message = "Indexer is unavailable",
                            status = 503,
                        ),
                ),
                publicError {
                    client.getBalances(
                        networks = listOf(Network.POLYGON),
                        walletAddress = "0x9999999999999999999999999999999999999999",
                        includeMetadata = false,
                    )
                },
            )
        }

    @Test
    fun snapshotsIndexerNonJsonHttpErrorsWithoutRawUpstreamBodies() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .body("<html>Bad Gateway</html>")
                    .build(),
            )

            val client = createIndexerClient()
            val failure =
                publicError {
                    client.getBalances(
                        networks = listOf(Network.POLYGON),
                        walletAddress = "0x9999999999999999999999999999999999999999",
                        includeMetadata = false,
                    )
                }

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_HTTP_ERROR",
                    operation = "indexer.getBalances",
                    message = "indexer.getBalances failed with HTTP 502",
                    status = 502,
                    retryable = true,
                    upstreamError =
                        upstream(
                            service = "Indexer",
                            message = "indexer.getBalances failed with HTTP 502",
                            status = 502,
                        ),
                ),
                failure,
            )
            assertTrue(requireNotNull(failure.message).contains("Bad Gateway").not())
            assertTrue(requireNotNull(failure.upstreamError?.message).contains("Bad Gateway").not())
        }

    @Test
    fun snapshotsIndexerTransportFailuresWithUpstreamDetails() =
        runBlocking {
            val client =
                createIndexerClient(
                    transport = OMSWalletHttpClient(failingOkHttpClient("fetch failed")),
                )

            assertEquals(
                error(
                    name = "OMSWalletRequestException",
                    code = "OMS_REQUEST_FAILED",
                    operation = "indexer.getBalances",
                    message = "fetch failed",
                    retryable = true,
                    upstreamError =
                        upstream(
                            service = "Indexer",
                            name = "IOException",
                            message = "fetch failed",
                        ),
                ),
                publicError {
                    client.getBalances(
                        networks = listOf(Network.POLYGON),
                        walletAddress = "0x9999999999999999999999999999999999999999",
                        includeMetadata = false,
                    )
                },
            )
        }

    @Test
    fun snapshotsIndexerMalformedResponseErrorsWithUpstreamDetails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("not-json")
                    .build(),
            )

            val client = createIndexerClient()

            assertEquals(
                error(
                    name = "OMSWalletResponseException",
                    code = "OMS_INVALID_RESPONSE",
                    operation = "indexer.getBalances",
                    message = "Invalid JSON response from indexer.getBalances",
                    status = 200,
                    upstreamError =
                        upstream(
                            service = "Indexer",
                            message = "Invalid JSON response from indexer.getBalances",
                            status = 200,
                        ),
                ),
                publicError {
                    client.getBalances(
                        networks = listOf(Network.POLYGON),
                        walletAddress = "0x9999999999999999999999999999999999999999",
                        includeMetadata = false,
                    )
                },
            )
        }

    @Test
    fun snapshotsExportedErrorHelperAndSubclassFields() {
        val upstreamError =
            OMSWalletUpstreamError(
                service = OMSWalletUpstreamService.Waas,
                name = "WebrpcBadResponse",
                code = "-5",
                message = "bad response",
                status = 502,
            )

        val error =
            OMSWalletRequestException(
                code = OMSWalletErrorCode.HttpError,
                operation = OMSWalletOperation.WalletStartEmailAuth,
                status = 502,
                retryable = true,
                upstreamError = upstreamError,
                message = "bad gateway",
            )

        assertEquals(
            error(
                name = "OMSWalletRequestException",
                code = "OMS_HTTP_ERROR",
                operation = "wallet.startEmailAuth",
                message = "bad gateway",
                status = 502,
                retryable = true,
                upstreamError =
                    upstream(
                        service = "Waas",
                        name = "WebrpcBadResponse",
                        code = "-5",
                        message = "bad response",
                        status = 502,
                    ),
            ),
            error.serializePublicFields(),
        )
    }

    private fun createOmsClient(
        okHttpClient: OkHttpClient = OkHttpClient(),
        oidcRedirectAuthStore: OidcRedirectAuthStore? = InMemoryOidcRedirectAuthStore(),
        credentialSigner: CredentialSigner = TrackingCredentialSigner(),
    ): OMSWallet =
        OMSWallet(
            publishableKey = "test-publishable-key",
            projectId = "test-project-id",
            environment = testEnvironment(),
            okHttpClient = okHttpClient,
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = oidcRedirectAuthStore,
            credentialSigner = credentialSigner,
        )

    private fun createOmsClientWithSession(okHttpClient: OkHttpClient = OkHttpClient()): OMSWallet =
        createOmsClient(okHttpClient = okHttpClient).also { client ->
            client.wallet.restoreSession(activeSessionSnapshot())
        }

    private fun createRestoredWalletClient(okHttpClient: OkHttpClient = OkHttpClient()): WalletClient {
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                transport = OMSWalletHttpClient(okHttpClient),
                sessionStore =
                    InMemorySessionStore(
                        OMSWalletSessionSnapshot(
                            walletId = "wallet-main",
                            walletAddress = "0x9999999999999999999999999999999999999999",
                            signerAddress = TEST_CREDENTIAL_ID,
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                            auth = OMSWalletEmailSessionAuth(email = "user@example.com"),
                        ),
                    ),
                credentialSigner = TrackingCredentialSigner(),
                transactionStatusDelay = {},
            )
        assertTrue(client.restorePersistedSession())
        return client
    }

    private fun createIndexerClient(transport: OMSWalletHttpClient = OMSWalletHttpClient()): IndexerClient =
        IndexerClient(
            publishableKey = "test-publishable-key",
            environment = testEnvironment(),
            transport = transport,
        )

    private fun testEnvironment(): OMSWalletEnvironment =
        OMSWalletEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )

    private fun testOidcProvider(): OidcProviderConfig =
        OidcProviderConfig(
            issuer = "https://issuer.example",
            clientId = "client-id",
            authorizationUrl = "https://issuer.example/oauth/authorize",
            providerRedirectUri = "omsclientkotlindemo://auth/callback",
        )

    private fun failingOkHttpClient(message: String): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor {
                    throw IOException(message)
                },
            ).build()

    private fun failingOnRequestOkHttpClient(
        requestNumber: Int,
        message: String,
    ): OkHttpClient {
        val requestCount = AtomicInteger()
        return OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    if (requestCount.incrementAndGet() == requestNumber) {
                        throw IOException(message)
                    }
                    chain.proceed(chain.request())
                },
            ).build()
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

    private fun prepareTransactionResponse(
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
          "expiresAt": "2099-01-01T00:00:00Z"
        }
        """.trimIndent()

    private suspend fun publicErrors(vararg cases: Pair<String, suspend () -> Any?>): List<LabeledError> =
        cases.map { (label, action) ->
            labeled(label, publicError(action))
        }

    private suspend fun publicError(action: suspend () -> Any?): SerializedError {
        try {
            action()
        } catch (throwable: Throwable) {
            return throwable.serializePublicFields()
        }

        error("Expected public API call to fail")
    }

    private fun oidcFailure(result: OidcRedirectAuthResult): SerializedError =
        when (result) {
            is OidcRedirectAuthResult.Failed -> result.error.serializePublicFields()
            else -> error("Expected OIDC redirect result to fail, got $result")
        }

    private fun missingSession(
        operation: String,
        message: String = "No active wallet session",
    ): LabeledError =
        labeled(
            operation,
            error(
                name = "OMSWalletSessionException",
                code = "OMS_SESSION_MISSING",
                operation = operation,
                message = message,
            ),
        )

    private fun Throwable.serializePublicFields(): SerializedError {
        val sdkError = this as? OMSWalletException
        return SerializedError(
            name = javaClass.simpleName,
            code = sdkError?.code?.id,
            operation = sdkError?.operation?.id,
            message = message,
            status = sdkError?.status,
            retryable = sdkError?.retryable,
            txnId = sdkError?.txnId,
            upstreamError = sdkError?.upstreamError?.serializePublicFields(),
        )
    }

    private fun OMSWalletUpstreamError.serializePublicFields(): SerializedUpstreamError =
        SerializedUpstreamError(
            service = service.name,
            name = name,
            code = code,
            message = message,
            status = status,
        )

    private fun labeled(
        label: String,
        error: SerializedError,
    ): LabeledError = LabeledError(label = label, error = error)

    private fun error(
        name: String,
        code: String?,
        operation: String?,
        message: String?,
        status: Int? = null,
        retryable: Boolean? = null,
        txnId: String? = null,
        upstreamError: SerializedUpstreamError? = null,
    ): SerializedError =
        SerializedError(
            name = name,
            code = code,
            operation = operation,
            message = message,
            status = status,
            retryable = retryable,
            txnId = txnId,
            upstreamError = upstreamError,
        )

    private fun upstream(
        service: String,
        name: String? = null,
        code: String? = null,
        message: String? = null,
        status: Int? = null,
    ): SerializedUpstreamError =
        SerializedUpstreamError(
            service = service,
            name = name,
            code = code,
            message = message,
            status = status,
        )

    private data class LabeledError(
        val label: String,
        val error: SerializedError,
    )

    private class MutableCredentialSigner(
        var credentialIdValue: String = TEST_CREDENTIAL_ID,
    ) : CredentialSigner {
        override val signingAlgorithm: WalletSigningAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256

        override suspend fun credentialId(): String = credentialIdValue

        override suspend fun nextNonce(): String = "1710000999"

        override suspend fun sign(preimage: String): String = "0x" + "22".repeat(64)

        override fun hasCredential(): Boolean = true

        override fun clear() = Unit
    }

    private data class SerializedError(
        val name: String?,
        val code: String?,
        val operation: String?,
        val message: String?,
        val status: Int?,
        val retryable: Boolean?,
        val txnId: String?,
        val upstreamError: SerializedUpstreamError?,
    )

    private data class SerializedUpstreamError(
        val service: String?,
        val name: String?,
        val code: String?,
        val message: String?,
        val status: Int?,
    )

    private class ThrowingOidcRedirectAuthStore(
        private val saveFailure: Throwable,
    ) : OidcRedirectAuthStore {
        override fun load(): PendingOidcRedirectAuth? = null

        override fun save(pending: PendingOidcRedirectAuth): Unit = throw saveFailure

        override fun clear() = Unit
    }
}
