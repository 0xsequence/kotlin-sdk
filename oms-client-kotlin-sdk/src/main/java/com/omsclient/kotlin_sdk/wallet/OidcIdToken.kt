package com.omsclient.kotlin_sdk.wallet

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object OidcIdToken {
    private val json = Json { ignoreUnknownKeys = true }

    fun expiresAtEpochSeconds(idToken: String): Long {
        val payload = parsePayload(idToken)
        return requireNotNull(payload["exp"]?.jsonPrimitive?.longOrNull) {
            "OIDC ID token is missing an exp claim"
        }
    }

    fun handleHash(idToken: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(idToken.toByteArray(Charsets.UTF_8)),
        )

    private fun parsePayload(idToken: String): JsonObject {
        val parts = idToken.split('.')
        require(parts.size >= 2) { "OIDC ID token must contain header and payload sections" }
        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        return json.parseToJsonElement(payloadJson).jsonObject
    }
}
