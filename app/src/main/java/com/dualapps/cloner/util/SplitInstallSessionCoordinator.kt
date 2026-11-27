package com.dualapps.cloner.util

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/**
 * Abstraksi sesi `PackageInstaller.Session` agar logika penulisan multi-APK,
 * `fsync`, `commit`, dan `abandon` saat gagal dapat diuji langsung di unit test JVM.
 */
interface InstallSessionWriter : Closeable {
    fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream
    fun fsync(out: OutputStream)
    fun commit()
    fun abandon()
}

/**
 * Koordinator penulisan satu sesi instalasi multi-APK (base + seluruh split).
 */
object SplitInstallSessionCoordinator {

    fun writeAndCommit(
        apkFiles: List<File>,
        openSession: (totalSizeBytes: Long) -> InstallSessionWriter
    ): Int {
        if (apkFiles.isEmpty()) {
            throw CloneException(CloneError.SESSION_FAILED, "Daftar berkas APK untuk sesi instalasi kosong.")
        }
        for (file in apkFiles) {
            if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
                throw CloneException(
                    CloneError.SESSION_FAILED,
                    "Berkas APK '${file.name}' tidak ditemukan atau kosong."
                )
            }
        }

        val totalBytes = apkFiles.sumOf { it.length() }
        val session = try {
            openSession(totalBytes)
        } catch (e: CloneException) {
            throw e
        } catch (e: Exception) {
            throw CloneException(
                CloneError.SESSION_FAILED,
                "Gagal membuka sesi PackageInstaller: ${e.message}",
                e
            )
        }

        var committed = false
        try {
            session.use { s ->
                apkFiles.forEachIndexed { idx, file ->
                    val entryName = "${idx}_${file.name}"
                    s.openWrite(entryName, 0L, file.length()).use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                        s.fsync(out)
                    }
                }
                s.commit()
                committed = true
            }
            return apkFiles.size
        } catch (e: CloneException) {
            if (!committed) runCatching { session.abandon() }
            throw e
        } catch (e: Exception) {
            if (!committed) runCatching { session.abandon() }
            throw CloneException(
                CloneError.SESSION_FAILED,
                "Gagal menulis atau meng-commit sesi instalasi multi-APK: ${e.message}",
                e
            )
        }
    }
}

sealed class SplitInstallOutcome {
    data object PendingUserAction : SplitInstallOutcome()
    data class Success(val message: String) : SplitInstallOutcome()
    data class Failure(val error: CloneError, val message: String) : SplitInstallOutcome()
}

/**
 * Pemetaan kode status `PackageInstaller.EXTRA_STATUS` ke hasil dan pesan Bahasa Indonesia yang jelas.
 * Nilai konstanta mengikuti `android.content.pm.PackageInstaller` agar dapat diuji di JVM murni.
 */
object SplitInstallStatusMapper {
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7

    fun mapStatus(status: Int, statusMessage: String?): SplitInstallOutcome {
        val detail = statusMessage?.trim().orEmpty()
        return when (status) {
            STATUS_PENDING_USER_ACTION -> SplitInstallOutcome.PendingUserAction
            STATUS_SUCCESS -> SplitInstallOutcome.Success("Split APK clone installed!")
            STATUS_FAILURE_ABORTED -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                "Installation was cancelled by the user or system."
            )
            STATUS_FAILURE_BLOCKED -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                "Installation was blocked by the system or Play Protect. See the Play Protect Guide in Clone Results."
            )
            STATUS_FAILURE_CONFLICT -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                "Failed to install due to a package name or signature conflict with an installed app."
            )
            STATUS_FAILURE_STORAGE -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                "Failed to install because device storage is insufficient."
            )
            STATUS_FAILURE_INCOMPATIBLE -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                "Failed to install: split files are incompatible with this device or ABI."
            )
            STATUS_FAILURE_INVALID -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                if (detail.isNotEmpty()) "Split APK files were rejected by the system: $detail"
                else "Split APK files were rejected as invalid by Android."
            )
            else -> SplitInstallOutcome.Failure(
                CloneError.SPLIT_INSTALL_FAILED,
                if (detail.isNotEmpty()) "Split APK installation failed: $detail"
                else CloneError.SPLIT_INSTALL_FAILED.userMessage
            )
        }
    }
}
