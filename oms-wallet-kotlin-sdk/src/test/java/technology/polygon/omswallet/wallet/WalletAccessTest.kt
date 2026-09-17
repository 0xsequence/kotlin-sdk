package technology.polygon.omswallet.wallet

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.internal.generated.waas.GetIDTokenRequest
import technology.polygon.omswallet.internal.generated.waas.ListAccessRequest
import technology.polygon.omswallet.internal.generated.waas.Page
import technology.polygon.omswallet.internal.generated.waas.RevokeAccessRequest
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.models.SmartSessionGrant
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import java.math.BigInteger

class WalletAccessTest {
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
    fun listAccessFollowsCursorsAndRevokeUsesWalletId() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "credentials": [
                            {
                              "credentialId": "credential-1",
                              "type": "direct",
                              "expiresAt": "2099-01-01T00:00:00Z",
                              "isCaller": true
                            }
                          ],
                          "page": {"cursor": "next"}
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "credentials": [
                            {
                              "credentialId": "credential-2",
                              "type": "direct",
                              "expiresAt": "2026-01-02T00:00:00Z",
                              "isCaller": false
                            }
                          ],
                          "page": {}
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"ok":true}""")
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
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000111"),
                )
            assertTrue(client.restorePersistedSession())

            val credentials = client.listAccess(pageSize = 2u)
            client.revokeAccess(credentialId = "credential-2")
            val firstListRequest = requireNotNull(server.takeRequest())
            val secondListRequest = requireNotNull(server.takeRequest())
            val revokeRequest = requireNotNull(server.takeRequest())

            assertEquals(listOf("credential-1", "credential-2"), credentials.map { it.credential.credentialId })
            assertEquals(true, credentials.first().credential.isCaller)
            assertEquals("/v1/Waas/ListAccess", firstListRequest.target)
            assertEquals(
                WaasApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 2u),
                    ),
                ),
                requireNotNull(firstListRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/ListAccess", secondListRequest.target)
            assertEquals(
                WaasApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 2u, cursor = "next"),
                    ),
                ),
                requireNotNull(secondListRequest.body).utf8(),
            )
            assertEquals("/v1/Waas/RevokeAccess", revokeRequest.target)
            assertEquals(
                WaasApi.RevokeAccess.encodeRequest(
                    RevokeAccessRequest(
                        targetCredentialId = "credential-2",
                        walletId = "wallet-main",
                    ),
                ),
                requireNotNull(revokeRequest.body).utf8(),
            )
            assertEquals("test-publishable-key", revokeRequest.headers[OMSWalletEnvironment.accessKeyHeaderName])
            assertNotNull(revokeRequest.headers[OMSWalletEnvironment.walletSignatureHeaderName])
        }

    @Test
    fun listAccessPagesEmitsEachWaasPage() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "credentials": [
                            {
                              "credentialId": "credential-1",
                              "type": "direct",
                              "expiresAt": "2099-01-01T00:00:00Z",
                              "isCaller": true
                            }
                          ],
                          "page": {"cursor": "next"}
                        }
                        """.trimIndent(),
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "credentials": [
                            {
                              "credentialId": "credential-2",
                              "type": "direct",
                              "expiresAt": "2026-01-02T00:00:00Z",
                              "isCaller": false
                            }
                          ],
                          "page": {}
                        }
                        """.trimIndent(),
                    ).build(),
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
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )
            assertTrue(client.restorePersistedSession())

            val pages = client.listAccessPages(pageSize = 1u).toList()
            val firstListRequest = requireNotNull(server.takeRequest())
            val secondListRequest = requireNotNull(server.takeRequest())

            assertEquals(2, pages.size)
            assertEquals(listOf("credential-1"), pages[0].grants.map { it.credential.credentialId })
            assertEquals("next", pages[0].page?.cursor)
            assertEquals(listOf("credential-2"), pages[1].grants.map { it.credential.credentialId })
            assertEquals(null, pages[1].page?.cursor)
            assertEquals(
                WaasApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 1u),
                    ),
                ),
                requireNotNull(firstListRequest.body).utf8(),
            )
            assertEquals(
                WaasApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 1u, cursor = "next"),
                    ),
                ),
                requireNotNull(secondListRequest.body).utf8(),
            )
        }

    @Test
    fun ownerSmartSessionFlowMapsRequestsAndResponses() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """{"metadata":{"appUrl":"https://app.example","appName":"Example","appLogoUrl":"https://app.example/logo.png","custom":{"environment":"test"}}}""",
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """{"sessionId":"session-1","expiry":"2099-01-01T00:00:00Z"}""",
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """{"session":{"sessionId":"session-1","walletId":"wallet-main","signerAddress":"0x3333333333333333333333333333333333333333","grants":{"entries":[{"kind":"nativeTransfer","nativeTransfer":{"to":"0x2222222222222222222222222222222222222222","limit":"100"}}]},"chainId":"137","expiresAt":"2099-01-01T00:00:00Z"}}""",
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """{"entries":[{"grant":{"kind":"nativeTransfer","nativeTransfer":{"to":"0x2222222222222222222222222222222222222222","limit":"100"}},"used":"25"}]}""",
                    ).build(),
            )
            val client = restoredWalletClient("1710000114")
            val grant =
                SmartSessionGrant.NativeTransfer(
                    to = "0x2222222222222222222222222222222222222222",
                    limit = BigInteger("100"),
                )

            val metadata = client.inspectRemoteCredential("remote-credential")
            val authorization =
                client.authorizeRemoteAccess(
                    credentialId = "remote-credential",
                    network = Network.POLYGON,
                    grants = listOf(grant),
                    expiresAt = "2099-01-01T00:00:00Z",
                )
            val session = client.getRemoteAccessSession(authorization.sessionId)
            val usage = client.getRemoteAccessSessionUsage(authorization.sessionId, Network.POLYGON)
            val inspect = requireNotNull(server.takeRequest())
            val authorize = requireNotNull(server.takeRequest())
            val getSession = requireNotNull(server.takeRequest())
            val getUsage = requireNotNull(server.takeRequest())

            assertEquals("Example", metadata.appName)
            assertEquals("/v1/WaasPublic/InspectCredential", inspect.target)
            assertEquals("/v1/Waas/AuthorizeRemoteAccess", authorize.target)
            assertTrue(requireNotNull(authorize.body).utf8().contains("\"chainId\":\"137\""))
            assertEquals("/v1/Waas/GetSession", getSession.target)
            assertEquals(137, session.chainId)
            assertEquals(listOf(grant), session.grants)
            assertEquals("/v1/Waas/GetSessionUsage", getUsage.target)
            assertEquals(BigInteger("25"), usage.single().used)
        }

    @Test
    fun getIdTokenUsesGeneratedWaasRequest() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"idToken":"id-token-value"}""")
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
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSWalletSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                    auth = emailSessionAuth(),
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000113"),
                )
            assertTrue(client.restorePersistedSession())

            val idToken =
                client.getIdToken(
                    ttlSeconds = 300u,
                    customClaims =
                        mapOf(
                            "role" to JsonPrimitive("admin"),
                        ),
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("id-token-value", idToken)
            assertEquals("/v1/Waas/GetIDToken", request.target)
            assertEquals(
                WaasApi.GetIDToken.encodeRequest(
                    GetIDTokenRequest(
                        walletId = "wallet-main",
                        ttlSeconds = 300u,
                        customClaims =
                            mapOf(
                                "role" to JsonPrimitive("admin"),
                            ),
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )
            assertEquals("test-publishable-key", request.headers[OMSWalletEnvironment.accessKeyHeaderName])
            assertNotNull(request.headers[OMSWalletEnvironment.walletSignatureHeaderName])
        }

    @Test
    fun revokeAccessRequiresActiveCredential() =
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
                    credentialSigner = MockWebCryptoCredentialSigner(available = false),
                )
            client.restoreSession(activeSessionSnapshot())

            val error =
                runCatching {
                    client.revokeAccess(credentialId = "credential-2")
                }.exceptionOrNull()

            assertTrue(error is OMSWalletException)
            assertEquals(OMSWalletErrorCode.SessionMissing, (error as OMSWalletException).code)
            assertEquals(0, server.requestCount)
        }

    private fun restoredWalletClient(nonce: String): WalletClient =
        WalletClient
            .create(
                publishableKey = "test-publishable-key",
                projectId = "test-project-id",
                environment =
                    OMSWalletEnvironment(
                        walletApiUrl = server.url("/v1/Waas/").toString(),
                        indexerGatewayUrl = server.url("/v1/IndexerGateway/").toString(),
                    ),
                transport = OMSWalletHttpClient(),
                sessionStore =
                    InMemorySessionStore(
                        snapshot =
                            OMSWalletSessionSnapshot(
                                walletId = "wallet-main",
                                walletAddress = "0x1111111111111111111111111111111111111111",
                                signerAddress = TEST_CREDENTIAL_ID,
                                signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                auth = emailSessionAuth(),
                            ),
                    ),
                credentialSigner = TrackingCredentialSigner(nonceValue = nonce),
            ).also { assertTrue(it.restorePersistedSession()) }
}
