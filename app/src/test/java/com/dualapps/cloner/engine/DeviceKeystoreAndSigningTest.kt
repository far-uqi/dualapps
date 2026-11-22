package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceKeystoreAndSigningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun keystoreIsCreatedOnceAndReusedForever() {
        val deviceFilesDir = tempFolder.newFolder("deviceA")
        val manager = DeviceKeystoreManager(deviceFilesDir)

        assertFalse(manager.keystoreFile.exists())
        val first = manager.getOrCreateSigningMaterial()

        assertTrue(manager.keystoreFile.exists())
        assertTrue(manager.passwordFile.exists())
        assertEquals("RSA", first.privateKey.algorithm)

        // Pemanggilan kedua (dan instans baru pada filesDir sama) WAJIB memakai ulang kunci yang sama
        val second = DeviceKeystoreManager(deviceFilesDir).getOrCreateSigningMaterial()
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)

        // Perangkat berbeda menghasilkan keystore unik yang berbeda
        val otherDeviceDir = tempFolder.newFolder("deviceB")
        val otherMaterial = DeviceKeystoreManager(otherDeviceDir).getOrCreateSigningMaterial()
        assertFalse(first.certificate.encoded.contentEquals(otherMaterial.certificate.encoded))
        assertNotEquals(first.certificate.serialNumber, otherMaterial.certificate.serialNumber)
    }

    @Test
    fun signedApkPassesApkVerifierWithV1AndV2Schemes() {
        val filesDir = tempFolder.newFolder("signingFiles")
        val sourceApk = tempFolder.newFile("fixture-src.apk")
        val unsignedApk = tempFolder.newFile("fixture-unsigned.apk")
        val signedApk = File(tempFolder.root, "fixture-signed.apk")

        BinaryFixtureBuilder.buildFixtureApk(sourceApk, "com.dualapps.cloner")
        ApkRebuilder.rebuild(sourceApk, unsignedApk, "com.dualapps.cloner", "com.dualapps.cloner.clone")

        val material = DeviceKeystoreManager(filesDir).getOrCreateSigningMaterial()
        val verifyResult = ApkSigningEngine.signAndVerify(unsignedApk, signedApk, material)

        assertTrue(signedApk.exists())
        assertTrue(verifyResult.isVerified)
        assertTrue(verifyResult.isVerifiedUsingV1Scheme)
        assertTrue(verifyResult.isVerifiedUsingV2Scheme)
        assertFalse(verifyResult.containsErrors())
    }

    @Test
    fun corruptedSignedApkFailsWithSignVerifyFailed() {
        val filesDir = tempFolder.newFolder("signingTamper")
        val sourceApk = tempFolder.newFile("tamper-src.apk")
        val unsignedApk = tempFolder.newFile("tamper-unsigned.apk")
        val signedApk = File(tempFolder.root, "tamper-signed.apk")

        BinaryFixtureBuilder.buildFixtureApk(sourceApk, "com.dualapps.cloner")
        ApkRebuilder.rebuild(sourceApk, unsignedApk, "com.dualapps.cloner", "com.dualapps.cloner.clone")

        val material = DeviceKeystoreManager(filesDir).getOrCreateSigningMaterial()
        ApkSigningEngine.sign(unsignedApk, signedApk, material)

        // Rusak satu byte di tengah APK hasil signing
        val bytes = signedApk.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        signedApk.writeBytes(bytes)

        try {
            ApkSigningEngine.verify(signedApk)
            fail("Seharusnya melempar SIGN_VERIFY_FAILED")
        } catch (e: CloneException) {
            assertEquals(CloneError.SIGN_VERIFY_FAILED, e.error)
        }
    }
}
