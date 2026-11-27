package com.dualapps.cloner.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asal pemicu pemasangan untuk menentukan navigasi otomatis pasca-pasang (Fase 14).
 */
enum class InstallOrigin {
    CLONE_PROGRESS,
    RESULTS
}

/**
 * Fase rinci pemasangan yang sedang berlangsung agar indikator UI selalu akurat.
 */
enum class InstallPhase {
    PREPARING,
    WAITING_CONFIRMATION,
    INSTALLING
}

/**
 * State progres pemasangan yang diamati oleh `ResultsActivity` dan `CloneProgressActivity`
 * agar layar selalu mencerminkan fase pemasangan yang sebenarnya.
 */
sealed class InstallProgressState {
    data object Idle : InstallProgressState()

    data class Installing(
        val clonePackageName: String,
        val cloneName: String,
        val message: String,
        val isSingleApkIntent: Boolean = false,
        val phase: InstallPhase = InstallPhase.INSTALLING,
        val origin: InstallOrigin = InstallOrigin.RESULTS,
        val hasPausedForConfirmation: Boolean = false
    ) : InstallProgressState()

    data class Succeeded(
        val clonePackageName: String,
        val cloneName: String,
        val message: String,
        val origin: InstallOrigin = InstallOrigin.RESULTS
    ) : InstallProgressState()

    data class Failed(
        val clonePackageName: String,
        val cloneName: String,
        val message: String,
        val origin: InstallOrigin = InstallOrigin.RESULTS
    ) : InstallProgressState()
}

/**
 * State tampilan daftar Hasil Clone (Fase 14) agar layar Hasil tidak pernah menampilkan
 * keadaan kosong sebelum pemuatan dari `ClonedApksRepository` selesai.
 */
sealed class ResultsLoadState {
    data object Loading : ResultsLoadState()
    data object Empty : ResultsLoadState()
    data class Content(val count: Int) : ResultsLoadState()
}

/**
 * Event bus in-process berbasis `StateFlow` antara `SplitApkInstaller`/`SplitInstallResultReceiver`
 * dan layar UI (`ResultsActivity`/`CloneProgressActivity`). Aman terhadap lifecycle (tidak ada
 * referensi Context/Activity yang bocor dan tidak crash bila Activity sudah ditutup).
 */
object InstallProgressTracker {

    const val MSG_INSTALLING = "Installing…"
    const val MSG_WAITING_CONFIRM = "Waiting for install confirmation…"
    const val MSG_INSTALLED = "Installed ✓"
    const val MSG_SINGLE_APK_INTENT = "Installing… Continue in the system dialog."

    private val _state = MutableStateFlow<InstallProgressState>(InstallProgressState.Idle)
    val state: StateFlow<InstallProgressState> = _state.asStateFlow()

    private var activeOrigin: InstallOrigin = InstallOrigin.RESULTS

    fun onSplitInstallStarted(
        clonePackageName: String,
        cloneName: String,
        origin: InstallOrigin = InstallOrigin.RESULTS
    ) {
        activeOrigin = origin
        _state.value = InstallProgressState.Installing(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = MSG_INSTALLING,
            isSingleApkIntent = false,
            phase = InstallPhase.PREPARING,
            origin = origin,
            hasPausedForConfirmation = false
        )
    }

    fun onSplitWaitingUserConfirmation(clonePackageName: String, cloneName: String) {
        val currentOrigin = currentOriginOrDefault()
        _state.value = InstallProgressState.Installing(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = MSG_WAITING_CONFIRM,
            isSingleApkIntent = false,
            phase = InstallPhase.WAITING_CONFIRMATION,
            origin = currentOrigin,
            hasPausedForConfirmation = false
        )
    }

    /**
     * Dipanggil saat pengguna sudah mengonfirmasi dialog sistem (terdeteksi lewat kembalinya
     * Activity dari dialog konfirmasi atau lewat `PackageInstaller.SessionCallback`).
     */
    fun onSplitInstallConfirmed(clonePackageName: String? = null, cloneName: String? = null) {
        val cur = _state.value
        if (cur is InstallProgressState.Installing &&
            !cur.isSingleApkIntent &&
            cur.phase == InstallPhase.WAITING_CONFIRMATION
        ) {
            _state.value = cur.copy(
                clonePackageName = clonePackageName?.takeIf { it.isNotBlank() } ?: cur.clonePackageName,
                cloneName = cloneName?.takeIf { it.isNotBlank() } ?: cur.cloneName,
                message = MSG_INSTALLING,
                phase = InstallPhase.INSTALLING,
                hasPausedForConfirmation = false
            )
        }
    }

