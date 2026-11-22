package com.dualapps.cloner.util

import java.util.Locale

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
}
