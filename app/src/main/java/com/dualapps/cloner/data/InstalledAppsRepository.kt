package com.dualapps.cloner.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.dualapps.cloner.model.AppSortOrder
import com.dualapps.cloner.model.InstalledApp
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

            val versionName = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(appInfo.packageName, 0).versionName
            }.getOrNull()

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isSplit = !appInfo.splitSourceDirs.isNullOrEmpty()

            InstalledApp(
                packageName = appInfo.packageName,
                label = label,
                apkSizeBytes = if (apkFile.exists()) apkFile.length() else 0L,
                sourceDir = sourceDir,
                isSystemApp = isSystem,
                isSplitApk = isSplit,
                versionName = versionName
            )
        }
    }

    companion object {
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
