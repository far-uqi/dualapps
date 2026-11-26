package com.dualapps.cloner.engine

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Informasi provider hasil resolusi aplikasi terpasang (Aturan B — lintas-aplikasi).
 */
data class ResolvedProviderInfo(
    val authority: String,
    val isSignatureProtected: Boolean,
    val signaturePermissionName: String?,
    val ownerPackageName: String? = null
)

/**
 * Abstraksi lookup `ContentProvider` terpasang agar dapat di-stub di unit test JVM murni.
 */
fun interface ContentProviderResolver {
    fun resolve(authority: String): ResolvedProviderInfo?
}

/**
 * Implementasi runtime Android memakai `PackageManager.resolveContentProvider`.
 */
class AndroidContentProviderResolver(
    private val packageManager: PackageManager
) : ContentProviderResolver {

    override fun resolve(authority: String): ResolvedProviderInfo? {
        val providerInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveContentProvider(
                    authority,
                    PackageManager.ComponentInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveContentProvider(authority, 0)
            }
        }.getOrNull() ?: return null

        val permCandidates = listOfNotNull(
            providerInfo.readPermission?.takeIf { it.isNotBlank() },
            providerInfo.writePermission?.takeIf { it.isNotBlank() }
        ).distinct()

        for (permName in permCandidates) {
            val permInfo = runCatching {
                packageManager.getPermissionInfo(permName, 0)
            }.getOrNull() ?: continue
            if (CloneCompatibilityChecker.isSignatureProtectionLevel(permInfo.protectionLevel)) {
                return ResolvedProviderInfo(
                    authority = authority,
                    isSignatureProtected = true,
                    signaturePermissionName = permName,
                    ownerPackageName = providerInfo.packageName
                )
            }
        }

        return ResolvedProviderInfo(
            authority = authority,
            isSignatureProtected = false,
            signaturePermissionName = null,
            ownerPackageName = providerInfo.packageName
        )
    }
}

enum class CompatibilityRule {
    A,
    B
}

enum class CompatibilitySeverity {
    BLOCKING,
    WARNING
}

data class CompatibilityIssue(
    val authority: String,
    val permission: String,
    val reason: String,
    val rule: CompatibilityRule = CompatibilityRule.A,
    val severity: CompatibilitySeverity = CompatibilitySeverity.BLOCKING,
    val ownerPackageName: String? = null
)

data class CompatibilityReport(
    val issues: List<CompatibilityIssue>
) {
    val blockingIssues: List<CompatibilityIssue>
        get() = issues.filter { it.severity == CompatibilitySeverity.BLOCKING }

    val warnings: List<CompatibilityIssue>
        get() = issues.filter { it.severity == CompatibilitySeverity.WARNING }

    val hasBlockingIssues: Boolean get() = blockingIssues.isNotEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
    val isCompatible: Boolean get() = issues.isEmpty()

    fun formatUserMessage(): String {
        val primary = blockingIssues.ifEmpty { issues }
        if (primary.isEmpty()) return ""
        val baseMsg = if (primary.size == 1) {
            "Aplikasi ini tidak bisa di-clone. Alasan: ${primary.first().reason} Clone akan tertutup sendiri saat dibuka."
        } else {
            val details = primary.take(3).joinToString("; ") { "${it.authority} (${it.permission})" }
            val extra = if (primary.size > 3) " dan ${primary.size - 3} lainnya" else ""
            "Aplikasi ini tidak bisa di-clone. Alasan: aplikasinya memanggil provider ber-permission signature secara langsung ($details$extra). Clone akan tertutup sendiri saat dibuka."
        }
        val extraWarnings = if (blockingIssues.isNotEmpty() && warnings.isNotEmpty()) {
            val wSummary = warnings.take(2).joinToString("; ") { "${it.authority} (${it.permission})" }
            "\nCatatan lintas-aplikasi: $wSummary."
        } else {
            ""
        }
        return baseMsg + extraWarnings
    }

    fun formatWarningMessage(): String {
        val list = warnings
        if (list.isEmpty()) return ""
        if (list.size == 1) {
            val w = list.first()
            val ownerSuffix = w.ownerPackageName?.takeIf { it.isNotBlank() }?.let { " milik $it" }.orEmpty()
            return "Peringatan: aplikasi ini memanggil provider ${w.authority}$ownerSuffix yang dikunci permission signature ${w.permission}. Biasanya ditangani aplikasi dengan aman, tetapi ada risiko clone tertutup saat fitur itu dipakai."
        }
        val details = list.take(3).joinToString("; ") { "${it.authority} (${it.permission})" }
        val extra = if (list.size > 3) " dan ${list.size - 3} lainnya" else ""
        return "Peringatan: aplikasi ini memanggil provider lintas-aplikasi ber-permission signature ($details$extra). Biasanya ditangani aplikasi dengan aman, tetapi ada risiko clone tertutup saat fitur itu dipakai."
    }
}

