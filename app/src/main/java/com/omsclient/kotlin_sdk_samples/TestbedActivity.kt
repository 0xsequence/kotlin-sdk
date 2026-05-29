package com.omsclient.kotlin_sdk_samples

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClient
import com.omsclient.kotlin_sdk.findNetworkById
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.utils.parseUnits
import com.omsclient.kotlin_sdk.wallet.CompleteAuthResult
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TestbedActivity : AppCompatActivity() {
    private val uiScope = MainScope()

    private lateinit var publishableKeyInput: TextInputEditText
    private lateinit var projectIdInput: TextInputEditText
    private lateinit var walletApiUrlInput: TextInputEditText
    private lateinit var apiRpcUrlInput: TextInputEditText
    private lateinit var indexerUrlTemplateInput: TextInputEditText
    private lateinit var balancesChainIdInput: TextInputEditText
    private lateinit var contractAddressInput: TextInputEditText
    private lateinit var balancesWalletAddressInput: TextInputEditText
    private lateinit var emailInput: TextInputEditText
    private lateinit var codeInput: TextInputEditText
    private lateinit var messageChainIdInput: TextInputEditText
    private lateinit var messageInput: TextInputEditText
    private lateinit var transactionToInput: TextInputEditText
    private lateinit var transactionValueInput: TextInputEditText
    private lateinit var currentWalletView: TextView
    private lateinit var lastSignatureView: TextView
    private lateinit var logView: TextView

    private var runtime: DemoRuntime? = null
    private var lastSignedMessage: String? = null
    private var lastSignedSignature: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.testbedRoot).applySafeDrawingInsets()
        bindViews()
        populateDefaults()
        bindActions()
        renderSession()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        publishableKeyInput = findViewById(R.id.publishableKeyInput)
        projectIdInput = findViewById(R.id.projectIdInput)
        walletApiUrlInput = findViewById(R.id.walletApiUrlInput)
        apiRpcUrlInput = findViewById(R.id.apiRpcUrlInput)
        indexerUrlTemplateInput = findViewById(R.id.indexerUrlTemplateInput)
        balancesChainIdInput = findViewById(R.id.balancesChainIdInput)
        contractAddressInput = findViewById(R.id.contractAddressInput)
        balancesWalletAddressInput = findViewById(R.id.balancesWalletAddressInput)
        emailInput = findViewById(R.id.emailInput)
        codeInput = findViewById(R.id.codeInput)
        messageChainIdInput = findViewById(R.id.messageChainIdInput)
        messageInput = findViewById(R.id.messageInput)
        transactionToInput = findViewById(R.id.transactionToInput)
        transactionValueInput = findViewById(R.id.transactionValueInput)
        currentWalletView = findViewById(R.id.currentWalletView)
        lastSignatureView = findViewById(R.id.lastSignatureView)
        logView = findViewById(R.id.logView)
    }

    private fun populateDefaults() {
        val demoEnvironment = OMSClientEnvironment.demoDefaults()
        publishableKeyInput.setText(DemoConfig.demoPublishableKey)
        projectIdInput.setText(DemoConfig.demoProjectId)
        walletApiUrlInput.setText(demoEnvironment.walletApiUrl)
        apiRpcUrlInput.setText(demoEnvironment.apiRpcUrl)
        indexerUrlTemplateInput.setText(demoEnvironment.indexerUrlTemplate)

        balancesChainIdInput.setText("137")
        contractAddressInput.setText("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359")
        balancesWalletAddressInput.setText("0x8e3E38fe7367dd3b52D1e281E4e8400447C8d8B9")
        messageChainIdInput.setText("80002")
        messageInput.setText("test")
        transactionToInput.setText("0xE5E8B483FfC05967FcFed58cc98D053265af6D99")
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.loadBalancesButton).setOnClickListener {
            launchAction("Load token balances") { sdk ->
                val balances =
                    sdk.indexer.getTokenBalances(
                        network = requireNetwork(balancesChainIdInput, "Balances chain ID"),
                        contractAddress = requireText(contractAddressInput, "Contract address"),
                        walletAddress = requireText(balancesWalletAddressInput, "Balances wallet address"),
                        includeMetadata = true,
                    )
                appendLog("Balances status=${balances.status} count=${balances.balances.size}")
                balances.balances.take(3).forEachIndexed { index, balance ->
                    appendLog(
                        "  [$index] ${balance.contractType ?: "?"} ${balance.balance ?: "?"} @ ${balance.contractAddress ?: "?"}",
                    )
                }
            }
        }

        findViewById<MaterialButton>(R.id.signInButton).setOnClickListener {
            launchAction("Start email sign-in") { sdk ->
                val email = requireText(emailInput, "Email")
                sdk.wallet.startEmailAuth(email)
                appendLog("Email verifier committed for $email")
                renderSession()
            }
        }

        findViewById<MaterialButton>(R.id.confirmCodeButton).setOnClickListener {
            launchAction("Confirm email code and resolve wallet") { sdk ->
                val result = sdk.wallet.completeEmailAuth(code = requireText(codeInput, "Verification code"))
                val wallet =
                    when (result) {
                        is CompleteAuthResult.WalletSelected -> result.wallet
                        is CompleteAuthResult.WalletSelection -> error("Manual wallet selection is not available in testbed")
                    }
                balancesWalletAddressInput.setText(wallet.address)
                appendLog(buildAuthSummary(wallet.address))
                renderSession()
            }
        }

        findViewById<MaterialButton>(R.id.signMessageButton).setOnClickListener {
            launchAction("Sign message") { sdk ->
                val network = requireNetwork(messageChainIdInput, "Message chain ID")
                val message = requireText(messageInput, "Message")
                val result = sdk.wallet.signMessage(network = network, message = message)
                lastSignedMessage = message
                lastSignedSignature = result
                lastSignatureView.text = "Last signature: $result"
                appendLog("Signature for '$message': $result")
                renderSession()
            }
        }

        findViewById<MaterialButton>(R.id.verifySignatureButton).setOnClickListener {
            launchAction("Verify last signature") { sdk ->
                val network = requireNetwork(messageChainIdInput, "Message chain ID")
                val result =
                    sdk.wallet.isValidMessageSignature(
                        network = network,
                        message = requireNotNull(lastSignedMessage) { "No signed message available" },
                        signature = requireNotNull(lastSignedSignature) { "No signature available" },
                    )
                appendLog("Verify signature => isValid=$result")
            }
        }

        findViewById<MaterialButton>(R.id.sendTransactionButton).setOnClickListener {
            launchAction("Send transaction") { sdk ->
                val result =
                    sdk.wallet.sendTransaction(
                        network = requireNetwork(messageChainIdInput, "Message chain ID"),
                        to = transactionToInput.text.toString().trim(),
                        value = parseUnits(transactionValueInput.text.toString(), 18),
                    )
                appendLog("Transaction ${result.txnId}: status=${result.status} hash=${result.txnHash ?: "pending"}")
            }
        }
    }

    private fun launchAction(
        label: String,
        action: suspend (OMSClient) -> Unit,
    ) {
        uiScope.launch {
            appendLog(">> $label")
            runCatching {
                action(requireSdk())
            }.onFailure { throwable ->
                appendLog("!! ${throwable.message ?: throwable::class.java.simpleName}")
            }
        }
    }

    private fun requireSdk(): OMSClient {
        val publishableKey = currentPublishableKey()
        val projectId = currentProjectId()
        val environment = currentEnvironment()
        val existing = runtime
        if (existing?.publishableKey != publishableKey || existing.projectId != projectId || existing.environment != environment) {
            runtime =
                DemoRuntime(
                    publishableKey = publishableKey,
                    projectId = projectId,
                    environment = environment,
                    sdk =
                        OMSClient(
                            context = this,
                            publishableKey = publishableKey,
                            projectId = projectId,
                            environment = environment,
                        ),
                )
            lastSignedMessage = null
            lastSignedSignature = null
            lastSignatureView.text = "Last signature: none"
            appendLog("Environment applied.")
        }
        return requireNotNull(runtime).sdk
    }

    private fun currentPublishableKey(): String = requireText(publishableKeyInput, "Publishable key")

    private fun currentProjectId(): String = requireText(projectIdInput, "Project ID")

    private fun currentEnvironment(): OMSClientEnvironment =
        OMSClientEnvironment(
            walletApiUrl = requireText(walletApiUrlInput, "Wallet RPC URL"),
            apiRpcUrl = requireText(apiRpcUrlInput, "API RPC URL"),
            indexerUrlTemplate = requireText(indexerUrlTemplateInput, "Indexer URL template"),
        )

    private fun renderSession() {
        val sdk = runtime?.sdk
        if (sdk?.session?.walletAddress == null) {
            currentWalletView.text = "Wallet: none"
            return
        }

        currentWalletView.text = "Wallet: ${sdk.session.walletAddress}"
    }

    private fun appendLog(message: String) {
        logView.text =
            buildString {
                append(logView.text)
                append("\n")
                append(message)
            }.trim()
    }

    private fun buildAuthSummary(resolvedWalletAddress: String): String =
        buildString {
            append("Auth confirmed")
            append(" selected=$resolvedWalletAddress")
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

    private fun requireNetwork(
        input: TextInputEditText,
        label: String,
    ): Network {
        val chainId = requireText(input, label).toIntOrNull()
        requireNotNull(chainId) { "$label must be a numeric chain id" }
        return requireNotNull(findNetworkById(chainId)) { "$label is not supported: $chainId" }
    }

    private data class DemoRuntime(
        val publishableKey: String,
        val projectId: String,
        val environment: OMSClientEnvironment,
        val sdk: OMSClient,
    )
}
