package com.dualapps.cloner.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Menerima callback status sesi `PackageInstaller` untuk pemasangan Split APK.
 * Pada `STATUS_PENDING_USER_ACTION`, meluncurkan dialog konfirmasi sistem ke pengguna.
 */
class SplitInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (val outcome = SplitInstallStatusMapper.mapStatus(status, statusMessage)) {
            SplitInstallOutcome.PendingUserAction -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }.onFailure {
                        Toast.makeText(
                            context,
                            SplitInstallStatusMapper.mapStatus(
                                PackageInstaller.STATUS_FAILURE,
                                it.message
                            ).let { res ->
                                (res as? SplitInstallOutcome.Failure)?.message.orEmpty()
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            is SplitInstallOutcome.Success -> {
                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            }
            is SplitInstallOutcome.Failure -> {
                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.dualapps.cloner.ACTION_SPLIT_INSTALL_RESULT"
        const val EXTRA_CLONE_NAME = "extra_clone_name"
    }
}
