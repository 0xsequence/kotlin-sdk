package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.models.CredentialInfo

/**
 * Result returned when a wallet is activated explicitly.
 */
data class WalletActivationResult(
    val walletAddress: String,
    val wallet: Wallet,
)

/**
 * Result returned by auth completion APIs when the caller chooses whether to
 * activate a wallet automatically.
 */
sealed interface CompleteAuthResult {
    val wallets: List<Wallet>
    val credential: CredentialInfo

    data class Activated(
        val walletAddress: String,
        val wallet: Wallet,
        override val wallets: List<Wallet>,
        override val credential: CredentialInfo,
    ) : CompleteAuthResult

    data class WalletSelection(
        override val wallets: List<Wallet>,
        override val credential: CredentialInfo,
    ) : CompleteAuthResult
}
