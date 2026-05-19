package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WalletType
import com.omsclient.kotlin_sdk.models.CredentialInfo

/**
 * Result returned after selecting or creating a wallet.
 */
data class WalletSelectionResult(
    val walletAddress: String,
    val wallet: Wallet,
)

/**
 * Controls whether auth completion should select a wallet automatically or let
 * the app complete wallet selection.
 */
enum class WalletSelectionBehavior {
    /**
     * Selects the only existing wallet for the requested wallet type, or
     * creates and selects one when none exists. Auth completion fails when
     * multiple matching wallets exist; use [Manual] when the app needs to
     * present wallet choices.
     */
    Automatic,

    /**
     * Completes auth and returns a [PendingWalletSelection] for app-driven
     * wallet selection.
     */
    Manual,
}

/**
 * Authenticated state waiting for the app to select or create a wallet.
 */
class PendingWalletSelection internal constructor(
    val walletType: WalletType,
    val wallets: List<Wallet>,
    val credential: CredentialInfo,
    private val selectWalletAction: suspend (String) -> WalletSelectionResult,
    private val createAndSelectWalletAction: suspend (String?) -> WalletSelectionResult,
) {
    /**
     * Selects one of [wallets] and persists it as the active wallet session.
     */
    suspend fun selectWallet(walletId: String): WalletSelectionResult {
        require(wallets.any { it.id == walletId }) {
            "Selected wallet is not one of the available options"
        }
        return selectWalletAction(walletId)
    }

    /**
     * Creates a new wallet for [walletType], selects it, and persists it as the
     * active wallet session.
     */
    suspend fun createAndSelectWallet(reference: String? = null): WalletSelectionResult = createAndSelectWalletAction(reference)
}

/**
 * Result returned by auth completion APIs when wallet selection can be automatic
 * or app-driven.
 */
sealed interface CompleteAuthResult {
    data class WalletSelected(
        val walletAddress: String,
        val wallet: Wallet,
        val wallets: List<Wallet>,
        val credential: CredentialInfo,
    ) : CompleteAuthResult

    data class WalletSelection(
        val pendingSelection: PendingWalletSelection,
    ) : CompleteAuthResult
}
