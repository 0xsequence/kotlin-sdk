package technology.polygon.omswallet.wallet

import kotlinx.coroutines.sync.Mutex
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletSelectionException
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletCredential
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.runOMSWalletOperation

/**
 * Result returned after selecting, creating, or importing a wallet.
 */
data class WalletActivationResult(
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
    val credential: WalletCredential,
    private val selectWalletAction: suspend (String) -> WalletActivationResult,
    private val createAndSelectWalletAction: suspend (String?) -> WalletActivationResult,
) {
    private val selectionMutex = Mutex()

    /**
     * Selects one of [wallets] and persists it as the active wallet session.
     */
    suspend fun selectWallet(walletId: String): WalletActivationResult {
        val operation = OMSWalletOperation.PendingWalletSelectionSelectWallet
        return runOMSWalletOperation(operation) {
            lockSelection(operation)
            try {
                if (wallets.none { it.id == walletId }) {
                    throw OMSWalletSelectionException(
                        code = OMSWalletErrorCode.WalletSelectionUnavailable,
                        operation = operation,
                        message = "Selected wallet is not one of the available options",
                    )
                }
                selectWalletAction(walletId)
            } finally {
                selectionMutex.unlock()
            }
        }
    }

    /**
     * Creates a new wallet for [walletType], selects it, and persists it as the
     * active wallet session.
     */
    suspend fun createAndSelectWallet(reference: String? = null): WalletActivationResult {
        val operation = OMSWalletOperation.PendingWalletSelectionCreateAndSelectWallet
        return runOMSWalletOperation(operation) {
            lockSelection(operation)
            try {
                createAndSelectWalletAction(reference)
            } finally {
                selectionMutex.unlock()
            }
        }
    }

    private fun lockSelection(operation: OMSWalletOperation) {
        if (!selectionMutex.tryLock()) {
            throw OMSWalletSelectionException(
                code = OMSWalletErrorCode.WalletSelectionInFlight,
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
    /** The credential issued by auth completion. */
    val credential: WalletCredential

    /** The selected wallet, or `null` while wallet selection is pending. */
    val wallet: Wallet?

    data class WalletSelected(
        override val wallet: Wallet,
        val wallets: List<Wallet>,
        override val credential: WalletCredential,
    ) : CompleteAuthResult

    data class WalletSelection(
        val pendingSelection: PendingWalletSelection,
    ) : CompleteAuthResult {
        override val credential: WalletCredential
            get() = pendingSelection.credential

        override val wallet: Wallet?
            get() = null
    }
}
