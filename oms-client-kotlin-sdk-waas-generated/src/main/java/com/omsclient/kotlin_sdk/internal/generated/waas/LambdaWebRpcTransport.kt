package com.omsclient.kotlin_sdk.internal.generated.waas

class LambdaWebRpcTransport(
    private val postJson: suspend (
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ) -> WebRpcHttpResponse,
) : WebRpcTransport {
    override suspend fun post(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): WebRpcHttpResponse =
        postJson(
            baseUrl,
            path,
            body,
            headers,
        )
}
