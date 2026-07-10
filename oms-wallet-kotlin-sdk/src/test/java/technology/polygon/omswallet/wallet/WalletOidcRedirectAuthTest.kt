package technology.polygon.omswallet.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletStorageException
import technology.polygon.omswallet.internal.generated.waas.AuthMode
import technology.polygon.omswallet.internal.generated.waas.CommitVerifierRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthRequest
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.UseWalletRequest
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.WalletType
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.utils.OMSWalletBase64Url
import java.io.IOException

class WalletOidcRedirectAuthTest {
    private companion object {
        const val DEFAULT_GOOGLE_OIDC_CLIENT_ID =
            "913882656162-7l4ofa0ou2hqo90umlkenhdop1f5inba.apps.googleusercontent.com"
    }

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
    fun omsRelayOidcProvidersExposeFixedDistinctValues() {
        assertEquals(OmsRelayOidcProviders.google, OmsRelayOidcProviders.google)
        assertEquals(OmsRelayOidcProviders.apple, OmsRelayOidcProviders.apple)
        assertTrue(OmsRelayOidcProviders.google != OmsRelayOidcProviders.apple)
    }

    @Test
    fun customOidcProviderConfigDefaultsToNoScopes() {
        val provider =
            CustomOidcProviderConfig(
                issuer = "https://issuer.example",
                clientId = "client-123",
                authorizationUrl = "https://issuer.example/oauth/authorize",
                providerRedirectUri = "omsclientkotlindemo://auth/callback",
            )

        assertEquals(emptyList<String>(), provider.scopes)
    }

