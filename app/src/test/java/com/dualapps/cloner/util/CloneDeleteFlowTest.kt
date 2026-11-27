package com.dualapps.cloner.util

import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.util.CloneDeleteCoordinator.CloneDeleteMode
import com.dualapps.cloner.util.CloneDeleteCoordinator.DeleteExecutionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloneDeleteFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStoredRecord(
        id: String = "clone_muse_1",
        cloneName: String = "Muse 2",
        clonePackage: String = "com.muse.app.clone",
        sizeBytes: Long = 24_500_000L
    ): Pair<ClonedApksRepository, ClonedApkRecord> {
        val repo = ClonedApksRepository(tempFolder.root)
        repo.clonedApksDir.mkdirs()
        val apkFile = File(repo.clonedApksDir, "$id.apk").apply {
            writeBytes(ByteArray(1024) { 0x5A })
        }
        val record = ClonedApkRecord(
            id = id,
            cloneName = cloneName,
            clonePackageName = clonePackage,
            originalPackageName = "com.muse.app",
            originalAppName = "Muse",
            apkPath = apkFile.absolutePath,
            apkSizeBytes = sizeBytes,
            createdAtMillis = 1_732_700_000_000L
        )
        File(repo.clonedApksDir, "$id.json").writeText(record.toJsonString(), Charsets.UTF_8)
        return repo to record
    }

    private fun resolveAppDir(): File {
        val direct = File("src/main")
        if (direct.exists()) return File(".")
        val nested = File("app/src/main")
        if (nested.exists()) return File("app")
        error("Cannot locate app/src/main from working dir: ${File(".").absolutePath}")
    }

    @Test
    fun popup1OnInstalledCloneShowsBothOptionsWithConsequenceSubtextAndSize() {
        val (_, record) = createStoredRecord()
        val formattedSize = FormatUtils.formatFileSize(record.apkSizeBytes)

        val model = CloneDeleteCoordinator.buildChoiceModel(
            record = record,
            isInstalledLive = true,
            formattedSize = formattedSize
        )

        assertTrue("Uninstall option must be shown when clone is installed", model.showUninstallOption)
        assertTrue(model.fileOnlySubtitle.contains(formattedSize))
        assertTrue(model.fileOnlySubtitle.contains("stays on your device"))
        assertNotNull(model.fileAndUninstallSubtitle)
        assertTrue(model.fileAndUninstallSubtitle!!.contains(formattedSize))
        assertTrue(model.fileAndUninstallSubtitle!!.contains("removed from your device"))
    }

    @Test
    fun popup1OnUninstalledCloneHidesUninstallOption() {
        val (_, record) = createStoredRecord()
        val formattedSize = FormatUtils.formatFileSize(record.apkSizeBytes)

        val model = CloneDeleteCoordinator.buildChoiceModel(
            record = record,
            isInstalledLive = false,
            formattedSize = formattedSize
        )

        assertFalse("Uninstall option must be hidden when clone is not installed", model.showUninstallOption)
        assertNull(model.fileAndUninstallSubtitle)
        assertTrue(model.fileOnlySubtitle.contains(formattedSize))
    }

    @Test
    fun popup2ShowsDistinctConsequencesAndExactButtonLabelsForEachMode() {
        val (_, record) = createStoredRecord()
        val formattedSize = FormatUtils.formatFileSize(record.apkSizeBytes)

        val fileOnlyConfirm = CloneDeleteCoordinator.buildConfirmModel(
            record = record,
            mode = CloneDeleteMode.FILE_ONLY,
            isInstalledLive = true,
            formattedSize = formattedSize
        )
        val fileAndUninstallConfirm = CloneDeleteCoordinator.buildConfirmModel(
            record = record,
            mode = CloneDeleteMode.FILE_AND_UNINSTALL,
            isInstalledLive = true,
            formattedSize = formattedSize
        )

        assertEquals("Cancel", fileOnlyConfirm.cancelLabel)
        assertEquals("Yes, delete", fileOnlyConfirm.confirmLabel)
        assertTrue(fileOnlyConfirm.message.contains(formattedSize))
        assertTrue(fileOnlyConfirm.message.contains("will stay on your device"))
        assertTrue(fileOnlyConfirm.message.contains("clone it again"))

        assertEquals("Cancel", fileAndUninstallConfirm.cancelLabel)
        assertEquals("Yes, delete", fileAndUninstallConfirm.confirmLabel)
        assertTrue(fileAndUninstallConfirm.message.contains(formattedSize))
        assertTrue(fileAndUninstallConfirm.message.contains("uninstall '${record.cloneName}'"))
        assertTrue(fileAndUninstallConfirm.message.contains("cannot be undone"))
        assertFalse(fileOnlyConfirm.message == fileAndUninstallConfirm.message)
    }

    @Test
    fun fileOnlyDeletesFilesAndMetadataWithoutRequestingUninstall() {
        val (repo, record) = createStoredRecord()
        assertEquals(1, repo.loadAll().size)

        val outcome = CloneDeleteCoordinator.beginConfirmedDelete(
            record = record,
            mode = CloneDeleteMode.FILE_ONLY,
            isInstalledLive = true,
            deleteFiles = { repo.delete(it) }
        )

        assertEquals(DeleteExecutionOutcome.DeletedFileOnly, outcome)
        assertFalse("APK file must be deleted", record.apkFile.exists())
        assertTrue("Repository must be empty after delete", repo.loadAll().isEmpty())
    }

    @Test
    fun fileAndUninstallRunsSystemUninstallFirstThenDeletesFilesOnlyAfterConfirmed() {
        val (repo, record) = createStoredRecord()
        val executionLog = mutableListOf<String>()

        val step1 = CloneDeleteCoordinator.beginConfirmedDelete(
            record = record,
            mode = CloneDeleteMode.FILE_AND_UNINSTALL,
            isInstalledLive = true,
            deleteFiles = {
                executionLog.add("delete_files")
                repo.delete(it)
            }
        )

        assertEquals(DeleteExecutionOutcome.NeedsSystemUninstall(record.clonePackageName), step1)
        assertTrue("Files MUST NOT be deleted before system uninstall returns", record.apkFile.exists())
        assertTrue("deleteFiles must not be invoked yet", executionLog.isEmpty())

        // Simulate system uninstall dialog returning with package successfully uninstalled
        executionLog.add("system_uninstall_succeeded")
        val step2 = CloneDeleteCoordinator.completeAfterSystemUninstall(
            record = record,
            isStillInstalledLive = false,
            deleteFiles = {
                executionLog.add("delete_files")
                repo.delete(it)
            }
        )

        assertEquals(DeleteExecutionOutcome.DeletedFileAndUninstalled, step2)
        assertEquals(listOf("system_uninstall_succeeded", "delete_files"), executionLog)
        assertFalse("APK file must be deleted after uninstall succeeds", record.apkFile.exists())
        assertTrue("Metadata must be deleted after uninstall succeeds", repo.loadAll().isEmpty())
    }

    @Test
    fun cancellingSystemUninstallDialogLeavesFilesAndMetadataIntact() {
        val (repo, record) = createStoredRecord()
        var deleteInvoked = false

        val step1 = CloneDeleteCoordinator.beginConfirmedDelete(
            record = record,
            mode = CloneDeleteMode.FILE_AND_UNINSTALL,
            isInstalledLive = true,
            deleteFiles = {
                deleteInvoked = true
                repo.delete(it)
            }
        )
        assertEquals(DeleteExecutionOutcome.NeedsSystemUninstall(record.clonePackageName), step1)

        // Simulate user pressing Cancel on Android system uninstall dialog (app still installed)
        val step2 = CloneDeleteCoordinator.completeAfterSystemUninstall(
            record = record,
            isStillInstalledLive = true,
            deleteFiles = {
                deleteInvoked = true
                repo.delete(it)
            }
        )

        assertEquals(DeleteExecutionOutcome.CancelledAtSystemDialog, step2)
        assertFalse("deleteFiles must NOT be called when system uninstall is cancelled", deleteInvoked)
        assertTrue("APK file must remain intact", record.apkFile.exists())
        assertEquals("Metadata must remain intact", 1, repo.loadAll().size)
    }

    @Test
    fun appUninstalledExternallyBetweenPopupAndExecutionDoesNotSilentlyDeleteFiles() {
        val (repo, record) = createStoredRecord()
        var deleteInvoked = false

        val outcome = CloneDeleteCoordinator.beginConfirmedDelete(
            record = record,
            mode = CloneDeleteMode.FILE_AND_UNINSTALL,
            isInstalledLive = false,
            deleteFiles = {
                deleteInvoked = true
                repo.delete(it)
            }
        )

        assertEquals(DeleteExecutionOutcome.AppAlreadyUninstalled(record.clonePackageName), outcome)
        assertFalse("Must not silently delete files when app is already gone", deleteInvoked)
        assertTrue("APK file must remain intact", record.apkFile.exists())
        assertEquals(1, repo.loadAll().size)
    }

    @Test
    fun phase17DialogLayoutsAndStringsMatchNeoBrutalismAndEnglishSpec() {
        val appDir = resolveAppDir()
        val optionsXml = File(appDir, "src/main/res/layout/dialog_delete_options.xml").readText(Charsets.UTF_8)
        val confirmXml = File(appDir, "src/main/res/layout/dialog_confirm_delete.xml").readText(Charsets.UTF_8)
        val stringsXml = File(appDir, "src/main/res/values/strings.xml").readText(Charsets.UTF_8)

        assertTrue(optionsXml.contains("android:id=\"@+id/optionDeleteFileOnly\""))
        assertTrue(optionsXml.contains("android:id=\"@+id/optionDeleteFileAndUninstall\""))
        assertTrue(optionsXml.contains("android:id=\"@+id/tvFileOnlySubtitle\""))
        assertTrue(optionsXml.contains("android:id=\"@+id/tvFileAndUninstallSubtitle\""))
        assertTrue(optionsXml.contains("@drawable/bg_neo_card"))

        assertTrue(confirmXml.contains("android:id=\"@+id/btnCancelDelete\""))
        assertTrue(confirmXml.contains("android:id=\"@+id/btnConfirmDelete\""))
        assertTrue(confirmXml.contains("@string/action_cancel_dialog"))
        assertTrue(confirmXml.contains("@string/action_yes_delete"))

        assertTrue(stringsXml.contains("<string name=\"action_cancel_dialog\">Cancel</string>"))
        assertTrue(stringsXml.contains("<string name=\"action_yes_delete\">Yes, delete</string>"))
        assertTrue(stringsXml.contains("<string name=\"delete_option_file_only_title\">Delete file only</string>"))
        assertTrue(stringsXml.contains("<string name=\"delete_option_file_and_uninstall_title\">Delete file &amp; uninstall app</string>"))
    }
}
