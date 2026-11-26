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

    @Test
    fun abortsWithIncompatibleAppBeforePatchingWhenApkHasSignatureProviderDexUri() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheCompatFail")
        val filesDir = tempFolder.newFolder("filesCompatFail")
        val sourceApk = tempFolder.newFile("source-incompat.apk")
        val oldPkg = "com.example.fb"
        val newPkg = "com.example.fb.clone"

        val manifest = CloneCompatibilityCheckerTest.buildManifestWithProvidersAndPermissions(
            packageName = oldPkg,
            permissions = listOf(
                CloneCompatibilityCheckerTest.Companion.PermDef(
                    "com.example.permission.prod.FB_APP_COMMUNICATION",
                    protectionLevel = 0x00000002
                )
            ),
            providers = listOf(
                CloneCompatibilityCheckerTest.Companion.ProviderDef(
                    authorities = "$oldPkg.ClientMessagePushDedupInfoProvider",
                    permission = "com.example.permission.prod.FB_APP_COMMUNICATION"
                )
            )
        )
        val dexBytes = DexStringReaderTest.buildSyntheticDex(
            listOf("content://$oldPkg.ClientMessagePushDedupInfoProvider/mutestatus")
        )
        AlignedZipWriter(sourceApk).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", manifest)
            w.writeStoredEntry("resources.arsc", BinaryFixtureBuilder.buildArsc(oldPkg), AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", dexBytes)
        }

        val startedSteps = mutableListOf<CloneStep>()
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { newPkg },
            isPackageInstalled = { false }
        )

        try {
            pipeline.execute(
                request = CloneRequest(
                    sourceApkPath = sourceApk.absolutePath,
                    isSplitApk = false,
                    originalAppName = "FB Demo",
                    originalPackageName = oldPkg,
                    cloneName = "FB Demo 2",
                    clonePackageName = newPkg
                ),
                onStepStart = { startedSteps.add(it) }
            )
            fail("Seharusnya gagal dengan INCOMPATIBLE_APP sebelum PATCH_MANIFEST")
        } catch (e: CloneException) {
            assertEquals(CloneError.INCOMPATIBLE_APP, e.error)
            assertTrue(e.message.orEmpty().contains("$oldPkg.ClientMessagePushDedupInfoProvider"))
            assertTrue(e.message.orEmpty().contains("com.example.permission.prod.FB_APP_COMMUNICATION"))
        }

        // Pastikan PATCH_MANIFEST tidak pernah dimulai dan tidak ada output APK yang dihasilkan
        assertEquals(listOf(CloneStep.VALIDATE_SOURCE), startedSteps)
        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun completesNormallyWhenApkHasCleanDexWithoutDangerousProviderUri() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheCompatOk")
        val filesDir = tempFolder.newFolder("filesCompatOk")
        val sourceApk = tempFolder.newFile("source-clean-dex.apk")
        val oldPkg = "com.example.cleanapp"
        val newPkg = "com.example.cleanapp.clone"
        val cleanDex = DexStringReaderTest.buildSyntheticDex(
            listOf("content://$oldPkg.fileprovider/items", "Lcom/example/cleanapp/MainActivity;")
        )
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, oldPkg, dexPayload = cleanDex)

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apkFile ->
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val mf = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                    AxmlPatcher.readPackageName(mf)
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            CloneRequest(
                sourceApkPath = sourceApk.absolutePath,
                isSplitApk = false,
                originalAppName = "Dualapps",
                originalPackageName = oldPkg,
                cloneName = "Dualapps 2",
                clonePackageName = newPkg
            )
        )
        assertEquals(newPkg, record.clonePackageName)
        assertTrue(record.apkFile.exists())
    }

    @Test
    fun completesWithRecordedWarningWhenApkOnlyTriggersRuleB() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheRuleB")
        val filesDir = tempFolder.newFolder("filesRuleB")
        val sourceApk = tempFolder.newFile("source-muse-ruleb.apk")
        val oldPkg = "com.example.aura"
        val newPkg = "com.example.aura.clone"
        val museDex = DexStringReaderTest.buildSyntheticDex(
            listOf("content://com.facebook.katana.provider.UserValuesProvider/user_values")
        )
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, oldPkg, dexPayload = museDex)

        val resolver = ContentProviderResolver { auth ->
            if (auth == "com.facebook.katana.provider.UserValuesProvider") {
                ResolvedProviderInfo(
                    authority = auth,
                    isSignatureProtected = true,
                    signaturePermissionName = "com.facebook.katana.provider.ACCESS",
                    ownerPackageName = "com.facebook.katana"
                )
            } else null
        }

        val receivedWarnings = mutableListOf<String>()
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apkFile ->
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val mf = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                    AxmlPatcher.readPackageName(mf)
                }
            },
            isPackageInstalled = { false },
            contentProviderResolver = resolver
        )

        val record = pipeline.execute(
            request = CloneRequest(
                sourceApkPath = sourceApk.absolutePath,
                isSplitApk = false,
                originalAppName = "Dualapps",
                originalPackageName = oldPkg,
                cloneName = "Muse 2",
                clonePackageName = newPkg
            ),
            onCompatibilityWarning = { receivedWarnings.add(it) }
        )

        assertEquals(newPkg, record.clonePackageName)
        assertTrue(record.apkFile.exists())
        assertEquals(1, receivedWarnings.size)
        assertTrue(record.compatibilityWarning.orEmpty().contains("com.facebook.katana.provider.UserValuesProvider"))
        assertTrue(record.compatibilityWarning.orEmpty().contains("com.facebook.katana.provider.ACCESS"))

        // Pastikan persistence JSON menyimpan compatibilityWarning secara round-trip
        val loaded = com.dualapps.cloner.data.ClonedApksRepository(filesDir).loadAll().single()
        assertEquals(record.compatibilityWarning, loaded.compatibilityWarning)
    }

    @Test
    fun completesRuleAApkWhenSkipCompatibilityCheckOverrideEnabledAndRecordsOverride() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheOverride")
        val filesDir = tempFolder.newFolder("filesOverride")
        val sourceApk = tempFolder.newFile("source-rulea-override.apk")
        val oldPkg = "com.example.fb"
        val newPkg = "com.example.fb.clone"

        val manifest = CloneCompatibilityCheckerTest.buildManifestWithProvidersAndPermissions(
            packageName = oldPkg,
            permissions = listOf(
                CloneCompatibilityCheckerTest.Companion.PermDef(
                    "com.example.permission.prod.FB_APP_COMMUNICATION",
                    protectionLevel = 0x00000002
                )
            ),
            providers = listOf(
                CloneCompatibilityCheckerTest.Companion.ProviderDef(
                    authorities = "$oldPkg.ClientMessagePushDedupInfoProvider",
                    permission = "com.example.permission.prod.FB_APP_COMMUNICATION"
                )
            )
        )
        val dexBytes = DexStringReaderTest.buildSyntheticDex(
            listOf("content://$oldPkg.ClientMessagePushDedupInfoProvider/mutestatus")
        )
        AlignedZipWriter(sourceApk).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", manifest)
            w.writeStoredEntry("resources.arsc", BinaryFixtureBuilder.buildArsc(oldPkg), AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", dexBytes)
        }

        val receivedWarnings = mutableListOf<String>()
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apkFile ->
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val mf = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                    AxmlPatcher.readPackageName(mf)
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            request = CloneRequest(
                sourceApkPath = sourceApk.absolutePath,
                isSplitApk = false,
                originalAppName = "FB Demo",
                originalPackageName = oldPkg,
                cloneName = "FB Demo", // sama agar tidak trigger label patch pada manifest sintetis minimal
                clonePackageName = newPkg,
                skipCompatibilityCheck = true
            ),
            onCompatibilityWarning = { receivedWarnings.add(it) }
        )

        assertEquals(newPkg, record.clonePackageName)
        assertTrue(record.apkFile.exists())
        assertEquals(1, receivedWarnings.size)
        assertEquals(ClonePipeline.OVERRIDE_WARNING_NOTE, record.compatibilityWarning)
        assertEquals(
            CloneStartAction.RunPipeline,
            CloneProgressStartResolver.resolve(
                clonePackageName = newPkg,
                savedRecordJson = record.toJsonString(),
                existingRecords = listOf(record),
                isExplicitRetry = false,
                isUpdateRun = false,
                skipCompatibilityCheck = true
            )
        )
    }
}
