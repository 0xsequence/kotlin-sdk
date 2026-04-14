package com.polygon_wallet.polygon_kotlin_sdk.utils

import com.polygon_wallet.polygon_kotlin_sdk.models.VerifySignatureResult
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.network.boolean
import com.polygon_wallet.polygon_kotlin_sdk.network.parseJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PolygonSdkUtils internal constructor(
    private val projectAccessKey: String,
    private val environment: SequenceEnvironment,
    private val transport: SequenceHttpClient = SequenceHttpClient(),
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
        SequenceEnvironment.accessKeyHeaderName to projectAccessKey,
        "Accept" to "application/json",
    )
}
