package com.dualapps.cloner.ui

import com.dualapps.cloner.util.InstallOrigin
import com.dualapps.cloner.util.InstallProgressState
import com.dualapps.cloner.util.InstallProgressTracker
import com.dualapps.cloner.util.ResultsLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Phase16TabAndNoAutoNavTest {

    @Before
    fun resetTracker() {
        InstallProgressTracker.reset()
    }

    private fun resolveAppDir(): File {
        val direct = File("src/main")
        if (direct.exists()) return File(".")
        val nested = File("app/src/main")
        if (nested.exists()) return File("app")
        error("Cannot locate app/src/main from working dir: ${File(".").absolutePath}")
    }

    @Test
    fun installedTabUsesSameFolderTabShapeAsCloneResultAndSitsNextToItWithoutFloatingGap() {
        val appDir = resolveAppDir()
        val itemXml = File(appDir, "src/main/res/layout/item_cloned_apk.xml").readText(Charsets.UTF_8)
        val purpleTab = File(appDir, "src/main/res/drawable/bg_neo_folder_tab_purple.xml").readText(Charsets.UTF_8)
        val tealTab = File(appDir, "src/main/res/drawable/bg_neo_folder_tab_teal.xml").readText(Charsets.UTF_8)

        // Drawable pill lama sudah dihapus agar hanya 1 bentuk tab yang hidup
        assertFalse(File(appDir, "src/main/res/drawable/bg_neo_tab_installed_pill.xml").exists())

        // Bentuk bg_neo_folder_tab_teal identik dengan bg_neo_folder_tab_purple (hanya beda warna fill)
        assertEquals(
            purpleTab.replace("@color/accent_purple", "@color/accent_teal").replace("\r\n", "\n").trim(),
            tealTab.replace("\r\n", "\n").trim()
        )

        // Kedua tab berada dalam satu baris (rowCardTabs) yang menempel langsung ke kartu via marginBottom="-2.5dp"
        assertTrue(itemXml.contains("android:id=\"@+id/rowCardTabs\""))
        assertTrue(itemXml.contains("android:layout_marginBottom=\"-2.5dp\""))
        assertTrue(itemXml.contains("android:background=\"@drawable/bg_neo_folder_tab_purple\""))
        assertTrue(itemXml.contains("android:background=\"@drawable/bg_neo_folder_tab_teal\""))
        // Tidak ada View spacer weight=1 di antara kedua tab — keduanya bersebelahan langsung
        val rowBlock = itemXml.substringAfter("android:id=\"@+id/rowCardTabs\"").substringBefore("</LinearLayout>")
        assertFalse("Tabs must sit side by side without a weight=1 spacer", rowBlock.contains("layout_weight=\"1\""))
    }

    @Test
    fun compatibilityWarningTabUsesSameFolderTabShapeWithYellowFill() {
        val appDir = resolveAppDir()
        val progressXml = File(appDir, "src/main/res/layout/activity_clone_progress.xml").readText(Charsets.UTF_8)
        val purpleTab = File(appDir, "src/main/res/drawable/bg_neo_folder_tab_purple.xml").readText(Charsets.UTF_8)
        val yellowTab = File(appDir, "src/main/res/drawable/bg_neo_folder_tab_yellow.xml").readText(Charsets.UTF_8)

        // Bentuk bg_neo_folder_tab_yellow identik dengan bg_neo_folder_tab_purple (hanya beda warna fill)
        assertEquals(
            purpleTab.replace("@color/accent_purple", "@color/accent_yellow").replace("\r\n", "\n").trim(),
            yellowTab.replace("\r\n", "\n").trim()
        )
        assertTrue(progressXml.contains("android:id=\"@+id/tvWarningTab\""))
        assertTrue(progressXml.contains("android:background=\"@drawable/bg_neo_folder_tab_yellow\""))
    }

    @Test
    fun noAutoNavigationAfterInstallSuccessForAnyOrigin() {
        InstallProgressTracker.onSplitInstallStarted("com.facebook.aura.clone2", "Muse 2", InstallOrigin.CLONE_PROGRESS)
        InstallProgressTracker.onSplitInstallSucceeded(
            "com.facebook.aura.clone2",
            "Muse 2",
            InstallProgressTracker.MSG_INSTALLED
        )
        val state = InstallProgressTracker.state.value as InstallProgressState.Succeeded
        assertEquals(InstallProgressTracker.MSG_INSTALLED, state.message)
        assertFalse(InstallProgressTracker.shouldAutoNavigateToResults(state.origin))
        assertFalse(InstallProgressTracker.shouldAutoNavigateToResults(InstallOrigin.RESULTS))
    }

    @Test
    fun viewResultsButtonExistsOnCloneProgressLayoutForManualNavigation() {
        val appDir = resolveAppDir()
        val progressXml = File(appDir, "src/main/res/layout/activity_clone_progress.xml").readText(Charsets.UTF_8)
        val progressKt = File(appDir, "src/main/java/com/dualapps/cloner/ui/CloneProgressActivity.kt").readText(Charsets.UTF_8)

        assertTrue(progressXml.contains("android:id=\"@+id/btnViewResults\""))
        assertTrue(progressXml.contains("android:text=\"@string/action_view_results\""))
        assertTrue(progressXml.contains("android:background=\"@drawable/bg_neo_button_white\""))
        assertTrue(progressKt.contains("binding.btnViewResults.setOnClickListener"))
        assertTrue(progressKt.contains("ResultsActivity.createIntent(this)"))
        // Tidak boleh ada lagi delay(1500L) navigasi otomatis di CloneProgressActivity
        assertFalse(progressKt.contains("delay(1500L)"))
    }

    @Test
    fun resultsLoadStateNeverShowsLoadingAndContentSimultaneously() {
        val appDir = resolveAppDir()
        val resultsKt = File(appDir, "src/main/java/com/dualapps/cloner/ui/ResultsActivity.kt").readText(Charsets.UTF_8)

        // State model hanya bisa satu state pada satu waktu (Loading / Empty / Content)
        assertEquals(ResultsLoadState.Loading, InstallProgressTracker.resolveResultsLoadState(isLoading = true, itemCount = 5))
        assertEquals(ResultsLoadState.Content(5), InstallProgressTracker.resolveResultsLoadState(isLoading = false, itemCount = 5))
        assertEquals(ResultsLoadState.Empty, InstallProgressTracker.resolveResultsLoadState(isLoading = false, itemCount = 0))

        // Di ResultsActivity, layoutLoading di-GONE sebelum adapter.submitList dipanggil
        val hideLoadingIdx = resultsKt.indexOf("binding.layoutLoading.visibility = View.GONE")
        val submitIdx = resultsKt.indexOf("adapter.submitList(items)")
        assertTrue("layoutLoading must be hidden before submitList", hideLoadingIdx in 0 until submitIdx)
    }
}
