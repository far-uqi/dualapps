package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Penulis berkas ZIP/APK mandiri yang menjaga alignment entri STORED secara presisi:
 * - `resources.arsc` (STORED): align 4 byte
 * - berkas `.so` (STORED): align 4096 byte
 * - entri STORED lainnya: align 4 byte
 * - entri DEFLATED: dikompresi tanpa padding alignment
 */
class AlignedZipWriter(outputFile: File) : Closeable {

    private val out: OutputStream = BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024)
    private var bytesWritten: Long = 0L
    private val centralDirectory = mutableListOf<CentralDirEntry>()
    private var closed = false

    private data class CentralDirEntry(
        val nameBytes: ByteArray,
        val method: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long
    )

    fun writeStoredEntry(name: String, data: ByteArray, alignment: Int = alignmentForStored(name)) {
        ensureOpen()
        require(alignment >= 1) { "Alignment minimal 1" }
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val crc = computeCrc32(data)
        val localHeaderOffset = bytesWritten

        val baseDataOffset = localHeaderOffset + LFH_FIXED_SIZE + nameBytes.size
        val padding = ((alignment - (baseDataOffset % alignment)) % alignment).toInt()
        val extra = if (padding > 0) ByteArray(padding) else EMPTY_BYTES

        writeLocalFileHeader(
            nameBytes = nameBytes,
            extra = extra,
            method = METHOD_STORED,
            crc32 = crc,
            compressedSize = data.size.toLong(),
            uncompressedSize = data.size.toLong()
        )
        writeBytes(data)

        centralDirectory.add(
            CentralDirEntry(
                nameBytes = nameBytes,
                method = METHOD_STORED,
                dosTime = DEFAULT_DOS_TIME,
                dosDate = DEFAULT_DOS_DATE,
                crc32 = crc,
                compressedSize = data.size.toLong(),
                uncompressedSize = data.size.toLong(),
                localHeaderOffset = localHeaderOffset
            )
        )
    }

    fun writeDeflatedEntry(name: String, uncompressedData: ByteArray) {
        ensureOpen()
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val crc = computeCrc32(uncompressedData)
        val compressedData = deflate(uncompressedData)
        val localHeaderOffset = bytesWritten

        writeLocalFileHeader(
            nameBytes = nameBytes,
            extra = EMPTY_BYTES,
            method = METHOD_DEFLATED,
            crc32 = crc,
            compressedSize = compressedData.size.toLong(),
            uncompressedSize = uncompressedData.size.toLong()
        )
        writeBytes(compressedData)

        centralDirectory.add(
            CentralDirEntry(
                nameBytes = nameBytes,
                method = METHOD_DEFLATED,
                dosTime = DEFAULT_DOS_TIME,
                dosDate = DEFAULT_DOS_DATE,
                crc32 = crc,
                compressedSize = compressedData.size.toLong(),
                uncompressedSize = uncompressedData.size.toLong(),
                localHeaderOffset = localHeaderOffset
            )
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val cdOffset = bytesWritten
            for (entry in centralDirectory) {
                writeCentralDirHeader(entry)
            }
            val cdSize = bytesWritten - cdOffset
            writeEndOfCentralDirectory(centralDirectory.size, cdSize, cdOffset)
            out.flush()
        } catch (e: Exception) {
            throw CloneException(CloneError.ZIP_WRITE_FAILED, "Gagal menutup berkas APK: ${e.message}", e)
        } finally {
            runCatching { out.close() }
        }
    }

    private fun writeLocalFileHeader(
        nameBytes: ByteArray,
        extra: ByteArray,
        method: Int,
        crc32: Long,
        compressedSize: Long,
        uncompressedSize: Long
    ) {
        val buf = ByteBuffer.allocate(LFH_FIXED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(LFH_SIGNATURE)
        buf.putShort(VERSION_20.toShort())
        buf.putShort(FLAG_UTF8.toShort())
        buf.putShort(method.toShort())
        buf.putShort(DEFAULT_DOS_TIME.toShort())
        buf.putShort(DEFAULT_DOS_DATE.toShort())
        buf.putInt(crc32.toInt())
        buf.putInt(compressedSize.toInt())
        buf.putInt(uncompressedSize.toInt())
        buf.putShort(nameBytes.size.toShort())
        buf.putShort(extra.size.toShort())

        writeBytes(buf.array())
        writeBytes(nameBytes)
        if (extra.isNotEmpty()) {
            writeBytes(extra)
        }
    }

    private fun writeCentralDirHeader(entry: CentralDirEntry) {
        val buf = ByteBuffer.allocate(CDH_FIXED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(CDH_SIGNATURE)
        buf.putShort(VERSION_20.toShort()) // version made by
        buf.putShort(VERSION_20.toShort()) // version needed
        buf.putShort(FLAG_UTF8.toShort())
        buf.putShort(entry.method.toShort())
        buf.putShort(entry.dosTime.toShort())
        buf.putShort(entry.dosDate.toShort())
        buf.putInt(entry.crc32.toInt())
        buf.putInt(entry.compressedSize.toInt())
        buf.putInt(entry.uncompressedSize.toInt())
        buf.putShort(entry.nameBytes.size.toShort())
        buf.putShort(0) // extra len
        buf.putShort(0) // comment len
        buf.putShort(0) // disk number start
        buf.putShort(0) // internal attrs
        buf.putInt(0) // external attrs
        buf.putInt(entry.localHeaderOffset.toInt())

        writeBytes(buf.array())
        writeBytes(entry.nameBytes)
    }

    private fun writeEndOfCentralDirectory(entryCount: Int, cdSize: Long, cdOffset: Long) {
        val buf = ByteBuffer.allocate(EOCD_FIXED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(EOCD_SIGNATURE)
        buf.putShort(0) // disk number
        buf.putShort(0) // cd start disk
        buf.putShort(entryCount.toShort())
        buf.putShort(entryCount.toShort())
        buf.putInt(cdSize.toInt())
        buf.putInt(cdOffset.toInt())
        buf.putShort(0) // comment len

        writeBytes(buf.array())
    }

    private fun writeBytes(bytes: ByteArray) {
        out.write(bytes)
        bytesWritten += bytes.size
    }

    private fun ensureOpen() {
        check(!closed) { "AlignedZipWriter sudah ditutup." }
    }

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val ALIGN_DEFAULT = 4
        const val ALIGN_SO = 4096

        private const val LFH_SIGNATURE = 0x04034B50
        private const val CDH_SIGNATURE = 0x02014B50
        private const val EOCD_SIGNATURE = 0x06054B50
        private const val LFH_FIXED_SIZE = 30
        private const val CDH_FIXED_SIZE = 46
        private const val EOCD_FIXED_SIZE = 22
        private const val VERSION_20 = 20
        private const val FLAG_UTF8 = 0x0800
        private const val DEFAULT_DOS_TIME = 0
        private const val DEFAULT_DOS_DATE = (2026 - 1980) shl 9 or (1 shl 5) or 1
        private val EMPTY_BYTES = ByteArray(0)

        fun alignmentForStored(entryName: String): Int {
            return if (entryName.endsWith(".so", ignoreCase = true)) {
                ALIGN_SO
            } else {
                ALIGN_DEFAULT
            }
        }

        /**
         * Menentukan apakah entri adalah berkas tanda tangan lama di root `META-INF/`
         * (`MANIFEST.MF`, `*.SF`, `*.RSA`, `*.DSA`, `*.EC`) yang wajib dibuang saat rebuild.
         */
        fun isSignatureEntry(entryName: String): Boolean {
            if (!entryName.startsWith("META-INF/")) return false
            val fileName = entryName.substring("META-INF/".length)
            if (fileName.contains('/')) return false
            val upper = fileName.uppercase()
            return upper == "MANIFEST.MF" ||
                upper.endsWith(".SF") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".EC")
        }

        private fun computeCrc32(data: ByteArray): Long {
            val crc = CRC32()
            crc.update(data)
            return crc.value
        }

        private fun deflate(input: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(input)
                deflater.finish()
                val baos = ByteArrayOutputStream(maxOf(64, input.size / 2))
                val buffer = ByteArray(8192)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    if (count > 0) {
                        baos.write(buffer, 0, count)
                    } else if (deflater.needsInput()) {
                        break
                    }
                }
                return baos.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /**
         * Membaca offset data aktual setiap entri langsung dari Local File Header
         * pada berkas ZIP hasil — berguna untuk verifikasi alignment di test.
         */
        fun readEntryDataOffsets(zipFile: File): Map<String, Pair<Int, Long>> {
            val result = LinkedHashMap<String, Pair<Int, Long>>()
            RandomAccessFile(zipFile, "r").use { raf ->
                var pos = 0L
                val length = raf.length()
                val header = ByteArray(LFH_FIXED_SIZE)
                while (pos + LFH_FIXED_SIZE <= length) {
                    raf.seek(pos)
                    raf.readFully(header)
                    val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val sig = buf.int
                    if (sig != LFH_SIGNATURE) break
                    buf.short // version
                    buf.short // flags
                    val method = buf.short.toInt() and 0xFFFF
                    buf.short // time
                    buf.short // date
                    buf.int // crc
                    val compSize = buf.int.toLong() and 0xFFFFFFFFL
                    buf.int // uncompSize
                    val nameLen = buf.short.toInt() and 0xFFFF
                    val extraLen = buf.short.toInt() and 0xFFFF

                    val nameBytes = ByteArray(nameLen)
                    raf.readFully(nameBytes)
                    val name = String(nameBytes, StandardCharsets.UTF_8)
                    val dataOffset = pos + LFH_FIXED_SIZE + nameLen + extraLen
                    result[name] = method to dataOffset
                    pos = dataOffset + compSize
                }
            }
            return result
        }
    }
}