    @Test
    fun startOidcRedirectAuthCommitsPkceVerifierBuildsAuthorizationUrlAndStoresPendingAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                    authorizeParams = mapOf("prompt" to "consent"),
                )

            val result =
                client.startOidcRedirectAuth(
                    provider = provider,
                    authorizeParams = mapOf("prompt" to "select_account", "audience" to "wallet"),
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        metadata =
                            mapOf(
                                "iss" to "https://issuer.example",
                                "aud" to "client-123",
                                "redirect_uri" to "omsclientkotlindemo://auth/callback",
                            ),
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )

            val query = queryParams(result.authorizationUrl)
            assertEquals("https://issuer.example/oauth/authorize", uriOriginAndPath(result.authorizationUrl))
            assertEquals("client-123", query["client_id"])
            assertEquals("omsclientkotlindemo://auth/callback", query["redirect_uri"])
            assertEquals("code", query["response_type"])
            assertNull(query["scope"])
            assertEquals(result.state, query["state"])
            assertEquals("pkce-challenge", query["code_challenge"])
            assertEquals("S256", query["code_challenge_method"])
            assertNull(query["login_hint"])
            assertEquals("select_account", query["prompt"])
            assertEquals("wallet", query["audience"])
            assertEquals("pkce-challenge", result.challenge)

            val decodedState = String(OMSWalletBase64Url.decode(result.state), Charsets.UTF_8)
            assertTrue(decodedState.contains(""""nonce":"nonce-123""""))
            assertTrue(decodedState.contains(""""scope":"test-project-id""""))
            assertFalse(decodedState.contains(""""redirect_uri""""))
            assertEquals("oidc-verifier-123", redirectStore.pending?.verifier)
            assertEquals("pkce-challenge", redirectStore.pending?.challenge)
            assertEquals("nonce-123", redirectStore.pending?.nonce)
            assertEquals(OidcRedirectAuthMode.AuthCodePKCE, redirectStore.pending?.authMode)
            assertEquals("omsclientkotlindemo://auth/callback", redirectStore.pending?.redirectUri)
            assertEquals(WalletType.Ethereum.wireValue, redirectStore.pending?.walletType)
            assertNull(redirectStore.pending?.walletSelection)
            assertNull(redirectStore.pending?.sessionLifetimeSeconds)
            assertEquals(WalletSigningAlgorithm.ECDSA_P256_SHA256, redirectStore.pending?.signerKeyType)
            assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
        }

    @Test
    fun startOidcRedirectAuthWithHelperGoogleUsesRelayAndStoresOmsRelayReturnUriInState() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val derivedRelayUri = server.url("/v1/Waas/auth/waas/callback/google").toString()

            val result =
                client.startOidcRedirectAuth(
                    provider = OmsRelayOidcProviders.google,
                    omsRelayReturnUri = "omsclientkotlindemo://auth/callback",
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        metadata =
                            mapOf(
                                "iss" to "https://accounts.google.com",
                                "aud" to DEFAULT_GOOGLE_OIDC_CLIENT_ID,
                                "redirect_uri" to derivedRelayUri,
                            ),
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )

            val query = queryParams(result.authorizationUrl)
            assertEquals(derivedRelayUri, query["redirect_uri"])
            val decodedState = String(OMSWalletBase64Url.decode(result.state), Charsets.UTF_8)
            assertTrue(decodedState.contains(""""redirect_uri":"omsclientkotlindemo://auth/callback""""))
            assertEquals("omsclientkotlindemo://auth/callback", redirectStore.pending?.redirectUri)
        }

    @Test
    fun startOidcRedirectAuthWithHelperGoogleUsesDerivedRelayUriInState() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val derivedRelayUri = server.url("/v1/Waas/").toString().trimEnd('/') + "/auth/waas/callback/google"

            val result =
                client.startOidcRedirectAuth(
                    provider = OmsRelayOidcProviders.google,
                    omsRelayReturnUri = "omsclientkotlindemo://auth/callback",
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        metadata =
                            mapOf(
                                "iss" to "https://accounts.google.com",
                                "aud" to DEFAULT_GOOGLE_OIDC_CLIENT_ID,
                                "redirect_uri" to derivedRelayUri,
                            ),
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )

            val query = queryParams(result.authorizationUrl)
            assertEquals(derivedRelayUri, query["redirect_uri"])
            val decodedState = String(OMSWalletBase64Url.decode(result.state), Charsets.UTF_8)
            assertTrue(decodedState.contains(""""redirect_uri":"omsclientkotlindemo://auth/callback""""))
            assertEquals("omsclientkotlindemo://auth/callback", redirectStore.pending?.redirectUri)
        }

    @Test
    fun startOidcRedirectAuthWithManualGoogleLookingConfigUsesProviderRedirectUriWithoutDerivedRelay() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://accounts.google.com",
                    clientId = "client-123",
                    authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                    providerRedirectUri = "com.example.app://oidc/callback",
                    provider = "google",
                    providerLabel = "Google",
                    scopes = listOf("openid", "email"),
                )

            val result = client.startOidcRedirectAuth(provider = provider)
            val request = requireNotNull(server.takeRequest())

            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        metadata =
                            mapOf(
                                "iss" to "https://accounts.google.com",
                                "aud" to "client-123",
                                "redirect_uri" to "com.example.app://oidc/callback",
                            ),
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )

            val query = queryParams(result.authorizationUrl)
            assertEquals("com.example.app://oidc/callback", query["redirect_uri"])
            assertFalse(query["redirect_uri"].orEmpty().contains("/auth/waas/callback/google"))
            val decodedState = String(OMSWalletBase64Url.decode(result.state), Charsets.UTF_8)
            assertFalse(decodedState.contains(""""redirect_uri""""))
            assertEquals("com.example.app://oidc/callback", redirectStore.pending?.redirectUri)
        }

    @Test
    fun startOidcRedirectAuthRejectsInvalidSessionLifetimeBeforeRequest() =
        runBlocking {
            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )

            val error =
                runCatching {
                    client.startOidcRedirectAuth(
                        provider =
                            CustomOidcProviderConfig(
                                issuer = "https://issuer.example",
                                clientId = "client-123",
                                authorizationUrl = "https://issuer.example/oauth/authorize",
                                providerRedirectUri = "omsclientkotlindemo://auth/callback",
                            ),
                        sessionLifetimeSeconds = 2_592_001L,
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            error as OMSWalletException
            assertEquals(OMSWalletErrorCode.ValidationError, error.code)
            assertEquals("wallet.startOidcRedirectAuth", error.operation?.id)
            assertEquals("sessionLifetimeSeconds must be an integer between 1 and 2592000", error.message)
            assertEquals(0, server.requestCount)
            assertNull(redirectStore.pending)
        }

    @Test
    fun startOidcRedirectAuthUsesExplicitGoogleLoginHint() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"server@example.com","challenge":"pkce-challenge"}""")
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )

            val result =
                client.startOidcRedirectAuth(
                    provider = OmsRelayOidcProviders.google,
                    omsRelayReturnUri = "omsclientkotlindemo://auth/callback",
                    loginHint = "last@example.com",
                )

            assertEquals("last@example.com", queryParams(result.authorizationUrl)["login_hint"])
        }

    @Test
    fun startOidcRedirectAuthFallsBackToPreviousSessionEmailForGoogleLoginHint() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val activeSession =
                OMSWalletSessionSnapshot(
                    walletId = "wallet-main",
                    walletAddress = "0xwallet",
                    signerAddress = TEST_CREDENTIAL_ID,
                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                    expiresAt = "2099-01-01T00:00:00Z",
                    auth = googleRedirectSessionAuth(email = "previous@example.com"),
                )
            val sessionStore = InMemorySessionStore(activeSession)
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
                    sessionStore = sessionStore,
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.restoreSession(activeSession)

            val result =
                client.startOidcRedirectAuth(
                    provider = OmsRelayOidcProviders.google,
                    omsRelayReturnUri = "omsclientkotlindemo://auth/callback",
                )

            assertEquals("previous@example.com", queryParams(result.authorizationUrl)["login_hint"])
            assertNull(sessionStore.snapshot)
        }

    @Test
    fun startOidcRedirectAuthUsesAppleDefaultsWithoutLoginHint() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":"pkce-challenge"}""")
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )

            val result =
                client.startOidcRedirectAuth(
                    provider = OmsRelayOidcProviders.apple,
                    omsRelayReturnUri = "omsclientkotlindemo://auth/callback",
                    loginHint = "last@example.com",
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        metadata =
                            mapOf(
                                "iss" to "https://appleid.apple.com",
                                "aud" to "service.oms.polygon.technology",
                                "redirect_uri" to server.url("/v1/Waas/auth/waas/callback/apple").toString(),
                            ),
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )

            val query = queryParams(result.authorizationUrl)
            assertEquals("https://appleid.apple.com/auth/authorize", uriOriginAndPath(result.authorizationUrl))
            assertEquals("service.oms.polygon.technology", query["client_id"])
            assertEquals(server.url("/v1/Waas/auth/waas/callback/apple").toString(), query["redirect_uri"])
            assertEquals("form_post", query["response_mode"])
            assertEquals("openid email", query["scope"])
            assertEquals("pkce-challenge", query["code_challenge"])
            assertEquals("S256", query["code_challenge_method"])
            assertNull(query["login_hint"])
            val decodedState = String(OMSWalletBase64Url.decode(result.state), Charsets.UTF_8)
            assertTrue(decodedState.contains(""""redirect_uri":"omsclientkotlindemo://auth/callback""""))
        }

    @Test
    fun startOidcRedirectAuthReplacesActiveSessionAndPersistedPendingRedirectAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-new","loginHint":"user@example.com","challenge":"pkce-challenge-new"}""")
                    .build(),
            )

            val activeSession = activeSessionSnapshot()
            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val sessionStore = InMemorySessionStore(activeSession)
            val redirectStore = InMemoryOidcRedirectAuthStore(pendingOidcRedirectAuthFixture())
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = sessionStore,
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-new" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.restoreSession(activeSession)
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                )

            val result =
                client.startOidcRedirectAuth(
                    provider = provider,
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", request.target)
            assertEquals("oidc-verifier-new", redirectStore.pending?.verifier)
            assertEquals("pkce-challenge-new", redirectStore.pending?.challenge)
            assertEquals("nonce-new", redirectStore.pending?.nonce)
            assertEquals("omsclientkotlindemo://auth/callback", redirectStore.pending?.redirectUri)
            assertEquals("oidc-verifier-new", client.snapshotSession()?.verifier)
            assertNull(client.snapshotSession()?.walletId)
            assertNull(client.snapshotSession()?.walletAddress)
            assertNull(sessionStore.snapshot)
            assertEquals(1, redirectStore.clearCalls)
            assertEquals(result.state, queryParams(result.authorizationUrl)["state"])
        }

    @Test
    fun startAndHandleOidcRedirectAuthUseConfiguredAuthCodeModeWithoutPkceParamsOrScope() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":"pkce-challenge"}""")
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
                                    type = IdentityType.OIDC,
                                    iss = "https://issuer.example",
                                    sub = "oidc-sub-123",
                                ),
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                    scopes = emptyList(),
                    authorizeParams =
                        mapOf(
                            "scope" to "openid email",
                            "code_challenge" to "manual-challenge",
                            "code_challenge_method" to "plain",
                        ),
                    authMode = OidcRedirectAuthMode.AuthCode,
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                )
            val query = queryParams(started.authorizationUrl)
            assertNull(query["scope"])
            assertNull(query["code_challenge"])
            assertNull(query["code_challenge_method"])

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                )

            assertTrue(result is OidcRedirectAuthResult.Completed)
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())
            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCode,
                        metadata =
                            mapOf(
                                "iss" to "https://issuer.example",
                                "aud" to "client-123",
                                "redirect_uri" to "omsclientkotlindemo://auth/callback",
                            ),
                    ),
                ),
                requireNotNull(commitRequest.body).utf8(),
            )
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCode,
                        verifier = "oidc-verifier-123",
                        answer = "auth-code",
                        lifetime = 604_800u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
        }

    @Test
    fun handleOidcRedirectCallbackValidatesCallbackCompletesAuthResolvesWalletAndClearsPendingAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
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
                                    type = IdentityType.OIDC,
                                    iss = "https://issuer.example",
                                    sub = "oidc-sub-123",
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
            val sessionStore = InMemorySessionStore()
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = sessionStore,
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                )
            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}&scope=openid",
                )
            val wallet =
                ((result as OidcRedirectAuthResult.Completed).result as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        verifier = "oidc-verifier-123",
                        answer = "auth-code",
                        lifetime = 604_800u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(UseWalletRequest(walletId = "wallet-def")),
                requireNotNull(useWalletRequest.body).utf8(),
            )
            assertEquals("0xdef", wallet.address)
            assertEquals("0xdef", client.walletAddress)
            assertNull(redirectStore.pending)
            assertEquals(2, redirectStore.clearCalls)
            assertEquals("wallet-def", sessionStore.snapshot?.walletId)
            assertEquals("0xdef", sessionStore.snapshot?.walletAddress)
            assertEquals("2099-01-01T00:00:00Z", sessionStore.snapshot?.expiresAt)
            assertOidcSessionAuth(
                sessionStore.snapshot?.auth,
                flow = OMSWalletOidcSessionAuthFlow.Redirect,
                issuer = "https://issuer.example",
                provider = null,
                providerLabel = null,
            )
        }

    @Test
    fun handleOidcRedirectCallbackUsesRequestedSessionLifetime() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":"pkce-challenge"}""")
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
                                    type = IdentityType.OIDC,
                                    iss = "https://issuer.example",
                                    sub = "oidc-sub-123",
                                ),
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                )
            client.handleOidcRedirectCallback(
                callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                sessionLifetimeSeconds = 120L,
            )

            requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        verifier = "oidc-verifier-123",
                        answer = "auth-code",
                        lifetime = 120u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
        }

    @Test
    fun handleOidcRedirectCallbackUsesPendingStartPreferencesWhenCallbackDoesNotOverride() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":"pkce-challenge"}""")
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
                                    type = IdentityType.OIDC,
                                    iss = "https://issuer.example",
                                    sub = "oidc-sub-123",
                                ),
                            wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                        ),
                    ).build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )

            val started =
                client.startOidcRedirectAuth(
                    provider =
                        CustomOidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                            providerRedirectUri = "omsclientkotlindemo://auth/callback",
                        ),
                    walletSelection = WalletSelectionBehavior.Manual,
                    sessionLifetimeSeconds = 120L,
                )
            assertEquals(WalletSelectionBehavior.Manual, redirectStore.pending?.walletSelection)
            assertEquals(120L, redirectStore.pending?.sessionLifetimeSeconds)

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                )

            requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        verifier = "oidc-verifier-123",
                        answer = "auth-code",
                        lifetime = 120u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
            assertTrue(result is OidcRedirectAuthResult.Completed)
            assertTrue((result as OidcRedirectAuthResult.Completed).result is CompleteAuthResult.WalletSelection)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackUsesCallbackOverridesBeforePendingStartPreferences() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":"pkce-challenge"}""")
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
                                    type = IdentityType.OIDC,
                                    iss = "https://issuer.example",
                                    sub = "oidc-sub-123",
                                ),
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )

            val started =
                client.startOidcRedirectAuth(
                    provider =
                        CustomOidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                            providerRedirectUri = "omsclientkotlindemo://auth/callback",
                        ),
                    walletSelection = WalletSelectionBehavior.Manual,
                    sessionLifetimeSeconds = 120L,
                )
            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                    walletSelection = WalletSelectionBehavior.Automatic,
                    sessionLifetimeSeconds = 240L,
                )

            requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.AuthCodePKCE,
                        verifier = "oidc-verifier-123",
                        answer = "auth-code",
                        lifetime = 240u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertTrue(result is OidcRedirectAuthResult.Completed)
        }

    @Test
    fun handleOidcRedirectCallbackRejectsInvalidSessionLifetimeBeforeCompleteRequest() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                )
            val error =
                runCatching {
                    client.handleOidcRedirectCallback(
                        callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                        sessionLifetimeSeconds = 0L,
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            error as OMSWalletException
            assertEquals(OMSWalletErrorCode.ValidationError, error.code)
            assertEquals("wallet.handleOidcRedirectCallback", error.operation?.id)
            assertEquals("sessionLifetimeSeconds must be an integer between 1 and 2592000", error.message)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackCanReturnWalletSelectionWithoutSelectingWallet() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
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
                                    type = IdentityType.OIDC,
                                    iss = "https://issuer.example",
                                    sub = "oidc-sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-def", "0xdef", "picked")),
                        ),
                    ).build(),
            )

            val environment =
                OMSWalletEnvironment(
                    walletApiUrl = server.url("/v1/Waas/").toString(),
                    indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                )
            val sessionStore = InMemorySessionStore()
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = sessionStore,
                    oidcRedirectAuthStore = redirectStore,
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val provider =
                CustomOidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    providerRedirectUri = "omsclientkotlindemo://auth/callback",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                )
            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}&scope=openid",
                    walletSelection = WalletSelectionBehavior.Manual,
                )

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertTrue(result is OidcRedirectAuthResult.Completed)
            val selection =
                (result as OidcRedirectAuthResult.Completed).result as CompleteAuthResult.WalletSelection
            assertEquals(technology.polygon.omswallet.models.WalletType.Ethereum, selection.pendingSelection.walletType)
            assertEquals(listOf("wallet-def"), selection.pendingSelection.wallets.map { it.id })
            assertEquals("credential-123", selection.pendingSelection.credential.credentialId)
            assertNull(client.walletAddress)
            assertTrue(client.hasPendingSignIn)
            assertNull(redirectStore.pending)
            assertEquals(2, redirectStore.clearCalls)
            assertNull(sessionStore.snapshot)
        }

    @Test
    fun handleOidcRedirectCallbackReturnsNoPendingAuthWithoutClearingActiveSession() =
        runBlocking {
            val activeSession = activeSessionSnapshot()
            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    sessionStore = InMemorySessionStore(activeSession),
                    oidcRedirectAuthStore = redirectStore,
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.restoreSession(activeSession)

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=old-code&state=old-state",
                )

            assertEquals(OidcRedirectAuthResult.NoPendingAuth, result)
            assertEquals(activeSession, client.snapshotSession())
            assertEquals("0xactive", client.walletAddress)
            assertEquals(0, redirectStore.clearCalls)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackReturnsNotOidcRedirectCallbackForNonCallbackUrl() =
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
                    oidcRedirectAuthStore = InMemoryOidcRedirectAuthStore(),
                    credentialSigner = TrackingCredentialSigner(),
                )

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback",
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
    fun handleOidcRedirectCallbackIgnoresUnrelatedCallbackWithoutClearingPendingAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val started =
                client.startOidcRedirectAuth(
                    provider =
                        CustomOidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                            providerRedirectUri = "omsclientkotlindemo://auth/callback",
                        ),
                )

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "otherapp://auth/callback?code=auth-code&state=${started.state}",
                )

            assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
            assertEquals("oidc-verifier-123", redirectStore.pending?.verifier)
            assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
            assertEquals(1, redirectStore.clearCalls)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackIgnoresErrorCallbackWithoutStateAndKeepsPendingAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.startOidcRedirectAuth(
                provider =
                    CustomOidcProviderConfig(
                        issuer = "https://issuer.example",
                        clientId = "client-123",
                        authorizationUrl = "https://issuer.example/oauth/authorize",
                        providerRedirectUri = "omsclientkotlindemo://auth/callback",
                    ),
            )

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?error=access_denied&error_description=User%20cancelled",
                )

            assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
            assertEquals("oidc-verifier-123", redirectStore.pending?.verifier)
            assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
            assertEquals(1, redirectStore.clearCalls)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackIgnoresInvalidStateAndKeepsPendingAuth() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            client.startOidcRedirectAuth(
                provider =
                    CustomOidcProviderConfig(
                        issuer = "https://issuer.example",
                        clientId = "client-123",
                        authorizationUrl = "https://issuer.example/oauth/authorize",
                        providerRedirectUri = "omsclientkotlindemo://auth/callback",
                    ),
            )

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=invalid-state",
                )

            assertEquals(OidcRedirectAuthResult.NotOidcRedirectCallback, result)
            assertEquals("oidc-verifier-123", redirectStore.pending?.verifier)
            assertEquals("oidc-verifier-123", client.snapshotSession()?.verifier)
            assertEquals(1, redirectStore.clearCalls)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackReturnsFailedAndClearsPendingAuthForProviderError() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore()
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
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )
            val started =
                client.startOidcRedirectAuth(
                    provider =
                        CustomOidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                            providerRedirectUri = "omsclientkotlindemo://auth/callback",
                        ),
                )

            val failure =
                runCatching {
                    client.handleOidcRedirectCallback(
                        callbackUrl =
                            "omsclientkotlindemo://auth/callback" +
                                "?error=access_denied" +
                                "&error_description=User%20cancelled" +
                                "&state=${started.state}",
                    )
                }.exceptionOrNull() as OMSWalletException
            assertEquals(OMSWalletOperation.WalletHandleOidcRedirectCallback, failure.operation)
            assertEquals(OMSWalletErrorCode.ValidationError, failure.code)
            assertEquals("User cancelled", failure.message)
            assertNull(client.snapshotSession())
            assertNull(redirectStore.pending)
            assertEquals(2, redirectStore.clearCalls)
            assertEquals(1, server.requestCount)
        }
}

private val StartOidcRedirectAuthResult.state: String
    get() = requireNotNull(queryParams(authorizationUrl)["state"])

private val StartOidcRedirectAuthResult.challenge: String
    get() = requireNotNull(queryParams(authorizationUrl)["code_challenge"])
