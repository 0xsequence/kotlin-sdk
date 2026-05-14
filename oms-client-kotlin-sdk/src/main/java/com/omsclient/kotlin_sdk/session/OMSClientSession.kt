package com.omsclient.kotlin_sdk.session

import com.omsclient.kotlin_sdk.OMSClientSessionLoginType
import com.omsclient.kotlin_sdk.generated.waas.SigningAlgorithm

internal data class OMSClientSessionSnapshot(
    val challenge: String? = null,
    val verifier: String? = null,
    val walletId: String? = null,
    val walletAddress: String? = null,
    val signerAddress: String? = null,
    val signerKeyType: SigningAlgorithm? = null,
    val expiresAt: String? = null,
    val loginType: OMSClientSessionLoginType? = null,
    val sessionEmail: String? = null,
)

internal data class OMSClientPendingAuthSnapshot(
    val challenge: String,
    val verifier: String,
)

internal class OMSClientSession(
    initialSnapshot: OMSClientSessionSnapshot? = null,
) {
    private sealed interface SessionState {
        fun snapshot(): OMSClientSessionSnapshot?

        data object NoSession : SessionState {
            override fun snapshot(): OMSClientSessionSnapshot? = null
        }

        data class PendingAuth(
            val challenge: String,
            val verifier: String,
            val signerAddress: String,
            val signerKeyType: SigningAlgorithm?,
        ) : SessionState {
            override fun snapshot(): OMSClientSessionSnapshot =
                OMSClientSessionSnapshot(
                    challenge = challenge,
                    verifier = verifier,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                )
        }

        data class AwaitingWalletResolution(
            val signerAddress: String,
            val signerKeyType: SigningAlgorithm?,
            val expiresAt: String,
            val loginType: OMSClientSessionLoginType?,
            val sessionEmail: String?,
        ) : SessionState {
            override fun snapshot(): OMSClientSessionSnapshot =
                OMSClientSessionSnapshot(
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    expiresAt = expiresAt,
                    loginType = loginType,
                    sessionEmail = sessionEmail,
                )
        }

        data class ActiveSession(
            val walletId: String,
            val walletAddress: String,
            val signerAddress: String?,
            val signerKeyType: SigningAlgorithm?,
            val expiresAt: String?,
            val loginType: OMSClientSessionLoginType?,
            val sessionEmail: String?,
        ) : SessionState {
            override fun snapshot(): OMSClientSessionSnapshot =
                OMSClientSessionSnapshot(
                    walletId = walletId,
                    walletAddress = walletAddress,
                    signerAddress = signerAddress,
                    signerKeyType = signerKeyType,
                    expiresAt = expiresAt,
                    loginType = loginType,
                    sessionEmail = sessionEmail,
                )
        }
    }

    private var state: SessionState = initialSnapshot.toSessionState()

    fun snapshot(): OMSClientSessionSnapshot? = state.snapshot()

    fun restore(snapshot: OMSClientSessionSnapshot) {
        state = snapshot.toSessionState()
    }

    fun clear() {
        state = SessionState.NoSession
    }

    fun replaceForPendingAuth(
        challenge: String,
        verifier: String,
        signerAddress: String,
        signerKeyType: SigningAlgorithm?,
    ) {
        state =
            SessionState.PendingAuth(
                challenge = challenge,
                verifier = verifier,
                signerAddress = signerAddress,
                signerKeyType = signerKeyType,
            )
    }

    fun markAuthVerified(
        expiresAt: String,
        loginType: OMSClientSessionLoginType?,
        sessionEmail: String?,
    ) {
        val current =
            when (val current = state) {
                is SessionState.PendingAuth -> current
                else -> error("No active pending auth challenge")
            }
        state =
            SessionState.AwaitingWalletResolution(
                signerAddress = current.signerAddress,
                signerKeyType = current.signerKeyType,
                expiresAt = expiresAt,
                loginType = loginType,
                sessionEmail = sessionEmail,
            )
    }

    fun activateWallet(
        walletId: String,
        walletAddress: String,
    ) {
        val activated =
            when (val current = state) {
                is SessionState.AwaitingWalletResolution -> {
                    SessionState.ActiveSession(
                        walletId = walletId,
                        walletAddress = walletAddress,
                        signerAddress = current.signerAddress,
                        signerKeyType = current.signerKeyType,
                        expiresAt = current.expiresAt,
                        loginType = current.loginType,
                        sessionEmail = current.sessionEmail,
                    )
                }

                is SessionState.ActiveSession -> {
                    current.copy(
                        walletId = walletId,
                        walletAddress = walletAddress,
                    )
                }

                else -> {
                    error("No authenticated wallet resolution in progress")
                }
            }
        state = activated
    }

    fun requireSnapshot(): OMSClientSessionSnapshot =
        state.snapshot()
            ?: error("No active OMS Client session")

    fun requirePendingAuth(): OMSClientPendingAuthSnapshot =
        when (val current = state) {
            is SessionState.PendingAuth -> {
                OMSClientPendingAuthSnapshot(
                    challenge = current.challenge,
                    verifier = current.verifier,
                )
            }

            else -> {
                error("No active pending auth challenge")
            }
        }

    private fun OMSClientSessionSnapshot?.toSessionState(): SessionState {
        val snapshot = this ?: return SessionState.NoSession
        return when {
            !snapshot.walletId.isNullOrBlank() && !snapshot.walletAddress.isNullOrBlank() -> {
                SessionState.ActiveSession(
                    walletId = snapshot.walletId,
                    walletAddress = snapshot.walletAddress,
                    signerAddress = snapshot.signerAddress,
                    signerKeyType = snapshot.signerKeyType,
                    expiresAt = snapshot.expiresAt,
                    loginType = snapshot.loginType,
                    sessionEmail = snapshot.sessionEmail,
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
                SessionState.AwaitingWalletResolution(
                    signerAddress = snapshot.signerAddress,
                    signerKeyType = snapshot.signerKeyType,
                    expiresAt = snapshot.expiresAt.orEmpty(),
                    loginType = snapshot.loginType,
                    sessionEmail = snapshot.sessionEmail,
                )
            }

            else -> {
                SessionState.NoSession
            }
        }
    }
}
