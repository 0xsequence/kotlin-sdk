package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.OMSClientNetworks
import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthResponse
import com.omsclient.kotlin_sdk.generated.waas.Identity
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.WebRpcJson
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.models.SendTransactionRequest
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.TransactionMode
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.storage.OMSClientSecureSessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.web3j.utils.Numeric
import java.util.Base64

class WalletClientTest {
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
    fun startEmailAuthSendsCanonicalSignedRequestAndKeepsPendingSessionInMemory() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000100L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        val response = client.startEmailAuth("user@example.com")
        val request = requireNotNull(server.takeRequest())

        val expectedPayload = WaasWalletApi.CommitVerifier.encodeRequest(
            CommitVerifierRequest(
                identityType = IdentityType.Email,
                authMode = AuthMode.OTP,
                metadata = emptyMap(),
                handle = "user@example.com",
            ),
        )
        val expectedSignedRequest = WalletRequestSigner.signWalletRequest(
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
        assertNull(store.snapshot)
        assertNull(store.privateKeyHex)
        assertEquals(0, store.saveCalls)
        assertNull(store.savedPrivateKeyHex)
    }

    @Test
    fun startEmailAuthUsesGeneratedWalletRouteEvenWhenEnvironmentPathDiffers() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/custom/wallet/").toString(),
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            nonceGenerator = { 1710000105L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        client.startEmailAuth("user@example.com")
        val request = requireNotNull(server.takeRequest())
        val expectedPayload = WaasWalletApi.CommitVerifier.encodeRequest(
            CommitVerifierRequest(
                identityType = IdentityType.Email,
                authMode = AuthMode.OTP,
                metadata = emptyMap(),
                handle = "user@example.com",
            ),
        )
        val expectedSignedRequest = WalletRequestSigner.signWalletRequest(
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
    fun startEmailAuthClearsStateWhenCommitVerifierFails() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("""{"error":"InternalError","code":5000,"msg":"commit verifier failed","status":500}""")
                .build(),
        )

        val generatedKey = fixedPrivateKeyBytes()
        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000106L },
            privateKeyFactory = { generatedKey },
        )

        val failure = runCatching {
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
    fun signInWithOidcIdTokenCommitsCompletesAndResolvesWallet() = runBlocking {
        val idToken = fakeJwt(exp = 1910000100L)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.OIDC,
                            iss = "https://accounts.google.com",
                            sub = "google-sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000112L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        val wallet = client.signInWithOidcIdToken(
            idToken = idToken,
            issuer = "https://accounts.google.com",
            audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
        )
        val commitRequest = requireNotNull(server.takeRequest())
        val completeAuthRequest = requireNotNull(server.takeRequest())
        val useWalletRequest = requireNotNull(server.takeRequest())

        assertEquals("/rpc/Wallet/CommitVerifier", commitRequest.target)
        assertEquals(
            WaasWalletApi.CommitVerifier.encodeRequest(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    metadata = mapOf(
                        "iss" to "https://accounts.google.com",
                        "aud" to "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                        "exp" to "1910000100",
                    ),
                    handle = OidcIdToken.handleHash(idToken),
                ),
            ),
            requireNotNull(commitRequest.body).utf8(),
        )
        assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
        assertEquals(
            WaasWalletApi.CompleteAuth.encodeRequest(
                CompleteAuthRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    verifier = "oidc-verifier-123",
                    answer = idToken,
                ),
            ),
            requireNotNull(completeAuthRequest.body).utf8(),
        )
        assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
        assertEquals(
            WaasWalletApi.UseWallet.encodeRequest(
                UseWalletRequest(
                    walletId = "wallet-def",
                ),
            ),
            requireNotNull(useWalletRequest.body).utf8(),
        )
        assertEquals("0xdef", wallet.address)
        assertEquals("0xdef", client.address)
        assertFalse(client.hasPendingSignIn)
        assertEquals("wallet-def", store.snapshot?.walletId)
        assertEquals("0xdef", store.snapshot?.walletAddress)
        assertNull(store.snapshot?.verifier)
        assertNull(store.snapshot?.challenge)
        assertEquals(FIXED_PRIVATE_KEY_HEX, store.privateKeyHex)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun signInWithOidcIdTokenClearsPersistedSessionWhenCompleteAuthFails() = runBlocking {
        val idToken = fakeJwt(exp = 1910000100L)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("""{"error":"IdentityProviderError","code":7104,"msg":"Identity provider error","status":500}""")
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000112L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        val failure = runCatching {
            client.signInWithOidcIdToken(
                idToken = idToken,
                issuer = "https://accounts.google.com",
                audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
            )
        }.exceptionOrNull()

        val commitRequest = requireNotNull(server.takeRequest())
        val completeAuthRequest = requireNotNull(server.takeRequest())

        assertNotNull(failure)
        assertEquals("/rpc/Wallet/CommitVerifier", commitRequest.target)
        assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
        assertNull(client.snapshotSession())
        assertFalse(client.hasPendingSignIn)
        assertNull(client.address)
        assertNull(client.signerAddress)
        assertNull(store.snapshot)
        assertNull(store.privateKeyHex)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun signInWithOidcIdTokenClearsStateWhenCommitVerifierFails() = runBlocking {
        val idToken = fakeJwt(exp = 1910000100L)
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("""{"error":"InternalError","code":5000,"msg":"commit verifier failed","status":500}""")
                .build(),
        )

        val generatedKey = fixedPrivateKeyBytes()
        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000111L },
            privateKeyFactory = { generatedKey },
        )

        val failure = runCatching {
            client.signInWithOidcIdToken(
                idToken = idToken,
                issuer = "https://accounts.google.com",
                audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
            )
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
    fun confirmEmailSignInUsesStoredSessionAndParsesWallets() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(walletFixture("wallet-abc", "0xabc", "demo")),
                    ),
                )
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000101L },
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

        val expectedPayload = WaasWalletApi.CompleteAuth.encodeRequest(
            CompleteAuthRequest(
                identityType = IdentityType.Email,
                authMode = AuthMode.OTP,
                verifier = "verifier-123",
                answer = WalletAuthChallenge.hashAnswer(
                    challenge = "challenge",
                    code = "123456",
                ),
            ),
        )
        val expectedSignedRequest = WalletRequestSigner.signWalletRequest(
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
    fun restorePersistedSessionLoadsFromStore() {
        val snapshot = OMSClientSessionSnapshot(
            walletId = "wallet-abc",
            walletAddress = "0xabc",
            signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
        )
        val store = InMemorySessionStore(snapshot, FIXED_PRIVATE_KEY_HEX)
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(),
            sessionStore = store,
        )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals("0xabc", client.address)
        assertEquals(
            WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            client.signerAddress,
        )
    }

    @Test
    fun resolveWalletUsesReturnedWalletIndexWhenSelectingExistingWallet() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000102L },
        )
        client.restoreSession(
            OMSClientSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val resolved = client.resolveWallet(
            CompleteAuthResponse(
                identity = Identity(
                    type = IdentityType.Email,
                    iss = "issuer-123",
                    sub = "sub-123",
                ),
                email = "user@example.com",
                wallets = listOf(
                    Wallet(
                        id = "wallet-def",
                        type = environment.defaultWalletType,
                        address = "0xdef",
                        reference = "picked",
                    ),
                ),
            ),
        )
        val request = requireNotNull(server.takeRequest())

        val expectedPayload = WaasWalletApi.UseWallet.encodeRequest(
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
    fun completeEmailAuthConfirmsAndResolvesWallet() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = WalletClient(
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
    fun completeEmailAuthKeepsPendingStateWhenCompleteAuthFailsAndAllowsRetry() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":"Unauthorized","code":4001,"msg":"invalid code","status":401}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                .build(),
        )

        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000110L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startEmailAuth("user@example.com")

        val firstFailure = runCatching {
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
        assertEquals(FIXED_PRIVATE_KEY_HEX, store.privateKeyHex)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun completeEmailAuthReturnsWalletSelectionRequiredForMultipleWallets() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(
                            walletFixture("wallet-aaa", "0xaaa", "first"),
                            walletFixture("wallet-bbb", "0xbbb", "second"),
                        ),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000111L },
        )
        client.restoreSession(
            OMSClientSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val failure = runCatching {
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
    fun completeEmailAuthUsesSelectorWhenMultipleWalletsAreAvailable() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(
                            walletFixture("wallet-aaa", "0xaaa", "first"),
                            walletFixture("wallet-bbb", "0xbbb", "second"),
                        ),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-bbb", address = "0xbbb", reference = "second"))
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000112L },
        )
        client.restoreSession(
            OMSClientSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val selectedWallet = client.completeEmailAuth("123456") { wallets ->
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
    fun completeEmailAuthClearsSessionWhenSelectorThrows() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(
                            walletFixture("wallet-aaa", "0xaaa", "first"),
                            walletFixture("wallet-bbb", "0xbbb", "second"),
                        ),
                    ),
                )
                .build(),
        )

        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000113L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startEmailAuth("user@example.com")

        val failure = runCatching {
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
    fun completeEmailAuthClearsSessionWhenUseWalletFails() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("""{"error":"InternalError","code":5000,"msg":"use wallet failed","status":500}""")
                .build(),
        )

        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000114L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startEmailAuth("user@example.com")

        val failure = runCatching {
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
    fun completeEmailAuthClearsSessionWhenCreateWalletFails() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = emptyList(),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("""{"error":"InternalError","code":5001,"msg":"create wallet failed","status":500}""")
                .build(),
        )

        val store = InMemorySessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000115L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startEmailAuth("user@example.com")

        val failure = runCatching {
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
    fun completeEmailAuthClearsSessionWhenPersistFails() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.Email,
                            iss = "issuer-123",
                            sub = "sub-123",
                        ),
                        email = "user@example.com",
                        wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                    ),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(walletResponseBody(walletId = "wallet-def", address = "0xdef", reference = "picked"))
                .build(),
        )

        val store = FailingSaveSessionStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000116L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startEmailAuth("user@example.com")

        val failure = runCatching {
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

    @Test
    fun signOutClearsPersistedStore() {
        val snapshot = OMSClientSessionSnapshot(
            walletId = "wallet-abc",
            walletAddress = "0xabc",
            signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
        )
        val store = InMemorySessionStore(snapshot, FIXED_PRIVATE_KEY_HEX)
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(),
            sessionStore = store,
        )
        client.restorePersistedSession()

        client.signOut()

        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
        assertNull(client.address)
        assertNull(client.signerAddress)
        assertNull(store.privateKeyHex)
    }

    @Test
    fun addressReturnsSelectedWallet() {
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(),
            sessionStore = InMemorySessionStore(
                snapshot = OMSClientSessionSnapshot(
                    walletId = "wallet-main",
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
        )
        assertTrue(client.restorePersistedSession())

        assertEquals("0xwallet", client.address)
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun restorePersistedSessionIgnoresPendingSnapshots() {
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(),
            sessionStore = InMemorySessionStore(
                snapshot = OMSClientSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
        )
        assertFalse(client.restorePersistedSession())

        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.address)
    }

    @Test
    fun hasPendingSignInIsTrueForInMemoryPendingAuth() {
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
        )
        client.restoreSession(
            OMSClientSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        assertTrue(client.hasPendingSignIn)
        assertEquals(
            WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            client.signerAddress,
        )
        assertNull(client.address)
    }

    @Test
    fun signMessageLoadsPrivateKeyOnDemandAndWipesTransientBuffer() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"signature":"0xsigned"}""")
                .build(),
        )

        val store = TrackingPrivateKeyStore(
            snapshot = OMSClientSessionSnapshot(
                walletId = "wallet-main",
                walletAddress = "0xwallet",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000107L },
        )

        assertTrue(client.restorePersistedSession())
        assertEquals(0, store.withPrivateKeyCalls)

        val result = client.signMessage(
            network = OMSClientNetworks.requireSupported("80002"),
            message = "hello",
        )

        assertEquals("0xsigned", result.signature)
        assertEquals(1, store.withPrivateKeyCalls)
        assertEquals(0, store.saveCalls)
        assertTrue(requireNotNull(store.lastProvidedPrivateKey).all { it == 0.toByte() })
    }

    @Test
    fun sendTransactionMatchesWaasRequestShape() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
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
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
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
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
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
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"pending"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"pending"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"executed","txnHash":"0xdeadbeef"}""")
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
            indexerUrlTemplate = server.url("/indexer/").toString() + "{value}/rpc/Indexer/",
        )
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(
                snapshot = OMSClientSessionSnapshot(
                    walletId = "wallet-main",
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
            nonceGenerator = { 1710000107L },
            fastTransactionStatusPollIntervalMillis = 1L,
            transactionStatusPollIntervalMillis = 1L,
            transactionStatusPollTimeoutMillis = 1_000L,
        )
        assertTrue(client.restorePersistedSession())

        val result = client.sendTransaction(
            network = OMSClientNetworks.requireSupported("80002"),
            request = SendTransactionRequest(
                to = "0xabc",
                value = "0",
                data = "0x1234",
                mode = TransactionMode.Native,
            ),
        ) { feeOptions ->
            assertEquals(2, feeOptions.size)
            assertEquals("POL", feeOptions[0].feeOption.token.symbol)
            assertEquals("100", feeOptions[0].balance?.balance)
            assertEquals("0.0000000000000001", feeOptions[0].available)
            assertEquals("100", feeOptions[0].availableRaw)
            assertEquals(18u, feeOptions[0].decimals)
            assertEquals("USDC", feeOptions[1].feeOption.token.symbol)
            assertEquals("2000", feeOptions[1].balance?.balance)
            assertEquals("0.002", feeOptions[1].available)
            assertEquals("2000", feeOptions[1].availableRaw)
            assertEquals(6u, feeOptions[1].decimals)
            FeeOptionSelection(token = feeOptions[1].feeOption.token.symbol)
        }
        val prepareRequest = requireNotNull(server.takeRequest())
        val nativeBalanceRequest = requireNotNull(server.takeRequest())
        val balanceRequest = requireNotNull(server.takeRequest())
        val executeRequest = requireNotNull(server.takeRequest())
        val pendingStatusRequest = requireNotNull(server.takeRequest())
        val executedStatusRequest = requireNotNull(server.takeRequest())

        assertEquals("txn-1", result.txnId)
        assertEquals("0xdeadbeef", result.txHash)
        assertEquals(com.omsclient.kotlin_sdk.generated.waas.TransactionStatus.Executed, result.status)
        assertEquals("/rpc/Wallet/PrepareEthereumTransaction", prepareRequest.target)
        assertEquals(
            WaasWalletApi.PrepareEthereumTransaction.encodeRequest(
                com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumTransactionRequest(
                    walletId = "wallet-main",
                    network = "80002",
                    to = "0xabc",
                    value = "0",
                    data = "0x1234",
                    mode = TransactionMode.Native,
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
                    feeOption = FeeOptionSelection(token = "USDC"),
                ),
            ),
            requireNotNull(executeRequest.body).utf8(),
        )
        assertEquals("/rpc/Wallet/GetTransactionStatus", pendingStatusRequest.target)
        assertEquals(
            WaasWalletApi.GetTransactionStatus.encodeRequest(
                com.omsclient.kotlin_sdk.generated.waas.GetTransactionStatusRequest(txnId = "txn-1"),
            ),
            requireNotNull(pendingStatusRequest.body).utf8(),
        )
        assertEquals("/rpc/Wallet/GetTransactionStatus", executedStatusRequest.target)
        assertEquals(
            WaasWalletApi.GetTransactionStatus.encodeRequest(
                com.omsclient.kotlin_sdk.generated.waas.GetTransactionStatusRequest(txnId = "txn-1"),
            ),
            requireNotNull(executedStatusRequest.body).utf8(),
        )
    }

    @Test
    fun sendTransactionUsesFastStatusPollsBeforeDefaultInterval() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
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
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"pending"}""")
                .build(),
        )
        repeat(6) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"status":"pending"}""")
                    .build(),
            )
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"executed","txnHash":"0xdeadbeef"}""")
                .build(),
        )

        val delays = mutableListOf<Long>()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(
                snapshot = OMSClientSessionSnapshot(
                    walletId = "wallet-main",
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
            nonceGenerator = { 1710000108L },
            transactionStatusDelay = { delayMillis -> delays += delayMillis },
        )
        assertTrue(client.restorePersistedSession())

        val result = client.sendTransaction(
            network = OMSClientNetworks.requireSupported("80002"),
            request = SendTransactionRequest(
                to = "0xabc",
                value = "0",
            ),
        )

        assertEquals("0xdeadbeef", result.txHash)
        assertEquals(6, delays.size)
        assertEquals(400L, delays[0])
        assertEquals(400L, delays[3])
        assertEquals(2_000L, delays[4])
        assertEquals(2_000L, delays[5])
    }

    companion object {
        private const val FIXED_PRIVATE_KEY_HEX =
            "0x1111111111111111111111111111111111111111111111111111111111111111"

        private fun walletFixture(
            walletId: String,
            address: String,
            reference: String? = null,
            type: WalletType = WalletType.Ethereum,
        ): Wallet = Wallet(
            id = walletId,
            type = type,
            address = address,
            reference = reference,
        )

        private fun identityFixture(
            type: IdentityType,
            iss: String? = null,
            sub: String = "sub-123",
        ): Identity = Identity(
            type = type,
            iss = iss,
            sub = sub,
        )

        private fun completeAuthResponseBody(
            wallets: List<Wallet>,
            identity: Identity = identityFixture(IdentityType.Email),
            email: String? = "user@example.com",
        ): String = WebRpcJson.encodeToString(
            CompleteAuthResponse(
                identity = identity,
                wallets = wallets,
                email = email,
            ),
        )

        private fun walletResponseBody(
            walletId: String,
            address: String,
            reference: String? = null,
            type: WalletType = WalletType.Ethereum,
        ): String = """{"wallet":${WebRpcJson.encodeToString(walletFixture(walletId, address, reference, type))}}"""

        private fun fakeJwt(exp: Long): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
            val payload = encoder.encodeToString(
                """
                {"iss":"https://accounts.google.com","aud":"demo-web-client-id","sub":"google-sub-123","email":"user@example.com","exp":$exp}
                """.trimIndent().toByteArray()
            )
            return "$header.$payload.signature"
        }

        private fun fixedPrivateKeyBytes(): ByteArray =
            Numeric.hexStringToByteArray(FIXED_PRIVATE_KEY_HEX)
    }

    private class InMemorySessionStore(
        var snapshot: OMSClientSessionSnapshot? = null,
        var privateKeyHex: String? = null,
    ) : OMSClientSecureSessionStore {
        var saveCalls: Int = 0
            private set
        var savedPrivateKeyHex: String? = null
            private set

        override fun load(): OMSClientSessionSnapshot? = snapshot

        override fun save(snapshot: OMSClientSessionSnapshot, privateKey: ByteArray?) {
            saveCalls += 1
            this.snapshot = snapshot
            if (privateKey != null) {
                privateKeyHex = Numeric.toHexString(privateKey)
                savedPrivateKeyHex = privateKeyHex
            }
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
            block(Numeric.hexStringToByteArray(requireNotNull(privateKeyHex)))

        override fun clear() {
            snapshot = null
            privateKeyHex = null
        }
    }

    private class TrackingPrivateKeyStore(
        private val snapshot: OMSClientSessionSnapshot,
        private var privateKeyHex: String,
    ) : OMSClientSecureSessionStore {
        var withPrivateKeyCalls: Int = 0
            private set
        var saveCalls: Int = 0
            private set
        var lastProvidedPrivateKey: ByteArray? = null
            private set

        override fun load(): OMSClientSessionSnapshot = snapshot

        override fun save(snapshot: OMSClientSessionSnapshot, privateKey: ByteArray?) {
            saveCalls += 1
            if (privateKey != null) {
                privateKeyHex = Numeric.toHexString(privateKey)
            }
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T {
            withPrivateKeyCalls += 1
            val provided = Numeric.hexStringToByteArray(privateKeyHex)
            lastProvidedPrivateKey = provided
            return try {
                block(provided)
            } finally {
                provided.fill(0)
            }
        }

        override fun clear() {
            privateKeyHex = ""
        }
    }

    private class FailingSaveSessionStore : OMSClientSecureSessionStore {
        var clearCalls: Int = 0
            private set

        override fun load(): OMSClientSessionSnapshot? = null

        override fun save(snapshot: OMSClientSessionSnapshot, privateKey: ByteArray?) {
            throw IllegalStateException("save failed")
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
            error("Persisted private key should not be needed before save succeeds")

        override fun clear() {
            clearCalls += 1
        }
    }
}
