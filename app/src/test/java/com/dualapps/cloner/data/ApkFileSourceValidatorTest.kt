package com.dualapps.cloner.data

import com.dualapps.cloner.engine.AxmlPatcher
import com.dualapps.cloner.engine.BinaryFixtureBuilder
import com.dualapps.cloner.engine.ClonePipeline
import com.dualapps.cloner.engine.CloneRequest
import com.dualapps.cloner.engine.DeviceKeystoreManager
import com.dualapps.cloner.engine.PackageNameValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkFileSourceValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validatesValidApkFileCopiedToCacheAndProducesInstalledAppModel() {
        val cacheDir = tempFolder.newFolder("cacheValid")
        val rawApk = tempFolder.newFile("external-sample.apk")
        val pkg = "com.example.external"
        BinaryFixtureBuilder.buildFixtureApk(rawApk, pkg)

        val copied = ApkFileSourceValidator.copyToCache(rawApk.inputStream(), cacheDir)
        assertTrue(copied.exists())
        assertTrue(copied.parentFile?.name == ApkFileSourceValidator.PICKED_APKS_DIR_NAME)

        val result = ApkFileSourceValidator.validate(copied) { apk ->
            ZipFile(apk).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                val readPkg = AxmlPatcher.readPackageName(manifest) ?: return@validate null
                ApkArchiveMetadata(
                    packageName = readPkg,
                    label = "External App",
                    versionName = "1.2.3",
                    apkSizeBytes = apk.length()
                )
            }
        }

        assertTrue(result is ApkFileValidationResult.Valid)
        val app = (result as ApkFileValidationResult.Valid).app
        assertEquals(pkg, app.packageName)
        assertEquals("External App", app.label)
        assertEquals("1.2.3", app.versionName)
        assertEquals(copied.absolutePath, app.sourceDir)
        assertEquals(rawApk.length(), app.apkSizeBytes)
        assertFalse(app.isSplitApk)
        assertFalse(app.isSystemApp)
    }

    @Test
    fun rejectsCorruptOrNonApkFilesAndDeletesTempCopy() {
        val cacheDir = tempFolder.newFolder("cacheInvalid")

        // Kasus 1: Berkas teks acak bukan ZIP/APK
        val notAZip = ApkFileSourceValidator.copyToCache(
            ByteArrayInputStream("bukan berkas apk".toByteArray()),
            cacheDir
        )
        val res1 = ApkFileSourceValidator.validate(notAZip) {
            ApkArchiveMetadata("com.fake", "Fake", "1.0", 10L)
        }
        assertTrue(res1 is ApkFileValidationResult.Invalid)
        assertTrue((res1 as ApkFileValidationResult.Invalid).message.isNotBlank())
        assertFalse("Berkas sementara korup harus dihapus", notAZip.exists())

        // Kasus 2: ZIP valid tetapi tidak punya AndroidManifest.xml
        val zipNoManifest = File(cacheDir, "no_manifest.apk")
        ZipOutputStream(zipNoManifest.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val res2 = ApkFileSourceValidator.validate(zipNoManifest) {
            ApkArchiveMetadata("com.fake", "Fake", "1.0", 10L)
        }
        assertTrue(res2 is ApkFileValidationResult.Invalid)
        assertFalse(zipNoManifest.exists())

        // Kasus 3: Struktur ZIP punya manifest tetapi PackageManager (inspector) menolak (return null)
        val badManifestApk = tempFolder.newFile("bad-manifest.apk")
        BinaryFixtureBuilder.buildFixtureApk(badManifestApk, "com.example.bad")
        val copiedBad = ApkFileSourceValidator.copyToCache(badManifestApk.inputStream(), cacheDir)
        val res3 = ApkFileSourceValidator.validate(copiedBad) { null }
        assertTrue(res3 is ApkFileValidationResult.Invalid)
        assertFalse(copiedBad.exists())
    }

    @Test
    fun configuresAndClonesFromExternalApkFileIntoResultsRepository() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheFlow")
        val filesDir = tempFolder.newFolder("filesFlow")
        val externalApk = tempFolder.newFile("downloaded.apk")
        val origPkg = "com.example.downloaded"
        BinaryFixtureBuilder.buildFixtureApk(externalApk, origPkg)

        // 1. Salin & validasi file APK sumber
        val tempPicked = ApkFileSourceValidator.copyToCache(externalApk.inputStream(), cacheDir)
        val validated = ApkFileSourceValidator.validate(tempPicked) { apk ->
            ApkArchiveMetadata(
                packageName = origPkg,
                label = "Dualapps",
                versionName = "2.0",
                apkSizeBytes = apk.length()
            )
        } as ApkFileValidationResult.Valid

        // 2. Simulasi Atur Clone dari sumber file: saran package otomatis melewati yang sudah terpasang
        val alreadyInstalled = setOf("$origPkg.clone")
        val suggestedPkg = PackageNameValidator.suggestClonePackage(validated.app.packageName) {
            it in alreadyInstalled
        }
        assertEquals("$origPkg.clone2", suggestedPkg)
        val defaultCloneName = "${validated.app.label} 2"
        assertEquals("Dualapps 2", defaultCloneName)

        // 3. Jalankan ClonePipeline dari berkas sementara di cacheDir/picked_apks/
        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { apkFile ->
                ZipFile(apkFile).use { zip ->
                    AxmlPatcher.readPackageName(
                        zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                    )
                }
            },
            isPackageInstalled = { it in alreadyInstalled }
        )

        val record = pipeline.execute(
            CloneRequest(
                sourceApkPath = validated.app.sourceDir,
                isSplitApk = validated.app.isSplitApk,
                originalAppName = validated.app.label,
                originalPackageName = validated.app.packageName,
                cloneName = defaultCloneName,
                clonePackageName = suggestedPkg
            )
        )

        assertEquals(suggestedPkg, record.clonePackageName)
        assertEquals(defaultCloneName, record.cloneName)
        assertTrue(record.apkFile.exists())

        // 4. Pastikan hasil masuk ke Daftar Hasil (ClonedApksRepository)
        val resultsRepo = ClonedApksRepository(filesDir)
        val allResults = resultsRepo.loadAll()
        assertEquals(1, allResults.size)
        assertEquals(suggestedPkg, allResults[0].clonePackageName)
    }
}
