package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException

/**
 * Patcher mandiri untuk berkas `resources.arsc` (ResTable).
 *
 * Hanya mengganti field `name` (UTF-16LE fixed 128 char = 256 byte) pada header
 * `ResTable_package` (0x0200) bila isinya persis sama dengan [oldPackage].
 * String pool dan nilai resource lain TIDAK disentuh (Bagian 3 langkah 3).
 */
object ArscPatcher {

    const val RES_TABLE_TYPE = 0x0002
    const val RES_TABLE_PACKAGE_TYPE = 0x0200
    const val MAX_PACKAGE_NAME_LENGTH = 127
    private const val PACKAGE_NAME_FIELD_OFFSET = 12 // ResChunk_header(8) + id(4)
    private const val PACKAGE_NAME_CHAR_SLOTS = 128
    private const val PACKAGE_NAME_BYTE_LENGTH = PACKAGE_NAME_CHAR_SLOTS * 2
    private const val MIN_PACKAGE_HEADER_SIZE = PACKAGE_NAME_FIELD_OFFSET + PACKAGE_NAME_BYTE_LENGTH

    fun patch(arscBytes: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        if (newPackage.length > MAX_PACKAGE_NAME_LENGTH) {
            throw CloneException(
                CloneError.PACKAGE_TOO_LONG,
                "Panjang package baru ${newPackage.length} karakter melebihi batas maksimal $MAX_PACKAGE_NAME_LENGTH."
            )
        }
        if (oldPackage.isBlank() || newPackage.isBlank()) {
            throw CloneException(CloneError.ARSC_PATCH_FAILED, "Package lama atau baru tidak boleh kosong.")
        }

        val out = arscBytes.copyOf()
        val (tableHeaderSize, tableSize) = validateTableHeader(out)

        var offset = tableHeaderSize
        var packageChunkCount = 0

        while (offset < tableSize) {
            if (offset + 8 > tableSize) {
                throw CloneException(CloneError.ARSC_PATCH_FAILED, "Header chunk ARSC terpotong di offset $offset.")
            }
            val chunkType = readU16(out, offset)
            val chunkHeaderSize = readU16(out, offset + 2)
            val chunkSize = readI32(out, offset + 4)

            if (chunkSize < 8 || offset + chunkSize > tableSize) {
                throw CloneException(
                    CloneError.ARSC_PATCH_FAILED,
                    "Ukuran chunk ARSC tidak valid (type=0x${chunkType.toString(16)}, size=$chunkSize)."
                )
            }

            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                packageChunkCount++
                if (chunkHeaderSize < MIN_PACKAGE_HEADER_SIZE || offset + MIN_PACKAGE_HEADER_SIZE > tableSize) {
                    throw CloneException(
                        CloneError.ARSC_PATCH_FAILED,
                        "Header ResTable_package terlalu kecil ($chunkHeaderSize byte)."
                    )
                }
                val nameOffset = offset + PACKAGE_NAME_FIELD_OFFSET
                val currentName = readFixedUtf16Name(out, nameOffset)
                if (currentName == oldPackage) {
                    writeFixedUtf16Name(out, nameOffset, newPackage)
                }
            }

            offset += chunkSize
        }

        if (packageChunkCount == 0) {
            throw CloneException(CloneError.ARSC_PATCH_FAILED, "Tidak ditemukan chunk ResTable_package di resources.arsc.")
        }

        return out
    }

    /**
     * Membaca semua nama package pada chunk `ResTable_package` di dalam `resources.arsc`.
     */
    fun readPackageNames(arscBytes: ByteArray): List<String> {
        val (tableHeaderSize, tableSize) = validateTableHeader(arscBytes)
        val names = mutableListOf<String>()
        var offset = tableHeaderSize

        while (offset < tableSize) {
            if (offset + 8 > tableSize) {
                throw CloneException(CloneError.ARSC_PATCH_FAILED, "Header chunk ARSC terpotong di offset $offset.")
            }
            val chunkType = readU16(arscBytes, offset)
            val chunkHeaderSize = readU16(arscBytes, offset + 2)
            val chunkSize = readI32(arscBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > tableSize) {
                throw CloneException(CloneError.ARSC_PATCH_FAILED, "Ukuran chunk ARSC tidak valid.")
            }
            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                if (chunkHeaderSize < MIN_PACKAGE_HEADER_SIZE || offset + MIN_PACKAGE_HEADER_SIZE > tableSize) {
                    throw CloneException(CloneError.ARSC_PATCH_FAILED, "Header ResTable_package terpotong.")
                }
                names.add(readFixedUtf16Name(arscBytes, offset + PACKAGE_NAME_FIELD_OFFSET))
            }
            offset += chunkSize
        }
        return names
    }

    private fun validateTableHeader(data: ByteArray): Pair<Int, Int> {
        if (data.size < 12) {
            throw CloneException(CloneError.ARSC_PATCH_FAILED, "Berkas resources.arsc terlalu kecil.")
        }
        val type = readU16(data, 0)
        val headerSize = readU16(data, 2)
        val size = readI32(data, 4)
        if (type != RES_TABLE_TYPE || headerSize < 12 || size < headerSize || size > data.size) {
            throw CloneException(
                CloneError.ARSC_PATCH_FAILED,
                "Header ResTable tidak valid (type=0x${type.toString(16)}, size=$size)."
            )
        }
        return headerSize to size
    }

    private fun readFixedUtf16Name(data: ByteArray, offset: Int): String {
        val chars = StringBuilder()
        for (i in 0 until PACKAGE_NAME_CHAR_SLOTS) {
            val pos = offset + i * 2
            val codeUnit = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            if (codeUnit == 0) break
            chars.append(codeUnit.toChar())
        }
        return chars.toString()
    }

    private fun writeFixedUtf16Name(data: ByteArray, offset: Int, value: String) {
        // Nolkan seluruh 256 byte terlebih dahulu (menjamin null-terminator & padding bersih)
        for (i in 0 until PACKAGE_NAME_BYTE_LENGTH) {
            data[offset + i] = 0
        }
        for (i in value.indices) {
            val codeUnit = value[i].code
            val pos = offset + i * 2
            data[pos] = (codeUnit and 0xFF).toByte()
            data[pos + 1] = ((codeUnit ushr 8) and 0xFF).toByte()
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readI32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
