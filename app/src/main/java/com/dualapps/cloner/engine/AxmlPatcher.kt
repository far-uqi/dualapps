package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser dan patcher mandiri untuk AndroidManifest.xml biner (AXML).
 *
 * Cara kerja:
 * 1. Mem-parse header RES_XML_TYPE dan chunk ResStringPool.
 * 2. Mengganti SEMUA kemunculan persis [oldPackage] di StringPool menjadi [newPackage].
 * 3. Memindai elemen XML untuk:
 *    - memperbarui `authorities` pada `<provider>` yang berawalan `<oldPackage>.` (atau persis `<oldPackage>`),
 *    - memperbarui `name` pada `<permission>` / `<uses-permission>` yang berawalan `<oldPackage>.`,
 *    - menormalisasi nama kelas relatif (berawalan `.`) pada komponen (`activity`, `activity-alias`,
 *      `service`, `receiver`, `provider`, `application`) menjadi FQN asli agar tetap cocok dengan DEX
 *      yang tidak dimodifikasi.
 * 4. Menulis ulang AXML dengan offset & ukuran chunk terhitung ulang.
 * 5. Memverifikasi hasil dengan mem-parse ulang AXML dan memastikan atribut `package` pada `<manifest>`
 *    persis sama dengan [newPackage].
 */
object AxmlPatcher {

    const val RES_XML_TYPE = 0x0003
    const val RES_STRING_POOL_TYPE = 0x0001
    const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03

    sealed class LabelTarget {
        data class Literal(val value: String) : LabelTarget()
        data class ResourceRef(val resId: Int) : LabelTarget()
    }

