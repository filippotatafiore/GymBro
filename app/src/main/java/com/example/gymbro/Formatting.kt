package com.example.gymbro

import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// e.g. 75 000 ms -> "01:15", 3 725 000 ms -> "1:02:05"
fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

// e.g. "Oct 10, 2026, 5:32 PM" (in the language and time zone of the phone)
fun formatDateTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

// e.g. "Oct 10, 2026"
fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(instant.atZone(ZoneId.systemDefault()))

// e.g. 150.0 -> "150 kg", 72.5 -> "72.5 kg"
fun formatWeight(kg: Double): String = DecimalFormat("0.##").format(kg) + " kg"

// e.g. 10.0 -> "10 km", 5.25 -> "5.25 km"
fun formatDistance(km: Double): String = DecimalFormat("0.##").format(km) + " km"

// Average time per km, e.g. 3600 s for 10 km -> "06:00 /km"
fun formatPace(durationSeconds: Long, km: Double): String =
    formatDuration((durationSeconds / km * 1000).toLong()) + " /km"
