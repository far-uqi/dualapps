package com.dualapps.cloner.data

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.dualapps.cloner.model.InstalledApp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Metadata minimal hasil pembacaan berkas `.apk` eksternal sebelum masuk ke layar Atur Clone.
 */
data class ApkArchiveMetadata(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkSizeBytes: Long
)

/**
 * Abstraksi pembaca metadata APK (pada runtime Android memakai `PackageManager.getPackageArchiveInfo`).
 */
fun interface ApkArchiveInspector {
    fun inspect(apkFile: File): ApkArchiveMetadata?
}

/**
 * Implementasi runtime Android menggunakan `PackageManager.getPackageArchiveInfo`.
 */
class AndroidApkArchiveInspector(
    private val packageManager: PackageManager
) : ApkArchiveInspector {

    override fun inspect(apkFile: File): ApkArchiveMetadata? {
        val info = getArchiveInfo(packageManager, apkFile) ?: return null
        val pkgName = info.packageName?.trim().orEmpty()
        if (pkgName.isEmpty()) return null

        val appInfo = info.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
        }
        val label = appInfo?.loadLabel(packageManager)?.toString()?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: pkgName
        val versionName = info.versionName.orEmpty()

        return ApkArchiveMetadata(
            packageName = pkgName,
            label = label,
            versionName = versionName,
            apkSizeBytes = apkFile.length()
        )
    }

    companion object {
        fun loadArchiveIcon(packageManager: PackageManager, apkFile: File): Drawable? {
            if (!apkFile.exists()) return null
            val info = getArchiveInfo(packageManager, apkFile) ?: return null
            val appInfo = info.applicationInfo ?: return null
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            return runCatching { appInfo.loadIcon(packageManager) }.getOrNull()
        }

        private fun getArchiveInfo(
            packageManager: PackageManager,
            apkFile: File
        ) = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull()
    }
}

sealed class ApkFileValidationResult {
    data class Valid(val app: InstalledApp) : ApkFileValidationResult()
    data class Invalid(val message: String) : ApkFileValidationResult()
}

/**
 * Menyalin berkas `.apk` pilihan pengguna ke folder sementara di `cacheDir/picked_apks/`
 * lalu memvalidasi struktur ZIP/manifest dan metadata package-nya.
 */
object ApkFileSourceValidator {

    const val PICKED_APKS_DIR_NAME = "picked_apks"

    fun copyToCache(inputStream: InputStream, cacheDir: File): File {
        val dir = File(cacheDir, PICKED_APKS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        // Bersihkan sisa berkas APK pilihan lama agar cache tidak menumpuk
        dir.listFiles()?.forEach { old -> runCatching { old.delete() } }

        val dest = File(dir, "picked_${UUID.randomUUID()}.apk")
        inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        return dest
    }

    fun validate(
        apkFile: File,
        inspector: ApkArchiveInspector
    ): ApkFileValidationResult {
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead() || apkFile.length() <= 0L) {
            runCatching { apkFile.delete() }
            return ApkFileValidationResult.Invalid(
                "Berkas APK tidak dapat dibaca atau berukuran kosong. Pilih berkas .apk yang valid."
            )
        }

        val hasManifest = runCatching {
            ZipFile(apkFile).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)

        if (!hasManifest) {
            runCatching { apkFile.delete() }
            return ApkFileValidationResult.Invalid(
                "Berkas yang dipilih bukan APK Android yang valid (AndroidManifest.xml tidak ditemukan)."
            )
        }

        val metadata = runCatching { inspector.inspect(apkFile) }.getOrNull()
        if (metadata == null || metadata.packageName.isBlank()) {
            runCatching { apkFile.delete() }
            return ApkFileValidationResult.Invalid(
                "Berkas APK tidak valid atau rusak dan tidak dapat dibaca oleh sistem Android."
            )
        }

        val resolvedLabel = metadata.label.trim().ifEmpty { metadata.packageName }
        return ApkFileValidationResult.Valid(
            InstalledApp(
                packageName = metadata.packageName.trim(),
                label = resolvedLabel,
                apkSizeBytes = if (metadata.apkSizeBytes > 0L) metadata.apkSizeBytes else apkFile.length(),
                sourceDir = apkFile.absolutePath,
                isSystemApp = false,
                isSplitApk = false,
                versionName = metadata.versionName
            )
        )
    }
}
