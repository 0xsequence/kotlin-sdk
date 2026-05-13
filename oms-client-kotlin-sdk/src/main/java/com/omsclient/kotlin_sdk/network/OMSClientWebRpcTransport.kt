package com.omsclient.kotlin_sdk.network

import com.omsclient.kotlin_sdk.generated.waas.WebRpcHttpResponse
import com.omsclient.kotlin_sdk.generated.waas.WebRpcTransport

internal class OMSClientWebRpcTransport(
    private val httpClient: OMSClientHttpClient,
) : WebRpcTransport {
    override suspend fun post(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): WebRpcHttpResponse {
        val response =
            httpClient.postJsonWithStatus(
                baseUrl = baseUrl,
                path = path,
                body = body,
                headers = headers,
            )

        return WebRpcHttpResponse(
            statusCode = response.statusCode,
            body = response.body,
        )
    }
}
