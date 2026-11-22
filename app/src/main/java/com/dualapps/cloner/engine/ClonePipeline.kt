package com.dualapps.cloner.engine

import android.content.pm.PackageManager
import android.os.Build
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    SIGN_AND_VERIFY(4, "Tanda tangani ulang (v1 + v2) & ApkVerifier"),
    VERIFY_ARCHIVE(5, "Verifikasi akhir package archive")
}

data class CloneRequest(
    val sourceApkPath: String,
    val isSplitApk: Boolean,
    val originalAppName: String,
    val originalPackageName: String,
    val cloneName: String,
    val clonePackageName: String
)

/**
 * Pipeline mesin clone lengkap (Bagian 3 langkah 1–7).
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
        onStepDone: (CloneStep) -> Unit = {}
    ): ClonedApkRecord = withContext(Dispatchers.IO) {
        val workId = UUID.randomUUID().toString()
        val workDir = File(File(cacheDir, "clone_work"), workId)
        val outputDir = File(filesDir, "cloned_apks")
        var activeStep = CloneStep.VALIDATE_SOURCE

        suspend fun checkCancelled() {
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                throw CloneException(CloneError.CANCELLED, cause = e)
            }
        }

        try {
            // Langkah 1: Validasi awal & salin APK sumber
            activeStep = CloneStep.VALIDATE_SOURCE
            onStepStart(activeStep)
            checkCancelled()

            if (request.isSplitApk) {
                throw CloneException(CloneError.SPLIT_UNSUPPORTED)
            }
            if (request.clonePackageName.length > ArscPatcher.MAX_PACKAGE_NAME_LENGTH) {
                throw CloneException(CloneError.PACKAGE_TOO_LONG)
            }
            if (isPackageInstalled(request.clonePackageName)) {
                throw CloneException(CloneError.PACKAGE_ALREADY_INSTALLED)
            }

            val sourceFile = File(request.sourceApkPath)
            if (!sourceFile.exists() || !sourceFile.canRead()) {
                throw CloneException(CloneError.SOURCE_UNREADABLE)
            }

            if (!workDir.mkdirs()) {
                throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal membuat folder kerja clone.")
            }
            val copiedSource = File(workDir, "source.apk")
            sourceFile.copyTo(copiedSource, overwrite = true)
            onStepDone(activeStep)

            // Langkah 2–5: Patch AXML + ARSC + salin DEX utuh + bangun ulang APK ter-align
            val unsignedApk = File(workDir, "unsigned.apk")
            var manifestStarted = false
            var arscStarted = false

            activeStep = CloneStep.PATCH_MANIFEST
            onStepStart(activeStep)
            checkCancelled()

            ApkRebuilder.rebuild(
                sourceApk = copiedSource,
                outputUnsignedApk = unsignedApk,
                oldPackage = request.originalPackageName,
                newPackage = request.clonePackageName,
                onStep = { stepLabel ->
                    when (stepLabel) {
                        "Patch AndroidManifest.xml" -> {
                            manifestStarted = true
                        }
                        "Patch resources.arsc" -> {
                            if (manifestStarted) onStepDone(CloneStep.PATCH_MANIFEST)
                            activeStep = CloneStep.PATCH_ARSC
                            onStepStart(CloneStep.PATCH_ARSC)
                            arscStarted = true
                        }
                    }
                },
                checkCancelled = {
                    if (Thread.currentThread().isInterrupted) {
                        throw CloneException(CloneError.CANCELLED)
                    }
                }
            )
            checkCancelled()
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

            // Langkah 6: Tanda tangani ulang (keystore unik per perangkat + apksig v1/v2 + ApkVerifier)
            activeStep = CloneStep.SIGN_AND_VERIFY
            onStepStart(activeStep)
            checkCancelled()

            val signingMaterial = keystoreManager.getOrCreateSigningMaterial()
            val signedApk = File(workDir, "signed.apk")
            ApkSigningEngine.signAndVerify(unsignedApk, signedApk, signingMaterial)
            onStepDone(activeStep)

            // Langkah 7: Verifikasi akhir sebelum sukses dinyatakan (getPackageArchiveInfo)
            activeStep = CloneStep.VERIFY_ARCHIVE
            onStepStart(activeStep)
            checkCancelled()

            val archivePackage = archivePackageReader.readArchivePackageName(signedApk)
            if (archivePackage != request.clonePackageName) {
                signedApk.delete()
                throw CloneException(
                    CloneError.VERIFY_PACKAGE_MISMATCH,
                    "Verifikasi akhir gagal: diharapkan '${request.clonePackageName}', terbaca '$archivePackage'."
                )
            }
            onStepDone(activeStep)

            // Simpan APK terverifikasi ke folder privat aplikasi
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal membuat folder hasil clone.")
            }
            val now = System.currentTimeMillis()
            val safeFileBase = "${request.clonePackageName}_$now"
            val finalApk = File(outputDir, "$safeFileBase.apk")
            signedApk.copyTo(finalApk, overwrite = true)

            val record = ClonedApkRecord(
                id = safeFileBase,
                cloneName = request.cloneName,
                clonePackageName = request.clonePackageName,
                originalPackageName = request.originalPackageName,
                originalAppName = request.originalAppName,
                apkPath = finalApk.absolutePath,
                apkSizeBytes = finalApk.length(),
                createdAtMillis = now
            )
            val metaFile = File(outputDir, "$safeFileBase.json")
            metaFile.writeText(record.toJsonString(), Charsets.UTF_8)

            record
        } catch (e: CloneException) {
            throw e
        } catch (e: CancellationException) {
            throw CloneException(CloneError.CANCELLED, cause = e)
        } catch (e: Exception) {
            throw CloneException(
                CloneError.UNKNOWN,
                "Kesalahan pada langkah '${activeStep.label}': ${e.message}",
                e
            )
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }
}
