package com.omsclient.kotlin_sdk

/**
 * Current auth and wallet-selection state for an [OMSClient].
 */
data class OMSClientSessionState(
    val hasPendingSignIn: Boolean,
    val hasPendingOidcRedirectAuth: Boolean,
    val walletAddress: String?,
    val signerAddress: String?,
)
