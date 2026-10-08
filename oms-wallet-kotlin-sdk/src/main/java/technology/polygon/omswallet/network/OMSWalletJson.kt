package technology.polygon.omswallet.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal object OMSWalletJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
        }
}

internal fun parseJsonObject(body: String): JsonObject = OMSWalletJson.json.parseToJsonElement(body).jsonObject

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.objectOrNull(name: String): JsonObject? {
    val value = this[name] ?: return null
    if (value === JsonNull) return null
    return value as? JsonObject ?: throw IllegalArgumentException("Invalid $name")
}
