package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun oidcProvidersUseGoogleDefaults() {
        val provider = OidcProviders.google()

        assertEquals(
            "913882656162-7l4ofa0ou2hqo90umlkenhdop1f5inba.apps.googleusercontent.com",
            provider.clientId,
        )
        assertNull(provider.relayRedirectUri)
        assertEquals("https://accounts.google.com", provider.issuer)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", provider.authorizationUrl)
        assertEquals("google", provider.provider)
        assertEquals("Google", provider.providerLabel)
        assertEquals(listOf("openid", "email", "profile"), provider.scopes)
        assertEquals(OidcRedirectAuthMode.AuthCodePKCE, provider.authMode)
        assertEquals("offline", provider.authorizeParams["access_type"])
        assertEquals("consent", provider.authorizeParams["prompt"])
    }

    @Test
    fun oidcProvidersUseAppleDefaults() {
        val provider = OidcProviders.apple()

        assertEquals("service.oms.polygon.technology", provider.clientId)
        assertNull(provider.relayRedirectUri)
        assertEquals("https://appleid.apple.com", provider.issuer)
        assertEquals("https://appleid.apple.com/auth/authorize", provider.authorizationUrl)
        assertEquals("apple", provider.provider)
        assertEquals("Apple", provider.providerLabel)
        assertEquals(listOf("openid", "email"), provider.scopes)
        assertEquals(OidcRedirectAuthMode.AuthCodePKCE, provider.authMode)
        assertEquals("form_post", provider.authorizeParams["response_mode"])
    }

    @Test
    fun oidcProviderConfigDefaultsToNoScopesForCustomProviders() {
        val provider =
            OidcProviderConfig(
                issuer = "https://issuer.example",
                clientId = "client-123",
                authorizationUrl = "https://issuer.example/oauth/authorize",
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                    relayRedirectUri = "https://relay.example/callback",
                    authorizeParams = mapOf("prompt" to "consent"),
                )

            val result =
                client.startOidcRedirectAuth(
                    provider = provider,
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
            assertTrue(decodedState.contains(""""redirect_uri":"omsclientkotlindemo://auth/callback""""))
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
    fun startOidcRedirectAuthRejectsInvalidSessionLifetimeBeforeRequest() =
        runBlocking {
            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient(
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
                            OidcProviderConfig(
                                issuer = "https://issuer.example",
                                clientId = "client-123",
                                authorizationUrl = "https://issuer.example/oauth/authorize",
                            ),
                        redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                    provider = OidcProviders.google(),
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                    provider = OidcProviders.google(),
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                    provider = OidcProviders.apple(),
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                )

            val result =
                client.startOidcRedirectAuth(
                    provider = provider,
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
    fun startOidcRedirectAuthWrapsRedirectStateStorageFailure() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":"pkce-challenge"}""")
                    .build(),
            )

            val storageFailure = IOException("OIDC redirect state save failed")
            val client =
                WalletClient(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment =
                        OMSWalletEnvironment(
                            walletApiUrl = server.url("/v1/Waas/").toString(),
                            indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                        ),
                    transport = OMSWalletHttpClient(),
                    sessionStore = InMemorySessionStore(),
                    oidcRedirectAuthStore = ThrowingSaveOidcRedirectAuthStore(storageFailure),
                    oidcNonceGenerator = { "nonce-123" },
                    credentialSigner = TrackingCredentialSigner(),
                )

            val error =
                runCatching {
                    client.startOidcRedirectAuth(
                        provider =
                            OidcProviderConfig(
                                issuer = "https://issuer.example",
                                clientId = "client-123",
                                authorizationUrl = "https://issuer.example/oauth/authorize",
                            ),
                        redirectUri = "omsclientkotlindemo://auth/callback",
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletStorageException)
            val sdkError = error as OMSWalletStorageException
            assertEquals(OMSWalletErrorCode.StorageError, sdkError.code)
            assertEquals(OMSWalletOperation.WalletStartOidcRedirectAuth, sdkError.operation)
            assertEquals("OIDC redirect auth state persistence failed", sdkError.message)
            assertSame(storageFailure, sdkError.cause)
            assertNull(client.snapshotSession())
            assertEquals(1, server.requestCount)
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
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
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                    redirectUri = "omsclientkotlindemo://auth/callback",
                )
            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}&scope=openid",
                )
            val wallet = (result as OidcRedirectAuthResult.Completed).wallet
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                        OidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                        ),
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
            assertTrue(result is OidcRedirectAuthResult.WalletSelection)
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
                WalletClient(
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
                        OidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                        ),
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                    redirectUri = "omsclientkotlindemo://auth/callback",
                )
            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                    sessionLifetimeSeconds = 0L,
                )

            assertTrue(result is OidcRedirectAuthResult.Failed)
            result as OidcRedirectAuthResult.Failed
            assertTrue(result.error is OMSWalletException)
            val error = result.error as OMSWalletException
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
                WalletClient(
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
                OidcProviderConfig(
                    issuer = "https://issuer.example",
                    clientId = "client-123",
                    authorizationUrl = "https://issuer.example/oauth/authorize",
                )

            val started =
                client.startOidcRedirectAuth(
                    provider = provider,
                    redirectUri = "omsclientkotlindemo://auth/callback",
                )
            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}&scope=openid",
                    walletSelection = WalletSelectionBehavior.Manual,
                )

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertTrue(result is OidcRedirectAuthResult.WalletSelection)
            val selection = result as OidcRedirectAuthResult.WalletSelection
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
                WalletClient(
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
                WalletClient(
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
                WalletClient(
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
                        OidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                        ),
                    redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                    OidcProviderConfig(
                        issuer = "https://issuer.example",
                        clientId = "client-123",
                        authorizationUrl = "https://issuer.example/oauth/authorize",
                    ),
                redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                    OidcProviderConfig(
                        issuer = "https://issuer.example",
                        clientId = "client-123",
                        authorizationUrl = "https://issuer.example/oauth/authorize",
                    ),
                redirectUri = "omsclientkotlindemo://auth/callback",
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
                WalletClient(
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
                        OidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                        ),
                    redirectUri = "omsclientkotlindemo://auth/callback",
                )

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl =
                        "omsclientkotlindemo://auth/callback" +
                            "?error=access_denied" +
                            "&error_description=User%20cancelled" +
                            "&state=${started.state}",
                )

            val failure = (result as OidcRedirectAuthResult.Failed).error as OMSWalletException
            assertEquals(OMSWalletOperation.WalletHandleOidcRedirectCallback, failure.operation)
            assertEquals(OMSWalletErrorCode.ValidationError, failure.code)
            assertEquals("User cancelled", failure.message)
            assertNull(client.snapshotSession())
            assertNull(redirectStore.pending)
            assertEquals(2, redirectStore.clearCalls)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun handleOidcRedirectCallbackWrapsCompleteAuthFailureInOMSWalletException() =
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
                    .code(400)
                    .body("""{"error":"invalid request","code":7200,"msg":"Bad callback","status":400}""")
                    .build(),
            )

            val redirectStore = InMemoryOidcRedirectAuthStore()
            val client =
                WalletClient(
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
                        OidcProviderConfig(
                            issuer = "https://issuer.example",
                            clientId = "client-123",
                            authorizationUrl = "https://issuer.example/oauth/authorize",
                        ),
                    redirectUri = "omsclientkotlindemo://auth/callback",
                )

            val result =
                client.handleOidcRedirectCallback(
                    callbackUrl = "omsclientkotlindemo://auth/callback?code=auth-code&state=${started.state}",
                )
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())

            val failure = (result as OidcRedirectAuthResult.Failed).error as OMSWalletException
            assertEquals(OMSWalletErrorCode.RequestFailed, failure.code)
            assertEquals(OMSWalletOperation.WalletHandleOidcRedirectCallback, failure.operation)
            assertEquals(400, failure.status)
            assertEquals("Bad callback", failure.message)
            assertEquals(false, failure.retryable)
            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertNull(client.snapshotSession())
            assertNull(redirectStore.pending)
            assertEquals(2, redirectStore.clearCalls)
        }

    private class ThrowingSaveOidcRedirectAuthStore(
        private val failure: Throwable,
    ) : OidcRedirectAuthStore {
        override fun load(): PendingOidcRedirectAuth? = null

        override fun save(pending: PendingOidcRedirectAuth): Unit = throw failure

        override fun clear() = Unit
    }
}
