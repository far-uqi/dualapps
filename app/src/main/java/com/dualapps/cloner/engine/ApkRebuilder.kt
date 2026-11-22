package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Membangun ulang berkas APK (langkah 2–5 pipeline Bagian 3):
 * - Patch `AndroidManifest.xml` dengan [AxmlPatcher]
 * - Patch `resources.arsc` dengan [ArscPatcher] (disimpan STORED align 4 byte)
 * - Menyalin `classes*.dex` apa adanya tanpa modifikasi
 * - Membuang berkas tanda tangan lama di `META-INF/`
 * - Menjaga metode & alignment entri STORED via [AlignedZipWriter]
 */
object ApkRebuilder {

    fun rebuild(
        sourceApk: File,
        outputUnsignedApk: File,
        oldPackage: String,
        newPackage: String,
        onStep: (stepName: String) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ) {
        if (!sourceApk.exists() || !sourceApk.canRead()) {
            throw CloneException(CloneError.SOURCE_UNREADABLE)
        }

        // Validasi panjang package untuk resources.arsc lebih awal
        if (newPackage.length > ArscPatcher.MAX_PACKAGE_NAME_LENGTH) {
            throw CloneException(CloneError.PACKAGE_TOO_LONG)
        }

        outputUnsignedApk.parentFile?.mkdirs()

        try {
            ZipFile(sourceApk).use { zip ->
                val entries = zip.entries().toList()
                var manifestPatched = false
                var arscPatched = false

                AlignedZipWriter(outputUnsignedApk).use { writer ->
                    for (entry in entries) {
                        checkCancelled()
                        val name = entry.name

                        // Skip direktori kosong atau berkas tanda tangan lama META-INF/
                        if (entry.isDirectory || AlignedZipWriter.isSignatureEntry(name)) {
                            continue
                        }

                        val rawBytes = zip.getInputStream(entry).use { it.readBytes() }

                        when {
                            name == "AndroidManifest.xml" -> {
                                onStep("Patch AndroidManifest.xml")
                                val patchedManifest = AxmlPatcher.patch(rawBytes, oldPackage, newPackage)
                                writer.writeDeflatedEntry(name, patchedManifest)
                                manifestPatched = true
                            }
                            name == "resources.arsc" -> {
                                onStep("Patch resources.arsc")
                                val patchedArsc = ArscPatcher.patch(rawBytes, oldPackage, newPackage)
                                // resources.arsc WAJIB STORED align 4 byte
                                writer.writeStoredEntry(name, patchedArsc, AlignedZipWriter.ALIGN_DEFAULT)
                                arscPatched = true
                            }
                            else -> {
                                // classes*.dex dan seluruh entri lain disalin apa adanya
                                if (entry.method == ZipEntry.STORED) {
                                    val align = AlignedZipWriter.alignmentForStored(name)
                                    writer.writeStoredEntry(name, rawBytes, align)
                                } else {
                                    writer.writeDeflatedEntry(name, rawBytes)
                                }
                            }
                        }
                    }
                }

                if (!manifestPatched) {
                    throw CloneException(
                        CloneError.AXML_PARSE_FAILED,
                        "AndroidManifest.xml tidak ditemukan di dalam APK sumber."
                    )
                }
                if (!arscPatched) {
                    throw CloneException(
                        CloneError.ARSC_PATCH_FAILED,
                        "resources.arsc tidak ditemukan di dalam APK sumber."
                    )
                }
            }
        } catch (e: CloneException) {
            outputUnsignedApk.delete()
            throw e
        } catch (e: Exception) {
            outputUnsignedApk.delete()
            throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal membangun ulang APK: ${e.message}", e)
        }
    }
}
