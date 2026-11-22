package com.dualapps.cloner.engine

import com.dualapps.cloner.model.CloneError

/**
 * Validasi dan pembuatan otomatis package name clone sesuai aturan Android
 * serta batas panjang header `resources.arsc` (maks 127 karakter).
 */
object PackageNameValidator {

    private val SEGMENT_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*$")

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val message: String, val error: CloneError? = null) : ValidationResult()
    }

    fun validate(
        packageName: String,
        originalPackage: String,
        isInstalled: (String) -> Boolean
    ): ValidationResult {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Package name clone tidak boleh kosong.")
        }
        if (trimmed.length > ArscPatcher.MAX_PACKAGE_NAME_LENGTH) {
            return ValidationResult.Invalid(
                CloneError.PACKAGE_TOO_LONG.userMessage,
                CloneError.PACKAGE_TOO_LONG
            )
        }
        if (!isSyntaxValid(trimmed)) {
            return ValidationResult.Invalid(
                "Format package tidak valid. Gunakan minimal dua segmen dipisah titik (contoh: com.contoh.clone) dengan huruf, angka, atau garis bawah."
            )
        }
        if (trimmed == originalPackage) {
            return ValidationResult.Invalid("Package name clone harus berbeda dari package aplikasi asli.")
        }
        if (isInstalled(trimmed)) {
            return ValidationResult.Invalid(
                CloneError.PACKAGE_ALREADY_INSTALLED.userMessage,
                CloneError.PACKAGE_ALREADY_INSTALLED
            )
        }
        return ValidationResult.Valid
    }

    fun isSyntaxValid(packageName: String): Boolean {
        if (packageName.isEmpty() || packageName.length > ArscPatcher.MAX_PACKAGE_NAME_LENGTH) {
            return false
        }
        val segments = packageName.split(".")
        if (segments.size < 2) return false
        return segments.all { SEGMENT_REGEX.matches(it) }
    }

    /**
     * Menghasilkan saran package name clone dengan pola:
     * `<package asli>.clone`, `<package asli>.clone2`, `<package asli>.clone3`, dst.
     * Bila sudah terpasang di HP, nomor dinaikkan otomatis.
     */
    fun suggestClonePackage(
        originalPackage: String,
        isInstalled: (String) -> Boolean
    ): String {
        var index = 1
        while (index < 1000) {
            val suffix = if (index == 1) ".clone" else ".clone$index"
            val candidate = "$originalPackage$suffix"
            if (!isInstalled(candidate)) {
                return candidate
            }
            index++
        }
        return "$originalPackage.clone${System.currentTimeMillis() % 10000}"
    }
}
