package com.dualapps.cloner.engine

import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.model.ClonedApkRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloneProgressStartResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRecord(
        dir: File,
        id: String,
        clonePkg: String,
        createdAt: Long
    ): ClonedApkRecord {
        dir.mkdirs()
        val apk = File(dir, "$id.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val record = ClonedApkRecord(
            id = id,
            cloneName = "Muse CIMOL",
            clonePackageName = clonePkg,
            originalPackageName = "com.facebook.aura",
            originalAppName = "Muse",
            apkPath = apk.absolutePath,
            apkSizeBytes = apk.length(),
            createdAtMillis = createdAt
        )
        File(dir, "$id.json").writeText(record.toJsonString(), Charsets.UTF_8)
        return record
    }

    @Test
    fun showsCompletedImmediatelyFromSavedStateWhenApkStillExists() {
        val filesDir = tempFolder.newFolder("filesSaved")
        val repoDir = ClonedApksRepository(filesDir).clonedApksDir
        val saved = createRecord(repoDir, "saved_1", "com.facebook.aura.clone", 1_700_000_000_000L)

        val action = CloneProgressStartResolver.resolve(
            clonePackageName = "com.facebook.aura.clone",
            savedRecordJson = saved.toJsonString(),
            existingRecords = emptyList(),
            isExplicitRetry = false
        )

        assertTrue(action is CloneStartAction.ShowCompleted)
        assertEquals(saved.id, (action as CloneStartAction.ShowCompleted).record.id)
    }

    @Test
    fun showsCompletedFromRepositoryWithoutRunningPipelineWhenRecordExists() {
        val filesDir = tempFolder.newFolder("filesRepo")
        val repo = ClonedApksRepository(filesDir)
        createRecord(repo.clonedApksDir, "older_1", "com.facebook.aura.clone", 1_700_000_000_000L)
        val latest = createRecord(repo.clonedApksDir, "latest_2", "com.facebook.aura.clone", 1_700_000_500_000L)

        val action = CloneProgressStartResolver.resolve(
            clonePackageName = "com.facebook.aura.clone",
            savedRecordJson = null,
            existingRecords = repo.loadAll(),
            isExplicitRetry = false
        )

        assertTrue(action is CloneStartAction.ShowCompleted)
        assertEquals(latest.id, (action as CloneStartAction.ShowCompleted).record.id)
    }

    @Test
    fun runsPipelineWhenNoMatchingRecordExistsOrApkWasDeleted() {
        val filesDir = tempFolder.newFolder("filesEmpty")
        val repo = ClonedApksRepository(filesDir)
        val other = createRecord(repo.clonedApksDir, "other_1", "com.other.app.clone", 1_700_000_000_000L)

        // Tidak ada record untuk com.facebook.aura.clone
        val actionNoMatch = CloneProgressStartResolver.resolve(
            clonePackageName = "com.facebook.aura.clone",
            savedRecordJson = null,
            existingRecords = repo.loadAll(),
            isExplicitRetry = false
        )
        assertEquals(CloneStartAction.RunPipeline, actionNoMatch)

        // Saved state ada tetapi file APK-nya sudah dihapus
        val deletedRecord = createRecord(repo.clonedApksDir, "del_1", "com.facebook.aura.clone", 1_700_000_100_000L)
        assertTrue(deletedRecord.apkFile.delete())
        val actionDeletedApk = CloneProgressStartResolver.resolve(
            clonePackageName = "com.facebook.aura.clone",
            savedRecordJson = deletedRecord.toJsonString(),
            existingRecords = listOf(other),
            isExplicitRetry = false
        )
        assertEquals(CloneStartAction.RunPipeline, actionDeletedApk)
    }

    @Test
    fun runsPipelineOnExplicitRetryEvenWhenCompletedRecordExists() {
        val filesDir = tempFolder.newFolder("filesRetry")
        val repo = ClonedApksRepository(filesDir)
        val existing = createRecord(repo.clonedApksDir, "done_1", "com.facebook.aura.clone", 1_700_000_000_000L)

        val action = CloneProgressStartResolver.resolve(
            clonePackageName = "com.facebook.aura.clone",
            savedRecordJson = existing.toJsonString(),
            existingRecords = repo.loadAll(),
            isExplicitRetry = true
        )

        assertEquals(CloneStartAction.RunPipeline, action)
    }
}
