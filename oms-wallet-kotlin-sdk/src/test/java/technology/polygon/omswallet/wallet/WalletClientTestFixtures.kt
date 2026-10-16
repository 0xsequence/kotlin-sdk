package technology.polygon.omswallet.wallet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertTrue
import technology.polygon.omswallet.internal.generated.waas.CompleteAuthResponse
import technology.polygon.omswallet.internal.generated.waas.CredentialInfo
import technology.polygon.omswallet.internal.generated.waas.CredentialType
import technology.polygon.omswallet.internal.generated.waas.Identity
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.KeyOrigin
import technology.polygon.omswallet.internal.generated.waas.ListWalletsResponse
import technology.polygon.omswallet.internal.generated.waas.NetworkFamily
import technology.polygon.omswallet.internal.generated.waas.Page
import technology.polygon.omswallet.internal.generated.waas.Wallet
import technology.polygon.omswallet.internal.generated.waas.WalletType
import technology.polygon.omswallet.internal.generated.waas.WebRpcJson
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.storage.OMSWalletSessionMetadataStore
import technology.polygon.omswallet.utils.OMSWalletBase64Url
import java.net.URI
import java.net.URLDecoder

internal val TEST_CREDENTIAL_ID: String = "0x04" + "11".repeat(64)
internal val TEST_SIGNATURE: String = "0x" + "22".repeat(64)

internal fun testEnvironment(
    walletApiUrl: String = "https://wallet.example.com/v1/Waas",
    indexerGatewayUrl: String = "https://indexer.example.com/v1/IndexerGateway/",
): OMSWalletEnvironment =
    OMSWalletEnvironment(
        walletApiUrl = walletApiUrl,
        indexerGatewayUrl = indexerGatewayUrl,
    )

internal fun walletFixture(
    walletId: String,
    address: String,
    reference: String? = null,
    type: WalletType = WalletType.Ethereum,
): Wallet =
    Wallet(
        id = walletId,
        type = type,
        networkFamily =
            when (type) {
                WalletType.Ethereum -> NetworkFamily.EVM
                WalletType.Solana -> NetworkFamily.Solana
                WalletType.Tron -> NetworkFamily.Tron
                WalletType.UNKNOWN_DEFAULT -> NetworkFamily.UNKNOWN_DEFAULT
            },
        keyOrigin = KeyOrigin.Enclave,
        address = address,
        reference = reference,
    )

internal fun identityFixture(
    type: IdentityType,
    iss: String? = null,
    sub: String = "sub-123",
): Identity =
    Identity(
        type = type,
        iss = iss,
        sub = sub,
    )

internal fun completeAuthResponseBody(
    wallets: List<Wallet>,
    identity: Identity = identityFixture(IdentityType.Email),
    email: String? = "user@example.com",
    page: Page? = null,
): String =
    WebRpcJson.encodeToString(
        CompleteAuthResponse(
            identity = identity,
            wallets = wallets,
            page = page,
            email = email,
            credential = credentialFixture(),
        ),
    )

internal fun credentialFixture(): CredentialInfo =
    CredentialInfo(
        credentialId = "credential-123",
        type = CredentialType.Direct,
        expiresAt = "2099-01-01T00:00:00Z",
        isCaller = true,
    )

internal fun walletResponseBody(
    walletId: String,
    address: String,
    reference: String? = null,
    type: WalletType = WalletType.Ethereum,
): String = """{"wallet":${WebRpcJson.encodeToString(walletFixture(walletId, address, reference, type))}}"""

internal fun listWalletsResponseBody(
    wallets: List<Wallet>,
    page: Page? = null,
): String =
    WebRpcJson.encodeToString(
        ListWalletsResponse(
            wallets = wallets,
            page = page,
        ),
    )

internal fun fakeJwt(exp: Long): String {
    val header = OMSWalletBase64Url.encodeNoPadding("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload =
        OMSWalletBase64Url.encodeNoPadding(
            """
            {"iss":"https://accounts.google.com","aud":"demo-web-client-id","sub":"google-sub-123","email":"user@example.com","exp":$exp}
            """.trimIndent().toByteArray(),
        )
    return "$header.$payload.signature"
}

internal const val TEST_SESSION_EXPIRES_AT: String = "2099-01-01T00:00:00Z"

/** Public SDK wallet model for session snapshots; the stored type drives wallet-family checks. */
internal fun testWallet(
    id: String,
    address: String,
    type: technology.polygon.omswallet.models.WalletType = technology.polygon.omswallet.models.WalletType.Ethereum,
    reference: String? = null,
    keyOrigin: technology.polygon.omswallet.models.WalletKeyOrigin = technology.polygon.omswallet.models.WalletKeyOrigin.Enclave,
): technology.polygon.omswallet.models.Wallet =
    technology.polygon.omswallet.models.Wallet(
        id = id,
        type = type,
        address = address,
        reference = reference,
        keyOrigin = keyOrigin,
    )

internal fun activeSessionSnapshot(): OMSWalletSessionSnapshot =
    OMSWalletSessionSnapshot(
        wallet = testWallet("wallet-active", "0xactive"),
        expiresAt = TEST_SESSION_EXPIRES_AT,
        signerAddress = TEST_CREDENTIAL_ID,
        signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
        auth = emailSessionAuth(),
    )

internal fun pendingOidcRedirectAuthFixture(): PendingOidcRedirectAuth =
    PendingOidcRedirectAuth(
        verifier = "stale-oidc-verifier",
        challenge = "stale-oidc-challenge",
        nonce = "stale-oidc-nonce",
        authMode = OidcAuthMode.AuthCodePKCE,
        redirectUri = "omsclientkotlindemo://auth/callback",
        issuer = "https://issuer.example",
        projectId = "test-project-id",
        walletType = WalletType.Ethereum.wireValue,
        walletSelection = null,
        sessionLifetimeSeconds = null,
        signerAddress = TEST_CREDENTIAL_ID,
        signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
    )

internal fun expectedWalletSignatureHeader(
    nonce: String,
    scope: String = "test-project-id",
    credentialId: String = TEST_CREDENTIAL_ID,
    signature: String = TEST_SIGNATURE,
    signingAlgorithm: WalletSigningAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256,
): String =
    // Spelled out literally so the expected wire format is not derived from the production builder.
    "OMS-Wallet-Signature: alg=\"${signingAlgorithm.wireValue}\", scope=\"$scope\", cred=\"$credentialId\"," +
        " nonce=$nonce, sig=\"$signature\""

internal fun uriOriginAndPath(url: String): String {
    val uri = URI(url)
    return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}"
}

