package com.dualapps.cloner.util

import com.dualapps.cloner.engine.CloneUpdateStatus
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.ui.ClonedResultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostCloneAndInstallUxTest {

    @Before
    fun resetTracker() {
        InstallProgressTracker.reset()
    }

    @Test
    fun transitionsFromWaitingConfirmationToInstallingOnPauseResumeAndSessionCallbackThenSuccess() {
        InstallProgressTracker.onSplitInstallStarted(
            clonePackageName = "com.openai.chatgpt.clone",
            cloneName = "ChatGPT 2",
            origin = InstallOrigin.CLONE_PROGRESS
        )
        val preparing = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallPhase.PREPARING, preparing.phase)
        assertEquals(InstallProgressTracker.MSG_INSTALLING, preparing.message)

        // Dialog konfirmasi sistem muncul
        InstallProgressTracker.onSplitWaitingUserConfirmation("com.openai.chatgpt.clone", "ChatGPT 2")
        val waiting = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallPhase.WAITING_CONFIRMATION, waiting.phase)
        assertEquals(InstallProgressTracker.MSG_WAITING_CONFIRM, waiting.message)
        assertFalse(waiting.hasPausedForConfirmation)

        // onResume sebelum sempat onPause tidak boleh prematur mengubah fase
        InstallProgressTracker.onActivityResumed()
        val stillWaiting = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallPhase.WAITING_CONFIRMATION, stillWaiting.phase)

        // Dialog sistem mengambil fokus (onPause), lalu user ketuk INSTALL dan kembali (onResume)
        InstallProgressTracker.onActivityPaused()
        InstallProgressTracker.onActivityResumed()
        val installing = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallPhase.INSTALLING, installing.phase)
        assertEquals(InstallProgressTracker.MSG_INSTALLING, installing.message)

        // Pemasangan selesai -> Succeeded (spinner berhenti di UI)
        InstallProgressTracker.onSplitInstallSucceeded(
            "com.openai.chatgpt.clone",
            "ChatGPT 2",
            InstallProgressTracker.MSG_INSTALLED
        )
        val succeeded = InstallProgressTracker.state.value as InstallProgressState.Succeeded
        assertEquals(InstallProgressTracker.MSG_INSTALLED, succeeded.message)
        assertEquals(InstallOrigin.CLONE_PROGRESS, succeeded.origin)

        // Jalur alternatif via SessionCallback (onSplitInstallConfirmed) juga memindahkan WAITING_CONFIRMATION -> INSTALLING
        InstallProgressTracker.reset()
        InstallProgressTracker.onSplitInstallStarted("com.a.clone", "App A", InstallOrigin.RESULTS)
        InstallProgressTracker.onSplitWaitingUserConfirmation("com.a.clone", "App A")
        InstallProgressTracker.onSplitInstallConfirmed("com.a.clone", "App A")
        val confirmed = InstallProgressTracker.state.value as InstallProgressState.Installing
        assertEquals(InstallPhase.INSTALLING, confirmed.phase)
        assertEquals(InstallProgressTracker.MSG_INSTALLING, confirmed.message)

        // Gagal/dibatalkan berhenti pada state Failed (bukan Installing)
        InstallProgressTracker.onSplitInstallFailed("com.a.clone", "App A", "Pemasangan dibatalkan.")
        val failed = InstallProgressTracker.state.value as InstallProgressState.Failed
        assertEquals("Pemasangan dibatalkan.", failed.message)
        InstallProgressTracker.consumeTerminalState()
        assertEquals(InstallProgressState.Idle, InstallProgressTracker.state.value)
    }

    @Test
    fun installedResultItemExposesInstalledStatusForOpenButton() {
        val rec = ClonedApkRecord(
            id = "rec-1",
            cloneName = "ChatGPT 2",
            clonePackageName = "com.openai.chatgpt.clone",
            originalPackageName = "com.openai.chatgpt",
            originalAppName = "ChatGPT",
            apkPath = "/tmp/base.apk",
            apkSizeBytes = 100L,
            createdAtMillis = 1L
        )
        val installedItem = ClonedResultItem(
            record = rec,
            updateStatus = CloneUpdateStatus.UpToDate,
            installedSourceApp = null,
            isCloneInstalled = true
        )
        val uninstalledItem = installedItem.copy(isCloneInstalled = false)
        assertTrue(installedItem.isCloneInstalled)
        assertFalse(uninstalledItem.isCloneInstalled)
    }

    @Test
    fun autoNavigateToResultsOnlyWhenInstallTriggeredFromCloneProgress() {
        assertTrue(InstallProgressTracker.shouldAutoNavigateToResults(InstallOrigin.CLONE_PROGRESS))
        assertFalse(InstallProgressTracker.shouldAutoNavigateToResults(InstallOrigin.RESULTS))
    }

    @Test
    fun resultsLoadStateStartsInLoadingBeforeReturningEmptyOrContent() {
        assertEquals(
            ResultsLoadState.Loading,
            InstallProgressTracker.resolveResultsLoadState(isLoading = true, itemCount = 0)
        )
        assertEquals(
            ResultsLoadState.Empty,
            InstallProgressTracker.resolveResultsLoadState(isLoading = false, itemCount = 0)
        )
        assertEquals(
            ResultsLoadState.Content(3),
            InstallProgressTracker.resolveResultsLoadState(isLoading = false, itemCount = 3)
        )
    }
}
