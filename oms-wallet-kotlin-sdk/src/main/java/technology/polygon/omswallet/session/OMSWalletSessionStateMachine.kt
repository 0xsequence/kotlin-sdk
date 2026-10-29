package technology.polygon.omswallet.session

import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm

internal data class OMSWalletSessionSnapshot(
    val challenge: String? = null,
    val verifier: String? = null,
    /** Selected wallet; set only for a completed (active) wallet session. */
    val wallet: Wallet? = null,
    val signerAddress: String? = null,
    val signerKeyType: WalletSigningAlgorithm? = null,
    val expiresAt: String? = null,
    val auth: OMSWalletSessionAuth? = null,
    val pendingWalletSelectionId: Long? = null,
    val pendingWalletType: WalletType? = null,
    /** WaaS's idle window and lifetime ceiling for this credential; null when unknown. */
    val expiryBounds: OMSWalletSessionExpiryBounds? = null,
) {
    val walletId: String? get() = wallet?.id

    val walletAddress: String? get() = wallet?.address
}

/**
 * The session's lifetime cap, derived from its sign-in: WaaS never moves the credential's expiry past
 * [ceilingEpochMillis].
 */
internal data class OMSWalletSessionExpiryBounds(
    val ceilingEpochMillis: Long,
)

/** The session was signed out, replaced, or switched to another wallet while an operation ran. */
internal class SessionRevisionChangedException : IllegalStateException("Wallet session changed before operation completed")

internal data class OMSWalletPendingAuthSnapshot(
    val challenge: String,
    val verifier: String,
)

/**
 * Thread-safe auth/wallet-session state machine backing a wallet client scope.
 *
 * This is internal bookkeeping, distinct from the public
 * [technology.polygon.omswallet.OMSWalletSession] metadata snapshot.
 */
internal class OMSWalletSessionStateMachine {
    private sealed interface SessionState {
        fun snapshot(): OMSWalletSessionSnapshot?

        data object NoSession : SessionState {
            override fun snapshot(): OMSWalletSessionSnapshot? = null
        }

        data class PendingAuth(
            val challenge: String,
            val verifier: String,
            val signerAddress: String,
            val signerKeyType: WalletSigningAlgorithm?,
        ) : SessionState {
            override fun snapshot(): OMSWalletSessionSnapshot =
                OMSWalletSessionSnapshot(
                    challenge = challenge,
                    verifier = verifier,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                )
        }

        data class AwaitingWalletSelection(
            val signerAddress: String,
            val signerKeyType: WalletSigningAlgorithm?,
            val expiresAt: String,
            val auth: OMSWalletSessionAuth,
            val pendingWalletSelectionId: Long?,
            val walletType: WalletType?,
            val expiryBounds: OMSWalletSessionExpiryBounds?,
        ) : SessionState {
            override fun snapshot(): OMSWalletSessionSnapshot =
                OMSWalletSessionSnapshot(
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    expiresAt = expiresAt,
                    auth = auth,
                    pendingWalletSelectionId = pendingWalletSelectionId,
                    pendingWalletType = walletType,
                    expiryBounds = expiryBounds,
                )
        }

        data class ActiveSession(
            val wallet: Wallet,
            val signerAddress: String?,
            val signerKeyType: WalletSigningAlgorithm?,
            val expiresAt: String,
            val auth: OMSWalletSessionAuth,
            val expiryBounds: OMSWalletSessionExpiryBounds?,
        ) : SessionState {
            override fun snapshot(): OMSWalletSessionSnapshot =
                OMSWalletSessionSnapshot(
                    wallet = wallet,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    expiresAt = expiresAt,
                    auth = auth,
                    expiryBounds = expiryBounds,
                )
        }
    }

    private val lock = Any()
    private var state: SessionState = SessionState.NoSession
    private var nextPendingWalletSelectionId: Long = 1L
    private var revision: Long = 0L

    fun snapshot(): OMSWalletSessionSnapshot? = synchronized(lock) { state.snapshot() }

    fun revision(): Long = synchronized(lock) { revision }

    fun requireRevision(expectedRevision: Long) {
        synchronized(lock) {
            requireCurrentRevision(expectedRevision)
        }
    }

    fun restore(
        snapshot: OMSWalletSessionSnapshot,
        requiredRevision: Long? = null,
    ): Long =
        synchronized(lock) {
            requiredRevision?.let(::requireCurrentRevision)
            replaceState(snapshot.toSessionState())
            revision
        }

    fun clear(requiredRevision: Long? = null): Boolean =
        synchronized(lock) {
            if (requiredRevision != null && revision != requiredRevision) {
                return@synchronized false
            }
            replaceState(SessionState.NoSession)
            true
        }

    fun replaceForPendingAuth(
        challenge: String,
        verifier: String,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        requiredRevision: Long? = null,
    ): Long =
        synchronized(lock) {
            requiredRevision?.let(::requireCurrentRevision)
            replaceState(
                SessionState.PendingAuth(
                    challenge = challenge,
                    verifier = verifier,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                ),
            )
            revision
        }

    fun markAuthVerified(
        expiresAt: String,
        auth: OMSWalletSessionAuth,
        walletType: WalletType,
        expiryBounds: OMSWalletSessionExpiryBounds?,
        requiredRevision: Long? = null,
    ): Pair<Long, Long> {
        synchronized(lock) {
            requiredRevision?.let(::requireCurrentRevision)
            val current =
                when (val current = state) {
                    is SessionState.PendingAuth -> current
                    else -> error("No active pending auth challenge")
                }
            val pendingWalletSelectionId = nextPendingWalletSelectionId++
            replaceState(
                SessionState.AwaitingWalletSelection(
                    signerAddress = current.signerAddress,
                    signerKeyType = current.signerKeyType,
                    expiresAt = expiresAt,
                    auth = auth,
                    pendingWalletSelectionId = pendingWalletSelectionId,
                    walletType = walletType,
                    expiryBounds = expiryBounds,
                ),
            )
            return pendingWalletSelectionId to revision
        }
    }

