package com.dualapps.cloner.model

/**
 * Representasi satu berkas split APK (mis. `config.arm64_v8a`, `config.xxhdpi`, `config.en`).
 */
data class SplitApkFile(
    val splitName: String,
    val path: String,
    val sizeBytes: Long
)
