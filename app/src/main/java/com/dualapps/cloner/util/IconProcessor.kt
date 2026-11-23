package com.dualapps.cloner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Pemroses gambar ikon custom:
 * - Decode gambar (PNG/JPG/WEBP)
 * - Crop tengah menjadi persegi
 * - Resize ke ukuran standar per densitas (mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192 px)
 * - Encode hasil akhir ke format PNG.
 */
object IconProcessor {

    val DENSITY_SIZES: LinkedHashMap<String, Int> = linkedMapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192
    )

    private const val MAX_STORED_PREVIEW_PX = 512

    /**
     * Menentukan densitas target berdasarkan path entri resource di dalam APK
     * (urutan pengecekan dari string terpanjang agar `xxxhdpi` tidak cocok dengan `hdpi`).
     */
    fun densityForResourcePath(entryPath: String): String {
        val lower = entryPath.lowercase()
        return when {
            lower.contains("xxxhdpi") -> "xxxhdpi"
            lower.contains("xxhdpi") -> "xxhdpi"
            lower.contains("xhdpi") -> "xhdpi"
            lower.contains("hdpi") -> "hdpi"
            lower.contains("mdpi") -> "mdpi"
            else -> "xxxhdpi"
        }
    }

    /**
     * Decode gambar dari [uri], crop tengah menjadi persegi, simpan sebagai PNG sementara di [destFile],
     * dan kembalikan [Bitmap] persegi untuk pratinjau UI.
     */
    fun decodeCropAndSaveSquarePng(context: Context, uri: Uri, destFile: File): Bitmap {
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw CloneException(
            CloneError.ICON_PATCH_FAILED,
            "Gambar yang dipilih tidak dapat dibaca. Pilih berkas PNG, JPG, atau WEBP yang valid."
        )

        val cropped = cropCenterSquare(decoded)
        if (cropped !== decoded) {
            decoded.recycle()
        }

        val normalized = if (cropped.width > MAX_STORED_PREVIEW_PX) {
            val scaled = Bitmap.createScaledBitmap(cropped, MAX_STORED_PREVIEW_PX, MAX_STORED_PREVIEW_PX, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        } else {
            cropped
        }

        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { fos ->
            if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw CloneException(CloneError.ICON_PATCH_FAILED, "Gagal menyimpan pratinjau ikon PNG.")
            }
        }
        return normalized
    }

    /**
     * Menghasilkan peta PNG per densitas (mdpi 48 .. xxxhdpi 192 px) dari berkas gambar lokal.
     */
    fun generateDensityPngsFromFile(sourceImageFile: File): Map<String, ByteArray> {
        if (!sourceImageFile.exists() || !sourceImageFile.canRead()) {
            throw CloneException(CloneError.ICON_PATCH_FAILED, "Berkas gambar ikon custom tidak ditemukan.")
        }
        val decoded = BitmapFactory.decodeFile(sourceImageFile.absolutePath)
            ?: throw CloneException(CloneError.ICON_PATCH_FAILED, "Gagal men-decode berkas gambar ikon custom.")

        val square = cropCenterSquare(decoded)
        if (square !== decoded) {
            decoded.recycle()
        }

        return try {
            val result = LinkedHashMap<String, ByteArray>(DENSITY_SIZES.size)
            for ((density, sizePx) in DENSITY_SIZES) {
                val resized = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
                try {
                    val baos = ByteArrayOutputStream()
                    if (!resized.compress(Bitmap.CompressFormat.PNG, 100, baos)) {
                        throw CloneException(
                            CloneError.ICON_PATCH_FAILED,
                            "Gagal mengompresi ikon PNG untuk densitas $density."
                        )
                    }
                    result[density] = baos.toByteArray()
                } finally {
                    if (resized !== square) resized.recycle()
                }
            }
            result
        } finally {
            square.recycle()
        }
    }

    fun cropCenterSquare(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            throw CloneException(CloneError.ICON_PATCH_FAILED, "Dimensi gambar ikon tidak valid.")
        }
        if (width == height) return source
        val side = minOf(width, height)
        val x = (width - side) / 2
        val y = (height - side) / 2
        return Bitmap.createBitmap(source, x, y, side, side)
    }
}
