package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.util.IconProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class CloneIdentityPatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleDensityPngs(): Map<String, ByteArray> = linkedMapOf(
        "mdpi" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 48),
        "hdpi" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 72),
        "xhdpi" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 96),
        "xxhdpi" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 144.toByte()),
        "xxxhdpi" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 192.toByte())
    )

    @Test
    fun replacesLegacyMipmapLauncherPngsPerDensity() {
        val inputApk = tempFolder.newFile("legacy-in.apk")
        val outputApk = tempFolder.newFile("legacy-out.apk")
        val pkg = "com.example.legacy"
        val origIcon = "ORIGINAL_ICON".toByteArray()

        AlignedZipWriter(inputApk).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", BinaryFixtureBuilder.buildManifestAxml(pkg, true))
            w.writeStoredEntry("resources.arsc", BinaryFixtureBuilder.buildArsc(pkg), AlignedZipWriter.ALIGN_DEFAULT)
            w.writeStoredEntry("res/mipmap-mdpi/ic_launcher.png", origIcon)
            w.writeStoredEntry("res/mipmap-hdpi/ic_launcher.png", origIcon)
            w.writeStoredEntry("res/mipmap-xhdpi/ic_launcher_round.png", origIcon)
            w.writeDeflatedEntry("res/mipmap-xxhdpi/ic_launcher.png", origIcon)
            w.writeDeflatedEntry("res/mipmap-xxxhdpi/ic_launcher.png", origIcon)
        }

        val customPngs = sampleDensityPngs()
        val result = CloneIdentityPatcher.applyIdentity(
            inputApk = inputApk,
            outputApk = outputApk,
            newLabel = null,
            customIconPngs = customPngs
        )

        assertFalse(result.labelPatched)
        assertEquals(5, result.replacedIconEntries.size)

        ZipFile(outputApk).use { zip ->
            assertArrayEquals(customPngs["mdpi"], zip.getInputStream(zip.getEntry("res/mipmap-mdpi/ic_launcher.png")).readBytes())
            assertArrayEquals(customPngs["hdpi"], zip.getInputStream(zip.getEntry("res/mipmap-hdpi/ic_launcher.png")).readBytes())
            assertArrayEquals(customPngs["xhdpi"], zip.getInputStream(zip.getEntry("res/mipmap-xhdpi/ic_launcher_round.png")).readBytes())
            assertArrayEquals(customPngs["xxhdpi"], zip.getInputStream(zip.getEntry("res/mipmap-xxhdpi/ic_launcher.png")).readBytes())
            assertArrayEquals(customPngs["xxxhdpi"], zip.getInputStream(zip.getEntry("res/mipmap-xxxhdpi/ic_launcher.png")).readBytes())
        }
    }

    @Test
    fun replacesAdaptiveIconForegroundWhilePreservingBackgroundAndXml() {
        val inputApk = tempFolder.newFile("adaptive-in.apk")
        val outputApk = tempFolder.newFile("adaptive-out.apk")
        val pkg = "com.example.adaptive"
        val iconXmlResId = 0x7F040000
        val fgResId = 0x7F040001
        val bgResId = 0x7F040002

        val origFg = "ORIGINAL_FOREGROUND".toByteArray()
        val origBg = "ORIGINAL_BACKGROUND_MUST_STAY".toByteArray()
        val adaptiveXml = BinaryFixtureBuilder.buildAdaptiveIconAxml(
            foregroundResId = fgResId,
            backgroundResId = bgResId
        )
        val arsc = BinaryFixtureBuilder.buildArscWithTypeChunks(
            packageName = pkg,
            useUtf8 = true,
            typeChunks = listOf(
                0x04 to listOf(
                    0x0000 to "res/mipmap-anydpi-v26/ic_launcher.xml",
                    0x0001 to "res/drawable-xxhdpi/custom_fg_layer.png",
                    0x0002 to "res/mipmap-xxhdpi/ic_launcher_background.png"
                )
            )
        )

        AlignedZipWriter(inputApk).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                BinaryFixtureBuilder.buildManifestAxml(pkg, useUtf8 = true, iconResId = iconXmlResId)
            )
            w.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("res/mipmap-anydpi-v26/ic_launcher.xml", adaptiveXml)
            w.writeDeflatedEntry("res/drawable-xxhdpi/custom_fg_layer.png", origFg)
            w.writeDeflatedEntry("res/mipmap-xhdpi/ic_launcher_foreground.png", origFg)
            w.writeDeflatedEntry("res/mipmap-xxhdpi/ic_launcher_background.png", origBg)
        }

        val customPngs = sampleDensityPngs()
        val result = CloneIdentityPatcher.applyIdentity(
            inputApk = inputApk,
            outputApk = outputApk,
            newLabel = null,
            customIconPngs = customPngs
        )

        assertTrue(result.replacedIconEntries.contains("res/drawable-xxhdpi/custom_fg_layer.png"))
        assertTrue(result.replacedIconEntries.contains("res/mipmap-xhdpi/ic_launcher_foreground.png"))
        assertFalse(result.replacedIconEntries.contains("res/mipmap-xxhdpi/ic_launcher_background.png"))

        ZipFile(outputApk).use { zip ->
            assertArrayEquals(
                customPngs["xxhdpi"],
                zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/custom_fg_layer.png")).readBytes()
            )
            assertArrayEquals(
                customPngs["xhdpi"],
                zip.getInputStream(zip.getEntry("res/mipmap-xhdpi/ic_launcher_foreground.png")).readBytes()
            )
            // Background dan XML adaptive-icon asli harus tetap utuh
            assertArrayEquals(
                origBg,
                zip.getInputStream(zip.getEntry("res/mipmap-xxhdpi/ic_launcher_background.png")).readBytes()
            )
            assertArrayEquals(
                adaptiveXml,
                zip.getInputStream(zip.getEntry("res/mipmap-anydpi-v26/ic_launcher.xml")).readBytes()
            )
        }
    }

    @Test
    fun patchesLiteralApplicationLabelInAxml() {
        val inputApk = tempFolder.newFile("literal-in.apk")
        val outputApk = tempFolder.newFile("literal-out.apk")
        val pkg = "com.example.lit"

        AlignedZipWriter(inputApk).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                BinaryFixtureBuilder.buildManifestAxml(pkg, useUtf8 = true, labelLiteral = "App Asli")
            )
            w.writeStoredEntry("resources.arsc", BinaryFixtureBuilder.buildArsc(pkg), AlignedZipWriter.ALIGN_DEFAULT)
        }

        val result = CloneIdentityPatcher.applyIdentity(
            inputApk = inputApk,
            outputApk = outputApk,
            newLabel = "App Clone Keren",
            customIconPngs = null
        )

        assertTrue(result.labelPatched)
        ZipFile(outputApk).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(
                AxmlPatcher.LabelTarget.Literal("App Clone Keren"),
                AxmlPatcher.readApplicationLabel(manifest)
            )
            assertEquals(pkg, AxmlPatcher.readPackageName(manifest))
        }
    }

    @Test
    fun patchesStringReferenceLabelInUtf8AndUtf16Arsc() {
        for (useUtf8 in listOf(true, false)) {
            val tag = if (useUtf8) "utf8" else "utf16"
            val inputApk = tempFolder.newFile("ref-$tag-in.apk")
            val outputApk = tempFolder.newFile("ref-$tag-out.apk")
            val pkg = "com.example.ref$tag"
            val appNameResId = 0x7F050002

            val arsc = BinaryFixtureBuilder.buildArscWithTypeChunks(
                packageName = pkg,
                useUtf8 = useUtf8,
                typeChunks = listOf(
                    0x05 to listOf(
                        0x0000 to "String Lain",
                        0x0002 to "Nama Asli $tag"
                    )
                )
            )
            AlignedZipWriter(inputApk).use { w ->
                w.writeDeflatedEntry(
                    "AndroidManifest.xml",
                    BinaryFixtureBuilder.buildManifestAxml(
                        packageName = pkg,
                        useUtf8 = useUtf8,
                        labelLiteral = null,
                        labelResId = appNameResId
                    )
                )
                w.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
            }

            val newLabel = "Clone Baru $tag — Ω"
            val result = CloneIdentityPatcher.applyIdentity(
                inputApk = inputApk,
                outputApk = outputApk,
                newLabel = newLabel,
                customIconPngs = null
            )

            assertTrue(result.labelPatched)
            ZipFile(outputApk).use { zip ->
                val patchedArsc = zip.getInputStream(zip.getEntry("resources.arsc")).readBytes()
                val pool = AxmlStringPool.parse(patchedArsc, offset = 12)
                assertEquals(useUtf8, pool.isUtf8)
                assertEquals(listOf(newLabel), ArscPatcher.readStringResourceValues(patchedArsc, appNameResId))
                // String lain di ARSC tidak ikut rusak
                assertEquals(listOf("String Lain"), ArscPatcher.readStringResourceValues(patchedArsc, 0x7F050000))
            }
        }
    }

    @Test
    fun failsWithIconPatchFailedAndDoesNotSaveCloneWhenNoIconEntriesFound() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheNoIcon")
        val filesDir = tempFolder.newFolder("filesNoIcon")
        val sourceApk = tempFolder.newFile("source-no-icon.apk")
        // Fixture standar tidak punya res/mipmap-*/ic_launcher*.png
        BinaryFixtureBuilder.buildFixtureApk(sourceApk, "com.example.noicon")

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { "com.example.noicon.clone" },
            isPackageInstalled = { false }
        )

        try {
            pipeline.execute(
                CloneRequest(
                    sourceApkPath = sourceApk.absolutePath,
                    isSplitApk = false,
                    originalAppName = "Dualapps",
                    originalPackageName = "com.example.noicon",
                    cloneName = "Dualapps",
                    clonePackageName = "com.example.noicon.clone",
                    customIconPngs = sampleDensityPngs()
                )
            )
            fail("Seharusnya melempar CloneError.ICON_PATCH_FAILED")
        } catch (e: CloneException) {
            assertEquals(CloneError.ICON_PATCH_FAILED, e.error)
        }

        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun failsWithLabelPatchFailedAndDoesNotSaveCloneWhenLabelMissing() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheNoLabel")
        val filesDir = tempFolder.newFolder("filesNoLabel")
        val sourceApk = tempFolder.newFile("source-no-label.apk")
        val pkg = "com.example.nolabel"

        AlignedZipWriter(sourceApk).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                BinaryFixtureBuilder.buildManifestAxml(pkg, useUtf8 = true, labelLiteral = null, labelResId = null)
            )
            w.writeStoredEntry("resources.arsc", BinaryFixtureBuilder.buildArsc(pkg), AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", "dex\n035\u0000FAKE".toByteArray())
        }

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = { "$pkg.clone" },
            isPackageInstalled = { false }
        )

        try {
            pipeline.execute(
                CloneRequest(
                    sourceApkPath = sourceApk.absolutePath,
                    isSplitApk = false,
                    originalAppName = "Nama Lama",
                    originalPackageName = pkg,
                    cloneName = "Nama Baru",
                    clonePackageName = "$pkg.clone",
                    customIconPngs = null
                )
            )
            fail("Seharusnya melempar CloneError.LABEL_PATCH_FAILED")
        } catch (e: CloneException) {
            assertEquals(CloneError.LABEL_PATCH_FAILED, e.error)
        }

        val clonedDir = File(filesDir, "cloned_apks")
        assertTrue(!clonedDir.exists() || clonedDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun cloneWithoutCustomIconKeepsOriginalIconUntouched() = runBlocking {
        val cacheDir = tempFolder.newFolder("cacheRegIcon")
        val filesDir = tempFolder.newFolder("filesRegIcon")
        val sourceApk = tempFolder.newFile("source-reg-icon.apk")
        val pkg = "com.example.regicon"
        val newPkg = "com.example.regicon.clone"
        val originalIconBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5)

        AlignedZipWriter(sourceApk).use { w ->
            w.writeDeflatedEntry("AndroidManifest.xml", BinaryFixtureBuilder.buildManifestAxml(pkg, true))
            w.writeStoredEntry("resources.arsc", BinaryFixtureBuilder.buildArsc(pkg), AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", "dex\n035\u0000FAKE".toByteArray())
            w.writeStoredEntry("res/mipmap-xxhdpi/ic_launcher.png", originalIconBytes)
        }

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

        val stepsDone = mutableListOf<CloneStep>()
        val record = pipeline.execute(
            request = CloneRequest(
                sourceApkPath = sourceApk.absolutePath,
                isSplitApk = false,
                originalAppName = "Dualapps",
                originalPackageName = pkg,
                cloneName = "Dualapps", // sama dengan asli, tanpa ikon custom -> APPLY_IDENTITY dilewati
                clonePackageName = newPkg,
                customIconPngs = null
            ),
            onStepDone = { stepsDone.add(it) }
        )

        assertFalse(stepsDone.contains(CloneStep.APPLY_IDENTITY))
        ZipFile(record.apkFile).use { zip ->
            val iconInClone = zip.getInputStream(zip.getEntry("res/mipmap-xxhdpi/ic_launcher.png")).readBytes()
            assertArrayEquals(originalIconBytes, iconInClone)
        }
    }

    @Test
    fun iconProcessorMapsResourcePathDensitiesAccurately() {
        assertEquals("mdpi", IconProcessor.densityForResourcePath("res/mipmap-mdpi/ic_launcher.png"))
        assertEquals("hdpi", IconProcessor.densityForResourcePath("res/mipmap-hdpi/ic_launcher.png"))
        assertEquals("xhdpi", IconProcessor.densityForResourcePath("res/mipmap-xhdpi/ic_launcher_foreground.png"))
        assertEquals("xxhdpi", IconProcessor.densityForResourcePath("res/mipmap-xxhdpi/ic_launcher.png"))
        assertEquals("xxxhdpi", IconProcessor.densityForResourcePath("res/mipmap-xxxhdpi/ic_launcher.png"))
        assertEquals(mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192), IconProcessor.DENSITY_SIZES)
    }
}
