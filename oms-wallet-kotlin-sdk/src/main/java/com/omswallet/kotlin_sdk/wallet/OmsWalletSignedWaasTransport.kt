package com.omswallet.kotlin_sdk.wallet

import com.omswallet.kotlin_sdk.generated.waas.WaasWalletApi
import com.omswallet.kotlin_sdk.generated.waas.WebRpcHttpResponse
import com.omswallet.kotlin_sdk.generated.waas.WebRpcTransport
import com.omswallet.kotlin_sdk.network.OmsWalletEnvironment
import com.omswallet.kotlin_sdk.network.OmsWalletHttpClient

internal class OmsWalletSignedWaasTransport(
    private val projectAccessKey: String,
    private val environment: OmsWalletEnvironment,
    private val httpClient: OmsWalletHttpClient,
    private val nonceGenerator: () -> Long,
    private val privateKey: ByteArray,
    ) : WebRpcTransport {
    override suspend fun post(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): WebRpcHttpResponse {
        val endpoint = resolveEndpoint(path)
        val signedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = endpoint,
            nonce = nonceGenerator().toString(),
            payload = body,
            scope = environment.authorizationScope,
            privateKey = privateKey,
            requestPathPrefix = WaasWalletApi.basePath,
        )

        val response = httpClient.postJsonWithStatus(
            baseUrl = baseUrl,
            path = WaasWalletApi.basePath + endpoint,
            body = body,
            headers = defaultHeaders(headers, signedRequest.authorizationHeader),
        )

        return WebRpcHttpResponse(
            statusCode = response.statusCode,
            body = response.body,
        )
    }

    private fun resolveEndpoint(path: String): String =
        when {
            path.startsWith(WaasWalletApi.basePath) -> path.removePrefix(WaasWalletApi.basePath)
            path.startsWith("/") -> path
            else -> "/$path"
        }

    private fun defaultHeaders(
        headers: Map<String, String>,
        authorizationHeader: String,
    ): Map<String, String> = linkedMapOf(
        OmsWalletEnvironment.accessKeyHeaderName to projectAccessKey,
        "Origin" to "http://localhost:3000",
        "Accept" to "application/json",
        "Authorization" to authorizationHeader.removePrefix(OmsWalletEnvironment.authorizationHeaderPrefix),
    ).apply {
        putAll(headers)
    }
}
