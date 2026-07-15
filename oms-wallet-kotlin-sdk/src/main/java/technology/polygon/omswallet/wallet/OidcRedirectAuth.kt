package technology.polygon.omswallet.wallet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import technology.polygon.omswallet.internal.generated.waas.WebRpcJson
import technology.polygon.omswallet.utils.OMSWalletBase64Url
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Caller-owned OIDC provider configuration for authorization-code redirect auth.
 */
data class CustomOidcProviderConfig(
    val issuer: String,
    val clientId: String,
    val authorizationUrl: String,
    val providerRedirectUri: String,
    val provider: String? = null,
    val providerLabel: String? = null,
    val scopes: List<String> = emptyList(),
    val authorizeParams: Map<String, String> = emptyMap(),
    val authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
)

/** Opaque OIDC provider value whose OAuth callback is owned by the OMS relay. */
sealed interface OmsRelayOidcProvider

private data object GoogleOmsRelayOidcProvider : OmsRelayOidcProvider

private data object AppleOmsRelayOidcProvider : OmsRelayOidcProvider

/** SDK-owned OMS relay provider values. */
object OmsRelayOidcProviders {
    val google: OmsRelayOidcProvider = GoogleOmsRelayOidcProvider
    val apple: OmsRelayOidcProvider = AppleOmsRelayOidcProvider
}

/**
 * WaaS redirect auth-code mode supported by OIDC redirect providers.
 */
@Serializable
enum class OidcRedirectAuthMode {
    @SerialName("auth-code")
    AuthCode,

    @SerialName("auth-code-pkce")
    AuthCodePKCE,
}

internal val OidcRedirectAuthMode.usesPkce: Boolean
    get() = this == OidcRedirectAuthMode.AuthCodePKCE

/**
 * Result returned after starting an OIDC authorization-code redirect flow.
 *
 * Open [authorizationUrl] in a browser or Custom Tabs, then pass the final app
 * callback URL to `handleOidcRedirectCallback`.
 */
data class StartOidcRedirectAuthResult(
    val authorizationUrl: String,
)

/**
 * Result of handling an incoming OIDC authorization-code redirect callback.
 */
sealed interface OidcRedirectAuthResult {
    data class Completed(
        val result: CompleteAuthResult,
    ) : OidcRedirectAuthResult

    data object NotOidcRedirectCallback : OidcRedirectAuthResult

    data object NoPendingAuth : OidcRedirectAuthResult
}

@Serializable
internal data class PendingOidcRedirectAuth(
    val verifier: String,
    val challenge: String,
    val nonce: String,
    val authMode: OidcRedirectAuthMode,
    val redirectUri: String,
    val issuer: String,
    val provider: String? = null,
    val providerLabel: String? = null,
    val projectId: String,
    val walletType: String,
    val walletSelection: WalletSelectionBehavior?,
    val sessionLifetimeSeconds: Long?,
    val signerAddress: String,
    val signerKeyType: WalletSigningAlgorithm,
    val consumed: Boolean = false,
) {
    val flowIdentifier: String
        get() = "$nonce:$verifier"
}

internal interface OidcRedirectAuthStore {
    @get:JvmSynthetic
    val synchronizationKey: Any
        get() = this

    fun load(): PendingOidcRedirectAuth?

    fun save(pending: PendingOidcRedirectAuth)

    fun clear()
}

internal data class OidcCallbackParams(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
) {
    val hasOidcResponse: Boolean
        get() = code != null || state != null || error != null || errorDescription != null
}

@Serializable
private data class OidcStatePayload(
    val nonce: String,
    val scope: String,
    @SerialName("redirect_uri")
    val redirectUri: String? = null,
)

internal object OidcRedirectAuth {
    fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    fun encodeState(
        nonce: String,
        scope: String,
        redirectUri: String? = null,
    ): String =
        base64UrlEncode(
            WebRpcJson
                .encodeToString(
                    OidcStatePayload(
                        nonce = nonce,
                        scope = scope,
                        redirectUri = redirectUri,
                    ),
                ).toByteArray(Charsets.UTF_8),
        )

    fun buildAuthorizationUrl(
        authorizationUrl: String,
        clientId: String,
        scopes: List<String>,
        redirectUri: String,
        state: String,
        challenge: String,
        usePkce: Boolean,
        loginHint: String?,
        authorizeParams: Map<String, String>,
    ): String {
        val builder = authorizationUrl.toHttpUrl().newBuilder()
        authorizeParams.forEach { (key, value) ->
            builder.setQueryParameter(key, value)
        }
        if (!loginHint.isNullOrBlank()) {
            builder.setQueryParameter("login_hint", loginHint)
        }
        builder
            .setQueryParameter("client_id", clientId)
            .setQueryParameter("redirect_uri", redirectUri)
            .setQueryParameter("response_type", "code")
            .setQueryParameter("state", state)
        if (scopes.isEmpty()) {
            builder.removeAllQueryParameters("scope")
        } else {
            builder.setQueryParameter("scope", scopes.joinToString(" "))
        }
        if (usePkce) {
            builder
                .setQueryParameter("code_challenge", challenge)
                .setQueryParameter("code_challenge_method", "S256")
        } else {
            builder
                .removeAllQueryParameters("code_challenge")
                .removeAllQueryParameters("code_challenge_method")
        }
        return builder
            .build()
            .toString()
    }

    fun parseCallbackUrl(callbackUrl: String): OidcCallbackParams {
        val query =
            callbackUrl
                .substringAfter('?', missingDelimiterValue = "")
                .substringBefore('#')
        val params = parseQuery(query)
        return OidcCallbackParams(
            code = params["code"],
            state = params["state"],
            error = params["error"],
            errorDescription = params["error_description"],
        )
    }

    fun validateState(
        encodedState: String,
        pending: PendingOidcRedirectAuth,
    ) {
        val state = decodeState(encodedState)
        require(state.nonce == pending.nonce) { "OIDC state nonce mismatch" }
        require(state.scope == pending.projectId) { "OIDC state scope mismatch" }
        require(state.redirectUri == null || state.redirectUri == pending.redirectUri) {
            "OIDC state redirect_uri mismatch"
        }
    }

    fun matchesRedirectUri(
        callbackUrl: String,
        redirectUri: String,
    ): Boolean =
        runCatching {
            val callback = URI(callbackUrl)
            val expected = URI(redirectUri)
            callback.scheme.equals(expected.scheme, ignoreCase = true) &&
                sameAuthority(callback.rawAuthority, expected.rawAuthority) &&
                callback.rawPath == expected.rawPath
        }.getOrDefault(false)

    private fun sameAuthority(
        callbackAuthority: String?,
        expectedAuthority: String?,
    ): Boolean =
        when {
            callbackAuthority == null || expectedAuthority == null -> callbackAuthority == expectedAuthority
            else -> callbackAuthority.equals(expectedAuthority, ignoreCase = true)
        }

    private fun decodeState(encodedState: String): OidcStatePayload {
        val decoded = String(base64UrlDecode(encodedState), Charsets.UTF_8)
        return WebRpcJson.decodeFromString(decoded)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) {
            return emptyMap()
        }
        return query
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=').urlDecode()
                val value = pair.substringAfter('=', missingDelimiterValue = "").urlDecode()
                key to value
            }
    }

    private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

    private fun base64UrlEncode(bytes: ByteArray): String = OMSWalletBase64Url.encodeNoPadding(bytes)

    private fun base64UrlDecode(value: String): ByteArray = OMSWalletBase64Url.decode(value)
}
