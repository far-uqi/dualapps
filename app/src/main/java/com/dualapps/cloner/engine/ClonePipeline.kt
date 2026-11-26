package com.dualapps.cloner.engine

import android.content.pm.PackageManager
import android.os.Build
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.model.SplitApkFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Abstraksi pembaca package name dari berkas APK hasil untuk langkah 7 (verifikasi akhir).
 * Pada runtime Android diimplementasikan memakai `PackageManager.getPackageArchiveInfo`.
 */
fun interface ArchivePackageReader {
    fun readArchivePackageName(apkFile: File): String?
}

class AndroidArchivePackageReader(
    private val packageManager: PackageManager
) : ArchivePackageReader {
    override fun readArchivePackageName(apkFile: File): String? {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
        return info?.packageName
    }
}

enum class CloneStep(val index: Int, val label: String) {
    VALIDATE_SOURCE(0, "Validasi awal & salin APK sumber"),
    PATCH_MANIFEST(1, "Patch AndroidManifest.xml (AXML)"),
    PATCH_ARSC(2, "Patch resources.arsc"),
    REBUILD_APK(3, "Bangun ulang APK & jaga alignment"),
    APPLY_IDENTITY(4, "Terapkan identitas"),
    SIGN_AND_VERIFY(5, "Tanda tangani ulang (v1 + v2) & ApkVerifier"),
    VERIFY_ARCHIVE(6, "Verifikasi akhir package archive")
}

data class CloneRequest(
    val sourceApkPath: String,
    val isSplitApk: Boolean,
    val originalAppName: String,
    val originalPackageName: String,
    val cloneName: String,
    val clonePackageName: String,
    val customIconPngs: Map<String, ByteArray>? = null,
    val splitApkPaths: List<SplitApkFile> = emptyList(),
    val sourceVersionCode: Long = 0L,
    val sourceVersionName: String = "",
    val customIconSourcePath: String? = null,
    val isUpdate: Boolean = false,
    val existingRecordId: String? = null,
    val previousSourceVersionCode: Long = 0L
)

/**
 * Pipeline mesin clone lengkap (APK tunggal v1.0 & Split APK multi-file Fase 10).
 * Bekerja pada salinan APK sumber di `cacheDir/clone_work/<uuid>/`,
 * dan hanya menyimpan hasil akhir setelah seluruh langkah verifikasi lolos.
 */
