package com.photobooth.core

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Event dates are calendar days stored as UTC midnight, which is what the Material date picker returns. */
object EventDates {
    fun todayUtc(): Long = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun format(utcMillis: Long, pattern: String = "d MMM yyyy", locale: Locale = Locale.FRANCE): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(utcMillis))
}