    fun onSplitInstallSucceeded(clonePackageName: String, cloneName: String, message: String) {
        val currentOrigin = currentOriginOrDefault()
        _state.value = InstallProgressState.Succeeded(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = message.ifBlank { MSG_INSTALLED },
            origin = currentOrigin
        )
    }

    fun onSplitInstallFailed(clonePackageName: String, cloneName: String, message: String) {
        val currentOrigin = currentOriginOrDefault()
        _state.value = InstallProgressState.Failed(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = message,
            origin = currentOrigin
        )
    }

    fun onSingleApkInstallLaunched(
        clonePackageName: String,
        cloneName: String,
        origin: InstallOrigin = InstallOrigin.RESULTS
    ) {
        activeOrigin = origin
        _state.value = InstallProgressState.Installing(
            clonePackageName = clonePackageName,
            cloneName = cloneName,
            message = MSG_SINGLE_APK_INTENT,
            isSingleApkIntent = true,
            phase = InstallPhase.WAITING_CONFIRMATION,
            origin = origin,
            hasPausedForConfirmation = false
        )
    }

    /**
     * Dipanggil saat Activity memasuki `onPause` (mis. karena dialog konfirmasi sistem muncul
     * di atas layar Dualapps). Menandai bahwa dialog benar-benar sempat tampil sebelum `onResume`.
     */
    fun onActivityPaused() {
        val cur = _state.value
        if (cur is InstallProgressState.Installing &&
            !cur.isSingleApkIntent &&
            cur.phase == InstallPhase.WAITING_CONFIRMATION &&
            !cur.hasPausedForConfirmation
        ) {
            _state.value = cur.copy(hasPausedForConfirmation = true)
        }
    }

    /**
     * - Jalur APK tunggal (`ACTION_VIEW`): membersihkan status saat kembali ke aplikasi.
     * - Jalur Split APK (`WAITING_CONFIRMATION` setelah sempat `onPause`): segera beralih dari
     *   "Menunggu konfirmasi pemasangan…" ke "Memasang…" saat pengguna kembali setelah mengetuk
     *   INSTALL di dialog sistem, karena Android tidak mengirim broadcast perantara sebelum selesai.
     */
    fun onActivityResumed() {
        val cur = _state.value
        if (cur is InstallProgressState.Installing) {
            when {
                cur.isSingleApkIntent -> {
                    _state.value = InstallProgressState.Idle
                }
                cur.phase == InstallPhase.WAITING_CONFIRMATION && cur.hasPausedForConfirmation -> {
                    _state.value = cur.copy(
                        message = MSG_INSTALLING,
                        phase = InstallPhase.INSTALLING,
                        hasPausedForConfirmation = false
                    )
                }
            }
        }
    }

    /**
     * Fase 16: navigasi otomatis ke Clone Results dibatalkan; setelah pemasangan berhasil,
     * layar proses tetap menampilkan status "Installed ✓" + tombol OPEN + VIEW RESULTS manual.
     */
    fun shouldAutoNavigateToResults(origin: InstallOrigin): Boolean = false

    fun resolveResultsLoadState(isLoading: Boolean, itemCount: Int): ResultsLoadState {
        return when {
            isLoading -> ResultsLoadState.Loading
            itemCount <= 0 -> ResultsLoadState.Empty
            else -> ResultsLoadState.Content(itemCount)
        }
    }

    fun consumeTerminalState() {
        val cur = _state.value
        if (cur is InstallProgressState.Succeeded || cur is InstallProgressState.Failed) {
            _state.value = InstallProgressState.Idle
        }
    }

    fun reset() {
        activeOrigin = InstallOrigin.RESULTS
        _state.value = InstallProgressState.Idle
    }

    private fun currentOriginOrDefault(): InstallOrigin {
        return when (val cur = _state.value) {
            is InstallProgressState.Installing -> cur.origin
            is InstallProgressState.Succeeded -> cur.origin
            is InstallProgressState.Failed -> cur.origin
            InstallProgressState.Idle -> activeOrigin
        }
    }
}
