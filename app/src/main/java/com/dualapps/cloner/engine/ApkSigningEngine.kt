package com.dualapps.cloner.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.File

/**
 * Menandatangani APK menggunakan `com.android.tools.build:apksig` (skema v1 + v2 aktif)
 * lalu memverifikasi tanda tangan hasil dengan `ApkVerifier`.
 */
object ApkSigningEngine {

    const val MIN_SDK_VERSION = 26

    fun signAndVerify(
        unsignedApk: File,
        signedApk: File,
        signingMaterial: DeviceKeystoreManager.SigningMaterial
    ): ApkVerifier.Result {
        sign(unsignedApk, signedApk, signingMaterial)
        return verify(signedApk)
    }

    fun sign(
        unsignedApk: File,
        signedApk: File,
        signingMaterial: DeviceKeystoreManager.SigningMaterial
    ) {
        if (!unsignedApk.exists() || !unsignedApk.canRead()) {
            throw CloneException(CloneError.SIGN_FAILED, "APK belum bertanda tangan tidak ditemukan.")
        }
        signedApk.parentFile?.mkdirs()

        try {
            val signerConfig = ApkSigner.SignerConfig.Builder(
                DeviceKeystoreManager.KEY_ALIAS,
                signingMaterial.privateKey,
                listOf(signingMaterial.certificate)
            ).build()

            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(MIN_SDK_VERSION)
                .build()

            signer.sign()
        } catch (e: Exception) {
            signedApk.delete()
            throw CloneException(
                CloneError.SIGN_FAILED,
                "Gagal menandatangani APK dengan apksig: ${e.message}",
                e
            )
        }
    }

    fun verify(signedApk: File): ApkVerifier.Result {
        val result = try {
            // minCheckedPlatformVersion = 23 agar ApkVerifier memverifikasi skema v1 (API <= 23)
            // sekaligus skema v2 (API >= 24) pada APK yang ditandatangani v1 + v2.
            ApkVerifier.Builder(signedApk)
                .setMinCheckedPlatformVersion(23)
                .build()
                .verify()
        } catch (e: Exception) {
            signedApk.delete()
            throw CloneException(
                CloneError.SIGN_VERIFY_FAILED,
                "Gagal menjalankan ApkVerifier: ${e.message}",
                e
            )
        }

        val ok = result.isVerified &&
            result.isVerifiedUsingV1Scheme &&
            result.isVerifiedUsingV2Scheme &&
            !result.containsErrors()

        if (!ok) {
            val errors = result.allErrors.joinToString("; ") { it.toString() }
            signedApk.delete()
            throw CloneException(
                CloneError.SIGN_VERIFY_FAILED,
                "Verifikasi tanda tangan APK gagal (v1=${result.isVerifiedUsingV1Scheme}, v2=${result.isVerifiedUsingV2Scheme}): $errors"
            )
        }
        return result
    }
}
