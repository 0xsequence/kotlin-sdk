package com.polygon_wallet.polygon_kotlin_sdk.wallet

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

internal object OidcIdToken {
    private val json = Json { ignoreUnknownKeys = true }

    fun expiresAtEpochSeconds(idToken: String): Long {
        val payload = parsePayload(idToken)
        return requireNotNull(payload["exp"]?.jsonPrimitive?.longOrNull) {
            "OIDC ID token is missing an exp claim"
        }
    }

    // Temporary parity shim: the current WaaS API still expects the full ID token
    // handle encoded as keccak/hex. Once the API is updated, switch this back to
    // SHA-256/base64 to match the intended contract.
    fun handleHash(idToken: String): String =
        Numeric.toHexString(Hash.sha3(idToken.toByteArray(Charsets.UTF_8)))

    private fun parsePayload(idToken: String): JsonObject {
        val parts = idToken.split('.')
        require(parts.size >= 2) { "OIDC ID token must contain header and payload sections" }
        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        return json.parseToJsonElement(payloadJson).jsonObject
    }
}
