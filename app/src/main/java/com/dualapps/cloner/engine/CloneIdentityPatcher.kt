package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.util.IconProcessor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Menerapkan identitas clone (nama launcher dan/atau ikon custom) pada APK hasil rebuild
 * sebelum tahap signing.
 *
 * - Nama launcher:
 *   - Jika `android:label` berupa string literal di `AndroidManifest.xml`, patch langsung di StringPool AXML.
 *   - Jika berupa referensi `@string/...`, patch nilai string tersebut di global StringPool `resources.arsc`
 *     (mendukung UTF-8 dan UTF-16LE).
 * - Ikon custom:
 *   - Mengganti `res/mipmap-<density>/ic_launcher*.png` (kecuali `*background*`) dengan PNG hasil resize per densitas.
 *   - Jika aplikasi memakai adaptive icon (`res/mipmap-anydpi-v26/ic_launcher*.xml` atau referensi XML dari
 *     `<application android:icon>`), mengganti entri PNG foreground-nya sambil mempertahankan background asli.
 *   - Jika tidak ada entri ikon yang cocok, gagal dengan [CloneError.ICON_PATCH_FAILED].
 */
object CloneIdentityPatcher {

    data class IdentityPatchResult(
        val labelPatched: Boolean,
        val replacedIconEntries: List<String>
    )

    fun applyIdentity(
        inputApk: File,
        outputApk: File,
        newLabel: String?,
        customIconPngs: Map<String, ByteArray>?,
        checkCancelled: () -> Unit = {}
    ): IdentityPatchResult {
        val shouldPatchLabel = !newLabel.isNullOrBlank()
        val shouldPatchIcon = !customIconPngs.isNullOrEmpty()

        if (!shouldPatchLabel && !shouldPatchIcon) {
            inputApk.copyTo(outputApk, overwrite = true)
            return IdentityPatchResult(labelPatched = false, replacedIconEntries = emptyList())
        }

        outputApk.parentFile?.mkdirs()

        try {
            ZipFile(inputApk).use { zip ->
                val entries = zip.entries().toList()
                val entryNames = entries.filterNot { it.isDirectory }.mapTo(LinkedHashSet()) { it.name }

                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    ?: throw CloneException(CloneError.LABEL_PATCH_FAILED, "AndroidManifest.xml tidak ditemukan di APK.")
                val arscEntry = zip.getEntry("resources.arsc")
                    ?: throw CloneException(CloneError.LABEL_PATCH_FAILED, "resources.arsc tidak ditemukan di APK.")

                var manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                var arscBytes = zip.getInputStream(arscEntry).use { it.readBytes() }
                var manifestModified = false
                var arscModified = false

                // 1. Patch nama launcher bila diminta
                if (shouldPatchLabel) {
                    checkCancelled()
                    val labelTarget = AxmlPatcher.readApplicationLabel(manifestBytes)
                        ?: throw CloneException(
                            CloneError.LABEL_PATCH_FAILED,
                            "Atribut android:label tidak ditemukan pada elemen <application>."
                        )
                    when (labelTarget) {
                        is AxmlPatcher.LabelTarget.Literal -> {
                            manifestBytes = AxmlPatcher.patchApplicationLabelLiteral(manifestBytes, newLabel!!)
                            manifestModified = true
                        }
                        is AxmlPatcher.LabelTarget.ResourceRef -> {
                            arscBytes = ArscPatcher.patchStringResource(arscBytes, labelTarget.resId, newLabel!!)
                            arscModified = true
                        }
                    }
                }

                // 2. Temukan semua entri ikon yang harus diganti bila ikon custom dipilih
                val targetIconEntries = LinkedHashSet<String>()
                if (shouldPatchIcon) {
                    checkCancelled()
                    collectTargetIconEntries(zip, entryNames, manifestBytes, arscBytes, targetIconEntries)
                    if (targetIconEntries.isEmpty()) {
                        throw CloneException(
                            CloneError.ICON_PATCH_FAILED,
                            "Tidak ditemukan entri ikon launcher (res/mipmap-*/ic_launcher*.png atau foreground adaptive icon) di APK."
                        )
                    }
                }

                // 3. Tulis ulang APK dengan menjaga metode & alignment entri STORED
                val replacedIcons = mutableListOf<String>()
                AlignedZipWriter(outputApk).use { writer ->
                    for (entry in entries) {
                        checkCancelled()
                        if (entry.isDirectory) continue
                        val name = entry.name

                        when {
                            name == "AndroidManifest.xml" && manifestModified -> {
                                writer.writeDeflatedEntry(name, manifestBytes)
                            }
                            name == "resources.arsc" && arscModified -> {
                                writer.writeStoredEntry(name, arscBytes, AlignedZipWriter.ALIGN_DEFAULT)
                            }
                            name in targetIconEntries -> {
                                val density = IconProcessor.densityForResourcePath(name)
                                val pngBytes = customIconPngs!![density]
                                    ?: customIconPngs["xxxhdpi"]
                                    ?: customIconPngs.values.first()
                                if (entry.method == ZipEntry.STORED) {
                                    writer.writeStoredEntry(name, pngBytes, AlignedZipWriter.alignmentForStored(name))
                                } else {
                                    writer.writeDeflatedEntry(name, pngBytes)
                                }
                                replacedIcons.add(name)
                            }
                            else -> {
                                val rawBytes = zip.getInputStream(entry).use { it.readBytes() }
                                if (entry.method == ZipEntry.STORED) {
                                    writer.writeStoredEntry(name, rawBytes, AlignedZipWriter.alignmentForStored(name))
                                } else {
                                    writer.writeDeflatedEntry(name, rawBytes)
                                }
                            }
                        }
                    }
                }

                return IdentityPatchResult(
                    labelPatched = shouldPatchLabel,
                    replacedIconEntries = replacedIcons
                )
            }
        } catch (e: CloneException) {
            outputApk.delete()
            throw e
        } catch (e: Exception) {
            outputApk.delete()
            val error = if (shouldPatchIcon && !shouldPatchLabel) {
                CloneError.ICON_PATCH_FAILED
            } else {
                CloneError.LABEL_PATCH_FAILED
            }
            throw CloneException(error, "Gagal menerapkan identitas clone: ${e.message}", e)
        }
    }

