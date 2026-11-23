package com.dualapps.cloner.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pembuat fixture biner mandiri (AXML, resources.arsc, dan APK mini) untuk unit test Fase 2–4.
 */
object BinaryFixtureBuilder {

    /**
     * Membangun AndroidManifest.xml biner (AXML) valid yang memiliki:
     * - `<manifest package="...">`
     * - `<permission android:name="<pkg>.CUSTOM_PERM">`
     * - `<application android:name=".App">`
     *   - `<activity android:name="<pkg>.MainActivity">`
     *   - `<provider android:name="androidx.core.content.FileProvider" android:authorities="<pkg>.fileprovider">`
     */
    fun buildManifestAxml(
        packageName: String,
        useUtf8: Boolean,
        labelLiteral: String? = "Dualapps",
        labelResId: Int? = null,
        iconResId: Int? = null
    ): ByteArray {
        val strings = mutableListOf(
            "http://schemas.android.com/apk/res/android", // 0
            "manifest",                                   // 1
            "package",                                    // 2
            packageName,                                  // 3
            "permission",                                 // 4
            "name",                                       // 5
            "$packageName.CUSTOM_PERM",                   // 6
            "application",                                // 7
            ".App",                                       // 8
            "activity",                                   // 9
            "$packageName.MainActivity",                  // 10
            "provider",                                   // 11
            "androidx.core.content.FileProvider",         // 12
            "authorities",                                // 13
            "$packageName.fileprovider"                   // 14
        )
        val labelNameIdx = if (labelLiteral != null || labelResId != null) {
            strings.add("label")
            strings.lastIndex
        } else -1
        val labelValIdx = if (labelLiteral != null) {
            strings.add(labelLiteral)
            strings.lastIndex
        } else -1
        val iconNameIdx = if (iconResId != null) {
            strings.add("icon")
            strings.lastIndex
        } else -1

        val pool = AxmlStringPool(
            strings = strings,
            isUtf8 = useUtf8,
            originalFlags = if (useUtf8) AxmlStringPool.UTF8_FLAG else 0,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        )
        val poolBytes = pool.toByteArray()

        val body = ByteArrayOutputStream()
        // <manifest package="...">
        body.write(
            buildStartElement(
                nsIdx = -1,
                nameIdx = 1,
                attrs = listOf(AttrSpec(nsIdx = -1, nameIdx = 2, valueStrIdx = 3))
            )
        )
        // <permission android:name="<pkg>.CUSTOM_PERM"/>
        body.write(
            buildStartElement(
                nsIdx = -1,
                nameIdx = 4,
                attrs = listOf(AttrSpec(nsIdx = 0, nameIdx = 5, valueStrIdx = 6))
            )
        )
        body.write(buildEndElement(nsIdx = -1, nameIdx = 4))

        // <application android:name=".App" [android:label=...] [android:icon=...]>
        val appAttrs = mutableListOf(AttrSpec(nsIdx = 0, nameIdx = 5, valueStrIdx = 8))
        if (labelResId != null) {
            appAttrs.add(
                AttrSpec(
                    nsIdx = 0,
                    nameIdx = labelNameIdx,
                    valueStrIdx = -1,
                    valueType = 0x01,
                    valueData = labelResId
                )
            )
        } else if (labelLiteral != null) {
            appAttrs.add(AttrSpec(nsIdx = 0, nameIdx = labelNameIdx, valueStrIdx = labelValIdx))
        }
        if (iconResId != null) {
            appAttrs.add(
                AttrSpec(
                    nsIdx = 0,
                    nameIdx = iconNameIdx,
                    valueStrIdx = -1,
                    valueType = 0x01,
                    valueData = iconResId
                )
            )
        }
        body.write(buildStartElement(nsIdx = -1, nameIdx = 7, attrs = appAttrs))
        // <activity android:name="<pkg>.MainActivity"/>
        body.write(
            buildStartElement(
                nsIdx = -1,
                nameIdx = 9,
                attrs = listOf(AttrSpec(nsIdx = 0, nameIdx = 5, valueStrIdx = 10))
            )
        )
        body.write(buildEndElement(nsIdx = -1, nameIdx = 9))

        // <provider android:name="..." android:authorities="<pkg>.fileprovider"/>
        body.write(
            buildStartElement(
                nsIdx = -1,
                nameIdx = 11,
                attrs = listOf(
                    AttrSpec(nsIdx = 0, nameIdx = 5, valueStrIdx = 12),
                    AttrSpec(nsIdx = 0, nameIdx = 13, valueStrIdx = 14)
                )
            )
        )
        body.write(buildEndElement(nsIdx = -1, nameIdx = 11))
        body.write(buildEndElement(nsIdx = -1, nameIdx = 7))
        body.write(buildEndElement(nsIdx = -1, nameIdx = 1))

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

    /**
     * Membangun berkas adaptive-icon XML biner (`<adaptive-icon><background .../><foreground .../></adaptive-icon>`).
     */
    fun buildAdaptiveIconAxml(foregroundResId: Int, backgroundResId: Int): ByteArray {
        val strings = mutableListOf(
            "http://schemas.android.com/apk/res/android", // 0
            "adaptive-icon",                              // 1
            "background",                                 // 2
            "foreground",                                 // 3
            "drawable"                                    // 4
        )
        val poolBytes = AxmlStringPool(
            strings = strings,
            isUtf8 = true,
            originalFlags = AxmlStringPool.UTF8_FLAG,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        ).toByteArray()

        val body = ByteArrayOutputStream()
        body.write(buildStartElement(-1, 1, emptyList()))
        body.write(
            buildStartElement(
                -1,
                2,
                listOf(AttrSpec(0, 4, valueStrIdx = -1, valueType = 0x01, valueData = backgroundResId))
            )
        )
        body.write(buildEndElement(-1, 2))
        body.write(
            buildStartElement(
                -1,
                3,
                listOf(AttrSpec(0, 4, valueStrIdx = -1, valueType = 0x01, valueData = foregroundResId))
            )
        )
        body.write(buildEndElement(-1, 3))
        body.write(buildEndElement(-1, 1))

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

    /**
     * Membangun berkas VectorDrawable XML biner (`<vector/>`) minimal untuk fixture uji Fase 6.
     */
    fun buildVectorDrawableAxml(): ByteArray {
        val strings = mutableListOf(
            "http://schemas.android.com/apk/res/android", // 0
            "vector",                                     // 1
            "path"                                        // 2
        )
        val poolBytes = AxmlStringPool(
            strings = strings,
            isUtf8 = true,
            originalFlags = AxmlStringPool.UTF8_FLAG,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        ).toByteArray()

        val body = ByteArrayOutputStream()
        body.write(buildStartElement(-1, 1, emptyList()))
        body.write(buildStartElement(-1, 2, emptyList()))
        body.write(buildEndElement(-1, 2))
        body.write(buildEndElement(-1, 1))

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

    /**
     * Membangun APK fixture meniru struktur Muse (`com.facebook.aura`):
     * - `android:icon=@0x7F0F0000` -> `res/bi2.xml` (`<adaptive-icon>`)
     * - `<background>@0x7F0800AD` -> `res/js.xml`
     * - `<foreground>@0x7F0800AE` -> `res/jt.xml` (vector XML)
     * - Tidak memiliki entri PNG ikon sama sekali di dalam APK.
     */
    fun buildVectorAdaptiveIconApk(
        outputFile: File,
        packageName: String
    ): Pair<ByteArray, ByteArray> {
        val iconXmlResId = 0x7F0F0000
        val bgResId = 0x7F0800AD
        val fgResId = 0x7F0800AE

        val adaptiveXml = buildAdaptiveIconAxml(foregroundResId = fgResId, backgroundResId = bgResId)
        val bgXml = buildVectorDrawableAxml()
        val fgVectorXml = buildVectorDrawableAxml()

        val arsc = buildArscWithTypeChunks(
            packageName = packageName,
            useUtf8 = true,
            typeChunks = listOf(
                0x0F to listOf(0x0000 to "res/bi2.xml"),
                0x08 to listOf(
                    0x00AD to "res/js.xml",
                    0x00AE to "res/jt.xml"
                )
            )
        )

        AlignedZipWriter(outputFile).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                buildManifestAxml(packageName, useUtf8 = true, labelLiteral = "Muse", iconResId = iconXmlResId)
            )
            w.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", "dex\n035\u0000FAKE_DEX".toByteArray())
            w.writeDeflatedEntry("res/bi2.xml", adaptiveXml)
            w.writeDeflatedEntry("res/js.xml", bgXml)
            w.writeDeflatedEntry("res/jt.xml", fgVectorXml)
        }
        return adaptiveXml to bgXml
    }

    /**
     * Membangun APK fixture dengan `android:icon` menunjuk langsung ke vector XML (non-adaptive)
     * tanpa PNG ikon sama sekali.
     */
    fun buildDirectVectorIconApk(outputFile: File, packageName: String) {
        val iconResId = 0x7F080001
        val vectorXml = buildVectorDrawableAxml()
        val arsc = buildArscWithTypeChunks(
            packageName = packageName,
            useUtf8 = true,
            typeChunks = listOf(
                0x08 to listOf(0x0001 to "res/drawable/ic_vector_app.xml")
            )
        )

        AlignedZipWriter(outputFile).use { w ->
            w.writeDeflatedEntry(
                "AndroidManifest.xml",
                buildManifestAxml(packageName, useUtf8 = true, labelLiteral = "VectorApp", iconResId = iconResId)
            )
            w.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
            w.writeDeflatedEntry("classes.dex", "dex\n035\u0000FAKE_DEX".toByteArray())
            w.writeDeflatedEntry("res/drawable/ic_vector_app.xml", vectorXml)
        }
    }

    private data class AttrSpec(
        val nsIdx: Int,
        val nameIdx: Int,
        val valueStrIdx: Int,
        val valueType: Int = 0x03,
        val valueData: Int = valueStrIdx
    )

    private fun buildStartElement(nsIdx: Int, nameIdx: Int, attrs: List<AttrSpec>): ByteArray {
        val headerSize = 16
        val extSize = 20
        val attrSize = 20
        val chunkSize = headerSize + extSize + attrs.size * attrSize
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        // ResXMLTree_node (16B)
        buf.putShort(AxmlPatcher.RES_XML_START_ELEMENT_TYPE.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(1)  // lineNumber
        buf.putInt(-1) // comment
        // ResXMLTree_attrExt (20B)
        buf.putInt(nsIdx)
        buf.putInt(nameIdx)
        buf.putShort(extSize.toShort())   // attributeStart
        buf.putShort(attrSize.toShort())  // attributeSize
        buf.putShort(attrs.size.toShort()) // attributeCount
        buf.putShort(0) // idIndex
        buf.putShort(0) // classIndex
        buf.putShort(0) // styleIndex
        // Attributes (20B each)
        for (a in attrs) {
            buf.putInt(a.nsIdx)
            buf.putInt(a.nameIdx)
            buf.putInt(a.valueStrIdx) // rawValue
            buf.putShort(8)           // Res_value.size
            buf.put(0)                // res0
            buf.put(a.valueType.toByte())
            buf.putInt(a.valueData)   // data
        }
        return buf.array()
    }

    private fun buildEndElement(nsIdx: Int, nameIdx: Int): ByteArray {
        val chunkSize = 24
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0103.toShort()) // RES_XML_END_ELEMENT_TYPE
        buf.putShort(16)
        buf.putInt(chunkSize)
        buf.putInt(1)
        buf.putInt(-1)
        buf.putInt(nsIdx)
        buf.putInt(nameIdx)
        return buf.array()
    }

    /**
     * Membangun `resources.arsc` biner valid dengan:
     * - ResTable_header (12B)
     * - Global ResStringPool (isi [poolStrings])
     * - ResTable_package (header 288B termasuk 128-char UTF-16LE `name`, + payload dummy 16B)
     */
    fun buildArsc(
        packageName: String,
        poolStrings: List<String> = listOf("Dualapps", "Halo Dunia"),
        packageId: Int = 0x7F
    ): ByteArray {
        val globalPool = AxmlStringPool(
            strings = poolStrings.toMutableList(),
            isUtf8 = true,
            originalFlags = AxmlStringPool.UTF8_FLAG,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        ).toByteArray()

        val pkgHeaderSize = 288 // standar Android ResTable_package header
        val pkgPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val pkgChunkSize = pkgHeaderSize + pkgPayload.size
        val pkgBuf = ByteBuffer.allocate(pkgChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        pkgBuf.putShort(ArscPatcher.RES_TABLE_PACKAGE_TYPE.toShort())
        pkgBuf.putShort(pkgHeaderSize.toShort())
        pkgBuf.putInt(pkgChunkSize)
        pkgBuf.putInt(packageId)

        // 128 char UTF-16LE (256 byte)
        val nameBytes = ByteArray(256)
        for (i in packageName.indices.take(127)) {
            val c = packageName[i].code
            nameBytes[i * 2] = (c and 0xFF).toByte()
            nameBytes[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        pkgBuf.put(nameBytes)
        // Sisa header ResTable_package (typeStrings, lastPublicType, keyStrings, lastPublicKey, typeIdOffset = 20B)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.put(pkgPayload)

        val totalSize = 12 + globalPool.size + pkgChunkSize
        val tableBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        tableBuf.putShort(ArscPatcher.RES_TABLE_TYPE.toShort())
        tableBuf.putShort(12)
        tableBuf.putInt(totalSize)
        tableBuf.putInt(1) // packageCount
        tableBuf.put(globalPool)
        tableBuf.put(pkgBuf.array())
        return tableBuf.array()
    }

    /**
     * Membangun `resources.arsc` lengkap dengan chunk `ResTable_type` (0x0201) yang berisi
     * entri-entri string/path untuk menguji patch `@string` (UTF-8 & UTF-16) dan resolusi
     * path foreground adaptive icon.
     *
     * Setiap item pada [typeChunks] adalah `(typeId, listOf(entryId to stringValue))`.
     */
    fun buildArscWithTypeChunks(
        packageName: String,
        useUtf8: Boolean,
        typeChunks: List<Pair<Int, List<Pair<Int, String>>>>,
        packageId: Int = 0x7F
    ): ByteArray {
        val poolStrings = mutableListOf<String>()
        fun addString(s: String): Int {
            val idx = poolStrings.indexOf(s)
            if (idx >= 0) return idx
            poolStrings.add(s)
            return poolStrings.lastIndex
        }

        val typeChunkBytesList = mutableListOf<ByteArray>()
        for ((typeId, entries) in typeChunks) {
            val maxEntryId = entries.maxOfOrNull { it.first } ?: 0
            val entryCount = maxEntryId + 1
            val typeHeaderSize = 68 // 20B fixed + 48B ResTable_config
            val offsetsSize = entryCount * 4
            val entriesStart = typeHeaderSize + offsetsSize

            val offsets = IntArray(entryCount) { -1 }
            val entriesStream = ByteArrayOutputStream()
            for ((entryId, value) in entries) {
                val strIdx = addString(value)
                offsets[entryId] = entriesStream.size()
                val eBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                // ResTable_entry (8B): size=8, flags=0, key=entryId
                eBuf.putShort(8)
                eBuf.putShort(0)
                eBuf.putInt(entryId)
                // Res_value (8B): size=8, res0=0, dataType=TYPE_STRING(0x03), data=strIdx
                eBuf.putShort(8)
                eBuf.put(0)
                eBuf.put(0x03)
                eBuf.putInt(strIdx)
                entriesStream.write(eBuf.array())
            }

            val entriesData = entriesStream.toByteArray()
            val chunkSize = entriesStart + entriesData.size
            val tBuf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
            tBuf.putShort(ArscPatcher.RES_TABLE_TYPE_TYPE.toShort())
            tBuf.putShort(typeHeaderSize.toShort())
            tBuf.putInt(chunkSize)
            tBuf.put(typeId.toByte())
            tBuf.put(0) // flags
            tBuf.putShort(0) // reserved
            tBuf.putInt(entryCount)
            tBuf.putInt(entriesStart)
            // ResTable_config (48B zeros, size=48)
            tBuf.putInt(48)
            tBuf.put(ByteArray(44))
            for (off in offsets) {
                tBuf.putInt(off)
            }
            tBuf.put(entriesData)
            typeChunkBytesList.add(tBuf.array())
        }

        val globalPool = AxmlStringPool(
            strings = poolStrings,
            isUtf8 = useUtf8,
            originalFlags = if (useUtf8) AxmlStringPool.UTF8_FLAG else 0,
            styleOffsets = IntArray(0),
            stylesData = ByteArray(0)
        ).toByteArray()

        val pkgHeaderSize = 288
        val childChunksSize = typeChunkBytesList.sumOf { it.size }
        val pkgChunkSize = pkgHeaderSize + childChunksSize
        val pkgBuf = ByteBuffer.allocate(pkgChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        pkgBuf.putShort(ArscPatcher.RES_TABLE_PACKAGE_TYPE.toShort())
        pkgBuf.putShort(pkgHeaderSize.toShort())
        pkgBuf.putInt(pkgChunkSize)
        pkgBuf.putInt(packageId)

        val nameBytes = ByteArray(256)
        for (i in packageName.indices.take(127)) {
            val c = packageName[i].code
            nameBytes[i * 2] = (c and 0xFF).toByte()
            nameBytes[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        pkgBuf.put(nameBytes)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        pkgBuf.putInt(0)
        for (chunk in typeChunkBytesList) {
            pkgBuf.put(chunk)
        }

        val totalSize = 12 + globalPool.size + pkgChunkSize
        val tableBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        tableBuf.putShort(ArscPatcher.RES_TABLE_TYPE.toShort())
        tableBuf.putShort(12)
        tableBuf.putInt(totalSize)
        tableBuf.putInt(1)
        tableBuf.put(globalPool)
        tableBuf.put(pkgBuf.array())
        return tableBuf.array()
    }

    /**
     * Membangun berkas APK fixture kecil yang berisi AndroidManifest.xml, resources.arsc,
     * classes.dex, libnative.so (STORED), res/raw/data.bin (STORED), dan berkas tanda tangan lama META-INF/.
     */
    fun buildFixtureApk(
        outputFile: File,
        packageName: String,
        useUtf8Manifest: Boolean = true,
        dexPayload: ByteArray = "dex\n035\u0000FAKE_DEX_CONTENT_1234567890".toByteArray()
    ) {
        val manifest = buildManifestAxml(packageName, useUtf8Manifest)
        val arsc = buildArsc(packageName)
        val fakeSo = ByteArray(128) { (it and 0xFF).toByte() }
        val fakeRaw = "RAW_STORED_ASSET".toByteArray()

        AlignedZipWriter(outputFile).use { writer ->
            writer.writeDeflatedEntry("AndroidManifest.xml", manifest)
            writer.writeStoredEntry("resources.arsc", arsc, AlignedZipWriter.ALIGN_DEFAULT)
            writer.writeDeflatedEntry("classes.dex", dexPayload)
            writer.writeStoredEntry("lib/arm64-v8a/libnative.so", fakeSo, AlignedZipWriter.ALIGN_SO)
            writer.writeStoredEntry("res/raw/data.bin", fakeRaw, AlignedZipWriter.ALIGN_DEFAULT)
            // Berkas tanda tangan lama yang harus dibuang
            writer.writeDeflatedEntry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".toByteArray())
            writer.writeDeflatedEntry("META-INF/CERT.SF", "Signature-Version: 1.0\n".toByteArray())
            writer.writeDeflatedEntry("META-INF/CERT.RSA", byteArrayOf(0x30, 0x01, 0x02))
            writer.writeDeflatedEntry("META-INF/CERT.DSA", byteArrayOf(0x30, 0x03, 0x04))
            writer.writeDeflatedEntry("META-INF/CERT.EC", byteArrayOf(0x30, 0x05, 0x06))
            // Subfolder META-INF/services/ harus dipertahankan
            writer.writeDeflatedEntry("META-INF/services/example.Service", "com.example.Impl\n".toByteArray())
        }
    }
}
