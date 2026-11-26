package com.dualapps.cloner.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.dualapps.cloner.model.AppSortOrder
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.model.SplitApkFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class InstalledAppsRepository(
    private val packageManager: PackageManager
) {
    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        apps.mapNotNull { appInfo ->
            val sourceDir = appInfo.sourceDir ?: return@mapNotNull null
            val apkFile = File(sourceDir)
            val label = runCatching {
                packageManager.getApplicationLabel(appInfo).toString().trim()
            }.getOrNull().takeUnless { it.isNullOrEmpty() } ?: appInfo.packageName

            val pkgInfo = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(appInfo.packageName, 0)
            }.getOrNull()
            val versionName = pkgInfo?.versionName
            val versionCode = pkgInfo?.let { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } ?: 0L

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val splitFiles = buildSplitFiles(appInfo.splitSourceDirs, appInfo.splitNames)
            val isSplit = splitFiles.isNotEmpty()
            val baseSize = if (apkFile.exists()) apkFile.length() else 0L
            val totalSize = baseSize + splitFiles.sumOf { it.sizeBytes }

            InstalledApp(
                packageName = appInfo.packageName,
                label = label,
                apkSizeBytes = totalSize,
                sourceDir = sourceDir,
                isSystemApp = isSystem,
                isSplitApk = isSplit,
                versionName = versionName,
                splitFiles = splitFiles,
                versionCode = versionCode
            )
        }
    }

    companion object {
        fun buildSplitFiles(
            splitSourceDirs: Array<String>?,
            splitNames: Array<String>?
        ): List<SplitApkFile> {
            if (splitSourceDirs.isNullOrEmpty()) return emptyList()
            return splitSourceDirs.mapIndexed { index, dirPath ->
                val file = File(dirPath)
                val name = splitNames?.getOrNull(index)
                    ?.takeIf { it.isNotBlank() }
                    ?: file.nameWithoutExtension.removePrefix("split_")
                val size = if (file.exists()) file.length() else 0L
                SplitApkFile(
                    splitName = name,
                    path = dirPath,
                    sizeBytes = size
                )
            }
        }
        fun filterAndSort(
            apps: List<InstalledApp>,
            query: String,
            showSystemApps: Boolean,
            sortOrder: AppSortOrder
        ): List<InstalledApp> {
            val trimmedQuery = query.trim().lowercase()
            val filtered = apps.filter { app ->
                val passesSystem = showSystemApps || !app.isSystemApp
                val passesQuery = trimmedQuery.isEmpty() ||
                    app.label.lowercase().contains(trimmedQuery) ||
                    app.packageName.lowercase().contains(trimmedQuery)
                passesSystem && passesQuery
            }
            return when (sortOrder) {
                AppSortOrder.NAME_ASC -> filtered.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                )
                AppSortOrder.NAME_DESC -> filtered.sortedWith(
                    compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.label }
                )
                AppSortOrder.SIZE_DESC -> filtered.sortedWith(
                    compareByDescending<InstalledApp> { it.apkSizeBytes }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                )
            }
        }
    }
}
