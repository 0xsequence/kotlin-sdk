package technology.polygon.omswallet.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletUpstreamService
import technology.polygon.omswallet.internal.generated.waas.AuthMode
import technology.polygon.omswallet.internal.generated.waas.CommitVerifierRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthRequest
import technology.polygon.omswallet.internal.generated.waas.CreateWalletRequest
import technology.polygon.omswallet.internal.generated.waas.Identity
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.ListWalletsRequest
import technology.polygon.omswallet.internal.generated.waas.Page
import technology.polygon.omswallet.internal.generated.waas.UseWalletRequest
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.Wallet
import technology.polygon.omswallet.internal.generated.waas.WalletType
import technology.polygon.omswallet.models.FeeOptionSelection
import technology.polygon.omswallet.models.SendTransactionRequest
import technology.polygon.omswallet.models.TransactionMode
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import java.math.BigInteger

class WalletEmailAuthTest {
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
    fun startEmailAuthSendsCanonicalSignedRequestAndKeepsPendingSessionInMemory() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val store = InMemorySessionStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000100"),
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            val expectedPayload =
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.Email,
                        authMode = AuthMode.OTP,
                        metadata = emptyMap(),
                        handle = "user@example.com",
                    ),
                )
            val expectedWalletSignatureHeader = expectedWalletSignatureHeader(nonce = "1710000100")

            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals("POST", request.method)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals("test-publishable-key", request.headers[OMSWalletEnvironment.accessKeyHeaderName])
            assertEquals(null, request.headers["Origin"])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(
                expectedWalletSignatureHeader.removePrefix(OMSWalletEnvironment.walletSignatureHeaderPrefix),
                request.headers[OMSWalletEnvironment.walletSignatureHeaderName],
            )
            val session = client.snapshotSession()
            assertNotNull(session)
            assertEquals("challenge", session?.challenge)
            assertEquals("verifier-123", session?.verifier)
            assertEquals(
                TEST_CREDENTIAL_ID,
                session?.signerAddress,
            )
            assertEquals(WalletSigningAlgorithm.ECDSA_P256_SHA256, session?.signerKeyType)
            assertNull(store.snapshot)
            assertEquals(0, store.saveCalls)
        }

    @Test
    fun startEmailAuthClearsPendingOidcRedirectAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore(pendingOidcRedirectAuthFixture())
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
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000100"),
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())
            val session = client.snapshotSession()

            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals("verifier-123", session?.verifier)
            assertNull(redirectStore.pending)
            assertEquals(1, redirectStore.clearCalls)
        }

    @Test
    fun startEmailAuthRequestFailedNormalizesGeneratedStatus400ToNoStatus() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .body("""{"error":"WebrpcRequestFailed","code":-1,"msg":"request failed","status":400}""")
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000100"),
                )

            val failure =
                runCatching {
                    client.startEmailAuth("user@example.com")
                }.exceptionOrNull() as? OMSWalletException

            requireNotNull(failure)
            assertEquals(OMSWalletErrorCode.RequestFailed, failure.code)
            assertEquals(OMSWalletOperation.WalletStartEmailAuth, failure.operation)
            assertEquals(null, failure.status)
            assertEquals(true, failure.retryable)
            assertEquals(OMSWalletUpstreamService.Waas, failure.upstreamError?.service)
            assertEquals("WebrpcRequestFailed", failure.upstreamError?.name)
            assertEquals("-1", failure.upstreamError?.code)
            assertEquals("request failed", failure.upstreamError?.message)
            assertEquals(null, failure.upstreamError?.status)
        }

    @Test
    fun startEmailAuthReplacesActiveWalletSessionWithPendingAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )

            val activeSession = activeSessionSnapshot()
            val store = InMemorySessionStore(activeSession)
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.restoreSession(activeSession)

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            val session = client.snapshotSession()
            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals("challenge", session?.challenge)
            assertEquals("verifier-123", session?.verifier)
            assertNull(session?.walletId)
            assertNull(session?.walletAddress)
            assertNull(store.snapshot)
        }

    @Test
    fun startEmailAuthUsesWebCryptoCredentialSignerWalletSignatureHeader() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val signer = MockWebCryptoCredentialSigner()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = signer,
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals(
                "alg=\"ecdsa-p256-sha256\", scope=\"test-project-id\"," +
                    " cred=\"${signer.credentialIdValue}\", nonce=42, sig=\"${signer.signatureValue}\"",
                request.headers[OMSWalletEnvironment.walletSignatureHeaderName],
            )
            assertEquals(WalletSigningAlgorithm.ECDSA_P256_SHA256, client.snapshotSession()?.signerKeyType)
            assertEquals(1, signer.signCalls)
        }

    @Test
    fun startEmailAuthUsesGeneratedWalletRouteEvenWhenEnvironmentPathDiffers() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/custom/wallet/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000105"),
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())
            val expectedPayload =
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.Email,
                        authMode = AuthMode.OTP,
                        metadata = emptyMap(),
                        handle = "user@example.com",
                    ),
                )
            val expectedWalletSignatureHeader = expectedWalletSignatureHeader(nonce = "1710000105")

            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals(
                expectedWalletSignatureHeader.removePrefix(OMSWalletEnvironment.walletSignatureHeaderPrefix),
                request.headers[OMSWalletEnvironment.walletSignatureHeaderName],
            )
        }

    @Test
    fun startEmailAuthClearsStateWhenCommitVerifierFails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":"InternalError","code":5000,"msg":"commit verifier failed","status":500}""")
                    .build(),
            )

            val signer = TrackingCredentialSigner(nonceValue = "1710000106")
            val store = InMemorySessionStore()
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
                    sessionStore = store,
                    credentialSigner = signer,
                )

            val failure =
                runCatching {
                    client.startEmailAuth("user@example.com")
                }.exceptionOrNull()

            val request = requireNotNull(server.takeRequest())
            assertNotNull(failure)
            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.walletAddress)
            assertNull(store.snapshot)
            assertEquals(0, store.saveCalls)
            assertFalse(signer.hasCredential())
        }

    @Test
    fun completeEmailAuthUsesStoredSessionAndParsesWallets() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-abc", "0xabc", "demo")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-abc", address = "0xabc", reference = "demo"))
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000101"),
                )
            client.startEmailAuth("user@example.com")
            assertEquals("/v1/Waas/CommitVerifier", requireNotNull(server.takeRequest()).target)

            val response = client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected
            val request = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            val expectedPayload =
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.Email,
                        authMode = AuthMode.OTP,
                        verifier = "verifier-123",
                        answer =
                            WalletAuthChallenge.hashAnswer(
                                challenge = "challenge",
                                code = "123456",
                            ),
                        lifetime = 604_800u,
                    ),
                )
            val expectedWalletSignatureHeader = expectedWalletSignatureHeader(nonce = "1710000101")

            assertEquals("/v1/Waas/CompleteAuth", request.target)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals(
                expectedWalletSignatureHeader.removePrefix(OMSWalletEnvironment.walletSignatureHeaderPrefix),
                request.headers[OMSWalletEnvironment.walletSignatureHeaderName],
            )
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEmailSessionAuth(client.snapshotSession()?.auth)
            assertEquals(1, response.wallets.size)
            assertEquals(technology.polygon.omswallet.models.WalletType.Ethereum, response.wallets.single().type)
            assertEquals("0xabc", response.wallets.single().address)
        }

    @Test
    fun completeEmailAuthBindsSessionToLocalSignerAcrossProtectedCallsAndRestore() =
        runBlocking {
            val wallet = walletFixture("wallet-local-signer", "0xabc", "demo")
            val store = InMemorySessionStore()
            val signer = TrackingCredentialSigner(nonceValue = "1710000199")
            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            assertTrue(credentialFixture().credentialId != TEST_CREDENTIAL_ID)

            enqueueEmailAuthStart()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(completeAuthResponseBody(wallets = listOf(wallet)))
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = wallet.id, address = wallet.address, reference = wallet.reference))
                    .build(),
            )
            repeat(2) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(listWalletsResponseBody(wallets = listOf(wallet)))
                        .build(),
                )
            }

            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = store,
                    credentialSigner = signer,
                )
            client.startEmailAuth("user@example.com")
            assertEquals("/v1/Waas/CommitVerifier", requireNotNull(server.takeRequest()).target)

            client.completeEmailAuth("123456")
            val activeWallets = client.listWallets()

            val restoredClient =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = store,
                    credentialSigner = signer,
                )
            assertTrue(restoredClient.restorePersistedSession())
            val restoredWallets = restoredClient.listWallets()

            assertEquals(listOf(wallet.id), activeWallets.map { it.id })
            assertEquals(wallet.address, restoredClient.walletAddress)
            assertEquals(listOf(wallet.id), restoredWallets.map { it.id })
            assertEquals("/v1/Waas/CompleteAuth", requireNotNull(server.takeRequest()).target)
            assertEquals("/v1/Waas/UseWallet", requireNotNull(server.takeRequest()).target)
            assertEquals("/v1/Waas/ListWallets", requireNotNull(server.takeRequest()).target)
            assertEquals("/v1/Waas/ListWallets", requireNotNull(server.takeRequest()).target)
        }

    @Test
    fun startEmailAuthPersistsRequestedSessionLifetimeForCompletion() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            wallets = listOf(walletFixture("wallet-abc", "0xabc", "demo")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-abc", address = "0xabc", reference = "demo"))
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(),
                )

            client.startEmailAuth(
                email = "user@example.com",
                sessionLifetimeSeconds = 120L,
            )
            assertEquals("/v1/Waas/CommitVerifier", requireNotNull(server.takeRequest()).target)
            client.completeEmailAuth(code = "123456")
            val request = requireNotNull(server.takeRequest())

            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.Email,
                        authMode = AuthMode.OTP,
                        verifier = "verifier-123",
                        answer =
                            WalletAuthChallenge.hashAnswer(
                                challenge = "challenge",
                                code = "123456",
                            ),
                        lifetime = 120u,
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )
        }

    @Test
    fun startEmailAuthRejectsInvalidSessionLifetimeBeforeSendingCode() =
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(),
                )
            val error =
                runCatching {
                    client.startEmailAuth(
                        email = "user@example.com",
                        sessionLifetimeSeconds = 0L,
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            error as OMSWalletException
            assertEquals(OMSWalletErrorCode.ValidationError, error.code)
            assertEquals("wallet.startEmailAuth", error.operation?.id)
            assertEquals("sessionLifetimeSeconds must be an integer between 1 and 2592000", error.message)
            assertEquals(0, server.requestCount)
            assertNull(client.snapshotSession())
        }

    @Test
    fun completeEmailAuthUsesReturnedWalletIndexWhenSelectingExistingWallet() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                Identity(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets =
                                listOf(
                                    Wallet(
                                        id = "wallet-def",
                                        type = WalletType.Ethereum,
                                        address = "0xdef",
                                        reference = "picked",
                                    ),
                                ),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000102"),
                )
            client.startEmailAuth("user@example.com")
            assertEquals("/v1/Waas/CommitVerifier", requireNotNull(server.takeRequest()).target)

            val resolved = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet
            server.takeRequest()
            val request = requireNotNull(server.takeRequest())

            val expectedPayload =
                WaasApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-def",
                    ),
                )

            assertEquals("/v1/Waas/UseWallet", request.target)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals("wallet-def", resolved.id)
            assertEquals("0xdef", resolved.address)
        }

    @Test
    fun completeEmailAuthConfirmsAndResolvesWallet() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val resolved = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-def",
                    ),
                ),
                requireNotNull(useWalletRequest.body).utf8(),
            )
            assertEquals("0xdef", resolved.address)
            assertEquals("wallet-def", resolved.id)
            assertEquals("0xdef", client.walletAddress)
            assertFalse(client.hasPendingSignIn)
        }

    @Test
    fun completeEmailAuthLoadsRemainingWalletPagesBeforeCreatingWallet() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            wallets =
                                listOf(
                                    walletFixture(
                                        walletId = "wallet-other",
                                        address = "0xother",
                                        type = WalletType.UNKNOWN_DEFAULT,
                                    ),
                                ),
                            page = Page(cursor = "cursor-2"),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        listWalletsResponseBody(
                            wallets = listOf(walletFixture("wallet-later", "0xlater", "later")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-later", address = "0xlater", reference = "later"))
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val resolved = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val listWalletsRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals("/v1/Waas/ListWallets", listWalletsRequest.target)
            assertEquals(
                WaasApi.ListWallets.encodeRequest(
                    ListWalletsRequest(
                        page = Page(cursor = "cursor-2"),
                    ),
                ),
                requireNotNull(listWalletsRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-later",
                    ),
                ),
                requireNotNull(useWalletRequest.body).utf8(),
            )
            assertEquals("wallet-later", resolved.id)
            assertEquals("0xlater", resolved.address)
            assertEquals(4, server.requestCount)
        }

    @Test
    fun manualCompleteEmailAuthReceivesMatchingWalletsFromAllPages() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            wallets = listOf(walletFixture("wallet-aaa", "0xaaa", "first")),
                            page = Page(cursor = "cursor-2"),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        listWalletsResponseBody(
                            wallets = listOf(walletFixture("wallet-bbb", "0xbbb", "second")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            assertTrue(result is CompleteAuthResult.WalletSelection)
            val selection = result as CompleteAuthResult.WalletSelection
            assertEquals(listOf("wallet-aaa", "wallet-bbb"), selection.pendingSelection.wallets.map { it.id })
            val resolved = selection.pendingSelection.selectWallet("wallet-bbb")

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val listWalletsRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/ListWallets", listWalletsRequest.target)
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals("wallet-bbb", resolved.wallet.id)
            assertEquals("0xbbb", client.walletAddress)
        }

    @Test
    fun completeEmailAuthCanReturnWalletSelectionWithoutSelectingWallet() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            wallets = listOf(walletFixture("wallet-aaa", "0xaaa", "first")),
                            page = Page(cursor = "cursor-2"),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        listWalletsResponseBody(
                            wallets = listOf(walletFixture("wallet-bbb", "0xbbb", "second")),
                        ),
                    ).build(),
            )

            val store = InMemorySessionStore()
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                )

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val listWalletsRequest = requireNotNull(server.takeRequest())
            assertTrue(result is CompleteAuthResult.WalletSelection)
            val selection = result as CompleteAuthResult.WalletSelection
            assertEquals(technology.polygon.omswallet.models.WalletType.Ethereum, selection.pendingSelection.walletType)
            assertEquals(listOf("wallet-aaa", "wallet-bbb"), selection.pendingSelection.wallets.map { it.id })
            assertEquals("credential-123", selection.pendingSelection.credential.credentialId)
            assertEquals("/v1/Waas/ListWallets", listWalletsRequest.target)
            assertNull(client.walletAddress)
            assertTrue(client.hasPendingSignIn)
            assertEquals(3, server.requestCount)
            assertNull(store.snapshot)

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
                    .build(),
            )

            val selected = selection.pendingSelection.selectWallet("wallet-bbb")
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals("wallet-bbb", selected.wallet.id)
            assertEquals("0xbbb", selected.walletAddress)
            assertEquals("wallet-bbb", store.snapshot?.walletId)
            assertEquals("0xbbb", store.snapshot?.walletAddress)
            assertEquals("2099-01-01T00:00:00Z", store.snapshot?.expiresAt)
            assertEmailSessionAuth(store.snapshot?.auth)
        }

    @Test
    fun pendingWalletSelectionCreatesRequestedWalletTypeWhenNoMatchingWalletExists() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            wallets =
                                listOf(
                                    walletFixture(
                                        walletId = "wallet-other",
                                        address = "0xother",
                                        reference = "other",
                                        type = WalletType.UNKNOWN_DEFAULT,
                                    ),
                                ),
                        ),
                    ).build(),
            )

            val store = InMemorySessionStore()
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            assertTrue(result is CompleteAuthResult.WalletSelection)
            val selection = result as CompleteAuthResult.WalletSelection
            assertTrue(selection.pendingSelection.wallets.isEmpty())

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-new", address = "0xnew", reference = "fresh"))
                    .build(),
            )

            val selected = selection.pendingSelection.createAndSelectWallet(reference = "fresh")
            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val createWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CreateWallet", createWalletRequest.target)
            assertEquals(
                WaasApi.CreateWallet.encodeRequest(
                    CreateWalletRequest(
                        type = WalletType.Ethereum,
                        reference = "fresh",
                    ),
                ),
                requireNotNull(createWalletRequest.body).utf8(),
            )
            assertEquals("wallet-new", selected.wallet.id)
            assertEquals("0xnew", selected.walletAddress)
            assertEquals("wallet-new", store.snapshot?.walletId)
            assertEquals("0xnew", store.snapshot?.walletAddress)
        }

    @Test
    fun stalePendingWalletSelectionFromEarlierManualAuthCannotSelectOrCreateForNewManualAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"old-verifier","loginHint":"old@example.com","challenge":"old-challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            email = "old@example.com",
                            wallets = listOf(walletFixture("wallet-old", "0xold", "old")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"new-verifier","loginHint":"new@example.com","challenge":"new-challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            email = "new@example.com",
                            wallets = listOf(walletFixture("wallet-new", "0xnew", "new")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-old", address = "0xold", reference = "old"))
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-stale", address = "0xstale", reference = "stale"))
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("old@example.com")
            val oldResult =
                client.completeEmailAuth(
                    code = "111111",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            val oldPendingSelection = (oldResult as CompleteAuthResult.WalletSelection).pendingSelection
            client.startEmailAuth("new@example.com")
            val newResult =
                client.completeEmailAuth(
                    code = "222222",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            assertTrue(newResult is CompleteAuthResult.WalletSelection)
            val requestCountBeforeStaleSelection = server.requestCount

            val selectFailure =
                runCatching {
                    oldPendingSelection.selectWallet("wallet-old")
                }.exceptionOrNull()
            val createFailure =
                runCatching {
                    oldPendingSelection.createAndSelectWallet(reference = "stale")
                }.exceptionOrNull()

            assertEquals("Pending wallet selection is no longer active", selectFailure?.message)
            assertEquals("Pending wallet selection is no longer active", createFailure?.message)
            assertEquals(requestCountBeforeStaleSelection, server.requestCount)
            assertNull(client.walletAddress)
            assertTrue(client.hasPendingSignIn)
        }

    @Test
    fun pendingWalletSelectionCannotBeReusedAfterSuccessfulSelection() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            wallets = listOf(walletFixture("wallet-bbb", "0xbbb", "second")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")
            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            val pendingSelection = (result as CompleteAuthResult.WalletSelection).pendingSelection

            val selected = pendingSelection.selectWallet("wallet-bbb")
            val requestCountAfterSelection = server.requestCount
            val reuseFailure =
                runCatching {
                    pendingSelection.selectWallet("wallet-bbb")
                }.exceptionOrNull()

            assertEquals("wallet-bbb", selected.wallet.id)
            assertEquals("0xbbb", client.walletAddress)
            assertEquals("Pending wallet selection is no longer active", reuseFailure?.message)
            assertEquals(requestCountAfterSelection, server.requestCount)
        }

    @Test
    fun stalePendingWalletSelectionCannotCreateAfterNewAutomaticAuthSelectsWallet() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"old-verifier","loginHint":"old@example.com","challenge":"old-challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            email = "old@example.com",
                            wallets = listOf(walletFixture("wallet-old", "0xold", "old")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"new-verifier","loginHint":"new@example.com","challenge":"new-challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            email = "new@example.com",
                            wallets = listOf(walletFixture("wallet-new", "0xnew", "new")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-new", address = "0xnew", reference = "new"))
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-stale", address = "0xstale", reference = "stale"))
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
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("old@example.com")
            val oldResult =
                client.completeEmailAuth(
                    code = "111111",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            val oldPendingSelection = (oldResult as CompleteAuthResult.WalletSelection).pendingSelection
            client.startEmailAuth("new@example.com")
            client.completeEmailAuth(code = "222222")
            val requestCountBeforeStaleSelection = server.requestCount

            val createFailure =
                runCatching {
                    oldPendingSelection.createAndSelectWallet(reference = "stale")
                }.exceptionOrNull()

            assertEquals("0xnew", client.walletAddress)
            assertEquals("Pending wallet selection is no longer active", createFailure?.message)
            assertEquals(requestCountBeforeStaleSelection, server.requestCount)
        }

    @Test
    fun completeEmailAuthKeepsPendingStateWhenCompleteAuthFailsAndAllowsRetry() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(401)
                    .body("""{"error":"Unauthorized","code":4001,"msg":"invalid code","status":401}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                    .build(),
            )

            val store = InMemorySessionStore()
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val firstFailure =
                runCatching {
                    client.completeEmailAuth("000000")
                }.exceptionOrNull()
            val afterFailure = client.snapshotSession()

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertNotNull(firstFailure)
            assertTrue(firstFailure is OMSWalletException)
            firstFailure as OMSWalletException
            assertEquals(OMSWalletErrorCode.RequestFailed, firstFailure.code)
            assertEquals(OMSWalletOperation.WalletCompleteEmailAuth, firstFailure.operation)
            assertEquals(401, firstFailure.status)
            assertEquals(false, firstFailure.retryable)
            assertEquals(OMSWalletUpstreamService.Waas, firstFailure.upstreamError?.service)
            assertEquals("Unauthorized", firstFailure.upstreamError?.name)
            assertEquals("4001", firstFailure.upstreamError?.code)
            assertEquals("invalid code", firstFailure.upstreamError?.message)
            assertEquals(401, firstFailure.upstreamError?.status)
            assertTrue(client.hasPendingSignIn)
            assertEquals("challenge", afterFailure?.challenge)
            assertEquals("verifier-123", afterFailure?.verifier)
            assertEquals(
                TEST_CREDENTIAL_ID,
                client.signerAddress,
            )
            assertNull(client.walletAddress)
            assertNull(store.snapshot)
            assertEquals(0, store.saveCalls)

            val wallet = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertEquals("0xdef", wallet.address)
            assertEquals("0xdef", client.walletAddress)
            assertFalse(client.hasPendingSignIn)
            assertEquals("wallet-def", store.snapshot?.walletId)
            assertEquals("0xdef", store.snapshot?.walletAddress)
            assertEquals("2099-01-01T00:00:00Z", store.snapshot?.expiresAt)
            assertEmailSessionAuth(store.snapshot?.auth)
            assertEquals(1, store.saveCalls)
        }

    @Test
    fun completeEmailAuthSelectsFirstMatchingWalletWhenMultipleWalletsExist() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets =
                                listOf(
                                    walletFixture("wallet-aaa", "0xaaa", "first"),
                                    walletFixture("wallet-bbb", "0xbbb", "second"),
                                ),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-aaa", address = "0xaaa", reference = "first"))
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000111"),
                )
            client.startEmailAuth("user@example.com")
            assertEquals("/v1/Waas/CommitVerifier", requireNotNull(server.takeRequest()).target)

            val result = client.completeEmailAuth("123456")
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-aaa",
                    ),
                ),
                requireNotNull(useWalletRequest.body).utf8(),
            )
            assertTrue(result is CompleteAuthResult.WalletSelected)
            val selected = result as CompleteAuthResult.WalletSelected
            assertEquals("wallet-aaa", selected.wallet.id)
            assertEquals("0xaaa", selected.walletAddress)
            assertFalse(client.hasPendingSignIn)
            assertEquals(
                TEST_CREDENTIAL_ID,
                client.signerAddress,
            )
            assertEquals("0xaaa", client.walletAddress)
        }

    @Test
    fun pendingWalletSelectionUsesSelectedWallet() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets =
                                listOf(
                                    walletFixture("wallet-aaa", "0xaaa", "first"),
                                    walletFixture("wallet-bbb", "0xbbb", "second"),
                                ),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )
            client.startEmailAuth("user@example.com")
            assertEquals("/v1/Waas/CommitVerifier", requireNotNull(server.takeRequest()).target)

            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            assertTrue(result is CompleteAuthResult.WalletSelection)
            val selectedWallet = (result as CompleteAuthResult.WalletSelection).pendingSelection.selectWallet("wallet-bbb")
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-bbb",
                    ),
                ),
                requireNotNull(useWalletRequest.body).utf8(),
            )
            assertEquals("0xbbb", selectedWallet.wallet.address)
            assertEquals("0xbbb", client.walletAddress)
        }

    @Test
    fun completeEmailAuthClearsSessionWhenUseWalletFails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":"InternalError","code":5000,"msg":"use wallet failed","status":500}""")
                    .build(),
            )

            val store = InMemorySessionStore()
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000114"),
                )
            client.startEmailAuth("user@example.com")

            val failure =
                runCatching {
                    client.completeEmailAuth("123456")
                }.exceptionOrNull()

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertNotNull(failure)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.walletAddress)
            assertNull(store.snapshot)
        }

    @Test
    fun completeEmailAuthClearsSessionWhenCreateWalletFails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets = emptyList(),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":"InternalError","code":5001,"msg":"create wallet failed","status":500}""")
                    .build(),
            )

            val store = InMemorySessionStore()
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000115"),
                )
            client.startEmailAuth("user@example.com")

            val failure =
                runCatching {
                    client.completeEmailAuth("123456")
                }.exceptionOrNull()

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertNotNull(failure)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.walletAddress)
            assertNull(store.snapshot)
        }

    @Test
    fun completeEmailAuthClearsSessionWhenPersistFails() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        completeAuthResponseBody(
                            identity =
                                identityFixture(
                                    type = IdentityType.Email,
                                    iss = "issuer-123",
                                    sub = "sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                    .build(),
            )

            val store = FailingSaveSessionStore()
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
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000116"),
                )
            client.startEmailAuth("user@example.com")

            val failure =
                runCatching {
                    client.completeEmailAuth("123456")
                }.exceptionOrNull()

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val storageFailure = failure as OMSWalletException
            assertEquals(OMSWalletErrorCode.StorageError, storageFailure.code)
            assertEquals("Failed to persist wallet session", storageFailure.message)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.walletAddress)
            assertTrue(store.clearCalls > 0)
        }

    private fun enqueueEmailAuthStart() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
    }
}
