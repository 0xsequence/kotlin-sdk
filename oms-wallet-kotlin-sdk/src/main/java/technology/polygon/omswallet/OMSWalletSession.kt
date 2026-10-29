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
    /**
     * ISO-8601 current expiration time of the wallet session. WaaS extends it with use up to the
     * requested lifetime, and the SDK refreshes it, so it can move while the session is active.
     */
    val expiresAt: String,
    val auth: OMSWalletSessionAuth,
)

/**
 * A session that expired, as reported by [OMSWalletSessionChangeEvent.Expired]. Apps can use it to
 * prefill re-authentication UI.
 */
data class OMSWalletExpiredSession(
    /**
     * Wallet that was active when the session expired, or null when the
     * credential expired while a manual wallet selection was still pending.
     */
    val wallet: Wallet?,
    val session: OMSWalletSession,
    /**
     * ISO-8601 time the session ended: its expiry, or the time the SDK found it ended when that was
     * earlier.
     */
    val expiredAt: String,
)

/**
 * A change of [WalletClient.activeWallet] or [WalletClient.session], with the state right after it.
 * See [WalletClient.onSessionChange].
 */
sealed interface OMSWalletSessionChangeEvent {
    /** The active wallet after the change; null for [SignedOut] and [Expired]. */
    val activeWallet: Wallet?

    /** The session after the change; non-null exactly when [activeWallet] is. */
    val session: OMSWalletSession?

    /** A sign-in made a wallet active. */
    data class SignedIn(
        override val activeWallet: Wallet,
        override val session: OMSWalletSession,
    ) : OMSWalletSessionChangeEvent

    /** Another wallet of the same sign-in became active. */
    data class WalletChanged(
        override val activeWallet: Wallet,
        override val session: OMSWalletSession,
    ) : OMSWalletSessionChangeEvent

    /** [OMSWalletSession.expiresAt] moved. Safe to ignore unless you show the expiry. */
    data class ExpiryUpdated(
        override val activeWallet: Wallet,
        override val session: OMSWalletSession,
    ) : OMSWalletSessionChangeEvent

    /**
     * There is no session any more and it did not expire: [WalletClient.signOut], a sign-in started
     * while signed in, or another process signing in. Not necessarily the user's choice.
     */
    data object SignedOut : OMSWalletSessionChangeEvent {
        override val activeWallet: Wallet? get() = null
        override val session: OMSWalletSession? get() = null
    }

    /** The session expired, or the wallet API no longer accepts it. */
    data class Expired(
        val expiredSession: OMSWalletExpiredSession,
    ) : OMSWalletSessionChangeEvent {
        override val activeWallet: Wallet? get() = null
        override val session: OMSWalletSession? get() = null
    }
}
