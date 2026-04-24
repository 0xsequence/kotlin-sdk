package com.omsclient.kotlin_sdk.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class OMSClientHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class OMSClientHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("OMS Client request failed with status $statusCode")

internal class OMSClientHttpClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun postJson(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): OMSClientHttpResponse {
        val response = postJsonWithStatus(
            baseUrl = baseUrl,
            path = path,
            body = body,
            headers = headers,
        )
        if (response.statusCode !in 200..299) {
            throw OMSClientHttpException(response.statusCode, response.body)
        }
        return response
    }

    suspend fun postJsonWithStatus(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): OMSClientHttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, path))
            .post(body.toRequestBody(jsonMediaType))
            .apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            OMSClientHttpResponse(
                statusCode = response.code,
                body = responseBody,
            )
        }
    }

    private fun joinUrl(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}
