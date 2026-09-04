package technology.polygon.omswallet.wallet

import com.upokecenter.cbor.CBORObject
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import technology.polygon.omswallet.OMSWalletAttestationException
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.WalletImportConfiguration
import technology.polygon.omswallet.models.WalletImportPrivateKey
import java.security.MessageDigest

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
            WalletImportCrypto.plaintext(WalletImportPrivateKey.Solana("1".repeat(32)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletImportCrypto.validateReference("é".repeat(65))
        }
    }

    @Test
    fun canonicalBase64CoversPaddingAndRejectsNonCanonicalInputs() {
        listOf(
            byteArrayOf(0),
            byteArrayOf(0, 1),
            byteArrayOf(0, 1, 2),
            byteArrayOf(0, 1, 2, 3),
        ).forEach { value ->
            assertArrayEquals(value, WalletImportBase64.decodeCanonical(WalletImportBase64.encode(value), "value"))
        }

        listOf("", "AQ", "AB==", "A===", "AA=A").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                WalletImportBase64.decodeCanonical(value, "value")
            }
        }
    }

    @Test
    fun attestationVerifierRejectsUntrustedOrMismatchedDocuments() {
        val now = 1_800_000_000_000L
        val trustedPcr0 = ByteArray(48) { 0xaa.toByte() }
        val trustedPcr0s = setOf(trustedPcr0.toHex())
        val requestBody = "{}"
        val responseBody = """{"keyId":"key-id"}"""
        val nonce = "test-nonce"

        expectAttestationFailure("invalid COSE_Sign1 structure") {
            verifyAttestation(
                encodedDocument = WalletImportBase64.encode(byteArrayOf(0)),
                requestBody = requestBody,
                responseBody = responseBody,
                nonce = nonce,
                trustedPcr0s = trustedPcr0s,
                now = now,
            )
        }
        expectAttestationFailure("freshness window") {
            verifySyntheticAttestation(
                timestamp = now - 6 * 60 * 1_000,
                pcr0 = trustedPcr0,
                nonce = nonce,
                requestBody = requestBody,
                responseBody = responseBody,
                trustedPcr0s = trustedPcr0s,
                now = now,
            )
        }
        expectAttestationFailure("PCR0 is not trusted") {
            verifySyntheticAttestation(
                timestamp = now,
                pcr0 = ByteArray(48) { 0xbb.toByte() },
                nonce = nonce,
                requestBody = requestBody,
                responseBody = responseBody,
                trustedPcr0s = trustedPcr0s,
                now = now,
            )
        }
        expectAttestationFailure("nonce does not match") {
            verifySyntheticAttestation(
                timestamp = now,
                pcr0 = trustedPcr0,
                nonce = nonce,
                requestBody = requestBody,
                responseBody = responseBody,
                trustedPcr0s = trustedPcr0s,
                now = now,
                verificationNonce = "different-nonce",
            )
        }
        expectAttestationFailure("not bound to the request and response") {
            verifySyntheticAttestation(
                timestamp = now,
                pcr0 = trustedPcr0,
                nonce = nonce,
                requestBody = requestBody,
                responseBody = responseBody,
                trustedPcr0s = trustedPcr0s,
                now = now,
                verificationResponseBody = "{}",
            )
        }
        expectAttestationFailure("does not use the AWS Nitro root") {
            verifySyntheticAttestation(
                timestamp = now,
                pcr0 = trustedPcr0,
                nonce = nonce,
                requestBody = requestBody,
                responseBody = responseBody,
                trustedPcr0s = trustedPcr0s,
                now = now,
            )
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

    private fun verifySyntheticAttestation(
        timestamp: Long,
        pcr0: ByteArray,
        nonce: String,
        requestBody: String,
        responseBody: String,
        trustedPcr0s: Set<String>,
        now: Long,
        verificationNonce: String = nonce,
        verificationResponseBody: String = responseBody,
    ) {
        val method = "POST"
        val path = "/v1/Waas/GetRecipientKey"
        val preimage = "$method $path\n$requestBody\n$responseBody"
        val hash = WalletImportBase64.encode(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()))
        val protectedHeader = CBORObject.NewMap().apply { Add(1, -35) }.EncodeToBytes()
        val pcrs = CBORObject.NewMap().apply { Add(0, pcr0) }
        val payload =
            CBORObject
                .NewMap()
                .apply {
                    Add("digest", "SHA384")
                    Add("timestamp", timestamp)
                    Add("pcrs", pcrs)
                    Add("certificate", byteArrayOf(1))
                    Add("cabundle", CBORObject.NewArray().apply { Add(byteArrayOf(2)) })
                    Add("user_data", "Sequence/1:$hash".toByteArray())
                    Add("nonce", nonce.toByteArray())
                }.EncodeToBytes()
        val document =
            CBORObject
                .NewArray()
                .apply {
                    Add(protectedHeader)
                    Add(CBORObject.NewMap())
                    Add(payload)
                    Add(ByteArray(96))
                }.WithTag(18)

        verifyAttestation(
            encodedDocument = WalletImportBase64.encode(document.EncodeToBytes()),
            requestBody = requestBody,
            responseBody = verificationResponseBody,
            nonce = verificationNonce,
            trustedPcr0s = trustedPcr0s,
            now = now,
        )
    }

    private fun verifyAttestation(
        encodedDocument: String,
        requestBody: String,
        responseBody: String,
        nonce: String,
        trustedPcr0s: Set<String>,
        now: Long,
    ) {
        AttestationVerifier.verify(
            encodedDocument = encodedDocument,
            method = "POST",
            path = "/v1/Waas/GetRecipientKey",
            requestBody = requestBody,
            responseBody = responseBody,
            nonce = nonce,
            trustedPcr0s = trustedPcr0s,
            nowMillis = now,
        )
    }

    private fun expectAttestationFailure(
        expectedMessage: String,
        operation: () -> Unit,
    ) {
        val exception = assertThrows(OMSWalletAttestationException::class.java, operation)
        assertEquals(OMSWalletErrorCode.AttestationVerificationFailed, exception.code)
        assertTrue(requireNotNull(exception.message).contains(expectedMessage))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
