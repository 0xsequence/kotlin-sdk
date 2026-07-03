package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.indexer.IndexerClient
import com.omsclient.kotlin_sdk.models.AbiArg
import com.omsclient.kotlin_sdk.models.SendTransactionRequest
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.wallet.CompleteAuthResult
import com.omsclient.kotlin_sdk.wallet.CredentialSigner
import com.omsclient.kotlin_sdk.wallet.InMemoryOidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.InMemorySessionStore
import com.omsclient.kotlin_sdk.wallet.OidcProviderConfig
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthResult
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthStore
import com.omsclient.kotlin_sdk.wallet.PendingOidcRedirectAuth
import com.omsclient.kotlin_sdk.wallet.TEST_CREDENTIAL_ID
import com.omsclient.kotlin_sdk.wallet.TrackingCredentialSigner
import com.omsclient.kotlin_sdk.wallet.WalletClient
import com.omsclient.kotlin_sdk.wallet.WalletSelectionBehavior
import com.omsclient.kotlin_sdk.wallet.WalletSigningAlgorithm
import com.omsclient.kotlin_sdk.wallet.activeSessionSnapshot
import com.omsclient.kotlin_sdk.wallet.completeAuthResponseBody
import com.omsclient.kotlin_sdk.wallet.walletFixture
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
                    name = "OmsRequestException",
                    code = "RequestFailed",
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
                    name = "OmsRequestException",
                    code = "AuthCommitmentConsumed",
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
                    name = "OmsRequestException",
                    code = "HttpError",
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
                            name = "OmsSessionException",
                            code = "SessionMissing",
                            operation = "wallet.completeEmailAuth",
                            message = "No pending email auth attempt",
                        ),
                    ),
                    labeled(
                        "wallet.completeEmailAuth.invalidLifetime",
                        error(
                            name = "OmsValidationException",
                            code = "ValidationError",
                            operation = "wallet.completeEmailAuth",
                            message = "sessionLifetimeSeconds must be a positive whole number",
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
                            name = "OmsWalletSelectionException",
                            code = "WalletSelectionUnavailable",
                            operation = "wallet.pendingWalletSelection.selectWallet",
                            message = "Selected wallet is not one of the available options",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.selectWallet.stale",
                        error(
                            name = "OmsWalletSelectionException",
                            code = "WalletSelectionStale",
                            operation = "wallet.pendingWalletSelection.selectWallet",
                            message = "Pending wallet selection is no longer active",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.createAndSelectWallet.stale",
                        error(
                            name = "OmsWalletSelectionException",
                            code = "WalletSelectionStale",
                            operation = "wallet.pendingWalletSelection.createAndSelectWallet",
                            message = "Pending wallet selection is no longer active",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.selectWallet.inFlight",
                        error(
                            name = "OmsWalletSelectionException",
                            code = "WalletSelectionInFlight",
                            operation = "wallet.pendingWalletSelection.selectWallet",
                            message = "Pending wallet selection already has an action in flight",
                        ),
                    ),
                    labeled(
                        "wallet.pendingWalletSelection.createAndSelectWallet.inFlight",
                        error(
                            name = "OmsWalletSelectionException",
                            code = "WalletSelectionInFlight",
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
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                        redirectUri = "omsclientkotlindemo://auth/callback",
                    )
                }

            assertEquals(
                listOf(
                    labeled(
                        "wallet.startOidcRedirectAuth.missingRedirectStorage",
                        error(
                            name = "OmsValidationException",
                            code = "ValidationError",
                            operation = "wallet.startOidcRedirectAuth",
                            message = "OIDC redirect auth requires an OIDC redirect auth store",
                        ),
                    ),
                    labeled(
                        "wallet.startOidcRedirectAuth.redirectStorageWriteFailure",
                        error(
                            name = "IOException",
                            code = null,
                            operation = null,
                            message = "OIDC redirect state save failed",
                        ),
                    ),
                    labeled(
                        "wallet.handleOidcRedirectCallback.providerError",
                        error(
                            name = "OmsSessionException",
                            code = "SessionMissing",
                            operation = "wallet.handleOidcRedirectCallback",
                            message = "User cancelled",
                        ),
                    ),
                    labeled(
                        "wallet.handleOidcRedirectCallback.invalidLifetime",
                        error(
                            name = "OmsValidationException",
                            code = "ValidationError",
                            operation = "wallet.handleOidcRedirectCallback",
                            message = "sessionLifetimeSeconds must be a positive whole number",
                        ),
                    ),
                    labeled(
                        "wallet.handleOidcRedirectCallback.signerMismatch",
                        error(
                            name = "OmsSessionException",
                            code = "SessionMissing",
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
                                redirectUri = "omsclientkotlindemo://auth/callback",
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
                    name = "OmsValidationException",
                    code = "ValidationError",
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
                            name = "OmsRequestException",
                            code = "RequestFailed",
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
                            name = "OmsRequestException",
                            code = "RequestFailed",
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
                    name = "OmsRequestException",
                    code = "RequestFailed",
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
                    name = "OmsValidationException",
                    code = "ValidationError",
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
                    name = "OmsTransactionException",
                    code = "TransactionExecutionUnconfirmed",
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
                    name = "OmsTransactionException",
                    code = "TransactionStatusLookupFailed",
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
                    name = "OmsTransactionException",
                    code = "TransactionStatusLookupFailed",
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
                            name = "OmsRequestException",
                            code = "RequestFailed",
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
                    name = "OmsRequestException",
                    code = "HttpError",
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
                    name = "OmsRequestException",
                    code = "HttpError",
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
                    transport = OMSClientHttpClient(failingOkHttpClient("fetch failed")),
                )

            assertEquals(
                error(
                    name = "OmsRequestException",
                    code = "RequestFailed",
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
                    name = "OmsResponseException",
                    code = "InvalidResponse",
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
            OmsUpstreamError(
                service = OmsUpstreamService.Waas,
                name = "WebrpcBadResponse",
                code = "-5",
                message = "bad response",
                status = 502,
            )

        val error =
            OmsRequestException(
                code = OmsSdkErrorCode.HttpError,
                operation = OmsSdkOperation.WalletStartEmailAuth,
                status = 502,
                retryable = true,
                upstreamError = upstreamError,
                message = "bad gateway",
            )

        assertEquals(
            error(
                name = "OmsRequestException",
                code = "HttpError",
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
    ): OMSClient =
        OMSClient(
            publishableKey = "test-publishable-key",
            projectId = "test-project-id",
            environment = testEnvironment(),
            okHttpClient = okHttpClient,
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = oidcRedirectAuthStore,
            credentialSigner = credentialSigner,
        )

    private fun createOmsClientWithSession(okHttpClient: OkHttpClient = OkHttpClient()): OMSClient =
        createOmsClient(okHttpClient = okHttpClient).also { client ->
            client.wallet.restoreSession(activeSessionSnapshot())
        }

    private fun createRestoredWalletClient(okHttpClient: OkHttpClient = OkHttpClient()): WalletClient {
        val client =
            WalletClient(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment = testEnvironment(),
                transport = OMSClientHttpClient(okHttpClient),
                sessionStore =
                    InMemorySessionStore(
                        OMSClientSessionSnapshot(
                            walletId = "wallet-main",
                            walletAddress = "0x9999999999999999999999999999999999999999",
                            signerAddress = TEST_CREDENTIAL_ID,
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                            auth = OMSClientEmailSessionAuth(email = "user@example.com"),
                        ),
                    ),
                credentialSigner = TrackingCredentialSigner(),
                transactionStatusDelay = {},
            )
        assertTrue(client.restorePersistedSession())
        return client
    }

    private fun createIndexerClient(transport: OMSClientHttpClient = OMSClientHttpClient()): IndexerClient =
        IndexerClient(
            publishableKey = "test-publishable-key",
            environment = testEnvironment(),
            transport = transport,
        )

    private fun testEnvironment(): OMSClientEnvironment =
        OMSClientEnvironment(
            walletApiUrl = server.url("/v1/Waas/").toString(),
            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
        )

    private fun testOidcProvider(): OidcProviderConfig =
        OidcProviderConfig(
            issuer = "https://issuer.example",
            clientId = "client-id",
            authorizationUrl = "https://issuer.example/oauth/authorize",
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
                name = "OmsSessionException",
                code = "SessionMissing",
                operation = operation,
                message = message,
            ),
        )

    private fun Throwable.serializePublicFields(): SerializedError {
        val sdkError = this as? OmsSdkException
        return SerializedError(
            name = javaClass.simpleName,
            code = sdkError?.code?.name,
            operation = sdkError?.operation?.id,
            message = message,
            status = sdkError?.status,
            retryable = sdkError?.retryable,
            txnId = sdkError?.txnId,
            upstreamError = sdkError?.upstreamError?.serializePublicFields(),
        )
    }

    private fun OmsUpstreamError.serializePublicFields(): SerializedUpstreamError =
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
