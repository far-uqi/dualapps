package com.dualapps.cloner.ui

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.util.InstallProgressTracker
import com.dualapps.cloner.util.SplitInstallOutcome
import com.dualapps.cloner.util.SplitInstallStatusMapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase15UiAndEnglishGuardTest {

    private fun resolveAppDir(): File {
        val direct = File("src/main")
        if (direct.exists()) return File(".")
        val nested = File("app/src/main")
        if (nested.exists()) return File("app")
        error("Cannot locate app/src/main from working dir: ${File(".").absolutePath}")
    }

    @Test
    fun installedStatusIsDisplayedAsTopTabOutsideCardAndNotAsInnerBadge() {
        val appDir = resolveAppDir()
        val itemXml = File(appDir, "src/main/res/layout/item_cloned_apk.xml").readText(Charsets.UTF_8)

        // Tab "Installed ✓" harus ada di atas kartu dengan bentuk pill berbeda dari folder tab
        assertTrue(
            "item_cloned_apk.xml must define tvInstalledTab above the card",
            itemXml.contains("android:id=\"@+id/tvInstalledTab\"")
        )
        assertTrue(
            "tvInstalledTab must use distinct pill drawable bg_neo_tab_installed_pill",
            itemXml.contains("android:background=\"@drawable/bg_neo_tab_installed_pill\"")
        )
        // Badge lama di dalam kartu tidak boleh ada lagi
        assertFalse(
            "Old inner tvInstalledBadge must be removed from item_cloned_apk.xml",
            itemXml.contains("android:id=\"@+id/tvInstalledBadge\"")
        )
        // Posisi tvInstalledTab harus sebelum kartu utama (bg_neo_card)
        val tabIndex = itemXml.indexOf("@+id/tvInstalledTab")
        val cardIndex = itemXml.indexOf("@drawable/bg_neo_card")
        assertTrue("tvInstalledTab must appear above bg_neo_card", tabIndex in 0 until cardIndex)

        // Drawable pill harus membulat penuh (radius 999dp) + border 2.5dp + shadow 4dp
        val pillXml = File(appDir, "src/main/res/drawable/bg_neo_tab_installed_pill.xml").readText(Charsets.UTF_8)
        assertTrue(pillXml.contains("android:radius=\"999dp\""))
        assertTrue(pillXml.contains("android:width=\"2.5dp\""))
        assertTrue(pillXml.contains("android:left=\"4dp\""))
    }

    @Test
    fun openButtonUsesStandardNeoBrutalismButtonDrawableMatchingInstallShareDelete() {
        val appDir = resolveAppDir()
        val itemXml = File(appDir, "src/main/res/layout/item_cloned_apk.xml").readText(Charsets.UTF_8)
        val progressXml = File(appDir, "src/main/res/layout/activity_clone_progress.xml").readText(Charsets.UTF_8)
        val tealBtnXml = File(appDir, "src/main/res/drawable/bg_neo_button_teal.xml").readText(Charsets.UTF_8)

        // Tombol OPEN di kedua layar memakai bg_neo_button_teal (bukan bg_neo_folder_tab_teal!)
        assertTrue(itemXml.contains("android:id=\"@+id/btnOpen\""))
        assertTrue(itemXml.contains("android:background=\"@drawable/bg_neo_button_teal\""))
        assertTrue(progressXml.contains("android:id=\"@+id/btnOpenApp\""))
        assertTrue(progressXml.contains("android:background=\"@drawable/bg_neo_button_teal\""))

        // Drawable bg_neo_button_teal identik struktur dengan bg_neo_button_yellow (radius 12dp 4 sudut, border 2.5dp, shadow 4dp)
        assertTrue(tealBtnXml.contains("android:radius=\"12dp\""))
        assertTrue(tealBtnXml.contains("android:width=\"2.5dp\""))
        assertTrue(tealBtnXml.contains("android:left=\"4dp\""))
        assertTrue(tealBtnXml.contains("android:top=\"4dp\""))
        assertTrue(tealBtnXml.contains("android:bottom=\"4dp\""))
        assertTrue(tealBtnXml.contains("android:right=\"4dp\""))
    }

    @Test
    fun allUserFacingStringsInStringsXmlAndErrorMessagesAreEnglishOnly() {
        val appDir = resolveAppDir()
        val stringsXml = File(appDir, "src/main/res/values/strings.xml").readText(Charsets.UTF_8)

        val forbiddenIndonesianWords = listOf(
            "yang", "dan", "untuk", "sudah", "pasang", "hapus", "bagikan", "memuat",
            "memasang", "terpasang", "versi", "terbaru", "buka", "perbarui", "hasil",
            "berkas", "aplikasi", "gagal", "berhasil", "tidak", "dibatalkan", "menunggu",
            "konfirmasi", "pemasangan", "peringatan", "terlalu", "pengaturan", "kembali"
        )
        val wordPattern = Regex(
            "\\b(${forbiddenIndonesianWords.joinToString("|")})\\b",
            RegexOption.IGNORE_CASE
        )

        val stringEntryRegex = Regex("<string\\s+name=\"([^\"]+)\">([\\s\\S]*?)</string>")
        val violations = mutableListOf<String>()

        stringEntryRegex.findAll(stringsXml).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            val found = wordPattern.find(value)
            if (found != null) {
                violations.add("strings.xml[$key] contains Indonesian word '${found.value}': $value")
            }
        }

        // Periksa juga semua CloneError.userMessage, InstallProgressTracker, dan SplitInstallStatusMapper
        for (err in CloneError.entries) {
            val found = wordPattern.find(err.userMessage)
            if (found != null) {
                violations.add("CloneError.${err.name} contains Indonesian word '${found.value}': ${err.userMessage}")
            }
        }
        val trackerMessages = listOf(
            InstallProgressTracker.MSG_INSTALLING,
            InstallProgressTracker.MSG_WAITING_CONFIRM,
            InstallProgressTracker.MSG_INSTALLED,
            InstallProgressTracker.MSG_SINGLE_APK_INTENT
        )
        for (msg in trackerMessages) {
            val found = wordPattern.find(msg)
            if (found != null) {
                violations.add("InstallProgressTracker message contains Indonesian word '${found.value}': $msg")
            }
        }
        val statusCodes = listOf(
            SplitInstallStatusMapper.STATUS_SUCCESS,
            SplitInstallStatusMapper.STATUS_FAILURE_ABORTED,
            SplitInstallStatusMapper.STATUS_FAILURE_BLOCKED,
            SplitInstallStatusMapper.STATUS_FAILURE_CONFLICT,
            SplitInstallStatusMapper.STATUS_FAILURE_STORAGE,
            SplitInstallStatusMapper.STATUS_FAILURE_INCOMPATIBLE,
            SplitInstallStatusMapper.STATUS_FAILURE_INVALID
        )
        for (code in statusCodes) {
            val msg = when (val out = SplitInstallStatusMapper.mapStatus(code, null)) {
                is SplitInstallOutcome.Success -> out.message
                is SplitInstallOutcome.Failure -> out.message
                SplitInstallOutcome.PendingUserAction -> ""
            }
            val found = wordPattern.find(msg)
            if (found != null) {
                violations.add("SplitInstallStatusMapper($code) contains Indonesian word '${found.value}': $msg")
            }
        }

        assertTrue(
            "Found remaining Indonesian words in user-facing UI strings:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
