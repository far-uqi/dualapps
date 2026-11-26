package com.dualapps.cloner.engine

import com.dualapps.cloner.model.ClonedApkRecord

/**
 * Informasi versi aplikasi sumber yang sedang terpasang di perangkat.
 */
data class InstalledPackageVersion(
    val packageName: String,
    val versionCode: Long,
    val versionName: String
)

/**
 * Status ketersediaan pembaruan untuk satu hasil clone di layar Hasil.
 */
sealed class CloneUpdateStatus {
    data class UpdateAvailable(
        val currentSourceVersionCode: Long,
        val currentSourceVersionName: String,
        val clonedFromVersionCode: Long,
        val clonedFromVersionName: String
    ) : CloneUpdateStatus()

    data object UpToDate : CloneUpdateStatus()
    data object UnknownSourceVersion : CloneUpdateStatus()
    data object SourceNotInstalled : CloneUpdateStatus()
    data object CloneNotInstalled : CloneUpdateStatus()
}

/**
 * Membandingkan `versionCode` aplikasi sumber yang terpasang saat ini terhadap
 * `sourceVersionCode` yang tersimpan pada [ClonedApkRecord].
 */
object CloneUpdateChecker {

    fun check(
        record: ClonedApkRecord,
        installedSource: InstalledPackageVersion?,
        isCloneInstalled: Boolean
    ): CloneUpdateStatus {
        if (!isCloneInstalled) {
            return CloneUpdateStatus.CloneNotInstalled
        }
        if (installedSource == null) {
            return CloneUpdateStatus.SourceNotInstalled
        }
        if (record.sourceVersionCode <= 0L) {
            return CloneUpdateStatus.UnknownSourceVersion
        }
        if (installedSource.versionCode > record.sourceVersionCode) {
            return CloneUpdateStatus.UpdateAvailable(
                currentSourceVersionCode = installedSource.versionCode,
                currentSourceVersionName = installedSource.versionName,
                clonedFromVersionCode = record.sourceVersionCode,
                clonedFromVersionName = record.sourceVersionName
            )
        }
        return CloneUpdateStatus.UpToDate
    }
}
