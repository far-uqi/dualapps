package com.dualapps.cloner.model

import java.io.File

/**
 * Metadata satu APK hasil clone yang tersimpan di folder privat aplikasi (`filesDir/cloned_apks`).
 * Menggunakan serialisasi JSON murni agar tidak bergantung pada stub `org.json` saat unit test JVM.
 */
data class ClonedApkRecord(
    val id: String,
    val cloneName: String,
    val clonePackageName: String,
    val originalPackageName: String,
    val originalAppName: String,
    val apkPath: String,
    val apkSizeBytes: Long,
    val createdAtMillis: Long
) {
    val apkFile: File get() = File(apkPath)

    fun toJsonString(): String {
        return buildString {
            append('{')
            append("\"id\":").append(quote(id)).append(',')
            append("\"cloneName\":").append(quote(cloneName)).append(',')
            append("\"clonePackageName\":").append(quote(clonePackageName)).append(',')
            append("\"originalPackageName\":").append(quote(originalPackageName)).append(',')
            append("\"originalAppName\":").append(quote(originalAppName)).append(',')
            append("\"apkPath\":").append(quote(apkPath)).append(',')
            append("\"apkSizeBytes\":").append(apkSizeBytes).append(',')
            append("\"createdAtMillis\":").append(createdAtMillis)
            append('}')
        }
    }

    companion object {
        private val STRING_FIELD = Regex("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        private val NUMBER_FIELD = Regex("\"([^\"]+)\"\\s*:\\s*(-?\\d+)")

        fun fromJsonString(raw: String): ClonedApkRecord? {
            val strings = mutableMapOf<String, String>()
            STRING_FIELD.findAll(raw).forEach { m ->
                strings[m.groupValues[1]] = unescape(m.groupValues[2])
            }
            val numbers = mutableMapOf<String, Long>()
            NUMBER_FIELD.findAll(raw).forEach { m ->
                numbers[m.groupValues[1]] = m.groupValues[2].toLongOrNull() ?: 0L
            }
            val id = strings["id"] ?: return null
            val cloneName = strings["cloneName"] ?: return null
            val clonePkg = strings["clonePackageName"] ?: return null
            val origPkg = strings["originalPackageName"] ?: return null
            val origName = strings["originalAppName"] ?: return null
            val apkPath = strings["apkPath"] ?: return null
            val apkSize = numbers["apkSizeBytes"] ?: 0L
            val createdAt = numbers["createdAtMillis"] ?: 0L
            return ClonedApkRecord(
                id = id,
                cloneName = cloneName,
                clonePackageName = clonePkg,
                originalPackageName = origPkg,
                originalAppName = origName,
                apkPath = apkPath,
                apkSizeBytes = apkSize,
                createdAtMillis = createdAt
            )
        }

        private fun quote(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        private fun unescape(value: String): String {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '\\' && i + 1 < value.length) {
                    when (val next = value[i + 1]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> sb.append(next)
                    }
                    i += 2
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
