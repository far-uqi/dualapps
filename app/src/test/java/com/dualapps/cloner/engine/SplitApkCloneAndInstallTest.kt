package com.dualapps.cloner.engine

import com.android.apksig.ApkVerifier
import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.data.InstalledAppsRepository
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.model.SplitApkFile
import com.dualapps.cloner.util.InstallSessionWriter
import com.dualapps.cloner.util.SplitInstallOutcome
import com.dualapps.cloner.util.SplitInstallSessionCoordinator
import com.dualapps.cloner.util.SplitInstallStatusMapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile

class SplitApkCloneAndInstallTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildAbiSplitApk(output: File, packageName: String) {
        val manifest = BinaryFixtureBuilder.buildManifestAxml(packageName, useUtf8 = true, labelLiteral = null)
        val fakeSo = ByteArray(128) { (it and 0xFF).toByte() }
        AlignedZipWriter(output).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", manifest)
            // Sengaja TANPA resources.arsc seperti split_config.arm64_v8a.apk nyata
            w.writeStoredEntry("lib/arm64-v8a/libnative.so", fakeSo, AlignedZipWriter.ALIGN_SO)
        }
    }

    private fun buildDensitySplitApk(
        output: File,
        packageName: String,
        fgResId: Int,
        fgPath: String,
        origFgBytes: ByteArray
    ) {
        val manifest = BinaryFixtureBuilder.buildManifestAxml(packageName, useUtf8 = true, labelLiteral = null)
        val typeId = (fgResId ushr 16) and 0xFF
        val entryId = fgResId and 0xFFFF
        val arsc = BinaryFixtureBuilder.buildArscWithTypeChunks(
            packageName = packageName,
            useUtf8 = true,
            typeChunks = listOf(typeId to listOf(entryId to fgPath))
        )
        AlignedZipWriter(output).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", manifest)
            w.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
            w.writeStoredEntry(fgPath, origFgBytes, AlignedZipWriter.ALIGN_DEFAULT)
        }
    }

    private fun buildLanguageSplitApk(
        output: File,
        packageName: String,
        labelResId: Int,
        localizedLabel: String
    ) {
        val manifest = BinaryFixtureBuilder.buildManifestAxml(packageName, useUtf8 = true, labelLiteral = null)
        val typeId = (labelResId ushr 16) and 0xFF
        val entryId = labelResId and 0xFFFF
        val arsc = BinaryFixtureBuilder.buildArscWithTypeChunks(
            packageName = packageName,
            useUtf8 = true,
            typeChunks = listOf(typeId to listOf(entryId to localizedLabel))
        )
        AlignedZipWriter(output).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", manifest)
            w.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
        }
    }

    @Test
    fun installedAppAndSplitFilesTrackTotalSizeAndVersionCode() {
        val f1 = tempFolder.newFile("split_config.arm64_v8a.apk").apply { writeBytes(ByteArray(2000)) }
        val f2 = tempFolder.newFile("split_config.xxhdpi.apk").apply { writeBytes(ByteArray(500)) }

        val splits = InstalledAppsRepository.buildSplitFiles(
            splitSourceDirs = arrayOf(f1.absolutePath, f2.absolutePath),
            splitNames = arrayOf("config.arm64_v8a", "config.xxhdpi")
        )
        assertEquals(2, splits.size)
        assertEquals("config.arm64_v8a", splits[0].splitName)
        assertEquals(2000L, splits[0].sizeBytes)
        assertEquals("config.xxhdpi", splits[1].splitName)
        assertEquals(500L, splits[1].sizeBytes)

        val app = InstalledApp(
            packageName = "com.rarlab.rar",
            label = "RAR",
            apkSizeBytes = 1000L + 2000L + 500L,
            sourceDir = "/data/app/base.apk",
            isSystemApp = false,
            isSplitApk = true,
            versionName = "6.10",
            splitFiles = splits,
            versionCode = 104L
        )
        assertEquals(3, app.totalFileCount)
        assertEquals(1000L, app.baseApkSizeBytes)
        assertEquals(104L, app.versionCode)
    }

    @Test
    fun clonedApkRecordSerializesSplitPathsAndRemainsBackwardCompatibleWithV1Json() {
        val filesDir = tempFolder.newFolder("filesSplitRepo")
        val repo = ClonedApksRepository(filesDir)
        val bundleDir = File(repo.clonedApksDir, "com.rar.clone_1").apply { mkdirs() }
        val base = File(bundleDir, "base.apk").apply { writeBytes(ByteArray(100)) }
        val s1 = File(bundleDir, "split_config.arm64_v8a.apk").apply { writeBytes(ByteArray(80)) }
        val s2 = File(bundleDir, "split_config.xxhdpi.apk").apply { writeBytes(ByteArray(40)) }

        val splitRecord = ClonedApkRecord(
            id = "com.rar.clone_1",
            cloneName = "RAR 2",
            clonePackageName = "com.rar.clone",
            originalPackageName = "com.rar",
            originalAppName = "RAR",
            apkPath = base.absolutePath,
            apkSizeBytes = 220L,
            createdAtMillis = 1_700_000_200_000L,
            splitApkPaths = listOf(s1.absolutePath, s2.absolutePath),
            sourceVersionCode = 104L
        )
        File(repo.clonedApksDir, "${splitRecord.id}.json")
            .writeText(splitRecord.toJsonString(), Charsets.UTF_8)

        // JSON format v1.0 (tanpa splitApkPaths dan tanpa sourceVersionCode) tetap harus terbaca
        val v1Apk = File(repo.clonedApksDir, "v1_single.apk").apply { writeBytes(ByteArray(50)) }
        val v1RawJson = """{"id":"v1_single","cloneName":"Single 2","clonePackageName":"com.s.clone","originalPackageName":"com.s","originalAppName":"Single","apkPath":"${v1Apk.absolutePath.replace("\\", "\\\\")}","apkSizeBytes":50,"createdAtMillis":1700000100000}"""
        File(repo.clonedApksDir, "v1_single.json").writeText(v1RawJson, Charsets.UTF_8)

        val loaded = repo.loadAll()
        assertEquals(2, loaded.size)
        assertTrue(loaded[0].isSplitClone)
        assertEquals(3, loaded[0].totalFileCount)
        assertEquals(104L, loaded[0].sourceVersionCode)
        assertFalse(loaded[1].isSplitClone)
        assertEquals(1, loaded[1].totalFileCount)

        // Hapus split clone harus menghapus semua file split + folder bundle + json
        assertTrue(repo.delete(loaded[0]))
        assertFalse(bundleDir.exists())
        assertEquals(1, repo.loadAll().size)
    }

    @Test
    fun clonesMultiSplitApkWithAbiWithoutArscAndDensitySplitWithSameCert() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheMulti")
        val filesDir = tempFolder.newFolder("filesMulti")
        val oldPkg = "com.rarlab.rar"
        val newPkg = "com.rarlab.rar.clone"

        val baseApk = tempFolder.newFile("base.apk")
        BinaryFixtureBuilder.buildFixtureApk(baseApk, oldPkg)

        val abiSplit = tempFolder.newFile("split_config.arm64_v8a.apk")
        buildAbiSplitApk(abiSplit, oldPkg)

        val densitySplit = tempFolder.newFile("split_config.xxhdpi.apk")
        buildDensitySplitApk(
            output = densitySplit,
            packageName = oldPkg,
            fgResId = 0x7F0800A7,
            fgPath = "res/drawable-xxhdpi-v4/ic_launcher_foreground.png",
            origFgBytes = "ORIG_FG".toByteArray()
        )

        val progressEvents = mutableListOf<Triple<Int, Int, String>>()
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apk ->
                ZipFile(apk).use { z ->
                    AxmlPatcher.readPackageName(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            request = CloneRequest(
                sourceApkPath = baseApk.absolutePath,
                isSplitApk = true,
                originalAppName = "Dualapps",
                originalPackageName = oldPkg,
                cloneName = "RAR 2",
                clonePackageName = newPkg,
                splitApkPaths = listOf(
                    SplitApkFile("config.arm64_v8a", abiSplit.absolutePath, abiSplit.length()),
                    SplitApkFile("config.xxhdpi", densitySplit.absolutePath, densitySplit.length())
                ),
                sourceVersionCode = 104L
            ),
            onFileProgress = { idx, total, name -> progressEvents.add(Triple(idx, total, name)) }
        )

        assertTrue(record.isSplitClone)
        assertEquals(3, record.totalFileCount)
        assertEquals(104L, record.sourceVersionCode)
        assertTrue(progressEvents.any { it.first == 2 && it.second == 3 })
        assertTrue(progressEvents.any { it.first == 3 && it.second == 3 })

        // Semua 3 file harus lolos ApkVerifier dan memakai sertifikat penandatangan yang IDENTIK
        val certs = record.allApkFiles.map { apk ->
            val res = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()
            assertTrue("Tanda tangan ${apk.name} harus valid", res.isVerified)
            res.signerCertificates.single().encoded
        }
        assertArrayEquals(certs[0], certs[1])
        assertArrayEquals(certs[0], certs[2])

        // Semua 3 file harus memiliki manifest package baru; split ABI tetap tanpa ARSC & align .so 4096
        record.allApkFiles.forEach { apk ->
            ZipFile(apk).use { z ->
                val manifest = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
                assertEquals(newPkg, AxmlPatcher.readPackageName(manifest))
                if (apk.name.contains("arm64_v8a")) {
                    assertTrue(z.getEntry("resources.arsc") == null)
                    assertNotNull(z.getEntry("lib/arm64-v8a/libnative.so"))
                } else {
                    val arsc = z.getInputStream(z.getEntry("resources.arsc")).readBytes()
                    assertEquals(listOf(newPkg), ArscPatcher.readPackageNames(arsc))
                }
            }
        }
    }

    @Test
    fun appliesCustomIconAcrossDensitySplitApkLikeFakeGps() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheIconSplit")
        val filesDir = tempFolder.newFolder("filesIconSplit")
        val oldPkg = "com.gsmartstudio.fakegps"
        val newPkg = "com.gsmartstudio.fakegps.clone"
        val iconXmlResId = 0x7F0F0000
        val fgResId = 0x7F0800A7
        val bgResId = 0x7F06007E

        val baseApk = tempFolder.newFile("fakegps_base.apk")
        val adaptiveXml = BinaryFixtureBuilder.buildAdaptiveIconAxml(foregroundResId = fgResId, backgroundResId = bgResId)
        val baseArsc = BinaryFixtureBuilder.buildArscWithTypeChunks(
            packageName = oldPkg,
            useUtf8 = true,
            typeChunks = listOf(0x0F to listOf(0x0000 to "res/mipmap-anydpi-v26/ic_launcher.xml"))
        )
        AlignedZipWriter(baseApk).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                BinaryFixtureBuilder.buildManifestAxml(oldPkg, useUtf8 = true, labelLiteral = "Fake GPS Pro", iconResId = iconXmlResId)
            )
            w.writeStoredEntry("resources.arsc", baseArsc, AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", "dex\n035\u0000FAKE".toByteArray())
            w.writeDeflatedEntry("res/mipmap-anydpi-v26/ic_launcher.xml", adaptiveXml)
        }

        val xxhdpiSplit = tempFolder.newFile("fakegps_xxhdpi.apk")
        val fgPath = "res/drawable-xxhdpi-v4/ic_launcher_foreground.png"
        buildDensitySplitApk(xxhdpiSplit, oldPkg, fgResId, fgPath, "ORIGINAL_XXHDPI_FG".toByteArray())

        val customXxhdpiPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 4, 4)
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apk ->
                ZipFile(apk).use { z ->
                    AxmlPatcher.readPackageName(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            CloneRequest(
                sourceApkPath = baseApk.absolutePath,
                isSplitApk = true,
                originalAppName = "Fake GPS Pro",
                originalPackageName = oldPkg,
                cloneName = "Fake GPS 2",
                clonePackageName = newPkg,
                customIconPngs = mapOf("xxhdpi" to customXxhdpiPng),
                splitApkPaths = listOf(SplitApkFile("config.xxhdpi", xxhdpiSplit.absolutePath, xxhdpiSplit.length()))
            )
        )

        val clonedDensitySplit = File(record.splitApkPaths.single())
        ZipFile(clonedDensitySplit).use { z ->
            val replaced = z.getInputStream(z.getEntry(fgPath)).readBytes()
            assertArrayEquals(customXxhdpiPng, replaced)
        }
    }

    @Test
    fun appliesCustomLabelAcrossBaseAndLanguageSplitApk() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheLangSplit")
        val filesDir = tempFolder.newFolder("filesLangSplit")
        val oldPkg = "com.example.multilang"
        val newPkg = "com.example.multilang.clone"
        val labelResId = 0x7F120025

        val baseApk = tempFolder.newFile("lang_base.apk")
        val baseArsc = BinaryFixtureBuilder.buildArscWithTypeChunks(
            packageName = oldPkg,
            useUtf8 = true,
            typeChunks = listOf(0x12 to listOf(0x0025 to "App Default"))
        )
        AlignedZipWriter(baseApk).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                BinaryFixtureBuilder.buildManifestAxml(oldPkg, useUtf8 = true, labelLiteral = null, labelResId = labelResId)
            )
            w.writeStoredEntry("resources.arsc", baseArsc, AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", "dex\n035\u0000FAKE".toByteArray())
        }

        val langInSplit = tempFolder.newFile("lang_in.apk")
        buildLanguageSplitApk(langInSplit, oldPkg, labelResId, "Aplikasi Indo")

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apk ->
                ZipFile(apk).use { z ->
                    AxmlPatcher.readPackageName(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            CloneRequest(
                sourceApkPath = baseApk.absolutePath,
                isSplitApk = true,
                originalAppName = "App Default",
                originalPackageName = oldPkg,
                cloneName = "Clone Keren",
                clonePackageName = newPkg,
                splitApkPaths = listOf(SplitApkFile("config.in", langInSplit.absolutePath, langInSplit.length()))
            )
        )

        ZipFile(record.apkFile).use { z ->
            val arsc = z.getInputStream(z.getEntry("resources.arsc")).readBytes()
            assertEquals(listOf("Clone Keren"), ArscPatcher.readStringResourceValues(arsc, labelResId))
        }
        ZipFile(File(record.splitApkPaths.single())).use { z ->
            val arsc = z.getInputStream(z.getEntry("resources.arsc")).readBytes()
            assertEquals(listOf("Clone Keren"), ArscPatcher.readStringResourceValues(arsc, labelResId))
        }
    }

    @Test
    fun failsWithIconPatchFailedWhenNoIconFoundAcrossBaseAndAllSplits() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheNoIconSplit")
        val filesDir = tempFolder.newFolder("filesNoIconSplit")
        val oldPkg = "com.example.noicon.split"
        val baseApk = tempFolder.newFile("noicon_base.apk")
        BinaryFixtureBuilder.buildFixtureApk(baseApk, oldPkg)
        val abiSplit = tempFolder.newFile("noicon_abi.apk")
        buildAbiSplitApk(abiSplit, oldPkg)

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { "$oldPkg.clone" },
            isPackageInstalled = { false }
        )

        try {
            pipeline.execute(
                CloneRequest(
                    sourceApkPath = baseApk.absolutePath,
                    isSplitApk = true,
                    originalAppName = "Dualapps",
                    originalPackageName = oldPkg,
                    cloneName = "Dualapps",
                    clonePackageName = "$oldPkg.clone",
                    customIconPngs = mapOf("xxhdpi" to byteArrayOf(1, 2, 3)),
                    splitApkPaths = listOf(SplitApkFile("config.arm64_v8a", abiSplit.absolutePath, abiSplit.length()))
                )
            )
            fail("Seharusnya melempar ICON_PATCH_FAILED")
        } catch (e: CloneException) {
            assertEquals(CloneError.ICON_PATCH_FAILED, e.error)
        }
        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun cancelsMidSplitPipelineAndCleansTempAndPartialOutput() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheCancelSplit")
        val filesDir = tempFolder.newFolder("filesCancelSplit")
        val oldPkg = "com.example.cancelsplit"
        val baseApk = tempFolder.newFile("cancel_base.apk")
        BinaryFixtureBuilder.buildFixtureApk(baseApk, oldPkg)
        val abiSplit = tempFolder.newFile("cancel_abi.apk")
        buildAbiSplitApk(abiSplit, oldPkg)

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { "$oldPkg.clone" },
            isPackageInstalled = { false }
        )

        var caughtError: CloneError? = null
        lateinit var job: Job
        job = launch {
            try {
                pipeline.execute(
                    request = CloneRequest(
                        sourceApkPath = baseApk.absolutePath,
                        isSplitApk = true,
                        originalAppName = "Dualapps",
                        originalPackageName = oldPkg,
                        cloneName = "Dualapps 2",
                        clonePackageName = "$oldPkg.clone",
                        splitApkPaths = listOf(SplitApkFile("config.arm64_v8a", abiSplit.absolutePath, abiSplit.length()))
                    ),
                    onStepStart = { step ->
                        if (step == CloneStep.SIGN_AND_VERIFY) {
                            job.cancel()
                        }
                    }
                )
            } catch (e: CloneException) {
                caughtError = e.error
            }
        }
        job.join()

        assertEquals(CloneError.CANCELLED, caughtError)
        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
        val workRoot = File(cacheDir, "clone_work")
        assertTrue(!workRoot.exists() || workRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun sessionCoordinatorWritesAllSplitsFsyncsAndCommitsInSingleSession() {
        val f1 = tempFolder.newFile("sess_base.apk").apply { writeBytes("BASE_BYTES".toByteArray()) }
        val f2 = tempFolder.newFile("sess_split1.apk").apply { writeBytes("SPLIT1_BYTES".toByteArray()) }
        val f3 = tempFolder.newFile("sess_split2.apk").apply { writeBytes("SPLIT2".toByteArray()) }

        var openedSessions = 0
        var recordedTotalSize = 0L
        val writtenNames = mutableListOf<String>()
        val writtenData = LinkedHashMap<String, ByteArrayOutputStream>()
        var fsyncCount = 0
        var commitCount = 0
        var abandonCount = 0

        val count = SplitInstallSessionCoordinator.writeAndCommit(listOf(f1, f2, f3)) { totalBytes ->
            openedSessions++
            recordedTotalSize = totalBytes
            object : InstallSessionWriter {
                override fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream {
                    writtenNames.add(name)
                    val baos = ByteArrayOutputStream()
                    writtenData[name] = baos
                    return baos
                }

                override fun fsync(out: OutputStream) {
                    fsyncCount++
                }

                override fun commit() {
                    commitCount++
                }

                override fun abandon() {
                    abandonCount++
                }

                override fun close() = Unit
            }
        }

        assertEquals(3, count)
        assertEquals(1, openedSessions)
        assertEquals(f1.length() + f2.length() + f3.length(), recordedTotalSize)
        assertEquals(3, writtenNames.size)
        assertEquals(3, fsyncCount)
        assertEquals(1, commitCount)
        assertEquals(0, abandonCount)
        assertArrayEquals("BASE_BYTES".toByteArray(), writtenData[writtenNames[0]]!!.toByteArray())
        assertArrayEquals("SPLIT1_BYTES".toByteArray(), writtenData[writtenNames[1]]!!.toByteArray())
        assertArrayEquals("SPLIT2".toByteArray(), writtenData[writtenNames[2]]!!.toByteArray())
    }

    @Test
    fun sessionCoordinatorAbandonsSessionOnWriteFailure() {
        val f1 = tempFolder.newFile("fail_base.apk").apply { writeBytes("DATA".toByteArray()) }
        var abandoned = false

        try {
            SplitInstallSessionCoordinator.writeAndCommit(listOf(f1)) {
                object : InstallSessionWriter {
                    override fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream {
                        return object : OutputStream() {
                            override fun write(b: Int) {
                                throw IOException("Disk penuh simulasi")
                            }
                        }
                    }

                    override fun fsync(out: OutputStream) = Unit
                    override fun commit() = fail("Tidak boleh commit saat write gagal")
                    override fun abandon() {
                        abandoned = true
                    }

                    override fun close() = Unit
                }
            }
            fail("Seharusnya melempar CloneException(SESSION_FAILED)")
        } catch (e: CloneException) {
            assertEquals(CloneError.SESSION_FAILED, e.error)
        }
        assertTrue("Sesi wajib di-abandon saat gagal", abandoned)
    }

    @Test
    fun splitInstallStatusMapperMapsAllPackageInstallerStatusesToIndonesian() {
        assertEquals(
            SplitInstallOutcome.PendingUserAction,
            SplitInstallStatusMapper.mapStatus(SplitInstallStatusMapper.STATUS_PENDING_USER_ACTION, null)
        )
        assertTrue(
            SplitInstallStatusMapper.mapStatus(SplitInstallStatusMapper.STATUS_SUCCESS, null)
                is SplitInstallOutcome.Success
        )
        val blocked = SplitInstallStatusMapper.mapStatus(SplitInstallStatusMapper.STATUS_FAILURE_BLOCKED, "blocked")
            as SplitInstallOutcome.Failure
        assertEquals(CloneError.SPLIT_INSTALL_FAILED, blocked.error)
        assertTrue(blocked.message.contains("Play Protect"))

        val conflict = SplitInstallStatusMapper.mapStatus(SplitInstallStatusMapper.STATUS_FAILURE_CONFLICT, null)
            as SplitInstallOutcome.Failure
        assertTrue(conflict.message.contains("konflik"))

        val incompatible = SplitInstallStatusMapper.mapStatus(SplitInstallStatusMapper.STATUS_FAILURE_INCOMPATIBLE, null)
            as SplitInstallOutcome.Failure
        assertTrue(incompatible.message.contains("ABI"))
    }
}
