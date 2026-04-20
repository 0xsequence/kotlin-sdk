package com.polygon_wallet.polygon_kotlin_sdk.session

internal data class SequenceSessionSnapshot(
    val challenge: String? = null,
    val verifier: String? = null,
    val walletAddress: String? = null,
    val signerAddress: String? = null,
)

internal data class SequencePendingAuthSnapshot(
    val challenge: String,
    val verifier: String,
)

internal class SequenceWalletSession(
    initialSnapshot: SequenceSessionSnapshot? = null,
) {
    private sealed interface SessionState {
        fun snapshot(): SequenceSessionSnapshot?

        data object NoSession : SessionState {
            override fun snapshot(): SequenceSessionSnapshot? = null
        }

        data class PendingAuth(
            val challenge: String,
            val verifier: String,
            val signerAddress: String,
        ) : SessionState {
            override fun snapshot(): SequenceSessionSnapshot = SequenceSessionSnapshot(
                challenge = challenge,
                verifier = verifier,
                signerAddress = signerAddress,
            )
        }

        data class AwaitingWalletResolution(
            val signerAddress: String,
        ) : SessionState {
            override fun snapshot(): SequenceSessionSnapshot = SequenceSessionSnapshot(
                signerAddress = signerAddress,
            )
        }

        data class ActiveSession(
            val walletAddress: String,
            val signerAddress: String?,
        ) : SessionState {
            override fun snapshot(): SequenceSessionSnapshot = SequenceSessionSnapshot(
                walletAddress = walletAddress,
                signerAddress = signerAddress,
            )
        }
    }

    private var state: SessionState = initialSnapshot.toSessionState()

    fun snapshot(): SequenceSessionSnapshot? = state.snapshot()

    fun restore(snapshot: SequenceSessionSnapshot) {
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

    fun activateWallet(walletAddress: String) {
        val current = when (val current = state) {
            is SessionState.AwaitingWalletResolution -> current
            else -> error("No authenticated wallet resolution in progress")
        }
        state = SessionState.ActiveSession(
            walletAddress = walletAddress,
            signerAddress = current.signerAddress,
        )
    }

    fun requireSnapshot(): SequenceSessionSnapshot = state.snapshot()
        ?: error("No active Sequence wallet session")

    fun requirePendingAuth(): SequencePendingAuthSnapshot {
        return when (val current = state) {
            is SessionState.PendingAuth -> SequencePendingAuthSnapshot(
                challenge = current.challenge,
                verifier = current.verifier,
            )
            else -> error("No active pending auth challenge")
        }
    }

    private fun SequenceSessionSnapshot?.toSessionState(): SessionState {
        val snapshot = this ?: return SessionState.NoSession
        return when {
            !snapshot.walletAddress.isNullOrBlank() -> SessionState.ActiveSession(
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
