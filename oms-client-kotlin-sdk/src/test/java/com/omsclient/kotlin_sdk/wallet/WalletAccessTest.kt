package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkException
import com.omsclient.kotlin_sdk.internal.generated.waas.GetIDTokenRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.ListAccessRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.Page
import com.omsclient.kotlin_sdk.internal.generated.waas.RevokeAccessRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
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
                              "expiresAt": "2026-01-01T00:00:00Z",
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000111"),
                )
            assertTrue(client.restorePersistedSession())

            val credentials = client.listAccess(pageSize = 2u)
            client.revokeAccess(targetCredentialId = "credential-2")
            val firstListRequest = requireNotNull(server.takeRequest())
            val secondListRequest = requireNotNull(server.takeRequest())
            val revokeRequest = requireNotNull(server.takeRequest())

            assertEquals(listOf("credential-1", "credential-2"), credentials.map { it.credentialId })
            assertEquals(true, credentials.first().isCaller)
            assertEquals("/rpc/Wallet/ListAccess", firstListRequest.target)
            assertEquals(
                WaasWalletApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 2u),
                    ),
                ),
                requireNotNull(firstListRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/ListAccess", secondListRequest.target)
            assertEquals(
                WaasWalletApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 2u, cursor = "next"),
                    ),
                ),
                requireNotNull(secondListRequest.body).utf8(),
            )
            assertEquals("/rpc/Wallet/RevokeAccess", revokeRequest.target)
            assertEquals(
                WaasWalletApi.RevokeAccess.encodeRequest(
                    RevokeAccessRequest(
                        targetCredentialId = "credential-2",
                        walletId = "wallet-main",
                    ),
                ),
                requireNotNull(revokeRequest.body).utf8(),
            )
            assertEquals("test-access-key", revokeRequest.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertNotNull(revokeRequest.headers[OMSClientEnvironment.walletSignatureHeaderName])
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
                              "expiresAt": "2026-01-01T00:00:00Z",
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                                ),
                        ),
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000112"),
                )
            assertTrue(client.restorePersistedSession())

            val pages = client.listAccessPages(pageSize = 1u).toList()
            val firstListRequest = requireNotNull(server.takeRequest())
            val secondListRequest = requireNotNull(server.takeRequest())

            assertEquals(2, pages.size)
            assertEquals(listOf("credential-1"), pages[0].credentials.map { it.credentialId })
            assertEquals("next", pages[0].page?.cursor)
            assertEquals(listOf("credential-2"), pages[1].credentials.map { it.credentialId })
            assertEquals(null, pages[1].page?.cursor)
            assertEquals(
                WaasWalletApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 1u),
                    ),
                ),
                requireNotNull(firstListRequest.body).utf8(),
            )
            assertEquals(
                WaasWalletApi.ListAccess.encodeRequest(
                    ListAccessRequest(
                        walletId = "wallet-main",
                        page = Page(limit = 1u, cursor = "next"),
                    ),
                ),
                requireNotNull(secondListRequest.body).utf8(),
            )
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
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    sessionStore =
                        InMemorySessionStore(
                            snapshot =
                                OMSClientSessionSnapshot(
                                    walletId = "wallet-main",
                                    walletAddress = "0xwallet",
                                    signerAddress = TEST_CREDENTIAL_ID,
                                    signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
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
            assertEquals("/rpc/Wallet/GetIDToken", request.target)
            assertEquals(
                WaasWalletApi.GetIDToken.encodeRequest(
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
            assertEquals("test-access-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertNotNull(request.headers[OMSClientEnvironment.walletSignatureHeaderName])
        }

    @Test
    fun revokeAccessRequiresActiveCredential() =
        runBlocking {
            val client =
                WalletClient(
                    publicApiKey = "test-access-key",
                    projectId = "test-project-id",
                    environment =
                        OMSClientEnvironment(
                            walletApiUrl = server.url("/rpc/Wallet/").toString(),
                        ),
                    transport = OMSClientHttpClient(),
                    credentialSigner = MockWebCryptoCredentialSigner(available = false),
                )
            client.restoreSession(activeSessionSnapshot())

            val error =
                runCatching {
                    client.revokeAccess(targetCredentialId = "credential-2")
                }.exceptionOrNull()

            assertTrue(error is OmsSdkException)
            assertEquals(OmsSdkErrorCode.SessionMissing, (error as OmsSdkException).code)
            assertEquals(0, server.requestCount)
        }
}
