package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Mengelola keystore penandatanganan UNIK per perangkat di penyimpanan privat aplikasi:
 * `<filesDir>/signing/dualkey.p12` dengan kata sandi acak di `<filesDir>/signing/dualkey.pass`.
 *
 * - Tidak ada keystore/kunci apa pun yang dibundel di dalam APK.
 * - Menggunakan instans [BouncyCastleProvider] terpisah (tidak mendaftar global sebagai "BC")
 *   untuk menghindari konflik dengan provider "BC" bawaan Android.
 * - Sekali dibuat, keystore dipakai ulang selamanya agar seluruh clone di perangkat yang sama
 *   ditandatangani oleh sertifikat yang sama.
 */
class DeviceKeystoreManager(
    private val filesDir: File
) {
    data class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private val signingDir: File get() = File(filesDir, SIGNING_DIR_NAME)
    val keystoreFile: File get() = File(signingDir, KEYSTORE_FILE_NAME)
    val passwordFile: File get() = File(signingDir, PASSWORD_FILE_NAME)

    @Synchronized
    fun getOrCreateSigningMaterial(): SigningMaterial {
        return try {
            if (keystoreFile.exists() && passwordFile.exists()) {
                loadExisting()
            } else {
                createAndPersist()
            }
        } catch (e: CloneException) {
            throw e
        } catch (e: Exception) {
            throw CloneException(
                CloneError.SIGN_FAILED,
                "Gagal menyiapkan atau memuat keystore perangkat: ${e.message}",
                e
            )
        }
    }

    private fun loadExisting(): SigningMaterial {
        val password = passwordFile.readText(Charsets.UTF_8).trim().toCharArray()
        if (password.isEmpty()) {
            throw CloneException(CloneError.SIGN_FAILED, "Kata sandi keystore perangkat kosong atau rusak.")
        }
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE, bcProvider)
        FileInputStream(keystoreFile).use { fis ->
            keyStore.load(fis, password)
        }
        val key = keyStore.getKey(KEY_ALIAS, password) as? PrivateKey
            ?: throw CloneException(CloneError.SIGN_FAILED, "Private key alias '$KEY_ALIAS' tidak ditemukan di keystore.")
        val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: throw CloneException(CloneError.SIGN_FAILED, "Sertifikat alias '$KEY_ALIAS' tidak ditemukan di keystore.")
        return SigningMaterial(privateKey = key, certificate = cert)
    }

    private fun createAndPersist(): SigningMaterial {
        if (!signingDir.exists() && !signingDir.mkdirs()) {
            throw CloneException(CloneError.SIGN_FAILED, "Gagal membuat direktori penyimpanan keystore.")
        }

        val secureRandom = SecureRandom()
        val passwordBytes = ByteArray(24)
        secureRandom.nextBytes(passwordBytes)
        val passwordStr = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes)
        val passwordChars = passwordStr.toCharArray()

        // Generate RSA 2048 menggunakan instans BouncyCastleProvider terpisah
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider)
        keyPairGenerator.initialize(RSA_KEY_BITS, secureRandom)
        val keyPair = keyPairGenerator.generateKeyPair()

        // Buat sertifikat self-signed X.509 v3 unik per perangkat
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L) // -1 hari untuk toleransi clock skew
        val notAfter = Date(now + 25L * 365L * 24L * 60L * 60L * 1000L) // berlaku 25 tahun
        val serial = BigInteger(128, secureRandom).abs().max(BigInteger.ONE)
        val deviceTag = BigInteger(32, secureRandom).toString(16).padStart(8, '0')
        val subject = X500Name("CN=Dualapps Device $deviceTag, O=Dualapps Local")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)
        val certHolder = certBuilder.build(contentSigner)
        val certificate = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certHolder)

        // Simpan sebagai PKCS12 secara atomik (tulis temp -> ganti file target)
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE, bcProvider)
        keyStore.load(null, null)
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, passwordChars, arrayOf(certificate))

        val tmpKeystore = File(signingDir, "$KEYSTORE_FILE_NAME.tmp")
        val tmpPass = File(signingDir, "$PASSWORD_FILE_NAME.tmp")

        try {
            FileOutputStream(tmpKeystore).use { fos ->
                keyStore.store(fos, passwordChars)
            }
            tmpPass.writeText(passwordStr, Charsets.UTF_8)

            tmpKeystore.setReadable(false, false)
            tmpKeystore.setReadable(true, true)
            tmpPass.setReadable(false, false)
            tmpPass.setReadable(true, true)

            if (keystoreFile.exists()) keystoreFile.delete()
            if (passwordFile.exists()) passwordFile.delete()

            if (!tmpKeystore.renameTo(keystoreFile) || !tmpPass.renameTo(passwordFile)) {
                throw CloneException(CloneError.SIGN_FAILED, "Gagal menyimpan berkas keystore ke folder privat.")
            }
        } catch (e: Exception) {
            tmpKeystore.delete()
            tmpPass.delete()
            throw e
        }

        return SigningMaterial(privateKey = keyPair.private, certificate = certificate)
    }

    companion object {
        const val KEY_ALIAS = "dualkey"
        const val SIGNING_DIR_NAME = "signing"
        const val KEYSTORE_FILE_NAME = "dualkey.p12"
        const val PASSWORD_FILE_NAME = "dualkey.pass"
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val RSA_KEY_BITS = 2048

        // Instans BouncyCastle terpisah (tidak didaftarkan global agar tidak bentrok dengan "BC" Android)
        private val bcProvider: BouncyCastleProvider by lazy { BouncyCastleProvider() }
    }
}
