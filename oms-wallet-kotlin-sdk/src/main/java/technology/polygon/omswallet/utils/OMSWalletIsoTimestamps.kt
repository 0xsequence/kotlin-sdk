package technology.polygon.omswallet.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

internal object OMSWalletIsoTimestamps {
    private val timestampPattern =
        Regex(
            """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})$""",
        )

    /** Formats [epochMillis] as an ISO-8601 UTC timestamp with milliseconds. */
    fun formatEpochMillis(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    fun parseEpochMillis(value: String): Long? {
        val match = timestampPattern.matchEntire(value.trim()) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        val millisecond = match.groupValues[7].toMillis()
        val offsetMillis = match.groupValues[8].offsetMillis() ?: return null

        return runCatching {
            val calendar =
                GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                    isLenient = false
                    clear()
                    set(year, month - 1, day, hour, minute, second)
                    set(Calendar.MILLISECOND, millisecond)
                }
            calendar.timeInMillis - offsetMillis
        }.getOrNull()
    }

    private fun String.toMillis(): Int {
        if (isEmpty()) {
            return 0
        }
        return padEnd(3, '0').take(3).toInt()
    }

    private fun String.offsetMillis(): Long? {
        if (this == "Z") {
            return 0L
        }
        val sign = if (startsWith("-")) -1L else 1L
        val digits = substring(1).replace(":", "")
        if (digits.length != 4) {
            return null
        }
        val hours = digits.substring(0, 2).toInt()
        val minutes = digits.substring(2, 4).toInt()
        if (hours > 23 || minutes > 59) {
            return null
        }
        return sign * ((hours * 60L + minutes) * 60_000L)
    }
}
