package com.omsclient.kotlin_sdk_trails_actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OMSClient
import com.omsclient.kotlin_sdk.OMSClientSessionExpiredEvent
import com.omsclient.kotlin_sdk.models.FeeOptionSelection
import com.omsclient.kotlin_sdk.models.FeeOptionWithBalance
import com.omsclient.kotlin_sdk.models.SendTransactionRequest
import com.omsclient.kotlin_sdk.models.SendTransactionResponse
import com.omsclient.kotlin_sdk.models.TransactionStatus
import com.omsclient.kotlin_sdk.models.Wallet
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.utils.formatUnits
import com.omsclient.kotlin_sdk.utils.parseUnits
import com.omsclient.kotlin_sdk.wallet.CompleteAuthResult
import com.omsclient.kotlin_sdk.wallet.OidcProviders
import com.omsclient.kotlin_sdk.wallet.OidcRedirectAuthResult
import com.omsclient.kotlin_sdk.wallet.PendingWalletSelection
import com.omsclient.kotlin_sdk.wallet.WalletClient
import com.omsclient.kotlin_sdk.wallet.WalletSelectionBehavior
import com.omsclient.kotlin_sdk_trails_actions.generated.CommitIntentRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.CreateYieldActionRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.ExecuteIntentRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.FundMethod
import com.omsclient.kotlin_sdk_trails_actions.generated.GetYieldAggregateBalancesRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.GetYieldMarketsRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.IntentMode
import com.omsclient.kotlin_sdk_trails_actions.generated.IntentStatus
import com.omsclient.kotlin_sdk_trails_actions.generated.OkHttpWebRpcTransport
import com.omsclient.kotlin_sdk_trails_actions.generated.QuoteIntentRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.QuoteIntentRequestOptions
import com.omsclient.kotlin_sdk_trails_actions.generated.RouteProvider
import com.omsclient.kotlin_sdk_trails_actions.generated.TradeType
import com.omsclient.kotlin_sdk_trails_actions.generated.TrailsApiTrailsClient
import com.omsclient.kotlin_sdk_trails_actions.generated.WaitIntentReceiptRequest
import com.omsclient.kotlin_sdk_trails_actions.generated.WebRpcError
import com.omsclient.kotlin_sdk_trails_actions.generated.WebRpcTransportException
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldActionArguments
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldBalance
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldBalanceQuery
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldBalances
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldMarket
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldRewardRate
import com.omsclient.kotlin_sdk_trails_actions.generated.YieldTransaction
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TrailsActionsActivity : AppCompatActivity() {
    private val uiScope = MainScope()
    private val authPreferences by lazy {
        getSharedPreferences(TRAILS_ACTIONS_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val sdk by lazy {
        OMSClient(
            context = this,
            publishableKey = DemoConfig.demoPublishableKey,
            projectId = DemoConfig.demoProjectId,
            environment = OMSClientEnvironment.demoDefaults(),
        )
    }
    private val trailsClient =
        TrailsApiTrailsClient(
            baseUrl = TRAILS_API_URL,
            transport = OkHttpWebRpcTransport(),
            headers = {
                mapOf(TRAILS_ACCESS_KEY_HEADER to TRAILS_ACCESS_KEY)
            },
        )

    private lateinit var root: LinearLayout
    private lateinit var authStatusView: TextView
    private lateinit var sessionStateCard: View
    private lateinit var sessionStateView: TextView
    private lateinit var walletView: TextView
    private lateinit var balancesView: TextView
    private lateinit var balancesStatusView: TextView
    private lateinit var positionsView: TextView
    private lateinit var positionsStatusView: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var swapStatusView: TextView
    private lateinit var depositStatusView: TextView
    private lateinit var earnStatusView: TextView
    private lateinit var lastTransactionView: TextView
    private lateinit var logView: TextView
    private lateinit var emailInput: TextInputEditText
    private lateinit var codeInput: TextInputEditText
    private lateinit var sessionLifetimeInput: TextInputEditText
    private lateinit var swapAmountInput: TextInputEditText
    private lateinit var depositAmountInput: TextInputEditText
    private lateinit var earnAmountInput: TextInputEditText
    private lateinit var manualWalletSelectionCheckbox: MaterialCheckBox
    private lateinit var authCard: View
    private lateinit var emailStepContainer: View
    private lateinit var codeStepContainer: View
    private lateinit var walletCard: View
    private lateinit var balancesCard: View
    private lateinit var actionsCard: View
    private lateinit var positionsCard: View
    private lateinit var signOutButton: MaterialButton
    private lateinit var startGoogleRedirectSignInButton: MaterialButton
    private lateinit var startEmailSignInButton: MaterialButton
    private lateinit var confirmCodeButton: MaterialButton
    private lateinit var cancelCodeStepButton: MaterialButton
    private lateinit var prepareSwapButton: MaterialButton
    private lateinit var sendSwapButton: MaterialButton
    private lateinit var prepareDepositButton: MaterialButton
    private lateinit var sendDepositButton: MaterialButton
    private lateinit var prepareEarnButton: MaterialButton
    private lateinit var sendEarnButton: MaterialButton
    private lateinit var openExplorerButton: MaterialButton

    private var balances = BalanceState.signedOut
    private var earnPositions: List<EarnPosition> = emptyList()
    private val withdrawStatusesByPosition = mutableMapOf<String, String>()
    private val lastWithdrawTransactionHashes = mutableMapOf<String, String>()
    private var preparedSwap: PreparedSwapTransaction? = null
    private var preparedDeposit: PreparedYieldTransactions? = null
    private var preparedEarn: PreparedSwapAndDepositPlan? = null
    private var selectedFeeOption: FeeOptionWithBalance? = null
    private var lastTransactionHash: String? = null
    private var expiredSessionEvent: OMSClientSessionExpiredEvent? = null
    private var unsubscribeSessionExpired: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        bindActions()
        restoreAuthPreferences()
        subscribeSessionExpiry()
        renderSessionState()
        if (sdk.session.walletAddress != null) {
            refreshSignedInData()
        }
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

    private fun buildUi() {
        val scrollView =
            ScrollView(this).apply {
                setBackgroundColor(color(R.color.surface_900))
                isFillViewport = true
            }
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(28))
            }
        scrollView.addView(root, matchWrap())
        setContentView(scrollView)

        val header =
            horizontal {
                addView(
                    text("Trails Actions Demo", 30f, R.color.slate_900, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                signOutButton = button("Logout", outline = true)
                addView(signOutButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        root.addView(header, matchWrap())

        sessionStateCard =
            card {
                addView(authDemoSectionTitle("OMSClientSessionState"))
                sessionStateView = monospaceBody("")
                addView(sessionStateView, matchWrap(topMargin = 10))
            }
        root.addView(sessionStateCard, matchWrap(topMargin = 18))

        authCard =
            card {
                addView(sectionTitle("Sign-In"))
                authStatusView = body("Enter an email to start.")
                addView(authStatusView, matchWrap(topMargin = 8))
                manualWalletSelectionCheckbox =
                    MaterialCheckBox(this@TrailsActionsActivity).apply {
                        text = "Use manual wallet selection"
                        setTextColor(color(R.color.slate_900))
                        textSize = 14f
                    }
                addView(manualWalletSelectionCheckbox, matchWrap(topMargin = 12))
                addView(fieldLabel("Session lifetime"), matchWrap(topMargin = 14))
                addView(smallBody("Shorten this to test session expiry easier."), matchWrap(topMargin = 2))
                sessionLifetimeInput = input("", InputType.TYPE_CLASS_NUMBER)
                addView(wrapInput(sessionLifetimeInput, suffixText = "seconds"), matchWrap(topMargin = 8))
                emailStepContainer =
                    vertical {
                        startGoogleRedirectSignInButton = button("Continue with Google")
                        addView(startGoogleRedirectSignInButton, matchWrap(topMargin = 16))
                        addView(centerBody("or continue with email"), matchWrap(topMargin = 14))
                        emailInput = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                        addView(wrapInput(emailInput), matchWrap(topMargin = 16))
                        startEmailSignInButton = button("Send Login Code")
                        addView(startEmailSignInButton, matchWrap(topMargin = 14))
                    }
                addView(emailStepContainer)
                codeStepContainer =
                    vertical {
                        addView(codeStepTitle("Verification Code"))
                        codeInput = input("Enter code", InputType.TYPE_CLASS_NUMBER)
                        addView(wrapInput(codeInput), matchWrap(topMargin = 10))
                        addView(
                            horizontal {
                                cancelCodeStepButton = button("Cancel", outline = true)
                                addView(cancelCodeStepButton, weightedButton())
                                addView(space(width = 12))
                                confirmCodeButton = button("Verify Code")
                                addView(confirmCodeButton, weightedButton())
                            },
                            matchWrap(topMargin = 12),
                        )
                    }
                addView(codeStepContainer, matchWrap(topMargin = 18))
            }
        root.addView(authCard, matchWrap(topMargin = 18))

        walletCard =
            card {
                addView(sectionTitle("Wallet"))
                walletView = body("No wallet selected.")
                addView(walletView)
                addView(
                    horizontal {
                        addView(
                            button("Copy").also { button ->
                                button.setOnClickListener { copyWalletAddress() }
                            },
                            weightedButton(),
                        )
                        addView(space(width = 10))
                        addView(
                            button("Refresh", outline = true).also { button ->
                                button.setOnClickListener { refreshSignedInData() }
                            },
                            weightedButton(),
                        )
                    },
                    matchWrap(topMargin = 12),
                )
            }
        root.addView(walletCard, matchWrap(topMargin = 18))

        balancesCard =
            card {
                addView(sectionTitle("Polygon balances"))
                balancesView = body(BalanceState.signedOut.display)
                addView(balancesView, matchWrap(topMargin = 12))
                balancesStatusView = body(BalanceState.signedOut.status)
                addView(balancesStatusView, matchWrap(topMargin = 8))
            }
        root.addView(balancesCard, matchWrap(topMargin = 18))

        actionsCard =
            card {
                addView(sectionTitle("Polygon Trails Actions"))
                addView(actionDivider("Swap POL to USDC"), matchWrap(topMargin = 14))
                swapAmountInput = input("POL amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                swapAmountInput.setText(DEFAULT_SWAP_POL_AMOUNT)
                addView(wrapInput(swapAmountInput), matchWrap(topMargin = 12))
                addView(
                    horizontal {
                        prepareSwapButton = button("Prepare Swap")
                        addView(prepareSwapButton, weightedButton())
                        addView(space(width = 10))
                        sendSwapButton = button("Send Swap", outline = true)
                        addView(sendSwapButton, weightedButton())
                    },
                    matchWrap(topMargin = 12),
                )
                swapStatusView = body("Swap status: waiting to prepare.")
                addView(swapStatusView, matchWrap(topMargin = 8))

                addView(actionDivider("Deposit USDC using Earn"), matchWrap(topMargin = 18))
                depositAmountInput = input("USDC amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                depositAmountInput.setText(DEFAULT_DEPOSIT_USDC_AMOUNT)
                addView(wrapInput(depositAmountInput), matchWrap(topMargin = 12))
                addView(
                    horizontal {
                        prepareDepositButton = button("Prepare Deposit")
                        addView(prepareDepositButton, weightedButton())
                        addView(space(width = 10))
                        sendDepositButton = button("Send Deposit", outline = true)
                        addView(sendDepositButton, weightedButton())
                    },
                    matchWrap(topMargin = 12),
                )
                depositStatusView = body("Deposit status: waiting to prepare.")
                addView(depositStatusView, matchWrap(topMargin = 8))

                addView(actionDivider("Swap POL to USDC, then deposit"), matchWrap(topMargin = 18))
                earnAmountInput = input("POL amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                earnAmountInput.setText(DEFAULT_EARN_POL_AMOUNT)
                addView(wrapInput(earnAmountInput), matchWrap(topMargin = 12))
                addView(
                    horizontal {
                        prepareEarnButton = button("Prepare Swap + Deposit")
                        addView(prepareEarnButton, weightedButton())
                        addView(space(width = 10))
                        sendEarnButton = button("Send Swap + Deposit", outline = true)
                        addView(sendEarnButton, weightedButton())
                    },
                    matchWrap(topMargin = 12),
                )
                earnStatusView = body("Swap and Deposit status: waiting to prepare.")
                addView(earnStatusView, matchWrap(topMargin = 8))

                lastTransactionView = body("Last transaction: none")
                addView(lastTransactionView, matchWrap(topMargin = 18))
                openExplorerButton = button("Open Last Transaction", outline = true)
                addView(openExplorerButton, matchWrap(topMargin = 10))
            }
        root.addView(actionsCard, matchWrap(topMargin = 18))

        positionsCard =
            card {
                addView(sectionTitle("Earn positions"))
                positionsView = body("Sign in to load earn positions.")
                addView(positionsView, matchWrap(topMargin = 8))
                positionsContainer = vertical {}
                addView(positionsContainer, matchWrap(topMargin = 12))
                positionsStatusView = body("")
                addView(positionsStatusView, matchWrap(topMargin = 8))
            }
        root.addView(positionsCard, matchWrap(topMargin = 18))

        root.addView(
            card {
                addView(sectionTitle("Log"))
                logView =
                    body("Ready.").apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 12f
                    }
                addView(logView)
            },
            matchWrap(topMargin = 18),
        )
    }

    private fun bindActions() {
        startGoogleRedirectSignInButton.setOnClickListener { startGoogleRedirectSignIn() }
        startEmailSignInButton.setOnClickListener { startEmailSignIn() }
        confirmCodeButton.setOnClickListener { completeEmailSignIn() }
        cancelCodeStepButton.setOnClickListener {
            sdk.wallet.signOut()
            clearExpiredSessionState()
            codeInput.text?.clear()
            showEmailStep()
        }
        signOutButton.setOnClickListener {
            sdk.wallet.signOut()
            clearExpiredSessionState()
            clearPreparedState()
            resetLoadedData()
            appendLog("Logged out.")
            renderSessionState()
        }
        prepareSwapButton.setOnClickListener { prepareSwap() }
        sendSwapButton.setOnClickListener { sendSwap() }
        prepareDepositButton.setOnClickListener { prepareDeposit() }
        sendDepositButton.setOnClickListener { sendDeposit() }
        prepareEarnButton.setOnClickListener { prepareEarn() }
        sendEarnButton.setOnClickListener { sendEarn() }
        openExplorerButton.setOnClickListener {
            lastTransactionHash?.let(::openExplorer)
        }
    }

    private fun startGoogleRedirectSignIn() {
        launchAction(
            label = "Start Google redirect sign-in",
            onStart = { showGoogleRedirectPendingStep("Opening Google redirect sign-in...") },
            onFailure = {
                showEmailStep()
                authStatusView.text = "Google redirect sign-in failed: ${describe(it)}"
            },
        ) {
            persistAuthPreferences()
            clearExpiredSessionState()
            val started =
                sdk.wallet.startOidcRedirectAuth(
                    provider =
                        OidcProviders.google(
                            clientId = DemoConfig.demoGoogleWebClientId,
                        ),
                    redirectUri = DemoConfig.oidcRedirectUri,
                    loginHint = expiredSessionEmail(),
                )
            appendLog("Google redirect auth started: state=${started.state}")
            showGoogleRedirectPendingStep("Waiting for Google redirect callback...")
            openInAppBrowser(started.authorizationUrl)
        }
    }

    private fun startEmailSignIn() {
        launchAction(
            label = "Start email sign-in",
            onStart = { authStatusView.text = "Requesting email code..." },
            onFailure = { authStatusView.text = "Email sign-in failed: ${describe(it)}" },
        ) {
            val email = requireEmailForSignIn()
            persistAuthPreferences()
            clearExpiredSessionState()
            sdk.wallet.startEmailAuth(email)
            authStatusView.text = "Code requested for $email."
            showPendingCodeStep()
            emailInput.text?.clear()
            appendLog("Email verifier committed for $email")
        }
    }

    private fun completeEmailSignIn() {
        launchAction(
            label = "Verify email code",
            onStart = { authStatusView.text = "Confirming code..." },
            onFailure = { authStatusView.text = "Code confirmation failed: ${describe(it)}" },
        ) {
            val code = requireInput(codeInput, "Verification code")
            codeInput.text?.clear()
            val result = completeEmailAuthWithConfiguredLifetime(code)
            when (result) {
                is CompleteAuthResult.WalletSelected -> {
                    renderSignedInWallet(result.wallet, "Email login complete")
                }

                is CompleteAuthResult.WalletSelection -> {
                    completePendingWalletSelection(result.pendingSelection, "Email login complete")
                }
            }
        }
    }

    private fun refreshSignedInData() {
        launchAction(
            label = "Refresh Polygon data",
            onStart = {
                balancesStatusView.text = "Loading Polygon balances..."
                positionsContainer.removeAllViews()
                positionsView.visibility = View.VISIBLE
                positionsView.text = "Loading Polygon earn positions..."
                positionsStatusView.text = "Loading Polygon earn positions..."
                positionsStatusView.visibility = View.GONE
            },
            onFailure = {
                balancesStatusView.text = "Refresh failed: ${describe(it)}"
                positionsContainer.removeAllViews()
                positionsView.visibility = View.VISIBLE
                positionsView.text = "Unable to load earn positions."
                positionsStatusView.text = "Earn positions status: ${describe(it)}"
                positionsStatusView.visibility = View.VISIBLE
            },
        ) {
            val walletAddress = requireWalletAddress()
            balances = getPolygonBalances(walletAddress)
            val positionsResult = getPolygonEarnPositions(walletAddress)
            earnPositions = positionsResult.positions
            balancesView.text = balances.display
            balancesStatusView.text = balances.status
            renderEarnPositions(positionsResult)
            positionsResult.errors.forEach { error ->
                appendLog("! Earn balance error: $error")
            }
        }
    }

    private fun prepareSwap() {
        launchAction(
            label = "Prepare swap",
            onStart = { swapStatusView.text = "Swap status: preparing Trails intent..." },
            onFailure = { swapStatusView.text = "Swap status: ${describe(it)}" },
        ) {
            val prepared = prepareSwapPolToUsdc(requireWalletAddress(), requireInput(swapAmountInput, "POL amount"))
            preparedSwap = prepared
            swapStatusView.text = "Swap status: prepared for about ${prepared.outputDisplay}."
            appendLog("Prepared ${prepared.title}: ${prepared.request.to}")
        }
    }

    private fun sendSwap() {
        launchAction(
            label = "Send swap",
            onStart = { swapStatusView.text = "Swap status: sending..." },
            onFailure = { swapStatusView.text = "Swap status: ${describe(it)}" },
        ) {
            val prepared = preparedSwap ?: throw IllegalStateException("Prepare the swap first.")
            val initialBalances = balances
            val latest = sendPreparedSwap(prepared)
            val hash = latest.txnHash ?: latest.txnId
            lastTransactionHash = hash
            renderLastTransaction()
            swapStatusView.text = "Swap status: sent ${shortHash(hash)}. Refreshing balances..."
            waitForUsdcBalanceIncrease(
                initialBalances = initialBalances,
                minIncreaseRaw = prepared.outputRaw,
                selectedFeeOption = prepared.executionState.selectedFeeOption,
                setStatus = { swapStatusView.text = it },
                pendingStatus = "Swap status: sent ${shortHash(hash)}. Waiting for expected USDC balance",
                successStatus = "Swap status: sent ${shortHash(hash)}. USDC balance updated.",
                staleStatus = "Swap status: sent ${shortHash(hash)}. USDC balance has not reached the expected swap output yet.",
            )
        }
    }

    private fun prepareDeposit() {
        launchAction(
            label = "Prepare deposit",
            onStart = { depositStatusView.text = "Deposit status: preparing Earn action..." },
            onFailure = { depositStatusView.text = "Deposit status: ${describe(it)}" },
        ) {
            val prepared = prepareDepositUsdc(requireWalletAddress(), requireInput(depositAmountInput, "USDC amount"))
            preparedDeposit = prepared
            depositStatusView.text =
                "Deposit status: prepared ${prepared.transactions.size} transaction${plural(
                    prepared.transactions.size,
                )} for ${prepared.marketName}."
        }
    }

    private fun sendDeposit() {
        launchAction(
            label = "Send deposit",
            onStart = { depositStatusView.text = "Deposit status: sending..." },
            onFailure = { depositStatusView.text = "Deposit status: ${describe(it)}" },
        ) {
            val prepared = preparedDeposit ?: throw IllegalStateException("Prepare the deposit first.")
            val response = sendYieldTransactions(prepared.transactions, "Deposit") { depositStatusView.text = it }
            lastTransactionHash = response.txnHash
            renderLastTransaction()
            depositStatusView.text = "Deposit status: sent ${shortHash(response.txnHash ?: response.txnId)}. Refreshing..."
            refreshAfterSend()
        }
    }

    private fun prepareEarn() {
        launchAction(
            label = "Prepare swap and deposit",
            onStart = { earnStatusView.text = "Swap and Deposit status: preparing Trails actions..." },
            onFailure = { earnStatusView.text = "Swap and Deposit status: ${describe(it)}" },
        ) {
            val walletAddress = requireWalletAddress()
            val market = findPolygonUsdcEarnMarket()
            val swap = prepareSwapPolToUsdc(walletAddress, requireInput(earnAmountInput, "POL amount"))
            val depositAmount = formatUnits(swap.outputRaw.toBigInteger(), 6)
            preparedEarn =
                PreparedSwapAndDepositPlan(
                    swap = swap,
                    market = market,
                    depositAmount = depositAmount,
                )
            earnStatusView.text =
                "Swap and Deposit status: prepared swap output for ${market.metadata.name}."
            appendLog("Prepared swap and deposit: ${swap.outputDisplay} into ${market.metadata.name}")
        }
    }

    private fun sendEarn() {
        launchAction(
            label = "Send swap and deposit",
            onStart = { earnStatusView.text = "Swap and Deposit status: sending swap..." },
            onFailure = { earnStatusView.text = "Swap and Deposit status: ${describe(it)}" },
        ) {
            val plan = preparedEarn ?: throw IllegalStateException("Prepare the swap and deposit first.")
            val initialBalances = balances
            val swapResponse = sendPreparedSwap(plan.swap)
            val swapHash = swapResponse.txnHash ?: swapResponse.txnId
            lastTransactionHash = swapHash
            renderLastTransaction()
            val didReceiveSwapOutput =
                waitForUsdcBalanceIncrease(
                    initialBalances = initialBalances,
                    minIncreaseRaw = plan.swap.outputRaw,
                    selectedFeeOption = plan.swap.executionState.selectedFeeOption,
                    setStatus = { earnStatusView.text = it },
                    pendingStatus = "Swap and Deposit status: sent ${shortHash(swapHash)}. Waiting for USDC output",
                    successStatus = "Swap and Deposit status: USDC output detected. Preparing deposit step...",
                    staleStatus = "Swap and Deposit status: USDC output has not appeared yet.",
                )
            if (!didReceiveSwapOutput) {
                appendLog("Skipping deposit because the swap output was not detected.")
                return@launchAction
            }
            val deposit =
                prepareDepositUsdc(
                    walletAddress = requireWalletAddress(),
                    usdcAmount = plan.depositAmount,
                    preferredMarket = plan.market,
                )
            val depositResponse = sendYieldTransactions(deposit.transactions, "Swap and Deposit") { earnStatusView.text = it }
            lastTransactionHash = depositResponse.txnHash
            renderLastTransaction()
            earnStatusView.text =
                "Swap and Deposit status: sent ${shortHash(depositResponse.txnHash ?: depositResponse.txnId)}. Refreshing..."
            refreshAfterSend()
        }
    }

    private fun withdrawEarnPosition(position: EarnPosition) {
        launchAction(
            label = "Withdraw ${position.marketName}",
            onStart = {
                withdrawStatusesByPosition[position.id] = "Withdraw status: preparing ${position.marketName}..."
                lastWithdrawTransactionHashes.remove(position.id)
                renderEarnPositions(EarnPositionsResult(earnPositions, emptyList()))
            },
            onFailure = {
                withdrawStatusesByPosition[position.id] = "Withdraw status: ${describe(it)}"
                renderEarnPositions(EarnPositionsResult(earnPositions, emptyList()))
            },
        ) {
            require(position.canWithdraw) { "This earn position cannot be withdrawn." }
            val prepared = prepareWithdrawEarnPosition(requireWalletAddress(), position)
            val response =
                sendYieldTransactions(prepared.transactions, "Withdraw") { status ->
                    withdrawStatusesByPosition[position.id] = status
                    renderEarnPositions(EarnPositionsResult(earnPositions, emptyList()))
                }
            val hash = response.txnHash ?: response.txnId
            lastTransactionHash = hash
            lastWithdrawTransactionHashes[position.id] = hash
            renderLastTransaction()
            withdrawStatusesByPosition[position.id] = "Withdraw status: sent ${shortHash(hash)}. Refreshing..."
            renderEarnPositions(EarnPositionsResult(earnPositions, emptyList()))
            refreshAfterSend()
        }
    }

    private suspend fun sendPreparedSwap(prepared: PreparedSwapTransaction): SendTransactionResponse {
        selectedFeeOption = null
        val intentId =
            prepared.executionState.committedIntentId
                ?: trailsClient
                    .commitIntent(CommitIntentRequest(prepared.intent))
                    .intentId
                    .also { committedIntentId ->
                        prepared.executionState.committedIntentId = committedIntentId
                    }
        val response =
            prepared.executionState.submittedResponse
                ?: sdk
                    .wallet
                    .sendTransaction(
                        network = Network.POLYGON,
                        request = prepared.request,
                        selectFeeOption = ::selectFeeOption,
                    ).also { submittedResponse ->
                        prepared.executionState.submittedResponse = submittedResponse
                        prepared.executionState.selectedFeeOption = selectedFeeOption
                    }
        val latest = waitForTransactionHash(response)
        prepared.executionState.submittedResponse = latest
        val hash = latest.txnHash ?: throw IllegalStateException("Wallet transaction hash was not available.")
        if (!prepared.executionState.didExecuteIntent) {
            trailsClient.executeIntent(ExecuteIntentRequest(intentId = intentId, depositTransactionHash = hash))
            prepared.executionState.didExecuteIntent = true
        }
        waitForIntentSuccess(intentId)
        return latest
    }

    private suspend fun getPolygonBalances(walletAddress: String): BalanceState {
        val polBalance =
            sdk.indexer.getNativeTokenBalance(
                network = Network.POLYGON,
                walletAddress = walletAddress,
            )
        val usdcBalances =
            sdk.indexer.getTokenBalances(
                network = Network.POLYGON,
                contractAddress = POLYGON_USDC,
                walletAddress = walletAddress,
                includeMetadata = false,
            )
        val polRaw = polBalance?.balance ?: "0"
        val usdcRaw = usdcBalances.balances.firstOrNull()?.balance ?: "0"
        return BalanceState(
            pol = formatTokenAmount(polRaw, 18, "POL"),
            usdc = formatTokenAmount(usdcRaw, 6, "USDC"),
            polRaw = polRaw,
            usdcRaw = usdcRaw,
            status = "Balances updated.",
        )
    }

    private suspend fun getPolygonEarnPositions(walletAddress: String): EarnPositionsResult {
        val markets =
            trailsClient
                .yieldGetMarkets(
                    GetYieldMarketsRequest(
                        chainId = POLYGON_CHAIN_ID.toString(),
                        limit = 100u,
                    ),
                ).items
        val marketById = markets.associateBy { it.id }
        val balancesResult =
            trailsClient
                .yieldGetAggregateBalances(
                    GetYieldAggregateBalancesRequest(
                        queries =
                            listOf(
                                YieldBalanceQuery(
                                    address = walletAddress,
                                    network = "polygon",
                                ),
                            ),
                    ),
                )
        return EarnPositionsResult(
            positions =
                balancesResult
                    .items
                    .mapNotNull { balances -> balances.toEarnPosition(marketById[balances.yieldId]) }
                    .sortedByDescending { it.sortValue },
            errors = balancesResult.errors.map { error -> "${error.yieldId}: ${error.error}" },
        )
    }

    private suspend fun prepareSwapPolToUsdc(
        walletAddress: String,
        polAmount: String,
    ): PreparedSwapTransaction {
        val amountRaw = parsePositiveAmount(polAmount, decimals = 18, label = "POL")
        val response =
            trailsClient.quoteIntent(
                QuoteIntentRequest(
                    ownerAddress = walletAddress,
                    originChainId = POLYGON_CHAIN_ID.toULong(),
                    originTokenAddress = POLYGON_NATIVE_TOKEN,
                    destinationChainId = POLYGON_CHAIN_ID.toULong(),
                    destinationTokenAddress = POLYGON_USDC,
                    destinationToAddress = walletAddress,
                    originTokenAmount = amountRaw,
                    tradeType = TradeType.EXACT_INPUT,
                    fundMethod = FundMethod.WALLET,
                    mode = IntentMode.SWAP,
                    options = QuoteIntentRequestOptions(swapProvider = RouteProvider.AUTO),
                ),
            )
        val deposit = response.intent.depositTransaction
        require(deposit.chainId == POLYGON_CHAIN_ID.toULong()) {
            "Trails returned chain ${deposit.chainId}, but this demo only sends Polygon transactions."
        }
        val outputRaw = response.intent.quote.toAmountMin ?: response.intent.quote.toAmount
        return PreparedSwapTransaction(
            title = "Swap POL to USDC",
            request =
                SendTransactionRequest(
                    to = deposit.to,
                    value = deposit.value.toBigInteger(),
                    data = deposit.data,
                ),
            intent = response.intent,
            outputRaw = outputRaw,
            outputDisplay = formatTokenAmount(outputRaw, 6, "USDC"),
        )
    }

    private suspend fun prepareDepositUsdc(
        walletAddress: String,
        usdcAmount: String,
        preferredMarket: YieldMarket? = null,
    ): PreparedYieldTransactions {
        val amount = parsePositiveDisplayAmount(usdcAmount, decimals = 6, label = "USDC")
        val market = preferredMarket ?: findPolygonUsdcEarnMarket()
        val inputToken = market.inputTokens.firstOrNull() ?: market.token
        val response =
            trailsClient.yieldCreateEnterAction(
                CreateYieldActionRequest(
                    earnMarketId = market.id,
                    userWalletAddress = walletAddress,
                    args =
                        YieldActionArguments(
                            amount = amount,
                            inputToken = inputToken.address ?: inputToken.symbol,
                            inputTokenNetwork = inputToken.network,
                            receiverAddress = walletAddress,
                        ),
                ),
            )
        return PreparedYieldTransactions(
            title = "Deposit USDC using Earn",
            transactions = parseYieldTransactions(response.action.transactions, "Deposit"),
            marketName = market.metadata.name,
        )
    }

    private suspend fun prepareWithdrawEarnPosition(
        walletAddress: String,
        position: EarnPosition,
    ): PreparedYieldTransactions {
        val response =
            trailsClient.yieldCreateExitAction(
                CreateYieldActionRequest(
                    earnMarketId = position.marketId,
                    userWalletAddress = walletAddress,
                    args =
                        YieldActionArguments(
                            amount = position.amount,
                            outputToken = position.outputToken,
                            outputTokenNetwork = position.outputTokenNetwork,
                        ),
                ),
            )
        return PreparedYieldTransactions(
            title = "Withdraw ${position.marketName}",
            transactions = parseYieldTransactions(response.action.transactions, "Withdraw"),
            marketName = position.marketName,
        )
    }

    private suspend fun findPolygonUsdcEarnMarket(): YieldMarket {
        val markets =
            trailsClient
                .yieldGetMarkets(
                    GetYieldMarketsRequest(
                        chainId = POLYGON_CHAIN_ID.toString(),
                        search = "USDC",
                        limit = 50u,
                    ),
                ).items
        return markets
            .filter { it.status.enter }
            .filter { market -> market.inputToken().address.equals(POLYGON_USDC, ignoreCase = true) }
            .filter { reasonableApyRate(it.rewardRate) != null }
            .maxByOrNull { reasonableApyRate(it.rewardRate) ?: 0.0 }
            ?: throw IllegalStateException("No enterable Polygon USDC earn market was returned.")
    }

    private suspend fun sendYieldTransactions(
        transactions: List<ParsedYieldTransaction>,
        label: String,
        setStatus: (String) -> Unit,
    ): SendTransactionResponse {
        var latest: SendTransactionResponse? = null
        transactions.forEachIndexed { index, transaction ->
            val stepLabel =
                if (transactions.size == 1) {
                    "transaction"
                } else {
                    "transaction ${index + 1}/${transactions.size}"
                }
            setStatus("$label status: sending $stepLabel...")
            latest =
                sdk.wallet.sendTransaction(
                    network = Network.POLYGON,
                    request = transaction.request,
                    selectFeeOption = ::selectFeeOption,
                )
            setStatus("$label status: sent $stepLabel ${shortHash(latest?.txnHash ?: latest?.txnId.orEmpty())}.")
        }
        return latest ?: throw IllegalStateException("$label action did not send a transaction.")
    }

    private suspend fun refreshAfterSend() {
        delay(POST_SEND_REFRESH_DELAY_MS)
        refreshSignedInDataSnapshot()
    }

    private suspend fun waitForUsdcBalanceIncrease(
        initialBalances: BalanceState,
        minIncreaseRaw: String,
        selectedFeeOption: FeeOptionWithBalance?,
        setStatus: (String) -> Unit,
        pendingStatus: String,
        successStatus: String,
        staleStatus: String,
    ): Boolean {
        repeat(POST_SEND_REFRESH_ATTEMPTS) { index ->
            val attempt = index + 1
            val suffix =
                if (attempt == 1) {
                    "..."
                } else {
                    " ($attempt/$POST_SEND_REFRESH_ATTEMPTS)..."
                }
            setStatus("$pendingStatus$suffix")
            refreshSignedInDataSnapshot()
            if (hasUsdcBalanceIncrease(initialBalances, balances, minIncreaseRaw, selectedFeeOption)) {
                setStatus(successStatus)
                return true
            }
            if (attempt < POST_SEND_REFRESH_ATTEMPTS) {
                delay(POST_SEND_REFRESH_DELAY_MS)
            }
        }
        setStatus("$staleStatus Use Refresh to check again.")
        return false
    }

    private suspend fun refreshSignedInDataSnapshot(): EarnPositionsResult {
        val walletAddress = requireWalletAddress()
        balances = getPolygonBalances(walletAddress)
        val positionsResult = getPolygonEarnPositions(walletAddress)
        earnPositions = positionsResult.positions
        balancesView.text = balances.display
        balancesStatusView.text = balances.status
        renderEarnPositions(positionsResult)
        positionsResult.errors.forEach { error ->
            appendLog("! Earn balance error: $error")
        }
        return positionsResult
    }

    private suspend fun waitForIntentSuccess(intentId: String) {
        repeat(POST_SEND_REFRESH_ATTEMPTS) { index ->
            val attempt = index + 1
            val receiptResult =
                runCatching {
                    trailsClient.waitIntentReceipt(WaitIntentReceiptRequest(intentId = intentId)).intentReceipt
                }
            receiptResult
                .onSuccess { receipt ->
                    when (receipt.status) {
                        IntentStatus.SUCCEEDED -> {
                            return
                        }

                        IntentStatus.FAILED,
                        IntentStatus.ABORTED,
                        IntentStatus.REFUNDED,
                        IntentStatus.INVALID,
                        -> {
                            throw IllegalStateException(
                                "Trails intent $intentId finished with ${receipt.status.wireValue}.",
                            )
                        }

                        IntentStatus.UNKNOWN_DEFAULT -> {
                            throw IllegalStateException("Trails intent $intentId returned an unsupported status.")
                        }

                        IntentStatus.QUOTED,
                        IntentStatus.COMMITTED,
                        IntentStatus.EXECUTING,
                        -> {
                            appendLog("Waiting for Trails intent $intentId ($attempt/$POST_SEND_REFRESH_ATTEMPTS).")
                        }
                    }
                }.onFailure { throwable ->
                    if (attempt == POST_SEND_REFRESH_ATTEMPTS) {
                        throw throwable
                    }
                    appendLog("Waiting for Trails intent receipt ($attempt/$POST_SEND_REFRESH_ATTEMPTS): ${describe(throwable)}")
                }
            if (attempt < POST_SEND_REFRESH_ATTEMPTS) {
                delay(POST_SEND_REFRESH_DELAY_MS)
            }
        }
        throw IllegalStateException("Trails intent did not finish yet. Use Refresh to check again.")
    }

    private suspend fun waitForTransactionHash(response: SendTransactionResponse): SendTransactionResponse {
        if (!response.txnHash.isNullOrBlank()) return response
        var latest = response
        repeat(POST_SEND_REFRESH_ATTEMPTS) { attempt ->
            delay(POST_SEND_REFRESH_DELAY_MS)
            val status = sdk.wallet.getTransactionStatus(response.txnId)
            latest =
                SendTransactionResponse(
                    txnId = response.txnId,
                    status = status.status,
                    txnHash = status.txnHash,
                )
            if (!latest.txnHash.isNullOrBlank()) return latest
            if (latest.status == TransactionStatus.UNKNOWN_DEFAULT) {
                throw IllegalStateException("Wallet transaction returned an unsupported status.")
            }
            appendLog("Waiting for chain hash (${attempt + 1}/$POST_SEND_REFRESH_ATTEMPTS).")
        }
        throw IllegalStateException("Wallet transaction hash was not available yet. Try again in a few seconds.")
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
                        selectedFeeOption = feeOptions[index]
                        resumeOnce(feeOptions[index].selection)
                    }.setOnCancelListener {
                        cancelOnce()
                    }
            firstAvailable?.let { option ->
                builder.setPositiveButton("Select first available") { _, _ ->
                    selectedFeeOption = option
                    resumeOnce(option.selection)
                }
            }
            val dialog = builder.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private suspend fun completePendingWalletSelection(
        pendingSelection: PendingWalletSelection,
        status: String,
    ) {
        authStatusView.text = "Select a wallet to finish sign-in."
        appendLog("Wallet selection required: type=${pendingSelection.walletType} count=${pendingSelection.wallets.size}")
        val selected =
            try {
                requestWalletSelectionChoice(pendingSelection)
            } catch (throwable: Throwable) {
                sdk.wallet.signOut()
                showEmailStep()
                throw throwable
            }
        renderSignedInWallet(selected.wallet, status)
    }

    private suspend fun requestWalletSelectionChoice(pendingSelection: PendingWalletSelection) =
        suspendCancellableCoroutine<com.omsclient.kotlin_sdk.wallet.WalletSelectionResult> { continuation ->
            var resumed = false

            fun resumeOnce(action: suspend () -> com.omsclient.kotlin_sdk.wallet.WalletSelectionResult) {
                if (resumed) return
                resumed = true
                uiScope.launch {
                    runCatching { action() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                }
            }

            val labels =
                buildList {
                    pendingSelection.wallets.forEach { wallet ->
                        add("${shortAddress(wallet.address)}\n${wallet.reference ?: wallet.id}")
                    }
                    add("Create new wallet")
                }.toTypedArray()
            val dialog =
                MaterialAlertDialogBuilder(this)
                    .setTitle("Select wallet")
                    .setItems(labels) { _, index ->
                        val wallet = pendingSelection.wallets.getOrNull(index)
                        if (wallet != null) {
                            resumeOnce { pendingSelection.selectWallet(wallet.id) }
                        } else {
                            resumeOnce { pendingSelection.createAndSelectWallet() }
                        }
                    }.setOnCancelListener {
                        if (!resumed) {
                            resumed = true
                            continuation.resumeWithException(IllegalStateException("Wallet selection cancelled"))
                        }
                    }.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }

    private fun parseYieldTransactions(
        transactions: List<YieldTransaction>,
        label: String,
    ): List<ParsedYieldTransaction> {
        val parsed =
            transactions
                .filter { it.isMessage != true }
                .map { parseUnsignedYieldTransaction(it.unsignedTransaction) }
        require(parsed.isNotEmpty()) { "$label action did not return a transaction." }
        val unsupported = parsed.firstOrNull { it.chainId != POLYGON_CHAIN_ID }
        require(unsupported == null) {
            "$label returned chain ${unsupported?.chainId}, but this demo only sends Polygon transactions."
        }
        return parsed
    }

    private fun parseUnsignedYieldTransaction(tx: JsonElement?): ParsedYieldTransaction {
        val txObject = tx?.asObject() ?: throw IllegalStateException("Yield action returned an incomplete transaction.")
        val to = txObject.string("to") ?: throw IllegalStateException("Yield action returned no transaction recipient.")
        val chainId = txObject.string("chainId")?.toIntOrNull() ?: txObject.numberString("chainId")?.toDoubleOrNull()?.toInt()
        return ParsedYieldTransaction(
            to = to,
            data = txObject.string("data") ?: "0x",
            value = txObject.string("value")?.takeUnless { it == "null" }?.toBigIntegerOrNull() ?: BigInteger.ZERO,
            chainId = chainId ?: throw IllegalStateException("Yield action returned no chain id."),
        )
    }

    private fun YieldBalances.toEarnPosition(market: YieldMarket?): EarnPosition? {
        val balance = primaryBalance() ?: return null
        return EarnPosition(
            id = yieldId,
            marketId = yieldId,
            marketName = market?.metadata?.name ?: balance.shareToken?.name ?: "${balance.token.symbol} position",
            provider = market?.providerId ?: balance.shareToken?.symbol ?: yieldId,
            amount = balance.amount,
            amountDisplay = formatDisplayAmount(balance.amount),
            amountRaw = balance.amountRaw,
            amountUsd = formatUsdAmount(balance.amountUsd),
            apy = formatApy(rewardRate ?: market?.rewardRate),
            tokenSymbol = balance.token.symbol,
            outputToken = balance.token.address ?: balance.token.symbol,
            outputTokenNetwork = balance.token.network,
            canWithdraw = market?.status?.exit != false,
            sortValue = balance.amountUsd?.toDoubleOrNull() ?: balance.amount.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun YieldBalances.primaryBalance(): YieldBalance? =
        outputTokenBalance?.takeIf { it.hasPositiveAmount() } ?: balances.firstOrNull { it.hasPositiveAmount() }

    private fun YieldBalance.hasPositiveAmount(): Boolean =
        amountRaw.toBigIntegerOrNull()?.let { it > BigInteger.ZERO }
            ?: amount.toDoubleOrNull()?.let { it > 0.0 }
            ?: false

    private fun YieldMarket.inputToken() = inputTokens.firstOrNull() ?: token

    private fun handleOidcRedirectCallback(intent: Intent?) {
        val callbackUrl = intent?.data?.toString() ?: return
        launchAction(
            label = "Handle Google redirect sign-in callback",
            onStart = { showGoogleRedirectPendingStep("Completing Google redirect sign-in...") },
        ) {
            when (val result = handleOidcRedirectCallbackWithConfiguredLifetime(callbackUrl)) {
                is OidcRedirectAuthResult.Completed -> {
                    consumeIntentData()
                    renderSignedInWallet(result.wallet, "Google redirect login complete")
                }

                is OidcRedirectAuthResult.WalletSelection -> {
                    consumeIntentData()
                    completePendingWalletSelection(
                        pendingSelection = result.pendingSelection,
                        status = "Google redirect login complete",
                    )
                }

                is OidcRedirectAuthResult.Failed -> {
                    consumeIntentData()
                    showEmailStep()
                    authStatusView.text = "Google redirect completion failed: ${describe(result.error)}"
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

    private fun openInAppBrowser(url: String) {
        val colorSchemeParams =
            CustomTabColorSchemeParams
                .Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.surface_800))
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

    private fun renderSessionState() {
        val walletAddress = sdk.session.walletAddress
        if (walletAddress == null) {
            renderSessionStateBox()
            expiredSessionEvent?.let(::renderExpiredSession) ?: resetUiForNoSession()
            return
        }

        clearExpiredSessionState()
        renderSessionStateBox()
        authStatusView.text = "Restored persisted wallet session"
        walletView.text = "Wallet:\n$walletAddress"
        authCard.visibility = View.GONE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletCard.visibility = View.VISIBLE
        balancesCard.visibility = View.VISIBLE
        actionsCard.visibility = View.VISIBLE
        positionsCard.visibility = View.VISIBLE
        signOutButton.visibility = View.VISIBLE
        renderLastTransaction()
        balancesView.text = balances.display
        balancesStatusView.text = balances.status
        if (earnPositions.isEmpty()) {
            positionsContainer.removeAllViews()
            positionsView.visibility = View.VISIBLE
            positionsView.text = "Loading Polygon earn positions..."
            positionsStatusView.visibility = View.GONE
        } else {
            renderEarnPositions(EarnPositionsResult(earnPositions, emptyList()))
        }
    }

    private fun renderSignedInWallet(
        wallet: Wallet,
        status: String,
    ) {
        clearExpiredSessionState()
        renderSessionStateBox()
        authStatusView.text = status
        walletView.text = "Wallet:\n${wallet.address}"
        authCard.visibility = View.GONE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletCard.visibility = View.VISIBLE
        balancesCard.visibility = View.VISIBLE
        actionsCard.visibility = View.VISIBLE
        positionsCard.visibility = View.VISIBLE
        signOutButton.visibility = View.VISIBLE
        positionsContainer.removeAllViews()
        positionsView.visibility = View.VISIBLE
        positionsView.text = "Loading Polygon earn positions..."
        positionsStatusView.visibility = View.GONE
        appendLog("Wallet ready: ${wallet.address}")
        refreshSignedInData()
    }

    private fun renderExpiredSession(event: OMSClientSessionExpiredEvent) {
        val isNewEvent = expiredSessionEvent != event
        expiredSessionEvent = event
        renderSessionStateBox()
        prefillExpiredSessionEmail()
        authStatusView.text =
            buildString {
                append("Wallet session expired. Sign in again")
                event.session.sessionEmail?.takeIf { it.isNotBlank() }?.let { email ->
                    append(" as ")
                    append(email)
                }
                append(".")
            }
        walletView.text = "No wallet selected."
        clearPreparedState()
        resetLoadedData()
        signOutButton.visibility = View.VISIBLE
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletCard.visibility = View.GONE
        balancesCard.visibility = View.GONE
        actionsCard.visibility = View.GONE
        positionsCard.visibility = View.GONE
        if (isNewEvent) {
            appendLog(
                "Wallet session expired at ${event.expiredAt}: " +
                    "wallet=${event.session.walletAddress ?: "none"} email=${event.session.sessionEmail ?: "none"}",
            )
        }
    }

    private fun resetUiForNoSession() {
        renderSessionStateBox()
        authStatusView.text = "Waiting for sign-in."
        walletView.text = "No wallet selected."
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletCard.visibility = View.GONE
        balancesCard.visibility = View.GONE
        actionsCard.visibility = View.GONE
        positionsCard.visibility = View.GONE
        signOutButton.visibility = View.GONE
        renderLastTransaction()
    }

    private fun showEmailStep() {
        renderSessionStateBox()
        authStatusView.text = "Waiting for sign-in."
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.VISIBLE
        codeStepContainer.visibility = View.GONE
        walletCard.visibility = View.GONE
        balancesCard.visibility = View.GONE
        actionsCard.visibility = View.GONE
        positionsCard.visibility = View.GONE
        signOutButton.visibility = View.GONE
        codeInput.text?.clear()
        emailInput.post {
            emailInput.requestFocus()
            emailInput.setSelection(emailInput.text?.length ?: 0)
        }
    }

    private fun showGoogleRedirectPendingStep(status: String) {
        renderSessionStateBox()
        authStatusView.text = status
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.GONE
        walletCard.visibility = View.GONE
        balancesCard.visibility = View.GONE
        actionsCard.visibility = View.GONE
        positionsCard.visibility = View.GONE
        signOutButton.visibility = View.VISIBLE
        renderLastTransaction()
    }

    private fun showPendingCodeStep() {
        renderSessionStateBox()
        authCard.visibility = View.VISIBLE
        emailStepContainer.visibility = View.GONE
        codeStepContainer.visibility = View.VISIBLE
        walletCard.visibility = View.GONE
        balancesCard.visibility = View.GONE
        actionsCard.visibility = View.GONE
        positionsCard.visibility = View.GONE
        signOutButton.visibility = View.VISIBLE
        codeInput.post {
            codeInput.requestFocus()
            codeInput.setSelection(codeInput.text?.length ?: 0)
        }
    }

    private fun renderSessionStateBox() {
        val session = sdk.session
        val expiredEvent = expiredSessionEvent
        sessionStateCard.visibility =
            if (session.walletAddress == null && expiredEvent == null) {
                View.GONE
            } else {
                View.VISIBLE
            }
        sessionStateView.text =
            if (expiredEvent != null && session.walletAddress == null) {
                buildString {
                    appendLine("expiredAt: ${expiredEvent.expiredAt}")
                    appendLine("walletAddress: ${expiredEvent.session.walletAddress ?: "null"}")
                    appendLine("expiresAt: ${expiredEvent.session.expiresAt ?: "null"}")
                    appendLine("loginType: ${expiredEvent.session.loginType ?: "null"}")
                    append("sessionEmail: ${expiredEvent.session.sessionEmail ?: "null"}")
                }
            } else {
                buildString {
                    appendLine("walletAddress: ${session.walletAddress ?: "null"}")
                    appendLine("expiresAt: ${session.expiresAt ?: "null"}")
                    appendLine("loginType: ${session.loginType ?: "null"}")
                    append("sessionEmail: ${session.sessionEmail ?: "null"}")
                }
            }
    }

    private fun requireWalletAddress(): String =
        sdk.session.walletAddress?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sign in before preparing a Trails action.")

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
        error("Email is required.")
    }

    private fun expiredSessionEmail(): String? =
        expiredSessionEvent
            ?.session
            ?.sessionEmail
            ?.takeIf { it.isNotBlank() }

    private fun clearExpiredSessionState() {
        expiredSessionEvent = null
    }

    private suspend fun completeEmailAuthWithConfiguredLifetime(code: String): CompleteAuthResult {
        val sessionLifetimeSeconds = requestedSessionLifetimeSeconds()
        persistAuthPreferences()
        return if (sessionLifetimeSeconds == null) {
            sdk.wallet.completeEmailAuth(
                code = code,
                walletSelection = currentWalletSelectionBehavior(),
            )
        } else {
            sdk.wallet.completeEmailAuth(
                code = code,
                walletSelection = currentWalletSelectionBehavior(),
                sessionLifetimeSeconds = sessionLifetimeSeconds,
            )
        }
    }

    private suspend fun handleOidcRedirectCallbackWithConfiguredLifetime(callbackUrl: String): OidcRedirectAuthResult {
        val sessionLifetimeSeconds = requestedSessionLifetimeSeconds()
        persistAuthPreferences()
        return if (sessionLifetimeSeconds == null) {
            sdk.wallet.handleOidcRedirectCallback(
                callbackUrl = callbackUrl,
                walletSelection = currentWalletSelectionBehavior(),
            )
        } else {
            sdk.wallet.handleOidcRedirectCallback(
                callbackUrl = callbackUrl,
                walletSelection = currentWalletSelectionBehavior(),
                sessionLifetimeSeconds = sessionLifetimeSeconds,
            )
        }
    }

    private fun currentWalletSelectionBehavior(): WalletSelectionBehavior =
        if (manualWalletSelectionCheckbox.isChecked) {
            WalletSelectionBehavior.Manual
        } else {
            WalletSelectionBehavior.Automatic
        }

    private fun restoreAuthPreferences() {
        manualWalletSelectionCheckbox.isChecked =
            authPreferences.getBoolean(TRAILS_ACTIONS_MANUAL_WALLET_SELECTION_KEY, false)
        sessionLifetimeInput.setText(
            authPreferences.getString(
                TRAILS_ACTIONS_SESSION_LIFETIME_SECONDS_KEY,
                TRAILS_ACTIONS_DEFAULT_SESSION_LIFETIME_SECONDS,
            ),
        )
    }

    private fun persistAuthPreferences() {
        val rawValue = currentSessionLifetimeSecondsText()
        parseSessionLifetimeSeconds(rawValue)
        authPreferences
            .edit()
            .putBoolean(TRAILS_ACTIONS_MANUAL_WALLET_SELECTION_KEY, manualWalletSelectionCheckbox.isChecked)
            .apply {
                if (rawValue.isBlank()) {
                    remove(TRAILS_ACTIONS_SESSION_LIFETIME_SECONDS_KEY)
                } else {
                    putString(TRAILS_ACTIONS_SESSION_LIFETIME_SECONDS_KEY, rawValue)
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
        if (rawValue.isBlank()) return null
        val parsed = rawValue.toLongOrNull()
        require(parsed != null && parsed > 0L) {
            "Session lifetime seconds must be a positive whole number."
        }
        return parsed
    }

    private fun prefillExpiredSessionEmail() {
        val email = expiredSessionEmail() ?: return
        if (emailInput.text?.isNotBlank() == true) return
        emailInput.setText(email)
        emailInput.setSelection(email.length)
    }

    private fun resetLoadedData() {
        balances = BalanceState.signedOut
        earnPositions = emptyList()
        withdrawStatusesByPosition.clear()
        lastWithdrawTransactionHashes.clear()
        balancesView.text = balances.display
        balancesStatusView.text = balances.status
        positionsContainer.removeAllViews()
        positionsView.visibility = View.VISIBLE
        positionsView.text = "Sign in to load earn positions."
        positionsStatusView.text = ""
        positionsStatusView.visibility = View.GONE
    }

    private fun clearPreparedState() {
        preparedSwap = null
        preparedDeposit = null
        preparedEarn = null
        selectedFeeOption = null
        lastTransactionHash = null
        withdrawStatusesByPosition.clear()
        lastWithdrawTransactionHashes.clear()
        swapStatusView.text = "Swap status: waiting to prepare."
        depositStatusView.text = "Deposit status: waiting to prepare."
        earnStatusView.text = "Swap and Deposit status: waiting to prepare."
        renderLastTransaction()
        if (::positionsContainer.isInitialized && earnPositions.isNotEmpty()) {
            renderEarnPositions(EarnPositionsResult(earnPositions, emptyList()))
        }
    }

    private fun renderLastTransaction() {
        val hash = lastTransactionHash
        lastTransactionView.text =
            if (hash.isNullOrBlank()) {
                "Last transaction: none"
            } else {
                "Last transaction: ${shortHash(hash)}"
            }
        openExplorerButton.visibility = if (hash.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun requireInput(
        input: TextInputEditText,
        label: String,
    ): String {
        val value =
            input.text
                ?.toString()
                ?.trim()
                .orEmpty()
        require(value.isNotBlank()) { "$label is required." }
        return normalizeAmountInput(value)
    }

    private fun launchAction(
        label: String,
        onStart: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        uiScope.launch {
            appendLog("> $label")
            onStart?.invoke()
            runCatching { action() }
                .onFailure { throwable ->
                    onFailure?.invoke(throwable)
                    appendLog("! ${describe(throwable)}")
                }
        }
    }

    private fun copyWalletAddress() {
        val address = sdk.session.walletAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "No wallet address", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Wallet address", address))
        Toast.makeText(this, "Wallet address copied", Toast.LENGTH_SHORT).show()
    }

    private fun appendLog(message: String) {
        if (message.startsWith("!")) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
        logView.text =
            buildString {
                append(logView.text)
                append('\n')
                append(message)
            }.lineSequence()
                .toList()
                .takeLast(80)
                .joinToString("\n")
    }

    private fun describe(error: Throwable): String =
        when (error) {
            is WebRpcError -> error.message.ifBlank { error.error }
            is WebRpcTransportException -> error.message ?: error::class.java.simpleName
            else -> error.message ?: error::class.java.simpleName
        }

    private fun openExplorer(hash: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Network.POLYGON.explorerUrl}/tx/$hash")))
    }

    private fun positionsStatus(result: EarnPositionsResult): String =
        when {
            result.errors.isNotEmpty() -> "Earn positions loaded with ${result.errors.size} API error${plural(result.errors.size)}."
            result.positions.isNotEmpty() -> "Earn positions updated."
            else -> NO_EARN_POSITIONS_STATUS
        }

    private fun renderEarnPositions(result: EarnPositionsResult) {
        positionsContainer.removeAllViews()
        val hasVisibleWithdrawStatus =
            result.positions.any { position ->
                withdrawStatusesByPosition[position.id]?.isNotBlank() == true
            }

        if (result.positions.isEmpty()) {
            positionsView.visibility = View.VISIBLE
            positionsView.text = NO_EARN_POSITIONS_STATUS
        } else {
            positionsView.visibility = View.GONE
            result.positions.forEachIndexed { index, position ->
                if (index > 0) {
                    positionsContainer.addView(
                        dividerLine(),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            topMargin = dp(14)
                        },
                    )
                }
                positionsContainer.addView(positionRow(position), matchWrap(topMargin = if (index == 0) 0 else 14))
            }
        }

        val status = positionsStatus(result)
        if (!hasVisibleWithdrawStatus && (result.positions.isNotEmpty() || status != NO_EARN_POSITIONS_STATUS)) {
            positionsStatusView.text = status
            positionsStatusView.visibility = View.VISIBLE
        } else {
            positionsStatusView.text = ""
            positionsStatusView.visibility = View.GONE
        }
    }

    private fun positionRow(position: EarnPosition): LinearLayout =
        vertical {
            addView(fieldLabel(position.marketName))
            addView(smallBody(position.provider), matchWrap(topMargin = 2))
            addView(body(positionAmountText(position)), matchWrap(topMargin = 8))
            addView(smallBody("APY ${position.apy}"), matchWrap(topMargin = 2))
            addView(
                button("Withdraw", outline = true).apply {
                    isEnabled = position.canWithdraw
                    setOnClickListener { withdrawEarnPosition(position) }
                },
                matchWrap(topMargin = 10),
            )
            addView(smallBody(if (position.canWithdraw) "All" else "Unavailable"), matchWrap(topMargin = 4))
            withdrawStatusesByPosition[position.id]
                ?.takeIf { it.isNotBlank() }
                ?.let { status ->
                    addView(body(status), matchWrap(topMargin = 8))
                }
            lastWithdrawTransactionHashes[position.id]
                ?.takeIf { it.isNotBlank() }
                ?.let { hash ->
                    addView(body("Last withdraw: ${shortHash(hash)}"), matchWrap(topMargin = 6))
                }
        }

    private fun positionAmountText(position: EarnPosition): String =
        buildString {
            append(position.amountDisplay)
            append(' ')
            append(position.tokenSymbol)
            append(" / ")
            append(position.amountUsd ?: "USD unavailable")
        }

    private fun normalizeAmountInput(value: String): String =
        buildString {
            var hasDecimal = false
            value.replace(',', '.').forEach { char ->
                when {
                    char in '0'..'9' -> {
                        append(char)
                    }

                    char == '.' && !hasDecimal -> {
                        append(char)
                        hasDecimal = true
                    }
                }
            }
        }.let { if (it.startsWith('.')) "0$it" else it }

    private fun parsePositiveAmount(
        amount: String,
        decimals: Int,
        label: String,
    ): String = parsePositiveDisplayAmount(amount, decimals, label).let { parseUnits(it, decimals).toString() }

    private fun parsePositiveDisplayAmount(
        amount: String,
        decimals: Int,
        label: String,
    ): String {
        val normalized = normalizeAmountInput(amount)
        require(normalized.isNotBlank()) { "Enter a $label amount." }
        require(parseUnits(normalized, decimals) > BigInteger.ZERO) { "Enter a $label amount greater than zero." }
        return normalized
    }

    private fun formatTokenAmount(
        raw: String,
        decimals: Int,
        symbol: String,
    ): String =
        raw
            .toBigIntegerOrNull()
            ?.let { formatUnits(it, decimals) }
            ?.let { formatDisplayAmount(it, maxFractionDigits = 6) }
            ?.let { "$it $symbol" }
            ?: "- $symbol"

    private fun formatDisplayAmount(
        amount: String,
        maxFractionDigits: Int = 4,
    ): String {
        val parts = amount.split('.', limit = 2)
        val whole = parts.firstOrNull().orEmpty().ifBlank { "0" }
        val fraction =
            parts
                .getOrNull(1)
                .orEmpty()
                .take(maxFractionDigits)
                .trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    private fun formatUsdAmount(value: String?): String? =
        value
            ?.toDoubleOrNull()
            ?.let {
                NumberFormat
                    .getCurrencyInstance(Locale.US)
                    .format(it)
            }

    private fun formatApy(rewardRate: YieldRewardRate?): String {
        val rate = reasonableApyRate(rewardRate) ?: return "-"
        val percent = rate * 100.0
        return if (percent >= 10.0) {
            "%.1f%%".format(Locale.US, percent)
        } else {
            "%.2f%%".format(Locale.US, percent)
        }
    }

    private fun reasonableApyRate(rewardRate: YieldRewardRate?): Double? =
        rewardRate?.total?.takeIf { it.isFinite() && it in 0.0..MAX_REASONABLE_USDC_APY_RATE }

    private fun shortHash(value: String): String =
        if (value.length > 18) {
            "${value.take(10)}...${value.takeLast(8)}"
        } else {
            value
        }

    private fun shortAddress(value: String): String =
        if (value.length > 18) {
            "${value.take(10)}...${value.takeLast(6)}"
        } else {
            value
        }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private fun feeOptionLabel(option: FeeOptionWithBalance): String =
        buildString {
            append(option.feeOption.token.symbol)
            append(" fee ")
            append(option.feeOption.displayValue)
            option.available?.let {
                append(" / available ")
                append(it)
            }
        }

    private fun hasEnoughBalance(option: FeeOptionWithBalance): Boolean {
        val balance = option.availableRaw?.toBigIntegerOrNull() ?: return false
        val fee = option.feeOption.value.toBigIntegerOrNull() ?: return false
        return balance >= fee
    }

    private fun hasUsdcBalanceIncrease(
        initialBalances: BalanceState,
        refreshedBalances: BalanceState,
        minIncreaseRaw: String,
        selectedFeeOption: FeeOptionWithBalance?,
    ): Boolean {
        val initialRaw = rawUnsignedAmount(initialBalances.usdcRaw)
        val refreshedRaw = rawUnsignedAmount(refreshedBalances.usdcRaw)
        val minIncrease = rawUnsignedAmount(minIncreaseRaw)
        val expectedIncrease = (minIncrease - usdcFeeRaw(selectedFeeOption)).coerceAtLeast(BigInteger.ZERO)
        return if (expectedIncrease == BigInteger.ZERO) {
            refreshedRaw > initialRaw
        } else {
            refreshedRaw >= initialRaw + expectedIncrease
        }
    }

    private fun usdcFeeRaw(option: FeeOptionWithBalance?): BigInteger {
        val token = option?.feeOption?.token ?: return BigInteger.ZERO
        val isUsdc =
            token.contractAddress.equals(POLYGON_USDC, ignoreCase = true) ||
                token.symbol.equals("USDC", ignoreCase = true)
        return if (isUsdc) {
            rawUnsignedAmount(option.feeOption.value)
        } else {
            BigInteger.ZERO
        }
    }

    private fun rawUnsignedAmount(value: String): BigInteger =
        value
            .toBigIntegerOrNull()
            ?.takeIf { it >= BigInteger.ZERO }
            ?: BigInteger.ZERO

    private fun JsonElement.asObject(): JsonObject? =
        when (this) {
            is JsonObject -> this
            is JsonPrimitive -> contentOrNull?.let { TrailsJson.parseToJsonElement(it).jsonObject }
            else -> null
        }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.numberString(name: String): String? = this[name]?.jsonPrimitive?.toString()?.trim('"')

    private fun card(content: LinearLayout.() -> Unit): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            strokeWidth = dp(1)
            strokeColor = color(R.color.border_300)
            setCardBackgroundColor(color(R.color.surface_800))
            cardElevation = 0f
            addView(
                vertical {
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    content()
                },
                matchWrap(),
            )
        }

    private fun vertical(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            content()
        }

    private fun horizontal(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            content()
        }

    private fun sectionTitle(value: String): TextView = text(value, 20f, R.color.slate_900, bold = true)

    private fun authDemoSectionTitle(value: String): TextView = text(value, 18f, R.color.slate_900, bold = true)

    private fun fieldLabel(value: String): TextView = text(value, 14f, R.color.slate_900, bold = true)

    private fun codeStepTitle(value: String): TextView = text(value, 16f, R.color.slate_900, bold = true)

    private fun body(value: String): TextView = text(value, 13f, R.color.slate_700, bold = false)

    private fun smallBody(value: String): TextView = text(value, 12f, R.color.slate_700, bold = false)

    private fun centerBody(value: String): TextView =
        body(value).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }

    private fun actionDivider(value: String): LinearLayout =
        horizontal {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(dividerLine(), LinearLayout.LayoutParams(0, dp(1), 1f))
            addView(
                text(value, 13f, R.color.slate_500, bold = true).apply {
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                },
            )
            addView(dividerLine(), LinearLayout.LayoutParams(0, dp(1), 1f))
        }

    private fun dividerLine(): View =
        View(this).apply {
            setBackgroundColor(color(R.color.border_300))
        }

    private fun monospaceBody(value: String): TextView =
        smallBody(value).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

    private fun text(
        value: String,
        size: Float,
        colorRes: Int,
        bold: Boolean,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color(colorRes))
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun button(
        value: String,
        outline: Boolean = false,
    ): MaterialButton =
        MaterialButton(this).apply {
            text = value
            isAllCaps = false
            cornerRadius = dp(10)
            minHeight = dp(52)
            if (outline) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(color(R.color.border_300))
                backgroundTintList = ColorStateList.valueOf(color(R.color.surface_700))
                setTextColor(color(R.color.slate_900))
            } else {
                strokeWidth = 0
                backgroundTintList = ColorStateList.valueOf(color(R.color.brand_500))
                setTextColor(color(R.color.black))
            }
        }

    private fun input(
        hint: String,
        inputType: Int,
    ): TextInputEditText =
        TextInputEditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            maxLines = 1
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(color(R.color.slate_900))
            setHintTextColor(color(R.color.slate_500))
        }

    private fun wrapInput(
        input: TextInputEditText,
        suffixText: String? = null,
    ): TextInputLayout =
        TextInputLayout(this).apply {
            val fieldHint = input.hint
            if (!fieldHint.isNullOrBlank()) {
                hint = fieldHint
                input.hint = null
            }
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = color(R.color.surface_900)
            boxStrokeColor = color(R.color.border_300)
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(1)
            setBoxCornerRadii(
                dp(10).toFloat(),
                dp(10).toFloat(),
                dp(10).toFloat(),
                dp(10).toFloat(),
            )
            setDefaultHintTextColor(ColorStateList.valueOf(color(R.color.slate_500)))
            hintTextColor = ColorStateList.valueOf(color(R.color.slate_500))
            this.suffixText = suffixText
            setSuffixTextColor(ColorStateList.valueOf(color(R.color.slate_500)))
            input.background = null
            addView(input, matchWrap())
        }

    private fun space(width: Int = 0): Space = Space(this).apply { minimumWidth = dp(width) }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
        }

    private fun weightedButton(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun color(id: Int): Int = getColor(id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class BalanceState(
        val pol: String,
        val usdc: String,
        val polRaw: String,
        val usdcRaw: String,
        val status: String,
    ) {
        val display: String
            get() = "POL: $pol\nUSDC: $usdc"

        companion object {
            val signedOut = BalanceState("-", "-", "0", "0", "Sign in to load balances.")
        }
    }

    private data class EarnPositionsResult(
        val positions: List<EarnPosition>,
        val errors: List<String>,
    )

    private data class EarnPosition(
        val id: String,
        val marketId: String,
        val marketName: String,
        val provider: String,
        val amount: String,
        val amountDisplay: String,
        val amountRaw: String,
        val amountUsd: String?,
        val apy: String,
        val tokenSymbol: String,
        val outputToken: String,
        val outputTokenNetwork: String,
        val canWithdraw: Boolean,
        val sortValue: Double,
    )

    private data class ParsedYieldTransaction(
        val to: String,
        val data: String,
        val value: BigInteger,
        val chainId: Int,
    ) {
        val request: SendTransactionRequest
            get() = SendTransactionRequest(to = to, value = value, data = data)
    }

    private data class PreparedSwapTransaction(
        val title: String,
        val request: SendTransactionRequest,
        val intent: com.omsclient.kotlin_sdk_trails_actions.generated.Intent,
        val outputRaw: String,
        val outputDisplay: String,
        val executionState: PreparedSwapExecutionState = PreparedSwapExecutionState(),
    )

    private data class PreparedSwapExecutionState(
        var committedIntentId: String? = null,
        var submittedResponse: SendTransactionResponse? = null,
        var selectedFeeOption: FeeOptionWithBalance? = null,
        var didExecuteIntent: Boolean = false,
    )

    private data class PreparedYieldTransactions(
        val title: String,
        val transactions: List<ParsedYieldTransaction>,
        val marketName: String,
    )

    private data class PreparedSwapAndDepositPlan(
        val swap: PreparedSwapTransaction,
        val market: YieldMarket,
        val depositAmount: String,
    )

    private companion object {
        const val TAG = "TrailsActions"
        const val TRAILS_API_URL = "https://trails-api.sequence.app"

        // Demo-only Trails access key used by the public Trails sample apps; do not reuse for production apps.
        const val TRAILS_ACCESS_KEY = "AQAAAAAAAMCYJYqQIBlKgsdYZIC44JP84lo"
        const val TRAILS_ACCESS_KEY_HEADER = "X-Access-Key"
        const val POLYGON_CHAIN_ID = 137
        const val POLYGON_USDC = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"
        const val POLYGON_NATIVE_TOKEN = "0x0000000000000000000000000000000000000000"
        const val DEFAULT_SWAP_POL_AMOUNT = "0.5"
        const val DEFAULT_DEPOSIT_USDC_AMOUNT = "0.1"
        const val DEFAULT_EARN_POL_AMOUNT = "1"
        const val NO_EARN_POSITIONS_STATUS = "No deposited earn positions."
        const val MAX_REASONABLE_USDC_APY_RATE = 0.5
        const val POST_SEND_REFRESH_ATTEMPTS = 24
        const val POST_SEND_REFRESH_DELAY_MS = 2_500L
        const val TRAILS_ACTIONS_PREFERENCES_NAME = "oms_client_trails_actions_preferences"
        const val TRAILS_ACTIONS_MANUAL_WALLET_SELECTION_KEY = "manual_wallet_selection"
        const val TRAILS_ACTIONS_SESSION_LIFETIME_SECONDS_KEY = "session_lifetime_seconds"
        val TRAILS_ACTIONS_DEFAULT_SESSION_LIFETIME_SECONDS = WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS.toString()
        val TrailsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

private object DemoConfig {
    const val demoPublishableKey: String = "AQAAAAAAAAK2JvvZhWqZ51riasWBftkrVXE"
    const val demoProjectId: String = "proj_014kg56dc0a75"
    const val demoGoogleWebClientId: String = "970987756660-0dh5gubqfiugm452raf7mm39qaq639hn.apps.googleusercontent.com"
    const val oidcRedirectUri: String = "omsclientkotlindemo://auth/callback"
}
