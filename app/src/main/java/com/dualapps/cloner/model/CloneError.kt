package com.dualapps.cloner.model

/**
 * Official Dualapps error taxonomy.
 * Each error carries an English user-facing message explaining the cause and next step.
 */
enum class CloneError(val userMessage: String) {
    SPLIT_UNSUPPORTED(
        "This app uses the Split APK (multi-APK) format which is not supported in this mode. Choose another app."
    ),
    SOURCE_UNREADABLE(
        "Unable to read the source APK file. Make sure the app is still installed and not being updated, then try again."
    ),
    AXML_PARSE_FAILED(
        "Failed to read binary AndroidManifest.xml from the source APK. This app's manifest structure may be non-standard or protected."
    ),
    AXML_PATCH_FAILED(
        "Failed to update the package name in AndroidManifest.xml. This app cannot be cloned by the current engine."
    ),
    ARSC_PATCH_FAILED(
        "Failed to process resources.arsc in the source APK. This app may use a non-standard resource table structure."
    ),
    PACKAGE_TOO_LONG(
        "Clone package name is too long (maximum 127 characters for resources.arsc header). Use a shorter package suffix."
    ),
    PACKAGE_ALREADY_INSTALLED(
        "This clone package name is already installed on your device. Choose a different number or package name."
    ),
    ZIP_WRITE_FAILED(
        "Failed to rebuild the APK file. Check your device's available storage and try again."
    ),
    SIGN_FAILED(
        "Failed to re-sign the cloned APK or prepare the device signing key. Please try cloning again."
    ),
    SIGN_VERIFY_FAILED(
        "Signature verification of the cloned APK failed. Output was discarded for safety — please try cloning again."
    ),
    VERIFY_PACKAGE_MISMATCH(
        "Final verification detected that the output APK package name does not match the planned clone. This app may be incompatible."
    ),
    ICON_PATCH_FAILED(
        "Failed to apply the custom icon to the cloned APK. No matching launcher icon entries were found or the image could not be processed."
    ),
    LABEL_PATCH_FAILED(
        "Failed to change the launcher name of the cloned APK. The label attribute was not found or the resources.arsc string structure is unsupported."
    ),
    SPLIT_PATCH_FAILED(
        "Failed to patch one of the Split APK files. This app's split manifest or resource structure may be non-standard."
    ),
    SPLIT_INSTALL_FAILED(
        "Multi-APK (Split APK) installation failed or was rejected by Android. Check available storage or target app compatibility."
    ),
    SESSION_FAILED(
        "Failed to create or complete the PackageInstaller session. Keep Dualapps in the foreground and try again."
    ),
    NO_UPDATE_AVAILABLE(
        "The source app does not have a newer version than this clone yet. Update the source app first before updating the clone."
    ),
    BUNDLE_INVALID(
        "The APKS/XAPK bundle file is corrupted or not a valid ZIP archive."
    ),
    BUNDLE_NO_APK(
        "The bundle file does not contain any APK files."
    ),
    BUNDLE_NO_BASE_APK(
        "The bundle file does not contain a base APK (all files inside are splits)."
    ),
    BUNDLE_PACKAGE_MISMATCH(
        "APK files inside the bundle have mismatched, inconsistent package names."
    ),
    BUNDLE_HAS_OBB(
        "This bundle contains OBB files which are not supported — clone cancelled."
    ),
    INCOMPATIBLE_APP(
        "This app cannot be cloned because it directly calls a signature-protected provider. The clone will crash on launch."
    ),
    CANCELLED(
        "Cloning was cancelled by the user."
    ),
    UNKNOWN(
        "An unexpected error occurred while cloning. Please try again or pick another app."
    )
}

class CloneException(
    val error: CloneError,
    detail: String? = null,
    cause: Throwable? = null
) : Exception(detail ?: error.userMessage, cause)
