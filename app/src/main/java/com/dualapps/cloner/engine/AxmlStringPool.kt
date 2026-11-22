package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Parser dan writer mandiri untuk chunk Android ResStringPool (0x0001).
 * Mendukung encoding UTF-8 dan UTF-16LE serta menghitung ulang seluruh offset
 * ketika panjang string berubah atau ada penambahan string baru.
 */
class AxmlStringPool(
    val strings: MutableList<String>,
    val isUtf8: Boolean,
    private val originalFlags: Int,
    private val styleOffsets: IntArray,
    private val stylesData: ByteArray
) {
    fun toByteArray(): ByteArray {
        val stringCount = strings.size
        val styleCount = styleOffsets.size

        // Bersihkan SORTED_FLAG karena modifikasi string dapat mengubah urutan leksikografis.
        val flags = (originalFlags and SORTED_FLAG.inv()).let { base ->
            if (isUtf8) base or UTF8_FLAG else base and UTF8_FLAG.inv()
        }

        val newStringOffsets = IntArray(stringCount)
        val stringsStream = ByteArrayOutputStream()

        for (i in 0 until stringCount) {
            newStringOffsets[i] = stringsStream.size()
            val s = strings[i]
            if (isUtf8) {
                writeUtf8String(stringsStream, s)
            } else {
                writeUtf16String(stringsStream, s)
            }
        }

        // Pad blok string ke batas 4 byte
        while (stringsStream.size() % 4 != 0) {
            stringsStream.write(0)
        }
        val stringsBytes = stringsStream.toByteArray()

        val headerSize = HEADER_SIZE
        val offsetsTableSize = (stringCount + styleCount) * 4
        val stringsStart = if (stringCount > 0) headerSize + offsetsTableSize else 0
        val stylesStart = if (styleCount > 0) headerSize + offsetsTableSize + stringsBytes.size else 0

        val rawChunkSize = headerSize + offsetsTableSize + stringsBytes.size + stylesData.size
        val alignedChunkSize = (rawChunkSize + 3) and 3.inv()

        val out = ByteBuffer.allocate(alignedChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        // ResChunk_header
        out.putShort(CHUNK_TYPE.toShort())
        out.putShort(headerSize.toShort())
        out.putInt(alignedChunkSize)
        // ResStringPool_header
        out.putInt(stringCount)
        out.putInt(styleCount)
        out.putInt(flags)
        out.putInt(stringsStart)
        out.putInt(stylesStart)

        for (off in newStringOffsets) {
            out.putInt(off)
        }
        for (off in styleOffsets) {
            out.putInt(off)
        }
        out.put(stringsBytes)
        if (stylesData.isNotEmpty()) {
            out.put(stylesData)
        }
        while (out.position() < alignedChunkSize) {
            out.put(0)
        }

        return out.array()
    }

    companion object {
        const val CHUNK_TYPE = 0x0001
        const val HEADER_SIZE = 28
        const val SORTED_FLAG = 1 shl 0
        const val UTF8_FLAG = 1 shl 8

        fun parse(data: ByteArray, offset: Int = 0): AxmlStringPool {
            if (offset < 0 || offset + HEADER_SIZE > data.size) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Header StringPool terpotong.")
            }
            val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
            val type = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int

            if (type != CHUNK_TYPE || headerSize < HEADER_SIZE || chunkSize < headerSize || offset + chunkSize > data.size) {
                throw CloneException(
                    CloneError.AXML_PARSE_FAILED,
                    "Header StringPool tidak valid (type=0x${type.toString(16)}, size=$chunkSize)."
                )
            }

            val stringCount = buf.int
            val styleCount = buf.int
            val flags = buf.int
            val stringsStart = buf.int
            val stylesStart = buf.int

            if (stringCount < 0 || styleCount < 0) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Jumlah string/style negatif.")
            }

            val offsetsPos = offset + headerSize
            val neededOffsetsBytes = (stringCount.toLong() + styleCount.toLong()) * 4L
            if (offsetsPos + neededOffsetsBytes > offset + chunkSize) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Tabel offset StringPool melebihi ukuran chunk.")
            }

            val offsetBuf = ByteBuffer.wrap(data, offsetsPos, neededOffsetsBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val stringOffsets = IntArray(stringCount) { offsetBuf.int }
            val styleOffsets = IntArray(styleCount) { offsetBuf.int }

            val isUtf8 = (flags and UTF8_FLAG) != 0
            val stringsBase = offset + stringsStart
            val chunkEnd = offset + chunkSize

            val strings = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val strPos = stringsBase + stringOffsets[i]
                if (strPos < stringsBase || strPos >= chunkEnd) {
                    throw CloneException(CloneError.AXML_PARSE_FAILED, "Offset string ke-$i di luar batas chunk.")
                }
                val decoded = if (isUtf8) {
                    readUtf8String(data, strPos, chunkEnd)
                } else {
                    readUtf16String(data, strPos, chunkEnd)
                }
                strings.add(decoded)
            }

            val stylesData = if (styleCount > 0 && stylesStart > 0) {
                val stylesAbsStart = offset + stylesStart
                if (stylesAbsStart < offset || stylesAbsStart > chunkEnd) {
                    throw CloneException(CloneError.AXML_PARSE_FAILED, "stylesStart di luar batas chunk.")
                }
                data.copyOfRange(stylesAbsStart, chunkEnd)
            } else {
                ByteArray(0)
            }

            return AxmlStringPool(
                strings = strings,
                isUtf8 = isUtf8,
                originalFlags = flags,
                styleOffsets = styleOffsets,
                stylesData = stylesData
            )
        }

        private fun readUtf8String(data: ByteArray, startPos: Int, limit: Int): String {
            var pos = startPos
            // Lewati panjang karakter UTF-16
            val (_, afterU16Len) = decodeLength8(data, pos, limit)
            pos = afterU16Len
            // Baca panjang byte UTF-8
            val (byteLen, afterByteLen) = decodeLength8(data, pos, limit)
            pos = afterByteLen
            if (byteLen < 0 || pos + byteLen > limit) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Panjang string UTF-8 melebihi batas chunk.")
            }
            return String(data, pos, byteLen, StandardCharsets.UTF_8)
        }

        private fun decodeLength8(data: ByteArray, pos: Int, limit: Int): Pair<Int, Int> {
            if (pos >= limit) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "EOF saat membaca panjang UTF-8.")
            }
            val b0 = data[pos].toInt() and 0xFF
            return if ((b0 and 0x80) != 0) {
                if (pos + 1 >= limit) {
                    throw CloneException(CloneError.AXML_PARSE_FAILED, "EOF saat membaca byte kedua panjang UTF-8.")
                }
                val b1 = data[pos + 1].toInt() and 0xFF
                (((b0 and 0x7F) shl 8) or b1) to (pos + 2)
            } else {
                b0 to (pos + 1)
            }
        }

        private fun writeUtf8String(out: ByteArrayOutputStream, value: String) {
            val utf8Bytes = value.toByteArray(StandardCharsets.UTF_8)
            encodeLength8(out, value.length)
            encodeLength8(out, utf8Bytes.size)
            out.write(utf8Bytes)
            out.write(0)
        }

        private fun encodeLength8(out: ByteArrayOutputStream, len: Int) {
            if (len > 0x7F) {
                out.write(((len ushr 8) and 0x7F) or 0x80)
                out.write(len and 0xFF)
            } else {
                out.write(len and 0x7F)
            }
        }

        private fun readUtf16String(data: ByteArray, startPos: Int, limit: Int): String {
            val (charLen, dataPos) = decodeLength16(data, startPos, limit)
            val byteLen = charLen * 2
            if (charLen < 0 || dataPos + byteLen > limit) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Panjang string UTF-16 melebihi batas chunk.")
            }
            return String(data, dataPos, byteLen, StandardCharsets.UTF_16LE)
        }

        private fun decodeLength16(data: ByteArray, pos: Int, limit: Int): Pair<Int, Int> {
            if (pos + 2 > limit) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "EOF saat membaca panjang UTF-16.")
            }
            val s0 = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            return if ((s0 and 0x8000) != 0) {
                if (pos + 4 > limit) {
                    throw CloneException(CloneError.AXML_PARSE_FAILED, "EOF saat membaca panjang UTF-16 4-byte.")
                }
                val s1 = (data[pos + 2].toInt() and 0xFF) or ((data[pos + 3].toInt() and 0xFF) shl 8)
                (((s0 and 0x7FFF) shl 16) or s1) to (pos + 4)
            } else {
                s0 to (pos + 2)
            }
        }

        private fun writeUtf16String(out: ByteArrayOutputStream, value: String) {
            val charLen = value.length
            encodeLength16(out, charLen)
            val utf16Bytes = value.toByteArray(StandardCharsets.UTF_16LE)
            out.write(utf16Bytes)
            out.write(0)
            out.write(0)
        }

        private fun encodeLength16(out: ByteArrayOutputStream, len: Int) {
            if (len > 0x7FFF) {
                val high = ((len ushr 16) and 0x7FFF) or 0x8000
                val low = len and 0xFFFF
                out.write(high and 0xFF)
                out.write((high ushr 8) and 0xFF)
                out.write(low and 0xFF)
                out.write((low ushr 8) and 0xFF)
            } else {
                out.write(len and 0xFF)
                out.write((len ushr 8) and 0xFF)
            }
        }
    }
}
