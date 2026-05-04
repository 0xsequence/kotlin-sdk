package com.omsclient.kotlin_sdk.utils

import java.math.BigInteger

/**
 * Divides [value] by 10^[decimals] and formats it as a decimal string.
 *
 * Trailing fractional zeros are removed from the returned value.
 */
fun formatUnits(value: BigInteger, decimals: Int): String {
    require(decimals >= 0) { "decimals must be non-negative" }

    var display = value.abs().toString()
    display = display.padStart(decimals, '0')

    val integerEnd = display.length - decimals
    val integer = display.substring(0, integerEnd).ifEmpty { "0" }
    val fraction = display.substring(integerEnd).trimEnd('0')
    val sign = if (value.signum() < 0) "-" else ""

    return if (fraction.isEmpty()) {
        "$sign$integer"
    } else {
        "$sign$integer.$fraction"
    }
}

/**
 * Multiplies the decimal string [value] by 10^[decimals].
 *
 * Fractional precision beyond [decimals] is rounded to the nearest base unit,
 * matching viem's `parseUnits` behavior.
 */
fun parseUnits(value: String, decimals: Int): BigInteger {
    require(decimals >= 0) { "decimals must be non-negative" }
    require(DECIMAL_PATTERN.matches(value)) { "Invalid decimal number: $value" }

    val negative = value.startsWith("-")
    val unsigned = if (negative) value.drop(1) else value
    val parts = unsigned.split('.')
    var integer = parts[0].ifEmpty { "0" }
    var fraction = parts.getOrElse(1) { "0" }.trimEnd('0')

    if (decimals == 0) {
        if (fraction.firstOrNull()?.let { it >= '5' } == true) {
            integer = incrementDecimalString(integer)
        }
        fraction = ""
    } else if (fraction.length > decimals) {
        val roundedFraction = if (fraction[decimals] >= '5') {
            incrementDecimalString(fraction.substring(0, decimals)).padStart(decimals, '0')
        } else {
            fraction.substring(0, decimals)
        }

        if (roundedFraction.length > decimals) {
            integer = incrementDecimalString(integer)
            fraction = roundedFraction.drop(1)
        } else {
            fraction = roundedFraction
        }
    } else {
        fraction = fraction.padEnd(decimals, '0')
    }

    val raw = BigInteger(integer + fraction)
    return if (negative) raw.negate() else raw
}

private val DECIMAL_PATTERN = Regex("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")

private fun incrementDecimalString(value: String): String =
    value.ifEmpty { "0" }.let { BigInteger(it).add(BigInteger.ONE).toString() }
