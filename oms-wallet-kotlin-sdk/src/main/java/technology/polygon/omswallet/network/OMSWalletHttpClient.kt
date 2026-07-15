package technology.polygon.omswallet.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class OMSWalletHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class OMSWalletHttpClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun postJsonWithStatus(
        baseUrl: String,
        path: String,
        body: String,
        headers: Map<String, String>,
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

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                OMSWalletHttpResponse(
                    statusCode = response.code,
                    body = responseBody,
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
