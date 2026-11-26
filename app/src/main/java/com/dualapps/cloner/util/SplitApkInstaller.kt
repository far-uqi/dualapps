package com.dualapps.cloner.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dualapps.cloner.R
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import java.io.OutputStream

/**
 * Installer multi-APK (Split APK) menggunakan `PackageInstaller.Session` (`MODE_FULL_INSTALL`).
 * Satu sesi dipakai untuk seluruh berkas (base + semua split) satu hasil clone.
 * Untuk APK tunggal, tetap gunakan [ApkInstaller].
 */
object SplitApkInstaller {

    fun installSplitClone(
        context: Context,
        record: ClonedApkRecord,
        origin: InstallOrigin = InstallOrigin.RESULTS
    ): Boolean {
        val allFiles = record.allApkFiles
        if (allFiles.any { !it.exists() }) {
            Toast.makeText(context, R.string.err_apk_not_found, Toast.LENGTH_SHORT).show()
            return false
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.msg_allow_unknown_sources, Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return false
        }

        val packageInstaller = context.packageManager.packageInstaller
        val mainHandler = Handler(Looper.getMainLooper())
        InstallProgressTracker.onSplitInstallStarted(record.clonePackageName, record.cloneName, origin)
        return try {
            SplitInstallSessionCoordinator.writeAndCommit(allFiles) { totalBytes ->
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setSize(totalBytes)
                    setAppPackageName(record.clonePackageName)
                }
                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                // Pantau callback sesi resmi Android agar segera beralih dari "Menunggu konfirmasi"
                // ke "Memasang…" saat pengguna menekan INSTALL, dan otomatis unregister saat selesai.
                val sessionCallback = object : PackageInstaller.SessionCallback() {
                    override fun onCreated(id: Int) = Unit
                    override fun onBadgingChanged(id: Int) = Unit
                    override fun onActiveChanged(id: Int, active: Boolean) {
                        if (id == sessionId && active) {
                            InstallProgressTracker.onSplitInstallConfirmed(
                                record.clonePackageName,
                                record.cloneName
                            )
                        }
                    }

                    override fun onProgressChanged(id: Int, progress: Float) {
                        if (id == sessionId && progress > 0f) {
                            InstallProgressTracker.onSplitInstallConfirmed(
                                record.clonePackageName,
                                record.cloneName
                            )
                        }
                    }

                    override fun onFinished(id: Int, success: Boolean) {
                        if (id == sessionId) {
                            runCatching { packageInstaller.unregisterSessionCallback(this) }
                        }
                    }
                }
                runCatching {
                    packageInstaller.registerSessionCallback(sessionCallback, mainHandler)
                }

                val callbackIntent = Intent(context, SplitInstallResultReceiver::class.java).apply {
                    action = SplitInstallResultReceiver.ACTION_INSTALL_RESULT
                    putExtra(SplitInstallResultReceiver.EXTRA_CLONE_NAME, record.cloneName)
                    putExtra(SplitInstallResultReceiver.EXTRA_CLONE_PACKAGE, record.clonePackageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    flags
                )

                object : InstallSessionWriter {
                    override fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream {
                        return session.openWrite(name, offsetBytes, lengthBytes)
                    }

                    override fun fsync(out: OutputStream) {
                        session.fsync(out)
                    }

                    override fun commit() {
                        session.commit(pendingIntent.intentSender)
                    }

                    override fun abandon() {
                        runCatching { packageInstaller.unregisterSessionCallback(sessionCallback) }
                        runCatching { session.abandon() }
                    }

                    override fun close() {
                        session.close()
                    }
                }
            }
            true
        } catch (e: CloneException) {
            val msg = e.message ?: e.error.userMessage
            InstallProgressTracker.onSplitInstallFailed(record.clonePackageName, record.cloneName, msg)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            val msg = context.getString(R.string.err_split_session_start, e.message.orEmpty())
            InstallProgressTracker.onSplitInstallFailed(record.clonePackageName, record.cloneName, msg)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            false
        }
    }

    fun shareSplitApks(context: Context, record: ClonedApkRecord): Boolean {
        val files = record.allApkFiles
        if (files.any { !it.exists() }) {
            Toast.makeText(context, R.string.err_apk_not_found, Toast.LENGTH_SHORT).show()
            return false
        }

        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>(files.size)
        for (file in files) {
            uris.add(FileProvider.getUriForFile(context, authority, file))
        }

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/vnd.android.package-archive"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, record.cloneName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            shareIntent,
            context.getString(R.string.chooser_share_apk, record.cloneName)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }
}
