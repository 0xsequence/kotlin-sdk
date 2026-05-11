package com.omsclient.kotlin_sdk_samples

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClient
import com.omsclient.kotlin_sdk.generated.waas.Wallet
import com.omsclient.kotlin_sdk.generated.waas.WebRpcError
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.FeeOptionWithBalance
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.utils.parseUnits
import com.omsclient.kotlin_sdk.wallet.OidcProviders
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthResult
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthDemoActivity : AppCompatActivity() {
    private val uiScope = MainScope()
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val sdk by lazy {
        OMSClient(
            context = this,
            projectAccessKey = DemoConfig.demoProjectAccessKey,
            environment = OMSClientEnvironment.demoDefaults(),
        )
    }

    private lateinit var emailInput: TextInputEditText
    private lateinit var codeInput: TextInputEditText
    private lateinit var authStatusView: TextView
    private lateinit var walletAddressView: TextView
    private lateinit var signerAddressView: TextView
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

    private var lastSignedMessage: String? = null
    private var lastSignedSignature: String? = null
    private var lastTransactionHash: String? = null
    private var selectedNetwork: Network = Network.POLYGON_AMOY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth_demo)
        findViewById<View>(R.id.authDemoRoot).applySafeDrawingInsets()
        bindViews()
        populateDefaults()
        bindActions()
        renderSessionState()
        handleOidcRedirectCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOidcRedirectCallback(intent)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        emailInput = findViewById(R.id.emailInput)
        codeInput = findViewById(R.id.codeInput)
        authStatusView = findViewById(R.id.authStatusView)
        walletAddressView = findViewById(R.id.walletAddressView)
        signerAddressView = findViewById(R.id.signerAddressView)
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
    }

    private fun populateDefaults() {
        messageInput.setText("test")
        transactionToInput.setText("0xE5E8B483FfC05967FcFed58cc98D053265af6D99")
        configureNetworkPicker()
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
                val wallet =
                    try {
                        val idToken = requestGoogleIdToken()
                        sdk.signInWithOidcIdToken(
                            idToken = idToken,
                            issuer = DemoConfig.googleIssuer,
                            audience = DemoConfig.demoGoogleWebClientId,
                            selectWallet = { wallets -> wallets.first() },
                        )
                    } catch (throwable: Throwable) {
                        sdk.signOut()
                        throw throwable
                    }
                renderSignedInWallet(wallet, "Google login complete")
                appendLog("Google sign-in complete: ${wallet.address}")
            }
        }

        startGoogleRedirectSignInButton.setOnClickListener {
            launchAction(
                label = "Start Google redirect sign-in",
                onStart = {
                    showGoogleRedirectPendingStep("Opening Google redirect sign-in...")
                },
                onFailure = {
                    showEmailStep()
                    authStatusView.text = "Google redirect sign-in failed: ${it.message ?: "Unknown error"}"
                    appendLog("Google redirect start error: ${describeThrowable(it)}")
                },
            ) {
                val started =
                    sdk.startOidcRedirectAuth(
                        provider =
                            OidcProviders.google(
                                clientId = DemoConfig.demoGoogleWebClientId,
                            ),
                        redirectUri = DemoConfig.oidcRedirectUri,
                    )
                appendLog("Google redirect auth started: state=${started.state}")
                showGoogleRedirectPendingStep("Waiting for Google redirect callback...")
                openInAppBrowser(started.authorizationUrl)
            }
        }

        findViewById<MaterialButton>(R.id.startEmailSignInButton).setOnClickListener {
            launchAction(
                label = "Start email sign-in",
                onStart = { authStatusView.text = "Requesting email code..." },
                onFailure = { authStatusView.text = "Email sign-in failed: ${it.message ?: "Unknown error"}" },
            ) {
                val email = requireText(emailInput, "Email")
                val response = sdk.startEmailAuth(email)
                authStatusView.text =
                    buildString {
                        append("Code requested for ")
                        append(response.loginHint ?: email)
                    }
                signerAddressView.text = addressLabel("Signer address", sdk.session.signerAddress)
                showPendingCodeStep()
                emailInput.text?.clear()
                appendLog("Verifier committed: verifier=${response.verifier}")
            }
        }

        cancelCodeStepButton.setOnClickListener {
            sdk.signOut()
            codeInput.text?.clear()
            showEmailStep()
        }

        findViewById<MaterialButton>(R.id.confirmCodeButton).setOnClickListener {
            launchAction(
                label = "Confirm code and resolve wallet",
                onStart = { authStatusView.text = "Confirming code and resolving wallet..." },
                onFailure = { authStatusView.text = "Code confirmation failed: ${it.message ?: "Unknown error"}" },
            ) {
                val wallet =
                    sdk.completeEmailAuth(
                        code = requireText(codeInput, "Verification code"),
                        selectWallet = { wallets -> wallets.first() },
                    )
                codeInput.text?.clear()
                renderSignedInWallet(wallet, "Email login complete")
            }
        }

        logoutButton.setOnClickListener {
            sdk.signOut()
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
                lastSignedSignature = result.signature
                lastSignatureView.text = "Last signature: ${result.signature}"
                signatureStatusView.text = "Signature status: signed. Ready to verify."
                appendLog("Signed message on chain ${network.chainId}")
            }
        }

        findViewById<MaterialButton>(R.id.verifySignatureButton).setOnClickListener {
            launchAction(
                label = "Verify last signature",
                onStart = { signatureStatusView.text = "Signature status: verification in progress..." },
                onFailure = { signatureStatusView.text = "Signature status: verification failed." },
            ) {
                val result =
                    sdk.utils.verifySignature(
                        network = selectedNetwork,
                        walletAddress = requireNotNull(sdk.wallet.address) { "No wallet selected" },
                        message = requireNotNull(lastSignedMessage) { "No signed message available" },
                        signature = requireNotNull(lastSignedSignature) { "No signature available" },
                    )
                signatureStatusView.text =
                    if (result.isValid) {
                        "Signature status: valid on chain ${selectedNetwork.chainId}."
                    } else {
                        "Signature status: invalid. API status=${result.status}."
                    }
                appendLog("Verify signature => isValid=${result.isValid} status=${result.status}")
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
                lastTransactionHash = result.txHash
                lastTransactionHashView.text = "Last tx hash: ${result.txHash ?: "pending"}"
                transactionStatusView.text = "Transaction status: ${result.status} on chain ${network.chainId}."
                openExplorerButton.visibility = if (result.txHash == null) View.GONE else View.VISIBLE
                appendLog("Transaction ${result.txnId}: status=${result.status} hash=${result.txHash ?: "pending"}")
            }
        }

        openExplorerButton.setOnClickListener {
            val txHash = lastTransactionHash ?: return@setOnClickListener
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(explorerUrlFor(selectedNetwork.chainId, txHash)),
                ),
            )
        }

        copyWalletAddressButton.setOnClickListener {
            copyWalletAddress()
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

    private fun handleOidcRedirectCallback(intent: Intent?) {
        val callbackUrl = intent?.data?.toString() ?: return
        launchAction(
            label = "Handle Google redirect sign-in callback",
            onStart = {
                if (sdk.session.hasPendingOidcRedirectAuth) {
                    showGoogleRedirectPendingStep("Completing Google redirect sign-in...")
                }
            },
        ) {
            when (
                val result =
                    sdk.handleOidcRedirectCallback(
                        callbackUrl = callbackUrl,
                        selectWallet = { wallets -> wallets.first() },
                    )
            ) {
                is OidcRedirectAuthResult.Completed -> {
                    consumeIntentData()
                    renderSignedInWallet(result.wallet, "Google redirect login complete")
                    appendLog("Google redirect sign-in complete: ${result.wallet.address}")
                }

                is OidcRedirectAuthResult.Failed -> {
                    consumeIntentData()
                    showEmailStep()
                    authStatusView.text = "Google redirect completion failed: ${result.error.message ?: "Unknown error"}"
                    appendLog("Google redirect completion error: ${describeThrowable(result.error)}")
                }

                OidcRedirectAuthResult.NoPendingAuth -> {
                    consumeIntentData()
                    renderSessionState()
                }

                OidcRedirectAuthResult.NotOidcRedirectCallback -> {
                    Unit
                }
            }
        }
    }

    private fun consumeIntentData() {
        setIntent(Intent(intent).apply { data = null })
    }

    private fun configureNetworkPicker() {
        val networks =
            listOf(Network.POLYGON_AMOY, Network.POLYGON)
                .filter { network -> sdk.supportedNetworks.any { it.chainId == network.chainId } }
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
            if (throwable is WebRpcError) {
                append("WebRpcError(")
                append("error=")
                append(throwable.error)
                append(", code=")
                append(throwable.code)
                append(", status=")
                append(throwable.status)
                append(", kind=")
                append(throwable.errorKind.name)
                append(", message=")
                append(throwable.message)
                if (throwable.causeString.isNotBlank()) {
                    append(", cause=")
                    append(throwable.causeString)
                }
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

    private fun addressLabel(
        label: String,
        address: String?,
    ): String = "$label:\n${address ?: "none"}"

    private fun copyWalletAddress() {
        val address = sdk.session.walletAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "No wallet address to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Wallet address", address))
        Toast.makeText(this, "Wallet address copied", Toast.LENGTH_SHORT).show()
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
                        val token = feeOptions[index].feeOption.token.symbol
                        appendLog("Selected fee token: $token")
                        resumeOnce(FeeOptionSelection(token = token))
                    }.setOnCancelListener {
                        appendLog("Fee selection cancelled")
                        cancelOnce()
                    }
            firstAvailable?.let { option ->
                builder.setPositiveButton("Select first available") { _, _ ->
                    val token = option.feeOption.token.symbol
                    appendLog("Selected first available fee token: $token")
                    resumeOnce(FeeOptionSelection(token = token))
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
        lastTransactionHashView.text = "Last tx hash: none"
        transactionStatusView.text = "Transaction status: ready to send."
        openExplorerButton.visibility = View.GONE
    }

    private fun renderSignedInWallet(
        wallet: Wallet,
        status: String,
    ) {
        authStatusView.text = status
        walletAddressView.text = addressLabel("Wallet address", wallet.address)
        signerAddressView.text = addressLabel("Signer address", sdk.session.signerAddress)
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.GONE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.VISIBLE
        copyWalletAddressButton.visibility = View.VISIBLE
        signatureStatusView.text = "Signature status: ready to sign."
        transactionStatusView.text = "Transaction status: ready to send."
        appendLog("Wallet ready: ${wallet.address}")
    }

    private fun renderSessionState() {
        if (sdk.session.signerAddress == null && sdk.session.walletAddress == null) {
            if (sdk.session.hasPendingOidcRedirectAuth) {
                showGoogleRedirectPendingStep("Waiting for Google redirect callback...")
                return
            }
            resetUiForNoSession()
            return
        }

        logoutButton.visibility = View.VISIBLE
        signerAddressView.text = addressLabel("Signer address", sdk.session.signerAddress)
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: ready to sign."
        lastTransactionHashView.text = "Last tx hash: none"
        transactionStatusView.text = "Transaction status: ready to send."
        openExplorerButton.visibility = View.GONE

        if (sdk.session.hasPendingSignIn) {
            if (sdk.session.hasPendingOidcRedirectAuth) {
                showGoogleRedirectPendingStep("Waiting for Google redirect callback...")
                return
            }
            authStatusView.text = "Pending sign-in verification"
            walletAddressView.text = addressLabel("Wallet address", null)
            authCard.visibility = View.VISIBLE
            emailStepContainer.visibility = View.GONE
            codeStepContainer.visibility = View.VISIBLE
            walletActionsContainer.visibility = View.GONE
            copyWalletAddressButton.visibility = View.GONE
            focusCodeInput()
            return
        }

        authStatusView.text = "Restored persisted wallet session"
        walletAddressView.text = addressLabel("Wallet address", sdk.session.walletAddress)
        authCard.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.VISIBLE
        copyWalletAddressButton.visibility = View.VISIBLE
    }

    private fun resetUiForNoSession() {
        authStatusView.text = "Waiting for sign-in."
        walletAddressView.text = addressLabel("Wallet address", null)
        signerAddressView.text = addressLabel("Signer address", null)
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: waiting for a message."
        lastTransactionHashView.text = "Last tx hash: none"
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
        authStatusView.text = "Waiting for sign-in."
        signerAddressView.text = addressLabel("Signer address", null)
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

    private fun showGoogleRedirectPendingStep(status: String) {
        authStatusView.text = status
        signerAddressView.text = addressLabel("Signer address", sdk.session.signerAddress)
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
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.VISIBLE
        walletActionsContainer.visibility = View.GONE
        copyWalletAddressButton.visibility = View.GONE
        focusCodeInput()
    }

    private fun focusCodeInput() {
        codeInput.post {
            codeInput.requestFocus()
            codeInput.setSelection(codeInput.text?.length ?: 0)
        }
    }

    companion object {
        private const val TAG = "AuthDemoActivity"

        private fun generateSecureRandomNonce(byteLength: Int = 32): String {
            val randomBytes = ByteArray(byteLength)
            SecureRandom().nextBytes(randomBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        }

        private fun explorerUrlFor(
            chainId: String,
            txHash: String,
        ): String =
            when (chainId) {
                "80002" -> "https://amoy.polygonscan.com/tx/$txHash"
                "137" -> "https://polygonscan.com/tx/$txHash"
                else -> "https://amoy.polygonscan.com/tx/$txHash"
            }

        private fun networkLabel(network: Network): String = "${network.displayName} (${network.chainId})"

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
