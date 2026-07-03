package technology.polygon.omswallet.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal object OMSWalletJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
        }
}

internal fun parseJsonObject(body: String): JsonObject = OMSWalletJson.json.parseToJsonElement(body).jsonObject

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> = (this[name] as? JsonArray)?.toList() ?: emptyList()