/**
 * Pemeriksa kompatibilitas pra-clone (Fase 13).
 *
 * Mendeteksi pola berbahaya "provider ber-permission signature yang URI `content://`-nya
 * di-hardcode di DEX" secara read-only sebelum langkah patching AXML dimulai:
 * - Aturan A: authority dideklarasikan di manifest target sendiri & dilindungi permission `signature`/`signatureOrSystem`.
 * - Aturan B: authority milik aplikasi lain yang terpasang & dilindungi permission `signature`/`signatureOrSystem`.
 */
object CloneCompatibilityChecker {

    private const val CONTENT_URI_PREFIX = "content://"
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val PROTECTION_MASK_BASE = 0x0F
    private const val PROTECTION_SIGNATURE = 0x02
    private const val PROTECTION_SIGNATURE_OR_SYSTEM = 0x03

    internal fun isSignatureProtectionLevel(protectionLevel: Int): Boolean {
        val base = protectionLevel and PROTECTION_MASK_BASE
        return base == PROTECTION_SIGNATURE || base == PROTECTION_SIGNATURE_OR_SYSTEM
    }

    /**
     * Memetik bagian authority dari URI `content://<authority>/...`.
     */
    fun extractAuthorityFromContentUri(uri: String): String? {
        if (!uri.startsWith(CONTENT_URI_PREFIX, ignoreCase = false)) return null
        val rest = uri.substring(CONTENT_URI_PREFIX.length)
        if (rest.isEmpty()) return null
        val endIdx = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = (if (endIdx >= 0) rest.substring(0, endIdx) else rest).trim()
        return authority.takeIf { it.isNotEmpty() && !it.contains(' ') }
    }

