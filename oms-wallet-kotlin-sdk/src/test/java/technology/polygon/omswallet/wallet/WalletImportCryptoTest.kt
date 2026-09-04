package technology.polygon.omswallet.wallet

import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import technology.polygon.omswallet.WalletImportConfiguration
import technology.polygon.omswallet.models.WalletImportPrivateKey

class WalletImportCryptoTest {
    @Test
    fun configurationRejectsMalformedAndAllZeroPcr0s() {
        listOf(
            emptyList(),
            listOf("0".repeat(95)),
            listOf("0".repeat(96)),
            listOf("z".repeat(96)),
        ).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) { WalletImportConfiguration(values) }
        }
        WalletImportConfiguration(listOf("0x" + "a".repeat(96)))
    }

    @Test
    fun privateKeyValidationCoversScalarAndLengthBoundaries() {
        val one = ByteArray(32).also { it[31] = 1 }
        assertArrayEquals(one, WalletImportCrypto.plaintext(WalletImportPrivateKey.EthereumBytes(one)))
        assertThrows(IllegalArgumentException::class.java) {
            WalletImportCrypto.plaintext(WalletImportPrivateKey.EthereumBytes(ByteArray(32)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletImportCrypto.plaintext(
                WalletImportPrivateKey.Ethereum("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletImportCrypto.plaintext(WalletImportPrivateKey.SolanaBytes(ByteArray(31)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletImportCrypto.validateReference("é".repeat(65))
        }
    }

    @Test
    fun p256HpkeCiphertextOpensWithStandardBouncyCastleReceiver() {
        val hpke = HPKE(HPKE.mode_base, HPKE.kem_P256_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)
        val recipient = hpke.generatePrivateKey()
        val spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(recipient.public).encoded
        val plaintext = ("0x" + "11".repeat(32)).toByteArray()

        val (encapsulation, ciphertext) = WalletImportCrypto.sealP256Aes256Gcm(spki, plaintext)
        val opened = hpke.open(encapsulation, recipient, byteArrayOf(), byteArrayOf(), ciphertext, null, null, null)

        assertArrayEquals(plaintext, opened)
    }
}
