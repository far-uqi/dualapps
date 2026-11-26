package com.dualapps.cloner.data

import com.dualapps.cloner.engine.AlignedZipWriter
import com.dualapps.cloner.engine.ArchivePackageReader
import com.dualapps.cloner.engine.AxmlPatcher
import com.dualapps.cloner.engine.AxmlStringPool
import com.dualapps.cloner.engine.BinaryFixtureBuilder
import com.dualapps.cloner.engine.ClonePipeline
import com.dualapps.cloner.engine.CloneRequest
import com.dualapps.cloner.engine.DeviceKeystoreManager
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.util.InstallProgressState
import com.dualapps.cloner.util.InstallProgressTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkBundleAndInstallProgressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun resetTracker() {
        InstallProgressTracker.reset()
    }

    @Test
    fun apksValidWithBaseAndTwoSplitsAndTocPb() {
        val work = tempFolder.newFolder("apks_valid")
        val cacheDir = tempFolder.newFolder("cache_apks_valid")
        val baseApk = File(work, "base-master.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.shop")
        }
        val splitArm = File(work, "base-arm64_v8a.apk").also {
            buildSplitApk(it, "com.example.shop", "config.arm64_v8a", includeArsc = false)
        }
        val splitLang = File(work, "base-id.apk").also {
            buildSplitApk(it, "com.example.shop", "config.id", includeArsc = true)
        }
        val apksFile = File(work, "shop.apks")
        writeZipBundle(
            apksFile,
            mapOf(
                "toc.pb" to byteArrayOf(0x0A, 0x01, 0x02),
                "splits/base-master.apk" to baseApk.readBytes(),
                "splits/base-arm64_v8a.apk" to splitArm.readBytes(),
                "splits/base-id.apk" to splitLang.readBytes()
            )
        )

        val inspectedFiles = mutableListOf<File>()
        val inspector = ApkArchiveInspector { apk ->
            inspectedFiles.add(apk)
            ApkArchiveMetadata(
                packageName = "com.example.shop",
                label = "Shop App",
                versionName = "3.4.0",
                apkSizeBytes = apk.length(),
                versionCode = 30400L
            )
        }

        val result = ApkFileSourceValidator.validateAnySource(apksFile, cacheDir, inspector)
        assertTrue("Harusnya Valid, dapat: $result", result is ApkFileValidationResult.Valid)
        val valid = result as ApkFileValidationResult.Valid
        assertEquals("APKS", valid.sourceOrigin)
        assertEquals("com.example.shop", valid.app.packageName)
        assertEquals("Shop App", valid.app.label)
        assertEquals("3.4.0", valid.app.versionName)
        assertEquals(30400L, valid.app.versionCode)
        assertTrue(valid.app.isSplitApk)
        assertEquals(2, valid.app.splitFiles.size)
        assertEquals(
            setOf("config.arm64_v8a", "config.id"),
            valid.app.splitFiles.map { it.splitName }.toSet()
        )
        // Pastikan inspector HANYA dipanggil sekali dan HANYA pada base APK, tidak pernah pada split APK
        assertEquals(1, inspectedFiles.size)
        assertEquals(valid.app.sourceDir, inspectedFiles.single().absolutePath)
    }

    @Test
    fun apksWithoutBaseRejectedWithBundleNoBaseApk() {
        val work = tempFolder.newFolder("apks_nobase")
        val cacheDir = tempFolder.newFolder("cache_apks_nobase")
        val split1 = File(work, "s1.apk").also {
            buildSplitApk(it, "com.example.nobase", "config.xxhdpi", includeArsc = false)
        }
        val split2 = File(work, "s2.apk").also {
            buildSplitApk(it, "com.example.nobase", "config.arm64_v8a", includeArsc = false)
        }
        val apksFile = File(work, "nobase.apks")
        writeZipBundle(
            apksFile,
            mapOf(
                "toc.pb" to byteArrayOf(0x01),
                "splits/s1.apk" to split1.readBytes(),
                "splits/s2.apk" to split2.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(apksFile, cacheDir) { null }
        assertTrue(result is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_NO_BASE_APK, (result as ApkFileValidationResult.Invalid).error)
    }

    @Test
    fun apksWithMismatchedPackageRejectedWithBundlePackageMismatch() {
        val work = tempFolder.newFolder("apks_mismatch")
        val cacheDir = tempFolder.newFolder("cache_apks_mismatch")
        val baseApk = File(work, "base.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.alpha")
        }
        val foreignSplit = File(work, "split_other.apk").also {
            buildSplitApk(it, "com.example.beta", "config.xxhdpi", includeArsc = false)
        }
        val apksFile = File(work, "mismatch.apks")
        writeZipBundle(
            apksFile,
            mapOf(
                "base.apk" to baseApk.readBytes(),
                "split_other.apk" to foreignSplit.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(apksFile, cacheDir) { null }
        assertTrue(result is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_PACKAGE_MISMATCH, (result as ApkFileValidationResult.Invalid).error)
    }

    @Test
    fun corruptNonZipAndApkWithoutManifestRejectedWithBundleInvalid() {
        val work = tempFolder.newFolder("corrupt_bundle")
        val cacheDir = tempFolder.newFolder("cache_corrupt")

        // 1. Berkas acak bukan ZIP
        val notZip = File(work, "broken.apks").apply { writeText("bukan arsip zip sama sekali") }
        val res1 = ApkFileSourceValidator.validateAnySource(notZip, cacheDir) { null }
        assertTrue(res1 is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_INVALID, (res1 as ApkFileValidationResult.Invalid).error)

        // 2. ZIP berisi .apk palsu tanpa AndroidManifest.xml
        val fakeApk = File(work, "fake.apk")
        writeZipBundle(fakeApk, mapOf("assets/hello.txt" to "hi".toByteArray()))
        val badBundle = File(work, "bad_inner.apks")
        writeZipBundle(badBundle, mapOf("base.apk" to fakeApk.readBytes()))

        val res2 = ApkFileSourceValidator.validateAnySource(badBundle, cacheDir) { null }
        assertTrue(res2 is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_INVALID, (res2 as ApkFileValidationResult.Invalid).error)
    }

    @Test
    fun xapkWithValidManifestJsonPopulatesMetadata() {
        val work = tempFolder.newFolder("xapk_valid")
        val cacheDir = tempFolder.newFolder("cache_xapk_valid")
        val baseApk = File(work, "com.example.reader.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.reader")
        }
        val splitApk = File(work, "config.xxhdpi.apk").also {
            buildSplitApk(it, "com.example.reader", "config.xxhdpi", includeArsc = false)
        }
        val manifestJson = """
            {
              "xapk_version": 2,
              "package_name": "com.example.reader",
              "name": "Reader Pro",
              "version_code": "205",
              "version_name": "2.5.0",
              "split_apks": [
                {"file": "com.example.reader.apk", "id": "base"},
                {"file": "config.xxhdpi.apk", "id": "config.xxhdpi"}
              ]
            }
        """.trimIndent()
        val xapkFile = File(work, "reader.xapk")
        writeZipBundle(
            xapkFile,
            mapOf(
                "manifest.json" to manifestJson.toByteArray(Charsets.UTF_8),
                "com.example.reader.apk" to baseApk.readBytes(),
                "config.xxhdpi.apk" to splitApk.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(xapkFile, cacheDir) { null }
        assertTrue("Harusnya Valid, dapat: $result", result is ApkFileValidationResult.Valid)
        val valid = result as ApkFileValidationResult.Valid
        assertEquals("XAPK", valid.sourceOrigin)
        assertEquals("com.example.reader", valid.app.packageName)
        assertEquals("Reader Pro", valid.app.label)
        assertEquals("2.5.0", valid.app.versionName)
        assertEquals(205L, valid.app.versionCode)
        assertTrue(valid.app.isSplitApk)
        assertEquals(1, valid.app.splitFiles.size)
        assertEquals("config.xxhdpi", valid.app.splitFiles.first().splitName)
    }

    @Test
    fun xapkWithoutManifestJsonFallsBackToZipScan() {
        val work = tempFolder.newFolder("xapk_nomf")
        val cacheDir = tempFolder.newFolder("cache_xapk_nomf")
        val baseApk = File(work, "base.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.nomf")
        }
        val splitApk = File(work, "split_id.apk").also {
            buildSplitApk(it, "com.example.nomf", "config.id", includeArsc = false)
        }
        val xapkFile = File(work, "nomf.xapk")
        writeZipBundle(
            xapkFile,
            mapOf(
                "base.apk" to baseApk.readBytes(),
                "split_id.apk" to splitApk.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(xapkFile, cacheDir) { apk ->
            ApkArchiveMetadata("com.example.nomf", "NoMf App", "1.0", apk.length(), 10L)
        }
        assertTrue("Harusnya fallback scan ZIP dan Valid, dapat: $result", result is ApkFileValidationResult.Valid)
        val valid = result as ApkFileValidationResult.Valid
        assertEquals("com.example.nomf", valid.app.packageName)
        assertEquals(1, valid.app.splitFiles.size)
    }

    @Test
    fun xapkWithMissingDeclaredApkRejectedWithBundleInvalid() {
        val work = tempFolder.newFolder("xapk_missing")
        val cacheDir = tempFolder.newFolder("cache_xapk_missing")
        val baseApk = File(work, "base.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.miss")
        }
        val manifestJson = """
            {
              "package_name": "com.example.miss",
              "name": "Miss App",
              "split_apks": [
                {"file": "base.apk", "id": "base"},
                {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"}
              ]
            }
        """.trimIndent()
        val xapkFile = File(work, "missing.xapk")
        writeZipBundle(
            xapkFile,
            mapOf(
                "manifest.json" to manifestJson.toByteArray(Charsets.UTF_8),
                "base.apk" to baseApk.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(xapkFile, cacheDir) { null }
        assertTrue(result is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_INVALID, (result as ApkFileValidationResult.Invalid).error)
    }

    @Test
    fun xapkWithManifestPackageMismatchRejectedWithBundlePackageMismatch() {
        val work = tempFolder.newFolder("xapk_pkg_mismatch")
        val cacheDir = tempFolder.newFolder("cache_xapk_pkg_mismatch")
        val baseApk = File(work, "base.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.actual")
        }
        val manifestJson = """
            {
              "package_name": "com.example.declared",
              "name": "Mismatch App",
              "split_apks": [{"file": "base.apk", "id": "base"}]
            }
        """.trimIndent()
        val xapkFile = File(work, "mismatch.xapk")
        writeZipBundle(
            xapkFile,
            mapOf(
                "manifest.json" to manifestJson.toByteArray(Charsets.UTF_8),
                "base.apk" to baseApk.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(xapkFile, cacheDir) { null }
        assertTrue(result is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_PACKAGE_MISMATCH, (result as ApkFileValidationResult.Invalid).error)
    }

    @Test
    fun bundleWithObbRejectedWithBundleHasObb() {
        val work = tempFolder.newFolder("xapk_obb")
        val cacheDir = tempFolder.newFolder("cache_xapk_obb")
        val baseApk = File(work, "game.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.game")
        }
        val xapkFile = File(work, "game.xapk")
        writeZipBundle(
            xapkFile,
            mapOf(
                "game.apk" to baseApk.readBytes(),
                "Android/obb/com.example.game/main.100.com.example.game.obb" to byteArrayOf(1, 2, 3)
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(xapkFile, cacheDir) { null }
        assertTrue(result is ApkFileValidationResult.Invalid)
        assertEquals(CloneError.BUNDLE_HAS_OBB, (result as ApkFileValidationResult.Invalid).error)
    }

    @Test
    fun singleApkInsideApksBundleProducesNonSplitApp() {
        val work = tempFolder.newFolder("apks_single")
        val cacheDir = tempFolder.newFolder("cache_apks_single")
        val baseApk = File(work, "base.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.solo")
        }
        val apksFile = File(work, "solo.apks")
        writeZipBundle(
            apksFile,
            mapOf(
                "toc.pb" to byteArrayOf(0x01),
                "base.apk" to baseApk.readBytes()
            )
        )

        val result = ApkFileSourceValidator.validateAnySource(apksFile, cacheDir) { apk ->
            ApkArchiveMetadata("com.example.solo", "Solo App", "1.0", apk.length(), 1L)
        }
        assertTrue(result is ApkFileValidationResult.Valid)
        val valid = result as ApkFileValidationResult.Valid
        assertFalse(valid.app.isSplitApk)
        assertTrue(valid.app.splitFiles.isEmpty())
        assertEquals("APKS", valid.sourceOrigin)
    }

    @Test
    fun endToEndValidatorAndClonePipelineFromApksBundle() = runBlocking {
        val work = tempFolder.newFolder("e2e_bundle")
        val cacheDir = tempFolder.newFolder("e2e_cache")
        val filesDir = tempFolder.newFolder("e2e_files")

        val baseApk = File(work, "base-master.apk").also {
            BinaryFixtureBuilder.buildFixtureApk(it, "com.example.bundleapp")
        }
        val splitApk = File(work, "split-xxhdpi.apk").also {
            buildSplitApk(it, "com.example.bundleapp", "config.xxhdpi", includeArsc = true)
        }
        val apksFile = File(work, "bundleapp.apks")
        writeZipBundle(
            apksFile,
            mapOf(
                "toc.pb" to byteArrayOf(0x0A),
                "base-master.apk" to baseApk.readBytes(),
                "split-xxhdpi.apk" to splitApk.readBytes()
            )
        )

        val validation = ApkFileSourceValidator.validateAnySource(apksFile, cacheDir) { apk ->
            ApkArchiveMetadata("com.example.bundleapp", "Bundle App", "4.1.0", apk.length(), 410L)
        }
        assertTrue(validation is ApkFileValidationResult.Valid)
        val app = (validation as ApkFileValidationResult.Valid).app

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = ArchivePackageReader { apkFile ->
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val entry = zip.getEntry("AndroidManifest.xml") ?: return@ArchivePackageReader null
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    AxmlPatcher.readPackageName(bytes)
                }
            },
            isPackageInstalled = { false }
        )

        val record = pipeline.execute(
            CloneRequest(
                sourceApkPath = app.sourceDir,
                isSplitApk = app.isSplitApk,
                originalAppName = app.label,
                originalPackageName = app.packageName,
                cloneName = "Bundle App 2",
                clonePackageName = "com.example.bundleapp.clone",
                splitApkPaths = app.splitFiles,
                sourceVersionCode = app.versionCode,
                sourceVersionName = app.versionName.orEmpty(),
                sourceOrigin = validation.sourceOrigin
            )
        )

        assertTrue(record.isSplitClone)
        assertEquals(2, record.totalFileCount)
        assertEquals("APKS", record.sourceOrigin)
        assertEquals(410L, record.sourceVersionCode)
        assertEquals("4.1.0", record.sourceVersionName)
        assertTrue(record.allApkFiles.all { it.exists() && it.length() > 0L })

        val loaded = ClonedApksRepository(filesDir).loadAll()
        assertEquals(1, loaded.size)
        assertEquals("APKS", loaded.first().sourceOrigin)
    }

    @Test
    fun installProgressTrackerSplitAndSingleApkTransitions() {
        assertEquals(InstallProgressState.Idle, InstallProgressTracker.state.value)

        // Alur Split APK: start -> pending user action -> succeeded -> consume -> Idle
        InstallProgressTracker.onSplitInstallStarted("com.a.clone", "App A 2")
        val s1 = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallProgressTracker.MSG_INSTALLING, s1.message)
        assertFalse(s1.isSingleApkIntent)

        InstallProgressTracker.onSplitWaitingUserConfirmation("com.a.clone", "App A 2")
        val s2 = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallProgressTracker.MSG_WAITING_CONFIRM, s2.message)

        // onActivityResumed tidak boleh menghapus status split APK yang masih menunggu sesi
        InstallProgressTracker.onActivityResumed()
        assertTrue(InstallProgressTracker.state.value is InstallProgressState.Installing)

        InstallProgressTracker.onSplitInstallSucceeded("com.a.clone", "App A 2", "Pemasangan berhasil.")
        assertTrue(InstallProgressTracker.state.value is InstallProgressState.Succeeded)

        InstallProgressTracker.consumeTerminalState()
        assertEquals(InstallProgressState.Idle, InstallProgressTracker.state.value)

        // Alur gagal: start -> failed -> consume -> Idle
        InstallProgressTracker.onSplitInstallStarted("com.a.clone", "App A 2")
        InstallProgressTracker.onSplitInstallFailed("com.a.clone", "App A 2", "Pemasangan dibatalkan.")
        assertTrue(InstallProgressTracker.state.value is InstallProgressState.Failed)
        InstallProgressTracker.consumeTerminalState()
        assertEquals(InstallProgressState.Idle, InstallProgressTracker.state.value)

        // Alur APK tunggal: launched -> onActivityResumed -> Idle
        InstallProgressTracker.onSingleApkInstallLaunched("com.b.clone", "App B 2")
        val single = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertTrue(single.isSingleApkIntent)
        assertEquals(InstallProgressTracker.MSG_SINGLE_APK_INTENT, single.message)

        InstallProgressTracker.onActivityResumed()
        assertEquals(InstallProgressState.Idle, InstallProgressTracker.state.value)
    }

    @Test
    fun clonedApkRecordJsonPreservesSourceOriginAndBackwardCompatible() {
        val record = ClonedApkRecord(
            id = "rec-1",
            originalAppName = "Demo",
            originalPackageName = "com.example.demo",
            cloneName = "Demo 2",
            clonePackageName = "com.example.demo.clone",
            apkPath = "/tmp/demo.apk",
            apkSizeBytes = 1024L,
            createdAtMillis = 123456L,
            sourceOrigin = "XAPK"
        )
        val parsed = ClonedApkRecord.fromJsonString(record.toJsonString())
        assertEquals("XAPK", parsed!!.sourceOrigin)

        // JSON lama (Fase 11) tanpa field sourceOrigin harus terbaca dengan default ""
        val legacyJson = """{"id":"old-1","originalAppName":"Old","originalPackageName":"com.old","cloneName":"Old 2","clonePackageName":"com.old.clone","apkPath":"/tmp/old.apk","apkSizeBytes":512,"createdAtMillis":100}"""
        val parsedLegacy = ClonedApkRecord.fromJsonString(legacyJson)
        assertEquals("", parsedLegacy!!.sourceOrigin)
    }

    // --- Helper fixture pembuat Split APK & arsip ZIP bundle ---

    private fun buildSplitApk(
        outputFile: File,
        packageName: String,
        splitName: String,
        includeArsc: Boolean
    ) {
        val manifest = buildSplitManifestAxml(packageName, splitName)
        AlignedZipWriter(outputFile).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", manifest)
            if (includeArsc) {
                w.writeStoredEntry(
                    "resources.arsc",
                    BinaryFixtureBuilder.buildArsc(packageName),
                    AlignedZipWriter.ALIGN_DEFAULT
                )
            }
            w.writeStoredEntry("lib/arm64-v8a/libsplit.so", ByteArray(64) { 7 }, AlignedZipWriter.ALIGN_SO)
        }
    }

    private fun buildSplitManifestAxml(packageName: String, splitName: String): ByteArray {
        val strings = mutableListOf(
            "http://schemas.android.com/apk/res/android", // 0
            "manifest",                                   // 1
            "package",                                    // 2
            packageName,                                  // 3
            "split",                                      // 4
            splitName                                     // 5
        )
        val poolBytes = AxmlStringPool(
            strings = strings,
            isUtf8 = true,
            originalFlags = AxmlStringPool.UTF8_FLAG,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        ).toByteArray()

        val body = ByteArrayOutputStream()
        // <manifest package="..." split="...">
        val attrCount = 2
        val startChunkSize = 16 + 20 + attrCount * 20
        val startBuf = ByteBuffer.allocate(startChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        startBuf.putShort(AxmlPatcher.RES_XML_START_ELEMENT_TYPE.toShort())
        startBuf.putShort(16)
        startBuf.putInt(startChunkSize)
        startBuf.putInt(1)
        startBuf.putInt(-1)
        startBuf.putInt(-1) // ns
        startBuf.putInt(1)  // "manifest"
        startBuf.putShort(20)
        startBuf.putShort(20)
        startBuf.putShort(attrCount.toShort())
        startBuf.putShort(0)
        startBuf.putShort(0)
        startBuf.putShort(0)
        // attr 0: package
        startBuf.putInt(-1)
        startBuf.putInt(2)
        startBuf.putInt(3)
        startBuf.putShort(8)
        startBuf.put(0)
        startBuf.put(0x03)
        startBuf.putInt(3)
        // attr 1: split
        startBuf.putInt(-1)
        startBuf.putInt(4)
        startBuf.putInt(5)
        startBuf.putShort(8)
        startBuf.put(0)
        startBuf.put(0x03)
        startBuf.putInt(5)
        body.write(startBuf.array())

        // </manifest>
        val endBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        endBuf.putShort(0x0103.toShort())
        endBuf.putShort(16)
        endBuf.putInt(24)
        endBuf.putInt(1)
        endBuf.putInt(-1)
        endBuf.putInt(-1)
        endBuf.putInt(1)
        body.write(endBuf.array())

        val bodyBytes = body.toByteArray()
        val totalSize = 8 + poolBytes.size + bodyBytes.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(AxmlPatcher.RES_XML_TYPE.toShort())
        out.putShort(8)
        out.putInt(totalSize)
        out.put(poolBytes)
        out.put(bodyBytes)
        return out.array()
    }

    private fun writeZipBundle(outputFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}
