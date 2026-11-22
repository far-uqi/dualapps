package com.dualapps.cloner.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class AlignedZipWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun maintainsStoredAlignmentsForArscSoAndOtherStoredEntries() {
        val outZip = tempFolder.newFile("aligned-test.apk")
        val arscData = ByteArray(100) { it.toByte() }
        val soData = ByteArray(256) { (it * 3).toByte() }
        val rawData = "hello-stored-entry".toByteArray()
        val deflatedData = "deflated-content-".repeat(20).toByteArray()

        AlignedZipWriter(outZip).use { writer ->
            writer.writeDeflatedEntry("a.txt", deflatedData)
            writer.writeStoredEntry("resources.arsc", arscData)
            writer.writeStoredEntry("res/raw/unaligned_name_123.bin", rawData)
            writer.writeStoredEntry("lib/arm64-v8a/libsample.so", soData)
        }

        // Verifikasi offset data aktual langsung dari Local File Header
        val offsets = AlignedZipWriter.readEntryDataOffsets(outZip)

        val (arscMethod, arscOffset) = offsets["resources.arsc"]!!
        assertEquals(ZipEntry.STORED, arscMethod)
        assertEquals("resources.arsc harus align 4 byte", 0L, arscOffset % 4L)

        val (rawMethod, rawOffset) = offsets["res/raw/unaligned_name_123.bin"]!!
        assertEquals(ZipEntry.STORED, rawMethod)
        assertEquals("Entri STORED lain harus align 4 byte", 0L, rawOffset % 4L)

        val (soMethod, soOffset) = offsets["lib/arm64-v8a/libsample.so"]!!
        assertEquals(ZipEntry.STORED, soMethod)
        assertEquals("Berkas .so STORED harus align 4096 byte", 0L, soOffset % 4096L)

        // Verifikasi bisa dibuka oleh java.util.zip.ZipFile standar dengan isi utuh
        ZipFile(outZip).use { zip ->
            assertArrayEquals(arscData, zip.getInputStream(zip.getEntry("resources.arsc")).readBytes())
            assertArrayEquals(soData, zip.getInputStream(zip.getEntry("lib/arm64-v8a/libsample.so")).readBytes())
            assertArrayEquals(rawData, zip.getInputStream(zip.getEntry("res/raw/unaligned_name_123.bin")).readBytes())
            assertArrayEquals(deflatedData, zip.getInputStream(zip.getEntry("a.txt")).readBytes())
        }
    }

    @Test
    fun apkRebuilderPatchesManifestAndArscStripsOldSignaturesKeepsDexAndAlignments() {
        val oldPkg = "com.dualapps.cloner"
        val newPkg = "com.dualapps.cloner.clone"
        val originalDex = "dex\n035\u0000UNCHANGED_DEX_BYTES_987654321".toByteArray()

        val sourceApk = tempFolder.newFile("source.apk")
        val rebuiltApk = tempFolder.newFile("rebuilt-unsigned.apk")
        BinaryFixtureBuilder.buildFixtureApk(
            outputFile = sourceApk,
            packageName = oldPkg,
            useUtf8Manifest = true,
            dexPayload = originalDex
        )

        ApkRebuilder.rebuild(
            sourceApk = sourceApk,
            outputUnsignedApk = rebuiltApk,
            oldPackage = oldPkg,
            newPackage = newPkg
        )

        val offsets = AlignedZipWriter.readEntryDataOffsets(rebuiltApk)
        assertEquals(0L, offsets["resources.arsc"]!!.second % 4L)
        assertEquals(0L, offsets["lib/arm64-v8a/libnative.so"]!!.second % 4096L)
        assertEquals(0L, offsets["res/raw/data.bin"]!!.second % 4L)

        ZipFile(rebuiltApk).use { zip ->
            // Tanda tangan lama META-INF wajib hilang
            assertTrue(zip.getEntry("META-INF/MANIFEST.MF") == null)
            assertTrue(zip.getEntry("META-INF/CERT.SF") == null)
            assertTrue(zip.getEntry("META-INF/CERT.RSA") == null)
            assertTrue(zip.getEntry("META-INF/CERT.DSA") == null)
            assertTrue(zip.getEntry("META-INF/CERT.EC") == null)
            // Non-signature di subfolder META-INF tetap dipertahankan
            assertNotNull(zip.getEntry("META-INF/services/example.Service"))

            // DEX disalin utuh byte-per-byte
            val rebuiltDex = zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
            assertArrayEquals(originalDex, rebuiltDex)

            // Manifest terpatch dan terparse ulang dengan package baru
            val manifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(newPkg, AxmlPatcher.readPackageName(manifestBytes))

            // resources.arsc terpatch dengan package baru & tetap STORED
            val arscEntry = zip.getEntry("resources.arsc")
            assertEquals(ZipEntry.STORED, arscEntry.method)
            val arscBytes = zip.getInputStream(arscEntry).readBytes()
            assertEquals(listOf(newPkg), ArscPatcher.readPackageNames(arscBytes))
        }
    }

    @Test
    fun detectsSignatureEntriesAccurately() {
        assertTrue(AlignedZipWriter.isSignatureEntry("META-INF/MANIFEST.MF"))
        assertTrue(AlignedZipWriter.isSignatureEntry("META-INF/CERT.SF"))
        assertTrue(AlignedZipWriter.isSignatureEntry("META-INF/DUALKEY.RSA"))
        assertTrue(AlignedZipWriter.isSignatureEntry("META-INF/KEY.DSA"))
        assertTrue(AlignedZipWriter.isSignatureEntry("META-INF/KEY.EC"))
        assertFalse(AlignedZipWriter.isSignatureEntry("META-INF/services/foo.Bar"))
        assertFalse(AlignedZipWriter.isSignatureEntry("META-INF/com/android/build/gradle/app-metadata.properties"))
        assertFalse(AlignedZipWriter.isSignatureEntry("classes.dex"))
    }
}
