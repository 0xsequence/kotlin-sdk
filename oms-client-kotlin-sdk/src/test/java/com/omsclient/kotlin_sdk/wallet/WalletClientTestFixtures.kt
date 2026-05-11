package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthResponse
import com.omsclient.kotlin_sdk.generated.waas.CredentialInfo
import com.omsclient.kotlin_sdk.generated.waas.Identity
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.KeyType
import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.generated.waas.WebRpcJson
import com.omsclient.kotlin_sdk.session.OMSClientSessionSnapshot
import com.omsclient.kotlin_sdk.storage.OMSClientSecureSessionStore
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.web3j.utils.Numeric
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal const val FIXED_PRIVATE_KEY_HEX: String =
    "0x1111111111111111111111111111111111111111111111111111111111111111"

internal fun walletFixture(
    walletId: String,
    address: String,
    reference: String? = null,
    type: WalletType = WalletType.Ethereum,
): Wallet = Wallet(
    id = walletId,
    type = type,
    address = address,
    reference = reference,
)

internal fun identityFixture(
    type: IdentityType,
    iss: String? = null,
    sub: String = "sub-123",
): Identity = Identity(
    type = type,
    iss = iss,
    sub = sub,
)

internal fun completeAuthResponseBody(
    wallets: List<Wallet>,
    identity: Identity = identityFixture(IdentityType.Email),
    email: String? = "user@example.com",
): String = WebRpcJson.encodeToString(
    CompleteAuthResponse(
        identity = identity,
        wallets = wallets,
        email = email,
        credential = credentialFixture(),
    ),
)

internal fun credentialFixture(): CredentialInfo = CredentialInfo(
    credentialId = "credential-123",
    expiresAt = "2026-01-01T00:00:00Z",
    isCaller = true,
)

internal fun walletResponseBody(
    walletId: String,
    address: String,
    reference: String? = null,
    type: WalletType = WalletType.Ethereum,
): String = """{"wallet":${WebRpcJson.encodeToString(walletFixture(walletId, address, reference, type))}}"""

internal fun fakeJwt(exp: Long): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload = encoder.encodeToString(
        """
        {"iss":"https://accounts.google.com","aud":"demo-web-client-id","sub":"google-sub-123","email":"user@example.com","exp":$exp}
        """.trimIndent().toByteArray()
    )
    return "$header.$payload.signature"
}

internal fun fixedPrivateKeyBytes(): ByteArray =
    Numeric.hexStringToByteArray(FIXED_PRIVATE_KEY_HEX)

internal fun activeSessionSnapshot(): OMSClientSessionSnapshot =
    OMSClientSessionSnapshot(
        walletId = "wallet-active",
        walletAddress = "0xactive",
        signerAddress = WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX),
        signerKeyType = KeyType.Ethereum_Secp256k1,
    )

internal fun uriOriginAndPath(url: String): String {
    val uri = URI(url)
    return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}"
}

internal fun queryParams(url: String): Map<String, String> {
    val query = URI(url).rawQuery ?: return emptyMap()
    return query.split('&')
        .filter { it.isNotBlank() }
        .associate { pair ->
            pair.substringBefore('=').urlDecode() to
                pair.substringAfter('=', missingDelimiterValue = "").urlDecode()
        }
}

private fun String.urlDecode(): String =
    URLDecoder.decode(this, Charsets.UTF_8.name())

internal class InMemorySessionStore(
    var snapshot: OMSClientSessionSnapshot? = null,
    var privateKeyHex: String? = null,
) : OMSClientSecureSessionStore {
    var saveCalls: Int = 0
        private set
    var savedPrivateKeyHex: String? = null
        private set

    override fun load(): OMSClientSessionSnapshot? = snapshot

    override fun save(snapshot: OMSClientSessionSnapshot) {
        saveCalls += 1
        this.snapshot = snapshot
    }

    override fun clear() {
        snapshot = null
        privateKeyHex = null
    }
}

internal class FailingSaveSessionStore : OMSClientSecureSessionStore {
    var clearCalls: Int = 0
        private set

    override fun load(): OMSClientSessionSnapshot? = null

    override fun save(snapshot: OMSClientSessionSnapshot) {
        throw IllegalStateException("save failed")
    }

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

internal class TrackingCredentialSigner : CredentialSigner {
    override val keyType: KeyType = KeyType.Ethereum_Secp256k1
    var signCalls: Int = 0
        private set

    override suspend fun credentialId(): String =
        WalletRequestSigner.walletAddressFromPrivateKeyHex(FIXED_PRIVATE_KEY_HEX)

    override suspend fun nextNonce(): String = "1710000107"

    override suspend fun sign(preimage: String): String {
        signCalls += 1
        return WalletRequestSigner.signWalletRequestPreimage(FIXED_PRIVATE_KEY_HEX, preimage)
    }

    override fun hasCredential(): Boolean = true

    override fun clear() = Unit
}

internal class MockWebCryptoCredentialSigner(
    private var available: Boolean = true,
) : CredentialSigner {
    override val keyType: KeyType = KeyType.WebCrypto_Secp256r1
    val credentialIdValue: String = "0x04" + "11".repeat(64)
    val signatureValue: String = "0x" + "22".repeat(64)
    var signCalls: Int = 0
        private set

    override suspend fun credentialId(): String = credentialIdValue

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
