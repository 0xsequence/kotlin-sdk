package com.omsclient.kotlin_sdk.wallet

internal object P256EcdsaSignatureEncoding {
    fun derToRaw(derSignature: ByteArray): ByteArray {
        val reader = DerReader(derSignature)
        reader.expectTag(SEQUENCE_TAG)
        val sequenceEnd = reader.readLengthEnd()
        val r = reader.readInteger()
        val s = reader.readInteger()
        require(reader.position == sequenceEnd && reader.position == derSignature.size) {
            "Invalid DER ECDSA signature trailing data"
        }
        return r + s
    }

    internal fun rawToDer(rawSignature: ByteArray): ByteArray {
        require(rawSignature.size == RAW_SIGNATURE_SIZE_BYTES) {
            "Expected 64-byte P-256 signature, got ${rawSignature.size} bytes"
        }
        val r = rawSignature.copyOfRange(0, P256_FIELD_SIZE_BYTES).toDerInteger()
        val s = rawSignature.copyOfRange(P256_FIELD_SIZE_BYTES, RAW_SIGNATURE_SIZE_BYTES).toDerInteger()
        val body = r + s
        return byteArrayOf(SEQUENCE_TAG) + encodeLength(body.size) + body
    }

    private fun ByteArray.toDerInteger(): ByteArray {
        val trimmed = dropLeadingZeroes()
        val positive =
            if (trimmed.first().toInt() and 0x80 != 0) {
                byteArrayOf(0) + trimmed
            } else {
                trimmed
            }
        return byteArrayOf(INTEGER_TAG) + encodeLength(positive.size) + positive
    }

    private fun ByteArray.dropLeadingZeroes(): ByteArray {
        val firstNonZero = indexOfFirst { it != 0.toByte() }
        return if (firstNonZero == -1) byteArrayOf(0) else copyOfRange(firstNonZero, size)
    }

    private fun encodeLength(length: Int): ByteArray {
        require(length >= 0)
        if (length < 0x80) {
            return byteArrayOf(length.toByte())
        }
        val bytes = mutableListOf<Byte>()
        var remaining = length
        while (remaining > 0) {
            bytes += (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.asReversed().toByteArray()
    }

    private class DerReader(
        private val source: ByteArray,
    ) {
        var position: Int = 0
            private set

        fun expectTag(tag: Byte) {
            require(readByte() == tag) { "Invalid DER ECDSA signature tag" }
        }

        fun readLengthEnd(): Int {
            val length = readLength()
            val end = position + length
            require(end <= source.size) { "Invalid DER ECDSA signature length" }
            return end
        }

        fun readInteger(): ByteArray {
            expectTag(INTEGER_TAG)
            val length = readLength()
            require(length > 0 && position + length <= source.size) {
                "Invalid DER ECDSA integer length"
            }
            val encoded = source.copyOfRange(position, position + length)
            position += length

            require(encoded[0].toInt() and 0x80 == 0) {
                "Invalid negative DER ECDSA integer"
            }
            require(encoded.size == 1 || encoded[0] != 0.toByte() || encoded[1].toInt() and 0x80 != 0) {
                "Invalid non-minimal DER ECDSA integer"
            }

            val unsigned =
                if (encoded.size > 1 && encoded[0] == 0.toByte()) {
                    encoded.copyOfRange(1, encoded.size)
                } else {
                    encoded
                }
            require(unsigned.size <= P256_FIELD_SIZE_BYTES) {
                "Invalid oversized P-256 ECDSA integer"
            }

            return ByteArray(P256_FIELD_SIZE_BYTES - unsigned.size) + unsigned
        }

        private fun readLength(): Int {
            val first = readByte().toInt() and 0xff
            if (first and 0x80 == 0) {
                return first
            }

            val byteCount = first and 0x7f
            require(byteCount in 1..2) { "Invalid DER ECDSA length" }
            require(position + byteCount <= source.size) { "Invalid DER ECDSA length" }

            var length = 0
            repeat(byteCount) {
                length = (length shl 8) or (readByte().toInt() and 0xff)
            }
            require(length >= 0x80) { "Invalid non-minimal DER ECDSA length" }
            return length
        }

        private fun readByte(): Byte {
            require(position < source.size) { "Truncated DER ECDSA signature" }
            return source[position++]
        }
    }

    private const val P256_FIELD_SIZE_BYTES = 32
    private const val RAW_SIGNATURE_SIZE_BYTES = 64
    private const val SEQUENCE_TAG = 0x30.toByte()
    private const val INTEGER_TAG = 0x02.toByte()
}
