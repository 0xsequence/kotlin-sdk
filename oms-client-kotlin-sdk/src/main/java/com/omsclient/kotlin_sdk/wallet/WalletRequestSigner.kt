package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.KeyType
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

internal data class SignedWalletRequest(
    val payload: String,
    val preimage: String,
    val digestHex: String,
    val address: String,
    val signature: String,
    val authorizationHeader: String,
)

internal object WalletRequestSigner {
    fun generatePrivateKeyBytes(): ByteArray =
        try {
            Numeric.toBytesPadded(Keys.createEcKeyPair().privateKey, PRIVATE_KEY_SIZE_BYTES)
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException("Unable to generate secp256k1 private key", exception)
        }

    fun generatePrivateKeyHex(): String = Numeric.toHexString(generatePrivateKeyBytes())

    fun buildWalletRequestPreimage(
        endpoint: String,
        nonce: String,
        payload: String,
        requestPathPrefix: String = DEFAULT_WALLET_REQUEST_PATH_PREFIX,
    ): String = "POST ${requestPathPrefix.trimEnd('/')}$endpoint\nnonce: $nonce\n\n$payload"

    fun walletRequestPreimageDigestHex(preimage: String): String =
        Numeric.toHexString(Hash.sha3(preimage.toByteArray(StandardCharsets.UTF_8)))

    fun walletAddressFromPrivateKey(privateKey: ByteArray): String {
        require(privateKey.size == PRIVATE_KEY_SIZE_BYTES) {
            "Expected a 32-byte secp256k1 private key, got ${privateKey.size} bytes"
        }

        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        return Numeric.prependHexPrefix(Keys.getAddress(keyPair).lowercase())
    }

    fun walletAddressFromPrivateKeyHex(privateKeyHex: String): String = walletAddressFromPrivateKey(privateKeyFromHex(privateKeyHex))

    fun signWalletDigestHexEip191(
        privateKey: ByteArray,
        digestHex: String,
    ): String {
        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        val signature = Sign.signPrefixedMessage(digestHex.toByteArray(StandardCharsets.UTF_8), keyPair)
        return signature.toHexString()
    }

    fun signWalletDigestHexEip191(
        privateKeyHex: String,
        digestHex: String,
    ): String = signWalletDigestHexEip191(privateKeyFromHex(privateKeyHex), digestHex)

    fun signWalletRequestPreimage(
        privateKey: ByteArray,
        preimage: String,
    ): String = signWalletDigestHexEip191(privateKey, walletRequestPreimageDigestHex(preimage))

    fun signWalletRequestPreimage(
        privateKeyHex: String,
        preimage: String,
    ): String = signWalletRequestPreimage(privateKeyFromHex(privateKeyHex), preimage)

    fun buildWalletAuthorizationHeader(
        scope: String,
        address: String,
        nonce: String,
        signature: String,
    ): String =
        buildWalletAuthorizationHeader(
            keyType = KeyType.Ethereum_Secp256k1,
            scope = scope,
            credentialId = address,
            nonce = nonce,
            signature = signature,
        )

    fun buildWalletAuthorizationHeader(
        keyType: KeyType,
        scope: String,
        credentialId: String,
        nonce: String,
        signature: String,
    ): String = "Authorization: ${keyType.wireValue} scope=\"$scope\",cred=\"$credentialId\",nonce=$nonce,sig=\"$signature\""

    fun signWalletRequest(
        endpoint: String,
        nonce: String,
        payload: String,
        scope: String,
        privateKey: ByteArray,
        requestPathPrefix: String = DEFAULT_WALLET_REQUEST_PATH_PREFIX,
    ): SignedWalletRequest {
        val preimage =
            buildWalletRequestPreimage(
                endpoint = endpoint,
                nonce = nonce,
                payload = payload,
                requestPathPrefix = requestPathPrefix,
            )
        val digestHex = walletRequestPreimageDigestHex(preimage)
        val address = walletAddressFromPrivateKey(privateKey)
        val signature = signWalletDigestHexEip191(privateKey, digestHex)
        val authorizationHeader =
            buildWalletAuthorizationHeader(
                scope = scope,
                address = address,
                nonce = nonce,
                signature = signature,
            )

        return SignedWalletRequest(
            payload = payload,
            preimage = preimage,
            digestHex = digestHex,
            address = address,
            signature = signature,
            authorizationHeader = authorizationHeader,
        )
    }

    fun signWalletRequest(
        endpoint: String,
        nonce: String,
        payload: String,
        scope: String,
        privateKeyHex: String,
        requestPathPrefix: String = DEFAULT_WALLET_REQUEST_PATH_PREFIX,
    ): SignedWalletRequest =
        signWalletRequest(
            endpoint = endpoint,
            nonce = nonce,
            payload = payload,
            scope = scope,
            privateKey = privateKeyFromHex(privateKeyHex),
            requestPathPrefix = requestPathPrefix,
        )

    private fun privateKeyFromHex(privateKeyHex: String): ByteArray {
        val bytes = Numeric.hexStringToByteArray(privateKeyHex)
        require(bytes.size == PRIVATE_KEY_SIZE_BYTES) {
            "Expected a 32-byte secp256k1 private key, got ${bytes.size} bytes"
        }
        return bytes
    }

    private fun Sign.SignatureData.toHexString(): String = Numeric.toHexString(r + s + v)

    private const val PRIVATE_KEY_SIZE_BYTES = 32
    private const val DEFAULT_WALLET_REQUEST_PATH_PREFIX = "/rpc/Wallet"
}
