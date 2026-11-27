package com.dualapps.cloner.util

import com.dualapps.cloner.model.ClonedApkRecord

/**
 * Pure-JVM coordinator for the two-step clone deletion & optional uninstall flow (Phase 17).
 *
 * Guarantees:
 * 1. "Delete file & uninstall app" option is only shown when the cloned package is live-installed.
 * 2. Both options surface clear consequence sub-text and formatted freed size.
 * 3. For "Delete file & uninstall app", clone files are NEVER deleted before the Android system
 *    uninstall dialog finishes and live PackageManager confirms the package is gone.
 * 4. If the user cancels the system uninstall dialog (or uninstall fails), nothing is deleted.
 */
object CloneDeleteCoordinator {

    enum class CloneDeleteMode {
        FILE_ONLY,
        FILE_AND_UNINSTALL
    }

    data class DeleteChoiceUiModel(
        val showUninstallOption: Boolean,
        val fileOnlySubtitle: String,
        val fileAndUninstallSubtitle: String?
    )

    data class DeleteConfirmUiModel(
        val mode: CloneDeleteMode,
        val message: String,
        val cancelLabel: String = LABEL_CANCEL,
        val confirmLabel: String = LABEL_YES_DELETE
    )

    sealed class DeleteExecutionOutcome {
        data object DeletedFileOnly : DeleteExecutionOutcome()
        data object DeletedFileAndUninstalled : DeleteExecutionOutcome()
        data class NeedsSystemUninstall(val packageName: String) : DeleteExecutionOutcome()
        data object CancelledAtSystemDialog : DeleteExecutionOutcome()
        data class AppAlreadyUninstalled(val packageName: String) : DeleteExecutionOutcome()
        data object FileDeleteFailed : DeleteExecutionOutcome()
    }

    const val LABEL_CANCEL = "Cancel"
    const val LABEL_YES_DELETE = "Yes, delete"

    fun buildChoiceModel(
        record: ClonedApkRecord,
        isInstalledLive: Boolean,
        formattedSize: String
    ): DeleteChoiceUiModel {
        val fileOnlySubtitle = if (isInstalledLive) {
            "Frees $formattedSize. The installed app stays on your device."
        } else {
            "Frees $formattedSize from Dualapps storage."
        }
        val fileAndUninstallSubtitle = if (isInstalledLive) {
            "Frees $formattedSize + app storage. The app will be removed from your device."
        } else {
            null
        }
        return DeleteChoiceUiModel(
            showUninstallOption = isInstalledLive,
            fileOnlySubtitle = fileOnlySubtitle,
            fileAndUninstallSubtitle = fileAndUninstallSubtitle
        )
    }

    fun buildConfirmModel(
        record: ClonedApkRecord,
        mode: CloneDeleteMode,
        isInstalledLive: Boolean,
        formattedSize: String
    ): DeleteConfirmUiModel {
        val message = when (mode) {
            CloneDeleteMode.FILE_ONLY -> {
                if (isInstalledLive) {
                    "This will delete the clone files ($formattedSize). " +
                        "The installed app '${record.cloneName}' will stay on your device. " +
                        "To install it again, you will need to clone it again."
                } else {
                    "This will delete the clone files ($formattedSize) for '${record.cloneName}'. " +
                        "To install it again, you will need to clone it again."
                }
            }
            CloneDeleteMode.FILE_AND_UNINSTALL -> {
                "This will delete the clone files ($formattedSize) and uninstall " +
                    "'${record.cloneName}' from your device. This cannot be undone."
            }
        }
        return DeleteConfirmUiModel(
            mode = mode,
            message = message,
            cancelLabel = LABEL_CANCEL,
            confirmLabel = LABEL_YES_DELETE
        )
    }

    fun beginConfirmedDelete(
        record: ClonedApkRecord,
        mode: CloneDeleteMode,
        isInstalledLive: Boolean,
        deleteFiles: (ClonedApkRecord) -> Boolean
    ): DeleteExecutionOutcome {
        return when (mode) {
            CloneDeleteMode.FILE_ONLY -> {
                if (deleteFiles(record)) {
                    DeleteExecutionOutcome.DeletedFileOnly
                } else {
                    DeleteExecutionOutcome.FileDeleteFailed
                }
            }
            CloneDeleteMode.FILE_AND_UNINSTALL -> {
                if (!isInstalledLive) {
                    // Do not silently delete files when the app was already removed externally.
                    DeleteExecutionOutcome.AppAlreadyUninstalled(record.clonePackageName)
                } else {
                    // Launch system uninstall FIRST; files are deleted only after uninstall succeeds.
                    DeleteExecutionOutcome.NeedsSystemUninstall(record.clonePackageName)
                }
            }
        }
    }

    fun completeAfterSystemUninstall(
        record: ClonedApkRecord,
        isStillInstalledLive: Boolean,
        deleteFiles: (ClonedApkRecord) -> Boolean
    ): DeleteExecutionOutcome {
        if (isStillInstalledLive) {
            // System uninstall was cancelled or failed -> keep files & metadata untouched.
            return DeleteExecutionOutcome.CancelledAtSystemDialog
        }
        return if (deleteFiles(record)) {
            DeleteExecutionOutcome.DeletedFileAndUninstalled
        } else {
            DeleteExecutionOutcome.FileDeleteFailed
        }
    }
}
