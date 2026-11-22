package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ArscPatcherTest {

    @Test
    fun patchesPackageFieldWithoutTouchingGlobalStringPool() {
        val oldPkg = "com.dualapps.cloner"
        val newPkg = "com.dualapps.cloner.clone"
        val poolStrings = listOf("Dualapps", oldPkg, "Teks Resource Lain")
        val originalArsc = BinaryFixtureBuilder.buildArsc(oldPkg, poolStrings)

        assertEquals(listOf(oldPkg), ArscPatcher.readPackageNames(originalArsc))

        val patchedArsc = ArscPatcher.patch(originalArsc, oldPkg, newPkg)

        // Field ResTable_package terganti
        assertEquals(listOf(newPkg), ArscPatcher.readPackageNames(patchedArsc))
        // Panjang berkas tetap sama persis (fixed 128-char UTF-16LE slot)
        assertEquals(originalArsc.size, patchedArsc.size)

        // String pool global tidak disentuh (sesuai Bagian 3 langkah 3)
        val parsedPool = AxmlStringPool.parse(patchedArsc, offset = 12)
        assertEquals(poolStrings, parsedPool.strings)
    }

    @Test
    fun throwsPackageTooLongWhenExceeding127Chars() {
        val arsc = BinaryFixtureBuilder.buildArsc("com.example.a")
        val tooLongPkg = "com." + "a".repeat(124) // 128 karakter (> 127)
        try {
            ArscPatcher.patch(arsc, "com.example.a", tooLongPkg)
            fail("Seharusnya melempar PACKAGE_TOO_LONG")
        } catch (e: CloneException) {
            assertEquals(CloneError.PACKAGE_TOO_LONG, e.error)
        }
    }

    @Test
    fun throwsArscPatchFailedOnInvalidHeader() {
        try {
            ArscPatcher.patch(byteArrayOf(0x01, 0x02, 0x03, 0x04), "com.a", "com.b")
            fail("Seharusnya melempar ARSC_PATCH_FAILED")
        } catch (e: CloneException) {
            assertEquals(CloneError.ARSC_PATCH_FAILED, e.error)
        }
    }
}
