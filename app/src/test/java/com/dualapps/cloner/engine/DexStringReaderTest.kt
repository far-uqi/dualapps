package com.dualapps.cloner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DexStringReaderTest {

    @Test
    fun readsAllStringsAndContentUrisFromSyntheticDex() {
        val expectedStrings = listOf(
            "Lcom/example/App;",
            "content://com.facebook.katana.ClientMessagePushDedupInfoProvider/mutestatus",
            "content://com.facebook.orca.ClientMessagePushDedupInfoProvider/userfriendship",
            "https://example.com/normal",
            "plain_ascii_string"
        )
        val dexBytes = buildSyntheticDex(expectedStrings, version = "035")

        val all = DexStringReader.readStrings(dexBytes)
        assertEquals(expectedStrings, all)

        val contentUris = DexStringReader.readStringsWithPrefix(dexBytes, "content://")
        assertEquals(
            listOf(
                "content://com.facebook.katana.ClientMessagePushDedupInfoProvider/mutestatus",
                "content://com.facebook.orca.ClientMessagePushDedupInfoProvider/userfriendship"
            ),
            contentUris
        )
    }

    @Test
    fun returnsEmptySafelyOnCorruptOrInvalidMagicDex() {
        assertTrue(DexStringReader.readStrings(ByteArray(0)).isEmpty())
        assertTrue(DexStringReader.readStrings("not a dex file at all".toByteArray()).isEmpty())

        // Magic salah (versi 999)
        val badMagic = buildSyntheticDex(listOf("content://com.a/b"), version = "035").copyOf()
        badMagic[4] = '9'.code.toByte()
        assertTrue(DexStringReader.readStrings(badMagic).isEmpty())

        // Tabel string_ids terpotong
        val truncated = buildSyntheticDex(listOf("content://com.a/b"), version = "039").copyOf(114)
        assertTrue(DexStringReader.readStrings(truncated).isEmpty())
    }

    companion object {
        /**
         * Membangun berkas DEX sintetis minimal yang valid (header 112B + `string_ids` + `string_data`)
         * untuk pengujian JVM murni tanpa APK eksternal.
         */
        fun buildSyntheticDex(strings: List<String>, version: String = "035"): ByteArray {
            val headerSize = 112
            val stringIdsOff = headerSize
            val stringDataStart = stringIdsOff + strings.size * 4

            val dataStream = ByteArrayOutputStream()
            val offsets = IntArray(strings.size)
            for (i in strings.indices) {
                offsets[i] = stringDataStart + dataStream.size()
                val s = strings[i]
                // Tulis ULEB128 utf16_size
                var len = s.length
                while (true) {
                    val b = len and 0x7F
                    len = len ushr 7
                    if (len != 0) {
                        dataStream.write(b or 0x80)
                    } else {
                        dataStream.write(b)
                        break
                    }
                }
                dataStream.write(s.toByteArray(Charsets.UTF_8))
                dataStream.write(0) // null terminator
            }
            val dataBytes = dataStream.toByteArray()
            val totalSize = stringDataStart + dataBytes.size

            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            // Magic "dex\n<ver>\0"
            buf.put("dex\n$version\u0000".toByteArray(Charsets.US_ASCII))
            // Checksum (4) + SHA-1 signature (20) = 24 bytes
            buf.put(ByteArray(24))
            buf.putInt(totalSize)      // file_size (offset 32)
            buf.putInt(headerSize)     // header_size (offset 36)
            buf.putInt(0x12345678)     // endian_tag (offset 40)
            buf.putInt(0)              // link_size (44)
            buf.putInt(0)              // link_off (48)
            buf.putInt(0)              // map_off (52)
            buf.putInt(strings.size)   // string_ids_size (56)
            buf.putInt(stringIdsOff)   // string_ids_off (60)
            // Sisa header sampai offset 112 (48 byte)
            buf.put(ByteArray(headerSize - 64))
            for (off in offsets) {
                buf.putInt(off)
            }
            buf.put(dataBytes)
            return buf.array()
        }
    }
}
