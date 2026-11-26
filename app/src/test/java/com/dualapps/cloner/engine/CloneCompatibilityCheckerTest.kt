package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CloneCompatibilityCheckerTest {

    @Test
    fun flagsFacebookLikeSignatureProviderReferencedByHardcodedDexUri() {
        val manifest = buildManifestWithProvidersAndPermissions(
            packageName = "com.example.katana",
            permissions = listOf(
                PermDef("com.example.permission.prod.FB_APP_COMMUNICATION", protectionLevel = 0x00000002)
            ),
            providers = listOf(
                ProviderDef(
                    authorities = "com.example.katana.ClientMessagePushDedupInfoProvider",
                    permission = "com.example.permission.prod.FB_APP_COMMUNICATION"
                )
            )
        )
        val dexStrings = listOf(
            "content://com.example.katana.ClientMessagePushDedupInfoProvider/mutestatus",
            "https://example.com"
        )

        val report = CloneCompatibilityChecker.check(manifest, dexStrings)
        assertFalse(report.isCompatible)
        assertEquals(1, report.issues.size)
        val issue = report.issues.single()
        assertEquals("com.example.katana.ClientMessagePushDedupInfoProvider", issue.authority)
        assertEquals("com.example.permission.prod.FB_APP_COMMUNICATION", issue.permission)
    }

    @Test
    fun doesNotFlagSameProviderWhenNotSignatureProtected() {
        val manifest = buildManifestWithProvidersAndPermissions(
            packageName = "com.example.katana",
            permissions = listOf(
                // protectionLevel 0 = normal (bukan signature)
                PermDef("com.example.permission.NORMAL_PERM", protectionLevel = 0x00000000)
            ),
            providers = listOf(
                ProviderDef(
                    authorities = "com.example.katana.ClientMessagePushDedupInfoProvider",
                    permission = "com.example.permission.NORMAL_PERM"
                )
            )
        )
        val dexStrings = listOf(
            "content://com.example.katana.ClientMessagePushDedupInfoProvider/mutestatus"
        )

        val report = CloneCompatibilityChecker.check(manifest, dexStrings)
        assertTrue(report.isCompatible)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun flagsCrossAppSignatureProviderAndIgnoresNonSignatureViaResolver() {
        val manifest = buildManifestWithProvidersAndPermissions(
            packageName = "com.example.katana",
            permissions = emptyList(),
            providers = emptyList()
        )
        val dexStrings = listOf(
            "content://com.facebook.orca.ClientMessagePushDedupInfoProvider/mutestatus"
        )

        // Stub mengembalikan provider lintas-aplikasi dengan permission signature -> harus di-flag
        val sigResolver = ContentProviderResolver { auth ->
            if (auth == "com.facebook.orca.ClientMessagePushDedupInfoProvider") {
                ResolvedProviderInfo(
                    authority = auth,
                    isSignatureProtected = true,
                    signaturePermissionName = "com.facebook.permission.prod.FB_APP_COMMUNICATION",
                    ownerPackageName = "com.facebook.orca"
                )
            } else null
        }
        val sigReport = CloneCompatibilityChecker.check(manifest, dexStrings, sigResolver)
        assertFalse(sigReport.isCompatible)
        assertEquals(1, sigReport.issues.size)
        assertEquals("com.facebook.orca.ClientMessagePushDedupInfoProvider", sigReport.issues.single().authority)
        assertEquals("com.facebook.permission.prod.FB_APP_COMMUNICATION", sigReport.issues.single().permission)

        // Stub mengembalikan provider TANPA signature -> tidak boleh di-flag
        val nonSigResolver = ContentProviderResolver { auth ->
            ResolvedProviderInfo(
                authority = auth,
                isSignatureProtected = false,
                signaturePermissionName = null,
                ownerPackageName = "com.facebook.orca"
            )
        }
        val cleanReport = CloneCompatibilityChecker.check(manifest, dexStrings, nonSigResolver)
        assertTrue(cleanReport.isCompatible)
    }

    @Test
    fun doesNotFlagMuseLikeCleanManifestAndDex() {
        val manifest = buildManifestWithProvidersAndPermissions(
            packageName = "com.example.aura",
            permissions = listOf(
                PermDef("com.example.aura.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", protectionLevel = 0x00000002)
            ),
            providers = listOf(
                ProviderDef(authorities = "com.example.aura.firebaseinitprovider", permission = null),
                ProviderDef(authorities = "com.example.aura.androidx-startup", permission = null),
                ProviderDef(authorities = "com.example.aura.aura.fileprovider", permission = null)
            )
        )
        val dexStrings = listOf(
            "content://media/external/images/media",
            "content://com.example.aura.aura.fileprovider/shared",
            "Lcom/example/aura/MainActivity;"
        )

        val report = CloneCompatibilityChecker.check(manifest, dexStrings)
        assertTrue(report.isCompatible)
    }

    @Test
    fun formattedErrorMessageIncludesAuthorityPermissionAndConsequence() {
        val report = CompatibilityReport(
            issues = listOf(
                CompatibilityIssue(
                    authority = "com.facebook.katana.ClientMessagePushDedupInfoProvider",
                    permission = "com.facebook.permission.prod.FB_APP_COMMUNICATION",
                    reason = "aplikasinya memanggil provider com.facebook.katana.ClientMessagePushDedupInfoProvider yang dikunci permission signature com.facebook.permission.prod.FB_APP_COMMUNICATION."
                )
            )
        )
        val msg = report.formatUserMessage()
        assertTrue(CloneError.INCOMPATIBLE_APP.userMessage.isNotBlank())
        assertTrue(msg.contains("tidak bisa di-clone"))
        assertTrue(msg.contains("com.facebook.katana.ClientMessagePushDedupInfoProvider"))
        assertTrue(msg.contains("com.facebook.permission.prod.FB_APP_COMMUNICATION"))
        assertTrue(msg.contains("tertutup sendiri"))
    }

    companion object {
        data class PermDef(val name: String, val protectionLevel: Int)
        data class ProviderDef(
            val authorities: String,
            val permission: String? = null,
            val readPermission: String? = null,
            val writePermission: String? = null
        )

        fun buildManifestWithProvidersAndPermissions(
            packageName: String,
            permissions: List<PermDef>,
            providers: List<ProviderDef>
        ): ByteArray {
            val strings = mutableListOf(
                "http://schemas.android.com/apk/res/android", // 0
                "manifest",                                   // 1
                "package",                                    // 2
                packageName                                   // 3
            )
            fun idxOf(s: String): Int {
                val i = strings.indexOf(s)
                if (i >= 0) return i
                strings.add(s)
                return strings.lastIndex
            }

            data class AttrVal(
                val nsIdx: Int,
                val nameIdx: Int,
                val rawStrIdx: Int,
                val valueType: Int,
                val valueData: Int
            )

            val elementChunks = mutableListOf<Pair<Int, List<AttrVal>>>()
            for (p in permissions) {
                val tagIdx = idxOf("permission")
                val nameKey = idxOf("name")
                val nameVal = idxOf(p.name)
                val protKey = idxOf("protectionLevel")
                elementChunks.add(
                    tagIdx to listOf(
                        AttrVal(0, nameKey, nameVal, 0x03, nameVal),
                        AttrVal(0, protKey, -1, 0x11, p.protectionLevel)
                    )
                )
            }
            for (pr in providers) {
                val tagIdx = idxOf("provider")
                val attrs = mutableListOf<AttrVal>()
                val authKey = idxOf("authorities")
                val authVal = idxOf(pr.authorities)
                attrs.add(AttrVal(0, authKey, authVal, 0x03, authVal))
                pr.permission?.let {
                    val k = idxOf("permission")
                    val v = idxOf(it)
                    attrs.add(AttrVal(0, k, v, 0x03, v))
                }
                pr.readPermission?.let {
                    val k = idxOf("readPermission")
                    val v = idxOf(it)
                    attrs.add(AttrVal(0, k, v, 0x03, v))
                }
                pr.writePermission?.let {
                    val k = idxOf("writePermission")
                    val v = idxOf(it)
                    attrs.add(AttrVal(0, k, v, 0x03, v))
                }
                elementChunks.add(tagIdx to attrs)
            }

            val poolBytes = AxmlStringPool(
                strings = strings,
                isUtf8 = true,
                originalFlags = AxmlStringPool.UTF8_FLAG,
                styleOffsets = IntArray(0),
                stylesData = ByteArray(0)
            ).toByteArray()

            fun startElem(nsIdx: Int, nameIdx: Int, attrs: List<AttrVal>): ByteArray {
                val size = 16 + 20 + attrs.size * 20
                val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(AxmlPatcher.RES_XML_START_ELEMENT_TYPE.toShort())
                b.putShort(16)
                b.putInt(size)
                b.putInt(1)
                b.putInt(-1)
                b.putInt(nsIdx)
                b.putInt(nameIdx)
                b.putShort(20)
                b.putShort(20)
                b.putShort(attrs.size.toShort())
                b.putShort(0)
                b.putShort(0)
                b.putShort(0)
                for (a in attrs) {
                    b.putInt(a.nsIdx)
                    b.putInt(a.nameIdx)
                    b.putInt(a.rawStrIdx)
                    b.putShort(8)
                    b.put(0)
                    b.put(a.valueType.toByte())
                    b.putInt(a.valueData)
                }
                return b.array()
            }

            fun endElem(nsIdx: Int, nameIdx: Int): ByteArray {
                val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(0x0103.toShort())
                b.putShort(16)
                b.putInt(24)
                b.putInt(1)
                b.putInt(-1)
                b.putInt(nsIdx)
                b.putInt(nameIdx)
                return b.array()
            }

            val body = ByteArrayOutputStream()
            body.write(startElem(-1, 1, listOf(AttrVal(-1, 2, 3, 0x03, 3))))
            for ((tagIdx, attrs) in elementChunks) {
                body.write(startElem(-1, tagIdx, attrs))
                body.write(endElem(-1, tagIdx))
            }
            body.write(endElem(-1, 1))

            val bodyBytes = body.toByteArray()
            val total = 8 + poolBytes.size + bodyBytes.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putShort(AxmlPatcher.RES_XML_TYPE.toShort())
            out.putShort(8)
            out.putInt(total)
            out.put(poolBytes)
            out.put(bodyBytes)
            return out.array()
        }
    }
}
