package com.dualapps.cloner.engine

import com.android.apksig.ApkVerifier
import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class CloneUpdateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleRecord(
        sourceVersionCode: Long,
        sourceVersionName: String = "17.1"
    ): ClonedApkRecord {
        return ClonedApkRecord(
            id = "com.facebook.aura.clone3_1000",
            cloneName = "Muse 2",
            clonePackageName = "com.facebook.aura.clone3",
            originalPackageName = "com.facebook.aura",
            originalAppName = "Muse",
            apkPath = "/tmp/base.apk",
            apkSizeBytes = 1024L,
            createdAtMillis = 1000L,
            sourceVersionCode = sourceVersionCode,
            sourceVersionName = sourceVersionName
        )
    }

    @Test
    fun detectsUpdateAvailableWhenSourceVersionCodeIsNewer() {
        val record = sampleRecord(sourceVersionCode = 104L, sourceVersionName = "6.10")
        val status = CloneUpdateChecker.check(
            record = record,
            installedSource = InstalledPackageVersion("com.facebook.aura", 105L, "6.11"),
            isCloneInstalled = true
        )

        assertTrue(status is CloneUpdateStatus.UpdateAvailable)
        val avail = status as CloneUpdateStatus.UpdateAvailable
        assertEquals(105L, avail.currentSourceVersionCode)
        assertEquals("6.11", avail.currentSourceVersionName)
        assertEquals(104L, avail.clonedFromVersionCode)
        assertEquals("6.10", avail.clonedFromVersionName)
    }

    @Test
    fun reportsUpToDateWhenSourceVersionCodeIsEqualOrOlder() {
        val record = sampleRecord(sourceVersionCode = 104L)
        assertEquals(
            CloneUpdateStatus.UpToDate,
            CloneUpdateChecker.check(
                record = record,
                installedSource = InstalledPackageVersion("com.facebook.aura", 104L, "6.10"),
                isCloneInstalled = true
            )
        )
        assertEquals(
            CloneUpdateStatus.UpToDate,
            CloneUpdateChecker.check(
                record = record,
                installedSource = InstalledPackageVersion("com.facebook.aura", 100L, "6.00"),
                isCloneInstalled = true
            )
        )
    }

    @Test
    fun reportsSourceNotInstalledCloneNotInstalledAndUnknownSourceVersion() {
        val record = sampleRecord(sourceVersionCode = 104L)
        assertEquals(
            CloneUpdateStatus.SourceNotInstalled,
            CloneUpdateChecker.check(record, installedSource = null, isCloneInstalled = true)
        )
        assertEquals(
            CloneUpdateStatus.CloneNotInstalled,
            CloneUpdateChecker.check(
                record,
                installedSource = InstalledPackageVersion("com.facebook.aura", 105L, "6.11"),
                isCloneInstalled = false
            )
        )
        val legacyRecord = sampleRecord(sourceVersionCode = 0L, sourceVersionName = "")
        assertEquals(
            CloneUpdateStatus.UnknownSourceVersion,
            CloneUpdateChecker.check(
                legacyRecord,
                installedSource = InstalledPackageVersion("com.facebook.aura", 105L, "6.11"),
                isCloneInstalled = true
            )
        )
    }

    @Test
    fun updateModeBypassesAlreadyInstalledCheckKeepsSamePackageReusesIdentityAndOverwritesRecord() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheUpdate")
        val filesDir = tempFolder.newFolder("filesUpdate")
        val oldPkg = "com.example.notes"
        val clonePkg = "com.example.notes.clone"

        val sourceV1 = tempFolder.newFile("source_v1.apk")
        BinaryFixtureBuilder.buildFixtureApk(sourceV1, oldPkg)
        val sourceV2 = tempFolder.newFile("source_v2.apk")
        BinaryFixtureBuilder.buildFixtureApk(sourceV2, oldPkg)
        val iconSource = tempFolder.newFile("picked_icon.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }

        val keystoreManager = DeviceKeystoreManager(filesDir)
        var installedPackages = mutableSetOf(oldPkg)
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = keystoreManager,
            archivePackageReader = { apk ->
                ZipFile(apk).use { z ->
                    AxmlPatcher.readPackageName(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
                }
            },
            isPackageInstalled = { it in installedPackages }
        )

        // 1. Clone awal (v1, versionCode = 10)
        val initialRecord = pipeline.execute(
            CloneRequest(
                sourceApkPath = sourceV1.absolutePath,
                isSplitApk = false,
                originalAppName = "Dualapps",
                originalPackageName = oldPkg,
                cloneName = "Notes Kerja",
                clonePackageName = clonePkg,
                sourceVersionCode = 10L,
                sourceVersionName = "1.0",
                customIconSourcePath = iconSource.absolutePath
            )
        )
        assertEquals(10L, initialRecord.sourceVersionCode)
        assertEquals("1.0", initialRecord.sourceVersionName)
        assertTrue(initialRecord.hasCustomIcon)

        val initialCert = ApkVerifier.Builder(initialRecord.apkFile)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
            .signerCertificates
            .single()
            .encoded

        // Simulasikan clone sudah terpasang di HP
        installedPackages.add(clonePkg)

        // 2. Perbarui clone dari sumber v2 (versionCode = 12) dengan package name SAMA persis
        val updatedRecord = pipeline.execute(
            CloneRequest(
                sourceApkPath = sourceV2.absolutePath,
                isSplitApk = false,
                originalAppName = "Dualapps",
                originalPackageName = oldPkg,
                cloneName = initialRecord.cloneName,
                clonePackageName = initialRecord.clonePackageName,
                sourceVersionCode = 12L,
                sourceVersionName = "1.2",
                customIconSourcePath = initialRecord.customIconPath,
                isUpdate = true,
                existingRecordId = initialRecord.id,
                previousSourceVersionCode = initialRecord.sourceVersionCode
            )
        )

        assertEquals(initialRecord.id, updatedRecord.id)
        assertEquals(clonePkg, updatedRecord.clonePackageName)
        assertEquals("Notes Kerja", updatedRecord.cloneName)
        assertEquals(12L, updatedRecord.sourceVersionCode)
        assertEquals("1.2", updatedRecord.sourceVersionName)
        assertTrue(updatedRecord.hasCustomIcon)

        // Tidak menduplikasi entri di ClonedApksRepository
        val repo = ClonedApksRepository(filesDir)
        val all = repo.loadAll()
        assertEquals(1, all.size)
        assertEquals(12L, all[0].sourceVersionCode)

        // Sertifikat penandatangan IDENTIK sehingga Android memperlakukannya sebagai update (data clone tetap)
        val updatedCert = ApkVerifier.Builder(updatedRecord.apkFile)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
            .signerCertificates
            .single()
            .encoded
        assertArrayEquals(initialCert, updatedCert)
    }

    @Test
    fun updateModeRejectsWhenSourceVersionIsNotNewerWithoutOverwritingOldRecord() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheNoUpd")
        val filesDir = tempFolder.newFolder("filesNoUpd")
        val oldPkg = "com.example.noupd"
        val clonePkg = "com.example.noupd.clone"
        val sourceApk = tempFolder.newFile("noupd.apk")
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, oldPkg)

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { clonePkg },
            isPackageInstalled = { true }
        )

        try {
            pipeline.execute(
                CloneRequest(
                    sourceApkPath = sourceApk.absolutePath,
                    isSplitApk = false,
                    originalAppName = "Dualapps",
                    originalPackageName = oldPkg,
                    cloneName = "App 2",
                    clonePackageName = clonePkg,
                    sourceVersionCode = 10L,
                    isUpdate = true,
                    existingRecordId = "existing_1",
                    previousSourceVersionCode = 10L
                )
            )
            fail("Seharusnya melempar NO_UPDATE_AVAILABLE")
        } catch (e: CloneException) {
            assertEquals(CloneError.NO_UPDATE_AVAILABLE, e.error)
        }
    }

    @Test
    fun newCloneModeStillRejectsAlreadyInstalledPackage() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheNewRej")
        val filesDir = tempFolder.newFolder("filesNewRej")
        val sourceApk = tempFolder.newFile("newrej.apk")
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
                CloneRequest(
                    sourceApkPath = sourceApk.absolutePath,
                    isSplitApk = false,
                    originalAppName = "App",
                    originalPackageName = "com.example.app",
                    cloneName = "App 2",
                    clonePackageName = "com.example.app.clone",
                    isUpdate = false
                )
            )
            fail("Clone baru tetap harus menolak paket yang sudah terpasang")
        } catch (e: CloneException) {
            assertEquals(CloneError.PACKAGE_ALREADY_INSTALLED, e.error)
        }
    }

    @Test
    fun startResolverRunsPipelineForUpdateRunWithoutSavedStateAndRestoresAfterCompletion() {
        val filesDir = tempFolder.newFolder("filesUpdRes")
        val repoDir = ClonedApksRepository(filesDir).clonedApksDir.apply { mkdirs() }
        val apk = File(repoDir, "old.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val existing = ClonedApkRecord(
            id = "rec_1",
            cloneName = "Muse 2",
            clonePackageName = "com.facebook.aura.clone3",
            originalPackageName = "com.facebook.aura",
            originalAppName = "Muse",
            apkPath = apk.absolutePath,
            apkSizeBytes = apk.length(),
            createdAtMillis = 1000L,
            sourceVersionCode = 10L
        )

        // Awal mulai mode Perbarui (tanpa saved state): WAJIB jalankan pipeline meski record lama ada di repo
        val initialAction = CloneProgressStartResolver.resolve(
            clonePackageName = existing.clonePackageName,
            savedRecordJson = null,
            existingRecords = listOf(existing),
            isExplicitRetry = false,
            isUpdateRun = true
        )
        assertEquals(CloneStartAction.RunPipeline, initialAction)

        // Setelah update selesai dan Activity dibuat ulang (saved state ada): tampilkan state Selesai
        val afterUpdateAction = CloneProgressStartResolver.resolve(
            clonePackageName = existing.clonePackageName,
            savedRecordJson = existing.copy(sourceVersionCode = 12L).toJsonString(),
            existingRecords = listOf(existing),
            isExplicitRetry = false,
            isUpdateRun = true
        )
        assertTrue(afterUpdateAction is CloneStartAction.ShowCompleted)
        assertEquals(12L, (afterUpdateAction as CloneStartAction.ShowCompleted).record.sourceVersionCode)
    }

    @Test
    fun clonedApkRecordPersistsSourceVersionNameAndCustomIconPathWithBackwardCompatibility() {
        val withNewFields = ClonedApkRecord(
            id = "rec_v2",
            cloneName = "RAR 2",
            clonePackageName = "com.rarlab.rar.clone",
            originalPackageName = "com.rarlab.rar",
            originalAppName = "RAR",
            apkPath = "/files/cloned_apks/rec_v2/base.apk",
            apkSizeBytes = 3500L,
            createdAtMillis = 2000L,
            splitApkPaths = listOf("/files/cloned_apks/rec_v2/split_config.arm64_v8a.apk"),
            sourceVersionCode = 104L,
            sourceVersionName = "6.10.build104",
            customIconPath = "/files/cloned_apks/icons/rec_v2.png"
        )
        val parsed = ClonedApkRecord.fromJsonString(withNewFields.toJsonString())!!
        assertEquals("6.10.build104", parsed.sourceVersionName)
        assertEquals("/files/cloned_apks/icons/rec_v2.png", parsed.customIconPath)
        assertEquals(104L, parsed.sourceVersionCode)

        // JSON lama tanpa sourceVersionName & customIconPath tetap kompatibel
        val legacyJson = """{"id":"old_1","cloneName":"Old 2","clonePackageName":"com.old.clone","originalPackageName":"com.old","originalAppName":"Old","apkPath":"/tmp/old.apk","apkSizeBytes":100,"createdAtMillis":500}"""
        val legacyParsed = ClonedApkRecord.fromJsonString(legacyJson)!!
        assertEquals(0L, legacyParsed.sourceVersionCode)
        assertEquals("", legacyParsed.sourceVersionName)
        assertNull(legacyParsed.customIconPath)
    }
}
