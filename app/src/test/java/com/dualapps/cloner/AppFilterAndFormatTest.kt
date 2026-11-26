package com.dualapps.cloner

import com.dualapps.cloner.data.InstalledAppsRepository
import com.dualapps.cloner.model.AppSortOrder
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterAndFormatTest {

    private val sampleApps = listOf(
        InstalledApp("com.zebra.app", "Zebra Notes", 1_500_000L, "/data/app/z.apk", false, false, "1.0"),
        InstalledApp("com.alpha.chat", "Alpha Chat", 25_000_000L, "/data/app/a.apk", false, true, "2.1"),
        InstalledApp("com.android.systemui", "System UI", 12_000_000L, "/system/priv-app/s.apk", true, false, "14")
    )

    @Test
    fun hidesSystemAppsByDefaultAndSortsAZ() {
        val result = InstalledAppsRepository.filterAndSort(
            apps = sampleApps,
            query = "",
            showSystemApps = false,
            sortOrder = AppSortOrder.NAME_ASC
        )
        assertEquals(listOf("Alpha Chat", "Zebra Notes"), result.map { it.label })
    }

    @Test
    fun showsSystemAppsWhenEnabled() {
        val result = InstalledAppsRepository.filterAndSort(
            apps = sampleApps,
            query = "",
            showSystemApps = true,
            sortOrder = AppSortOrder.NAME_ASC
        )
        assertEquals(3, result.size)
        assertEquals(listOf("Alpha Chat", "System UI", "Zebra Notes"), result.map { it.label })
    }

    @Test
    fun searchesByNameAndPackageCaseInsensitive() {
        val byName = InstalledAppsRepository.filterAndSort(
            apps = sampleApps,
            query = "zebra",
            showSystemApps = false,
            sortOrder = AppSortOrder.NAME_ASC
        )
        assertEquals(listOf("com.zebra.app"), byName.map { it.packageName })

        val byPackage = InstalledAppsRepository.filterAndSort(
            apps = sampleApps,
            query = "ALPHA.CHAT",
            showSystemApps = false,
            sortOrder = AppSortOrder.NAME_ASC
        )
        assertEquals(listOf("com.alpha.chat"), byPackage.map { it.packageName })
    }

    @Test
    fun sortsBySizeDescending() {
        val result = InstalledAppsRepository.filterAndSort(
            apps = sampleApps,
            query = "",
            showSystemApps = true,
            sortOrder = AppSortOrder.SIZE_DESC
        )
        assertEquals(listOf("Alpha Chat", "System UI", "Zebra Notes"), result.map { it.label })
    }

    @Test
    fun formatsFileSizesReadable() {
        assertEquals("512 B", FormatUtils.formatFileSize(512L))
        assertTrue(FormatUtils.formatFileSize(2048L).endsWith("KB"))
        assertTrue(FormatUtils.formatFileSize(5L * 1024L * 1024L).endsWith("MB"))
    }

    @Test
    fun allCloneErrorsHaveNonBlankIndonesianMessages() {
        assertEquals(18, CloneError.entries.size)
        CloneError.entries.forEach { error ->
            assertTrue("Message kosong untuk $error", error.userMessage.isNotBlank())
        }
    }
}
