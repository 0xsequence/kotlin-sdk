package com.omsclient.kotlin_sdk.utils

internal object OMSClientBase64Url {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val decodeTable =
        IntArray(128) { -1 }.also { table ->
            alphabet.forEachIndexed { index, char ->
                table[char.code] = index
            }
        }

    fun encodeNoPadding(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return ""
        }
        val output = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index + 2 < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes[index + 1].toInt() and 0xff
            val third = bytes[index + 2].toInt() and 0xff
            output.append(alphabet[first ushr 2])
            output.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            output.append(alphabet[((second and 0x0f) shl 2) or (third ushr 6)])
            output.append(alphabet[third and 0x3f])
            index += 3
        }

        val remaining = bytes.size - index
        if (remaining == 1) {
            val first = bytes[index].toInt() and 0xff
            output.append(alphabet[first ushr 2])
            output.append(alphabet[(first and 0x03) shl 4])
        } else if (remaining == 2) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes[index + 1].toInt() and 0xff
            output.append(alphabet[first ushr 2])
            output.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            output.append(alphabet[(second and 0x0f) shl 2])
        }

        return output.toString()
    }

    fun decode(value: String): ByteArray {
        val encoded = value.trimEnd('=')
        require(!encoded.contains('=')) { "Base64 padding is only allowed at the end" }
        require(encoded.length % 4 != 1) { "Invalid Base64 URL length" }
        if (encoded.isEmpty()) {
            return ByteArray(0)
        }

        val output = ByteArray(encoded.length * 3 / 4 + 2)
        var outputIndex = 0
        var index = 0
        while (index < encoded.length) {
            val first = encoded.decodeAt(index)
            val second = encoded.decodeAt(index + 1)
            output[outputIndex++] = ((first shl 2) or (second ushr 4)).toByte()

            if (index + 2 < encoded.length) {
                val third = encoded.decodeAt(index + 2)
                output[outputIndex++] = (((second and 0x0f) shl 4) or (third ushr 2)).toByte()

                if (index + 3 < encoded.length) {
                    val fourth = encoded.decodeAt(index + 3)
                    output[outputIndex++] = (((third and 0x03) shl 6) or fourth).toByte()
                }
            }
            index += 4
        }

        return output.copyOf(outputIndex)
    }

    private fun String.decodeAt(index: Int): Int {
        require(index < length) { "Invalid Base64 URL length" }
        val char = this[index]
        require(char.code < decodeTable.size && decodeTable[char.code] >= 0) {
            "Invalid Base64 URL character"
        }
        return decodeTable[char.code]
    }
}
