package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.utils.OMSClientBase64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

internal object OidcIdToken {
    private val json = Json { ignoreUnknownKeys = true }

    fun expiresAtEpochSeconds(idToken: String): Long {
        val payload = parsePayload(idToken)
        return requireNotNull(payload["exp"]?.jsonPrimitive?.longOrNull) {
            "OIDC ID token is missing an exp claim"
        }
    }

    fun handleHash(idToken: String): String =
        OMSClientBase64Url.encodeNoPadding(
            MessageDigest.getInstance("SHA-256").digest(idToken.toByteArray(Charsets.UTF_8)),
        )

    private fun parsePayload(idToken: String): JsonObject {
        val parts = idToken.split('.')
        require(parts.size >= 2) { "OIDC ID token must contain header and payload sections" }
        val payloadJson = String(OMSClientBase64Url.decode(parts[1]), Charsets.UTF_8)
        return json.parseToJsonElement(payloadJson).jsonObject
    }
}
