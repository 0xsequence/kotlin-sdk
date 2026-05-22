package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkException
import com.omsclient.kotlin_sdk.internal.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.internal.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.CreateWalletRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.Identity
import com.omsclient.kotlin_sdk.internal.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.internal.generated.waas.ListWalletsRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.Page
import com.omsclient.kotlin_sdk.internal.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.internal.generated.waas.Wallet
import com.omsclient.kotlin_sdk.internal.generated.waas.WalletType
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.SendTransactionRequest
import com.omsclient.kotlin_sdk.models.TransactionMode
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import java.math.BigInteger
import java.util.concurrent.TimeUnit

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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val store = InMemorySessionStore()
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000100"),
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            val expectedPayload =
                WaasWalletApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.Email,
                        authMode = AuthMode.OTP,
                        metadata = emptyMap(),
                        handle = "user@example.com",
                    ),
                )
            val expectedWalletSignatureHeader = expectedWalletSignatureHeader(nonce = "1710000100")

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals("POST", request.method)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals("test-access-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals("http://localhost:3000", request.headers["Origin"])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(
                expectedWalletSignatureHeader.removePrefix(OMSClientEnvironment.walletSignatureHeaderPrefix),
                request.headers[OMSClientEnvironment.walletSignatureHeaderName],
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000100"),
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())
            val session = client.snapshotSession()

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals("verifier-123", session?.verifier)
            assertNull(redirectStore.pending)
            assertEquals(1, redirectStore.clearCalls)
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.restoreSession(activeSession)

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            val session = client.snapshotSession()
            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val signer = MockWebCryptoCredentialSigner()
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = signer,
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals(
                "alg=\"ecdsa-p256-sha256\",scope=\"test-project-id\"," +
                    "cred=\"${signer.credentialIdValue}\",nonce=42,sig=\"${signer.signatureValue}\"",
                request.headers[OMSClientEnvironment.walletSignatureHeaderName],
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/custom/wallet/").toString(),
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000105"),
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())
            val expectedPayload =
                WaasWalletApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.Email,
                        authMode = AuthMode.OTP,
                        metadata = emptyMap(),
                        handle = "user@example.com",
                    ),
                )
            val expectedWalletSignatureHeader = expectedWalletSignatureHeader(nonce = "1710000105")

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals(
                expectedWalletSignatureHeader.removePrefix(OMSClientEnvironment.walletSignatureHeaderPrefix),
                request.headers[OMSClientEnvironment.walletSignatureHeaderName],
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    credentialSigner = signer,
                )

            val failure =
                runCatching {
                    client.startEmailAuth("user@example.com")
                }.exceptionOrNull()

            val request = requireNotNull(server.takeRequest())
            assertNotNull(failure)
            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000101"),
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = TEST_CREDENTIAL_ID,
                ),
            )

            val response = client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected
            val request = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            val expectedPayload =
                WaasWalletApi.CompleteAuth.encodeRequest(
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

            assertEquals("/rpc/Wallet/CompleteAuth", request.target)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals(
                expectedWalletSignatureHeader.removePrefix(OMSClientEnvironment.walletSignatureHeaderPrefix),
                request.headers[OMSClientEnvironment.walletSignatureHeaderName],
            )
            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
            assertEquals("user@example.com", client.snapshotSession()?.sessionEmail)
            assertEquals(1, response.wallets.size)
            assertEquals(com.omsclient.kotlin_sdk.models.WalletType.Ethereum, response.wallets.single().type)
            assertEquals("0xabc", response.wallets.single().address)
        }

    @Test
    fun completeEmailAuthUsesReturnedWalletIndexWhenSelectingExistingWallet() =
        runBlocking {
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000102"),
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = TEST_CREDENTIAL_ID,
                ),
            )

            val resolved = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet
            server.takeRequest()
            val request = requireNotNull(server.takeRequest())

            val expectedPayload =
                WaasWalletApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-def",
                    ),
                )

            assertEquals("/rpc/Wallet/UseWallet", request.target)
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val resolved = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CommitVerifier", commitRequest.target)
            assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasWalletApi.UseWallet.encodeRequest(
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000110"),
                )
            client.startEmailAuth("user@example.com")

            val resolved = (client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val listWalletsRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CommitVerifier", commitRequest.target)
            assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
            assertEquals("/rpc/Wallet/ListWallets", listWalletsRequest.target)
            assertEquals(
                WaasWalletApi.ListWallets.encodeRequest(
                    ListWalletsRequest(
                        page = Page(cursor = "cursor-2"),
                    ),
                ),
                requireNotNull(listWalletsRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasWalletApi.UseWallet.encodeRequest(
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
            assertEquals("/rpc/Wallet/ListWallets", listWalletsRequest.target)
            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
            assertEquals(com.omsclient.kotlin_sdk.models.WalletType.Ethereum, selection.pendingSelection.walletType)
            assertEquals(listOf("wallet-aaa", "wallet-bbb"), selection.pendingSelection.wallets.map { it.id })
            assertEquals("credential-123", selection.pendingSelection.credential.credentialId)
            assertEquals("/rpc/Wallet/ListWallets", listWalletsRequest.target)
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

            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
            assertEquals("wallet-bbb", selected.wallet.id)
            assertEquals("0xbbb", selected.walletAddress)
            assertEquals("wallet-bbb", store.snapshot?.walletId)
            assertEquals("0xbbb", store.snapshot?.walletAddress)
            assertEquals("2026-01-01T00:00:00Z", store.snapshot?.expiresAt)
            assertEquals(OMSClientSessionLoginType.Email, store.snapshot?.loginType)
            assertEquals("user@example.com", store.snapshot?.sessionEmail)
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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

            assertEquals("/rpc/Wallet/CreateWallet", createWalletRequest.target)
            assertEquals(
                WaasWalletApi.CreateWallet.encodeRequest(
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
    fun concurrentPendingWalletSelectionCreateCallsSendOnlyOneWalletRequest() =
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
                            wallets = emptyList(),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-new", address = "0xnew", reference = "fresh"))
                    .bodyDelay(500, TimeUnit.MILLISECONDS)
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-duplicate", address = "0xduplicate", reference = "fresh"))
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
            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())

            val firstCreate =
                async {
                    pendingSelection.createAndSelectWallet(reference = "fresh")
                }
            yield()
            val createWalletRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val secondCreate =
                async {
                    runCatching {
                        pendingSelection.createAndSelectWallet(reference = "fresh")
                    }
                }
            yield()
            val duplicateCreateWalletRequest = server.takeRequest(100, TimeUnit.MILLISECONDS)
            val selected = firstCreate.await()
            val secondFailure = secondCreate.await().exceptionOrNull()

            assertEquals("/rpc/Wallet/CreateWallet", createWalletRequest.target)
            assertNull(duplicateCreateWalletRequest)
            assertEquals("wallet-new", selected.wallet.id)
            assertTrue(secondFailure is OmsSdkException)
            assertEquals(
                OmsSdkErrorCode.WalletSelectionInFlight,
                (secondFailure as OmsSdkException).code,
            )
            assertEquals(3, server.requestCount)
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
            assertEquals("2026-01-01T00:00:00Z", store.snapshot?.expiresAt)
            assertEquals(OMSClientSessionLoginType.Email, store.snapshot?.loginType)
            assertEquals("user@example.com", store.snapshot?.sessionEmail)
            assertEquals(1, store.saveCalls)
        }

    @Test
    fun completeEmailAuthSelectsFirstMatchingWalletWhenMultipleWalletsExist() =
        runBlocking {
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000111"),
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = TEST_CREDENTIAL_ID,
                ),
            )

            val result = client.completeEmailAuth("123456")
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasWalletApi.UseWallet.encodeRequest(
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = TEST_CREDENTIAL_ID,
                ),
            )

            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                )
            assertTrue(result is CompleteAuthResult.WalletSelection)
            val selectedWallet = (result as CompleteAuthResult.WalletSelection).pendingSelection.selectWallet("wallet-bbb")
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
            assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasWalletApi.UseWallet.encodeRequest(
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
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
            assertEquals("save failed", failure?.message)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.walletAddress)
            assertTrue(store.clearCalls > 0)
        }
}
