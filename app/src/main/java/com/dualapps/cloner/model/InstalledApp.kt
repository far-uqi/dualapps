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
    val versionName: String?,
    val splitFiles: List<SplitApkFile> = emptyList(),
    val versionCode: Long = 0L
) {
    /** Jumlah total berkas APK (1 base + semua split). */
    val totalFileCount: Int get() = 1 + splitFiles.size

    /** Ukuran berkas base APK saja (tanpa split). */
    val baseApkSizeBytes: Long
        get() = (apkSizeBytes - splitFiles.sumOf { it.sizeBytes }).coerceAtLeast(0L)
}

enum class AppSortOrder {
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC
}
