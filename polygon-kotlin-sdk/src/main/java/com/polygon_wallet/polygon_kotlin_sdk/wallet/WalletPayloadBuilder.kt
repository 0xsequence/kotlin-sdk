package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.models.SendTransactionRequest
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

internal object WalletPayloadBuilder {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun buildSignMessagePayload(
        wallet: String,
        network: String,
        message: String,
    ): String = encode(
        buildJsonObject {
            putJsonObject("params") {
                put("wallet", wallet)
                put("network", network)
                put("message", message)
            }
        }
    )

    fun buildSendTransactionPayload(
        wallet: String,
        network: String,
        request: SendTransactionRequest,
    ): String = encode(
        buildJsonObject {
            putJsonObject("params") {
                put("mode", request.mode.name)
                put("wallet", wallet)
                put("network", network)
                put("to", request.to)
                put("value", request.value)
                request.data?.let { put("data", it) }
                request.feeCeiling?.let { put("feeCeiling", it) }
                request.nonce?.let { put("nonce", it) }
            }
        }
    )

    fun buildSendTransactionPayload(
        wallet: String,
        network: String,
        to: String,
        value: String,
    ): String = buildSendTransactionPayload(
        wallet = wallet,
        network = network,
        request = SendTransactionRequest(
            to = to,
            value = value,
        ),
    )

    fun buildCommitVerifierPayload(email: String): String = encode(
        buildJsonObject {
            putJsonObject("params") {
                put("identityType", "Email")
                put("authMode", "OTP")
                put("handle", email)
            }
        }
    )

    fun buildCompleteAuthPayload(
        verifier: String,
        answer: String,
    ): String = encode(
        buildJsonObject {
            putJsonObject("params") {
                put("identityType", "Email")
                put("authMode", "OTP")
                put("verifier", verifier)
                put("answer", answer)
            }
        }
    )

    fun buildCompleteAuthPayloadFromCode(
        verifier: String,
        challenge: String,
        code: String,
    ): String = buildCompleteAuthPayload(
        verifier = verifier,
        answer = hashChallengeAnswer(challenge, code),
    )

    fun buildUseWalletPayload(
        walletType: String,
        walletIndex: Int = 0,
    ): String = encode(
        buildJsonObject {
            putJsonObject("params") {
                put("walletType", walletType)
                put("walletIndex", walletIndex)
            }
        }
    )

    fun buildCreateWalletPayload(walletType: String): String = encode(
        buildJsonObject {
            putJsonObject("params") {
                put("walletType", walletType)
            }
        }
    )

    fun hashChallengeAnswer(
        challenge: String,
        code: String,
    ): String = Numeric.toHexString(
        Hash.sha3((challenge + code).toByteArray(StandardCharsets.UTF_8))
    )

    private fun encode(value: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), value)
}
