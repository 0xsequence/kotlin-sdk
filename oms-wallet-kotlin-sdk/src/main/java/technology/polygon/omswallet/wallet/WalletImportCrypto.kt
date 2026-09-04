package technology.polygon.omswallet.wallet

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.hpke.HPKE
import technology.polygon.omswallet.models.WalletImportPrivateKey
import java.math.BigInteger

internal object WalletImportCrypto {
    private val secp256k1Order = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private const val base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun plaintext(privateKey: WalletImportPrivateKey): ByteArray =
        when (privateKey) {
            is WalletImportPrivateKey.Ethereum -> {
                val value = privateKey.value.trimAsciiWhitespace()
                val hex = value.removePrefix("0x")
                require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    "Ethereum privateKey must be 32 bytes or 64 hexadecimal characters"
                }
                requireValidEthereumScalar(hex.hexBytes())
                value.toByteArray(Charsets.UTF_8)
            }

            is WalletImportPrivateKey.EthereumBytes -> {
                require(privateKey.value.size == 32) { "Ethereum privateKey must contain exactly 32 bytes" }
                requireValidEthereumScalar(privateKey.value)
                privateKey.value.copyOf()
            }

            is WalletImportPrivateKey.Solana -> {
                val value = privateKey.value.trimAsciiWhitespace()
                val decoded = decodeBase58(value)
                require(decoded.size == 32 || decoded.size == 64) {
                    "Solana privateKey must decode to a 32-byte seed or 64-byte keypair"
                }
                require(value.length != 32 && value.length != 64) {
                    "Solana privateKey string is ambiguous; provide the raw bytes instead"
                }
                value.toByteArray(Charsets.UTF_8)
            }

            is WalletImportPrivateKey.SolanaBytes -> {
                require(privateKey.value.size == 32 || privateKey.value.size == 64) {
                    "Solana privateKey must contain a 32-byte seed or 64-byte keypair"
                }
                privateKey.value.copyOf()
            }
        }

    fun validateReference(reference: String?) {
        require(reference == null || reference.toByteArray(Charsets.UTF_8).size <= 128) {
            "reference must be at most 128 UTF-8 bytes"
        }
    }

    fun sealP256Aes256Gcm(
        recipientPublicKey: ByteArray,
        plaintext: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val subjectPublicKey = SubjectPublicKeyInfo.getInstance(recipientPublicKey).publicKeyData.bytes
        val hpke =
            HPKE(
                HPKE.mode_base,
                HPKE.kem_P256_SHA256,
                HPKE.kdf_HKDF_SHA256,
                HPKE.aead_AES_GCM256,
            )
        val sealed = hpke.seal(hpke.deserializePublicKey(subjectPublicKey), byteArrayOf(), byteArrayOf(), plaintext, null, null, null)
        return sealed[1] to sealed[0]
    }

    private fun requireValidEthereumScalar(value: ByteArray) {
        val scalar = BigInteger(1, value)
        require(scalar.signum() > 0 && scalar < secp256k1Order) {
            "Ethereum privateKey is outside the valid secp256k1 scalar range"
        }
    }

    private fun decodeBase58(value: String): ByteArray {
        require(value.isNotEmpty()) { "Solana privateKey is required" }
        var decoded = BigInteger.ZERO
        value.forEach { character ->
            val digit = base58Alphabet.indexOf(character)
            require(digit >= 0) { "Solana privateKey must be base58 encoded" }
            decoded = decoded.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val encoded = decoded.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return ByteArray(value.takeWhile { it == '1' }.length) +
            if (decoded == BigInteger.ZERO) byteArrayOf() else encoded
    }

    private fun String.trimAsciiWhitespace(): String = trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000c' }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal object WalletImportBase64 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(value: ByteArray): String =
        buildString((value.size + 2) / 3 * 4) {
            var offset = 0
            while (offset < value.size) {
                val remaining = value.size - offset
                val bits =
                    ((value[offset].toInt() and 0xff) shl 16) or
                        (if (remaining > 1) (value[offset + 1].toInt() and 0xff) shl 8 else 0) or
                        (if (remaining > 2) value[offset + 2].toInt() and 0xff else 0)
                append(alphabet[bits ushr 18])
                append(alphabet[(bits ushr 12) and 63])
                append(if (remaining > 1) alphabet[(bits ushr 6) and 63] else '=')
                append(if (remaining > 2) alphabet[bits and 63] else '=')
                offset += 3
            }
        }

    fun decodeCanonical(
        value: String,
        field: String,
    ): ByteArray {
        require(value.isNotEmpty() && value.length % 4 == 0) { "$field must be canonical base64" }
        val padding = value.takeLastWhile { it == '=' }.length
        require(padding <= 2 && value.dropLast(padding).none { it == '=' }) { "$field must be canonical base64" }
        val output = ArrayList<Byte>(value.length / 4 * 3)
        value.chunked(4).forEach { chunk ->
            val digits = chunk.map { if (it == '=') 0 else alphabet.indexOf(it) }
            require(digits.all { it >= 0 }) { "$field must be canonical base64" }
            val bits = (digits[0] shl 18) or (digits[1] shl 12) or (digits[2] shl 6) or digits[3]
            output += (bits ushr 16).toByte()
            if (chunk[2] != '=') output += (bits ushr 8).toByte()
            if (chunk[3] != '=') output += bits.toByte()
        }
        val decoded = output.toByteArray()
        require(decoded.isNotEmpty() && encode(decoded) == value) { "$field must be canonical base64" }
        return decoded
    }
}
