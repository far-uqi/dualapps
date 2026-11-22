package com.dualapps.cloner.model

/**
 * Representasi satu aplikasi terpasang pada layar Pilih Aplikasi.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val apkSizeBytes: Long,
    val sourceDir: String,
    val isSystemApp: Boolean,
    val isSplitApk: Boolean,
    val versionName: String?
)

enum class AppSortOrder {
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC
}
