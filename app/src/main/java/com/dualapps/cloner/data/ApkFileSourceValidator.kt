package com.dualapps.cloner.data

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.dualapps.cloner.model.CloneError
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
    val apkSizeBytes: Long,
    val versionCode: Long = 0L
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
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        return ApkArchiveMetadata(
            packageName = pkgName,
            label = label,
            versionName = versionName,
            apkSizeBytes = apkFile.length(),
            versionCode = versionCode
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
    data class Valid(
        val app: InstalledApp,
        val sourceOrigin: String = "APK"
    ) : ApkFileValidationResult()

    data class Invalid(
        val message: String,
        val error: CloneError? = null
    ) : ApkFileValidationResult()
}

/**
 * Menyalin berkas `.apk` / `.apks` / `.xapk` pilihan pengguna ke folder sementara di
 * `cacheDir/picked_apks/` lalu memvalidasi struktur ZIP/manifest dan metadata package-nya.
 */
object ApkFileSourceValidator {

    const val PICKED_APKS_DIR_NAME = "picked_apks"
    private const val STALE_CACHE_AGE_MILLIS = 60L * 60L * 1000L // 1 jam

    fun copyToCache(inputStream: InputStream, cacheDir: File): File {
        val dir = File(cacheDir, PICKED_APKS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        // Hanya bersihkan sisa berkas/folder bundle yang sudah basi (> 1 jam) agar tidak menghapus
        // berkas bundle lain yang masih dipakai sesi aktif.
        val cutoff = System.currentTimeMillis() - STALE_CACHE_AGE_MILLIS
        dir.listFiles()?.forEach { old ->
            if (old.lastModified() < cutoff) {
                runCatching { old.deleteRecursively() }
            }
        }

        val dest = File(dir, "picked_${UUID.randomUUID()}.apk")
        inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        return dest
    }

    /**
     * Memvalidasi berkas yang dipilih pengguna — otomatis mendeteksi APK tunggal (Fase 7) vs
     * bundle APKS/XAPK (Fase 12) berdasarkan ISI arsip, bukan hanya ekstensi berkas.
     */
    fun validateAnySource(
        pickedFile: File,
        cacheDir: File,
        inspector: ApkArchiveInspector
    ): ApkFileValidationResult {
        val result = ApkBundleExtractor.extractAndValidate(pickedFile, cacheDir, inspector)
        // Jika hasil berupa bundle APKS/XAPK yang sudah diekstrak ke subfolder, salinan arsip mentah boleh dibersihkan
        if (result is ApkFileValidationResult.Valid && result.sourceOrigin != "APK") {
            runCatching { pickedFile.delete() }
        } else if (result is ApkFileValidationResult.Invalid) {
            runCatching { pickedFile.delete() }
        }
        return result
    }

    fun validate(
        apkFile: File,
        inspector: ApkArchiveInspector
    ): ApkFileValidationResult {
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead() || apkFile.length() <= 0L) {
            runCatching { apkFile.delete() }
            return ApkFileValidationResult.Invalid(
                "APK file cannot be read or is empty. Choose a valid .apk file."
            )
        }

        val hasManifest = runCatching {
            ZipFile(apkFile).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)

        if (!hasManifest) {
            runCatching { apkFile.delete() }
            return ApkFileValidationResult.Invalid(
                "The selected file is not a valid Android APK (AndroidManifest.xml not found)."
            )
        }

        val metadata = runCatching { inspector.inspect(apkFile) }.getOrNull()
        if (metadata == null || metadata.packageName.isBlank()) {
            runCatching { apkFile.delete() }
            return ApkFileValidationResult.Invalid(
                "The APK file is invalid or corrupted and cannot be read by Android."
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
                versionName = metadata.versionName,
                versionCode = metadata.versionCode
            ),
            sourceOrigin = "APK"
        )
    }
}
