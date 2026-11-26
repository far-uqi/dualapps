package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    // --- Fase 12.2: Authority berspasi & Permission non-prefixed ---

    @Test
    fun patchesSemicolonSpaceSeparatedAuthoritiesPreservingWhitespace() {
        val oldPkg = "com.example.katana"
        val newPkg = "com.example.katana.clone"
        val axml = buildCustomManifestAxml(
            packageName = oldPkg,
            elements = listOf(
                ElemSpec(
                    "provider",
                    listOf(
                        "name" to "androidx.core.content.FileProvider",
                        "authorities" to "$oldPkg.quicksilver.fileprovider; $oldPkg.apkfileprovider"
                    )
                )
            )
        )

        val patched = AxmlPatcher.patch(axml, oldPkg, newPkg)
        assertEquals(newPkg, AxmlPatcher.readPackageName(patched))

        val attrs = readElementAttrs(patched, "provider", "authorities")
        assertEquals(
            listOf("$newPkg.quicksilver.fileprovider; $newPkg.apkfileprovider"),
            attrs
        )
    }

    @Test
    fun patchesAuthoritiesWithTabsAndMultipleSpacesWhileKeepingForeignAuthorityUnchanged() {
        val oldPkg = "com.example.app"
        val newPkg = "com.example.app.clone"
        val axml = buildCustomManifestAxml(
            packageName = oldPkg,
            elements = listOf(
                ElemSpec(
                    "provider",
                    listOf(
                        "name" to "com.example.MultiProvider",
                        "authorities" to "  $oldPkg ; \t$oldPkg.files ;  com.other.vendor.provider \t"
                    )
                )
            )
        )

        val patched = AxmlPatcher.patch(axml, oldPkg, newPkg)
        val attrs = readElementAttrs(patched, "provider", "authorities")
        assertEquals(
            listOf("  $newPkg ; \t$newPkg.files ;  com.other.vendor.provider \t"),
            attrs
        )
    }

    @Test
    fun renamesDeclaredNonPrefixedPermissionAndUpdatesUsesAndProviderPermissionRefs() {
        val oldPkg = "com.example.katana"
        val newPkg = "com.example.katana.clone"
        val sharedPerm = "com.example.permission.prod.APP_COMM"
        val axml = buildCustomManifestAxml(
            packageName = oldPkg,
            elements = listOf(
                ElemSpec("permission", listOf("name" to sharedPerm)),
                ElemSpec("uses-permission", listOf("name" to sharedPerm)),
                ElemSpec(
                    "provider",
                    listOf(
                        "name" to "com.example.SecureProvider",
                        "authorities" to "$oldPkg.secure",
                        "readPermission" to sharedPerm,
                        "writePermission" to sharedPerm
                    )
                ),
                ElemSpec(
                    "receiver",
                    listOf(
                        "name" to "com.example.CommReceiver",
                        "permission" to sharedPerm
                    )
                )
            )
        )

        val patched = AxmlPatcher.patch(axml, oldPkg, newPkg)
        val expectedRenamed = "$newPkg.$sharedPerm"

        assertEquals(listOf(expectedRenamed), readElementAttrs(patched, "permission", "name"))
        assertEquals(listOf(expectedRenamed), readElementAttrs(patched, "uses-permission", "name"))
        assertEquals(listOf(expectedRenamed), readElementAttrs(patched, "provider", "readPermission"))
        assertEquals(listOf(expectedRenamed), readElementAttrs(patched, "provider", "writePermission"))
        assertEquals(listOf(expectedRenamed), readElementAttrs(patched, "receiver", "permission"))
    }

    @Test
    fun leavesForeignUsesPermissionUnchangedWhenNotDeclaredByTarget() {
        val oldPkg = "com.example.katana"
        val newPkg = "com.example.katana.clone"
        val foreignPerm = "com.example.orca.provider.ACCESS"
        val systemPerm = "android.permission.INTERNET"
        val axml = buildCustomManifestAxml(
            packageName = oldPkg,
            elements = listOf(
                ElemSpec("uses-permission", listOf("name" to foreignPerm)),
                ElemSpec("uses-permission", listOf("name" to systemPerm))
            )
        )

        val patched = AxmlPatcher.patch(axml, oldPkg, newPkg)
        assertEquals(
            listOf(foreignPerm, systemPerm),
            readElementAttrs(patched, "uses-permission", "name")
        )
    }

    @Test
    fun patchesFacebookLikeSyntheticManifestWithoutLeavingAnyConflictingNames() {
        val oldPkg = "com.example.katana"
        val newPkg = "com.example.katana.clone"
        val commPerm = "com.example.permission.prod.FB_APP_COMMUNICATION"
        val recvPerm = "com.example.receiver.permission.ACCESS"
        val prefixedPerm = "$oldPkg.provider.ACCESS"
        val foreignPerm = "com.example.orca.provider.ACCESS"

        val axml = buildCustomManifestAxml(
            packageName = oldPkg,
            elements = listOf(
                ElemSpec("permission", listOf("name" to prefixedPerm)),
                ElemSpec("permission", listOf("name" to commPerm)),
                ElemSpec("permission", listOf("name" to recvPerm)),
                ElemSpec("uses-permission", listOf("name" to prefixedPerm)),
                ElemSpec("uses-permission", listOf("name" to commPerm)),
                ElemSpec("uses-permission", listOf("name" to recvPerm)),
                ElemSpec("uses-permission", listOf("name" to foreignPerm)),
                ElemSpec(
                    "provider",
                    listOf(
                        "name" to "androidx.core.content.FileProvider",
                        "authorities" to "$oldPkg.quicksilver.fileprovider; $oldPkg.apkfileprovider",
                        "permission" to commPerm
                    )
                )
            )
        )

        val patched = AxmlPatcher.patch(axml, oldPkg, newPkg)
        assertEquals(newPkg, AxmlPatcher.readPackageName(patched))

        val declaredPerms = readElementAttrs(patched, "permission", "name")
        assertEquals(
            listOf("$newPkg.provider.ACCESS", "$newPkg.$commPerm", "$newPkg.$recvPerm"),
            declaredPerms
        )
        assertFalse(declaredPerms.contains(commPerm))
        assertFalse(declaredPerms.contains(recvPerm))
        assertFalse(declaredPerms.contains(prefixedPerm))

        val usesPerms = readElementAttrs(patched, "uses-permission", "name")
        assertEquals(
            listOf("$newPkg.provider.ACCESS", "$newPkg.$commPerm", "$newPkg.$recvPerm", foreignPerm),
            usesPerms
        )

        val providerAuths = readElementAttrs(patched, "provider", "authorities")
        assertEquals(listOf("$newPkg.quicksilver.fileprovider; $newPkg.apkfileprovider"), providerAuths)
        assertFalse(providerAuths.any { it.contains("$oldPkg.apkfileprovider") })

        assertEquals(listOf("$newPkg.$commPerm"), readElementAttrs(patched, "provider", "permission"))
    }

    @Test
    fun regressionNormalPrefixedManifestWithoutSpacesRemainsIdentical() {
        val oldPkg = "com.example.clean"
        val newPkg = "com.example.clean.clone"
        val axml = BinaryFixtureBuilder.buildManifestAxml(oldPkg, useUtf8 = true)

        val patched1 = AxmlPatcher.patch(axml, oldPkg, newPkg)
        val patched2 = AxmlPatcher.patch(axml, oldPkg, newPkg)
        assertArrayEquals(patched1, patched2)

        assertEquals(newPkg, AxmlPatcher.readPackageName(patched1))
        assertEquals(listOf("$newPkg.fileprovider"), readElementAttrs(patched1, "provider", "authorities"))
        assertEquals(listOf("$newPkg.CUSTOM_PERM"), readElementAttrs(patched1, "permission", "name"))
    }

    // --- Helper sintetis AXML & pembaca atribut untuk verifikasi tepat per elemen ---

    private data class ElemSpec(val tag: String, val attrs: List<Pair<String, String>>)

    private fun buildCustomManifestAxml(packageName: String, elements: List<ElemSpec>): ByteArray {
        val strings = mutableListOf(
            "http://schemas.android.com/apk/res/android", // 0
            "manifest",                                   // 1
            "package",                                    // 2
            packageName                                   // 3
        )
        fun idxOf(s: String): Int {
            val i = strings.indexOf(s)
            if (i >= 0) return i
            strings.add(s)
            return strings.lastIndex
        }

        val encodedElements = elements.map { el ->
            val tagIdx = idxOf(el.tag)
            val attrIndices = el.attrs.map { (k, v) -> idxOf(k) to idxOf(v) }
            tagIdx to attrIndices
        }

        val poolBytes = AxmlStringPool(
            strings = strings,
            isUtf8 = true,
            originalFlags = AxmlStringPool.UTF8_FLAG,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        ).toByteArray()

        val body = ByteArrayOutputStream()
        body.write(buildStartElem(-1, 1, listOf(Triple(-1, 2, 3))))
        for ((tagIdx, attrIndices) in encodedElements) {
            body.write(buildStartElem(-1, tagIdx, attrIndices.map { (k, v) -> Triple(0, k, v) }))
            body.write(buildEndElem(-1, tagIdx))
        }
        body.write(buildEndElem(-1, 1))

        val bodyBytes = body.toByteArray()
        val totalSize = 8 + poolBytes.size + bodyBytes.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(AxmlPatcher.RES_XML_TYPE.toShort())
        out.putShort(8)
        out.putInt(totalSize)
        out.put(poolBytes)
        out.put(bodyBytes)
        return out.array()
    }

    private fun buildStartElem(nsIdx: Int, nameIdx: Int, attrs: List<Triple<Int, Int, Int>>): ByteArray {
        val chunkSize = 16 + 20 + attrs.size * 20
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(AxmlPatcher.RES_XML_START_ELEMENT_TYPE.toShort())
        buf.putShort(16)
        buf.putInt(chunkSize)
        buf.putInt(1)
        buf.putInt(-1)
        buf.putInt(nsIdx)
        buf.putInt(nameIdx)
        buf.putShort(20)
        buf.putShort(20)
        buf.putShort(attrs.size.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        for ((aNs, aName, aVal) in attrs) {
            buf.putInt(aNs)
            buf.putInt(aName)
            buf.putInt(aVal)
            buf.putShort(8)
            buf.put(0)
            buf.put(0x03)
            buf.putInt(aVal)
        }
        return buf.array()
    }

    private fun buildEndElem(nsIdx: Int, nameIdx: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0103.toShort())
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(1)
        buf.putInt(-1)
        buf.putInt(nsIdx)
        buf.putInt(nameIdx)
        return buf.array()
    }

    private fun readElementAttrs(axmlBytes: ByteArray, targetTag: String, targetAttr: String): List<String> {
        val xmlHeaderSize = (axmlBytes[2].toInt() and 0xFF) or ((axmlBytes[3].toInt() and 0xFF) shl 8)
        val poolSize = readI32(axmlBytes, xmlHeaderSize + 4)
        val pool = AxmlStringPool.parse(axmlBytes, xmlHeaderSize)
        val values = mutableListOf<String>()
        var offset = xmlHeaderSize + poolSize
        while (offset + 8 <= axmlBytes.size) {
            val type = (axmlBytes[offset].toInt() and 0xFF) or ((axmlBytes[offset + 1].toInt() and 0xFF) shl 8)
            val headerSize = (axmlBytes[offset + 2].toInt() and 0xFF) or ((axmlBytes[offset + 3].toInt() and 0xFF) shl 8)
            val chunkSize = readI32(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) break
            if (type == AxmlPatcher.RES_XML_START_ELEMENT_TYPE) {
                val extStart = offset + headerSize
                val tagName = pool.strings.getOrNull(readI32(axmlBytes, extStart + 4))
                if (tagName == targetTag) {
                    val attrStart = (axmlBytes[extStart + 8].toInt() and 0xFF) or ((axmlBytes[extStart + 9].toInt() and 0xFF) shl 8)
                    val attrSize = (axmlBytes[extStart + 10].toInt() and 0xFF) or ((axmlBytes[extStart + 11].toInt() and 0xFF) shl 8)
                    val attrCount = (axmlBytes[extStart + 12].toInt() and 0xFF) or ((axmlBytes[extStart + 13].toInt() and 0xFF) shl 8)
                    val base = extStart + attrStart
                    for (i in 0 until attrCount) {
                        val off = base + i * attrSize
                        val attrName = pool.strings.getOrNull(readI32(axmlBytes, off + 4))
                        if (attrName == targetAttr) {
                            val rawIdx = readI32(axmlBytes, off + 8)
                            val dataIdx = readI32(axmlBytes, off + 16)
                            val strIdx = if (rawIdx >= 0) rawIdx else dataIdx
                            pool.strings.getOrNull(strIdx)?.let { values.add(it) }
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return values
    }

    private fun readI32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
