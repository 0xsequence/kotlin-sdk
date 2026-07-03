package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.internal.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.internal.generated.waas.WebRpcJson
import com.omsclient.kotlin_sdk.models.Wallet
import com.omsclient.kotlin_sdk.utils.OMSClientBase64Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * OIDC provider configuration for authorization-code redirect auth.
 */
data class OidcProviderConfig(
    val issuer: String,
    val clientId: String,
    val authorizationUrl: String,
    val provider: String? = null,
    val providerLabel: String? = null,
    val scopes: List<String> = listOf("openid", "email", "profile"),
    val relayRedirectUri: String? = null,
    val authorizeParams: Map<String, String> = emptyMap(),
    val authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
)

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

internal fun OidcRedirectAuthMode.toWaasAuthMode(): AuthMode =
    when (this) {
        OidcRedirectAuthMode.AuthCode -> AuthMode.AuthCode
        OidcRedirectAuthMode.AuthCodePKCE -> AuthMode.AuthCodePKCE
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
    val state: String,
    val challenge: String,
)

/**
 * Result of handling an incoming OIDC authorization-code redirect callback.
 */
sealed interface OidcRedirectAuthResult {
    data class Completed(
        val wallet: Wallet,
    ) : OidcRedirectAuthResult

    data class WalletSelection(
        val pendingSelection: PendingWalletSelection,
    ) : OidcRedirectAuthResult

    data object NotOidcRedirectCallback : OidcRedirectAuthResult

    data object NoPendingAuth : OidcRedirectAuthResult

    data class Failed(
        val error: Throwable,
    ) : OidcRedirectAuthResult
}

/**
 * Built-in OIDC provider configurations.
 */
object OidcProviders {
    const val defaultGoogleClientId: String =
        "913882656162-7l4ofa0ou2hqo90umlkenhdop1f5inba.apps.googleusercontent.com"
    const val defaultAppleClientId: String =
        "service.oms.polygon.technology"
    const val defaultRelayRedirectUri: String =
        "https://waas-cf-relay-staging.0xsequence.workers.dev/callback"

    fun google(
        clientId: String = defaultGoogleClientId,
        relayRedirectUri: String = defaultRelayRedirectUri,
        scopes: List<String> = listOf("openid", "email", "profile"),
        authorizeParams: Map<String, String> = emptyMap(),
        authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
    ): OidcProviderConfig =
        OidcProviderConfig(
            issuer = "https://accounts.google.com",
            clientId = clientId,
            authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            provider = "google",
            providerLabel = "Google",
            scopes = scopes,
            relayRedirectUri = relayRedirectUri,
            authorizeParams =
                mapOf(
                    "access_type" to "offline",
                    "prompt" to "consent",
                ) + authorizeParams,
            authMode = authMode,
        )

    fun apple(
        clientId: String = defaultAppleClientId,
        relayRedirectUri: String = defaultRelayRedirectUri,
        scopes: List<String> = listOf("openid", "email"),
        authorizeParams: Map<String, String> = emptyMap(),
        authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
    ): OidcProviderConfig =
        OidcProviderConfig(
            issuer = "https://appleid.apple.com",
            clientId = clientId,
            authorizationUrl = "https://appleid.apple.com/auth/authorize",
            provider = "apple",
            providerLabel = "Apple",
            scopes = scopes,
            relayRedirectUri = relayRedirectUri,
            authorizeParams =
                mapOf(
                    "response_mode" to "form_post",
                ) + authorizeParams,
            authMode = authMode,
        )
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
)

internal interface OidcRedirectAuthStore {
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
        provider: OidcProviderConfig,
        redirectUri: String,
        state: String,
        challenge: String,
        usePkce: Boolean,
        loginHint: String?,
        authorizeParams: Map<String, String>,
    ): String {
        val builder = provider.authorizationUrl.toHttpUrl().newBuilder()
        authorizeParams.forEach { (key, value) ->
            builder.setQueryParameter(key, value)
        }
        if (!loginHint.isNullOrBlank()) {
            builder.setQueryParameter("login_hint", loginHint)
        }
        builder
            .setQueryParameter("client_id", provider.clientId)
            .setQueryParameter("redirect_uri", redirectUri)
            .setQueryParameter("response_type", "code")
            .setQueryParameter("state", state)
        if (provider.scopes.isEmpty()) {
            builder.removeAllQueryParameters("scope")
        } else {
            builder.setQueryParameter("scope", provider.scopes.joinToString(" "))
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

    private fun base64UrlEncode(bytes: ByteArray): String = OMSClientBase64Url.encodeNoPadding(bytes)

    private fun base64UrlDecode(value: String): ByteArray = OMSClientBase64Url.decode(value)
}
