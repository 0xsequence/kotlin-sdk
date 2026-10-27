package technology.polygon.omswallet.wallet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletRequestException
import technology.polygon.omswallet.OMSWalletValidationException
import technology.polygon.omswallet.internal.generated.waas.ClaimWalletRequest
import technology.polygon.omswallet.internal.generated.waas.ClaimWalletResponse
import technology.polygon.omswallet.internal.generated.waas.CreateWalletRequest
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.NetworkFamily
import technology.polygon.omswallet.internal.generated.waas.UseWalletRequest
import technology.polygon.omswallet.internal.generated.waas.WaasApi
import technology.polygon.omswallet.internal.generated.waas.Wallet
import technology.polygon.omswallet.internal.generated.waas.WalletType
import technology.polygon.omswallet.internal.generated.waas.WebRpcJson
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.models.WalletType as SdkWalletType

class WalletClaimableWalletTest {
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
    fun automaticSelectionClaimsClaimableWalletOfRequestedTypeWhenNoOwnedWalletExists() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueueJson(
                completeAuthResponseBody(
                    wallets = listOf(OWNED_SOLANA),
                    claimable = listOf(CLAIMABLE_SOLANA, CLAIMABLE_ETHEREUM),
                ),
            )
            server.enqueueJson(claimWalletResponseBody(CLAIMABLE_ETHEREUM))

            val store = InMemorySessionStore()
            val client = createClient(store)
            client.startEmailAuth("user@example.com")

            val result = client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected

            requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/CompleteAuth", requireNotNull(server.takeRequest()).target)
            val claimRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/ClaimWallet", claimRequest.target)
            assertEquals("""{"walletId":"wallet-claim-evm"}""", claimRequest.utf8Body())
            assertEquals(
                WaasApi.ClaimWallet.encodeRequest(ClaimWalletRequest(walletId = "wallet-claim-evm")),
                claimRequest.utf8Body(),
            )
            // No CreateWallet or UseWallet call follows the claim.
            assertEquals(3, server.requestCount)
            assertEquals("wallet-claim-evm", result.wallet.id)
            assertEquals(CLAIMABLE_ETHEREUM.address, result.wallet.address)
            assertEquals(SdkWalletType.Ethereum, result.wallet.type)
            assertEquals(listOf("wallet-owned-sol", "wallet-claim-evm"), result.wallets.map { it.id })
            assertEquals(listOf("wallet-claim-sol"), result.claimableWallets.map { it.id })
            assertEquals(SdkWalletType.Solana, result.claimableWallets.single().type)
            assertEquals("wallet-claim-evm", client.activeWallet?.id)
            assertEquals("wallet-claim-evm", store.snapshot?.walletId)
            assertEquals(CLAIMABLE_ETHEREUM.address, store.snapshot?.walletAddress)
            assertEquals(TEST_SESSION_EXPIRES_AT, store.snapshot?.expiresAt)
            assertEmailSessionAuth(store.snapshot?.auth)
            assertFalse(client.hasPendingSignIn)
        }

    @Test
    fun oidcIdTokenAutomaticSelectionClaimsClaimableWallet() =
        runBlocking {
            server.enqueueJson("""{"verifier":"oidc-verifier-123","loginHint":"user@example.com","challenge":""}""")
            server.enqueueJson(
                completeAuthResponseBody(
                    identity = identityFixture(IdentityType.OIDC, iss = "https://accounts.google.com", sub = "google-sub-123"),
                    wallets = emptyList(),
                    claimable = listOf(CLAIMABLE_ETHEREUM),
                ),
            )
            server.enqueueJson(claimWalletResponseBody(CLAIMABLE_ETHEREUM))

            val store = InMemorySessionStore()
            val client = createClient(store)
            val result =
                client.signInWithOidcIdToken(
                    idToken = fakeJwt(exp = 1910000100L),
                    issuer = "https://accounts.google.com",
                    audience = "demo-web-client-id",
                ) as CompleteAuthResult.WalletSelected

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val claimRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/ClaimWallet", claimRequest.target)
            assertEquals("""{"walletId":"wallet-claim-evm"}""", claimRequest.utf8Body())
            assertEquals(3, server.requestCount)
            assertEquals("wallet-claim-evm", result.wallet.id)
            assertTrue(result.claimableWallets.isEmpty())
            assertEquals("wallet-claim-evm", store.snapshot?.walletId)
        }

    @Test
    fun automaticSelectionUsesOwnedWalletBeforeClaimableWallet() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueueJson(
                completeAuthResponseBody(
                    wallets = listOf(OWNED_ETHEREUM),
                    claimable = listOf(CLAIMABLE_ETHEREUM),
                ),
            )
            server.enqueueJson(walletResponseBody(OWNED_ETHEREUM.id, OWNED_ETHEREUM.address))

            val store = InMemorySessionStore()
            val client = createClient(store)
            client.startEmailAuth("user@example.com")

            val result = client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val useRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/UseWallet", useRequest.target)
            assertEquals(
                WaasApi.UseWallet.encodeRequest(UseWalletRequest(walletId = "wallet-owned-evm")),
                useRequest.utf8Body(),
            )
            assertEquals(3, server.requestCount)
            assertEquals("wallet-owned-evm", result.wallet.id)
            assertEquals(listOf("wallet-owned-evm"), result.wallets.map { it.id })
            assertEquals(listOf("wallet-claim-evm"), result.claimableWallets.map { it.id })
            assertEquals("wallet-owned-evm", store.snapshot?.walletId)
        }

    @Test
    fun automaticSelectionCreatesWalletWhenOnlyAnotherTypeIsClaimable() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueueJson(
                completeAuthResponseBody(
                    wallets = emptyList(),
                    claimable = listOf(CLAIMABLE_SOLANA),
                ),
            )
            server.enqueueJson(walletResponseBody("wallet-new", "0x4e00000000000000000000000000000000000000"))

            val store = InMemorySessionStore()
            val client = createClient(store)
            client.startEmailAuth("user@example.com")

            val result = client.completeEmailAuth("123456") as CompleteAuthResult.WalletSelected

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val createRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/CreateWallet", createRequest.target)
            assertEquals(
                WaasApi.CreateWallet.encodeRequest(CreateWalletRequest(networkFamily = NetworkFamily.EVM)),
                createRequest.utf8Body(),
            )
            assertEquals(3, server.requestCount)
            assertEquals("wallet-new", result.wallet.id)
            assertEquals(listOf("wallet-new"), result.wallets.map { it.id })
            assertEquals(listOf("wallet-claim-sol"), result.claimableWallets.map { it.id })
            assertEquals("wallet-new", store.snapshot?.walletId)
        }

    @Test
    fun automaticClaimFailureFailsSignInWithoutCreatingWallet() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueueJson(
                completeAuthResponseBody(
                    wallets = emptyList(),
                    claimable = listOf(CLAIMABLE_ETHEREUM),
                ),
            )
            enqueueAccountNotFound()

            val store = InMemorySessionStore()
            val client = createClient(store)
            client.startEmailAuth("user@example.com")

            val failure = runCatching { client.completeEmailAuth("123456") }.exceptionOrNull()

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/ClaimWallet", requireNotNull(server.takeRequest()).target)
            assertEquals(3, server.requestCount)
            val requestFailure = failure as OMSWalletRequestException
            assertEquals(OMSWalletErrorCode.RequestFailed, requestFailure.code)
            assertEquals(OMSWalletOperation.WalletCompleteEmailAuth, requestFailure.operation)
            assertEquals("AccountNotFound", requestFailure.upstreamError?.name)
            assertNull(client.snapshotSession())
            assertFalse(client.hasPendingSignIn)
            assertNull(client.activeWallet)
            assertNull(store.snapshot)
        }

    @Test
    fun manualSelectionExposesClaimableWalletsAndClaimWalletCompletesIt() =
        runBlocking {
            enqueueEmailAuthStart()
            server.enqueueJson(
                completeAuthResponseBody(
                    wallets = listOf(OWNED_ETHEREUM),
                    claimable = listOf(CLAIMABLE_ETHEREUM, CLAIMABLE_SOLANA),
                ),
            )

            val store = InMemorySessionStore()
            val client = createClient(store)
            client.startEmailAuth("user@example.com")

            val result =
                client.completeEmailAuth(
                    code = "123456",
                    walletSelection = WalletSelectionBehavior.Manual,
                ) as CompleteAuthResult.WalletSelection
            val pending = result.pendingSelection

            assertEquals(2, server.requestCount)
            assertEquals(listOf("wallet-owned-evm"), pending.wallets.map { it.id })
            assertEquals(listOf("wallet-claim-evm"), pending.claimableWallets.map { it.id })
            assertEquals(CLAIMABLE_ETHEREUM.address, pending.claimableWallets.single().address)
            assertNull(client.activeWallet)
            assertNull(store.snapshot)

            server.enqueueJson(claimWalletResponseBody(CLAIMABLE_ETHEREUM))
            val claimed = client.claimWallet(pending.claimableWallets.single().id)

            requireNotNull(server.takeRequest())
            requireNotNull(server.takeRequest())
            val claimRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/ClaimWallet", claimRequest.target)
            assertEquals("""{"walletId":"wallet-claim-evm"}""", claimRequest.utf8Body())
            assertEquals("wallet-claim-evm", claimed.wallet.id)
            assertEquals("wallet-claim-evm", client.activeWallet?.id)
            assertFalse(client.hasPendingSignIn)
            assertEquals("wallet-claim-evm", store.snapshot?.walletId)
            assertEquals(CLAIMABLE_ETHEREUM.address, store.snapshot?.walletAddress)
            assertEmailSessionAuth(store.snapshot?.auth)
        }

    @Test
    fun claimWalletSwitchesActiveWalletOfActiveSession() =
        runBlocking {
            server.enqueueJson(claimWalletResponseBody(CLAIMABLE_ETHEREUM))
            val store = InMemorySessionStore(activeSessionSnapshot())
            val client = createClient(store)
            assertTrue(client.restorePersistedSession())

            val claimed = client.claimWallet("wallet-claim-evm")

            val claimRequest = requireNotNull(server.takeRequest())
            assertEquals("/v1/Waas/ClaimWallet", claimRequest.target)
            assertEquals("""{"walletId":"wallet-claim-evm"}""", claimRequest.utf8Body())
            assertEquals("wallet-claim-evm", claimed.wallet.id)
            assertEquals(CLAIMABLE_ETHEREUM.address, claimed.wallet.address)
            assertEquals("wallet-claim-evm", client.activeWallet?.id)
            assertEquals("wallet-claim-evm", store.snapshot?.walletId)
            assertEquals(CLAIMABLE_ETHEREUM.address, store.snapshot?.walletAddress)
        }

    @Test
    fun claimWalletFailureSurfacesRequestErrorAndKeepsActiveWallet() =
        runBlocking {
            enqueueAccountNotFound()
            val initial = activeSessionSnapshot()
            val store = InMemorySessionStore(initial)
            val client = createClient(store)
            assertTrue(client.restorePersistedSession())

            val failure = runCatching { client.claimWallet("wallet-claim-evm") }.exceptionOrNull()

            assertEquals("/v1/Waas/ClaimWallet", requireNotNull(server.takeRequest()).target)
            val requestFailure = failure as OMSWalletRequestException
            assertEquals(OMSWalletErrorCode.RequestFailed, requestFailure.code)
            assertEquals(OMSWalletOperation.WalletClaimWallet, requestFailure.operation)
            assertEquals(404, requestFailure.status)
            assertEquals("AccountNotFound", requestFailure.upstreamError?.name)
            assertEquals("wallet-active", client.activeWallet?.id)
            assertEquals(initial, store.snapshot)
        }

    @Test
    fun claimWalletRejectsBlankWalletIdWithoutRequest() =
        runBlocking {
            val store = InMemorySessionStore(activeSessionSnapshot())
            val client = createClient(store)
            assertTrue(client.restorePersistedSession())

            val failure = runCatching { client.claimWallet(" ") }.exceptionOrNull()

            val validationFailure = failure as OMSWalletValidationException
            assertEquals(OMSWalletOperation.WalletClaimWallet, validationFailure.operation)
            assertEquals("walletId is required", validationFailure.message)
            assertEquals(0, server.requestCount)
            assertEquals("wallet-active", client.activeWallet?.id)
        }

    private fun createClient(store: InMemorySessionStore): WalletClient =
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
            credentialSigner = TrackingCredentialSigner(nonceValue = "1710000120"),
            credentialRefreshLauncher = IgnoringCredentialRefreshLauncher,
        )

    private fun enqueueEmailAuthStart() {
        server.enqueueJson("""{"verifier":"verifier-123","loginHint":"user@example.com","challenge":"challenge"}""")
    }

    private fun enqueueAccountNotFound() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(404)
                .body("""{"error":"AccountNotFound","code":7301,"msg":"Account not found","status":404}""")
                .build(),
        )
    }

    private fun claimWalletResponseBody(wallet: Wallet): String = WebRpcJson.encodeToString(ClaimWalletResponse(wallet = wallet))

    private companion object {
        val OWNED_ETHEREUM = walletFixture("wallet-owned-evm", "0x0e00000000000000000000000000000000000001")
        val OWNED_SOLANA =
            walletFixture("wallet-owned-sol", "4Nd1mYQbqjVU2aR7cJNPyqW9XjHnBYvWQd7ZxYxvT6uP", type = WalletType.Solana)
        val CLAIMABLE_ETHEREUM = walletFixture("wallet-claim-evm", "0xc1a1000000000000000000000000000000000002")
        val CLAIMABLE_SOLANA =
            walletFixture("wallet-claim-sol", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", type = WalletType.Solana)
    }
}
