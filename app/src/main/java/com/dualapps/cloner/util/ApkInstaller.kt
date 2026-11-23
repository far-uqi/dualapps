package com.dualapps.cloner.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dualapps.cloner.R
import java.io.File

/**
 * Helper untuk meluncurkan intent pemasangan APK melalui FileProvider.
 * Memakai `${context.packageName}.fileprovider` agar tetap berfungsi
 * meski Dualapps dikloning menjadi `com.dualapps.cloner.clone`.
 */
object ApkInstaller {

    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
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

        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    fun shareApk(context: Context, apkFile: File, displayName: String): Boolean {
        if (!apkFile.exists()) {
            Toast.makeText(context, R.string.err_apk_not_found, Toast.LENGTH_SHORT).show()
            return false
        }

        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, apkUri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            shareIntent,
            context.getString(R.string.chooser_share_apk, displayName)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }
}
