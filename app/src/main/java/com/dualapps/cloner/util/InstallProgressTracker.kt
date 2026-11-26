package com.dualapps.cloner.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State progres pemasangan yang diamati oleh `ResultsActivity` dan `CloneProgressActivity`
 * agar layar tidak diam tanpa informasi selama sesi `PackageInstaller` berjalan.
 */
sealed class InstallProgressState {
    data object Idle : InstallProgressState()

    data class Installing(
        val clonePackageName: String,
        val cloneName: String,
        val message: String,
        val isSingleApkIntent: Boolean = false
    ) : InstallProgressState()

    data class Succeeded(
        val clonePackageName: String,
        val cloneName: String,
        val message: String
    ) : InstallProgressState()

    data class Failed(
        val clonePackageName: String,
        val cloneName: String,
        val message: String
    ) : InstallProgressState()
}

/**
 * Event bus in-process berbasis `StateFlow` antara `SplitApkInstaller`/`SplitInstallResultReceiver`
 * dan layar UI (`ResultsActivity`/`CloneProgressActivity`). Aman terhadap lifecycle (tidak ada
 * referensi Context/Activity yang bocor dan tidak crash bila Activity sudah ditutup).
 */
object InstallProgressTracker {

    const val MSG_INSTALLING = "Memasang…"
    const val MSG_WAITING_CONFIRM = "Menunggu konfirmasi pemasangan…"
    const val MSG_SINGLE_APK_INTENT = "Memasang… Lanjutkan di dialog sistem."

    private val _state = MutableStateFlow<InstallProgressState>(InstallProgressState.Idle)
    val state: StateFlow<InstallProgressState> = _state.asStateFlow()

    fun onSplitInstallStarted(clonePackageName: String, cloneName: String) {
        _state.value = InstallProgressState.Installing(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = MSG_INSTALLING,
            isSingleApkIntent = false
        )
    }

    fun onSplitWaitingUserConfirmation(clonePackageName: String, cloneName: String) {
        _state.value = InstallProgressState.Installing(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = MSG_WAITING_CONFIRM,
            isSingleApkIntent = false
        )
    }

    fun onSplitInstallSucceeded(clonePackageName: String, cloneName: String, message: String) {
        _state.value = InstallProgressState.Succeeded(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = message
        )
    }

    fun onSplitInstallFailed(clonePackageName: String, cloneName: String, message: String) {
        _state.value = InstallProgressState.Failed(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = message
        )
    }

    fun onSingleApkInstallLaunched(clonePackageName: String, cloneName: String) {
        _state.value = InstallProgressState.Installing(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = MSG_SINGLE_APK_INTENT,
            isSingleApkIntent = true
        )
    }

    /**
     * Membersihkan status "Memasang…" milik jalur APK tunggal (`ACTION_VIEW`) saat pengguna
     * kembali ke aplikasi karena Android tidak memberi callback hasil untuk Intent installer.
     */
    fun onActivityResumed() {
        val cur = _state.value
        if (cur is InstallProgressState.Installing && cur.isSingleApkIntent) {
            _state.value = InstallProgressState.Idle
        }
    }

    fun consumeTerminalState() {
        val cur = _state.value
        if (cur is InstallProgressState.Succeeded || cur is InstallProgressState.Failed) {
            _state.value = InstallProgressState.Idle
        }
    }

    fun reset() {
        _state.value = InstallProgressState.Idle
    }
}
