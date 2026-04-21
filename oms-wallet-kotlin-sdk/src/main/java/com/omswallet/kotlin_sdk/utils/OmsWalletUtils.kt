package com.omswallet.kotlin_sdk.utils

import com.omswallet.kotlin_sdk.models.VerifySignatureResult
import com.omswallet.kotlin_sdk.network.OmsWalletEnvironment
import com.omswallet.kotlin_sdk.network.OmsWalletHttpClient
import com.omswallet.kotlin_sdk.network.boolean
import com.omswallet.kotlin_sdk.network.parseJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OmsWalletUtils internal constructor(
    private val projectAccessKey: String,
    private val environment: OmsWalletEnvironment,
    private val transport: OmsWalletHttpClient = OmsWalletHttpClient(),
) {
    suspend fun verifySignature(
        chainId: String,
        walletAddress: String,
        message: String,
        signature: String,
    ): VerifySignatureResult {
        val response = transport.postJson(
            baseUrl = environment.apiRpcUrl,
            path = "/IsValidMessageSignature",
            body = buildJsonObject {
                put("chainId", chainId)
                put("walletAddress", walletAddress)
                put("message", message)
                put("signature", signature)
            }.toString(),
            headers = defaultHeaders(),
        )

        return VerifySignatureResult(
            status = response.statusCode,
            isValid = parseJsonObject(response.body).boolean("isValid") == true,
        )
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        OmsWalletEnvironment.accessKeyHeaderName to projectAccessKey,
        "Accept" to "application/json",
    )
}
