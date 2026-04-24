package com.omsclient.kotlin_sdk.utils

import com.omsclient.kotlin_sdk.models.VerifySignatureResult
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.network.boolean
import com.omsclient.kotlin_sdk.network.parseJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OMSClientUtils internal constructor(
    private val projectAccessKey: String,
    private val environment: OMSClientEnvironment,
    private val transport: OMSClientHttpClient = OMSClientHttpClient(),
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
        OMSClientEnvironment.accessKeyHeaderName to projectAccessKey,
        "Accept" to "application/json",
    )
}
