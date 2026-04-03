package com.polygon_wallet.polygon_kotlin_sdk.session

data class SequenceSessionSnapshot(
    val challenge: String,
    val verifier: String,
    val walletAddress: String? = null,
    val signerAddress: String? = null,
)

class SequenceWalletSession(
    initialSnapshot: SequenceSessionSnapshot? = null,
) {
    private var snapshot: SequenceSessionSnapshot? = initialSnapshot

    fun snapshot(): SequenceSessionSnapshot? = snapshot

    fun restore(snapshot: SequenceSessionSnapshot) {
        this.snapshot = snapshot
    }

    fun clear() {
        snapshot = null
    }

    fun replaceForPendingAuth(
        challenge: String,
        verifier: String,
        signerAddress: String,
    ) {
        snapshot = SequenceSessionSnapshot(
            challenge = challenge,
            verifier = verifier,
            walletAddress = null,
            signerAddress = signerAddress,
        )
    }

    fun updateWalletAddress(walletAddress: String) {
        val current = requireSnapshot()
        snapshot = current.copy(walletAddress = walletAddress)
    }

    fun requireSnapshot(): SequenceSessionSnapshot =
        checkNotNull(snapshot) { "No active Sequence wallet session" }
}
