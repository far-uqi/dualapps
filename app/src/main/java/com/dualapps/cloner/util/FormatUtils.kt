package com.dualapps.cloner.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FormatUtils {
    private val LOCALE_ID = Locale("id", "ID")

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(LOCALE_ID, "%.1f KB", bytes / kb)
            bytes < 1024L * 1024L * 1024L -> String.format(LOCALE_ID, "%.1f MB", bytes / mb)
            else -> String.format(LOCALE_ID, "%.2f GB", bytes / gb)
        }
    }

    fun formatTimestamp(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (millis <= 0L) return "-"
        val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", LOCALE_ID).apply {
            this.timeZone = timeZone
        }
        return formatter.format(Date(millis))
    }
}
