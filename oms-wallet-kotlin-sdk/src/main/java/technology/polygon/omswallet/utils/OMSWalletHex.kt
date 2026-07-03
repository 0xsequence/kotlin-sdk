package technology.polygon.omswallet.utils

internal object OMSWalletHex {
    private val digits = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String = "0x${encodeNoPrefix(bytes)}"

    fun encodeNoPrefix(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }

    fun decode(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X")
        require(hex.length % 2 == 0) { "Hex value must have an even number of characters" }
        return ByteArray(hex.length / 2) { index ->
            val high = hex[index * 2].hexDigitToInt()
            val low = hex[index * 2 + 1].hexDigitToInt()
            ((high shl 4) or low).toByte()
        }
    }

    private fun Char.hexDigitToInt(): Int =
        when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> throw IllegalArgumentException("Invalid hex character: $this")
        }
}
