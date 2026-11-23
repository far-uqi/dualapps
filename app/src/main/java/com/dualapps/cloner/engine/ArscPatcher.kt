package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Patcher mandiri untuk berkas `resources.arsc` (ResTable).
 *
 * Hanya mengganti field `name` (UTF-16LE fixed 128 char = 256 byte) pada header
 * `ResTable_package` (0x0200) bila isinya persis sama dengan [oldPackage].
 * String pool dan nilai resource lain TIDAK disentuh (Bagian 3 langkah 3).
 */
object ArscPatcher {

    const val RES_TABLE_TYPE = 0x0002
    const val RES_STRING_POOL_TYPE = 0x0001
    const val RES_TABLE_PACKAGE_TYPE = 0x0200
    const val RES_TABLE_TYPE_TYPE = 0x0201
    const val MAX_PACKAGE_NAME_LENGTH = 127
    private const val PACKAGE_ID_FIELD_OFFSET = 8
    private const val PACKAGE_NAME_FIELD_OFFSET = 12 // ResChunk_header(8) + id(4)
    private const val PACKAGE_NAME_CHAR_SLOTS = 128
    private const val PACKAGE_NAME_BYTE_LENGTH = PACKAGE_NAME_CHAR_SLOTS * 2
    private const val MIN_PACKAGE_HEADER_SIZE = PACKAGE_NAME_FIELD_OFFSET + PACKAGE_NAME_BYTE_LENGTH
    private const val TYPE_FLAG_SPARSE = 0x01
    private const val TYPE_FLAG_OFFSET16 = 0x02
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008
    private const val RES_VALUE_TYPE_REFERENCE = 0x01
    private const val RES_VALUE_TYPE_STRING = 0x03

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

    /**
     * Mem-patch nilai resource string (`@string/...`) dengan [resId] di dalam `resources.arsc`
     * menjadi [newValue]. Mendukung global string pool UTF-8 maupun UTF-16LE, serta tabel
     * entri normal, OFFSET16, SPARSE, dan COMPACT (AAPT2 Android 13+).
     */
    fun patchStringResource(arscBytes: ByteArray, resId: Int, newValue: String): ByteArray {
        return patchResourceStringValue(
            arscBytes = arscBytes,
            resId = resId,
            newValue = newValue,
            errorCode = CloneError.LABEL_PATCH_FAILED,
            emptyValueMessage = "Nama launcher clone tidak boleh kosong."
        )
    }

    /**
     * Mengarahkan ulang path berkas resource (mis. drawable vector `.xml` → `.png`) pada [resId]
     * di `resources.arsc` dengan menambahkan [newPath] ke global StringPool dan menulis ulang
     * indeks string entri resource tersebut.
     */
    fun retargetFileResource(arscBytes: ByteArray, resId: Int, newPath: String): ByteArray {
        return patchResourceStringValue(
            arscBytes = arscBytes,
            resId = resId,
            newValue = newPath,
            errorCode = CloneError.ICON_PATCH_FAILED,
            emptyValueMessage = "Path target resource ikon tidak boleh kosong."
        )
    }

