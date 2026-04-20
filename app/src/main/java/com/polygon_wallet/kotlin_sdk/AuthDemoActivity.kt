package com.polygon_wallet.kotlin_sdk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.polygon_wallet.polygon_kotlin_sdk.PolygonSdk
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WebRpcError
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.Wallet
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64

class AuthDemoActivity : AppCompatActivity() {
    private val uiScope = MainScope()
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val sdk by lazy {
        PolygonSdk(
            context = this,
            projectAccessKey = DemoConfig.demoProjectAccessKey,
            environment = SequenceEnvironment.demoDefaults(),
        )
    }

    private lateinit var emailInput: TextInputEditText
    private lateinit var codeInput: TextInputEditText
    private lateinit var authStatusView: TextView
    private lateinit var walletAddressView: TextView
    private lateinit var signerAddressView: TextView
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
    private lateinit var cancelCodeStepButton: MaterialButton
    private lateinit var startGoogleSignInButton: MaterialButton

    private var lastSignedMessage: String? = null
    private var lastSignedSignature: String? = null
    private var lastTransactionHash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth_demo)
        findViewById<View>(R.id.authDemoRoot).applySafeDrawingInsets()
        bindViews()
        populateDefaults()
        bindActions()
        renderSessionState()
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
        cancelCodeStepButton = findViewById(R.id.cancelCodeStepButton)
        startGoogleSignInButton = findViewById(R.id.startGoogleSignInButton)
    }

    private fun populateDefaults() {
        messageInput.setText("test")
        transactionToInput.setText("0xE5E8B483FfC05967FcFed58cc98D053265af6D99")
        transactionValueInput.setText("0")
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
                val wallet = try {
                    val idToken = requestGoogleIdToken()
                    sdk.wallet.signInWithOidcIdToken(
                        idToken = idToken,
                        issuer = DemoConfig.googleIssuer,
                        audience = DemoConfig.demoGoogleWebClientId,
                        selectWallet = { wallets -> wallets.first() },
                    )
                } catch (throwable: Throwable) {
                    sdk.wallet.clearSession()
                    throw throwable
                }
                renderSignedInWallet(wallet, "Google login complete")
                appendLog("Google sign-in complete: ${wallet.address}")
            }
        }

        findViewById<MaterialButton>(R.id.startEmailSignInButton).setOnClickListener {
            launchAction(
                label = "Start email sign-in",
                onStart = { authStatusView.text = "Requesting email code..." },
                onFailure = { authStatusView.text = "Email sign-in failed: ${it.message ?: "Unknown error"}" },
            ) {
                val response = sdk.wallet.signInWithEmail(requireText(emailInput, "Email"))
                authStatusView.text = buildString {
                    append("Code requested for ")
                    append(response.loginHint ?: requireText(emailInput, "Email"))
                }
                signerAddressView.text = "Signer address: ${sdk.wallet.signerAddress ?: "none"}"
                showPendingCodeStep()
                appendLog("Verifier committed: verifier=${response.verifier}")
            }
        }

        cancelCodeStepButton.setOnClickListener {
            sdk.wallet.clearSession()
            codeInput.text?.clear()
            showEmailStep()
        }

        findViewById<MaterialButton>(R.id.confirmCodeButton).setOnClickListener {
            launchAction(
                label = "Confirm code and resolve wallet",
                onStart = { authStatusView.text = "Confirming code and resolving wallet..." },
                onFailure = { authStatusView.text = "Code confirmation failed: ${it.message ?: "Unknown error"}" },
            ) {
                val wallet = sdk.wallet.completeEmailSignIn(
                    code = requireText(codeInput, "Verification code"),
                    selectWallet = { wallets -> wallets.first() },
                )
                renderSignedInWallet(wallet, "Email login complete")
            }
        }

        logoutButton.setOnClickListener {
            sdk.wallet.clearSession()
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
                val result = sdk.wallet.signMessage(
                    chainId = MESSAGE_CHAIN_ID,
                    message = message,
                )
                lastSignedMessage = message
                lastSignedSignature = result.signature
                lastSignatureView.text = "Last signature: ${result.signature}"
                signatureStatusView.text = "Signature status: signed. Ready to verify."
                appendLog("Signed message on chain $MESSAGE_CHAIN_ID")
            }
        }

        findViewById<MaterialButton>(R.id.verifySignatureButton).setOnClickListener {
            launchAction(
                label = "Verify last signature",
                onStart = { signatureStatusView.text = "Signature status: verification in progress..." },
                onFailure = { signatureStatusView.text = "Signature status: verification failed." },
            ) {
                val result = sdk.utils.verifySignature(
                    chainId = MESSAGE_CHAIN_ID,
                    walletAddress = requireNotNull(sdk.wallet.walletAddress) { "No wallet selected" },
                    message = requireNotNull(lastSignedMessage) { "No signed message available" },
                    signature = requireNotNull(lastSignedSignature) { "No signature available" },
                )
                signatureStatusView.text = if (result.isValid) {
                    "Signature status: valid on chain $MESSAGE_CHAIN_ID."
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
                val result = sdk.wallet.sendTransaction(
                    chainId = MESSAGE_CHAIN_ID,
                    to = requireText(transactionToInput, "Transaction destination"),
                    value = requireText(transactionValueInput, "Transaction value"),
                )
                lastTransactionHash = result.txHash
                lastTransactionHashView.text = "Last tx hash: ${result.txHash}"
                transactionStatusView.text = "Transaction status: submitted on chain $MESSAGE_CHAIN_ID."
                openExplorerButton.visibility = View.VISIBLE
                appendLog("Transaction hash: ${result.txHash}")
            }
        }

        openExplorerButton.setOnClickListener {
            val txHash = lastTransactionHash ?: return@setOnClickListener
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(explorerUrlFor(MESSAGE_CHAIN_ID, txHash)),
                ),
            )
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
                appendLog("!! ${throwable.message ?: throwable::class.java.simpleName}")
            }
        }
    }

    private fun appendLog(message: String) {
        if (message.startsWith("!!")) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
        logView.text = buildString {
            append(logView.text)
            append("\n")
            append(message)
        }.trim()
    }

    private suspend fun requestGoogleIdToken(): String {
        val nonce = generateSecureRandomNonce()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(
                    serverClientId = DemoConfig.demoGoogleWebClientId,
                )
                    .setNonce(nonce)
                    .build(),
            )
            .build()

        val result = try {
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

    private fun describeThrowable(throwable: Throwable): String = buildString {
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

    private fun requireText(input: TextInputEditText, label: String): String {
        val value = input.text?.toString()?.trim().orEmpty()
        require(value.isNotEmpty()) { "$label is required" }
        return value
    }

    private fun renderSignedInWallet(
        wallet: Wallet,
        status: String,
    ) {
        authStatusView.text = status
        walletAddressView.text = "Wallet address: ${wallet.address}"
        signerAddressView.text = "Signer address: ${sdk.wallet.signerAddress ?: "none"}"
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.GONE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.VISIBLE
        signatureStatusView.text = "Signature status: ready to sign."
        transactionStatusView.text = "Transaction status: ready to send."
        appendLog("Wallet ready: ${wallet.address}")
    }

    private fun renderSessionState() {
        if (sdk.wallet.signerAddress == null && sdk.wallet.walletAddress == null) {
            resetUiForNoSession()
            return
        }

        logoutButton.visibility = View.VISIBLE
        signerAddressView.text = "Signer address: ${sdk.wallet.signerAddress ?: "none"}"
        lastSignatureView.text = "Last signature: none"
        signatureStatusView.text = "Signature status: ready to sign."
        lastTransactionHashView.text = "Last tx hash: none"
        transactionStatusView.text = "Transaction status: ready to send."
        openExplorerButton.visibility = View.GONE

        if (sdk.wallet.hasPendingSignIn) {
            authStatusView.text = "Pending sign-in verification"
            walletAddressView.text = "Wallet address: pending"
            authCard.visibility = View.VISIBLE
            emailStepContainer.visibility = View.GONE
            codeStepContainer.visibility = View.VISIBLE
            walletActionsContainer.visibility = View.GONE
            focusCodeInput()
            return
        }

        authStatusView.text = "Restored persisted wallet session"
        walletAddressView.text = "Wallet address: ${sdk.wallet.walletAddress}"
        authCard.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.VISIBLE
    }

    private fun resetUiForNoSession() {
        authStatusView.text = "Waiting for sign-in."
        walletAddressView.text = "Wallet address: pending"
        signerAddressView.text = "Signer address: none"
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
    }

    private fun showEmailStep() {
        authStatusView.text = "Waiting for sign-in."
        signerAddressView.text = "Signer address: none"
        logoutButton.visibility = View.GONE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletActionsContainer.visibility = View.GONE
        codeInput.text?.clear()
        emailInput.post {
            emailInput.requestFocus()
            emailInput.setSelection(emailInput.text?.length ?: 0)
        }
    }

    private fun showPendingCodeStep() {
        logoutButton.visibility = View.VISIBLE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.VISIBLE
        walletActionsContainer.visibility = View.GONE
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
        private const val MESSAGE_CHAIN_ID = "80002"

        private fun generateSecureRandomNonce(byteLength: Int = 32): String {
            val randomBytes = ByteArray(byteLength)
            SecureRandom().nextBytes(randomBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        }

        private fun explorerUrlFor(chainId: String, txHash: String): String = when (chainId) {
            "80002" -> "https://amoy.polygonscan.com/tx/$txHash"
            "137" -> "https://polygonscan.com/tx/$txHash"
            else -> "https://amoy.polygonscan.com/tx/$txHash"
        }
    }
}
