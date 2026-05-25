package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.internal.generated.waas.SignTypedDataRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WalletSigningTest {
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
    fun signMessageUsesCredentialSignerForRestoredSession() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"signature":"0xsigned"}""")
                    .build(),
            )

            val signer = TrackingCredentialSigner()
            val store =
                InMemorySessionStore(
                    snapshot =
                        OMSClientSessionSnapshot(
                            walletId = "wallet-main",
                            walletAddress = "0xwallet",
                            signerAddress = TEST_CREDENTIAL_ID,
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                        ),
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
                    sessionStore = store,
                    credentialSigner = signer,
                )

            assertTrue(client.restorePersistedSession())
            assertEquals(0, signer.signCalls)

            val result =
                client.signMessage(
                    network = Network.AMOY,
                    message = "hello",
                )

            assertEquals("0xsigned", result)
            assertEquals(1, signer.signCalls)
            assertEquals(0, store.saveCalls)
        }

    @Test
    fun signTypedDataUsesGeneratedWaasRequest() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"signature":"0xtyped"}""")
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
                    credentialSigner = TrackingCredentialSigner(nonceValue = "1710000109"),
                )
            assertTrue(client.restorePersistedSession())

            val typedData =
                buildJsonObject {
                    put("contents", "hello")
                }
            val result =
                client.signTypedData(
                    network = Network.AMOY,
                    typedData = typedData,
                )
            val request = requireNotNull(server.takeRequest())

            assertEquals("0xtyped", result)
            assertEquals("/rpc/Wallet/SignTypedData", request.target)
            assertEquals(
                WaasWalletApi.SignTypedData.encodeRequest(
                    SignTypedDataRequest(
                        walletId = "wallet-main",
                        network = "80002",
                        typedData = typedData,
                    ),
                ),
                requireNotNull(request.body).utf8(),
            )
            assertEquals("test-access-key", request.headers[OMSClientEnvironment.accessKeyHeaderName])
            assertNotNull(request.headers[OMSClientEnvironment.walletSignatureHeaderName])
        }
}
