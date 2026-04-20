package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.AuthMode
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CommitVerifierRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CompleteAuthRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CompleteAuthResponse
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.Identity
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.IdentityType
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.UseWalletRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WaasWalletApi
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.Wallet
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WalletType
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.models.SendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.models.TransactionMode
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceSessionSnapshot
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequenceSecureSessionStore
import kotlinx.coroutines.runBlocking
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

class SequenceWalletClientTest {
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
    fun signInWithEmailSendsCanonicalSignedRequestAndKeepsPendingSessionInMemory() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val store = InMemorySessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000100L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        val response = client.signInWithEmail("user@example.com")
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
        assertEquals("test-access-key", request.headers[SequenceEnvironment.accessKeyHeaderName])
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
    fun signInWithEmailUsesGeneratedWalletRouteEvenWhenEnvironmentPathDiffers() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/custom/wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            nonceGenerator = { 1710000105L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        client.signInWithEmail("user@example.com")
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
    fun signInWithEmailClearsStateWhenCommitVerifierFails() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("""{"error":"InternalError","code":5000,"msg":"commit verifier failed","status":500}""")
                .build(),
        )

        val generatedKey = fixedPrivateKeyBytes()
        val store = InMemorySessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000106L },
            privateKeyFactory = { generatedKey },
        )

        val failure = runCatching {
            client.signInWithEmail("user@example.com")
        }.exceptionOrNull()

        val request = requireNotNull(server.takeRequest())
        assertNotNull(failure)
        assertEquals("/rpc/Wallet/CommitVerifier", request.target)
        assertNull(client.snapshotSession())
        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.walletAddress)
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
                    """
                    {
                      "identity": {"type":"OIDC","issuer":"https://accounts.google.com","subject":"google-sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val store = InMemorySessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
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
                    walletType = WalletType.Ethereum_EOA,
                    walletIndex = 3.toUByte(),
                ),
            ),
            requireNotNull(useWalletRequest.body).utf8(),
        )
        assertEquals("0xdef", wallet.address)
        assertEquals("0xdef", client.walletAddress)
        assertFalse(client.hasPendingSignIn)
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

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val store = InMemorySessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
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
        assertNull(client.walletAddress)
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
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
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
        assertNull(client.walletAddress)
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xabc","index":0,"comment":"demo"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000101L },
        )
        client.restoreSession(
            SequenceSessionSnapshot(
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
        assertEquals("user@example.com", response.identity?.email)
        assertEquals(1, response.wallets.size)
        assertEquals(WalletType.Ethereum_EOA, response.wallets.single().type)
        assertEquals("0xabc", response.wallets.single().address)
    }

    @Test
    fun confirmEmailSignInAllowsIdentityWithoutIssuerAndSubject() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "identity": {"type":"Email","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xabc","index":0,"comment":"demo"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000101L },
        )
        client.restoreSession(
            SequenceSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val response = client.confirmEmailSignIn("123456")

        assertEquals("user@example.com", response.identity?.email)
        assertEquals(null, response.identity?.issuer)
        assertEquals(null, response.identity?.subject)
        assertEquals(1, response.wallets.size)
    }

    @Test
    fun confirmEmailSignInAllowsMissingIdentity() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xabc","index":0,"comment":"demo"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000101L },
        )
        client.restoreSession(
            SequenceSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val response = client.confirmEmailSignIn("123456")

        assertEquals(null, response.identity)
        assertEquals(1, response.wallets.size)
        assertEquals("0xabc", response.wallets.single().address)
    }

    @Test
    fun restorePersistedSessionLoadsFromStore() {
        val snapshot = SequenceSessionSnapshot(
            walletAddress = "0xabc",
            signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
        )
        val store = InMemorySessionStore(snapshot, FIXED_PRIVATE_KEY_HEX)
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(),
            sessionStore = store,
        )

        val restored = client.restorePersistedSession()

        assertTrue(restored)
        assertEquals(snapshot, client.snapshotSession())
        assertEquals("0xabc", client.walletAddress)
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
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000102L },
        )
        client.restoreSession(
            SequenceSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val resolved = client.resolveWallet(
            CompleteAuthResponse(
                identity = Identity(
                    type = IdentityType.Email,
                    issuer = "issuer-123",
                    subject = "sub-123",
                    email = "user@example.com",
                ),
                wallets = listOf(
                    Wallet(
                        type = environment.defaultWalletType,
                        address = "0xdef",
                        index = 3.toUByte(),
                        comment = "picked",
                    ),
                ),
            ),
        )
        val request = requireNotNull(server.takeRequest())

        val expectedPayload = WaasWalletApi.UseWallet.encodeRequest(
            UseWalletRequest(
                walletType = environment.defaultWalletType,
                walletIndex = 3.toUByte(),
            ),
        )

        assertEquals("/rpc/Wallet/UseWallet", request.target)
        assertEquals(expectedPayload, requireNotNull(request.body).utf8())
        assertEquals(3.toUByte(), resolved.index)
        assertEquals("0xdef", resolved.address)
    }

    @Test
    fun completeEmailSignInConfirmsAndResolvesWallet() = runBlocking {
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(),
            nonceGenerator = { 1710000110L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.signInWithEmail("user@example.com")

        val resolved = client.completeEmailSignIn("123456")
        val commitRequest = requireNotNull(server.takeRequest())
        val completeAuthRequest = requireNotNull(server.takeRequest())
        val useWalletRequest = requireNotNull(server.takeRequest())

        assertEquals("/rpc/Wallet/CommitVerifier", commitRequest.target)
        assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
        assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
        assertEquals(
            WaasWalletApi.UseWallet.encodeRequest(
                UseWalletRequest(
                    walletType = environment.defaultWalletType,
                    walletIndex = 3.toUByte(),
                ),
            ),
            requireNotNull(useWalletRequest.body).utf8(),
        )
        assertEquals("0xdef", resolved.address)
        assertEquals(3.toUByte(), resolved.index)
        assertEquals("0xdef", client.walletAddress)
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun completeEmailSignInKeepsPendingStateWhenCompleteAuthFailsAndAllowsRetry() = runBlocking {
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}}""")
                .build(),
        )

        val store = InMemorySessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000110L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.signInWithEmail("user@example.com")

        val firstFailure = runCatching {
            client.completeEmailSignIn("000000")
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
        assertNull(client.walletAddress)
        assertNull(store.snapshot)
        assertNull(store.privateKeyHex)
        assertEquals(0, store.saveCalls)

        val wallet = client.completeEmailSignIn("123456")

        requireNotNull(server.takeRequest())
        requireNotNull(server.takeRequest())
        assertEquals("0xdef", wallet.address)
        assertEquals("0xdef", client.walletAddress)
        assertFalse(client.hasPendingSignIn)
        assertEquals("0xdef", store.snapshot?.walletAddress)
        assertEquals(FIXED_PRIVATE_KEY_HEX, store.privateKeyHex)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun completeEmailSignInReturnsWalletSelectionRequiredForMultipleWallets() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xaaa","index":1,"comment":"first"},
                        {"type":"Ethereum_EOA","address":"0xbbb","index":4,"comment":"second"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xbbb","index":4,"comment":"second"}}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000111L },
        )
        client.restoreSession(
            SequenceSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val failure = runCatching {
            client.completeEmailSignIn("123456")
        }.exceptionOrNull()
        val completeAuthRequest = requireNotNull(server.takeRequest())

        assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
        assertEquals(
            "Multiple wallets are available. Call completeEmailSignIn(code, selectWallet) to choose one.",
            failure?.message,
        )
        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.walletAddress)
    }

    @Test
    fun completeEmailSignInUsesSelectorWhenMultipleWalletsAreAvailable() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xaaa","index":1,"comment":"first"},
                        {"type":"Ethereum_EOA","address":"0xbbb","index":4,"comment":"second"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xbbb","index":4,"comment":"second"}}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
            nonceGenerator = { 1710000112L },
        )
        client.restoreSession(
            SequenceSessionSnapshot(
                challenge = "challenge",
                verifier = "verifier-123",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
        )

        val selectedWallet = client.completeEmailSignIn("123456") { wallets ->
            wallets[1]
        }
        val completeAuthRequest = requireNotNull(server.takeRequest())
        val useWalletRequest = requireNotNull(server.takeRequest())

        assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
        assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
        assertEquals(
            WaasWalletApi.UseWallet.encodeRequest(
                UseWalletRequest(
                    walletType = environment.defaultWalletType,
                    walletIndex = 4.toUByte(),
                ),
            ),
            requireNotNull(useWalletRequest.body).utf8(),
        )
        assertEquals("0xbbb", selectedWallet.address)
        assertEquals("0xbbb", client.walletAddress)
    }

    @Test
    fun completeEmailSignInClearsSessionWhenSelectorThrows() = runBlocking {
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xaaa","index":1,"comment":"first"},
                        {"type":"Ethereum_EOA","address":"0xbbb","index":4,"comment":"second"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val store = InMemorySessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000113L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.signInWithEmail("user@example.com")

        val failure = runCatching {
            client.completeEmailSignIn("123456") { error("selector failed") }
        }.exceptionOrNull()

        requireNotNull(server.takeRequest())
        requireNotNull(server.takeRequest())
        assertEquals("selector failed", failure?.message)
        assertNull(client.snapshotSession())
        assertFalse(client.hasPendingSignIn)
        assertNull(client.signerAddress)
        assertNull(client.walletAddress)
        assertNull(store.snapshot)
        assertNull(store.privateKeyHex)
    }

    @Test
    fun completeEmailSignInClearsSessionWhenUseWalletFails() = runBlocking {
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}
                      ]
                    }
                    """.trimIndent(),
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
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000114L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.signInWithEmail("user@example.com")

        val failure = runCatching {
            client.completeEmailSignIn("123456")
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
        assertNull(store.privateKeyHex)
    }

    @Test
    fun completeEmailSignInClearsSessionWhenCreateWalletFails() = runBlocking {
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": []
                    }
                    """.trimIndent(),
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
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000115L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.signInWithEmail("user@example.com")

        val failure = runCatching {
            client.completeEmailSignIn("123456")
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
        assertNull(store.privateKeyHex)
    }

    @Test
    fun completeEmailSignInClearsSessionWhenPersistFails() = runBlocking {
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
                    """
                    {
                      "identity": {"type":"Email","issuer":"issuer-123","subject":"sub-123","email":"user@example.com"},
                      "wallets": [
                        {"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"wallet":{"type":"Ethereum_EOA","address":"0xdef","index":3,"comment":"picked"}}""")
                .build(),
        )

        val store = FailingSaveSessionStore()
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000116L },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.signInWithEmail("user@example.com")

        val failure = runCatching {
            client.completeEmailSignIn("123456")
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

    @Test
    fun clearSessionClearsPersistedStore() {
        val snapshot = SequenceSessionSnapshot(
            walletAddress = "0xabc",
            signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
        )
        val store = InMemorySessionStore(snapshot, FIXED_PRIVATE_KEY_HEX)
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(),
            sessionStore = store,
        )
        client.restorePersistedSession()

        client.clearSession()

        assertNull(client.snapshotSession())
        assertNull(store.snapshot)
        assertNull(client.walletAddress)
        assertNull(client.signerAddress)
        assertNull(store.privateKeyHex)
    }

    @Test
    fun walletAddressReturnsSelectedWallet() {
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(),
            sessionStore = InMemorySessionStore(
                snapshot = SequenceSessionSnapshot(
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
        )
        assertTrue(client.restorePersistedSession())

        assertEquals("0xwallet", client.walletAddress)
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun restorePersistedSessionIgnoresPendingSnapshots() {
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(),
            sessionStore = InMemorySessionStore(
                snapshot = SequenceSessionSnapshot(
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
        assertNull(client.walletAddress)
    }

    @Test
    fun hasPendingSignInIsTrueForInMemoryPendingAuth() {
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(),
            sessionStore = InMemorySessionStore(privateKeyHex = FIXED_PRIVATE_KEY_HEX),
        )
        client.restoreSession(
            SequenceSessionSnapshot(
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
        assertNull(client.walletAddress)
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
            snapshot = SequenceSessionSnapshot(
                walletAddress = "0xwallet",
                signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            ),
            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = SequenceHttpClient(),
            sessionStore = store,
            nonceGenerator = { 1710000107L },
        )

        assertTrue(client.restorePersistedSession())
        assertEquals(0, store.withPrivateKeyCalls)

        val result = client.signMessage(
            chainId = "80002",
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
                .body("""{"txHash":"0xdeadbeef"}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = SequenceHttpClient(),
            sessionStore = InMemorySessionStore(
                snapshot = SequenceSessionSnapshot(
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
            nonceGenerator = { 1710000107L },
        )
        assertTrue(client.restorePersistedSession())

        val result = client.sendTransaction(
            chainId = "80002",
            request = SendTransactionRequest(
                to = "0xabc",
                value = "0",
                data = "0x1234",
                mode = TransactionMode.Native,
                feeCeiling = "1000000",
                nonce = "42",
            ),
        )
        val request = requireNotNull(server.takeRequest())

        assertEquals("0xdeadbeef", result.txHash)
        assertEquals("/rpc/Wallet/SendTransaction", request.target)
        assertEquals(
            WaasWalletApi.SendTransaction.encodeRequest(
                com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SendTransactionRequest(
                    wallet = "0xwallet",
                    network = "amoy",
                    to = "0xabc",
                    value = "0",
                    data = "0x1234",
                    mode = TransactionMode.Native,
                    feeCeiling = "1000000",
                    nonce = "42",
                ),
            ),
            requireNotNull(request.body).utf8(),
        )
    }

    companion object {
        private const val FIXED_PRIVATE_KEY_HEX =
            "0x1111111111111111111111111111111111111111111111111111111111111111"

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
        var snapshot: SequenceSessionSnapshot? = null,
        var privateKeyHex: String? = null,
    ) : SequenceSecureSessionStore {
        var saveCalls: Int = 0
            private set
        var savedPrivateKeyHex: String? = null
            private set

        override fun load(): SequenceSessionSnapshot? = snapshot

        override fun save(snapshot: SequenceSessionSnapshot, privateKey: ByteArray?) {
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
        private val snapshot: SequenceSessionSnapshot,
        private var privateKeyHex: String,
    ) : SequenceSecureSessionStore {
        var withPrivateKeyCalls: Int = 0
            private set
        var saveCalls: Int = 0
            private set
        var lastProvidedPrivateKey: ByteArray? = null
            private set

        override fun load(): SequenceSessionSnapshot = snapshot

        override fun save(snapshot: SequenceSessionSnapshot, privateKey: ByteArray?) {
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

    private class FailingSaveSessionStore : SequenceSecureSessionStore {
        var clearCalls: Int = 0
            private set

        override fun load(): SequenceSessionSnapshot? = null

        override fun save(snapshot: SequenceSessionSnapshot, privateKey: ByteArray?) {
            throw IllegalStateException("save failed")
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
            error("Persisted private key should not be needed before save succeeds")

        override fun clear() {
            clearCalls += 1
        }
    }
}