    private val COMPONENT_TAGS = setOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider"
    )
    private val PERMISSION_TAGS = setOf("permission", "uses-permission", "uses-permission-sdk-23")
    private val PERMISSION_REF_ATTRS = setOf("permission", "readPermission", "writePermission")

    fun patch(axmlBytes: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        if (oldPackage.isBlank() || newPackage.isBlank()) {
            throw CloneException(CloneError.AXML_PATCH_FAILED, "Package lama atau baru tidak boleh kosong.")
        }

        val parsed = parseHeaderAndPool(axmlBytes)
        val pool = parsed.stringPool
        val restBytes = axmlBytes.copyOfRange(parsed.afterStringPoolOffset, axmlBytes.size)

        // Langkah A: Ganti SEMUA kemunculan persis string package asli di StringPool
        for (i in pool.strings.indices) {
            if (pool.strings[i] == oldPackage) {
                pool.strings[i] = newPackage
            }
        }

        // Langkah B: Patch atribut terkait di chunk RES_XML_START_ELEMENT_TYPE
        // (authorities provider, custom permission, dan nama kelas relatif)
        patchElementChunks(restBytes, pool, oldPackage, newPackage)

        // Langkah C: Rakit ulang berkas AXML
        val newPoolBytes = pool.toByteArray()
        val totalSize = parsed.xmlHeaderSize + newPoolBytes.size + restBytes.size

        val out = ByteArrayOutputStream(totalSize)
        val rootHeader = ByteBuffer.allocate(parsed.xmlHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        rootHeader.putShort(RES_XML_TYPE.toShort())
        rootHeader.putShort(parsed.xmlHeaderSize.toShort())
        rootHeader.putInt(totalSize)
        if (parsed.xmlHeaderSize > 8) {
            rootHeader.put(axmlBytes, 8, parsed.xmlHeaderSize - 8)
        }
        out.write(rootHeader.array())
        out.write(newPoolBytes)
        out.write(restBytes)
        val patchedBytes = out.toByteArray()

        // Langkah D: Verifikasi wajib — parse ulang manifest hasil patch dan pastikan package == newPackage
        val verifiedPackage = try {
            readPackageName(patchedBytes)
        } catch (e: CloneException) {
            throw CloneException(
                CloneError.AXML_PATCH_FAILED,
                "Manifest hasil patch gagal diparse ulang: ${e.message}",
                e
            )
        }
        if (verifiedPackage != newPackage) {
            throw CloneException(
                CloneError.AXML_PATCH_FAILED,
                "Verifikasi manifest gagal: diharapkan '$newPackage', terbaca '$verifiedPackage'."
            )
        }

        return patchedBytes
    }

    /**
     * Membaca atribut `package` pada elemen `<manifest>` dari AXML biner.
     */
    fun readPackageName(axmlBytes: ByteArray): String? {
        val parsed = parseHeaderAndPool(axmlBytes)
        val pool = parsed.stringPool
        var offset = parsed.afterStringPoolOffset

        while (offset + 8 <= axmlBytes.size) {
            val type = readU16(axmlBytes, offset)
            val headerSize = readU16(axmlBytes, offset + 2)
            val chunkSize = readI32(axmlBytes, offset + 4)

            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Ukuran chunk AXML tidak valid pada offset $offset.")
            }

            if (type == RES_XML_START_ELEMENT_TYPE) {
                // ResXMLTree_node header (16B) + ResXMLTree_attrExt (20B) = 36B
                if (offset + headerSize + 20 > offset + chunkSize) {
                    throw CloneException(CloneError.AXML_PARSE_FAILED, "Chunk START_ELEMENT terpotong.")
                }
                val extStart = offset + headerSize
                val nameIdx = readI32(axmlBytes, extStart + 4)
                val attributeStart = readU16(axmlBytes, extStart + 8)
                val attributeSize = readU16(axmlBytes, extStart + 10)
                val attributeCount = readU16(axmlBytes, extStart + 12)

                val tagName = pool.strings.getOrNull(nameIdx)
                if (tagName == "manifest") {
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) {
                            throw CloneException(CloneError.AXML_PARSE_FAILED, "Atribut START_ELEMENT terpotong.")
                        }
                        val attrNsIdx = readI32(axmlBytes, attrOff)
                        val attrNameIdx = readI32(axmlBytes, attrOff + 4)
                        val rawValueIdx = readI32(axmlBytes, attrOff + 8)
                        val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                        val valueData = readI32(axmlBytes, attrOff + 16)

                        val attrName = pool.strings.getOrNull(attrNameIdx)
                        if (attrNsIdx == -1 && attrName == "package") {
                            val strIdx = when {
                                rawValueIdx >= 0 -> rawValueIdx
                                valueType == TYPE_STRING && valueData >= 0 -> valueData
                                else -> -1
                            }
                            return pool.strings.getOrNull(strIdx)
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return null
    }

    /**
     * Membaca atribut `android:label` pada elemen `<application>` dari AXML biner.
     * Mengembalikan [LabelTarget.Literal] bila berupa string langsung, atau
     * [LabelTarget.ResourceRef] bila berupa referensi `@string/...`.
     */
    fun readApplicationLabel(axmlBytes: ByteArray): LabelTarget? {
        val parsed = parseHeaderAndPool(axmlBytes)
        val pool = parsed.stringPool
        var offset = parsed.afterStringPoolOffset

        while (offset + 8 <= axmlBytes.size) {
            val type = readU16(axmlBytes, offset)
            val headerSize = readU16(axmlBytes, offset + 2)
            val chunkSize = readI32(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Ukuran chunk AXML tidak valid pada offset $offset.")
            }

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(axmlBytes, extStart + 4)
                val attributeStart = readU16(axmlBytes, extStart + 8)
                val attributeSize = readU16(axmlBytes, extStart + 10)
                val attributeCount = readU16(axmlBytes, extStart + 12)
                val tagName = pool.strings.getOrNull(nameIdx)

                if (tagName == "application") {
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) break
                        val attrNameIdx = readI32(axmlBytes, attrOff + 4)
                        val rawValueIdx = readI32(axmlBytes, attrOff + 8)
                        val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                        val valueData = readI32(axmlBytes, attrOff + 16)

                        if (pool.strings.getOrNull(attrNameIdx) == "label") {
                            return when {
                                valueType == TYPE_REFERENCE && valueData != 0 -> {
                                    LabelTarget.ResourceRef(valueData)
                                }
                                valueType == TYPE_STRING && valueData >= 0 -> {
                                    pool.strings.getOrNull(valueData)?.let { LabelTarget.Literal(it) }
                                }
                                rawValueIdx >= 0 -> {
                                    pool.strings.getOrNull(rawValueIdx)?.let { LabelTarget.Literal(it) }
                                }
                                else -> null
                            }
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return null
    }

    /**
     * Mengganti nilai string literal `android:label` pada elemen `<application>` di AXML biner.
     * Menambahkan string baru ke StringPool agar indeks string lama tidak bergeser.
     */
    fun patchApplicationLabelLiteral(axmlBytes: ByteArray, newLabel: String): ByteArray {
        if (newLabel.isBlank()) {
            throw CloneException(CloneError.LABEL_PATCH_FAILED, "Nama launcher clone tidak boleh kosong.")
        }

        val parsed = parseHeaderAndPool(axmlBytes)
        val pool = parsed.stringPool
        val restBytes = axmlBytes.copyOfRange(parsed.afterStringPoolOffset, axmlBytes.size)
        var offset = 0
        var patched = false

        while (offset + 8 <= restBytes.size) {
            val type = readU16(restBytes, offset)
            val headerSize = readU16(restBytes, offset + 2)
            val chunkSize = readI32(restBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > restBytes.size) {
                throw CloneException(CloneError.LABEL_PATCH_FAILED, "Chunk AXML rusak saat patch label.")
            }

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(restBytes, extStart + 4)
                val attributeStart = readU16(restBytes, extStart + 8)
                val attributeSize = readU16(restBytes, extStart + 10)
                val attributeCount = readU16(restBytes, extStart + 12)

                if (pool.strings.getOrNull(nameIdx) == "application") {
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) break
                        val attrNameIdx = readI32(restBytes, attrOff + 4)
                        val valueType = restBytes[attrOff + 15].toInt() and 0xFF
                        if (pool.strings.getOrNull(attrNameIdx) == "label") {
                            if (valueType == TYPE_REFERENCE) {
                                throw CloneException(
                                    CloneError.LABEL_PATCH_FAILED,
                                    "Atribut android:label berupa referensi resource, bukan literal."
                                )
                            }
                            val newIdx = getOrAddString(pool, newLabel)
                            writeI32(restBytes, attrOff + 8, newIdx)
                            restBytes[attrOff + 15] = TYPE_STRING.toByte()
                            writeI32(restBytes, attrOff + 16, newIdx)
                            patched = true
                            break
                        }
                    }
                }
            }
            offset += chunkSize
        }

        if (!patched) {
            throw CloneException(
                CloneError.LABEL_PATCH_FAILED,
                "Atribut android:label literal tidak ditemukan pada elemen <application>."
            )
        }

        val newPoolBytes = pool.toByteArray()
        val totalSize = parsed.xmlHeaderSize + newPoolBytes.size + restBytes.size
        val out = ByteArrayOutputStream(totalSize)
        val rootHeader = ByteBuffer.allocate(parsed.xmlHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        rootHeader.putShort(RES_XML_TYPE.toShort())
        rootHeader.putShort(parsed.xmlHeaderSize.toShort())
        rootHeader.putInt(totalSize)
        if (parsed.xmlHeaderSize > 8) {
            rootHeader.put(axmlBytes, 8, parsed.xmlHeaderSize - 8)
        }
        out.write(rootHeader.array())
        out.write(newPoolBytes)
        out.write(restBytes)
        val patchedBytes = out.toByteArray()

        val verified = readApplicationLabel(patchedBytes)
        if (verified != LabelTarget.Literal(newLabel)) {
            throw CloneException(
                CloneError.LABEL_PATCH_FAILED,
                "Verifikasi label literal gagal: diharapkan '$newLabel', terbaca '$verified'."
            )
        }
        return patchedBytes
    }

    /**
     * Membaca resource ID dari atribut `android:icon` dan `android:roundIcon` pada `<application>`.
     */
    fun readApplicationIconRefs(axmlBytes: ByteArray): List<Int> {
        val parsed = parseHeaderAndPool(axmlBytes)
        val pool = parsed.stringPool
        val refs = LinkedHashSet<Int>()
        var offset = parsed.afterStringPoolOffset

        while (offset + 8 <= axmlBytes.size) {
            val type = readU16(axmlBytes, offset)
            val headerSize = readU16(axmlBytes, offset + 2)
            val chunkSize = readI32(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) break

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(axmlBytes, extStart + 4)
                val attributeStart = readU16(axmlBytes, extStart + 8)
                val attributeSize = readU16(axmlBytes, extStart + 10)
                val attributeCount = readU16(axmlBytes, extStart + 12)
                if (pool.strings.getOrNull(nameIdx) == "application") {
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) break
                        val attrName = pool.strings.getOrNull(readI32(axmlBytes, attrOff + 4))
                        val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                        val valueData = readI32(axmlBytes, attrOff + 16)
                        if ((attrName == "icon" || attrName == "roundIcon") &&
                            valueType == TYPE_REFERENCE && valueData != 0
                        ) {
                            refs.add(valueData)
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return refs.toList()
    }

    /**
     * Membaca resource ID `android:drawable` dari elemen `<foreground>` pada berkas
     * adaptive-icon XML biner (`res/mipmap-anydpi-v26/ic_launcher*.xml`).
     */
    fun readAdaptiveIconForegroundRef(axmlBytes: ByteArray): Int? {
        val parsed = runCatching { parseHeaderAndPool(axmlBytes) }.getOrNull() ?: return null
        val pool = parsed.stringPool
        var offset = parsed.afterStringPoolOffset

        while (offset + 8 <= axmlBytes.size) {
            val type = readU16(axmlBytes, offset)
            val headerSize = readU16(axmlBytes, offset + 2)
            val chunkSize = readI32(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) break

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(axmlBytes, extStart + 4)
                val attributeStart = readU16(axmlBytes, extStart + 8)
                val attributeSize = readU16(axmlBytes, extStart + 10)
                val attributeCount = readU16(axmlBytes, extStart + 12)
                if (pool.strings.getOrNull(nameIdx) == "foreground") {
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) break
                        val attrName = pool.strings.getOrNull(readI32(axmlBytes, attrOff + 4))
                        val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                        val valueData = readI32(axmlBytes, attrOff + 16)
                        if (attrName == "drawable" && valueType == TYPE_REFERENCE && valueData != 0) {
                            return valueData
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return null
    }

    private fun patchElementChunks(
        chunksBytes: ByteArray,
        pool: AxmlStringPool,
        oldPackage: String,
        newPackage: String
    ) {
        val oldPrefix = "$oldPackage."
        val newPrefix = "$newPackage."

        // Pass 1: Kumpulkan semua `<permission android:name="X">` yang dideklarasikan aplikasi target
        // tetapi TIDAK berawalan `<oldPackage>.` (dan bukan persis `<oldPackage>`), lalu petakan ke `<newPackage>.<X>`.
        val renamedNonPrefixedPermissions = collectDeclaredNonPrefixedPermissions(
            chunksBytes = chunksBytes,
            pool = pool,
            oldPackage = oldPackage,
            newPackage = newPackage
        )

        var offset = 0

        while (offset + 8 <= chunksBytes.size) {
            val type = readU16(chunksBytes, offset)
            val headerSize = readU16(chunksBytes, offset + 2)
            val chunkSize = readI32(chunksBytes, offset + 4)

            if (chunkSize < 8 || offset + chunkSize > chunksBytes.size) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Chunk AXML rusak saat patching elemen.")
            }

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(chunksBytes, extStart + 4)
                val attributeStart = readU16(chunksBytes, extStart + 8)
                val attributeSize = readU16(chunksBytes, extStart + 10)
                val attributeCount = readU16(chunksBytes, extStart + 12)
                val tagName = pool.strings.getOrNull(nameIdx)

                val attrsBase = extStart + attributeStart
                for (i in 0 until attributeCount) {
                    val attrOff = attrsBase + i * attributeSize
                    if (attrOff + 20 > offset + chunkSize) break

                    val attrNameIdx = readI32(chunksBytes, attrOff + 4)
                    val rawValueIdx = readI32(chunksBytes, attrOff + 8)
                    val valueType = chunksBytes[attrOff + 15].toInt() and 0xFF
                    val valueData = readI32(chunksBytes, attrOff + 16)
                    val attrName = pool.strings.getOrNull(attrNameIdx) ?: continue

                    val currentStrIdx = when {
                        rawValueIdx >= 0 -> rawValueIdx
                        valueType == TYPE_STRING && valueData >= 0 -> valueData
                        else -> -1
                    }
                    val currentValue = pool.strings.getOrNull(currentStrIdx) ?: continue

                    val updatedValue: String? = when {
                        tagName == "provider" && attrName == "authorities" -> {
                            rewriteAuthorities(currentValue, oldPackage, newPackage)
                        }
                        tagName in PERMISSION_TAGS && attrName == "name" -> {
                            when {
                                currentValue.startsWith(oldPrefix) -> {
                                    newPrefix + currentValue.removePrefix(oldPrefix)
                                }
                                renamedNonPrefixedPermissions.containsKey(currentValue) -> {
                                    renamedNonPrefixedPermissions[currentValue]
                                }
                                else -> null
                            }
                        }
                        attrName in PERMISSION_REF_ATTRS && renamedNonPrefixedPermissions.containsKey(currentValue) -> {
                            renamedNonPrefixedPermissions[currentValue]
                        }
                        tagName in COMPONENT_TAGS && attrName == "name" -> {
                            if (currentValue.startsWith(".")) {
                                oldPackage + currentValue
                            } else null
                        }
                        renamedNonPrefixedPermissions.containsKey(currentValue) -> {
                            // Atribut lain yang nilainya persis sama dengan permission yang di-rename
                            renamedNonPrefixedPermissions[currentValue]
                        }
                        else -> null
                    }

                    if (updatedValue != null && updatedValue != currentValue) {
                        val newIdx = getOrAddString(pool, updatedValue)
                        if (rawValueIdx >= 0) {
                            writeI32(chunksBytes, attrOff + 8, newIdx)
                        }
                        if (valueType == TYPE_STRING) {
                            writeI32(chunksBytes, attrOff + 16, newIdx)
                        }
                    }
                }
            }

            offset += chunkSize
        }
    }

    private fun collectDeclaredNonPrefixedPermissions(
        chunksBytes: ByteArray,
        pool: AxmlStringPool,
        oldPackage: String,
        newPackage: String
    ): Map<String, String> {
        val oldPrefix = "$oldPackage."
        val newPrefix = "$newPackage."
        val result = LinkedHashMap<String, String>()
        var offset = 0

        while (offset + 8 <= chunksBytes.size) {
            val type = readU16(chunksBytes, offset)
            val headerSize = readU16(chunksBytes, offset + 2)
            val chunkSize = readI32(chunksBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > chunksBytes.size) {
                throw CloneException(CloneError.AXML_PARSE_FAILED, "Chunk AXML rusak saat memindai permission.")
            }

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val nameIdx = readI32(chunksBytes, extStart + 4)
                val tagName = pool.strings.getOrNull(nameIdx)
                if (tagName == "permission") {
                    val attributeStart = readU16(chunksBytes, extStart + 8)
                    val attributeSize = readU16(chunksBytes, extStart + 10)
                    val attributeCount = readU16(chunksBytes, extStart + 12)
                    val attrsBase = extStart + attributeStart
                    for (i in 0 until attributeCount) {
                        val attrOff = attrsBase + i * attributeSize
                        if (attrOff + 20 > offset + chunkSize) break
                        val attrName = pool.strings.getOrNull(readI32(chunksBytes, attrOff + 4))
                        if (attrName == "name") {
                            val rawValueIdx = readI32(chunksBytes, attrOff + 8)
                            val valueType = chunksBytes[attrOff + 15].toInt() and 0xFF
                            val valueData = readI32(chunksBytes, attrOff + 16)
                            val strIdx = when {
                                rawValueIdx >= 0 -> rawValueIdx
                                valueType == TYPE_STRING && valueData >= 0 -> valueData
                                else -> -1
                            }
                            val permName = pool.strings.getOrNull(strIdx).orEmpty()
                            if (permName.isNotBlank() &&
                                permName != oldPackage &&
                                permName != newPackage &&
                                !permName.startsWith(oldPrefix) &&
                                !permName.startsWith(newPrefix)
                            ) {
                                result[permName] = "$newPackage.$permName"
                            }
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return result
    }

    private fun rewriteAuthorities(authorities: String, oldPackage: String, newPackage: String): String? {
        val oldPrefix = "$oldPackage."
        val newPrefix = "$newPackage."
        var changed = false
        val rewritten = authorities.split(";").joinToString(";") { part ->
            val leadingWs = part.takeWhile { it.isWhitespace() }
            val trailingWs = part.takeLastWhile { it.isWhitespace() }
            val trimmed = part.trim()
            when {
                trimmed == oldPackage -> {
                    changed = true
                    leadingWs + newPackage + trailingWs
                }
                trimmed.startsWith(oldPrefix) -> {
                    changed = true
                    leadingWs + newPrefix + trimmed.removePrefix(oldPrefix) + trailingWs
                }
                else -> part
            }
        }
        return if (changed) rewritten else null
    }

    private fun getOrAddString(pool: AxmlStringPool, value: String): Int {
        val existing = pool.strings.indexOf(value)
        if (existing >= 0) return existing
        pool.strings.add(value)
        return pool.strings.lastIndex
    }

    private data class ParsedHeader(
        val xmlHeaderSize: Int,
        val stringPool: AxmlStringPool,
        val afterStringPoolOffset: Int
    )

    private fun parseHeaderAndPool(axmlBytes: ByteArray): ParsedHeader {
        if (axmlBytes.size < 8) {
            throw CloneException(CloneError.AXML_PARSE_FAILED, "Berkas AXML terlalu kecil (${axmlBytes.size} byte).")
        }
        val rootType = readU16(axmlBytes, 0)
        val xmlHeaderSize = readU16(axmlBytes, 2)
        val fileSize = readI32(axmlBytes, 4)

        if (rootType != RES_XML_TYPE || xmlHeaderSize < 8 || fileSize > axmlBytes.size || fileSize < xmlHeaderSize) {
            throw CloneException(
                CloneError.AXML_PARSE_FAILED,
                "Header AXML tidak valid (type=0x${rootType.toString(16)}, fileSize=$fileSize)."
            )
        }

        if (xmlHeaderSize + 8 > axmlBytes.size) {
            throw CloneException(CloneError.AXML_PARSE_FAILED, "Chunk StringPool tidak ditemukan di AXML.")
        }
        val poolType = readU16(axmlBytes, xmlHeaderSize)
        val poolSize = readI32(axmlBytes, xmlHeaderSize + 4)
        if (poolType != RES_STRING_POOL_TYPE || poolSize < AxmlStringPool.HEADER_SIZE || xmlHeaderSize + poolSize > axmlBytes.size) {
            throw CloneException(CloneError.AXML_PARSE_FAILED, "Chunk pertama AXML bukan ResStringPool yang valid.")
        }

        val pool = AxmlStringPool.parse(axmlBytes, xmlHeaderSize)
        return ParsedHeader(
            xmlHeaderSize = xmlHeaderSize,
            stringPool = pool,
            afterStringPoolOffset = xmlHeaderSize + poolSize
        )
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

    private fun writeI32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
