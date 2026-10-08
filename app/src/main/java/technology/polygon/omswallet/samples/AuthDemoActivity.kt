package technology.polygon.omswallet.samples

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWallet
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletException
import technology.polygon.omswallet.OMSWalletNetworks
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.OMSWalletSessionExpiredEvent
import technology.polygon.omswallet.SolanaNetwork
import technology.polygon.omswallet.TronNetwork
import technology.polygon.omswallet.models.AbiArg
import technology.polygon.omswallet.models.FeeOptionSelection
import technology.polygon.omswallet.models.FeeOptionWithBalance
import technology.polygon.omswallet.models.SolanaBalance
import technology.polygon.omswallet.models.TransactionStatus
import technology.polygon.omswallet.models.TransactionStatusPollingOptions
import technology.polygon.omswallet.models.TronBalance
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.utils.parseUnits
import technology.polygon.omswallet.wallet.CompleteAuthResult
import technology.polygon.omswallet.wallet.OidcRedirectAuthResult
import technology.polygon.omswallet.wallet.OmsRelayOidcProvider
import technology.polygon.omswallet.wallet.OmsRelayOidcProviders
import technology.polygon.omswallet.wallet.PendingWalletSelection
import technology.polygon.omswallet.wallet.WalletClient
import technology.polygon.omswallet.wallet.WalletSelectionBehavior
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthDemoActivity : AppCompatActivity() {
    private val uiScope = MainScope()
    private val authPreferences by lazy {
        getSharedPreferences(AUTH_DEMO_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val sdk by lazy {
        OMSWallet(
            context = this,
            publishableKey = DemoConfig.demoPublishableKey,
        )
    }

    private lateinit var emailInput: TextInputEditText
    private lateinit var codeInput: TextInputEditText
    private lateinit var authStatusView: TextView
    private lateinit var sessionStateCard: View
    private lateinit var sessionStateView: TextView
    private lateinit var walletAddressView: TextView
    private lateinit var networkInput: AutoCompleteTextView
    private lateinit var messageInput: TextInputEditText
    private lateinit var transactionToInput: TextInputEditText
    private lateinit var transactionValueInput: TextInputEditText
    private lateinit var lastSignatureView: TextView
    private lateinit var signatureStatusView: TextView
    private lateinit var lastTransactionHashView: TextView
    private lateinit var transactionStatusView: TextView
    private lateinit var logView: TextView
    private lateinit var authCard: View
    private lateinit var emailStepContainer: View
    private lateinit var codeStepContainer: View
    private lateinit var walletActionsContainer: View
    private lateinit var logoutButton: MaterialButton
    private lateinit var openExplorerButton: MaterialButton
    private lateinit var copyWalletAddressButton: MaterialButton
    private lateinit var cancelCodeStepButton: MaterialButton
    private lateinit var startGoogleSignInButton: MaterialButton
    private lateinit var startGoogleRedirectSignInButton: MaterialButton
    private lateinit var startAppleRedirectSignInButton: MaterialButton
    private lateinit var manualWalletSelectionCheckbox: MaterialCheckBox
    private lateinit var sessionLifetimeInput: TextInputEditText
    private lateinit var walletTypeInput: AutoCompleteTextView
    private lateinit var networkInputLayout: View
    private lateinit var evmActionsContainer: View
    private lateinit var tronActionsContainer: View
    private lateinit var tronBalancesView: TextView
    private lateinit var tronRefreshBalancesButton: MaterialButton
    private lateinit var tronMessageInput: TextInputEditText
    private lateinit var tronSignMessageButton: MaterialButton
    private lateinit var tronSignatureView: TextView
    private lateinit var tronSignatureStatusView: TextView
    private lateinit var tronAssetInput: AutoCompleteTextView
    private lateinit var tronCustomTokenContainer: View
    private lateinit var tronTokenContractInput: TextInputEditText
    private lateinit var tronTokenDecimalsInput: TextInputEditText
    private lateinit var tronRecipientInput: TextInputEditText
    private lateinit var tronAmountLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var tronAmountInput: TextInputEditText
    private lateinit var tronSendButton: MaterialButton
    private lateinit var tronTransactionHashView: TextView
    private lateinit var tronTransactionStatusView: TextView
    private lateinit var tronOpenExplorerButton: MaterialButton
    private lateinit var solanaActionsContainer: View
    private lateinit var solanaBalancesView: TextView
    private lateinit var solanaRefreshBalancesButton: MaterialButton
    private lateinit var solanaMessageInput: TextInputEditText
    private lateinit var solanaSignMessageButton: MaterialButton
    private lateinit var solanaSignatureView: TextView
    private lateinit var solanaSignatureStatusView: TextView
    private lateinit var solanaAssetInput: AutoCompleteTextView
    private lateinit var solanaAssetHintView: TextView
    private lateinit var solanaCustomTokenContainer: View
    private lateinit var solanaTokenMintInput: TextInputEditText
    private lateinit var solanaTokenDecimalsInput: TextInputEditText
    private lateinit var solanaRecipientInput: TextInputEditText
    private lateinit var solanaAmountLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var solanaAmountInput: TextInputEditText
    private lateinit var solanaSendButton: MaterialButton
    private lateinit var solanaTransactionSignatureView: TextView
    private lateinit var solanaTransactionStatusView: TextView
    private lateinit var solanaOpenExplorerButton: MaterialButton

    private var lastSignedMessage: String? = null
    private var lastSignedSignature: String? = null
    private var lastTransactionHash: String? = null
    private var selectedNetwork: Network = Network.AMOY
    private var selectedTronAsset: TronAsset = TronAsset.Trx
    private var lastTronTransactionHash: String? = null
    private var selectedSolanaAsset: SolanaAsset = SolanaAsset.Sol
    private var lastSolanaTransactionSignature: String? = null
    private val busyWalletPanels = mutableSetOf<WalletType>()
    private var expiredSessionEvent: OMSWalletSessionExpiredEvent? = null
    private var unsubscribeSessionExpired: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth_demo)
        findViewById<View>(R.id.authDemoRoot).applySafeDrawingInsets()
        bindViews()
        populateDefaults()
        bindActions()
        subscribeSessionExpiry()
        renderSessionState()
        handleOidcRedirectCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOidcRedirectCallback(intent)
    }

    override fun onDestroy() {
        unsubscribeSessionExpired?.invoke()
        unsubscribeSessionExpired = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        emailInput = findViewById(R.id.emailInput)
        codeInput = findViewById(R.id.codeInput)
        authStatusView = findViewById(R.id.authStatusView)
        sessionStateCard = findViewById(R.id.sessionStateCard)
        sessionStateView = findViewById(R.id.sessionStateView)
        walletAddressView = findViewById(R.id.walletAddressView)
        networkInput = findViewById(R.id.networkInput)
        messageInput = findViewById(R.id.messageInput)
        transactionToInput = findViewById(R.id.transactionToInput)
        transactionValueInput = findViewById(R.id.transactionValueInput)
        lastSignatureView = findViewById(R.id.lastSignatureView)
        signatureStatusView = findViewById(R.id.signatureStatusView)
        lastTransactionHashView = findViewById(R.id.lastTransactionHashView)
        transactionStatusView = findViewById(R.id.transactionStatusView)
        logView = findViewById(R.id.logView)
        authCard = findViewById(R.id.authCard)
        emailStepContainer = findViewById(R.id.emailStepContainer)
        codeStepContainer = findViewById(R.id.codeStepContainer)
        walletActionsContainer = findViewById(R.id.walletActionsContainer)
        logoutButton = findViewById(R.id.resetSessionButton)
        openExplorerButton = findViewById(R.id.openExplorerButton)
        copyWalletAddressButton = findViewById(R.id.copyWalletAddressButton)
        cancelCodeStepButton = findViewById(R.id.cancelCodeStepButton)
        startGoogleSignInButton = findViewById(R.id.startGoogleSignInButton)
        startGoogleRedirectSignInButton = findViewById(R.id.startGoogleRedirectSignInButton)
        startAppleRedirectSignInButton = findViewById(R.id.startAppleRedirectSignInButton)
        manualWalletSelectionCheckbox = findViewById(R.id.manualWalletSelectionCheckbox)
        sessionLifetimeInput = findViewById(R.id.sessionLifetimeInput)
        walletTypeInput = findViewById(R.id.walletTypeInput)
        networkInputLayout = findViewById(R.id.networkInputLayout)
        evmActionsContainer = findViewById(R.id.evmActionsContainer)
        tronActionsContainer = findViewById(R.id.tronActionsContainer)
        tronBalancesView = findViewById(R.id.tronBalancesView)
        tronRefreshBalancesButton = findViewById(R.id.tronRefreshBalancesButton)
        tronMessageInput = findViewById(R.id.tronMessageInput)
        tronSignMessageButton = findViewById(R.id.tronSignMessageButton)
        tronSignatureView = findViewById(R.id.tronSignatureView)
        tronSignatureStatusView = findViewById(R.id.tronSignatureStatusView)
        tronAssetInput = findViewById(R.id.tronAssetInput)
        tronCustomTokenContainer = findViewById(R.id.tronCustomTokenContainer)
        tronTokenContractInput = findViewById(R.id.tronTokenContractInput)
        tronTokenDecimalsInput = findViewById(R.id.tronTokenDecimalsInput)
        tronRecipientInput = findViewById(R.id.tronRecipientInput)
        tronAmountLayout = findViewById(R.id.tronAmountLayout)
        tronAmountInput = findViewById(R.id.tronAmountInput)
        tronSendButton = findViewById(R.id.tronSendButton)
        tronTransactionHashView = findViewById(R.id.tronTransactionHashView)
        tronTransactionStatusView = findViewById(R.id.tronTransactionStatusView)
        tronOpenExplorerButton = findViewById(R.id.tronOpenExplorerButton)
        solanaActionsContainer = findViewById(R.id.solanaActionsContainer)
        solanaBalancesView = findViewById(R.id.solanaBalancesView)
        solanaRefreshBalancesButton = findViewById(R.id.solanaRefreshBalancesButton)
        solanaMessageInput = findViewById(R.id.solanaMessageInput)
        solanaSignMessageButton = findViewById(R.id.solanaSignMessageButton)
        solanaSignatureView = findViewById(R.id.solanaSignatureView)
        solanaSignatureStatusView = findViewById(R.id.solanaSignatureStatusView)
        solanaAssetInput = findViewById(R.id.solanaAssetInput)
        solanaAssetHintView = findViewById(R.id.solanaAssetHintView)
        solanaCustomTokenContainer = findViewById(R.id.solanaCustomTokenContainer)
        solanaTokenMintInput = findViewById(R.id.solanaTokenMintInput)
        solanaTokenDecimalsInput = findViewById(R.id.solanaTokenDecimalsInput)
        solanaRecipientInput = findViewById(R.id.solanaRecipientInput)
        solanaAmountLayout = findViewById(R.id.solanaAmountLayout)
        solanaAmountInput = findViewById(R.id.solanaAmountInput)
        solanaSendButton = findViewById(R.id.solanaSendButton)
        solanaTransactionSignatureView = findViewById(R.id.solanaTransactionSignatureView)
        solanaTransactionStatusView = findViewById(R.id.solanaTransactionStatusView)
        solanaOpenExplorerButton = findViewById(R.id.solanaOpenExplorerButton)
    }

    private fun populateDefaults() {
        messageInput.setText("test")
        transactionToInput.setText("0xE5E8B483FfC05967FcFed58cc98D053265af6D99")
        restoreAuthPreferences()
        configureNetworkPicker()
        configureWalletTypePicker()
        configureTronAssetPicker()
        configureSolanaAssetPicker()
        resetUiForNoSession()
    }

    private fun bindActions() {
        startGoogleSignInButton.setOnClickListener {
            launchAction(
                label = "Sign in with Google",
                onStart = { authStatusView.text = "Requesting Google ID token..." },
                onFailure = {
                    authStatusView.text = "Google sign-in failed: ${it.message ?: "Unknown error"}"
                    appendLog("Google sign-in error: ${describeThrowable(it)}")
                },
            ) {
                try {
                    val idToken = requestGoogleIdToken()
                    when (
                        val result =
                            completeGoogleIdTokenAuth(idToken)
                    ) {
                        is CompleteAuthResult.WalletSelected -> {
                            renderSignedInWallet(result.wallet, "Google login complete")
                            appendLog("Google sign-in complete: ${result.wallet.address}")
                        }

                        is CompleteAuthResult.WalletSelection -> {
                            completePendingWalletSelection(
                                pendingSelection = result.pendingSelection,
                                status = "Google login complete",
                            )
                        }
                    }
                } catch (throwable: Throwable) {
                    runCatching { sdk.wallet.signOut() }
                    throw throwable
                }
            }
        }

        startGoogleRedirectSignInButton.setOnClickListener {
            startOidcRedirectSignIn(
                providerName = "Google",
                provider = OmsRelayOidcProviders.google,
                loginHint = expiredSessionEmail(),
            )
        }

        startAppleRedirectSignInButton.setOnClickListener {
            startOidcRedirectSignIn(
                providerName = "Apple",
                provider = OmsRelayOidcProviders.apple,
            )
        }

        findViewById<MaterialButton>(R.id.startEmailSignInButton).setOnClickListener {
            launchAction(
                label = "Start email sign-in",
                onStart = { authStatusView.text = "Requesting email code..." },
                onFailure = { authStatusView.text = "Email sign-in failed: ${it.message ?: "Unknown error"}" },
            ) {
                val email = requireEmailForSignIn()
                val sessionLifetimeSeconds = requestedSessionLifetimeSeconds()
                persistAuthPreferences()
                sdk.wallet.startEmailAuth(
                    email = email,
                    sessionLifetimeSeconds = sessionLifetimeSeconds ?: WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS,
                )
                authStatusView.text =
                    buildString {
                        append("Code requested for ")
                        append(email)
                    }
                showPendingCodeStep()
                emailInput.text?.clear()
                appendLog("Email verifier committed for $email")
            }
        }

        cancelCodeStepButton.setOnClickListener {
            runCatching { sdk.wallet.signOut() }
                .onFailure { throwable -> appendLog("!! ${describeThrowable(throwable)}") }
            clearExpiredSessionState()
            codeInput.text?.clear()
            showEmailStep()
        }

        findViewById<MaterialButton>(R.id.confirmCodeButton).setOnClickListener {
            launchAction(
                label = "Confirm code and resolve wallet",
                onStart = { authStatusView.text = "Confirming code and resolving wallet..." },
                onFailure = { authStatusView.text = "Code confirmation failed: ${it.message ?: "Unknown error"}" },
            ) {
                val code = requireText(codeInput, "Verification code")
                codeInput.text?.clear()
                when (
                    val result =
                        sdk.wallet.completeEmailAuth(
                            code = code,
                            walletSelection = currentWalletSelectionBehavior(),
                        )
                ) {
                    is CompleteAuthResult.WalletSelected -> {
                        renderSignedInWallet(result.wallet, "Email login complete")
                    }

                    is CompleteAuthResult.WalletSelection -> {
                        completePendingWalletSelection(
                            pendingSelection = result.pendingSelection,
                            status = "Email login complete",
                        )
                    }
                }
            }
        }

        logoutButton.setOnClickListener {
            runCatching { sdk.wallet.signOut() }
                .onFailure { throwable -> appendLog("!! ${describeThrowable(throwable)}") }
            clearExpiredSessionState()
            lastSignedMessage = null
            lastSignedSignature = null
            lastTransactionHash = null
            renderSessionState()
            appendLog("Logged out.")
            uiScope.launch {
                runCatching {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                }.onFailure { throwable ->
                    appendLog("!! Failed to clear credential state: ${throwable.message ?: throwable::class.java.simpleName}")
                }
            }
        }

        findViewById<MaterialButton>(R.id.signMessageButton).setOnClickListener {
            launchAction(
                label = "Sign message",
                onStart = { signatureStatusView.text = "Signature status: signing in progress..." },
                onFailure = { signatureStatusView.text = "Signature status: signing failed." },
            ) {
                val message = requireText(messageInput, "Message")
                val network = selectedNetwork
                val result =
                    sdk.wallet.signMessage(
                        network = network,
                        message = message,
                    )
                lastSignedMessage = message
                lastSignedSignature = result
                lastSignatureView.text = "Last signature: $result"
                signatureStatusView.text = "Signature status: signed. Ready to verify."
                appendLog("Signed message on chain ${network.id}")
            }
        }

        findViewById<MaterialButton>(R.id.verifySignatureButton).setOnClickListener {
            launchAction(
                label = "Verify last signature",
                onStart = { signatureStatusView.text = "Signature status: verification in progress..." },
                onFailure = { signatureStatusView.text = "Signature status: verification failed." },
            ) {
                val result =
                    sdk.wallet.isValidMessageSignature(
                        network = selectedNetwork,
                        message = requireNotNull(lastSignedMessage) { "No signed message available" },
                        signature = requireNotNull(lastSignedSignature) { "No signature available" },
                    )
                signatureStatusView.text =
                    if (result) {
                        "Signature status: valid on chain ${selectedNetwork.id}."
                    } else {
                        "Signature status: invalid on chain ${selectedNetwork.id}."
                    }
                appendLog("Verify signature => isValid=$result")
            }
        }

        findViewById<MaterialButton>(R.id.sendTransactionButton).setOnClickListener {
            launchAction(
                label = "Send transaction",
                onStart = { transactionStatusView.text = "Transaction status: sending in progress..." },
                onFailure = { transactionStatusView.text = "Transaction status: send failed." },
            ) {
                val network = selectedNetwork
                val result =
                    sdk.wallet.sendTransaction(
                        network = network,
                        to = transactionToInput.text.toString().trim(),
                        value = parseUnits(transactionValueInput.text.toString(), 18),
                        selectFeeOption = ::selectFeeOption,
                    )
                lastTransactionHash = result.txnHash
                lastTransactionHashView.text = "Last transaction hash: ${result.txnHash ?: "pending"}"
                transactionStatusView.text = "Transaction status: ${result.status} on chain ${network.id}."
                openExplorerButton.visibility = if (result.txnHash == null) View.GONE else View.VISIBLE
                appendLog("Transaction ${result.txnId}: status=${result.status} hash=${result.txnHash ?: "pending"}")
            }
        }

        openExplorerButton.setOnClickListener {
            val txnHash = lastTransactionHash ?: return@setOnClickListener
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("${selectedNetwork.explorerUrl}/tx/$txnHash"),
                ),
            )
        }

        copyWalletAddressButton.setOnClickListener {
            copyWalletAddress()
        }

        bindTronActions()
        bindSolanaActions()
    }

    private fun bindTronActions() {
        tronRefreshBalancesButton.setOnClickListener {
            val wallet = activeWalletOrNull(WalletType.Tron) ?: return@setOnClickListener
            launchWalletPanelAction(WalletType.Tron, label = "Refresh Tron balances") {
                loadTronBalances(wallet)
            }
        }

        findViewById<MaterialButton>(R.id.tronFaucetButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NILE_FAUCET_URL)))
        }

        tronSignMessageButton.setOnClickListener {
            launchWalletPanelAction(
                WalletType.Tron,
                label = "Sign Tron message",
                onStart = { tronSignatureStatusView.text = "Signature status: signing in progress..." },
                onFailure = { tronSignatureStatusView.text = "Signature status: ${it.message ?: "signing failed"}" },
            ) {
                val message = requireText(tronMessageInput, "Message")
                val walletAddress = requireActiveWallet(WalletType.Tron).address
                val signature = sdk.wallet.signTronMessage(message)
                tronSignatureView.text = "Last signature: $signature"
                tronSignatureStatusView.text = "Signature status: verifying..."
                val valid =
                    sdk.wallet.isValidTronMessageSignature(
                        message = message,
                        signature = signature,
                        walletAddress = walletAddress,
                    )
                tronSignatureStatusView.text =
                    if (valid) "Signature status: signed and verified." else "Signature status: verification failed."
                appendLog("Tron message signed; isValid=$valid")
            }
        }

        tronSendButton.setOnClickListener {
            launchWalletPanelAction(
                WalletType.Tron,
                label = "Send on Tron Nile",
                onStart = {
                    tronTransactionStatusView.text = "Transfer status: preparing transfer..."
                    tronTransactionHashView.text = "Last tx hash: none"
                    tronOpenExplorerButton.visibility = View.GONE
                    lastTronTransactionHash = null
                },
                onFailure = { tronTransactionStatusView.text = "Transfer status: ${it.message ?: "send failed"}" },
            ) {
                val wallet = requireActiveWallet(WalletType.Tron)
                val recipient = requireText(tronRecipientInput, "Recipient")
                val amount = requireText(tronAmountInput, "Amount")
                val statusPolling = TransactionStatusPollingOptions(timeoutMs = 120_000L)
                val result =
                    when (val asset = selectedTronAsset) {
                        TronAsset.Trx -> {
                            sdk.wallet.sendTronTransaction(
                                network = TronNetwork.Nile,
                                to = recipient,
                                value = parseUnits(amount, TRX_DECIMALS),
                                statusPolling = statusPolling,
                                selectFeeOption = ::selectFeeOption,
                            )
                        }

                        TronAsset.Usdt, TronAsset.Custom -> {
                            val contract =
                                if (asset == TronAsset.Usdt) {
                                    NILE_USDT_CONTRACT
                                } else {
                                    requireText(tronTokenContractInput, "Token contract")
                                }
                            val decimals =
                                if (asset == TronAsset.Usdt) {
                                    USDT_DECIMALS
                                } else {
                                    requireText(tronTokenDecimalsInput, "Token decimals")
                                        .toIntOrNull()
                                        ?.takeIf { it in 0..255 }
                                        ?: error("Token decimals must be between 0 and 255")
                                }
                            // TRC-20 transfers are contract calls; the wallet service ABI-encodes the args.
                            sdk.wallet.callTronContract(
                                network = TronNetwork.Nile,
                                contractAddress = contract,
                                method = "transfer",
                                args =
                                    listOf(
                                        AbiArg(type = "address", value = JsonPrimitive(recipient)),
                                        AbiArg(type = "uint256", value = JsonPrimitive(parseUnits(amount, decimals).toString())),
                                    ),
                                statusPolling = statusPolling,
                                selectFeeOption = ::selectFeeOption,
                            )
                        }
                    }
                lastTronTransactionHash = result.txnHash
                tronTransactionHashView.text = "Last tx hash: ${result.txnHash ?: result.txnId}"
                tronTransactionStatusView.text =
                    when (result.status) {
                        TransactionStatus.Executed, TransactionStatus.Failed -> "Transfer status: transfer ${result.status}."
                        else -> "Transfer status: Transaction submitted."
                    }
                tronOpenExplorerButton.visibility = if (result.txnHash == null) View.GONE else View.VISIBLE
                appendLog("Tron transaction ${result.txnId}: status=${result.status} hash=${result.txnHash ?: "pending"}")
                // The transfer already succeeded; a balance refresh failure only updates the balance view.
                runCatching { loadTronBalances(wallet) }
                    .onFailure { appendLog("!! Tron balance refresh failed: ${describeThrowable(it)}") }
            }
        }

        tronOpenExplorerButton.setOnClickListener {
            val txnHash = lastTronTransactionHash ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$TRONSCAN_NILE_URL/transaction/$txnHash")))
        }
    }

    /** Loads Nile TRX and USDT balances; shows the indexer's Nile error instead of zero balances. */
    private suspend fun loadTronBalances(wallet: Wallet) {
        tronBalancesView.text = "Nile TRX: loading...\nNile USDT: loading..."
        try {
            val result =
                sdk.indexer.getTronBalances(
                    walletAddress = wallet.address,
                    networks = listOf(TronNetwork.Nile),
                )
            val networkError = result.errors.firstOrNull { it.network == TronNetwork.Nile }
            if (networkError != null) {
                tronBalancesView.text = "Balances unavailable: ${networkError.reason}"
                return
            }
            val trx = result.balances.firstOrNull { it is TronBalance.Native }
            val usdt =
                result.balances.firstOrNull {
                    it is TronBalance.FungibleToken && it.contractAddress == NILE_USDT_CONTRACT
                }
            tronBalancesView.text =
                buildString {
                    appendLine("Nile TRX: ${trx?.formattedBalance ?: "0"} TRX")
                    append("Nile USDT: ${usdt?.formattedBalance ?: "0"} USDT")
                }
        } catch (throwable: Throwable) {
            tronBalancesView.text = "Balances unavailable: ${throwable.message ?: "Unknown error"}"
            throw throwable
        }
    }

    private fun bindSolanaActions() {
        solanaRefreshBalancesButton.setOnClickListener {
            val wallet = activeWalletOrNull(WalletType.Solana) ?: return@setOnClickListener
            launchWalletPanelAction(WalletType.Solana, label = "Refresh Solana balances") {
                loadSolanaBalances(wallet)
            }
        }

        findViewById<MaterialButton>(R.id.solanaViewWalletButton).setOnClickListener {
            val address = activeWalletOrNull(WalletType.Solana)?.address ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SOLANA_EXPLORER_URL/address/$address?cluster=devnet")))
        }

        findViewById<MaterialButton>(R.id.solanaSolFaucetButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOLANA_SOL_FAUCET_URL)))
        }

        findViewById<MaterialButton>(R.id.solanaUsdcFaucetButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOLANA_USDC_FAUCET_URL)))
        }

        solanaSignMessageButton.setOnClickListener {
            launchWalletPanelAction(
                WalletType.Solana,
                label = "Sign Solana message",
                onStart = {
                    solanaSignatureView.text = "Last signature: none"
                    solanaSignatureStatusView.text = "Signature status: signing in progress..."
                },
                onFailure = { solanaSignatureStatusView.text = "Signature status: ${it.message ?: "signing failed"}" },
            ) {
                val message = requireText(solanaMessageInput, "Message")
                val walletAddress = requireActiveWallet(WalletType.Solana).address
                val signature = sdk.wallet.signSolanaMessage(message)
                solanaSignatureView.text = "Last signature: $signature"
                solanaSignatureStatusView.text = "Signature status: verifying..."
                val valid =
                    sdk.wallet.isValidSolanaMessageSignature(
                        message = message,
                        signature = signature,
                        walletAddress = walletAddress,
                    )
                solanaSignatureStatusView.text =
                    if (valid) "Signature status: signed and verified." else "Signature status: verification failed."
                appendLog("Solana message signed; isValid=$valid")
            }
        }

        solanaSendButton.setOnClickListener {
            launchWalletPanelAction(
                WalletType.Solana,
                label = "Send on Solana Devnet",
                onStart = {
                    solanaTransactionStatusView.text = "Transfer status: preparing relayed transfer..."
                    solanaTransactionSignatureView.text = "Last tx signature: none"
                    solanaOpenExplorerButton.visibility = View.GONE
                    lastSolanaTransactionSignature = null
                },
                onFailure = { solanaTransactionStatusView.text = "Transfer status: ${it.message ?: "send failed"}" },
            ) {
                val wallet = requireActiveWallet(WalletType.Solana)
                val recipient = requireText(solanaRecipientInput, "Recipient")
                val amountText = requireText(solanaAmountInput, "Amount")
                val (asset, decimals) =
                    when (selectedSolanaAsset) {
                        SolanaAsset.Sol -> {
                            SOLANA_NATIVE_ASSET to SOL_DECIMALS
                        }

                        SolanaAsset.Usdc -> {
                            DEVNET_USDC_MINT to USDC_DECIMALS
                        }

                        SolanaAsset.Custom -> {
                            requireText(solanaTokenMintInput, "Token mint") to
                                (
                                    requireText(solanaTokenDecimalsInput, "Token decimals")
                                        .toIntOrNull()
                                        ?.takeIf { it in 0..255 }
                                        ?: error("Token decimals must be between 0 and 255")
                                )
                        }
                    }
                val amount = parseUnits(amountText, decimals)
                require(amount.signum() > 0) { "Amount must be greater than zero" }
                val result =
                    sdk.wallet.sendSolanaTransfer(
                        network = SolanaNetwork.Devnet,
                        asset = asset,
                        to = recipient,
                        amount = amount,
                        statusPolling = TransactionStatusPollingOptions(timeoutMs = 120_000L),
                        selectFeeOption = ::selectFeeOption,
                    )
                lastSolanaTransactionSignature = result.txnHash
                solanaTransactionSignatureView.text = "Last tx signature: ${result.txnHash ?: result.txnId}"
                solanaTransactionStatusView.text =
                    when (result.status) {
                        TransactionStatus.Executed, TransactionStatus.Failed -> "Transfer status: transfer ${result.status}."
                        else -> "Transfer status: Transaction submitted."
                    }
                solanaOpenExplorerButton.visibility = if (result.txnHash == null) View.GONE else View.VISIBLE
                appendLog("Solana transaction ${result.txnId}: status=${result.status} hash=${result.txnHash ?: "pending"}")
                // The transfer already succeeded; a balance refresh failure only updates the balance view.
                runCatching { loadSolanaBalances(wallet) }
                    .onFailure { appendLog("!! Solana balance refresh failed: ${describeThrowable(it)}") }
            }
        }

        solanaOpenExplorerButton.setOnClickListener {
            val signature = lastSolanaTransactionSignature ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SOLANA_EXPLORER_URL/tx/$signature?cluster=devnet")))
        }
    }

    private fun activeWalletOrNull(type: WalletType): Wallet? = sdk.wallet.activeWallet?.takeIf { it.type == type }

    private fun requireActiveWallet(type: WalletType): Wallet =
        activeWalletOrNull(type) ?: error("Select a ${walletTypeLabel(type)} wallet first")

    /** Runs one action at a time per wallet panel, disabling that panel's actions while it is busy. */
    private fun launchWalletPanelAction(
        walletType: WalletType,
        label: String,
        onStart: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        if (!busyWalletPanels.add(walletType)) return
        setWalletPanelEnabled(walletType, enabled = false)
        launchAction(label = label, onStart = onStart, onFailure = onFailure) {
            try {
                action()
            } finally {
                busyWalletPanels.remove(walletType)
                setWalletPanelEnabled(walletType, enabled = true)
            }
        }
    }

    private fun setWalletPanelEnabled(
        walletType: WalletType,
        enabled: Boolean,
    ) {
        val controls =
            when (walletType) {
                WalletType.Solana -> listOf(solanaRefreshBalancesButton, solanaSignMessageButton, solanaSendButton, solanaAssetInput)
                WalletType.Tron -> listOf(tronRefreshBalancesButton, tronSignMessageButton, tronSendButton, tronAssetInput)
                else -> emptyList()
            }
        controls.forEach { it.isEnabled = enabled }
    }

    /** Loads Devnet SOL and USDC balances; shows the indexer's Devnet error instead of zero balances. */
    private suspend fun loadSolanaBalances(wallet: Wallet) {
        solanaBalancesView.text = "Devnet SOL: loading...\nDevnet USDC: loading..."
        try {
            val result =
                sdk.indexer.getSolanaBalances(
                    walletAddress = wallet.address,
                    networks = listOf(SolanaNetwork.Devnet),
                    mintAddresses = listOf(DEVNET_USDC_MINT),
                )
            val networkError = result.errors.firstOrNull { it.network == SolanaNetwork.Devnet }
            if (networkError != null) {
                solanaBalancesView.text = "Balances unavailable: ${networkError.reason}"
                return
            }
            val sol = result.balances.firstOrNull { it is SolanaBalance.Native }
            val usdc =
                result.balances.firstOrNull {
                    it is SolanaBalance.FungibleToken && it.mintAddress == DEVNET_USDC_MINT
                }
            solanaBalancesView.text =
                buildString {
                    appendLine("Devnet SOL: ${sol?.formattedBalance ?: "0"} SOL")
                    append("Devnet USDC: ${usdc?.formattedBalance ?: "0"} USDC")
                }
        } catch (throwable: Throwable) {
            solanaBalancesView.text = "Balances unavailable: ${throwable.message ?: "Unknown error"}"
            throw throwable
        }
    }

    private fun configureSolanaAssetPicker() {
        val assets = SolanaAsset.entries
        solanaAssetInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, assets.map { it.label }))
        solanaAssetInput.setText(selectedSolanaAsset.label, false)
        solanaAssetInput.setOnItemClickListener { _, _, position, _ ->
            val previous = selectedSolanaAsset
            selectedSolanaAsset = assets[position]
            renderSolanaAssetFields()
            if ((previous == SolanaAsset.Sol) != (selectedSolanaAsset == SolanaAsset.Sol)) {
                solanaAmountInput.setText(if (selectedSolanaAsset == SolanaAsset.Sol) "0.001" else "1")
            }
        }
        renderSolanaAssetFields()
    }

    private fun renderSolanaAssetFields() {
        solanaCustomTokenContainer.visibility =
            if (selectedSolanaAsset == SolanaAsset.Custom) View.VISIBLE else View.GONE
        solanaAmountLayout.hint =
            when (selectedSolanaAsset) {
                SolanaAsset.Sol -> "Amount (SOL)"
                SolanaAsset.Usdc -> "Amount (USDC)"
                SolanaAsset.Custom -> "Amount (token units)"
            }
        val tokenAccountHint =
            "WaaS uses the recipient's associated token account when one exists. If one must be " +
                "created, you choose how to pay the required account rent."
        solanaAssetHintView.text =
            when (selectedSolanaAsset) {
                SolanaAsset.Sol -> ""
                SolanaAsset.Usdc -> "USDC mint: $DEVNET_USDC_MINT\n$tokenAccountHint"
                SolanaAsset.Custom -> tokenAccountHint
            }
        solanaAssetHintView.visibility = if (selectedSolanaAsset == SolanaAsset.Sol) View.GONE else View.VISIBLE
    }

    private fun configureWalletTypePicker() {
        val labels = SELECTABLE_WALLET_TYPES.map(::walletTypeLabel)
        walletTypeInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        walletTypeInput.setOnItemClickListener { _, _, position, _ ->
            activateWalletType(SELECTABLE_WALLET_TYPES[position])
        }
    }

    /** Uses the account's first wallet of [type], creating one when none exists. */
    private fun activateWalletType(type: WalletType) {
        if (sdk.wallet.activeWallet?.type == type) return
        launchAction(
            label = "Switch to ${walletTypeLabel(type)} wallet",
            onStart = { authStatusView.text = "Switching to a ${walletTypeLabel(type)} wallet..." },
            onFailure = { sdk.wallet.activeWallet?.let(::renderWalletPanels) },
        ) {
            val existing = sdk.wallet.listWallets().firstOrNull { it.type == type }
            val result =
                if (existing != null) {
                    sdk.wallet.useWallet(existing.id)
                } else {
                    sdk.wallet.createWallet(walletType = type)
                }
            renderSignedInWallet(result.wallet, "${walletTypeLabel(type)} wallet ready")
        }
    }

    private fun configureTronAssetPicker() {
        val assets = TronAsset.entries
        tronAssetInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, assets.map { it.label }))
        tronAssetInput.setText(selectedTronAsset.label, false)
        tronAssetInput.setOnItemClickListener { _, _, position, _ ->
            selectedTronAsset = assets[position]
            tronCustomTokenContainer.visibility =
                if (selectedTronAsset == TronAsset.Custom) View.VISIBLE else View.GONE
            tronAmountLayout.hint =
                when (selectedTronAsset) {
                    TronAsset.Trx -> "Amount (TRX)"
                    TronAsset.Usdt -> "Amount (USDT)"
                    TronAsset.Custom -> "Amount (token units)"
                }
        }
    }

    /** Shows the EVM, Solana, or Tron actions that match the active wallet's stored type. */
    private fun renderWalletPanels(wallet: Wallet) {
        walletTypeInput.setText(walletTypeLabel(wallet.type), false)
        val isEthereum = wallet.type == WalletType.Ethereum
        val isSolana = wallet.type == WalletType.Solana
        val isTron = wallet.type == WalletType.Tron
        networkInputLayout.visibility = if (isEthereum) View.VISIBLE else View.GONE
        evmActionsContainer.visibility = if (isEthereum) View.VISIBLE else View.GONE
        solanaActionsContainer.visibility = if (isSolana) View.VISIBLE else View.GONE
        tronActionsContainer.visibility = if (isTron) View.VISIBLE else View.GONE
        if (isSolana) {
            solanaSignatureView.text = "Last signature: none"
            solanaSignatureStatusView.text = "Signature status: ready to sign."
            solanaTransactionSignatureView.text = "Last tx signature: none"
            solanaTransactionStatusView.text = "Transfer status: ready to send."
            solanaOpenExplorerButton.visibility = View.GONE
            lastSolanaTransactionSignature = null
            launchWalletPanelAction(WalletType.Solana, label = "Refresh Solana balances") { loadSolanaBalances(wallet) }
        }
        if (isTron) {
            tronSignatureView.text = "Last signature: none"
            tronSignatureStatusView.text = "Signature status: ready to sign."
            tronTransactionHashView.text = "Last tx hash: none"
            tronTransactionStatusView.text = "Transfer status: ready to send."
            tronOpenExplorerButton.visibility = View.GONE
            lastTronTransactionHash = null
            launchWalletPanelAction(WalletType.Tron, label = "Refresh Tron balances") { loadTronBalances(wallet) }
        }
    }

    private fun startOidcRedirectSignIn(
        providerName: String,
        provider: OmsRelayOidcProvider,
        loginHint: String? = null,
    ) {
        launchAction(
            label = "Start $providerName redirect sign-in",
            onStart = {
                showOidcRedirectPendingStep("Opening $providerName redirect sign-in...")
            },
            onFailure = {
                showEmailStep()
                authStatusView.text = "$providerName redirect sign-in failed: ${it.message ?: "Unknown error"}"
                appendLog("$providerName redirect start error: ${describeThrowable(it)}")
            },
        ) {
            persistAuthPreferences()
            val walletSelection = currentWalletSelectionBehavior()
            val sessionLifetimeSeconds = requestedSessionLifetimeSeconds()
            val started =
                sdk.wallet.startOidcRedirectAuth(
                    provider = provider,
                    omsRelayReturnUri = DemoConfig.oidcRedirectUri,
                    walletSelection = walletSelection,
                    sessionLifetimeSeconds = sessionLifetimeSeconds,
                    loginHint = loginHint,
                )
            appendLog("$providerName redirect auth started")
            showOidcRedirectPendingStep("Waiting for OIDC redirect callback...")
            openInAppBrowser(started.authorizationUrl)
        }
    }

    private fun openInAppBrowser(url: String) {
        val colorSchemeParams =
            CustomTabColorSchemeParams
                .Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.sand_50))
                .build()
        CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(colorSchemeParams)
            .build()
            .launchUrl(this, Uri.parse(url))
    }

    private fun subscribeSessionExpiry() {
        unsubscribeSessionExpired =
            sdk.wallet.onSessionExpired { event ->
                renderExpiredSession(event)
            }
    }

    private fun handleOidcRedirectCallback(intent: Intent?) {
        val callbackUrl = intent?.data?.toString() ?: return
        launchAction(
            label = "Handle OIDC redirect sign-in callback",
            onStart = {
                showOidcRedirectPendingStep("Completing OIDC redirect sign-in...")
            },
            onFailure = { throwable ->
                consumeIntentData()
                showEmailStep()
                authStatusView.text = "OIDC redirect sign-in failed: ${describeThrowable(throwable)}"
            },
        ) {
            when (
                val result =
                    handleOidcRedirectCallbackFromPendingAuth(callbackUrl)
            ) {
                is OidcRedirectAuthResult.Completed -> {
                    consumeIntentData()
                    when (val completion = result.result) {
                        is CompleteAuthResult.WalletSelected -> {
                            renderSignedInWallet(completion.wallet, "OIDC redirect login complete")
                            appendLog("OIDC redirect sign-in complete: ${completion.wallet.address}")
                        }

                        is CompleteAuthResult.WalletSelection -> {
                            completePendingWalletSelection(
                                pendingSelection = completion.pendingSelection,
                                status = "OIDC redirect login complete",
                            )
                        }
                    }
                }

                OidcRedirectAuthResult.NoPendingAuth -> {
                    consumeIntentData()
                    renderSessionState()
                }

                OidcRedirectAuthResult.NotOidcRedirectCallback -> {
                    renderSessionState()
                }
            }
        }
    }

    private fun consumeIntentData() {
        setIntent(Intent(intent).apply { data = null })
    }

    private fun configureNetworkPicker() {
        val networks =
            listOf(Network.AMOY, Network.POLYGON)
                .filter { network ->
                    OMSWalletNetworks.supportedNetworks.any { it.id == network.id }
                }
        val labels = networks.map(::networkLabel)
        networkInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        networkInput.setText(networkLabel(selectedNetwork), false)
        networkInput.setOnItemClickListener { _, _, position, _ ->
            selectedNetwork = networks[position]
            clearNetworkScopedResults()
            appendLog("Selected network: ${networkLabel(selectedNetwork)}")
        }
    }

    private fun launchAction(
        label: String,
        onStart: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        uiScope.launch {
            appendLog(">> $label")
            onStart?.invoke()
            runCatching {
                action()
            }.onFailure { throwable ->
                onFailure?.invoke(throwable)
                appendLog("!! ${describeThrowable(throwable)}")
            }
        }
    }

    private fun appendLog(message: String) {
        if (message.startsWith("!!")) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
        logView.text =
            buildString {
                append(logView.text)
                append("\n")
                append(message)
            }.trim()
    }

    private suspend fun requestGoogleIdToken(): String {
        val nonce = generateSecureRandomNonce()
        val request =
            GetCredentialRequest
                .Builder()
                .addCredentialOption(
                    GetSignInWithGoogleOption
                        .Builder(
                            serverClientId = DemoConfig.demoGoogleWebClientId,
                        ).setNonce(nonce)
                        .build(),
                ).build()

        val result =
            try {
                credentialManager.getCredential(
                    context = this,
                    request = request,
                )
            } catch (error: GetCredentialException) {
                appendLog("Credential Manager error: ${describeThrowable(error)}")
                throw error
            }
        val credential = result.credential
        require(credential is CustomCredential) {
            "Unexpected credential type: ${credential::class.java.simpleName}"
        }
        require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected Google credential type: ${credential.type}"
        }

        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: GoogleIdTokenParsingException) {
            appendLog("Google ID token parsing error: ${describeThrowable(error)}")
            throw IllegalStateException("Failed to parse Google ID token", error)
        }
    }

    private fun describeThrowable(throwable: Throwable): String =
        buildString {
            if (throwable is OMSWalletException) {
                append("OMSWalletException(")
                append("code=")
                append(throwable.code.id)
                throwable.operation?.let {
                    append(", operation=")
                    append(it.id)
                }
                throwable.status?.let {
                    append(", status=")
                    append(it)
                }
                throwable.txnId?.let {
                    append(", txnId=")
                    append(it)
                }
                append(", retryable=")
                append(throwable.retryable)
                append(", message=")
                append(throwable.message)
                append(")")
                return@buildString
            }
            append(throwable::class.java.simpleName)
            throwable.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
            throwable.cause?.let { cause ->
                append(" | cause=")
                append(cause::class.java.simpleName)
                cause.message?.takeIf { it.isNotBlank() }?.let { message ->
                    append(": ")
                    append(message)
                }
            }
        }

    private fun requireText(
        input: TextInputEditText,
        label: String,
    ): String {
        val value =
            input.text
                ?.toString()
                ?.trim()
                .orEmpty()
        require(value.isNotEmpty()) { "$label is required" }
        return value
    }

    private fun requireEmailForSignIn(): String {
        val typedEmail =
            emailInput.text
                ?.toString()
                ?.trim()
                .orEmpty()
        if (typedEmail.isNotEmpty()) {
            return typedEmail
        }
        val expiredEmail = expiredSessionEmail()
        if (expiredEmail != null) {
            emailInput.setText(expiredEmail)
            emailInput.setSelection(expiredEmail.length)
            return expiredEmail
        }
        error("Email is required")
    }

    private fun expiredSessionEmail(): String? =
        expiredSessionEvent
            ?.session
            ?.auth
            ?.email
            ?.takeIf { it.isNotBlank() }

    private fun clearExpiredSessionState() {
        expiredSessionEvent = null
    }

    private fun addressLabel(
        label: String,
        address: String?,
    ): String = "$label:\n${address ?: "none"}"

    private fun copyWalletAddress() {
        val address = sdk.wallet.activeWallet?.address
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "No wallet address to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Wallet address", address))
        Toast.makeText(this, "Wallet address copied", Toast.LENGTH_SHORT).show()
    }

    private suspend fun completeGoogleIdTokenAuth(idToken: String): CompleteAuthResult {
        val sessionLifetimeSeconds = requestedSessionLifetimeSeconds()
        persistAuthPreferences()
        val walletSelection = currentWalletSelectionBehavior()
        return sdk.wallet.signInWithOidcIdToken(
            idToken = idToken,
            issuer = DemoConfig.googleIssuer,
            audience = DemoConfig.demoGoogleWebClientId,
            walletSelection = walletSelection,
            sessionLifetimeSeconds = sessionLifetimeSeconds ?: WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS,
        )
    }

    private suspend fun handleOidcRedirectCallbackFromPendingAuth(callbackUrl: String): OidcRedirectAuthResult =
        sdk.wallet.handleOidcRedirectCallback(callbackUrl = callbackUrl)

    private fun currentWalletSelectionBehavior(): WalletSelectionBehavior =
        if (manualWalletSelectionCheckbox.isChecked) {
            WalletSelectionBehavior.Manual
        } else {
            WalletSelectionBehavior.Automatic
        }

    private fun restoreAuthPreferences() {
        manualWalletSelectionCheckbox.isChecked =
            authPreferences.getBoolean(AUTH_DEMO_MANUAL_WALLET_SELECTION_KEY, false)
        sessionLifetimeInput.setText(
            authPreferences.getString(
                AUTH_DEMO_SESSION_LIFETIME_SECONDS_KEY,
                AUTH_DEMO_DEFAULT_SESSION_LIFETIME_SECONDS,
            ),
        )
    }

    private fun persistAuthPreferences() {
        val rawValue = currentSessionLifetimeSecondsText()
        parseSessionLifetimeSeconds(rawValue)
        authPreferences
            .edit()
            .putBoolean(AUTH_DEMO_MANUAL_WALLET_SELECTION_KEY, manualWalletSelectionCheckbox.isChecked)
            .apply {
                if (rawValue.isBlank()) {
                    remove(AUTH_DEMO_SESSION_LIFETIME_SECONDS_KEY)
                } else {
                    putString(AUTH_DEMO_SESSION_LIFETIME_SECONDS_KEY, rawValue)
                }
            }.apply()
    }

    private fun requestedSessionLifetimeSeconds(): Long? = parseSessionLifetimeSeconds(currentSessionLifetimeSecondsText())

    private fun currentSessionLifetimeSecondsText(): String =
        sessionLifetimeInput.text
            ?.toString()
            ?.trim()
            .orEmpty()

    private fun parseSessionLifetimeSeconds(rawValue: String): Long? {
        if (rawValue.isBlank()) {
            return null
        }
        val parsed = rawValue.toLongOrNull()
        require(parsed != null && parsed in 1L..WalletClient.MAX_SESSION_LIFETIME_SECONDS) {
            "Session lifetime seconds must be between 1 and ${WalletClient.MAX_SESSION_LIFETIME_SECONDS}"
        }
        return parsed
    }

    private suspend fun completePendingWalletSelection(
        pendingSelection: PendingWalletSelection,
        status: String,
    ) {
        authStatusView.text = "Select a wallet to finish sign-in."
        appendLog(
            "Wallet selection required: type=${pendingSelection.walletType} count=${pendingSelection.wallets.size}",
        )
        val choice =
            try {
                requestWalletSelectionChoice(pendingSelection)
            } catch (throwable: Throwable) {
                runCatching { sdk.wallet.signOut() }
                showEmailStep()
                throw throwable
            }
        val selected =
            when (choice) {
                is ManualWalletChoice.Existing -> pendingSelection.selectWallet(choice.walletId)
                ManualWalletChoice.Create -> pendingSelection.createAndSelectWallet()
            }
        renderSignedInWallet(selected.wallet, status)
    }

    private suspend fun requestWalletSelectionChoice(pendingSelection: PendingWalletSelection): ManualWalletChoice =
        suspendCancellableCoroutine { continuation ->
            var resumed = false

            fun resumeOnce(choice: ManualWalletChoice) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(choice)
                }
            }

            fun cancelOnce() {
                if (!resumed) {
                    resumed = true
                    continuation.resumeWithException(IllegalStateException("Wallet selection cancelled"))
                }
            }

            var dialog: AlertDialog? = null
            val content =
                walletSelectionDialogContent(pendingSelection) { choice ->
                    when (choice) {
                        is ManualWalletChoice.Existing -> appendLog("Selected wallet: ${choice.walletId}")
                        ManualWalletChoice.Create -> appendLog("Creating new ${pendingSelection.walletType} wallet")
                    }
                    resumeOnce(choice)
                    dialog?.dismiss()
                }
            dialog =
                MaterialAlertDialogBuilder(this)
                    .setTitle("Select wallet")
                    .setView(content)
                    .setNegativeButton("Cancel") { _, _ -> cancelOnce() }
                    .setOnCancelListener {
                        appendLog("Wallet selection cancelled")
                        cancelOnce()
                    }.show()
            continuation.invokeOnCancellation { dialog?.dismiss() }
        }

    private fun walletSelectionDialogContent(
        pendingSelection: PendingWalletSelection,
        onChoice: (ManualWalletChoice) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
            addView(walletSelectionSectionLabel("Wallets"))
            if (pendingSelection.wallets.isEmpty()) {
                addWalletSelectionRow(
                    title = "No ${pendingSelection.walletType} wallets",
                    subtitle = "Create a wallet to continue",
                    enabled = false,
                    topMargin = dp(8),
                )
            } else {
                pendingSelection.wallets.forEach { wallet ->
                    addWalletSelectionRow(
                        title = shortWalletAddress(wallet.address),
                        subtitle = walletSelectionSubtitle(wallet),
                        leadingText = walletLeadingText(wallet.type),
                        topMargin = dp(8),
                    ) {
                        onChoice(ManualWalletChoice.Existing(wallet.id))
                    }
                }
            }

            addView(walletSelectionSectionLabel("Create"), verticalLayoutParams(topMargin = dp(18)))
            addWalletSelectionRow(
                title = "Create New Wallet",
                subtitle = "${pendingSelection.walletType} wallet",
                leadingText = "+",
                topMargin = dp(8),
            ) {
                onChoice(ManualWalletChoice.Create)
            }
        }

    private fun LinearLayout.addWalletSelectionRow(
        title: String,
        subtitle: String,
        leadingText: String? = null,
        enabled: Boolean = true,
        topMargin: Int = 0,
        onClick: (() -> Unit)? = null,
    ) {
        addView(
            walletSelectionRow(
                title = title,
                subtitle = subtitle,
                leadingText = leadingText,
                enabled = enabled,
                onClick = onClick,
            ),
            verticalLayoutParams(topMargin = topMargin),
        )
    }

    private fun walletSelectionSectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@AuthDemoActivity, R.color.slate_500))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
        }

    private fun walletSelectionRow(
        title: String,
        subtitle: String,
        leadingText: String?,
        enabled: Boolean,
        onClick: (() -> Unit)?,
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isEnabled = enabled
                isClickable = enabled && onClick != null
                isFocusable = enabled && onClick != null
                background = walletSelectionRowBackground(enabled)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minimumHeight = dp(64)
                alpha = if (enabled) 1f else 0.72f
                onClick?.let { listener -> setOnClickListener { listener() } }
            }

        leadingText?.let {
            row.addView(walletSelectionLeadingText(it))
        }

        val labels =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
        labels.addView(
            TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@AuthDemoActivity, R.color.slate_900))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        labels.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(ContextCompat.getColor(this@AuthDemoActivity, R.color.slate_500))
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(labels, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun walletSelectionLeadingText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@AuthDemoActivity, R.color.slate_900))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = walletSelectionLeadingBackground()
            layoutParams =
                LayoutParams(dp(38), dp(38)).apply {
                    marginEnd = dp(12)
                }
        }

    private fun walletSelectionRowBackground(enabled: Boolean): RippleDrawable {
        val fill =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(ContextCompat.getColor(this@AuthDemoActivity, R.color.surface_700))
                setStroke(dp(1), ContextCompat.getColor(this@AuthDemoActivity, R.color.sand_300))
            }
        val mask =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
            }
        val rippleColor =
            if (enabled) {
                ContextCompat.getColor(this, R.color.sand_200)
            } else {
                Color.TRANSPARENT
            }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), fill, mask)
    }

    private fun walletSelectionLeadingBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(ContextCompat.getColor(this@AuthDemoActivity, R.color.surface_900))
            setStroke(dp(1), ContextCompat.getColor(this@AuthDemoActivity, R.color.sand_300))
        }

    private fun verticalLayoutParams(topMargin: Int = 0): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.topMargin = topMargin
        }

    private fun walletSelectionSubtitle(wallet: Wallet): String =
        listOfNotNull(
            wallet.reference?.takeIf { it.isNotBlank() },
            wallet.type.toString(),
            wallet.id.takeIf { it.isNotBlank() },
        ).joinToString(" / ")

    private fun walletLeadingText(type: WalletType): String =
        when (type) {
            WalletType.Ethereum -> "0x"
            WalletType.Solana -> "SOL"
            WalletType.Tron -> "T"
            WalletType.UNKNOWN_DEFAULT -> "?"
        }

    private fun shortWalletAddress(address: String): String =
        if (address.length > 18) {
            "${address.take(10)}...${address.takeLast(6)}"
        } else {
            address
        }

    private suspend fun selectFeeOption(feeOptions: List<FeeOptionWithBalance>): FeeOptionSelection? {
        if (feeOptions.isEmpty()) return null
        appendLog("Fee options: ${feeOptions.joinToString { feeOptionLabel(it) }}")
        return suspendCancellableCoroutine { continuation ->
            val labels = feeOptions.map(::feeOptionLabel).toTypedArray()
            val firstAvailable = feeOptions.firstOrNull(::hasEnoughBalance)
            var resumed = false

            fun resumeOnce(selection: FeeOptionSelection?) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(selection)
                }
            }

            fun cancelOnce() {
                if (!resumed) {
                    resumed = true
                    continuation.resumeWithException(IllegalStateException("Fee selection cancelled"))
                }
            }
            val builder =
                MaterialAlertDialogBuilder(this)
                    .setTitle("Select fee")
                    .setItems(labels) { _, index ->
                        val selection = feeOptions[index].selection
                        val token = selection.token
                        appendLog("Selected fee token: $token")
                        resumeOnce(selection)
                    }.setOnCancelListener {
                        appendLog("Fee selection cancelled")
                        cancelOnce()
                    }
            firstAvailable?.let { option ->
                builder.setPositiveButton("Select first available") { _, _ ->
                    val selection = option.selection
                    val token = selection.token
                    appendLog("Selected first available fee token: $token")
                    resumeOnce(selection)
                }
            }
            val dialog = builder.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private fun hasEnoughBalance(option: FeeOptionWithBalance): Boolean {
        val balance = option.availableRaw?.toBigIntegerOrNull() ?: return false
        val fee = option.feeOption.value.toBigIntegerOrNull() ?: return false
        return balance >= fee
    }

    private fun clearNetworkScopedResults() {
        lastSignedMessage = null
        lastSignedSignature = null
        lastTransactionHash = null
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: ready to sign."
        lastTransactionHashView.text = "Last transaction hash: none"
        transactionStatusView.text = "Transaction status: ready to send."
        openExplorerButton.visibility = View.GONE
    }

    private fun renderSignedInWallet(
        wallet: Wallet,
        status: String,
    ) {
        clearExpiredSessionState()
        renderSessionStateBox()
        authStatusView.text = status
        walletAddressView.text = addressLabel("Wallet address", wallet.address)
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.GONE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.VISIBLE
        copyWalletAddressButton.visibility = View.VISIBLE
        signatureStatusView.text = "Signature status: ready to sign."
        transactionStatusView.text = "Transaction status: ready to send."
        renderWalletPanels(wallet)
        appendLog("Wallet ready: ${wallet.type.wireValue} ${wallet.address}")
    }

    private fun renderSessionState() {
        val activeWallet = sdk.wallet.activeWallet
        if (activeWallet == null) {
            renderSessionStateBox()
            expiredSessionEvent?.let {
                renderExpiredSession(it)
            } ?: resetUiForNoSession()
            return
        }

        clearExpiredSessionState()
        renderSessionStateBox()
        logoutButton.visibility = View.VISIBLE
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: ready to sign."
        lastTransactionHashView.text = "Last transaction hash: none"
        transactionStatusView.text = "Transaction status: ready to send."
        openExplorerButton.visibility = View.GONE

        authStatusView.text = "Restored persisted wallet session"
        walletAddressView.text = addressLabel("Wallet address", activeWallet.address)
        authCard.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.VISIBLE
        copyWalletAddressButton.visibility = View.VISIBLE
        renderWalletPanels(activeWallet)
    }

    private fun renderExpiredSession(event: OMSWalletSessionExpiredEvent) {
        val isNewEvent = expiredSessionEvent != event
        expiredSessionEvent = event
        renderSessionStateBox()
        prefillExpiredSessionEmail()
        walletAddressView.text = addressLabel("Wallet address", null)
        authStatusView.text =
            buildString {
                append("Wallet session expired. Sign in again")
                event.session.auth.email
                    ?.takeIf { it.isNotBlank() }
                    ?.let { email ->
                        append(" as ")
                        append(email)
                    }
                append(".")
            }
        lastSignedMessage = null
        lastSignedSignature = null
        lastTransactionHash = null
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: waiting for reauth."
        lastTransactionHashView.text = "Last transaction hash: none"
        transactionStatusView.text = "Transaction status: waiting for reauth."
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.GONE
        openExplorerButton.visibility = View.GONE
        copyWalletAddressButton.visibility = View.GONE
        if (isNewEvent) {
            appendLog(
                "Wallet session expired at ${event.expiredAt}: " +
                    "wallet=${event.wallet?.address ?: "none"} email=${event.session.auth.email ?: "none"}",
            )
        }
    }

    private fun resetUiForNoSession() {
        renderSessionStateBox()
        authStatusView.text = "Waiting for sign-in."
        walletAddressView.text = addressLabel("Wallet address", null)
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: waiting for a message."
        lastTransactionHashView.text = "Last transaction hash: none"
        transactionStatusView.text = "Transaction status: waiting to send."
        logoutButton.visibility = View.GONE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.GONE
        openExplorerButton.visibility = View.GONE
        copyWalletAddressButton.visibility = View.GONE
    }

    private fun showEmailStep() {
        renderSessionStateBox()
        authStatusView.text = "Waiting for sign-in."
        logoutButton.visibility = View.GONE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.GONE
        copyWalletAddressButton.visibility = View.GONE
        codeInput.text?.clear()
        emailInput.post {
            emailInput.requestFocus()
            emailInput.setSelection(emailInput.text?.length ?: 0)
        }
    }

    private fun showOidcRedirectPendingStep(status: String) {
        renderSessionStateBox()
        authStatusView.text = status
        walletAddressView.text = addressLabel("Wallet address", null)
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.GONE
        copyWalletAddressButton.visibility = View.GONE
        openExplorerButton.visibility = View.GONE
    }

    private fun showPendingCodeStep() {
        renderSessionStateBox()
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.VISIBLE
        walletActionsContainer.visibility = View.GONE
        copyWalletAddressButton.visibility = View.GONE
        focusCodeInput()
    }

    private fun renderSessionStateBox() {
        val session = sdk.wallet.session
        val expiredEvent = expiredSessionEvent
        sessionStateCard.visibility =
            if (session == null && expiredEvent == null) {
                View.GONE
            } else {
                View.VISIBLE
            }
        sessionStateView.text =
            if (expiredEvent != null && session == null) {
                buildString {
                    appendLine("expiredAt: ${expiredEvent.expiredAt}")
                    appendLine("wallet: ${formatWallet(expiredEvent.wallet)}")
                    appendLine("expiresAt: ${expiredEvent.session.expiresAt}")
                    appendLine("auth: ${formatSessionAuth(expiredEvent.session.auth)}")
                    append("authEmail: ${expiredEvent.session.auth.email ?: "null"}")
                }
            } else {
                buildString {
                    appendLine("wallet: ${formatWallet(sdk.wallet.activeWallet)}")
                    appendLine("expiresAt: ${session?.expiresAt ?: "null"}")
                    appendLine("auth: ${formatSessionAuth(session?.auth)}")
                    append("authEmail: ${session?.auth?.email ?: "null"}")
                }
            }
    }

    private fun formatWallet(wallet: Wallet?): String = wallet?.let { "${it.type.wireValue} ${it.address}" } ?: "null"

    private fun formatSessionAuth(auth: OMSWalletSessionAuth?): String =
        when (auth) {
            null -> "null"
            is OMSWalletEmailSessionAuth -> "Email"
            is OMSWalletOidcSessionAuth -> "${auth.providerLabel ?: auth.provider ?: "OIDC"} (${auth.flow})"
        }

    private fun prefillExpiredSessionEmail() {
        val email = expiredSessionEmail() ?: return
        if (emailInput.text?.isNotBlank() == true) {
            return
        }
        emailInput.setText(email)
        emailInput.setSelection(email.length)
    }

    private fun focusCodeInput() {
        codeInput.post {
            codeInput.requestFocus()
            codeInput.setSelection(codeInput.text?.length ?: 0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private enum class SolanaAsset(
        val label: String,
    ) {
        Sol("SOL"),
        Usdc("USDC (Devnet)"),
        Custom("Custom SPL token"),
    }

    private enum class TronAsset(
        val label: String,
    ) {
        Trx("TRX"),
        Usdt("USDT (Nile)"),
        Custom("Custom TRC-20"),
    }

    private sealed interface ManualWalletChoice {
        data class Existing(
            val walletId: String,
        ) : ManualWalletChoice

        data object Create : ManualWalletChoice
    }

    companion object {
        private const val TAG = "AuthDemoActivity"
        private const val AUTH_DEMO_PREFERENCES_NAME = "oms_wallet_auth_demo_preferences"
        private const val AUTH_DEMO_MANUAL_WALLET_SELECTION_KEY = "manual_wallet_selection"
        private const val AUTH_DEMO_SESSION_LIFETIME_SECONDS_KEY = "session_lifetime_seconds"
        private val AUTH_DEMO_DEFAULT_SESSION_LIFETIME_SECONDS = WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS.toString()
        private val SELECTABLE_WALLET_TYPES = listOf(WalletType.Ethereum, WalletType.Solana, WalletType.Tron)
        private const val SOLANA_NATIVE_ASSET = "SOL"
        private const val DEVNET_USDC_MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        private const val SOLANA_EXPLORER_URL = "https://explorer.solana.com"
        private const val SOLANA_SOL_FAUCET_URL = "https://faucet.solana.com/"
        private const val SOLANA_USDC_FAUCET_URL = "https://faucet.circle.com/"
        private const val SOL_DECIMALS = 9
        private const val USDC_DECIMALS = 6
        private const val NILE_USDT_CONTRACT = "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf"
        private const val NILE_FAUCET_URL = "https://nileex.io/join/getJoinPage"
        private const val TRONSCAN_NILE_URL = "https://nile.tronscan.org/#"
        private const val TRX_DECIMALS = 6
        private const val USDT_DECIMALS = 6

        private fun walletTypeLabel(type: WalletType): String =
            when (type) {
                WalletType.Ethereum -> "Ethereum"
                WalletType.Solana -> "Solana"
                WalletType.Tron -> "Tron"
                WalletType.UNKNOWN_DEFAULT -> type.wireValue
            }

        private fun generateSecureRandomNonce(byteLength: Int = 32): String {
            val randomBytes = ByteArray(byteLength)
            SecureRandom().nextBytes(randomBytes)
            return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        private fun networkLabel(network: Network): String = "${network.name} (${network.id})"

        private fun feeOptionLabel(option: FeeOptionWithBalance): String =
            buildString {
                val feeOption = option.feeOption
                append(feeOption.token.symbol)
                append(" ")
                append(feeOption.displayValue)
                append(" available=")
                append(option.available ?: "unknown")
                option.availableRaw?.let { availableRaw ->
                    append(" available_raw=")
                    append(availableRaw)
                }
                option.decimals?.let { decimals ->
                    append(" decimals=")
                    append(decimals)
                }
                if (feeOption.value.isNotBlank()) {
                    append(" raw=")
                    append(feeOption.value)
                }
            }
    }
}
