package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.ListAccessRequest
import com.omsclient.kotlin_sdk.generated.waas.Page
import com.omsclient.kotlin_sdk.generated.waas.RevokeAccessRequest
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
                    projectAccessKey = "test-access-key",
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
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000111L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
                    projectAccessKey = "test-access-key",
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
                                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                                ),
                            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
                        ),
                    nonceGenerator = { 1710000112L },
                    privateKeyFactory = ::fixedPrivateKeyBytes,
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
    fun revokeAccessRequiresActiveCredential() =
        runBlocking {
            val client =
                WalletClient(
                    projectAccessKey = "test-access-key",
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

            assertTrue(error is IllegalStateException)
            assertEquals(0, server.requestCount)
        }
}
