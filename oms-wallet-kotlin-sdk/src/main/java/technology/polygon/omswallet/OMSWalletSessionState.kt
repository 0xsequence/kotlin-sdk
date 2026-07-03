package technology.polygon.omswallet

import technology.polygon.omswallet.wallet.OidcRedirectAuthResult
import technology.polygon.omswallet.wallet.WalletClient

sealed interface OMSWalletSessionAuth {
    val email: String?
}

data class OMSWalletEmailSessionAuth(
    override val email: String?,
) : OMSWalletSessionAuth

enum class OMSWalletOidcSessionAuthFlow {
    Redirect,
    IdToken,
}

data class OMSWalletOidcSessionAuth(
    val flow: OMSWalletOidcSessionAuthFlow,
    val issuer: String,
    val provider: String?,
    val providerLabel: String?,
    override val email: String?,
) : OMSWalletSessionAuth

/**
 * Current durable wallet-session state for an [OMSWallet].
 *
 * This snapshot intentionally does not expose pending auth or signer
 * bookkeeping. Apps should pass incoming app links to
 * [WalletClient.handleOidcRedirectCallback]; stale or unrelated links are reported
 * through [OidcRedirectAuthResult] instead of session state.
 */
data class OMSWalletSessionState(
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
    val auth: OMSWalletSessionAuth? = null,
)

/**
 * Event delivered when a wallet session expires.
 *
 * [session] is the expired session snapshot, not the current active SDK
 * session. Apps can use it to prefill re-authentication UI.
 */
data class OMSWalletSessionExpiredEvent(
    val session: OMSWalletSessionState,
    val expiredAt: String,
)
