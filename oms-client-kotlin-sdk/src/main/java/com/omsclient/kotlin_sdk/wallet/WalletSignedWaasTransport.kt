package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.generated.waas.WebRpcHttpResponse
import com.omsclient.kotlin_sdk.generated.waas.WebRpcTransport
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient

internal class WalletSignedWaasTransport(
    private val projectAccessKey: String,
    private val environment: OMSClientEnvironment,
    private val httpClient: OMSClientHttpClient,
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
        OMSClientEnvironment.accessKeyHeaderName to projectAccessKey,
        "Origin" to "http://localhost:3000",
        "Accept" to "application/json",
        "Authorization" to authorizationHeader.removePrefix(OMSClientEnvironment.authorizationHeaderPrefix),
    ).apply {
        putAll(headers)
    }
}
