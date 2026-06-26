package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.internal.generated.waas.CompleteAuthResponse
import com.omsclient.kotlin_sdk.internal.generated.waas.CredentialInfo
import com.omsclient.kotlin_sdk.internal.generated.waas.Identity
import com.omsclient.kotlin_sdk.internal.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.internal.generated.waas.ListWalletsResponse
import com.omsclient.kotlin_sdk.internal.generated.waas.Page
import com.omsclient.kotlin_sdk.internal.generated.waas.Wallet
import com.omsclient.kotlin_sdk.internal.generated.waas.WalletType
import com.omsclient.kotlin_sdk.internal.generated.waas.WebRpcJson
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.storage.OMSClientSessionMetadataStore
import com.omsclient.kotlin_sdk.utils.OMSClientBase64Url
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import java.net.URI
import java.net.URLDecoder

internal val TEST_CREDENTIAL_ID: String = "0x04" + "11".repeat(64)
internal val TEST_SIGNATURE: String = "0x" + "22".repeat(64)

internal fun walletFixture(
    walletId: String,
    address: String,
    reference: String? = null,
    type: WalletType = WalletType.Ethereum,
): Wallet =
    Wallet(
        id = walletId,
        type = type,
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
    val header = OMSClientBase64Url.encodeNoPadding("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload =
        OMSClientBase64Url.encodeNoPadding(
            """
            {"iss":"https://accounts.google.com","aud":"demo-web-client-id","sub":"google-sub-123","email":"user@example.com","exp":$exp}
            """.trimIndent().toByteArray(),
        )
    return "$header.$payload.signature"
}

internal fun activeSessionSnapshot(): OMSClientSessionSnapshot =
    OMSClientSessionSnapshot(
        walletId = "wallet-active",
        walletAddress = "0xactive",
        signerAddress = TEST_CREDENTIAL_ID,
        signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
    )

internal fun pendingOidcRedirectAuthFixture(): PendingOidcRedirectAuth =
    PendingOidcRedirectAuth(
        verifier = "stale-oidc-verifier",
        challenge = "stale-oidc-challenge",
        nonce = "stale-oidc-nonce",
        redirectUri = "omsclientkotlindemo://auth/callback",
        issuer = "https://issuer.example",
        projectId = "test-project-id",
        walletType = WalletType.Ethereum.wireValue,
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
    WalletRequestSigner.buildWalletSignatureHeader(
        signingAlgorithm = signingAlgorithm,
        scope = scope,
        credentialId = credentialId,
        nonce = nonce,
        signature = signature,
    )

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
    var snapshot: OMSClientSessionSnapshot? = null,
) : OMSClientSessionMetadataStore {
    var saveCalls: Int = 0
        private set

    override fun load(): OMSClientSessionSnapshot? = snapshot

    override fun save(snapshot: OMSClientSessionSnapshot) {
        saveCalls += 1
        this.snapshot = snapshot
    }

    override fun clear() {
        snapshot = null
    }
}

internal class FailingSaveSessionStore : OMSClientSessionMetadataStore {
    var clearCalls: Int = 0
        private set

    override fun load(): OMSClientSessionSnapshot? = null

    override fun save(snapshot: OMSClientSessionSnapshot): Unit = throw IllegalStateException("save failed")

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

    override suspend fun credentialId(): String {
        available = true
        return credentialIdValue
    }

    override suspend fun nextNonce(): String = nonceValue

    override suspend fun sign(preimage: String): String {
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

internal class MockWebCryptoCredentialSigner(
    private var available: Boolean = true,
) : CredentialSigner {
    override val signingAlgorithm: WalletSigningAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256
    val credentialIdValue: String = TEST_CREDENTIAL_ID
    val signatureValue: String = TEST_SIGNATURE
    var signCalls: Int = 0
        private set

    override suspend fun credentialId(): String {
        available = true
        return credentialIdValue
    }

    override suspend fun nextNonce(): String = "42"

    override suspend fun sign(preimage: String): String {
        signCalls += 1
        assertTrue(preimage.contains("nonce: 42"))
        return signatureValue
    }

    override fun hasCredential(): Boolean = available

    override fun clear() {
        available = false
    }
}