    fun selectWallet(
        wallet: Wallet,
        requiredRevision: Long? = null,
    ): Long =
        synchronized(lock) {
            requiredRevision?.let(::requireCurrentRevision)
            val selected =
                when (val current = state) {
                    is SessionState.AwaitingWalletSelection -> {
                        SessionState.ActiveSession(
                            wallet = wallet,
                            signerAddress = current.signerAddress,
                            signerKeyType = current.signerKeyType,
                            expiresAt = current.expiresAt,
                            auth = current.auth,
                            expiryBounds = current.expiryBounds,
                        )
                    }

                    is SessionState.ActiveSession -> {
                        current.copy(wallet = wallet)
                    }

                    else -> {
                        error("No authenticated wallet selection in progress")
                    }
                }
            replaceState(selected)
            revision
        }

    fun selectWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        wallet: Wallet,
    ): Long =
        synchronized(lock) {
            val current = currentPendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
            replaceState(
                SessionState.ActiveSession(
                    wallet = wallet,
                    signerAddress = current.signerAddress,
                    signerKeyType = current.signerKeyType,
                    expiresAt = current.expiresAt,
                    auth = current.auth,
                    expiryBounds = current.expiryBounds,
                ),
            )
            revision
        }

    /**
     * Replaces the authenticated session's expiry without bumping the revision: the credential
     * is unchanged, so in-flight operations keep their session ownership. Returns the updated
     * snapshot, or null when [requiredRevision] is stale or no credential has been verified.
     */
    fun updateExpiresAt(
        expiresAt: String,
        requiredRevision: Long,
    ): OMSWalletSessionSnapshot? =
        synchronized(lock) {
            if (revision != requiredRevision) return@synchronized null
            state =
                when (val current = state) {
                    is SessionState.ActiveSession -> current.copy(expiresAt = expiresAt)
                    is SessionState.AwaitingWalletSelection -> current.copy(expiresAt = expiresAt)
                    else -> return@synchronized null
                }
            state.snapshot()
        }

    fun requireSnapshot(requiredRevision: Long? = null): OMSWalletSessionSnapshot =
        synchronized(lock) {
            requiredRevision?.let(::requireCurrentRevision)
            state.snapshot()
                ?: error("No active wallet session")
        }

    fun requirePendingAuth(): OMSWalletPendingAuthSnapshot =
        synchronized(lock) {
            when (val current = state) {
                is SessionState.PendingAuth -> {
                    OMSWalletPendingAuthSnapshot(
                        challenge = current.challenge,
                        verifier = current.verifier,
                    )
                }

                else -> {
                    error("No active pending auth challenge")
                }
            }
        }

    fun requirePendingWalletSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
    ) {
        synchronized(lock) {
            currentPendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
        }
    }

    private fun replaceState(nextState: SessionState) {
        state = nextState
        revision += 1
    }

    private fun requireCurrentRevision(expectedRevision: Long) {
        if (revision != expectedRevision) throw SessionRevisionChangedException()
    }

    private fun currentPendingWalletSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
    ): SessionState.AwaitingWalletSelection {
        val current =
            when (val current = state) {
                is SessionState.AwaitingWalletSelection -> current
                else -> error("Pending wallet selection is no longer active")
            }
        check(current.pendingWalletSelectionId == pendingWalletSelectionId) {
            "Pending wallet selection is no longer active"
        }
        check(current.signerAddress == signerAddress && current.signerKeyType == signerKeyType) {
            "Pending wallet selection is no longer active"
        }
        return current
    }

    private fun OMSWalletSessionSnapshot?.toSessionState(): SessionState {
        val snapshot = this ?: return SessionState.NoSession
        return when {
            snapshot.wallet != null -> {
                val wallet = snapshot.wallet
                val auth = snapshot.auth ?: return SessionState.NoSession
                val expiresAt = snapshot.expiresAt?.takeIf(String::isNotBlank) ?: return SessionState.NoSession
                if (wallet.id.isBlank() || wallet.address.isBlank()) return SessionState.NoSession
                SessionState.ActiveSession(
                    wallet = wallet,
                    signerAddress = snapshot.signerAddress,
                    signerKeyType = snapshot.signerKeyType,
                    expiresAt = expiresAt,
                    auth = auth,
                    expiryBounds = snapshot.expiryBounds,
                )
            }

            !snapshot.challenge.isNullOrBlank() && !snapshot.verifier.isNullOrBlank() &&
                !snapshot.signerAddress.isNullOrBlank() -> {
                SessionState.PendingAuth(
                    challenge = snapshot.challenge,
                    verifier = snapshot.verifier,
                    signerAddress = snapshot.signerAddress,
                    signerKeyType = snapshot.signerKeyType,
                )
            }

            !snapshot.signerAddress.isNullOrBlank() -> {
                val auth = snapshot.auth ?: return SessionState.NoSession
                SessionState.AwaitingWalletSelection(
                    signerAddress = snapshot.signerAddress,
                    signerKeyType = snapshot.signerKeyType,
                    expiresAt = snapshot.expiresAt.orEmpty(),
                    auth = auth,
                    pendingWalletSelectionId = null,
                    walletType = snapshot.pendingWalletType,
                    expiryBounds = snapshot.expiryBounds,
                )
            }

            else -> {
                SessionState.NoSession
            }
        }
    }
}
