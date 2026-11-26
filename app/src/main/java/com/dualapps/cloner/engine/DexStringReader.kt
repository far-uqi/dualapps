package com.dualapps.cloner.engine

/**
 * Pembaca read-only tabel `string_ids` dari berkas DEX (`classes*.dex`, versi `035`–`041`).
 *
 * Hanya membaca metadata header dan tabel string DEX tanpa pernah memodifikasi bytecode.
 * Bersifat fail-open: bila berkas DEX rusak, terpotong, atau magic tidak dikenal,
 * fungsi mengembalikan daftar kosong dan tidak melempar exception.
 */
object DexStringReader {

    private const val MIN_DEX_HEADER_SIZE = 112
    private const val ENDIAN_CONSTANT = 0x12345678
    private const val OFFSET_ENDIAN_TAG = 40
    private const val OFFSET_STRING_IDS_SIZE = 56
    private const val OFFSET_STRING_IDS_OFF = 60
    private const val MAX_STRING_BYTES = 16 * 1024

    /**
     * Membaca seluruh string pada tabel `string_ids` dari berkas DEX.
     */
    fun readStrings(dexBytes: ByteArray): List<String> {
        return readStringsInternal(dexBytes, asciiPrefix = null)
    }

    /**
     * Membaca hanya string yang diawali [asciiPrefix] (mis. `"content://"`) dari tabel `string_ids`
     * DEX. Pencocokan awalan dilakukan langsung pada byte MUTF-8 sebelum alokasi `String` agar hemat
     * memori saat memindai APK besar dengan banyak berkas DEX.
     */
    fun readStringsWithPrefix(dexBytes: ByteArray, asciiPrefix: String): List<String> {
        if (asciiPrefix.isEmpty()) return readStrings(dexBytes)
        return readStringsInternal(dexBytes, asciiPrefix = asciiPrefix.toByteArray(Charsets.US_ASCII))
    }

    private fun readStringsInternal(dexBytes: ByteArray, asciiPrefix: ByteArray?): List<String> {
        return runCatching {
            if (!isValidDexHeader(dexBytes)) return emptyList()

            val stringIdsSize = readI32(dexBytes, OFFSET_STRING_IDS_SIZE)
            val stringIdsOff = readI32(dexBytes, OFFSET_STRING_IDS_OFF)
            if (stringIdsSize <= 0 || stringIdsOff < MIN_DEX_HEADER_SIZE) return emptyList()

            val tableEnd = stringIdsOff.toLong() + stringIdsSize.toLong() * 4L
            if (tableEnd > dexBytes.size.toLong()) return emptyList()

            val result = ArrayList<String>(if (asciiPrefix == null) stringIdsSize else 32)
            for (i in 0 until stringIdsSize) {
                val dataOff = readI32(dexBytes, stringIdsOff + i * 4)
                if (dataOff < MIN_DEX_HEADER_SIZE || dataOff >= dexBytes.size) continue

                val strStart = skipUleb128(dexBytes, dataOff)
                if (strStart < 0 || strStart >= dexBytes.size) continue

                if (asciiPrefix != null && !matchesPrefix(dexBytes, strStart, asciiPrefix)) {
                    continue
                }

                val decoded = decodeMutf8NullTerminated(dexBytes, strStart)
                if (decoded != null) {
                    result.add(decoded)
                }
            }
            result
        }.getOrElse { emptyList() }
    }

    internal fun isValidDexHeader(dexBytes: ByteArray): Boolean {
        if (dexBytes.size < MIN_DEX_HEADER_SIZE) return false
        // Magic: "dex\n" + 3 digit versi ("035".."041") + "\0"
        if (dexBytes[0] != 0x64.toByte() || // 'd'
            dexBytes[1] != 0x65.toByte() || // 'e'
            dexBytes[2] != 0x78.toByte() || // 'x'
            dexBytes[3] != 0x0A.toByte() || // '\n'
            dexBytes[7] != 0x00.toByte()    // '\0'
        ) {
            return false
        }
        val v0 = dexBytes[4].toInt() and 0xFF
        val v1 = dexBytes[5].toInt() and 0xFF
        val v2 = dexBytes[6].toInt() and 0xFF
        if (v0 !in '0'.code..'9'.code || v1 !in '0'.code..'9'.code || v2 !in '0'.code..'9'.code) {
            return false
        }
        val version = (v0 - '0'.code) * 100 + (v1 - '0'.code) * 10 + (v2 - '0'.code)
        if (version !in 35..41) return false

        return readI32(dexBytes, OFFSET_ENDIAN_TAG) == ENDIAN_CONSTANT
    }

    private fun skipUleb128(data: ByteArray, startOffset: Int): Int {
        var pos = startOffset
        var count = 0
        while (pos < data.size && count < 5) {
            val b = data[pos].toInt() and 0xFF
            pos++
            count++
            if ((b and 0x80) == 0) return pos
        }
        return -1
    }

    private fun matchesPrefix(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > data.size) return false
        for (i in prefix.indices) {
            if (data[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun decodeMutf8NullTerminated(data: ByteArray, startOffset: Int): String? {
        var pos = startOffset
        val limit = minOf(data.size, startOffset + MAX_STRING_BYTES)
        val sb = StringBuilder()

        while (pos < limit) {
            val a = data[pos].toInt() and 0xFF
            if (a == 0) {
                return sb.toString()
            }
            if (a < 0x80) {
                sb.append(a.toChar())
                pos++
            } else if ((a and 0xE0) == 0xC0) {
                if (pos + 1 >= limit) return null
                val b = data[pos + 1].toInt() and 0xFF
                if ((b and 0xC0) != 0x80) return null
                sb.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                pos += 2
            } else if ((a and 0xF0) == 0xE0) {
                if (pos + 2 >= limit) return null
                val b = data[pos + 1].toInt() and 0xFF
                val c = data[pos + 2].toInt() and 0xFF
                if ((b and 0xC0) != 0x80 || (c and 0xC0) != 0x80) return null
                sb.append((((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar())
                pos += 3
            } else {
                // Byte MUTF-8 tidak valid
                return null
            }
        }
        return null
    }

    private fun readI32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