    /**
     * Memeriksa kumpulan berkas APK sumber (base + seluruh split) sebelum proses patching.
     * Bersifat fail-open bila berkas DEX/manifest tertentu gagal dibaca.
     */
    fun checkApkFiles(
        apkFiles: List<File>,
        contentProviderResolver: ContentProviderResolver = ContentProviderResolver { null }
    ): CompatibilityReport {
        val targetPermissions = LinkedHashMap<String, Int>()
        val targetProviders = LinkedHashMap<String, MutableSet<String>>()
        val contentUris = LinkedHashSet<String>()

        for (apkFile in apkFiles) {
            runCatching {
                ZipFile(apkFile).use { zip ->
                    val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    if (manifestEntry != null) {
                        val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                        val parsed = parseManifestProvidersAndPermissions(manifestBytes)
                        targetPermissions.putAll(parsed.permissionProtectionLevels)
                        for ((auth, perms) in parsed.providerPermissions) {
                            targetProviders.getOrPut(auth) { LinkedHashSet() }.addAll(perms)
                        }
                    }

                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory && isDexEntryName(entry.name)) {
                            val dexBytes = runCatching {
                                zip.getInputStream(entry).use { it.readBytes() }
                            }.getOrNull() ?: continue
                            contentUris.addAll(
                                DexStringReader.readStringsWithPrefix(dexBytes, CONTENT_URI_PREFIX)
                            )
                        }
                    }
                }
            }
        }

        return evaluate(
            targetPermissionLevels = targetPermissions,
            targetProviders = targetProviders,
            dexStrings = contentUris,
            contentProviderResolver = contentProviderResolver
        )
    }

    /**
     * Memeriksa kecocokan antara satu manifest AXML biner dengan kumpulan string DEX.
     */
    fun check(
        manifestAxmlBytes: ByteArray,
        dexStrings: Collection<String>,
        contentProviderResolver: ContentProviderResolver = ContentProviderResolver { null }
    ): CompatibilityReport {
        val parsed = runCatching { parseManifestProvidersAndPermissions(manifestAxmlBytes) }
            .getOrElse { return CompatibilityReport(emptyList()) }
        return evaluate(
            targetPermissionLevels = parsed.permissionProtectionLevels,
            targetProviders = parsed.providerPermissions,
            dexStrings = dexStrings,
            contentProviderResolver = contentProviderResolver
        )
    }

    private fun evaluate(
        targetPermissionLevels: Map<String, Int>,
        targetProviders: Map<String, Set<String>>,
        dexStrings: Collection<String>,
        contentProviderResolver: ContentProviderResolver
    ): CompatibilityReport {
        val authorities = LinkedHashSet<String>()
        for (str in dexStrings) {
            extractAuthorityFromContentUri(str)?.let { authorities.add(it) }
        }
        if (authorities.isEmpty()) return CompatibilityReport(emptyList())

        val issues = mutableListOf<CompatibilityIssue>()
        val seenKeys = HashSet<String>()

        for (authority in authorities) {
            val targetPermNames = targetProviders[authority]
            if (targetPermNames != null) {
                // Aturan A: provider milik target sendiri di manifest asli
                for (permName in targetPermNames) {
                    val level = targetPermissionLevels[permName]
                    if (level != null && isSignatureProtectionLevel(level)) {
                        val key = "$authority|$permName"
                        if (seenKeys.add(key)) {
                            issues.add(
                                CompatibilityIssue(
                                    authority = authority,
                                    permission = permName,
                                    reason = "aplikasinya memanggil provider $authority yang dikunci permission signature $permName.",
                                    rule = CompatibilityRule.A,
                                    severity = CompatibilitySeverity.BLOCKING
                                )
                            )
                        }
                    }
                }
            } else {
                // Aturan B: provider milik aplikasi lain yang terpasang (lintas-aplikasi) -> WARNING
                val resolved = runCatching { contentProviderResolver.resolve(authority) }.getOrNull()
                if (resolved != null &&
                    resolved.isSignatureProtected &&
                    !resolved.signaturePermissionName.isNullOrBlank()
                ) {
                    val permName = resolved.signaturePermissionName
                    val key = "$authority|$permName"
                    if (seenKeys.add(key)) {
                        val ownerSuffix = resolved.ownerPackageName
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " milik $it" }
                            .orEmpty()
                        issues.add(
                            CompatibilityIssue(
                                authority = authority,
                                permission = permName,
                                reason = "aplikasinya memanggil provider $authority$ownerSuffix yang dikunci permission signature $permName.",
                                rule = CompatibilityRule.B,
                                severity = CompatibilitySeverity.WARNING,
                                ownerPackageName = resolved.ownerPackageName
                            )
                        )
                    }
                }
            }
        }

        return CompatibilityReport(issues)
    }

    private data class ParsedManifestSecurity(
        val permissionProtectionLevels: Map<String, Int>,
        val providerPermissions: Map<String, Set<String>>
    )

    /**
     * Membaca deklarasi `<permission>` (beserta `protectionLevel`) dan `<provider>`
     * (beserta `authorities`, `permission`, `readPermission`, `writePermission`) dari AXML biner
     * menggunakan [AxmlStringPool.parse] yang sudah ada.
     */
    private fun parseManifestProvidersAndPermissions(axmlBytes: ByteArray): ParsedManifestSecurity {
        if (axmlBytes.size < 16) return ParsedManifestSecurity(emptyMap(), emptyMap())
        val rootType = readU16(axmlBytes, 0)
        val xmlHeaderSize = readU16(axmlBytes, 2)
        val fileSize = readI32(axmlBytes, 4)
        if (rootType != AxmlPatcher.RES_XML_TYPE ||
            xmlHeaderSize < 8 ||
            fileSize > axmlBytes.size ||
            xmlHeaderSize + 8 > axmlBytes.size
        ) {
            return ParsedManifestSecurity(emptyMap(), emptyMap())
        }

        val poolType = readU16(axmlBytes, xmlHeaderSize)
        val poolSize = readI32(axmlBytes, xmlHeaderSize + 4)
        if (poolType != AxmlPatcher.RES_STRING_POOL_TYPE || xmlHeaderSize + poolSize > axmlBytes.size) {
            return ParsedManifestSecurity(emptyMap(), emptyMap())
        }
        val pool = AxmlStringPool.parse(axmlBytes, xmlHeaderSize)

        val permLevels = LinkedHashMap<String, Int>()
        val providerPerms = LinkedHashMap<String, MutableSet<String>>()

        var offset = xmlHeaderSize + poolSize
        while (offset + 8 <= fileSize) {
            val type = readU16(axmlBytes, offset)
            val headerSize = readU16(axmlBytes, offset + 2)
            val chunkSize = readI32(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > fileSize) break

            if (type == AxmlPatcher.RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= offset + chunkSize) {
                val extStart = offset + headerSize
                val tagName = pool.strings.getOrNull(readI32(axmlBytes, extStart + 4))
                if (tagName == "permission" || tagName == "provider") {
                    val attrStart = readU16(axmlBytes, extStart + 8)
                    val attrSize = readU16(axmlBytes, extStart + 10)
                    val attrCount = readU16(axmlBytes, extStart + 12)
                    val attrsBase = extStart + attrStart

                    if (tagName == "permission") {
                        var permName: String? = null
                        var protectionLevel = 0
                        for (i in 0 until attrCount) {
                            val attrOff = attrsBase + i * attrSize
                            if (attrOff + 20 > offset + chunkSize) break
                            val attrName = pool.strings.getOrNull(readI32(axmlBytes, attrOff + 4))
                            val rawValueIdx = readI32(axmlBytes, attrOff + 8)
                            val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                            val valueData = readI32(axmlBytes, attrOff + 16)
                            when (attrName) {
                                "name" -> {
                                    permName = readAttrString(pool, rawValueIdx, valueType, valueData)
                                }
                                "protectionLevel" -> {
                                    if (valueType == TYPE_INT_DEC || valueType == TYPE_INT_HEX) {
                                        protectionLevel = valueData
                                    }
                                }
                            }
                        }
                        if (!permName.isNullOrBlank()) {
                            permLevels[permName] = protectionLevel
                        }
                    } else {
                        var authoritiesRaw: String? = null
                        val perms = LinkedHashSet<String>()
                        for (i in 0 until attrCount) {
                            val attrOff = attrsBase + i * attrSize
                            if (attrOff + 20 > offset + chunkSize) break
                            val attrName = pool.strings.getOrNull(readI32(axmlBytes, attrOff + 4))
                            val rawValueIdx = readI32(axmlBytes, attrOff + 8)
                            val valueType = axmlBytes[attrOff + 15].toInt() and 0xFF
                            val valueData = readI32(axmlBytes, attrOff + 16)
                            when (attrName) {
                                "authorities" -> {
                                    authoritiesRaw = readAttrString(pool, rawValueIdx, valueType, valueData)
                                }
                                "permission", "readPermission", "writePermission" -> {
                                    readAttrString(pool, rawValueIdx, valueType, valueData)
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { perms.add(it) }
                                }
                            }
                        }
                        if (!authoritiesRaw.isNullOrBlank()) {
                            for (part in authoritiesRaw.split(";")) {
                                val auth = part.trim()
                                if (auth.isNotEmpty()) {
                                    providerPerms.getOrPut(auth) { LinkedHashSet() }.addAll(perms)
                                }
                            }
                        }
                    }
                }
            }
            offset += chunkSize
        }

        return ParsedManifestSecurity(permLevels, providerPerms)
    }

    private fun readAttrString(
        pool: AxmlStringPool,
        rawValueIdx: Int,
        valueType: Int,
        valueData: Int
    ): String? {
        val strIdx = when {
            rawValueIdx >= 0 -> rawValueIdx
            valueType == TYPE_STRING && valueData >= 0 -> valueData
            else -> -1
        }
        return pool.strings.getOrNull(strIdx)
    }

    private fun isDexEntryName(entryName: String): Boolean {
        return entryName.startsWith("classes") && entryName.endsWith(".dex") && !entryName.contains('/')
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
