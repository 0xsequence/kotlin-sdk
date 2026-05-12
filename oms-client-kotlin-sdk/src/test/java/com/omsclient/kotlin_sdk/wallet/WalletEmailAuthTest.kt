package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.OMSClientNetworks
import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthResponse
import com.omsclient.kotlin_sdk.generated.waas.Identity
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.KeyType
import com.omsclient.kotlin_sdk.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.SendTransactionRequest
import com.omsclient.kotlin_sdk.models.TransactionMode
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import kotlinx.coroutines.runBlocking
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
import org.web3j.utils.Numeric
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
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val store = InMemorySessionStore()
            val client =
                WalletClient(
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000100L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )

            val response = client.startEmailAuth("user@example.com")
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
            val expectedSignedRequest =
                WalletRequestSigner.signWalletRequest(
                    endpoint = WaasWalletApi.CommitVerifier.path,
                    nonce = "1710000100",
                    payload = expectedPayload,
                    scope = environment.authorizationScope,
                    privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                )

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals("POST", request.method)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals("test-access-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertEquals("http://localhost:3000", request.headers["Origin"])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(
                expectedSignedRequest.authorizationHeader.removePrefix("Authorization: "),
                request.headers["Authorization"],
            )
            assertEquals("challenge", response.challenge)
            assertEquals("verifier-123", response.verifier)

            val session = client.snapshotSession()
            assertNotNull(session)
            assertEquals("challenge", session?.challenge)
            assertEquals("verifier-123", session?.verifier)
            assertEquals(
                WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                session?.signerAddress,
            )
            assertEquals(KeyType.Ethereum_Secp256k1, session?.signerKeyType)
            assertNull(store.snapshot)
            assertNull(store.privateKeyHex)
            assertEquals(0, store.saveCalls)
            assertNull(store.savedPrivateKeyHex)
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
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    nonceGenerator = { 1710000100L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )

            val response = client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals("verifier-123", response.verifier)
            assertNull(redirectStore.pending)
            assertFalse(client.canResumeOidcRedirectAuth)
            assertEquals(1, redirectStore.clearCalls)
        }

    @Test
    fun startEmailAuthRejectsWhenWalletSessionIsActive() =
        runBlocking {
            val activeSession = activeSessionSnapshot()
            val client =
                WalletClient(
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    privateKeyFactory = { error("Credential should not be created") },
                )
            client.restoreSession(activeSession)

            val failure =
                runCatching {
                    client.startEmailAuth("user@example.com")
                }.exceptionOrNull()

            assertEquals("Cannot start a new login while a wallet session is active", failure?.message)
            assertEquals(activeSession, client.snapshotSession())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun startEmailAuthUsesWebCryptoCredentialSignerAuthorizationHeader() =
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
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    credentialSigner = signer,
                )

            client.startEmailAuth("user@example.com")
            val request = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals(
                "webcrypto-secp256r1 scope=\"${environment.authorizationScope}\"," +
                    "cred=\"${signer.credentialIdValue}\",nonce=42,sig=\"${signer.signatureValue}\"",
                request.headers["Authorization"],
            )
            assertEquals(KeyType.WebCrypto_Secp256r1, client.snapshotSession()?.signerKeyType)
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
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    nonceGenerator = { 1710000105L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
            val expectedSignedRequest =
                WalletRequestSigner.signWalletRequest(
                    endpoint = WaasWalletApi.CommitVerifier.path,
                    nonce = "1710000105",
                    payload = expectedPayload,
                    scope = environment.authorizationScope,
                    privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                    requestPathPrefix = WaasWalletApi.basePath,
                )

            assertEquals("/rpc/Wallet/CommitVerifier", request.target)
            assertEquals(
                expectedSignedRequest.authorizationHeader.removePrefix("Authorization: "),
                request.headers["Authorization"],
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

            val generatedKey = fixedPrivateKeyBytes()
            val store = InMemorySessionStore()
            val client =
                WalletClient(
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000106L },
                    privateKeyFactory = { generatedKey },
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
            assertNull(client.address)
            assertNull(store.snapshot)
            assertNull(store.privateKeyHex)
            assertEquals(0, store.saveCalls)
            assertTrue(generatedKey.all { it == 0.toByte() })
        }

    @Test
    fun confirmEmailSignInUsesStoredSessionAndParsesWallets() =
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

            val environment =
                OMSClientEnvironment(
                    walletApiUrl = server.url("/rpc/Wallet/").toString(),
                )
            val client =
                WalletClient(
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
                    nonceGenerator = { 1710000101L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
            )

            val response = client.confirmEmailSignIn("123456")
            val request = requireNotNull(server.takeRequest())

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
            val expectedSignedRequest =
                WalletRequestSigner.signWalletRequest(
                    endpoint = WaasWalletApi.CompleteAuth.path,
                    nonce = "1710000101",
                    payload = expectedPayload,
                    scope = environment.authorizationScope,
                    privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                )

            assertEquals("/rpc/Wallet/CompleteAuth", request.target)
            assertEquals(expectedPayload, requireNotNull(request.body).utf8())
            assertEquals(
                expectedSignedRequest.authorizationHeader.removePrefix("Authorization: "),
                request.headers["Authorization"],
            )
            assertEquals("user@example.com", response.email)
            assertEquals(IdentityType.Email, response.identity.type)
            assertEquals("issuer-123", response.identity.iss)
            assertEquals("sub-123", response.identity.sub)
            assertEquals(1, response.wallets.size)
            assertEquals(WalletType.Ethereum, response.wallets.single().type)
            assertEquals("0xabc", response.wallets.single().address)
        }

    @Test
    fun resolveWalletUsesReturnedWalletIndexWhenSelectingExistingWallet() =
        runBlocking {
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
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
                    nonceGenerator = { 1710000102L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
            )

            val resolved =
                client.resolveWallet(
                    CompleteAuthResponse(
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
                                    type = environment.defaultWalletType,
                                    address = "0xdef",
                                    reference = "picked",
                                ),
                            ),
                        credential = credentialFixture(),
                    ),
                )
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
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    nonceGenerator = { 1710000110L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            client.startEmailAuth("user@example.com")

            val resolved = client.completeEmailAuth("123456")
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
            assertEquals("0xdef", client.address)
            assertFalse(client.hasPendingSignIn)
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
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000110L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
                WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                client.signerAddress,
            )
            assertNull(client.address)
            assertNull(store.snapshot)
            assertNull(store.privateKeyHex)
            assertEquals(0, store.saveCalls)

            val wallet = client.completeEmailAuth("123456")

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertEquals("0xdef", wallet.address)
            assertEquals("0xdef", client.address)
            assertFalse(client.hasPendingSignIn)
            assertEquals("wallet-def", store.snapshot?.walletId)
            assertEquals("0xdef", store.snapshot?.walletAddress)
            assertEquals("2026-01-01T00:00:00Z", store.snapshot?.expiresAt)
            assertEquals(OMSClientSessionLoginType.Email, store.snapshot?.loginType)
            assertEquals("user@example.com", store.snapshot?.sessionEmail)
            assertNull(store.privateKeyHex)
            assertEquals(1, store.saveCalls)
        }

    @Test
    fun completeEmailAuthReturnsWalletSelectionRequiredForMultipleWallets() =
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
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
                    nonceGenerator = { 1710000111L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
            )

            val failure =
                runCatching {
                    client.completeEmailAuth("123456")
                }.exceptionOrNull()
            val completeAuthRequest = requireNotNull(server.takeRequest())

            assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
            assertEquals(
                "Multiple wallets are available. Call completeEmailAuth(code, selectWallet) to choose one.",
                failure?.message,
            )
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.address)
        }

    @Test
    fun completeEmailAuthUsesSelectorWhenMultipleWalletsAreAvailable() =
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
                    projectAccessKey = "test-access-key",
                    environment = environment,
                    transport = OMSClientHttpClient(),
                    sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
                    nonceGenerator = { 1710000112L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            client.restoreSession(
                OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
            )

            val selectedWallet =
                client.completeEmailAuth("123456") { wallets ->
                    wallets[1]
                }
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
            assertEquals("0xbbb", selectedWallet.address)
            assertEquals("0xbbb", client.address)
        }

    @Test
    fun completeEmailAuthClearsSessionWhenSelectorThrows() =
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
                            wallets =
                                listOf(
                                    walletFixture("wallet-aaa", "0xaaa", "first"),
                                    walletFixture("wallet-bbb", "0xbbb", "second"),
                                ),
                        ),
                    ).build(),
            )

            val store = InMemorySessionStore()
            val client =
                WalletClient(
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000113L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
                )
            client.startEmailAuth("user@example.com")

            val failure =
                runCatching {
                    client.completeEmailAuth("123456") { error("selector failed") }
                }.exceptionOrNull()

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertEquals("selector failed", failure?.message)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.signerAddress)
            assertNull(client.address)
            assertNull(store.snapshot)
            assertNull(store.privateKeyHex)
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
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000114L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
            assertNull(client.address)
            assertNull(store.snapshot)
            assertNull(store.privateKeyHex)
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
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000115L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
            assertNull(client.address)
            assertNull(store.snapshot)
            assertNull(store.privateKeyHex)
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
                    projectAccessKey = "test-access-key",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore = store,
                    nonceGenerator = { 1710000116L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
            assertNull(client.address)
            assertTrue(store.clearCalls > 0)
        }
}
