package com.omswallet.kotlin_sdk.session

internal data class OmsWalletSessionSnapshot(
    val challenge: String? = null,
    val verifier: String? = null,
    val walletId: String? = null,
    val walletAddress: String? = null,
    val signerAddress: String? = null,
)

internal data class OmsWalletPendingAuthSnapshot(
    val challenge: String,
    val verifier: String,
)

internal class OmsWalletSession(
    initialSnapshot: OmsWalletSessionSnapshot? = null,
) {
    private sealed interface SessionState {
        fun snapshot(): OmsWalletSessionSnapshot?

        data object NoSession : SessionState {
            override fun snapshot(): OmsWalletSessionSnapshot? = null
        }

        data class PendingAuth(
            val challenge: String,
            val verifier: String,
            val signerAddress: String,
        ) : SessionState {
            override fun snapshot(): OmsWalletSessionSnapshot = OmsWalletSessionSnapshot(
                challenge = challenge,
                verifier = verifier,
                signerAddress = signerAddress,
            )
        }

        data class AwaitingWalletResolution(
            val signerAddress: String,
        ) : SessionState {
            override fun snapshot(): OmsWalletSessionSnapshot = OmsWalletSessionSnapshot(
                signerAddress = signerAddress,
            )
        }

        data class ActiveSession(
            val walletId: String,
            val walletAddress: String,
            val signerAddress: String?,
        ) : SessionState {
            override fun snapshot(): OmsWalletSessionSnapshot = OmsWalletSessionSnapshot(
                walletId = walletId,
                walletAddress = walletAddress,
                signerAddress = signerAddress,
            )
        }
    }

    private var state: SessionState = initialSnapshot.toSessionState()

    fun snapshot(): OmsWalletSessionSnapshot? = state.snapshot()

    fun restore(snapshot: OmsWalletSessionSnapshot) {
        state = snapshot.toSessionState()
    }

    fun clear() {
        state = SessionState.NoSession
    }

    fun replaceForPendingAuth(
        challenge: String,
        verifier: String,
        signerAddress: String,
    ) {
        state = SessionState.PendingAuth(
            challenge = challenge,
            verifier = verifier,
            signerAddress = signerAddress,
        )
    }

    fun markAuthVerified() {
        val current = when (val current = state) {
            is SessionState.PendingAuth -> current
            else -> error("No active pending auth challenge")
        }
        state = SessionState.AwaitingWalletResolution(
            signerAddress = current.signerAddress,
        )
    }

    fun activateWallet(walletId: String, walletAddress: String) {
        val current = when (val current = state) {
            is SessionState.AwaitingWalletResolution -> current
            else -> error("No authenticated wallet resolution in progress")
        }
        state = SessionState.ActiveSession(
            walletId = walletId,
            walletAddress = walletAddress,
            signerAddress = current.signerAddress,
        )
    }

    fun requireSnapshot(): OmsWalletSessionSnapshot = state.snapshot()
        ?: error("No active OMS Wallet session")

    fun requirePendingAuth(): OmsWalletPendingAuthSnapshot {
        return when (val current = state) {
            is SessionState.PendingAuth -> OmsWalletPendingAuthSnapshot(
                challenge = current.challenge,
                verifier = current.verifier,
            )
            else -> error("No active pending auth challenge")
        }
    }

    private fun OmsWalletSessionSnapshot?.toSessionState(): SessionState {
        val snapshot = this ?: return SessionState.NoSession
        return when {
            !snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank() -> SessionState.ActiveSession(
                walletId = snapshot.walletId,
                walletAddress = snapshot.walletAddress,
                signerAddress = snapshot.signerAddress,
            )
            !snapshot.challenge.isNullOrBlank() && !snapshot.verifier.isNullOrBlank() &&
                !snapshot.signerAddress.isNullOrBlank() -> SessionState.PendingAuth(
                challenge = snapshot.challenge,
                verifier = snapshot.verifier,
                signerAddress = snapshot.signerAddress,
            )
            !snapshot.signerAddress.isNullOrBlank() -> SessionState.AwaitingWalletResolution(
                signerAddress = snapshot.signerAddress,
            )
            else -> SessionState.NoSession
        }
    }
}
