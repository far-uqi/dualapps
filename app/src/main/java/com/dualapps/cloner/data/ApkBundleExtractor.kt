package com.dualapps.cloner.data

import com.dualapps.cloner.engine.ArscPatcher
import com.dualapps.cloner.engine.AxmlPatcher
import com.dualapps.cloner.engine.AxmlStringPool
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.model.SplitApkFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

enum class BundleSourceKind(val originTag: String) {
    APK("APK"),
    APKS("APKS"),
    XAPK("XAPK")
}

data class XapkManifestInfo(
    val packageName: String?,
    val appName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val declaredApkFiles: List<String>
)

/**
 * Pembongkar dan validator berkas bundle `.apks` dan `.xapk` (100% pure-JVM, tanpa parse `toc.pb`
 * dan tanpa memanggil `PackageManager.getPackageArchiveInfo` pada berkas split).
 */
object ApkBundleExtractor {

    const val MAX_BUNDLE_TOTAL_BYTES: Long = 2L * 1024L * 1024L * 1024L // 2 GB

    private data class ExtractedApkEntry(
        val file: File,
        val packageName: String,
        val splitName: String?
    )

    fun extractAndValidate(
        sourceArchiveFile: File,
        cacheDir: File,
        baseApkInspector: ApkArchiveInspector
    ): ApkFileValidationResult {
        if (!sourceArchiveFile.exists() || !sourceArchiveFile.isFile || !sourceArchiveFile.canRead() || sourceArchiveFile.length() <= 0L) {
            return ApkFileValidationResult.Invalid(CloneError.BUNDLE_INVALID.userMessage, CloneError.BUNDLE_INVALID)
        }
        if (sourceArchiveFile.length() > MAX_BUNDLE_TOTAL_BYTES) {
            return ApkFileValidationResult.Invalid(
                "Ukuran berkas bundle melebihi batas wajar 2 GB dan tidak dapat diproses.",
                CloneError.BUNDLE_INVALID
            )
        }

        val bundleWorkDir = File(File(cacheDir, ApkFileSourceValidator.PICKED_APKS_DIR_NAME), "bundle_${UUID.randomUUID()}")
        try {
            ZipFile(sourceArchiveFile).use { zip ->
                val entries = zip.entries().toList().filterNot { it.isDirectory }
                val entryNames = entries.map { it.name }

                // 1. Tolak segera bila bundle berisi berkas .obb (di luar cakupan v2.0)
                if (entryNames.any { it.lowercase().endsWith(".obb") }) {
                    return ApkFileValidationResult.Invalid(CloneError.BUNDLE_HAS_OBB.userMessage, CloneError.BUNDLE_HAS_OBB)
                }

                // 2. Deteksi berdasarkan ISI: jika ada AndroidManifest.xml di root → APK tunggal (Fase 7)
                if (zip.getEntry("AndroidManifest.xml") != null) {
                    return ApkFileSourceValidator.validate(sourceArchiveFile, baseApkInspector)
                }

                val manifestJsonEntry = entries.firstOrNull { it.name.equals("manifest.json", ignoreCase = true) }
                val apkEntries = entries.filter { entry ->
                    val lower = entry.name.lowercase()
                    lower.endsWith(".apk") && !lower.startsWith("__macosx/")
                }

                if (apkEntries.isEmpty()) {
                    return ApkFileValidationResult.Invalid(CloneError.BUNDLE_NO_APK.userMessage, CloneError.BUNDLE_NO_APK)
                }

                val kind = if (manifestJsonEntry != null) BundleSourceKind.XAPK else BundleSourceKind.APKS
                val xapkInfo = manifestJsonEntry?.let { entry ->
                    val rawJson = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    parseXapkManifestJson(rawJson)
                }

                // Validasi entri `apks` yang dideklarasikan di manifest.json XAPK benar-benar ada di ZIP
                if (xapkInfo != null && xapkInfo.declaredApkFiles.isNotEmpty()) {
                    val lowerNames = entryNames.mapTo(HashSet()) { it.lowercase() }
                    val missing = xapkInfo.declaredApkFiles.firstOrNull { declared ->
                        declared.lowercase() !in lowerNames
                    }
                    if (missing != null) {
                        return ApkFileValidationResult.Invalid(
                            "Berkas APK '$missing' yang tercantum di manifest.json tidak ditemukan di dalam XAPK.",
                            CloneError.BUNDLE_INVALID
                        )
                    }
                }

                if (!bundleWorkDir.mkdirs()) {
                    return ApkFileValidationResult.Invalid(CloneError.BUNDLE_INVALID.userMessage, CloneError.BUNDLE_INVALID)
                }

                // 3. Ekstrak semua *.apk ke cacheDir/picked_apks/bundle_<uuid>/ dengan guard ukuran & path traversal
                val extracted = mutableListOf<ExtractedApkEntry>()
                var totalExtractedBytes = 0L

                apkEntries.forEachIndexed { idx, apkEntry ->
                    val safeName = File(apkEntry.name).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .ifBlank { "part_$idx.apk" }
                    val destFile = File(bundleWorkDir, "${idx}_$safeName")

                    zip.getInputStream(apkEntry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = input.read(buffer)
                            while (read >= 0) {
                                totalExtractedBytes += read
                                if (totalExtractedBytes > MAX_BUNDLE_TOTAL_BYTES) {
                                    bundleWorkDir.deleteRecursively()
                                    return ApkFileValidationResult.Invalid(
                                        "Total ukuran ekstrak bundle melebihi batas wajar 2 GB.",
                                        CloneError.BUNDLE_INVALID
                                    )
                                }
                                output.write(buffer, 0, read)
                                read = input.read(buffer)
                            }
                        }
                    }

                    val inspected = inspectExtractedApk(destFile)
                        ?: run {
                            bundleWorkDir.deleteRecursively()
                            return ApkFileValidationResult.Invalid(
                                "Berkas '$safeName' di dalam bundle bukan APK valid atau tidak memiliki AndroidManifest.xml.",
                                CloneError.BUNDLE_INVALID
                            )
                        }
                    extracted.add(inspected)
                }

                // 4. Pastikan semua APK dalam bundle memiliki package name yang SAMA (dan cocok dengan manifest.json)
                val distinctPackages = extracted.map { it.packageName }.distinct()
                if (distinctPackages.size != 1) {
                    bundleWorkDir.deleteRecursively()
                    return ApkFileValidationResult.Invalid(
                        CloneError.BUNDLE_PACKAGE_MISMATCH.userMessage,
                        CloneError.BUNDLE_PACKAGE_MISMATCH
                    )
                }
                val resolvedPackage = distinctPackages.single()
                if (!xapkInfo?.packageName.isNullOrBlank() && xapkInfo!!.packageName != resolvedPackage) {
                    bundleWorkDir.deleteRecursively()
                    return ApkFileValidationResult.Invalid(
                        "Package name di manifest.json ('${xapkInfo.packageName}') tidak cocok dengan isi APK ('$resolvedPackage').",
                        CloneError.BUNDLE_PACKAGE_MISMATCH
                    )
                }

                // 5. Identifikasi base APK (tanpa atribut `split`) vs split APKs (punya atribut `split="..."`)
                val baseCandidates = extracted.filter { it.splitName == null }
                if (baseCandidates.isEmpty()) {
                    bundleWorkDir.deleteRecursively()
                    return ApkFileValidationResult.Invalid(
                        CloneError.BUNDLE_NO_BASE_APK.userMessage,
                        CloneError.BUNDLE_NO_BASE_APK
                    )
                }
                val baseEntry = baseCandidates.maxBy { it.file.length() }
                val splitEntries = extracted.filter { it.file != baseEntry.file && it.splitName != null }

                // Baca metadata base APK via inspector (HANYA untuk base.apk, BUKAN split)
                val baseMeta = runCatching { baseApkInspector.inspect(baseEntry.file) }.getOrNull()
                val label = xapkInfo?.appName?.takeIf { it.isNotBlank() }
                    ?: baseMeta?.label?.takeIf { it.isNotBlank() }
                    ?: resolvedPackage
                val versionCode = baseMeta?.versionCode?.takeIf { it > 0L }
                    ?: xapkInfo?.versionCode
                    ?: 0L
                val versionName = baseMeta?.versionName?.takeIf { it.isNotBlank() }
                    ?: xapkInfo?.versionName.orEmpty()

                val splitFiles = splitEntries.map { entry ->
                    SplitApkFile(
                        splitName = entry.splitName!!,
                        path = entry.file.absolutePath,
                        sizeBytes = entry.file.length()
                    )
                }
                val totalSize = baseEntry.file.length() + splitFiles.sumOf { it.sizeBytes }

                val app = InstalledApp(
                    packageName = resolvedPackage,
                    label = label,
                    apkSizeBytes = totalSize,
                    sourceDir = baseEntry.file.absolutePath,
                    isSystemApp = false,
                    isSplitApk = splitFiles.isNotEmpty(),
                    versionName = versionName.ifBlank { null },
                    splitFiles = splitFiles,
                    versionCode = versionCode
                )
                return ApkFileValidationResult.Valid(app = app, sourceOrigin = kind.originTag)
            }
        } catch (e: Exception) {
            bundleWorkDir.deleteRecursively()
            return ApkFileValidationResult.Invalid(
                CloneError.BUNDLE_INVALID.userMessage,
                CloneError.BUNDLE_INVALID
            )
        }
    }

    private fun inspectExtractedApk(apkFile: File): ExtractedApkEntry? {
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
                val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val pkgName = AxmlPatcher.readPackageName(manifestBytes)?.takeIf { it.isNotBlank() }
                    ?: return null
                val splitName = readManifestSplitName(manifestBytes)

                val arscEntry = zip.getEntry("resources.arsc")
                if (arscEntry != null) {
                    val arscBytes = zip.getInputStream(arscEntry).use { it.readBytes() }
                    val arscPkgs = ArscPatcher.readPackageNames(arscBytes)
                    if (arscPkgs.isNotEmpty() && !arscPkgs.contains(pkgName)) {
                        return null
                    }
                }
                ExtractedApkEntry(file = apkFile, packageName = pkgName, splitName = splitName)
            }
        }.getOrNull()
    }

    /**
     * Membaca atribut `split="..."` (tanpa namespace) pada elemen `<manifest>` di AXML biner.
     * Tidak mengubah `AxmlPatcher.kt`; memakai ulang `AxmlStringPool.parse` yang sudah ada.
     */
    internal fun readManifestSplitName(axmlBytes: ByteArray): String? {
        if (axmlBytes.size < 16) return null
        val rootType = readU16(axmlBytes, 0)
        val xmlHeaderSize = readU16(axmlBytes, 2)
        if (rootType != AxmlPatcher.RES_XML_TYPE || xmlHeaderSize < 8 || xmlHeaderSize + 8 > axmlBytes.size) {
            return null
        }
        val poolType = readU16(axmlBytes, xmlHeaderSize)
        val poolSize = readI32(axmlBytes, xmlHeaderSize + 4)
        if (poolType != AxmlPatcher.RES_STRING_POOL_TYPE || poolSize < AxmlStringPool.HEADER_SIZE) {
            return null
        }
        val pool = runCatching { AxmlStringPool.parse(axmlBytes, xmlHeaderSize) }.getOrNull() ?: return null
        var offset = xmlHeaderSize + poolSize

        while (offset + 8 <= axmlBytes.size) {
            val type = readU16(axmlBytes, offset)
            val headerSize = readU16(axmlBytes, offset + 2)
            val chunkSize = readI32(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) break

            if (type == AxmlPatcher.RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(axmlBytes, extStart + 4)
                val attributeStart = readU16(axmlBytes, extStart + 8)
                val attributeSize = readU16(axmlBytes, extStart + 10)
                val attributeCount = readU16(axmlBytes, extStart + 12)
                if (pool.strings.getOrNull(nameIdx) == "manifest") {
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) break
                        val attrNsIdx = readI32(axmlBytes, attrOff)
                        val attrNameIdx = readI32(axmlBytes, attrOff + 4)
                        val rawValueIdx = readI32(axmlBytes, attrOff + 8)
                        val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                        val valueData = readI32(axmlBytes, attrOff + 16)
                        if (attrNsIdx == -1 && pool.strings.getOrNull(attrNameIdx) == "split") {
                            val strIdx = when {
                                rawValueIdx >= 0 -> rawValueIdx
                                valueType == 0x03 && valueData >= 0 -> valueData
                                else -> -1
                            }
                            return pool.strings.getOrNull(strIdx)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return null
    }

    internal fun parseXapkManifestJson(raw: String): XapkManifestInfo {
        val pkgName = extractTopLevelString(raw, "package_name")
        val appName = extractTopLevelString(raw, "name")
        val versionName = extractTopLevelString(raw, "version_name")
        val versionCode = extractTopLevelNumberOrStringLong(raw, "version_code")

        val declaredFiles = mutableListOf<String>()
        val arrayMatch = Regex("\"(?:split_apks|apks)\"\\s*:\\s*\\[(.*?)]", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(raw)
        if (arrayMatch != null) {
            val fileRegex = Regex("\"file\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            fileRegex.findAll(arrayMatch.groupValues[1]).forEach { m ->
                val f = m.groupValues[1].replace("\\/", "/").trim()
                if (f.isNotEmpty()) declaredFiles.add(f)
            }
        }
        return XapkManifestInfo(
            packageName = pkgName,
            appName = appName,
            versionCode = versionCode,
            versionName = versionName,
            declaredApkFiles = declaredFiles
        )
    }

    private fun extractTopLevelString(raw: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(raw) ?: return null
        return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim().takeIf { it.isNotEmpty() }
    }

    private fun extractTopLevelNumberOrStringLong(raw: String, key: String): Long? {
        val numMatch = Regex("\"$key\"\\s*:\\s*\"?(\\d+)\"?").find(raw) ?: return null
        return numMatch.groupValues[1].toLongOrNull()
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readI32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
