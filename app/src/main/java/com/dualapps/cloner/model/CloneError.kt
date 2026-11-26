package com.dualapps.cloner.model

/**
 * Taksonomi error resmi Dualapps (Bagian 4).
 * Setiap error membawa pesan Bahasa Indonesia yang menjelaskan penyebab + langkah yang bisa dilakukan user.
 */
enum class CloneError(val userMessage: String) {
    SPLIT_UNSUPPORTED(
        "Aplikasi ini menggunakan format Split APK (multi-APK) yang belum didukung di versi ini. Pilih aplikasi non-split lainnya."
    ),
    SOURCE_UNREADABLE(
        "Berkas APK sumber tidak dapat dibaca. Pastikan aplikasi masih terpasang dan tidak sedang diperbarui, lalu coba lagi."
    ),
    AXML_PARSE_FAILED(
        "Gagal membaca AndroidManifest.xml biner dari APK sumber. Struktur manifest aplikasi ini kemungkinan tidak standar atau terproteksi."
    ),
    AXML_PATCH_FAILED(
        "Gagal mengubah package name di AndroidManifest.xml. Aplikasi ini belum dapat dikloning oleh mesin Dualapps saat ini."
    ),
    ARSC_PATCH_FAILED(
        "Gagal memproses berkas resources.arsc pada APK sumber. Aplikasi ini kemungkinan memakai struktur resource yang tidak standar."
    ),
    PACKAGE_TOO_LONG(
        "Package name clone terlalu panjang (maksimal 127 karakter untuk header resources.arsc). Gunakan akhiran package yang lebih pendek."
    ),
    PACKAGE_ALREADY_INSTALLED(
        "Package name clone sudah terpasang di perangkat ini. Gunakan nomor atau nama package clone yang berbeda."
    ),
    ZIP_WRITE_FAILED(
        "Gagal membangun ulang berkas APK. Periksa sisa ruang penyimpanan perangkat Anda lalu coba lagi."
    ),
    SIGN_FAILED(
        "Gagal menandatangani ulang APK hasil clone atau menyiapkan kunci penandatanganan perangkat. Coba ulangi proses clone."
    ),
    SIGN_VERIFY_FAILED(
        "Verifikasi tanda tangan APK hasil clone gagal. Berkas hasil dibatalkan demi keamanan — silakan coba clone ulang."
    ),
    VERIFY_PACKAGE_MISMATCH(
        "Verifikasi akhir mendeteksi package name di APK hasil tidak sesuai dengan rencana clone. Aplikasi ini kemungkinan tidak kompatibel."
    ),
    ICON_PATCH_FAILED(
        "Gagal menerapkan ikon custom pada APK hasil clone. Tidak ditemukan entri ikon launcher yang cocok atau gambar tidak dapat diproses."
    ),
    LABEL_PATCH_FAILED(
        "Gagal mengubah nama launcher pada APK hasil clone. Atribut label tidak ditemukan atau struktur string di resources.arsc tidak didukung."
    ),
    SPLIT_PATCH_FAILED(
        "Gagal memodifikasi salah satu berkas Split APK. Struktur manifest atau resource split aplikasi ini kemungkinan tidak standar."
    ),
    SPLIT_INSTALL_FAILED(
        "Pemasangan multi-APK (Split APK) gagal atau ditolak oleh sistem Android. Periksa ruang penyimpanan atau kompatibilitas aplikasi target."
    ),
    SESSION_FAILED(
        "Gagal membuat atau menyelesaikan sesi pemasangan PackageInstaller. Pastikan Dualapps tetap terbuka di layar depan lalu coba lagi."
    ),
    CANCELLED(
        "Proses clone dibatalkan oleh pengguna."
    ),
    UNKNOWN(
        "Terjadi kesalahan tak terduga saat memproses clone. Silakan coba lagi atau pilih aplikasi lain."
    )
}

class CloneException(
    val error: CloneError,
    detail: String? = null,
    cause: Throwable? = null
) : Exception(detail ?: error.userMessage, cause)
