package com.dualapps.cloner.data

import com.dualapps.cloner.model.ClonedApkRecord
import java.io.File

/**
 * Repositori berkas APK hasil clone yang tersimpan di `filesDir/cloned_apks`.
 * Tidak bergantung pada Android Context agar dapat diuji langsung di unit test JVM.
 */
class ClonedApksRepository(private val filesDir: File) {

    val clonedApksDir: File
        get() = File(filesDir, CLONED_APKS_DIR_NAME)

    fun loadAll(): List<ClonedApkRecord> {
        val dir = clonedApksDir
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val jsonFiles = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json", ignoreCase = true)
        } ?: return emptyList()

        return jsonFiles
            .mapNotNull { metaFile ->
                runCatching {
                    ClonedApkRecord.fromJsonString(metaFile.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .filter { record -> record.allApkFiles.all { it.exists() && it.isFile } }
            .sortedByDescending { it.createdAtMillis }
    }

    fun delete(record: ClonedApkRecord): Boolean {
        val allApksDeleted = record.allApkFiles.all { file -> !file.exists() || file.delete() }
        if (record.isSplitClone) {
            val splitDir = File(clonedApksDir, record.id)
            if (splitDir.exists() && splitDir.isDirectory) {
                splitDir.deleteRecursively()
            }
        }
        val metaFile = File(clonedApksDir, "${record.id}.json")
        val metaDeleted = !metaFile.exists() || metaFile.delete()
        record.customIconPath?.let { iconPath ->
            val iconFile = File(iconPath)
            if (iconFile.exists()) {
                runCatching { iconFile.delete() }
            }
        }
        return allApksDeleted && metaDeleted
    }

    companion object {
        const val CLONED_APKS_DIR_NAME = "cloned_apks"
    }
}
