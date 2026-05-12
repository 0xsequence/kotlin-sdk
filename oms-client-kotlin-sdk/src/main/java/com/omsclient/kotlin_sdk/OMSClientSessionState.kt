package com.omsclient.kotlin_sdk

import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthResult
import java.time.Instant

/**
 * Auth method that produced the current completed wallet session.
 */
enum class OMSClientSessionLoginType {
    Email,
    GoogleAuth,
    Oidc,
}

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
     * Expiration time for the current completed wallet session, or null when
     * the SDK is signed out.
     */
    val expiresAt: Instant? = null,
    /**
     * Auth method that produced the current completed wallet session.
     */
    val loginType: OMSClientSessionLoginType? = null,
    /**
     * Email associated with the current completed wallet session when the
     * wallet API returns one.
     */
    val sessionEmail: String? = null,
)