    private fun patchResourceStringValue(
        arscBytes: ByteArray,
        resId: Int,
        newValue: String,
        errorCode: CloneError,
        emptyValueMessage: String
    ): ByteArray {
        if (newValue.isBlank()) {
            throw CloneException(errorCode, emptyValueMessage)
        }

        val (tableHeaderSize, tableSize) = try {
            validateTableHeader(arscBytes)
        } catch (e: CloneException) {
            throw CloneException(errorCode, e.message, e)
        }

        val (poolOffset, poolSize, globalPool) = try {
            parseGlobalStringPool(arscBytes, tableHeaderSize, tableSize)
        } catch (e: CloneException) {
            throw CloneException(errorCode, e.message, e)
        }
        val afterPoolOffset = poolOffset + poolSize
        val restBytes = arscBytes.copyOfRange(afterPoolOffset, tableSize)

        // Tambahkan string baru di akhir global pool agar indeks string/style lama tidak bergeser.
        globalPool.strings.add(newValue)
        val newStringIndex = globalPool.strings.lastIndex

        var targetResId = resId
        var patchedCount = 0

        // Ikuti maksimal 3 hop referensi resource bila berupa alias ke resource lain.
        for (hop in 0 until 4) {
            var nextRefId: Int? = null
            visitMatchingEntries(restBytes, targetResId) { entry ->
                when (entry.valueType) {
                    RES_VALUE_TYPE_STRING -> {
                        writeI32(restBytes, entry.dataOffset, newStringIndex)
                        patchedCount++
                    }
                    RES_VALUE_TYPE_REFERENCE -> {
                        if (entry.dataValue != 0 && entry.dataValue != targetResId) {
                            nextRefId = entry.dataValue
                        }
                    }
                }
            }
            if (patchedCount > 0 || nextRefId == null) break
            targetResId = nextRefId!!
        }

        if (patchedCount == 0) {
            throw CloneException(
                errorCode,
                "Entri resource 0x${resId.toString(16)} tidak ditemukan di resources.arsc."
            )
        }

        val newPoolBytes = globalPool.toByteArray()
        val prefixBeforePool = arscBytes.copyOfRange(tableHeaderSize, poolOffset)
        val totalSize = tableHeaderSize + prefixBeforePool.size + newPoolBytes.size + restBytes.size

        val out = ByteArrayOutputStream(totalSize)
        val headerBuf = ByteBuffer.allocate(tableHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put(arscBytes, 0, tableHeaderSize)
        headerBuf.putInt(4, totalSize)
        out.write(headerBuf.array())
        if (prefixBeforePool.isNotEmpty()) {
            out.write(prefixBeforePool)
        }
        out.write(newPoolBytes)
        out.write(restBytes)
        val result = out.toByteArray()

        // Verifikasi hasil patch
        val verifiedValues = readStringResourceValues(result, resId)
        if (!verifiedValues.contains(newValue)) {
            throw CloneException(
                errorCode,
                "Verifikasi patch resource 0x${resId.toString(16)} gagal."
            )
        }
        return result
    }

    /**
     * Membaca seluruh nilai string (termasuk path berkas drawable/mipmap) untuk [resId]
     * dari `resources.arsc` di semua konfigurasi.
     */
    fun readStringResourceValues(arscBytes: ByteArray, resId: Int): List<String> {
        val (tableHeaderSize, tableSize) = validateTableHeader(arscBytes)
        val (poolOffset, poolSize, globalPool) = parseGlobalStringPool(arscBytes, tableHeaderSize, tableSize)
        val restBytes = arscBytes.copyOfRange(poolOffset + poolSize, tableSize)
        val results = LinkedHashSet<String>()

        var targetResId = resId
        for (hop in 0 until 4) {
            var nextRefId: Int? = null
            visitMatchingEntries(restBytes, targetResId) { entry ->
                when (entry.valueType) {
                    RES_VALUE_TYPE_STRING -> {
                        globalPool.strings.getOrNull(entry.dataValue)?.let { results.add(it) }
                    }
                    RES_VALUE_TYPE_REFERENCE -> {
                        if (entry.dataValue != 0 && entry.dataValue != targetResId) {
                            nextRefId = entry.dataValue
                        }
                    }
                }
            }
            if (results.isNotEmpty() || nextRefId == null) break
            targetResId = nextRefId!!
        }
        return results.toList()
    }

    private data class GlobalPoolInfo(
        val offset: Int,
        val size: Int,
        val pool: AxmlStringPool
    )

    private fun parseGlobalStringPool(data: ByteArray, tableHeaderSize: Int, tableSize: Int): GlobalPoolInfo {
        var offset = tableHeaderSize
        while (offset + 8 <= tableSize) {
            val chunkType = readU16(data, offset)
            val chunkSize = readI32(data, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > tableSize) {
                throw CloneException(CloneError.LABEL_PATCH_FAILED, "Chunk global ARSC rusak pada offset $offset.")
            }
            if (chunkType == RES_STRING_POOL_TYPE) {
                val pool = try {
                    AxmlStringPool.parse(data, offset)
                } catch (e: CloneException) {
                    throw CloneException(CloneError.LABEL_PATCH_FAILED, "Global StringPool ARSC tidak valid: ${e.message}", e)
                }
                return GlobalPoolInfo(offset, chunkSize, pool)
            }
            offset += chunkSize
        }
        throw CloneException(CloneError.LABEL_PATCH_FAILED, "Global StringPool tidak ditemukan di resources.arsc.")
    }

    private data class MatchedEntry(
        val valueType: Int,
        val dataValue: Int,
        val dataOffset: Int
    )

    private fun visitMatchingEntries(
        packagesRegion: ByteArray,
        resId: Int,
        onMatch: (MatchedEntry) -> Unit
    ) {
        val targetPackageId = (resId ushr 24) and 0xFF
        val targetTypeId = (resId ushr 16) and 0xFF
        val targetEntryId = resId and 0xFFFF

        var offset = 0
        while (offset + 8 <= packagesRegion.size) {
            val chunkType = readU16(packagesRegion, offset)
            val chunkHeaderSize = readU16(packagesRegion, offset + 2)
            val chunkSize = readI32(packagesRegion, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > packagesRegion.size) break

            if (chunkType == RES_TABLE_PACKAGE_TYPE && chunkHeaderSize >= MIN_PACKAGE_HEADER_SIZE) {
                val pkgId = readI32(packagesRegion, offset + PACKAGE_ID_FIELD_OFFSET) and 0xFF
                if (pkgId == targetPackageId) {
                    visitPackageTypes(
                        data = packagesRegion,
                        start = offset + chunkHeaderSize,
                        end = offset + chunkSize,
                        targetTypeId = targetTypeId,
                        targetEntryId = targetEntryId,
                        onMatch = onMatch
                    )
                }
            }
            offset += chunkSize
        }
    }

    private fun visitPackageTypes(
        data: ByteArray,
        start: Int,
        end: Int,
        targetTypeId: Int,
        targetEntryId: Int,
        onMatch: (MatchedEntry) -> Unit
    ) {
        var offset = start
        while (offset + 8 <= end) {
            val chunkType = readU16(data, offset)
            val chunkHeaderSize = readU16(data, offset + 2)
            val chunkSize = readI32(data, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > end) break

            if (chunkType == RES_TABLE_TYPE_TYPE && chunkHeaderSize >= 20) {
                val typeId = data[offset + 8].toInt() and 0xFF
                val flags = data[offset + 9].toInt() and 0xFF
                val entryCount = readI32(data, offset + 12)
                val entriesStart = readI32(data, offset + 16)

                if (typeId == targetTypeId && entryCount > 0) {
                    val entryRelOffset = resolveEntryOffset(
                        data = data,
                        tableStart = offset + chunkHeaderSize,
                        chunkEnd = offset + chunkSize,
                        flags = flags,
                        entryCount = entryCount,
                        targetEntryId = targetEntryId
                    )
                    if (entryRelOffset >= 0) {
                        val entryPos = offset + entriesStart + entryRelOffset
                        if (entryPos + 8 <= offset + chunkSize) {
                            val sizeOrKey = readU16(data, entryPos)
                            val entryFlags = readU16(data, entryPos + 2)
                            if ((entryFlags and ENTRY_FLAG_COMPACT) != 0) {
                                // ResTable_entry compact (8 byte): key(2) + flags(2) + data(4), type di high byte flags
                                val valueType = (entryFlags ushr 8) and 0xFF
                                val dataOff = entryPos + 4
                                val dataVal = readI32(data, dataOff)
                                onMatch(MatchedEntry(valueType, dataVal, dataOff))
                            } else if ((entryFlags and ENTRY_FLAG_COMPLEX) == 0) {
                                // ResTable_entry normal (sizeOrKey byte) + Res_value (8 byte)
                                val valuePos = entryPos + sizeOrKey
                                if (valuePos + 8 <= offset + chunkSize) {
                                    val valueType = data[valuePos + 3].toInt() and 0xFF
                                    val dataOff = valuePos + 4
                                    val dataVal = readI32(data, dataOff)
                                    onMatch(MatchedEntry(valueType, dataVal, dataOff))
                                }
                            }
                        }
                    }
                }
            }
            offset += chunkSize
        }
    }

    private fun resolveEntryOffset(
        data: ByteArray,
        tableStart: Int,
        chunkEnd: Int,
        flags: Int,
        entryCount: Int,
        targetEntryId: Int
    ): Int {
        return when {
            (flags and TYPE_FLAG_SPARSE) != 0 -> {
                for (i in 0 until entryCount) {
                    val pos = tableStart + i * 4
                    if (pos + 4 > chunkEnd) break
                    val idx = readU16(data, pos)
                    if (idx == targetEntryId) {
                        return readU16(data, pos + 2) * 4
                    }
                }
                -1
            }
            (flags and TYPE_FLAG_OFFSET16) != 0 -> {
                if (targetEntryId >= entryCount) return -1
                val pos = tableStart + targetEntryId * 2
                if (pos + 2 > chunkEnd) return -1
                val raw = readU16(data, pos)
                if (raw == 0xFFFF) -1 else raw * 4
            }
            else -> {
                if (targetEntryId >= entryCount) return -1
                val pos = tableStart + targetEntryId * 4
                if (pos + 4 > chunkEnd) return -1
                val raw = readI32(data, pos)
                if (raw == -1) -1 else raw
            }
        }
    }

    private fun writeI32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
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
