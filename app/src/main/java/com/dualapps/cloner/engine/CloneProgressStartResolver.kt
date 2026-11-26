package com.dualapps.cloner.engine

import com.dualapps.cloner.model.ClonedApkRecord

sealed class CloneStartAction {
    data class ShowCompleted(val record: ClonedApkRecord) : CloneStartAction()
    data object RunPipeline : CloneStartAction()
}

/**
 * Menentukan apakah [com.dualapps.cloner.ui.CloneProgressActivity] harus langsung menampilkan
 * state Selesai atau menjalankan pipeline clone.
 *
 * Mencegah pipeline mengulang dari langkah 1 saat Activity dibuat ulang oleh Android
 * (mis. setelah kembali dari layar Settings "Install unknown apps").
 */
object CloneProgressStartResolver {

    fun resolve(
        clonePackageName: String,
        savedRecordJson: String?,
        existingRecords: List<ClonedApkRecord>,
        isExplicitRetry: Boolean = false,
        isUpdateRun: Boolean = false
    ): CloneStartAction {
        if (isExplicitRetry) {
            return CloneStartAction.RunPipeline
        }

        // Jalur cepat: pulihkan dari onSaveInstanceState bila cocok dan berkas APK masih ada
        if (!savedRecordJson.isNullOrBlank()) {
            val restored = ClonedApkRecord.fromJsonString(savedRecordJson)
            if (restored != null &&
                restored.clonePackageName == clonePackageName &&
                restored.apkFile.exists() &&
                restored.apkFile.isFile
            ) {
                return CloneStartAction.ShowCompleted(restored)
            }
        }

        // Pada mode Perbarui (awal mulai tanpa saved state), jangan langsung tampilkan record lama
        if (isUpdateRun) {
            return CloneStartAction.RunPipeline
        }

        // Fallback: periksa record di ClonedApksRepository untuk clonePackageName yang sama
        val matched = existingRecords
            .filter {
                it.clonePackageName == clonePackageName &&
                    it.apkFile.exists() &&
                    it.apkFile.isFile
            }
            .maxByOrNull { it.createdAtMillis }

        return if (matched != null) {
            CloneStartAction.ShowCompleted(matched)
        } else {
            CloneStartAction.RunPipeline
        }
    }
}
