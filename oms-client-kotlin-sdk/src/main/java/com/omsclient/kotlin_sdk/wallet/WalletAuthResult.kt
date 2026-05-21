package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkOperation
import com.omsclient.kotlin_sdk.OmsWalletSelectionException
import com.omsclient.kotlin_sdk.models.CredentialInfo
import com.omsclient.kotlin_sdk.models.Wallet
import com.omsclient.kotlin_sdk.models.WalletType
import kotlinx.coroutines.sync.Mutex

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
     * Selects the first existing wallet for the requested wallet type, or
     * creates and selects one when none exists. Use [Manual] when the app needs
     * to present wallet choices.
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
    private val selectionMutex = Mutex()

    /**
     * Selects one of [wallets] and persists it as the active wallet session.
     */
    suspend fun selectWallet(walletId: String): WalletSelectionResult {
        lockSelection(OmsSdkOperation.PendingWalletSelectionSelectWallet)
        try {
            if (wallets.none { it.id == walletId }) {
                throw OmsWalletSelectionException(
                    code = OmsSdkErrorCode.WalletSelectionUnavailable,
                    operation = OmsSdkOperation.PendingWalletSelectionSelectWallet,
                    message = "Selected wallet is not one of the available options",
                )
            }
            return selectWalletAction(walletId)
        } finally {
            selectionMutex.unlock()
        }
    }

    /**
     * Creates a new wallet for [walletType], selects it, and persists it as the
     * active wallet session.
     */
    suspend fun createAndSelectWallet(reference: String? = null): WalletSelectionResult {
        lockSelection(OmsSdkOperation.PendingWalletSelectionCreateAndSelectWallet)
        try {
            return createAndSelectWalletAction(reference)
        } finally {
            selectionMutex.unlock()
        }
    }

    private fun lockSelection(operation: OmsSdkOperation) {
        if (!selectionMutex.tryLock()) {
            throw OmsWalletSelectionException(
                code = OmsSdkErrorCode.WalletSelectionInFlight,
                operation = operation,
                message = "Pending wallet selection already has an action in flight",
            )
        }
    }
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