internal fun queryParams(url: String): Map<String, String> {
    val query = URI(url).rawQuery ?: return emptyMap()
    return query
        .split('&')
        .filter { it.isNotBlank() }
        .associate { pair ->
            pair.substringBefore('=').urlDecode() to
                pair.substringAfter('=', missingDelimiterValue = "").urlDecode()
        }
}

private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

internal class InMemorySessionStore(
    var snapshot: OMSWalletSessionSnapshot? = null,
) : OMSWalletSessionMetadataStore {
    var saveCalls: Int = 0
        private set

    override fun load(): OMSWalletSessionSnapshot? = snapshot

    override fun save(snapshot: OMSWalletSessionSnapshot) {
        saveCalls += 1
        this.snapshot = snapshot
    }

    override fun clear() {
        snapshot = null
    }
}

internal class FailingSaveSessionStore : OMSWalletSessionMetadataStore {
    var clearCalls: Int = 0
        private set

    override fun load(): OMSWalletSessionSnapshot? = null

    override fun save(snapshot: OMSWalletSessionSnapshot): Unit = throw IllegalStateException("save failed")

    override fun clear() {
        clearCalls += 1
    }
}

internal class InMemoryOidcRedirectAuthStore(
    var pending: PendingOidcRedirectAuth? = null,
) : OidcRedirectAuthStore {
    var clearCalls: Int = 0
        private set

    override fun load(): PendingOidcRedirectAuth? = pending

    override fun save(pending: PendingOidcRedirectAuth) {
        this.pending = pending
    }

    override fun clear() {
        clearCalls += 1
        pending = null
    }
}

internal open class TrackingCredentialSigner(
    private var available: Boolean = true,
    val credentialIdValue: String = TEST_CREDENTIAL_ID,
    val nonceValue: String = "1710000107",
    val signatureValue: String = TEST_SIGNATURE,
    override val signingAlgorithm: WalletSigningAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256,
) : CredentialSigner {
    var signCalls: Int = 0
        private set

    override fun credentialId(): String {
        available = true
        return credentialIdValue
    }

    override fun existingCredentialId(): String? = credentialIdValue.takeIf { available }

    override fun nextNonce(): String = nonceValue

    override fun sign(preimage: String): String {
        signCalls += 1
        assertTrue(preimage.contains("nonce: $nonceValue"))
        return signatureValue
    }

    override fun hasCredential(): Boolean = available

    open override fun clear() {
        available = false
    }
}

internal class ThrowingClearCredentialSigner : TrackingCredentialSigner() {
    var clearAttempts: Int = 0
        private set

    override fun clear() {
        clearAttempts += 1
        throw IllegalStateException("clear failed")
    }
}

internal class RecordingSessionExpiryScheduler : SessionExpiryScheduler {
    data class ScheduledTask(
        val delayMillis: Long,
        val action: () -> Unit,
    )

    val scheduledTasks = mutableListOf<ScheduledTask>()
    var cancelCalls: Int = 0
        private set

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): SessionExpiryTask {
        val scheduledTask = ScheduledTask(delayMillis, action)
        scheduledTasks += scheduledTask
        return SessionExpiryTask {
            cancelCalls += 1
            scheduledTasks -= scheduledTask
        }
    }
}

internal class RecordingSessionExpiryDispatcher : SessionExpiryDispatcher {
    val actions = mutableListOf<() -> Unit>()

    override fun dispatch(action: () -> Unit) {
        actions += action
    }

    fun runNext() {
        actions.removeAt(0).invoke()
    }
}

internal fun MockWebServer.enqueueJson(body: String) {
    enqueue(
        MockResponse
            .Builder()
            .code(200)
            .body(body)
            .build(),
    )
}

internal fun RecordedRequest.utf8Body(): String = requireNotNull(body).utf8()

internal fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(utf8Body()).jsonObject

/** A [WalletClient] restored from a completed email session for [wallet]. */
internal fun restoredTestWalletClient(
    environment: OMSWalletEnvironment,
    wallet: technology.polygon.omswallet.models.Wallet,
    nonceValue: String,
): WalletClient {
    val client =
        WalletClient.create(
            publishableKey = "test-publishable-key",
            projectId = "test-project-id",
            environment = environment,
            transport = OMSWalletHttpClient(),
            sessionStore =
                InMemorySessionStore(
                    snapshot =
                        OMSWalletSessionSnapshot(
                            wallet = wallet,
                            expiresAt = TEST_SESSION_EXPIRES_AT,
                            signerAddress = TEST_CREDENTIAL_ID,
                            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                            auth = emailSessionAuth(),
                        ),
                ),
            credentialSigner = TrackingCredentialSigner(nonceValue = nonceValue),
        )
    assertTrue(client.restorePersistedSession())
    return client
}
