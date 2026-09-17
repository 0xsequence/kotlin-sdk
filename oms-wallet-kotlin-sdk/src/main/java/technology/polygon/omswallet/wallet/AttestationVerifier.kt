package technology.polygon.omswallet.wallet

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import technology.polygon.omswallet.OMSWalletAttestationException
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import kotlin.math.abs

internal object AttestationVerifier {
    private const val awsNitroRootSha256 = "641a0321a3e244efe456463195d606317ed7cdcc3c1756e09893f3c68f79bb5b"
    private const val maxAgeMillis = 5 * 60 * 1_000L

    fun verify(
        encodedDocument: String,
        method: String,
        path: String,
        requestBody: String,
        responseBody: String,
        nonce: String,
        trustedPcr0s: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        try {
            val documentBytes = WalletImportBase64.decodeCanonical(encodedDocument, "attestation document")
            val decoded = CBORObject.DecodeFromBytes(documentBytes)
            require(decoded.HasOneTag(18)) { "WaaS attestation has an invalid COSE_Sign1 structure" }
            val cose = decoded.UntagOne()
            require(cose.type == CBORType.Array && cose.size() == 4) {
                "WaaS attestation has an invalid COSE_Sign1 structure"
            }
            val protectedHeader = cose[0].requiredBytes("WaaS attestation has an invalid COSE_Sign1 structure")
            require(cose[1].type == CBORType.Map && cose[1].size() == 0) {
                "WaaS attestation has an invalid COSE_Sign1 structure"
            }
            val payload = cose[2].requiredBytes("WaaS attestation has an invalid COSE_Sign1 structure")
            val signature = cose[3].requiredBytes("WaaS attestation has an invalid COSE_Sign1 structure")
            require(signature.size == 96) { "WaaS attestation has an invalid COSE_Sign1 structure" }

            val protected = CBORObject.DecodeFromBytes(protectedHeader)
            require(
                protected.type == CBORType.Map &&
                    protected[CBORObject.FromObject(1)]?.AsInt32Value() == -35,
            ) { "WaaS attestation does not use COSE ES384" }

            val fields = CBORObject.DecodeFromBytes(payload)
            require(fields.type == CBORType.Map) { "WaaS attestation payload is not a CBOR map" }
            require(fields["digest"]?.AsString() == "SHA384") { "WaaS attestation payload is missing required fields" }
            val timestamp =
                fields["timestamp"]?.takeIf { it.type == CBORType.Integer }?.AsInt64Value()
                    ?: error("WaaS attestation payload is missing required fields")
            val pcrs =
                fields["pcrs"]?.takeIf { it.type == CBORType.Map }
                    ?: error("WaaS attestation payload is missing required fields")
            val certificate =
                fields["certificate"]?.requiredBytes("WaaS attestation payload is missing required fields")
                    ?: error("WaaS attestation payload is missing required fields")
            val cabundle =
                fields["cabundle"]?.takeIf { it.type == CBORType.Array }
                    ?: error("WaaS attestation payload is missing required fields")
            val userData =
                fields["user_data"]?.requiredBytes("WaaS attestation payload is missing required fields")
                    ?: error("WaaS attestation payload is missing required fields")
            val documentNonce =
                fields["nonce"]?.requiredBytes("WaaS attestation payload is missing required fields")
                    ?: error("WaaS attestation payload is missing required fields")

            require(abs(timestamp - nowMillis) <= maxAgeMillis) {
                "WaaS attestation timestamp is outside the accepted freshness window"
            }
            require(pcrs.size() in 1..32) { "WaaS attestation contains an invalid PCR measurement" }
            pcrs.entries.forEach { entry ->
                val index = entry.key.takeIf { it.type == CBORType.Integer }?.AsInt32Value()
                val measurement = entry.value.takeIf { it.type == CBORType.ByteString }?.GetByteString()
                require(index != null && index in 0..31 && measurement?.size in setOf(32, 48, 64)) {
                    "WaaS attestation contains an invalid PCR measurement"
                }
            }
            val pcr0 = pcrs[CBORObject.FromObject(0)]?.requiredBytes("WaaS attestation PCR0 is not trusted")
            require(pcr0 != null && pcr0.toHex() in trustedPcr0s) { "WaaS attestation PCR0 is not trusted" }
            require(documentNonce.contentEquals(nonce.toByteArray(Charsets.UTF_8))) {
                "WaaS attestation nonce does not match the request"
            }
            val preimage = "${method.uppercase()} $path\n$requestBody\n$responseBody"
            val hash = WalletImportBase64.encode(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()))
            require(userData.contentEquals("Sequence/1:$hash".toByteArray(Charsets.UTF_8))) {
                "WaaS attestation is not bound to the request and response"
            }

            require(cabundle.size() > 0) { "WaaS attestation certificate bundle is invalid" }
            val authorities = (0 until cabundle.size()).map { cabundle[it].requiredBytes("WaaS attestation certificate bundle is invalid") }
            val leaf = verifyCertificateChain(certificate, authorities, nowMillis)

            val signatureInput =
                CBORObject
                    .NewArray()
                    .apply {
                        Add("Signature1")
                        Add(protectedHeader)
                        Add(byteArrayOf())
                        Add(payload)
                    }.EncodeToBytes()
            val verifier = Signature.getInstance("SHA384withECDSA")
            verifier.initVerify(leaf.publicKey)
            verifier.update(signatureInput)
            require(verifier.verify(rawEcdsaSignatureToDer(signature))) { "WaaS attestation signature is invalid" }
        } catch (exception: OMSWalletAttestationException) {
            throw exception
        } catch (exception: Exception) {
            throw OMSWalletAttestationException(message = exception.message ?: "WaaS attestation verification failed", cause = exception)
        }
    }

    private fun verifyCertificateChain(
        leafBytes: ByteArray,
        authoritiesBytes: List<ByteArray>,
        nowMillis: Long,
    ): X509Certificate {
        require(authoritiesBytes.first().sha256Hex() == awsNitroRootSha256) {
            "WaaS attestation certificate chain does not use the AWS Nitro root"
        }
        val factory = CertificateFactory.getInstance("X.509")

        fun certificate(bytes: ByteArray): X509Certificate = factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate

        val leaf = certificate(leafBytes)
        val authorities = authoritiesBytes.map(::certificate)
        val chain = listOf(leaf) + authorities.drop(1)
        val parameters =
            PKIXParameters(setOf(TrustAnchor(authorities.first(), null))).apply {
                isRevocationEnabled = false
                date = java.util.Date(nowMillis)
            }
        CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), parameters)

        (listOf(leaf) + authorities).forEachIndexed { index, certificate ->
            certificate.checkValidity(java.util.Date(nowMillis))
            val usages = certificate.keyUsage
            if (index == 0) {
                require(certificate.basicConstraints < 0 && usages?.getOrNull(0) == true) {
                    "WaaS attestation leaf certificate has invalid constraints"
                }
            } else {
                require(certificate.basicConstraints >= index - 1 && usages?.getOrNull(5) == true) {
                    "WaaS attestation CA certificate has invalid constraints"
                }
            }
        }
        return leaf
    }

    private fun rawEcdsaSignatureToDer(signature: ByteArray): ByteArray {
        require(signature.size == 96) { "WaaS attestation signature is invalid" }

        fun integer(value: ByteArray): ByteArray {
            var bytes = value.dropWhile { it == 0.toByte() }.toByteArray()
            if (bytes.isEmpty()) bytes = byteArrayOf(0)
            if (bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0) + bytes
            return byteArrayOf(0x02) + derLength(bytes.size) + bytes
        }
        val r = integer(signature.copyOfRange(0, 48))
        val s = integer(signature.copyOfRange(48, 96))
        return byteArrayOf(0x30) + derLength(r.size + s.size) + r + s
    }

    private fun derLength(length: Int): ByteArray =
        if (length < 128) byteArrayOf(length.toByte()) else byteArrayOf(0x81.toByte(), length.toByte())

    private fun CBORObject.requiredBytes(message: String): ByteArray {
        require(type == CBORType.ByteString) { message }
        return GetByteString()
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
