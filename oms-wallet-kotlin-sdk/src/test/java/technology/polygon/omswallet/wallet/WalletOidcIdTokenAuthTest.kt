package technology.polygon.omswallet.wallet

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
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.internal.generated.waas.AuthMode
import technology.polygon.omswallet.internal.generated.waas.CommitVerifierRequest
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthRequest
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.Page
import technology.polygon.omswallet.internal.generated.waas.UseWalletRequest
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient

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
    fun signInWithOidcIdTokenCommitsCompletesAndResolvesWallet() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
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
                                    iss = "https://accounts.google.com",
                                    sub = "google-sub-123",
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
            val store = InMemorySessionStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )

            val result =
                client.signInWithOidcIdToken(
                    idToken = idToken,
                    issuer = "https://accounts.google.com",
                    audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                )
            val wallet = (result as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals(
                WaasApi.CommitVerifier.encodeRequest(
                    CommitVerifierRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.IDToken,
                        metadata =
                            mapOf(
                                "iss" to "https://accounts.google.com",
                                "aud" to "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                                "exp" to "1910000100",
                            ),
                        handle = OidcIdToken.handleHash(idToken),
                    ),
                ),
                requireNotNull(commitRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.IDToken,
                        verifier = "oidc-verifier-123",
                        answer = idToken,
                        lifetime = 604_800u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(
                    UseWalletRequest(
                        walletId = "wallet-def",
                    ),
                ),
                requireNotNull(useWalletRequest.body).utf8(),
            )
            assertEquals("0xdef", wallet.address)
            assertEquals("0xdef", client.walletAddress)
            assertFalse(client.hasPendingSignIn)
            assertEquals("wallet-def", store.snapshot?.walletId)
            assertEquals("0xdef", store.snapshot?.walletAddress)
            assertEquals("2099-01-01T00:00:00Z", store.snapshot?.expiresAt)
            assertOidcSessionAuth(store.snapshot?.auth, flow = OMSWalletOidcSessionAuthFlow.IdToken)
            assertEquals(WalletSigningAlgorithm.ECDSA_P256_SHA256, store.snapshot?.signerKeyType)
            assertNull(store.snapshot?.verifier)
            assertNull(store.snapshot?.challenge)
            assertEquals(1, store.saveCalls)
        }

    @Test
    fun signInWithOidcIdTokenStoresCustomProviderMetadata() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
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
                                    sub = "custom-sub-123",
                                ),
                            email = "user@example.com",
                            wallets = listOf(walletFixture("wallet-custom-oidc", "0xcustomoidc", "picked")),
                        ),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(walletResponseBody(walletId = "wallet-custom-oidc", address = "0xcustomoidc", reference = "picked"))
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
                    credentialSigner = TrackingCredentialSigner(),
                )

            client.signInWithOidcIdToken(
                idToken = idToken,
                issuer = "https://issuer.example",
                audience = "custom-client-id",
                provider = "custom",
                providerLabel = "Custom",
            )

            assertOidcSessionAuth(
                store.snapshot?.auth,
                flow = OMSWalletOidcSessionAuthFlow.IdToken,
                issuer = "https://issuer.example",
                provider = "custom",
                providerLabel = "Custom",
            )
        }

    @Test
    fun signInWithOidcIdTokenUsesRequestedSessionLifetime() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","challenge":""}""")
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
                                    iss = "https://accounts.google.com",
                                    sub = "google-sub-123",
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
                    credentialSigner = TrackingCredentialSigner(),
                )

            client.signInWithOidcIdToken(
                idToken = idToken,
                issuer = "https://accounts.google.com",
                audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                sessionLifetimeSeconds = 120L,
            )

            requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            assertEquals(
                WaasApi.CompleteAuth.encodeRequest(
                    CompleteAuthRequest(
                        identityType = IdentityType.OIDC,
                        authMode = AuthMode.IDToken,
                        verifier = "oidc-verifier-123",
                        answer = idToken,
                        lifetime = 120u,
                    ),
                ),
                requireNotNull(completeAuthRequest.body).utf8(),
            )
        }

    @Test
    fun signInWithOidcIdTokenRejectsInvalidSessionLifetimeBeforeRequest() =
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
                    client.signInWithOidcIdToken(
                        idToken = fakeJwt(exp = 1910000100L),
                        issuer = "https://accounts.google.com",
                        audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                        sessionLifetimeSeconds = 2_592_001L,
                    )
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            error as OMSWalletException
            assertEquals(OMSWalletErrorCode.ValidationError, error.code)
            assertEquals("wallet.signInWithOidcIdToken", error.operation?.id)
            assertEquals("sessionLifetimeSeconds must be an integer between 1 and 2592000", error.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun signInWithOidcIdTokenCanReturnWalletSelectionWithoutSelectingWallet() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
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
                                    iss = "https://accounts.google.com",
                                    sub = "google-sub-123",
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
            val store = InMemorySessionStore()
            val client =
                WalletClient.create(
                    publishableKey = "test-publishable-key",
                    projectId = "test-project-id",
                    environment = environment,
                    transport = OMSWalletHttpClient(),
                    sessionStore = store,
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )

            val result =
                client.signInWithOidcIdToken(
                    idToken = idToken,
                    issuer = "https://accounts.google.com",
                    audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                    walletSelection = WalletSelectionBehavior.Manual,
                )

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertTrue(result is CompleteAuthResult.WalletSelection)
            val selection = result as CompleteAuthResult.WalletSelection
            assertEquals(environment.defaultWalletType, selection.pendingSelection.walletType)
            assertEquals(listOf("wallet-def"), selection.pendingSelection.wallets.map { it.id })
            assertEquals("credential-123", selection.pendingSelection.credential.credentialId)
            assertNull(client.walletAddress)
            assertTrue(client.hasPendingSignIn)
            assertOidcSessionAuth(client.snapshotSession()?.auth, flow = OMSWalletOidcSessionAuthFlow.IdToken)
            assertNull(client.snapshotSession()?.walletId)
            assertNull(store.snapshot)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun signInWithOidcIdTokenClearsPendingOidcRedirectAuth() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
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
                                    iss = "https://accounts.google.com",
                                    sub = "google-sub-123",
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
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )

            val result =
                client.signInWithOidcIdToken(
                    idToken = idToken,
                    issuer = "https://accounts.google.com",
                    audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                )
            val wallet = (result as CompleteAuthResult.WalletSelected).wallet

            assertEquals("0xdef", wallet.address)
            assertNull(redirectStore.pending)
            assertEquals(1, redirectStore.clearCalls)
        }

    @Test
    fun signInWithOidcIdTokenReplacesActiveWalletSession() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
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
                                    iss = "https://accounts.google.com",
                                    sub = "google-sub-123",
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

            val result =
                client.signInWithOidcIdToken(
                    idToken = idToken,
                    issuer = "https://accounts.google.com",
                    audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                )
            val wallet = (result as CompleteAuthResult.WalletSelected).wallet
            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())
            val useWalletRequest = requireNotNull(server.takeRequest())

            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertEquals("/v1/Waas/UseWallet", useWalletRequest.target)
            assertEquals("0xdef", wallet.address)
            assertEquals("wallet-def", client.snapshotSession()?.walletId)
            assertEquals("0xdef", store.snapshot?.walletAddress)
        }

    @Test
    fun signInWithOidcIdTokenClearsPersistedSessionWhenCompleteAuthFails() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":"IdentityProviderError","code":7104,"msg":"Identity provider error","status":500}""")
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
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )

            val failure =
                runCatching {
                    client.signInWithOidcIdToken(
                        idToken = idToken,
                        issuer = "https://accounts.google.com",
                        audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                    )
                }.exceptionOrNull()

            val commitRequest = requireNotNull(server.takeRequest())
            val completeAuthRequest = requireNotNull(server.takeRequest())

            assertNotNull(failure)
            assertEquals("/v1/Waas/CommitVerifier", commitRequest.target)
            assertEquals("/v1/Waas/CompleteAuth", completeAuthRequest.target)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.walletAddress)
            assertNull(client.signerAddress)
            assertNull(store.snapshot)
            assertEquals(0, store.saveCalls)
        }

    @Test
    fun signInWithOidcIdTokenClearsStateWhenCommitVerifierFails() =
        runBlocking {
            val idToken = fakeJwt(exp = 1910000100L)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":"InternalError","code":5000,"msg":"commit verifier failed","status":500}""")
                    .build(),
            )

            val signer = TrackingCredentialSigner(nonceValue = "1710000111")
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
                    client.signInWithOidcIdToken(
                        idToken = idToken,
                        issuer = "https://accounts.google.com",
                        audience = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com",
                    )
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
}
