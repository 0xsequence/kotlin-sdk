package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.KeyType
import com.omsclient.kotlin_sdk.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class WalletOidcRedirectAuthTest {
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
    fun startOidcRedirectAuthCommitsPkceVerifierBuildsAuthorizationUrlAndStoresPendingAuth() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                .build(),
        )

        val environment = OMSClientEnvironment(
            walletApiUrl = server.url("/rpc/Wallet/").toString(),
        )
        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = redirectStore,
            nonceGenerator = { 1710000115L },
            oidcNonceGenerator = { "nonce-123" },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        val provider = OidcProviderConfig(
            issuer = "https://issuer.example",
            clientId = "client-123",
            authorizationUrl = "https://issuer.example/oauth/authorize",
            relayRedirectUri = "https://relay.example/callback",
            authorizeParams = mapOf("prompt" to "consent"),
        )

        val result = client.startOidcRedirectAuth(
            provider = provider,
            redirectUri = "omsclientkotlindemo://auth/callback",
            authorizeParams = mapOf("prompt" to "select_account", "audience" to "wallet"),
        )
        val request = requireNotNull(server.takeRequest())

        assertEquals("/rpc/Wallet/CommitVerifier", request.target)
        assertEquals(
            WaasWalletApi.CommitVerifier.encodeRequest(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.AuthCodePKCE,
                    metadata = mapOf(
                        "iss" to "https://issuer.example",
                        "aud" to "client-123",
                        "redirect_uri" to "https://relay.example/callback",
                    ),
                ),
            ),
            requireNotNull(request.body).utf8(),
        )

        val query = queryParams(result.authorizationUrl)
        assertEquals("https://issuer.example/oauth/authorize", uriOriginAndPath(result.authorizationUrl))
        assertEquals("client-123", query["client_id"])
        assertEquals("https://relay.example/callback", query["redirect_uri"])
        assertEquals("code", query["response_type"])
        assertEquals("openid email profile", query["scope"])
        assertEquals(result.state, query["state"])
        assertEquals("pkce-challenge", query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("user@example.com", query["login_hint"])
        assertEquals("select_account", query["prompt"])
        assertEquals("wallet", query["audience"])
        assertEquals("pkce-challenge", result.challenge)

        val decodedState = String(Base64.getUrlDecoder().decode(result.state), Charsets.UTF_8)
        assertTrue(decodedState.contains(""""nonce":"nonce-123""""))
        assertTrue(decodedState.contains(""""scope":"${environment.authorizationScope}""""))
        assertTrue(decodedState.contains(""""redirect_uri":"omsclientkotlindemo://auth/callback""""))
        assertEquals("oidc-verifier-123", redirectStore.pending?.verifier)
        assertEquals("pkce-challenge", redirectStore.pending?.challenge)
        assertEquals("nonce-123", redirectStore.pending?.nonce)
        assertEquals("omsclientkotlindemo://auth/callback", redirectStore.pending?.redirectUri)
        assertEquals(WalletType.Ethereum, redirectStore.pending?.walletType)
        assertEquals(KeyType.Ethereum_Secp256k1, redirectStore.pending?.signerKeyType)
        assertTrue(client.hasPendingOidcRedirectAuth)
        assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
    }

    @Test
    fun handleOidcRedirectCallbackValidatesCallbackCompletesAuthResolvesWalletAndClearsPendingAuth() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    completeAuthResponseBody(
                        identity = identityFixture(
                            type = IdentityType.OIDC,
                            iss = "https://issuer.example",
                            sub = "oidc-sub-123",
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
        val sessionStore = InMemorySessionStore()
        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = environment,
            transport = OMSClientHttpClient(),
            sessionStore = sessionStore,
            oidcRedirectAuthStore = redirectStore,
            nonceGenerator = { 1710000116L },
            oidcNonceGenerator = { "nonce-123" },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        val provider = OidcProviderConfig(
            issuer = "https://issuer.example",
            clientId = "client-123",
            authorizationUrl = "https://issuer.example/oauth/authorize",
        )

        val started = client.startOidcRedirectAuth(
            provider = provider,
            redirectUri = "omsclientkotlindemo://auth/callback",
        )
        val result = client.handleOidcRedirectCallback(
            callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}&scope=openid",
            selectWallet = { wallets -> wallets.first() },
        )
        val wallet = (result as OidcRedirectAuthResult.Completed).wallet
        val commitRequest = requireNotNull(server.takeRequest())
        val completeAuthRequest = requireNotNull(server.takeRequest())
        val useWalletRequest = requireNotNull(server.takeRequest())

        assertEquals("/rpc/Wallet/CommitVerifier", commitRequest.target)
        assertEquals("/rpc/Wallet/CompleteAuth", completeAuthRequest.target)
        assertEquals(
            WaasWalletApi.CompleteAuth.encodeRequest(
                CompleteAuthRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.AuthCodePKCE,
                    verifier = "oidc-verifier-123",
                    answer = "auth-code",
                ),
            ),
            requireNotNull(completeAuthRequest.body).utf8(),
        )
        assertEquals("/rpc/Wallet/UseWallet", useWalletRequest.target)
        assertEquals(
            WaasWalletApi.UseWallet.encodeRequest(UseWalletRequest(walletId = "wallet-def")),
            requireNotNull(useWalletRequest.body).utf8(),
        )
        assertEquals("0xdef", wallet.address)
        assertEquals("0xdef", client.address)
        assertFalse(client.hasPendingOidcRedirectAuth)
        assertNull(redirectStore.pending)
        assertEquals(1, redirectStore.clearCalls)
        assertEquals("wallet-def", sessionStore.snapshot?.walletId)
        assertEquals("0xdef", sessionStore.snapshot?.walletAddress)
    }

    @Test
    fun handleOidcRedirectCallbackReturnsNoPendingAuthWithoutClearingActiveSession() = runBlocking {
        val activeSession = activeSessionSnapshot()
        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(activeSession),
            oidcRedirectAuthStore = redirectStore,
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.restoreSession(activeSession)

        val result = client.handleOidcRedirectCallback(
            callbackUrl = "omsclientkotlindemo://auth/callback?code=old-code&state=old-state",
            selectWallet = { wallets -> wallets.first() },
        )

        assertEquals(OidcRedirectAuthResult.NoPendingAuth, result)
        assertEquals(activeSession, client.snapshotSession())
        assertEquals("0xactive", client.address)
        assertEquals(0, redirectStore.clearCalls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun handleOidcRedirectCallbackReturnsNotOidcRedirectCallbackForNonCallbackUrl() = runBlocking {
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )

        val result = client.handleOidcRedirectCallback(
            callbackUrl = "omsclientkotlindemo://auth/callback",
            selectWallet = { wallets -> wallets.first() },
        )

        assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun matchesRedirectUriSupportsCustomSchemesWithoutAuthority() {
        assertTrue(
            OidcRedirectAuth.matchesRedirectUri(
                callbackUrl = "omsclientkotlindemo:/callback?code=auth-code&state=state-123",
                redirectUri = "omsclientkotlindemo:/callback",
            ),
        )
    }

    @Test
    fun handleOidcRedirectCallbackIgnoresUnrelatedCallbackWithoutClearingPendingAuth() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                .build(),
        )

        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = redirectStore,
            oidcNonceGenerator = { "nonce-123" },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        val started = client.startOidcRedirectAuth(
            provider = OidcProviderConfig(
                issuer = "https://issuer.example",
                clientId = "client-123",
                authorizationUrl = "https://issuer.example/oauth/authorize",
            ),
            redirectUri = "omsclientkotlindemo://auth/callback",
        )

        val result = client.handleOidcRedirectCallback(
            callbackUrl = "otherapp://auth/callback?code=auth-code&state=${started.state}",
            selectWallet = { wallets -> wallets.first() },
        )

        assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
        assertTrue(client.hasPendingOidcRedirectAuth)
        assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
        assertEquals(0, redirectStore.clearCalls)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun handleOidcRedirectCallbackIgnoresErrorCallbackWithoutStateAndKeepsPendingAuth() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                .build(),
        )

        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = redirectStore,
            oidcNonceGenerator = { "nonce-123" },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startOidcRedirectAuth(
            provider = OidcProviderConfig(
                issuer = "https://issuer.example",
                clientId = "client-123",
                authorizationUrl = "https://issuer.example/oauth/authorize",
            ),
            redirectUri = "omsclientkotlindemo://auth/callback",
        )

        val result = client.handleOidcRedirectCallback(
            callbackUrl = "omsclientkotlindemo://auth/callback?error=access_denied&error_description=User%20cancelled",
            selectWallet = { wallets -> wallets.first() },
        )

        assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
        assertTrue(client.hasPendingOidcRedirectAuth)
        assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
        assertEquals(0, redirectStore.clearCalls)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun handleOidcRedirectCallbackIgnoresInvalidStateAndKeepsPendingAuth() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                .build(),
        )

        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = redirectStore,
            oidcNonceGenerator = { "nonce-123" },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        client.startOidcRedirectAuth(
            provider = OidcProviderConfig(
                issuer = "https://issuer.example",
                clientId = "client-123",
                authorizationUrl = "https://issuer.example/oauth/authorize",
            ),
            redirectUri = "omsclientkotlindemo://auth/callback",
        )

        val result = client.handleOidcRedirectCallback(
            callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=invalid-state",
            selectWallet = { wallets -> wallets.first() },
        )

        assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
        assertTrue(client.hasPendingOidcRedirectAuth)
        assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
        assertEquals(0, redirectStore.clearCalls)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun handleOidcRedirectCallbackReturnsFailedAndClearsPendingAuthForProviderError() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                .build(),
        )

        val redirectStore = InMemoryOidcRedirectAuthStore()
        val client = WalletClient(
            projectAccessKey = "test-access-key",
            environment = OMSClientEnvironment(
                walletApiUrl = server.url("/rpc/Wallet/").toString(),
            ),
            transport = OMSClientHttpClient(),
            sessionStore = InMemorySessionStore(),
            oidcRedirectAuthStore = redirectStore,
            oidcNonceGenerator = { "nonce-123" },
            privateKeyFactory = ::fixedPrivateKeyBytes,
        )
        val started = client.startOidcRedirectAuth(
            provider = OidcProviderConfig(
                issuer = "https://issuer.example",
                clientId = "client-123",
                authorizationUrl = "https://issuer.example/oauth/authorize",
            ),
            redirectUri = "omsclientkotlindemo://auth/callback",
        )

        val result = client.handleOidcRedirectCallback(
            callbackUrl = "omsclientkotlindemo://auth/callback?error=access_denied&error_description=User%20cancelled&state=${started.state}",
            selectWallet = { wallets -> wallets.first() },
        )

        val failure = (result as OidcRedirectAuthResult.Failed).error
        assertEquals("User cancelled", failure.message)
        assertNull(client.snapshotSession())
        assertFalse(client.hasPendingOidcRedirectAuth)
        assertNull(redirectStore.pending)
        assertEquals(1, redirectStore.clearCalls)
        assertEquals(1, server.requestCount)
    }

}
