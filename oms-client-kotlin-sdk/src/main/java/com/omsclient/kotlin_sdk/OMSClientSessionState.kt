package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthResult

sealed interface OMSClientSessionAuth {
    val email: String?
}

data class OMSClientEmailSessionAuth(
    override val email: String?,
) : OMSClientSessionAuth

enum class OMSClientOidcSessionAuthFlow {
    Redirect,
    IdToken,
}

data class OMSClientOidcSessionAuth(
    val flow: OMSClientOidcSessionAuthFlow,
    val issuer: String,
    val provider: String?,
    val providerLabel: String?,
    override val email: String?,
) : OMSClientSessionAuth

/**
 * Current durable wallet-session state for an [OMSClient].
 *
 * This snapshot intentionally does not expose pending auth or signer
 * bookkeeping. Apps should pass incoming app links to
 * [OMSClient.handleOidcRedirectCallback]; stale or unrelated links are reported
 * through [OidcRedirectAuthResult] instead of session state.
 */
data class OMSClientSessionState(
    /**
     * Address of the selected wallet in a completed session, or null when the
     * SDK is signed out.
     */
    val walletAddress: String?,
    /**
     * ISO-8601 expiration time for the current completed wallet session, or null
     * when the SDK is signed out.
     */
    val expiresAt: String? = null,
    val auth: OMSClientSessionAuth? = null,
)

/**
 * Event delivered when a wallet session expires.
 *
 * [session] is the expired session snapshot, not the current active SDK
 * session. Apps can use it to prefill re-authentication UI.
 */
data class OMSClientSessionExpiredEvent(
    val session: OMSClientSessionState,
    val expiredAt: String,
)
