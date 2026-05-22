package com.omsclient.kotlin_sdk.wallet

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = WalletSigningAlgorithmSerializer::class)
internal enum class WalletSigningAlgorithm(
    val wireValue: String,
) {
    ECDSA_P256_SHA256("ecdsa-p256-sha256"),
    ECDSA_P256K_EIP191("ecdsa-p256k-eip191"),
    UNKNOWN_DEFAULT("UNKNOWN_DEFAULT"),
    ;

    companion object {
        fun fromWireValue(value: String): WalletSigningAlgorithm = values().find { it.wireValue == value } ?: UNKNOWN_DEFAULT
    }
}

internal object WalletSigningAlgorithmSerializer : KSerializer<WalletSigningAlgorithm> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: WalletSigningAlgorithm,
    ) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): WalletSigningAlgorithm = WalletSigningAlgorithm.fromWireValue(decoder.decodeString())
}

internal interface CredentialSigner {
    val signingAlgorithm: WalletSigningAlgorithm

    suspend fun credentialId(): String

    suspend fun nextNonce(): String

    suspend fun sign(preimage: String): String

    fun hasCredential(): Boolean

    fun clear()
}
