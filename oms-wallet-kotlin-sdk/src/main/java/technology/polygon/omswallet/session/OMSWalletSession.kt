package technology.polygon.omswallet.session

import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm

internal data class OMSWalletSessionSnapshot(
    val challenge: String? = null,
    val verifier: String? = null,
    val walletId: String? = null,
    val walletAddress: String? = null,
    val signerAddress: String? = null,
    val signerKeyType: WalletSigningAlgorithm? = null,
    val expiresAt: String? = null,
    val auth: OMSWalletSessionAuth? = null,
)

internal data class OMSWalletPendingAuthSnapshot(
    val challenge: String,
    val verifier: String,
)

internal class OMSWalletSession(
    initialSnapshot: OMSWalletSessionSnapshot? = null,
) {
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
        ) : SessionState {
            override fun snapshot(): OMSWalletSessionSnapshot =
                OMSWalletSessionSnapshot(
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    expiresAt = expiresAt,
                    auth = auth,
                )
        }

        data class ActiveSession(
            val walletId: String,
            val walletAddress: String,
            val signerAddress: String?,
            val signerKeyType: WalletSigningAlgorithm?,
            val expiresAt: String?,
            val auth: OMSWalletSessionAuth,
        ) : SessionState {
            override fun snapshot(): OMSWalletSessionSnapshot =
                OMSWalletSessionSnapshot(
                    walletId = walletId,
                    walletAddress = walletAddress,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    expiresAt = expiresAt,
                    auth = auth,
                )
        }
    }

    private val lock = Any()
    private var state: SessionState = initialSnapshot.toSessionState()
    private var nextPendingWalletSelectionId: Long = 1L
    private var revision: Long = 0L

    fun snapshot(): OMSWalletSessionSnapshot? = synchronized(lock) { state.snapshot() }

    fun revision(): Long = synchronized(lock) { revision }

    fun requireRevision(expectedRevision: Long) {
        synchronized(lock) {
            requireCurrentRevision(expectedRevision)
        }
    }

    fun restore(snapshot: OMSWalletSessionSnapshot) {
        synchronized(lock) {
            replaceState(snapshot.toSessionState())
        }
    }

    fun clear() {
        synchronized(lock) {
            replaceState(SessionState.NoSession)
        }
    }

    fun replaceForPendingAuth(
        challenge: String,
        verifier: String,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        requiredRevision: Long? = null,
    ) {
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
        }
    }

    fun markAuthVerified(
        expiresAt: String,
        auth: OMSWalletSessionAuth,
        requiredRevision: Long? = null,
    ): Long {
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
                ),
            )
            return pendingWalletSelectionId
        }
    }

    fun selectWallet(
        walletId: String,
        walletAddress: String,
        requiredRevision: Long? = null,
    ) {
        synchronized(lock) {
            requiredRevision?.let(::requireCurrentRevision)
            val selected =
                when (val current = state) {
                    is SessionState.AwaitingWalletSelection -> {
                        SessionState.ActiveSession(
                            walletId = walletId,
                            walletAddress = walletAddress,
                            signerAddress = current.signerAddress,
                            signerKeyType = current.signerKeyType,
                            expiresAt = current.expiresAt,
                            auth = current.auth,
                        )
                    }

                    is SessionState.ActiveSession -> {
                        current.copy(
                            walletId = walletId,
                            walletAddress = walletAddress,
                        )
                    }

                    else -> {
                        error("No authenticated wallet selection in progress")
                    }
                }
            replaceState(selected)
        }
    }

    fun selectWalletForPendingSelection(
        pendingWalletSelectionId: Long,
        signerAddress: String,
        signerKeyType: WalletSigningAlgorithm?,
        walletId: String,
        walletAddress: String,
    ) {
        synchronized(lock) {
            val current = currentPendingWalletSelection(pendingWalletSelectionId, signerAddress, signerKeyType)
            replaceState(
                SessionState.ActiveSession(
                    walletId = walletId,
                    walletAddress = walletAddress,
                    signerAddress = current.signerAddress,
                    signerKeyType = current.signerKeyType,
                    expiresAt = current.expiresAt,
                    auth = current.auth,
                ),
            )
        }
    }

    fun requireSnapshot(): OMSWalletSessionSnapshot =
        synchronized(lock) {
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
        check(revision == expectedRevision) {
            "Wallet session changed before operation completed"
        }
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
            !snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank() -> {
                val auth = snapshot.auth ?: return SessionState.NoSession
                SessionState.ActiveSession(
                    walletId = snapshot.walletId,
                    walletAddress = snapshot.walletAddress,
                    signerAddress = snapshot.signerAddress,
                    signerKeyType = snapshot.signerKeyType,
                    expiresAt = snapshot.expiresAt,
                    auth = auth,
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
                )
            }

            else -> {
                SessionState.NoSession
            }
        }
    }
}