    private fun collectTargetIconEntries(
        zip: ZipFile,
        entryNames: Set<String>,
        manifestBytes: ByteArray,
        arscBytes: ByteArray,
        outTargets: MutableSet<String>
    ) {
        // A. Pencocokan langsung res/mipmap-*/ic_launcher*.png (kecuali *background*)
        for (name in entryNames) {
            if (isStandardLauncherPng(name)) {
                outTargets.add(name)
            }
        }

        // B. Resolusi adaptive icon XML (res/mipmap-anydpi-v26/ic_launcher*.xml)
        val adaptiveXmlPaths = LinkedHashSet<String>()
        for (name in entryNames) {
            val lower = name.lowercase()
            if (lower.startsWith("res/mipmap-anydpi") &&
                lower.endsWith(".xml") &&
                lower.substringAfterLast('/').startsWith("ic_launcher")
            ) {
                adaptiveXmlPaths.add(name)
            }
        }

        // C. Resolusi resource ID dari <application android:icon / android:roundIcon>
        val iconRefs = runCatching { AxmlPatcher.readApplicationIconRefs(manifestBytes) }.getOrDefault(emptyList())
        for (refId in iconRefs) {
            val paths = runCatching { ArscPatcher.readStringResourceValues(arscBytes, refId) }.getOrDefault(emptyList())
            for (path in paths) {
                if (path !in entryNames) continue
                val lower = path.lowercase()
                when {
                    lower.endsWith(".png") && !lower.contains("background") -> outTargets.add(path)
                    lower.endsWith(".xml") -> adaptiveXmlPaths.add(path)
                }
            }
        }

        // D. Untuk setiap adaptive-icon XML, baca <foreground android:drawable="@..."/> lalu ganti PNG foreground-nya
        for (xmlPath in adaptiveXmlPaths) {
            val xmlEntry = zip.getEntry(xmlPath) ?: continue
            val xmlBytes = zip.getInputStream(xmlEntry).use { it.readBytes() }
            val fgRef = AxmlPatcher.readAdaptiveIconForegroundRef(xmlBytes) ?: continue
            val fgPaths = runCatching { ArscPatcher.readStringResourceValues(arscBytes, fgRef) }.getOrDefault(emptyList())
            for (fgPath in fgPaths) {
                if (fgPath in entryNames && fgPath.lowercase().endsWith(".png")) {
                    outTargets.add(fgPath)
                }
            }
        }
    }

    internal fun isStandardLauncherPng(entryName: String): Boolean {
        val lower = entryName.lowercase()
        if (!lower.startsWith("res/mipmap-") || !lower.endsWith(".png")) return false
        val fileName = lower.substringAfterLast('/')
        if (!fileName.startsWith("ic_launcher")) return false
        // Pertahankan background asli pada adaptive icon
        if (fileName.contains("background")) return false
        return true
    }
}
