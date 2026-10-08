package technology.polygon.omswallet.storage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletSessionAuth
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletKeyOrigin
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.network.OMSWalletJson
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm
import technology.polygon.omswallet.wallet.isEthereumHexAddress

/**
 * Encodes and decodes the persisted completed-session record.
 *
 * Version 2 stores the full selected wallet (id, type, address, reference, and
 * key origin) so wallet-family checks use the stored type. Records written by
 * SDK 0.3.x carry no version or wallet type; they are rejected so the store
 * discards them and the user signs in again.
 */
internal object PersistedSessionRecord {
    const val VERSION: Int = 2

    fun encode(snapshot: OMSWalletSessionSnapshot): String {
        val wallet = requireNotNull(snapshot.wallet) { "Cannot persist pending OMS Wallet auth state" }
        val auth = requireNotNull(snapshot.auth) { "Cannot persist OMS Wallet session without auth metadata" }
        val expiresAt =
            requireNotNull(snapshot.expiresAt?.takeIf(String::isNotBlank)) {
                "Cannot persist OMS Wallet session without an expiry"
            }
        return buildJsonObject {
            put("version", VERSION)
            put(
                "wallet",
                buildJsonObject {
                    put("id", wallet.id)
                    put("type", wallet.type.wireValue)
                    put("address", wallet.address)
                    wallet.reference?.let { put("reference", it) }
                    put("keyOrigin", wallet.keyOrigin.wireValue)
                },
            )
            put("signerAddress", snapshot.signerAddress)
            put("signerKeyType", snapshot.signerKeyType?.wireValue)
            put("expiresAt", expiresAt)
            put("auth", auth.toJson())
        }.toString()
    }

    /** @throws InvalidSessionMetadataException when the record is malformed, outdated, or not restorable. */
    fun decode(source: String): OMSWalletSessionSnapshot {
        val root =
            try {
                OMSWalletJson.json.parseToJsonElement(source) as? JsonObject
            } catch (throwable: IllegalArgumentException) {
                throw InvalidSessionMetadataException("Invalid OMS Wallet session metadata", throwable)
            } ?: invalid()
        // Records without a version (SDK 0.3.x) or from another version are discarded.
        if ((root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != VERSION) invalid()
        val wallet = (root["wallet"] as? JsonObject)?.toStoredWallet() ?: invalid()
        val signerAddress = root.optionalString("signerAddress") ?: invalid()
        val signerKeyType =
            root
                .optionalString("signerKeyType")
                ?.let(WalletSigningAlgorithm::fromWireValue)
                ?.takeIf { it == WalletSigningAlgorithm.ECDSA_P256_SHA256 }
                ?: invalid()
        val expiresAt = root.optionalString("expiresAt") ?: invalid()
        val auth = (root["auth"] as? JsonObject)?.toSessionAuth() ?: invalid()
        return OMSWalletSessionSnapshot(
            wallet = wallet,
            signerAddress = signerAddress,
            signerKeyType = signerKeyType,
            expiresAt = expiresAt,
            auth = auth,
        )
    }

    private fun invalid(): Nothing = throw InvalidSessionMetadataException("Invalid OMS Wallet session metadata")

    private fun JsonObject.toStoredWallet(): Wallet? {
        val id = optionalString("id") ?: return null
        val address = optionalString("address") ?: return null
        val type =
            optionalString("type")
                ?.let { value -> WalletType.entries.firstOrNull { it.wireValue == value } }
                ?.takeIf { it != WalletType.UNKNOWN_DEFAULT }
                ?: return null
        val keyOrigin =
            optionalString("keyOrigin")
                ?.let { value -> WalletKeyOrigin.entries.firstOrNull { it.wireValue == value } }
                ?.takeIf { it != WalletKeyOrigin.UNKNOWN_DEFAULT }
                ?: return null
        val referenceElement = this["reference"]
        val reference =
            when {
                referenceElement == null || referenceElement === JsonNull -> null
                referenceElement is JsonPrimitive && referenceElement.isString -> referenceElement.content
                else -> return null
            }
        if (type == WalletType.Ethereum && !address.isEthereumHexAddress()) return null
        return Wallet(id = id, type = type, address = address, reference = reference, keyOrigin = keyOrigin)
    }

    private fun OMSWalletSessionAuth.toJson(): JsonObject =
        when (this) {
            is OMSWalletEmailSessionAuth -> {
                buildJsonObject {
                    put("type", "email")
                    put("email", email)
                }
            }

            is OMSWalletOidcSessionAuth -> {
                buildJsonObject {
                    put("type", "oidc")
                    put("flow", flow.wireValue)
                    put("issuer", issuer)
                    put("provider", provider)
                    put("providerLabel", providerLabel)
                    put("email", email)
                }
            }
        }

    private fun JsonObject.toSessionAuth(): OMSWalletSessionAuth? =
        when (optionalString("type")) {
            "email" -> {
                optionalString("email")?.let(::OMSWalletEmailSessionAuth)
            }

            "oidc" -> {
                val issuer = optionalString("issuer") ?: return null
                val flow = optionalString("flow")?.toSessionAuthFlow() ?: return null
                OMSWalletOidcSessionAuth(
                    flow = flow,
                    issuer = issuer,
                    provider = optionalString("provider"),
                    providerLabel = optionalString("providerLabel"),
                    email = optionalString("email"),
                )
            }

            else -> {
                null
            }
        }

    /** Returns a non-blank string value, or null for missing, null, blank, or non-string values. */
    private fun JsonObject.optionalString(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull?.ifBlank { null }
    }

    private val OMSWalletOidcSessionAuthFlow.wireValue: String
        get() =
            when (this) {
                OMSWalletOidcSessionAuthFlow.Redirect -> "redirect"
                OMSWalletOidcSessionAuthFlow.IdToken -> "id-token"
            }

    private fun String.toSessionAuthFlow(): OMSWalletOidcSessionAuthFlow? =
        when (this) {
            "redirect" -> OMSWalletOidcSessionAuthFlow.Redirect
            "id-token" -> OMSWalletOidcSessionAuthFlow.IdToken
            else -> null
        }
}
