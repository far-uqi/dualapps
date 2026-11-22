package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AxmlPatcherTest {

    @Test
    fun patchesUtf8ManifestPackageAuthoritiesPermissionAndRelativeClass() {
        val oldPkg = "com.dualapps.cloner"
        val newPkg = "com.dualapps.cloner.clone"
        val originalAxml = BinaryFixtureBuilder.buildManifestAxml(oldPkg, useUtf8 = true)

        assertEquals(oldPkg, AxmlPatcher.readPackageName(originalAxml))

        val patchedAxml = AxmlPatcher.patch(originalAxml, oldPkg, newPkg)

        // Wajib bisa diparse ulang dan package sama dengan package clone
        assertEquals(newPkg, AxmlPatcher.readPackageName(patchedAxml))

        // Periksa isi StringPool hasil patch
        val pool = AxmlStringPool.parse(patchedAxml, offset = 8)
        assertTrue(pool.strings.contains(newPkg))
        assertFalse(pool.strings.contains(oldPkg))
        assertTrue(pool.strings.contains("$newPkg.fileprovider"))
        assertTrue(pool.strings.contains("$newPkg.CUSTOM_PERM"))
        // FQN kelas Activity tetap menunjuk kelas asli (karena DEX tidak diubah)
        assertTrue(pool.strings.contains("$oldPkg.MainActivity"))
        // Nama kelas relatif `.App` dinormalisasi ke FQN asli
        assertTrue(pool.strings.contains("$oldPkg.App"))
    }

    @Test
    fun patchesUtf16ManifestPackageAndReParses() {
        val oldPkg = "com.example.notes"
        val newPkg = "com.example.notes.clone2"
        val originalAxml = BinaryFixtureBuilder.buildManifestAxml(oldPkg, useUtf8 = false)

        val patchedAxml = AxmlPatcher.patch(originalAxml, oldPkg, newPkg)
        assertEquals(newPkg, AxmlPatcher.readPackageName(patchedAxml))

        val pool = AxmlStringPool.parse(patchedAxml, offset = 8)
        assertFalse(pool.isUtf8)
        assertTrue(pool.strings.contains("$newPkg.fileprovider"))
    }

    @Test
    fun throwsAxmlParseFailedOnCorruptedHeader() {
        try {
            AxmlPatcher.patch(byteArrayOf(0x00, 0x01, 0x02, 0x03), "com.a", "com.b")
            fail("Seharusnya melempar CloneException(AXML_PARSE_FAILED)")
        } catch (e: CloneException) {
            assertEquals(CloneError.AXML_PARSE_FAILED, e.error)
        }
    }

    @Test
    fun preservesResourceMapIndicesWhenAppendingNewStrings() {
        val oldPkg = "com.example.demo"
        val newPkg = "com.example.demo.clone"
        val baseAxml = BinaryFixtureBuilder.buildManifestAxml(oldPkg, useUtf8 = true)
        val originalPool = AxmlStringPool.parse(baseAxml, offset = 8)
        val originalCount = originalPool.strings.size

        val patched = AxmlPatcher.patch(baseAxml, oldPkg, newPkg)
        val patchedPool = AxmlStringPool.parse(patched, offset = 8)

        // Indeks string lama (0..originalCount-1) tidak boleh bergeser agar RES_XML_RESOURCE_MAP tetap sinkron
        assertTrue(patchedPool.strings.size >= originalCount)
        assertEquals(originalPool.strings[0], patchedPool.strings[0]) // namespace android
        assertEquals(originalPool.strings[1], patchedPool.strings[1]) // "manifest"
        assertEquals(originalPool.strings[2], patchedPool.strings[2]) // "package"
        assertEquals(newPkg, patchedPool.strings[3])                  // exact replace di tempat
        assertEquals(newPkg, AxmlPatcher.readPackageName(patched))
    }
}
