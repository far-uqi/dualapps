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
 *   - Menelusuri rantai referensi `android:icon` / `android:roundIcon` → adaptive XML → foreground drawable (atau
 *     langsung drawable).
 *   - Jika file final berupa PNG, ganti PNG tersebut di tempat.
 *   - Jika file final berupa vector XML (tanpa PNG), jalankan fallback retarget resource di `resources.arsc`
 *     (`.xml` → `.png` baru), hapus entri XML lama dari ZIP, dan tulis PNG baru (432×432 px untuk foreground adaptive)
 *     sambil mempertahankan background dan XML adaptive-icon asli.
 *   - Jika tidak ada entri ikon yang cocok, gagal dengan [CloneError.ICON_PATCH_FAILED].
 */
object CloneIdentityPatcher {

    data class IdentityPatchResult(
        val labelPatched: Boolean,
        val replacedIconEntries: List<String>,
        val retargetedIconEntries: List<String> = emptyList()
    )

    private data class VectorRetargetPlan(
        val resId: Int,
        val oldXmlPaths: Set<String>,
        val newPngPath: String,
        val isAdaptiveForeground: Boolean
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

                // 2. Temukan entri ikon PNG yang diganti langsung DAN/ATAU rencana retarget vector XML → PNG
                val targetPngEntries = LinkedHashSet<String>()
                val retargetPlans = mutableListOf<VectorRetargetPlan>()
                if (shouldPatchIcon) {
                    checkCancelled()
                    planIconReplacements(
                        zip = zip,
                        entryNames = entryNames,
                        manifestBytes = manifestBytes,
                        arscBytes = arscBytes,
                        outTargetPngEntries = targetPngEntries,
                        outRetargetPlans = retargetPlans
                    )
                    if (targetPngEntries.isEmpty() && retargetPlans.isEmpty()) {
                        throw CloneException(
                            CloneError.ICON_PATCH_FAILED,
                            "Tidak ditemukan entri ikon launcher (PNG maupun vector XML yang dapat di-retarget) di APK."
                        )
                    }

                    // Terapkan retarget resource (.xml -> .png) di resources.arsc
                    for (plan in retargetPlans) {
                        checkCancelled()
                        arscBytes = ArscPatcher.retargetFileResource(arscBytes, plan.resId, plan.newPngPath)
                        arscModified = true
                    }
                }

                val removedXmlEntries = retargetPlans.flatMapTo(LinkedHashSet()) { it.oldXmlPaths }
                val newPngEntries = LinkedHashMap<String, ByteArray>()
                for (plan in retargetPlans) {
                    val pngBytes = selectPngForRetarget(customIconPngs!!, plan.isAdaptiveForeground)
                    newPngEntries[plan.newPngPath] = pngBytes
                }

                // 3. Tulis ulang APK dengan menjaga metode & alignment entri STORED
                val replacedIcons = mutableListOf<String>()
                val retargetedIcons = mutableListOf<String>()
                AlignedZipWriter(outputApk).use { writer ->
                    for (entry in entries) {
                        checkCancelled()
                        if (entry.isDirectory) continue
                        val name = entry.name

                        // Hapus entri XML vector lama yang sudah di-retarget ke PNG baru
                        if (name in removedXmlEntries) {
                            continue
                        }

                        when {
                            name == "AndroidManifest.xml" && manifestModified -> {
                                writer.writeDeflatedEntry(name, manifestBytes)
                            }
                            name == "resources.arsc" && arscModified -> {
                                writer.writeStoredEntry(name, arscBytes, AlignedZipWriter.ALIGN_DEFAULT)
                            }
                            name in targetPngEntries -> {
                                val pngBytes = selectPngForPath(customIconPngs!!, name)
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

                    // Tulis entri PNG baru hasil retarget (STORED align 4 byte agar siap dibaca AssetManager)
                    for ((newPath, pngBytes) in newPngEntries) {
                        checkCancelled()
                        writer.writeStoredEntry(newPath, pngBytes, AlignedZipWriter.ALIGN_DEFAULT)
                        retargetedIcons.add(newPath)
                    }
                }

                return IdentityPatchResult(
                    labelPatched = shouldPatchLabel,
                    replacedIconEntries = replacedIcons,
                    retargetedIconEntries = retargetedIcons
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

    private fun planIconReplacements(
        zip: ZipFile,
        entryNames: Set<String>,
        manifestBytes: ByteArray,
        arscBytes: ByteArray,
        outTargetPngEntries: MutableSet<String>,
        outRetargetPlans: MutableList<VectorRetargetPlan>
    ) {
        val reservedPaths = LinkedHashSet<String>(entryNames)
        val retargetedResIds = LinkedHashSet<Int>()

        // A. Pencocokan langsung res/mipmap-<density>/ic_launcher*.png (kecuali *background*)
        for (name in entryNames) {
            if (isStandardLauncherPng(name)) {
                outTargetPngEntries.add(name)
            }
        }

        // B. Kumpulkan adaptive-icon XML standar (res/mipmap-anydpi-v26/ic_launcher*.xml)
        //    serta pasangan (resId, xmlPath) dari <application android:icon / android:roundIcon>
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

        val directVectorCandidates = LinkedHashMap<Int, MutableSet<String>>()
        val iconRefs = runCatching { AxmlPatcher.readApplicationIconRefs(manifestBytes) }.getOrDefault(emptyList())
        for (refId in iconRefs) {
            val paths = runCatching { ArscPatcher.readStringResourceValues(arscBytes, refId) }.getOrDefault(emptyList())
            val xmlPathsForRef = LinkedHashSet<String>()
            var hasPngForRef = false
            for (path in paths) {
                if (path !in entryNames) continue
                val lower = path.lowercase()
                when {
                    lower.endsWith(".png") && !lower.contains("background") -> {
                        outTargetPngEntries.add(path)
                        hasPngForRef = true
                    }
                    lower.endsWith(".xml") -> {
                        xmlPathsForRef.add(path)
                    }
                }
            }
            // Pisahkan XML adaptive-icon vs XML vector drawable langsung pada android:icon
            for (xmlPath in xmlPathsForRef) {
                val xmlEntry = zip.getEntry(xmlPath) ?: continue
                val xmlBytes = zip.getInputStream(xmlEntry).use { it.readBytes() }
                val fgRef = AxmlPatcher.readAdaptiveIconForegroundRef(xmlBytes)
                if (fgRef != null) {
                    adaptiveXmlPaths.add(xmlPath)
                } else if (!hasPngForRef) {
                    directVectorCandidates.getOrPut(refId) { LinkedHashSet() }.add(xmlPath)
                }
            }
        }

        // C. Untuk setiap adaptive-icon XML, telusuri <foreground android:drawable="@..."/>
        for (xmlPath in adaptiveXmlPaths) {
            val xmlEntry = zip.getEntry(xmlPath) ?: continue
            val xmlBytes = zip.getInputStream(xmlEntry).use { it.readBytes() }
            val fgRef = AxmlPatcher.readAdaptiveIconForegroundRef(xmlBytes) ?: continue
            val fgPaths = runCatching { ArscPatcher.readStringResourceValues(arscBytes, fgRef) }.getOrDefault(emptyList())

            val existingPngPaths = fgPaths.filter { it in entryNames && it.lowercase().endsWith(".png") }
            val existingXmlPaths = fgPaths.filter { it in entryNames && it.lowercase().endsWith(".xml") }

            if (existingPngPaths.isNotEmpty()) {
                outTargetPngEntries.addAll(existingPngPaths)
            } else if (existingXmlPaths.isNotEmpty() && retargetedResIds.add(fgRef)) {
                val newPath = allocateUniquePngPath(
                    preferred = "res/drawable/ic_launcher_foreground.png",
                    fallbackPrefix = "res/drawable/dualapps_fg_${fgRef.toString(16)}",
                    reserved = reservedPaths
                )
                outRetargetPlans.add(
                    VectorRetargetPlan(
                        resId = fgRef,
                        oldXmlPaths = existingXmlPaths.toSet(),
                        newPngPath = newPath,
                        isAdaptiveForeground = true
                    )
                )
            }
        }

        // D. Fallback untuk android:icon / android:roundIcon yang menunjuk langsung ke vector XML (non-adaptive)
        for ((iconResId, xmlPaths) in directVectorCandidates) {
            if (xmlPaths.isEmpty() || !retargetedResIds.add(iconResId)) continue
            val newPath = allocateUniquePngPath(
                preferred = "res/drawable/ic_launcher_custom.png",
                fallbackPrefix = "res/drawable/dualapps_icon_${iconResId.toString(16)}",
                reserved = reservedPaths
            )
            outRetargetPlans.add(
                VectorRetargetPlan(
                    resId = iconResId,
                    oldXmlPaths = xmlPaths,
                    newPngPath = newPath,
                    isAdaptiveForeground = false
                )
            )
        }
    }

    private fun allocateUniquePngPath(
        preferred: String,
        fallbackPrefix: String,
        reserved: MutableSet<String>
    ): String {
        if (reserved.add(preferred)) return preferred
        var candidate = "$fallbackPrefix.png"
        var index = 2
        while (!reserved.add(candidate)) {
            candidate = "${fallbackPrefix}_$index.png"
            index++
        }
        return candidate
    }

    private fun selectPngForPath(customIconPngs: Map<String, ByteArray>, entryPath: String): ByteArray {
        val density = IconProcessor.densityForResourcePath(entryPath)
        return customIconPngs[density]
            ?: customIconPngs["xxxhdpi"]
            ?: customIconPngs[IconProcessor.ADAPTIVE_FOREGROUND_KEY]
            ?: customIconPngs.values.first()
    }

    private fun selectPngForRetarget(
        customIconPngs: Map<String, ByteArray>,
        isAdaptiveForeground: Boolean
    ): ByteArray {
        return if (isAdaptiveForeground) {
            customIconPngs[IconProcessor.ADAPTIVE_FOREGROUND_KEY]
                ?: customIconPngs["xxxhdpi"]
                ?: customIconPngs.values.first()
        } else {
            customIconPngs["xxxhdpi"]
                ?: customIconPngs[IconProcessor.ADAPTIVE_FOREGROUND_KEY]
                ?: customIconPngs.values.first()
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
