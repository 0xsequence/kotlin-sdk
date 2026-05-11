package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.KeyType
import com.omsclient.kotlin_sdk.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
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

class WalletOidcIdTokenAuthTest {
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
        assertEquals(KeyType.Ethereum_Secp256k1, store.snapshot?.signerKeyType)
        assertNull(store.snapshot?.verifier)
        assertNull(store.snapshot?.challenge)
        assertNull(store.privateKeyHex)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun signInWithOidcIdTokenRejectsWhenWalletSessionIsActive() = runBlocking {
        val activeSession = activeSessionSnapshot()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(),
            privateKeyFactory = { error("Credential should not be created") },
        )
        client.restoreSession(activeSession)

        val failure = runCatching {
            client.signInWithOidcIdToken(
                idToken = fakeJwt(exp = 1910000100L),
                issuer = "https://accounts.google.com",
                audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
            )
        }.exceptionOrNull()

        assertEquals("Cannot start a new login while a wallet session is active", failure?.message)
        assertEquals(activeSession, client.snapshotSession())
        assertEquals(0, server.requestCount)
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

}
