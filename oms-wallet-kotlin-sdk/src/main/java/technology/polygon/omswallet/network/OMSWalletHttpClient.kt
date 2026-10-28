package technology.polygon.omswallet.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal data class OMSWalletHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
)

internal class OMSWalletHttpClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun postJsonWithStatus(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
        /**
         * Bounds the whole call, including reading the response, by the SDK's remaining time; an
         * app client's shorter call timeout still applies. Null keeps the client's own timeouts.
         */
        timeoutMillis: Long? = null,
    ): OMSWalletHttpResponse =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(joinUrl(baseUrl, path))
                    .post(body.toRequestBody(jsonMediaType))
                    .apply {
                        headers.forEach { (name, value) -> addHeader(name, value) }
                    }.build()

            val call = okHttpClient.newCall(request)
            timeoutMillis?.let { remainingMillis ->
                // Zero means the client sets no call timeout.
                val clientCallTimeoutMillis = okHttpClient.callTimeoutMillis.toLong()
                val callTimeoutMillis =
                    if (clientCallTimeoutMillis > 0) minOf(clientCallTimeoutMillis, remainingMillis) else remainingMillis
                call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            call.execute().use { response ->
                val responseBody = response.body.string()
                OMSWalletHttpResponse(
                    statusCode = response.code,
                    body = responseBody,
                    headers = response.headers.associate { it.first.lowercase() to it.second },
                )
            }
        }

    private fun joinUrl(
        baseUrl: String,
        path: String,
    ): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}
