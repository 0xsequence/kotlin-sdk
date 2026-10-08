package technology.polygon.omswallet

import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.wallet.OidcRedirectAuthResult
import technology.polygon.omswallet.wallet.WalletClient

sealed interface OMSWalletSessionAuth {
    val email: String?
}

data class OMSWalletEmailSessionAuth(
    override val email: String,
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
 * Expiry and auth metadata for the active wallet session of a [WalletClient].
 *
 * [WalletClient.session] is non-null exactly when [WalletClient.activeWallet] is.
 * Pending auth and signer bookkeeping are intentionally not exposed. Apps should
 * pass incoming app links to [WalletClient.handleOidcRedirectCallback]; stale or
 * unrelated links are reported through [OidcRedirectAuthResult] instead.
 */
data class OMSWalletSession(
    /** ISO-8601 expiration time of the wallet session. */
    val expiresAt: String,
    val auth: OMSWalletSessionAuth,
)

/**
 * Event delivered when a wallet session expires.
 *
 * [wallet] and [session] describe the expired session, not the current SDK
 * session. Apps can use them to prefill re-authentication UI.
 */
data class OMSWalletSessionExpiredEvent(
    /**
     * Wallet that was active when the session expired, or null when the
     * credential expired while a manual wallet selection was still pending.
     */
    val wallet: Wallet?,
    val session: OMSWalletSession,
    val expiredAt: String,
)
