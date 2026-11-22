package com.dualapps.cloner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxmlStringPoolTest {

    @Test
    fun roundTripUtf8WithShortLongAndUnicodeStrings() {
        val longString = "a".repeat(210) // > 127 byte -> menguji encoding 2-byte length8
        val inputStrings = mutableListOf(
            "",
            "com.example.app",
            "Halo Dunia — Bahasa Indonesia 🚀",
            longString,
            "akhir"
        )
        val original = AxmlStringPool(
            strings = inputStrings.toMutableList(),
            isUtf8 = true,
            originalFlags = AxmlStringPool.UTF8_FLAG or AxmlStringPool.SORTED_FLAG,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        )

        val encoded = original.toByteArray()
        assertEquals(0, encoded.size % 4)

        val parsed = AxmlStringPool.parse(encoded)
        assertTrue(parsed.isUtf8)
        assertEquals(inputStrings, parsed.strings)

        // Ubah panjang string dan pastikan seluruh offset terhitung ulang dengan benar
        parsed.strings[1] = "com.example.app.clone.super.panjang.sekali"
        val reEncoded = parsed.toByteArray()
        val reParsed = AxmlStringPool.parse(reEncoded)
        assertEquals("com.example.app.clone.super.panjang.sekali", reParsed.strings[1])
        assertEquals(longString, reParsed.strings[3])
        assertEquals("akhir", reParsed.strings[4])
    }

    @Test
    fun roundTripUtf16WithLengthChangesAndUnicode() {
        val inputStrings = mutableListOf(
            "manifest",
            "com.dualapps.cloner",
            "Bahasa Indonesia ✓ 日本語",
            "x".repeat(300)
        )
        val original = AxmlStringPool(
            strings = inputStrings.toMutableList(),
            isUtf8 = false,
            originalFlags = 0,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        )

        val encoded = original.toByteArray()
        assertEquals(0, encoded.size % 4)

        val parsed = AxmlStringPool.parse(encoded)
        assertFalse(parsed.isUtf8)
        assertEquals(inputStrings, parsed.strings)

        // Ganti panjang string kedua lebih pendek dan ketiga lebih panjang
        parsed.strings[1] = "a.b"
        parsed.strings[2] = "Teks baru jauh lebih panjang dari sebelumnya untuk menguji offset UTF-16LE"
        val reParsed = AxmlStringPool.parse(parsed.toByteArray())
        assertEquals(parsed.strings, reParsed.strings)
    }
}
