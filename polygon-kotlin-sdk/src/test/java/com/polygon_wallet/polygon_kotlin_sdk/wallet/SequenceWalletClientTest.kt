package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.models.CompleteAuthResponse
import com.polygon_wallet.polygon_kotlin_sdk.session.SequenceSessionSnapshot
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequencePrivateKeyStore
import com.polygon_wallet.polygon_kotlin_sdk.storage.SequenceSessionStore
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
    fun signInWithEmailSendsCanonicalSignedRequestAndStoresSession() = runBlocking {
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

        val expectedPayload = WalletPayloadBuilder.buildCommitVerifierPayload("user@example.com")
        val expectedSignedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = WalletApi.Endpoints.commitVerifier,
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
        assertEquals(session, store.snapshot)
        assertEquals(FIXED_PRIVATE_KEY_HEX, store.privateKeyHex)
    }

    @Test
    fun signInWithEmailSignsConfiguredWalletPathPrefix() = runBlocking {
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
        val expectedPayload = WalletPayloadBuilder.buildCommitVerifierPayload("user@example.com")
        val expectedSignedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = WalletApi.Endpoints.commitVerifier,
            nonce = "1710000105",
            payload = expectedPayload,
            scope = environment.authorizationScope,
            privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            requestPathPrefix = "/custom/wallet",
        )

        assertEquals("/custom/wallet/CommitVerifier", request.target)
        assertEquals(
            expectedSignedRequest.authorizationHeader.removePrefix("Authorization: "),
            request.headers["Authorization"],
        )
    }

    @Test
    fun confirmEmailSignInUsesStoredSessionAndParsesWallets() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "identity": {"type":"Email","sub":"sub-123","email":"user@example.com"},
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
            sessionStore = InMemorySessionStore(
                snapshot = SequenceSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
            nonceGenerator = { 1710000101L },
        )
        assertTrue(client.restorePersistedSession())

        val response = client.confirmEmailSignIn("123456")
        val request = requireNotNull(server.takeRequest())

        val expectedPayload = WalletPayloadBuilder.buildCompleteAuthPayloadFromCode(
            verifier = "verifier-123",
            challenge = "challenge",
            code = "123456",
        )
        val expectedSignedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = WalletApi.Endpoints.completeAuth,
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
        assertEquals("Ethereum_EOA", response.wallets.single().type)
        assertEquals("0xabc", response.wallets.single().address)
    }

    @Test
    fun restorePersistedSessionLoadsFromStore() {
        val snapshot = SequenceSessionSnapshot(
            challenge = "challenge",
            verifier = "verifier-123",
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
        assertTrue(client.hasSession)
        assertTrue(client.isSignedIn)
        assertEquals("0xabc", client.currentWalletAddress)
        assertEquals(
            WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
            client.currentSignerAddress,
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
            sessionStore = InMemorySessionStore(
                snapshot = SequenceSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
            nonceGenerator = { 1710000102L },
        )
        assertTrue(client.restorePersistedSession())

        val resolved = client.resolveWallet(
            CompleteAuthResponse(
                identity = null,
                wallets = listOf(
                    com.polygon_wallet.polygon_kotlin_sdk.models.SequenceWallet(
                        type = environment.defaultWalletType,
                        address = "0xdef",
                        index = 3,
                        comment = "picked",
                    ),
                ),
            ),
        )
        val request = requireNotNull(server.takeRequest())

        val expectedPayload = WalletPayloadBuilder.buildUseWalletPayload(
            walletType = environment.defaultWalletType,
            walletIndex = 3,
        )

        assertEquals("/rpc/Wallet/UseWallet", request.target)
        assertEquals(expectedPayload, requireNotNull(request.body).utf8())
        assertEquals(3, resolved.index)
        assertEquals("0xdef", resolved.address)
    }

    @Test
    fun clearSessionClearsPersistedStore() {
        val snapshot = SequenceSessionSnapshot(
            challenge = "challenge",
            verifier = "verifier-123",
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
        assertFalse(client.hasSession)
        assertFalse(client.isSignedIn)
        assertNull(client.currentWalletAddress)
        assertNull(client.currentSignerAddress)
        assertNull(store.privateKeyHex)
    }

    @Test
    fun requireWalletAddressReturnsSelectedWallet() {
        val client = SequenceWalletClient(
            projectAccessKey = "test-access-key",
            environment = SequenceEnvironment(),
            sessionStore = InMemorySessionStore(
                snapshot = SequenceSessionSnapshot(
                    challenge = "challenge",
                    verifier = "verifier-123",
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
        )
        assertTrue(client.restorePersistedSession())

        assertEquals("0xwallet", client.requireWalletAddress())
        assertFalse(client.hasPendingSignIn)
    }

    @Test
    fun hasPendingSignInIsTrueBeforeWalletSelection() {
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
        assertTrue(client.restorePersistedSession())

        assertTrue(client.hasSession)
        assertTrue(client.hasPendingSignIn)
        assertFalse(client.isSignedIn)
        assertNull(client.currentWalletAddress)
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
                challenge = "challenge",
                verifier = "verifier-123",
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
        assertEquals(0, store.savePrivateKeyCalls)
        assertTrue(requireNotNull(store.lastProvidedPrivateKey).all { it == 0.toByte() })
    }

    @Test
    fun sendTransactionWithNullResponseFailsGracefully() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"response":null}""")
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
                    challenge = "challenge",
                    verifier = "verifier-123",
                    walletAddress = "0xwallet",
                    signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
                ),
                privateKeyHex = FIXED_PRIVATE_KEY_HEX,
            ),
            nonceGenerator = { 1710000106L },
        )
        assertTrue(client.restorePersistedSession())

        val failure = runCatching {
            client.sendTransaction(
                chainId = "80002",
                to = "0xabc",
                value = "0",
            )
        }.exceptionOrNull()

        assertEquals(
            "SendTransaction response missing response.txHash",
            failure?.message,
        )
    }

    companion object {
        private const val FIXED_PRIVATE_KEY_HEX =
            "0x1111111111111111111111111111111111111111111111111111111111111111"

        private fun fixedPrivateKeyBytes(): ByteArray =
            Numeric.hexStringToByteArray(FIXED_PRIVATE_KEY_HEX)
    }

    private class InMemorySessionStore(
        var snapshot: SequenceSessionSnapshot? = null,
        var privateKeyHex: String? = null,
    ) : SequenceSessionStore, SequencePrivateKeyStore {
        override fun load(): SequenceSessionSnapshot? = snapshot

        override fun save(snapshot: SequenceSessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun savePrivateKey(privateKey: ByteArray) {
            privateKeyHex = Numeric.toHexString(privateKey)
        }

        override suspend fun <T> withPrivateKey(block: suspend (ByteArray) -> T): T =
            block(Numeric.hexStringToByteArray(requireNotNull(privateKeyHex)))

        override fun clearPrivateKey() {
            privateKeyHex = null
        }

        override fun clear() {
            snapshot = null
            privateKeyHex = null
        }
    }

    private class TrackingPrivateKeyStore(
        private val snapshot: SequenceSessionSnapshot,
        private var privateKeyHex: String,
    ) : SequenceSessionStore, SequencePrivateKeyStore {
        var withPrivateKeyCalls: Int = 0
            private set
        var savePrivateKeyCalls: Int = 0
            private set
        var lastProvidedPrivateKey: ByteArray? = null
            private set

        override fun load(): SequenceSessionSnapshot = snapshot

        override fun save(snapshot: SequenceSessionSnapshot) = Unit

        override fun savePrivateKey(privateKey: ByteArray) {
            savePrivateKeyCalls += 1
            privateKeyHex = Numeric.toHexString(privateKey)
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

        override fun clearPrivateKey() {
            privateKeyHex = ""
        }

        override fun clear() = Unit
    }
}
