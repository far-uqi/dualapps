package com.dualapps.cloner.data

import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class ClonedApksRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun loadsSortedNewestFirstAndSkipsMissingApkOrCorruptJson() {
        val filesDir = tempFolder.newFolder("filesRepo")
        val repo = ClonedApksRepository(filesDir)
        val dir = repo.clonedApksDir
        assertTrue(dir.mkdirs())

        val olderApk = File(dir, "app_old.apk").apply { writeBytes(ByteArray(128)) }
        val olderRecord = ClonedApkRecord(
            id = "app_old",
            cloneName = "App Lama",
            clonePackageName = "com.example.old.clone",
            originalPackageName = "com.example.old",
            originalAppName = "App Lama Asli",
            apkPath = olderApk.absolutePath,
            apkSizeBytes = olderApk.length(),
            createdAtMillis = 1_700_000_000_000L
        )
        File(dir, "${olderRecord.id}.json").writeText(olderRecord.toJsonString(), Charsets.UTF_8)

        val newerApk = File(dir, "app_new.apk").apply { writeBytes(ByteArray(256)) }
        val newerRecord = ClonedApkRecord(
            id = "app_new",
            cloneName = "App Baru",
            clonePackageName = "com.example.new.clone",
            originalPackageName = "com.example.new",
            originalAppName = "App Baru Asli",
            apkPath = newerApk.absolutePath,
            apkSizeBytes = newerApk.length(),
            createdAtMillis = 1_700_000_500_000L
        )
        File(dir, "${newerRecord.id}.json").writeText(newerRecord.toJsonString(), Charsets.UTF_8)

        // Record yang berkas APK-nya sudah hilang harus dilewati
        val ghostRecord = ClonedApkRecord(
            id = "app_ghost",
            cloneName = "Ghost",
            clonePackageName = "com.example.ghost.clone",
            originalPackageName = "com.example.ghost",
            originalAppName = "Ghost",
            apkPath = File(dir, "missing.apk").absolutePath,
            apkSizeBytes = 100L,
            createdAtMillis = 1_700_001_000_000L
        )
        File(dir, "${ghostRecord.id}.json").writeText(ghostRecord.toJsonString(), Charsets.UTF_8)

        // JSON rusak harus dilewati tanpa crash
        File(dir, "corrupt.json").writeText("{not valid json", Charsets.UTF_8)

        val loaded = repo.loadAll()
        assertEquals(2, loaded.size)
        assertEquals("app_new", loaded[0].id)
        assertEquals("app_old", loaded[1].id)
    }

    @Test
    fun deleteRemovesApkAndMetadataFiles() {
        val filesDir = tempFolder.newFolder("filesDelete")
        val repo = ClonedApksRepository(filesDir)
        val dir = repo.clonedApksDir
        assertTrue(dir.mkdirs())

        val apk = File(dir, "sample_1.apk").apply { writeBytes(ByteArray(64)) }
        val record = ClonedApkRecord(
            id = "sample_1",
            cloneName = "Sample 2",
            clonePackageName = "com.example.sample.clone",
            originalPackageName = "com.example.sample",
            originalAppName = "Sample",
            apkPath = apk.absolutePath,
            apkSizeBytes = apk.length(),
            createdAtMillis = 1_700_000_000_000L
        )
        val meta = File(dir, "${record.id}.json").apply {
            writeText(record.toJsonString(), Charsets.UTF_8)
        }

        assertEquals(1, repo.loadAll().size)
        assertTrue(repo.delete(record))
        assertFalse(apk.exists())
        assertFalse(meta.exists())
        assertTrue(repo.loadAll().isEmpty())
    }

    @Test
    fun formatTimestampHandlesValidAndInvalidValues() {
        assertEquals("-", FormatUtils.formatTimestamp(0L))
        assertEquals("-", FormatUtils.formatTimestamp(-100L))
        val formatted = FormatUtils.formatTimestamp(0L + 86_400_000L, TimeZone.getTimeZone("UTC"))
        assertTrue(formatted.contains("1970"))
        assertTrue(formatted.contains("00:00"))
    }
}
