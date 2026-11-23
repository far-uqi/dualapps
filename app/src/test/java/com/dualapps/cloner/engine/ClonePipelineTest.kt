package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClonePipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun executesFullPipelineAndSavesVerifiedClone() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        val filesDir = tempFolder.newFolder("files")
        val sourceApk = tempFolder.newFile("source-app.apk")
        val oldPkg = "com.dualapps.cloner"
        val newPkg = "com.dualapps.cloner.clone"
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, oldPkg)

        val stepsDone = mutableListOf<CloneStep>()
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apkFile ->
                // Baca manifest dari APK hasil untuk mensimulasikan PackageManager.getPackageArchiveInfo
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                    AxmlPatcher.readPackageName(manifest)
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            request = CloneRequest(
                sourceApkPath = sourceApk.absolutePath,
                isSplitApk = false,
                originalAppName = "Dualapps",
                originalPackageName = oldPkg,
                cloneName = "Dualapps 2",
                clonePackageName = newPkg
            ),
            onStepDone = { stepsDone.add(it) }
        )

        assertEquals(CloneStep.entries.size, stepsDone.size)
        assertEquals(newPkg, record.clonePackageName)
        assertTrue(record.apkFile.exists())
        // Pastikan folder kerja sementara sudah dibersihkan
        val workRoot = File(cacheDir, "clone_work")
        assertTrue(!workRoot.exists() || workRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun detectsVerifyPackageMismatchAndDeletesOutput() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheMismatch")
        val filesDir = tempFolder.newFolder("filesMismatch")
        val sourceApk = tempFolder.newFile("source-mismatch.apk")
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, "com.dualapps.cloner")

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            // Simulasikan getPackageArchiveInfo mengembalikan package yang tidak sesuai
            archivePackageReader = { "com.wrong.package" },
            isPackageInstalled = { false }
        )

        try {
            pipeline.execute(
                CloneRequest(
                    sourceApkPath = sourceApk.absolutePath,
                    isSplitApk = false,
                    originalAppName = "Dualapps",
                    originalPackageName = "com.dualapps.cloner",
                    cloneName = "Dualapps 2",
                    clonePackageName = "com.dualapps.cloner.clone"
                )
            )
            fail("Seharusnya melempar VERIFY_PACKAGE_MISMATCH")
        } catch (e: CloneException) {
            assertEquals(CloneError.VERIFY_PACKAGE_MISMATCH, e.error)
        }

        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun rejectsSplitApkAndAlreadyInstalledPackage() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheErrors")
        val filesDir = tempFolder.newFolder("filesErrors")
        val sourceApk = tempFolder.newFile("source-err.apk")
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, "com.example.app")

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { "com.example.app.clone" },
            isPackageInstalled = { it == "com.example.app.clone" }
        )

        try {
            pipeline.execute(
                CloneRequest(sourceApk.absolutePath, true, "App", "com.example.app", "App 2", "com.example.app.clone2")
            )
            fail("Seharusnya melempar SPLIT_UNSUPPORTED")
        } catch (e: CloneException) {
            assertEquals(CloneError.SPLIT_UNSUPPORTED, e.error)
        }

        try {
            pipeline.execute(
                CloneRequest(sourceApk.absolutePath, false, "App", "com.example.app", "App 2", "com.example.app.clone")
            )
            fail("Seharusnya melempar PACKAGE_ALREADY_INSTALLED")
        } catch (e: CloneException) {
            assertEquals(CloneError.PACKAGE_ALREADY_INSTALLED, e.error)
        }
    }

    @Test
    fun packageNameValidatorSuggestsNextAvailableCloneNumber() {
        val installed = setOf("com.app.demo.clone", "com.app.demo.clone2")
        val suggested = PackageNameValidator.suggestClonePackage("com.app.demo") { it in installed }
        assertEquals("com.app.demo.clone3", suggested)

        assertTrue(
            PackageNameValidator.validate("com.app.demo.clone3", "com.app.demo") { it in installed }
                is PackageNameValidator.ValidationResult.Valid
        )
        val invalidSyntax = PackageNameValidator.validate("invalid_single_segment", "com.app.demo") { false }
        assertTrue(invalidSyntax is PackageNameValidator.ValidationResult.Invalid)
    }

    @Test
    fun cancelsMidPipelineWithCancelledErrorAndCleansTempDir() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheCancel")
        val filesDir = tempFolder.newFolder("filesCancel")
        val sourceApk = tempFolder.newFile("source-cancel.apk")
        val oldPkg = "com.dualapps.cloner"
        val newPkg = "com.dualapps.cloner.clone"
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, oldPkg)

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { newPkg },
            isPackageInstalled = { false }
        )

        var caughtError: CloneError? = null
        var completedNormally = false
        val startedSteps = mutableListOf<CloneStep>()
        lateinit var cloneJob: Job

        cloneJob = launch {
            try {
                pipeline.execute(
                    request = CloneRequest(
                        sourceApkPath = sourceApk.absolutePath,
                        isSplitApk = false,
                        originalAppName = "Dualapps",
                        originalPackageName = oldPkg,
                        cloneName = "Dualapps 2",
                        clonePackageName = newPkg
                    ),
                    onStepStart = { step ->
                        startedSteps.add(step)
                        // Batalkan Coroutine tepat saat memasuki langkah SIGN_AND_VERIFY
                        // (setelah source.apk dan unsigned.apk sempat dibuat di workDir).
                        if (step == CloneStep.SIGN_AND_VERIFY) {
                            cloneJob.cancel()
                        }
                    }
                )
                completedNormally = true
            } catch (e: CloneException) {
                caughtError = e.error
            }
        }
        cloneJob.join()

        assertTrue("Pipeline seharusnya berhenti karena dibatalkan", !completedNormally)
        assertEquals(CloneError.CANCELLED, caughtError)
        assertTrue(startedSteps.contains(CloneStep.SIGN_AND_VERIFY))
        assertTrue(!startedSteps.contains(CloneStep.VERIFY_ARCHIVE))

        // Pastikan tidak ada APK tersimpan di folder hasil dan folder kerja sementara bersih
        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
        val workRoot = File(cacheDir, "clone_work")
        assertTrue(!workRoot.exists() || workRoot.listFiles().isNullOrEmpty())
    }
}