class ClonePipeline(
    private val cacheDir: File,
    private val filesDir: File,
    private val keystoreManager: DeviceKeystoreManager,
    private val archivePackageReader: ArchivePackageReader,
    private val isPackageInstalled: (String) -> Boolean
) {
    suspend fun execute(
        request: CloneRequest,
        onStepStart: (CloneStep) -> Unit = {},
        onStepDone: (CloneStep) -> Unit = {},
        onFileProgress: (fileIndex: Int, totalFiles: Int, fileName: String) -> Unit = { _, _, _ -> }
    ): ClonedApkRecord = withContext(Dispatchers.IO) {
        val workId = UUID.randomUUID().toString()
        val workDir = File(File(cacheDir, "clone_work"), workId)
        val outputDir = File(filesDir, "cloned_apks")
        var activeStep = CloneStep.VALIDATE_SOURCE
        var finalOutputCleanupTarget: File? = null

        suspend fun checkCancelled() {
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                throw CloneException(CloneError.CANCELLED, cause = e)
            }
        }

        val threadCancelCheck: () -> Unit = {
            if (Thread.currentThread().isInterrupted) {
                throw CloneException(CloneError.CANCELLED)
            }
        }

        try {
            val isMultiSplit = request.splitApkPaths.isNotEmpty()
            val totalFiles = if (isMultiSplit) 1 + request.splitApkPaths.size else 1

            // Langkah 1: Validasi awal & salin APK sumber (base + seluruh split bila multi-APK)
            activeStep = CloneStep.VALIDATE_SOURCE
            onStepStart(activeStep)
            checkCancelled()

            if (request.isSplitApk && !isMultiSplit) {
                throw CloneException(CloneError.SPLIT_UNSUPPORTED)
            }
            if (request.clonePackageName.length > ArscPatcher.MAX_PACKAGE_NAME_LENGTH) {
                throw CloneException(CloneError.PACKAGE_TOO_LONG)
            }
            if (request.isUpdate) {
                if (request.previousSourceVersionCode <= 0L ||
                    request.sourceVersionCode <= request.previousSourceVersionCode
                ) {
                    throw CloneException(
                        CloneError.NO_UPDATE_AVAILABLE,
                        "Versi sumber (${request.sourceVersionCode}) tidak lebih baru dari versi clone (${request.previousSourceVersionCode})."
                    )
                }
            } else if (isPackageInstalled(request.clonePackageName)) {
                throw CloneException(CloneError.PACKAGE_ALREADY_INSTALLED)
            }

            val sourceFile = File(request.sourceApkPath)
            if (!sourceFile.exists() || !sourceFile.canRead()) {
                throw CloneException(CloneError.SOURCE_UNREADABLE)
            }
            if (isMultiSplit) {
                for (split in request.splitApkPaths) {
                    val f = File(split.path)
                    if (!f.exists() || !f.canRead()) {
                        throw CloneException(
                            CloneError.SOURCE_UNREADABLE,
                            "Berkas split '${split.splitName}' tidak dapat dibaca."
                        )
                    }
                }
            }

            if (!workDir.mkdirs()) {
                throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal membuat folder kerja clone.")
            }

            onFileProgress(1, totalFiles, "base.apk")
            val copiedBase = File(workDir, "src_base.apk")
            sourceFile.copyTo(copiedBase, overwrite = true)

            val copiedSplits = mutableListOf<Pair<String, File>>()
            if (isMultiSplit) {
                request.splitApkPaths.forEachIndexed { idx, split ->
                    checkCancelled()
                    val safeName = sanitizeSplitFileName(split.splitName, idx)
                    onFileProgress(idx + 2, totalFiles, safeName)
                    val dest = File(workDir, "src_$safeName")
                    File(split.path).copyTo(dest, overwrite = true)
                    copiedSplits.add(safeName to dest)
                }
            }
            onStepDone(activeStep)

            // Langkah 2–4: Patch AXML + ARSC + salin DEX utuh + bangun ulang APK ter-align
            val unsignedBase = File(workDir, "unsigned_base.apk")
            var manifestStarted = false
            var arscStarted = false

            activeStep = CloneStep.PATCH_MANIFEST
            onStepStart(activeStep)
            checkCancelled()
            onFileProgress(1, totalFiles, "base.apk")

            ApkRebuilder.rebuild(
                sourceApk = copiedBase,
                outputUnsignedApk = unsignedBase,
                oldPackage = request.originalPackageName,
                newPackage = request.clonePackageName,
                onStep = { stepLabel ->
                    when (stepLabel) {
                        "Patch AndroidManifest.xml" -> manifestStarted = true
                        "Patch resources.arsc" -> {
                            if (manifestStarted) onStepDone(CloneStep.PATCH_MANIFEST)
                            activeStep = CloneStep.PATCH_ARSC
                            onStepStart(CloneStep.PATCH_ARSC)
                            arscStarted = true
                        }
                    }
                },
                checkCancelled = threadCancelCheck,
                requireArsc = true
            )
            checkCancelled()

            val unsignedSplits = mutableListOf<Pair<String, File>>()
            if (isMultiSplit) {
                copiedSplits.forEachIndexed { idx, (safeName, srcSplit) ->
                    checkCancelled()
                    onFileProgress(idx + 2, totalFiles, safeName)
                    val outUnsigned = File(workDir, "unsigned_$safeName")
                    try {
                        // Semua split WAJIB patch AXML; ARSC dipatch bila ada (requireArsc = false untuk split ABI murni)
                        ApkRebuilder.rebuild(
                            sourceApk = srcSplit,
                            outputUnsignedApk = outUnsigned,
                            oldPackage = request.originalPackageName,
                            newPackage = request.clonePackageName,
                            checkCancelled = threadCancelCheck,
                            requireArsc = false
                        )
                    } catch (e: CloneException) {
                        if (e.error == CloneError.CANCELLED || e.error == CloneError.PACKAGE_TOO_LONG) {
                            throw e
                        }
                        throw CloneException(
                            CloneError.SPLIT_PATCH_FAILED,
                            "Gagal memproses split '$safeName': ${e.message}",
                            e
                        )
                    }
                    unsignedSplits.add(safeName to outUnsigned)
                }
            }

            if (arscStarted) {
                onStepDone(CloneStep.PATCH_ARSC)
            } else {
                onStepDone(CloneStep.PATCH_MANIFEST)
                onStepStart(CloneStep.PATCH_ARSC)
                onStepDone(CloneStep.PATCH_ARSC)
            }
            activeStep = CloneStep.REBUILD_APK
            onStepStart(CloneStep.REBUILD_APK)
            onStepDone(CloneStep.REBUILD_APK)

            // Langkah 5: Terapkan identitas (nama launcher &/atau ikon custom) bila diminta
            val needsLabelPatch = request.cloneName.isNotBlank() &&
                request.cloneName.trim() != request.originalAppName.trim()
            val needsIconPatch = !request.customIconPngs.isNullOrEmpty()

            val baseReadyForSigning: File
            val splitsReadyForSigning: List<Pair<String, File>>

            if (needsLabelPatch || needsIconPatch) {
                activeStep = CloneStep.APPLY_IDENTITY
                onStepStart(activeStep)
                checkCancelled()

                val labelArg = if (needsLabelPatch) request.cloneName.trim() else null
                val iconArg = if (needsIconPatch) request.customIconPngs else null

                if (!isMultiSplit) {
                    onFileProgress(1, 1, "base.apk")
                    val identityBase = File(workDir, "identity_base.apk")
                    CloneIdentityPatcher.applyIdentity(
                        inputApk = unsignedBase,
                        outputApk = identityBase,
                        newLabel = labelArg,
                        customIconPngs = iconArg,
                        checkCancelled = threadCancelCheck,
                        failIfNoIconsFound = true
                    )
                    baseReadyForSigning = identityBase
                    splitsReadyForSigning = emptyList()
                } else {
                    val baseContext = CloneIdentityPatcher.extractBaseIdentityContext(unsignedBase)
                    onFileProgress(1, totalFiles, "base.apk")
                    val identityBase = File(workDir, "identity_base.apk")
                    val baseRes = CloneIdentityPatcher.applyIdentity(
                        inputApk = unsignedBase,
                        outputApk = identityBase,
                        newLabel = labelArg,
                        customIconPngs = iconArg,
                        checkCancelled = threadCancelCheck,
                        failIfNoIconsFound = false
                    )
                    var totalIconsChanged = baseRes.totalIconChanges

                    val identitySplits = mutableListOf<Pair<String, File>>()
                    unsignedSplits.forEachIndexed { idx, (safeName, splitFile) ->
                        checkCancelled()
                        onFileProgress(idx + 2, totalFiles, safeName)
                        val outSplit = File(workDir, "identity_$safeName")
                        val splitRes = CloneIdentityPatcher.applyIdentityToSplitApk(
                            splitApk = splitFile,
                            outputApk = outSplit,
                            baseContext = baseContext,
                            newLabel = labelArg,
                            customIconPngs = iconArg,
                            checkCancelled = threadCancelCheck
                        )
                        totalIconsChanged += splitRes.totalIconChanges
                        identitySplits.add(safeName to outSplit)
                    }

                    if (needsIconPatch && totalIconsChanged == 0) {
                        throw CloneException(
                            CloneError.ICON_PATCH_FAILED,
                            "Tidak ditemukan entri ikon launcher yang dapat diganti di base.apk maupun seluruh berkas split."
                        )
                    }
                    baseReadyForSigning = identityBase
                    splitsReadyForSigning = identitySplits
                }

                checkCancelled()
                onStepDone(activeStep)
            } else {
                baseReadyForSigning = unsignedBase
                splitsReadyForSigning = unsignedSplits
            }

            // Langkah 6: Tanda tangani ulang SEMUA berkas dengan keystore per-perangkat yang SAMA
            activeStep = CloneStep.SIGN_AND_VERIFY
            onStepStart(activeStep)
            checkCancelled()

            val signingMaterial = keystoreManager.getOrCreateSigningMaterial()
            onFileProgress(1, totalFiles, "base.apk")
            val signedBase = File(workDir, "signed_base.apk")
            ApkSigningEngine.signAndVerify(baseReadyForSigning, signedBase, signingMaterial)

            val signedSplits = mutableListOf<Pair<String, File>>()
            splitsReadyForSigning.forEachIndexed { idx, (safeName, splitToSign) ->
                checkCancelled()
                onFileProgress(idx + 2, totalFiles, safeName)
                val outSigned = File(workDir, "signed_$safeName")
                ApkSigningEngine.signAndVerify(splitToSign, outSigned, signingMaterial)
                signedSplits.add(safeName to outSigned)
            }
            onStepDone(activeStep)

            // Langkah 7: Verifikasi akhir sebelum sukses dinyatakan (getPackageArchiveInfo pada base + tiap split)
            activeStep = CloneStep.VERIFY_ARCHIVE
            onStepStart(activeStep)
            checkCancelled()

            onFileProgress(1, totalFiles, "base.apk")
            val archivePackage = archivePackageReader.readArchivePackageName(signedBase)
            if (archivePackage != request.clonePackageName) {
                signedBase.delete()
                throw CloneException(
                    CloneError.VERIFY_PACKAGE_MISMATCH,
                    "Verifikasi akhir base.apk gagal: diharapkan '${request.clonePackageName}', terbaca '$archivePackage'."
                )
            }
            signedSplits.forEachIndexed { idx, (safeName, signedSplit) ->
                checkCancelled()
                onFileProgress(idx + 2, totalFiles, safeName)
                try {
                    verifySplitArchive(signedSplit, request.clonePackageName, safeName)
                } catch (e: CloneException) {
                    signedSplit.delete()
                    throw e
                }
            }
            onStepDone(activeStep)

            // Simpan APK terverifikasi ke folder privat aplikasi
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal membuat folder hasil clone.")
            }
            val now = System.currentTimeMillis()
            val safeFileBase = if (request.isUpdate && !request.existingRecordId.isNullOrBlank()) {
                request.existingRecordId
            } else {
                "${request.clonePackageName}_$now"
            }

            // Simpan salinan ikon custom permanen agar dapat dipakai ulang saat Perbarui Clone (Fase 11)
            val persistedIconPath = persistCustomIconIfPresent(
                outputDir = outputDir,
                recordId = safeFileBase,
                sourceIconPath = request.customIconSourcePath
            )

            val record = if (!isMultiSplit) {
                val stagingApk = File(outputDir, "${safeFileBase}_staging_$now.apk")
                val finalApk = File(outputDir, "$safeFileBase.apk")
                finalOutputCleanupTarget = stagingApk
                signedBase.copyTo(stagingApk, overwrite = true)
                checkCancelled()
                // Jika sebelumnya berupa split bundle folder dengan id yang sama, bersihkan setelah staging siap
                val oldSplitDir = File(outputDir, safeFileBase)
                if (request.isUpdate && oldSplitDir.exists() && oldSplitDir.isDirectory) {
                    oldSplitDir.deleteRecursively()
                }
                if (finalApk.exists()) finalApk.delete()
                if (!stagingApk.renameTo(finalApk)) {
                    stagingApk.copyTo(finalApk, overwrite = true)
                    stagingApk.delete()
                }
                finalOutputCleanupTarget = null
                ClonedApkRecord(
                    id = safeFileBase,
                    cloneName = request.cloneName,
                    clonePackageName = request.clonePackageName,
                    originalPackageName = request.originalPackageName,
                    originalAppName = request.originalAppName,
                    apkPath = finalApk.absolutePath,
                    apkSizeBytes = finalApk.length(),
                    createdAtMillis = now,
                    splitApkPaths = emptyList(),
                    sourceVersionCode = request.sourceVersionCode,
                    sourceVersionName = request.sourceVersionName,
                    customIconPath = persistedIconPath
                )
            } else {
                val stagingDir = File(outputDir, "${safeFileBase}_staging_$now")
                val splitBundleDir = File(outputDir, safeFileBase)
                finalOutputCleanupTarget = stagingDir
                if (!stagingDir.mkdirs()) {
                    throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal membuat folder hasil split clone.")
                }
                val stagingBase = File(stagingDir, "base.apk")
                signedBase.copyTo(stagingBase, overwrite = true)
                var totalBytes = stagingBase.length()

                val splitFileNames = mutableListOf<String>()
                for ((safeName, signedSplit) in signedSplits) {
                    checkCancelled()
                    val destSplit = File(stagingDir, safeName)
                    signedSplit.copyTo(destSplit, overwrite = true)
                    totalBytes += destSplit.length()
                    splitFileNames.add(safeName)
                }

                // Swap atomik setelah seluruh berkas staging selesai disalin
                val oldSingleApk = File(outputDir, "$safeFileBase.apk")
                if (request.isUpdate && oldSingleApk.exists() && oldSingleApk.isFile) {
                    oldSingleApk.delete()
                }
                if (splitBundleDir.exists()) {
                    splitBundleDir.deleteRecursively()
                }
                if (!stagingDir.renameTo(splitBundleDir)) {
                    stagingDir.copyRecursively(splitBundleDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
                finalOutputCleanupTarget = null

                val finalBase = File(splitBundleDir, "base.apk")
                val finalSplitPaths = splitFileNames.map { File(splitBundleDir, it).absolutePath }
                ClonedApkRecord(
                    id = safeFileBase,
                    cloneName = request.cloneName,
                    clonePackageName = request.clonePackageName,
                    originalPackageName = request.originalPackageName,
                    originalAppName = request.originalAppName,
                    apkPath = finalBase.absolutePath,
                    apkSizeBytes = totalBytes,
                    createdAtMillis = now,
                    splitApkPaths = finalSplitPaths,
                    sourceVersionCode = request.sourceVersionCode,
                    sourceVersionName = request.sourceVersionName,
                    customIconPath = persistedIconPath
                )
            }

            val metaFile = File(outputDir, "$safeFileBase.json")
            metaFile.writeText(record.toJsonString(), Charsets.UTF_8)

            record
        } catch (e: CloneException) {
            runCatching { finalOutputCleanupTarget?.deleteRecursively() }
            throw e
        } catch (e: CancellationException) {
            runCatching { finalOutputCleanupTarget?.deleteRecursively() }
            throw CloneException(CloneError.CANCELLED, cause = e)
        } catch (e: Exception) {
            runCatching { finalOutputCleanupTarget?.deleteRecursively() }
            throw CloneException(
                CloneError.UNKNOWN,
                "Kesalahan pada langkah '${activeStep.label}': ${e.message}",
                e
            )
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    companion object {
        internal fun sanitizeSplitFileName(splitName: String, index: Int): String {
            val cleaned = splitName.trim()
                .removePrefix("split_")
                .removeSuffix(".apk")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifEmpty { "part_${index + 1}" }
            return "split_$cleaned.apk"
        }

        private fun persistCustomIconIfPresent(
            outputDir: File,
            recordId: String,
            sourceIconPath: String?
        ): String? {
            if (sourceIconPath.isNullOrBlank()) return null
            val src = File(sourceIconPath)
            if (!src.exists() || !src.isFile) return null
            val iconsDir = File(outputDir, "icons")
            if (!iconsDir.exists()) iconsDir.mkdirs()
            val dest = File(iconsDir, "$recordId.png")
            if (src.absolutePath != dest.absolutePath) {
                runCatching { src.copyTo(dest, overwrite = true) }
            }
            return if (dest.exists()) dest.absolutePath else src.absolutePath
        }

        /**
         * Memverifikasi arsip split APK hasil sign dengan membaca langsung `AndroidManifest.xml`
         * (dan `resources.arsc` bila ada), karena `PackageManager.getPackageArchiveInfo()` hanya
         * mem-parse APK monolitik/base dan mengembalikan `null` untuk file split (`split="..."`).
         */
        internal fun verifySplitArchive(
            signedSplit: File,
            expectedPackage: String,
            displayName: String = signedSplit.name
        ) {
            val (manifestPkg, arscPackages) = try {
                ZipFile(signedSplit).use { zip ->
                    val manifestEntry = zip.getEntry("AndroidManifest.xml")
                        ?: throw CloneException(
                            CloneError.VERIFY_PACKAGE_MISMATCH,
                            "Verifikasi akhir split '$displayName' gagal: diharapkan '$expectedPackage', terbaca 'null'."
                        )
                    val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                    val parsedManifestPkg = AxmlPatcher.readPackageName(manifestBytes)

                    val arscEntry = zip.getEntry("resources.arsc")
                    val parsedArscPkgs = if (arscEntry != null) {
                        val arscBytes = zip.getInputStream(arscEntry).use { it.readBytes() }
                        ArscPatcher.readPackageNames(arscBytes)
                    } else {
                        null
                    }
                    parsedManifestPkg to parsedArscPkgs
                }
            } catch (e: CloneException) {
                if (e.error == CloneError.VERIFY_PACKAGE_MISMATCH) throw e
                throw CloneException(
                    CloneError.VERIFY_PACKAGE_MISMATCH,
                    "Verifikasi akhir split '$displayName' gagal: diharapkan '$expectedPackage', terbaca 'null'.",
                    e
                )
            } catch (e: Exception) {
                throw CloneException(
                    CloneError.VERIFY_PACKAGE_MISMATCH,
                    "Verifikasi akhir split '$displayName' gagal: diharapkan '$expectedPackage', terbaca 'null'.",
                    e
                )
            }

            if (manifestPkg != expectedPackage) {
                throw CloneException(
                    CloneError.VERIFY_PACKAGE_MISMATCH,
                    "Verifikasi akhir split '$displayName' gagal: diharapkan '$expectedPackage', terbaca '$manifestPkg'."
                )
            }

            if (arscPackages != null && !arscPackages.contains(expectedPackage)) {
                throw CloneException(
                    CloneError.VERIFY_PACKAGE_MISMATCH,
                    "Verifikasi akhir split '$displayName' gagal: diharapkan '$expectedPackage', terbaca '${arscPackages.firstOrNull()}'."
                )
            }
        }
    }
}
