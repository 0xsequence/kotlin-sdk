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
    private val signer: CredentialSigner,
) : WebRpcTransport {
    override suspend fun post(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): WebRpcHttpResponse {
        val endpoint = resolveEndpoint(path)
        val nonce = signer.nextNonce()
        val preimage =
            WalletRequestSigner.buildWalletRequestPreimage(
                endpoint = endpoint,
                nonce = nonce,
                scope = environment.authorizationScope,
                payload = body,
                requestPathPrefix = WaasWalletApi.basePath,
            )
        val authorizationHeader =
            WalletRequestSigner.buildWalletAuthorizationHeader(
                keyType = signer.keyType,
                scope = environment.authorizationScope,
                credentialId = signer.credentialId(),
                nonce = nonce,
                signature = signer.sign(preimage),
            )

        val response =
            httpClient.postJsonWithStatus(
                baseUrl = baseUrl,
                path = WaasWalletApi.basePath + endpoint,
                body = body,
                headers = defaultHeaders(headers, authorizationHeader),
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
    ): Map<String, String> =
        linkedMapOf(
            OMSClientEnvironment.accessKeyHeaderName to projectAccessKey,
            "Origin" to "http://localhost:3000",
            "Accept" to "application/json",
            "Authorization" to authorizationHeader.removePrefix(OMSClientEnvironment.authorizationHeaderPrefix),
        ).apply {
            putAll(headers)
        }
}
