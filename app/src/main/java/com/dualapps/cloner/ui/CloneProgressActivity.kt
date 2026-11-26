package com.dualapps.cloner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dualapps.cloner.R
import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.databinding.ActivityCloneProgressBinding
import com.dualapps.cloner.databinding.ItemCloneStepBinding
import com.dualapps.cloner.engine.AndroidArchivePackageReader
import com.dualapps.cloner.engine.AndroidContentProviderResolver
import com.dualapps.cloner.engine.ClonePipeline
import com.dualapps.cloner.engine.CloneProgressStartResolver
import com.dualapps.cloner.engine.CloneRequest
import com.dualapps.cloner.engine.CloneStartAction
import com.dualapps.cloner.engine.CloneStep
import com.dualapps.cloner.engine.DeviceKeystoreManager
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.model.SplitApkFile
import com.dualapps.cloner.util.ApkInstaller
import com.dualapps.cloner.util.FormatUtils
import com.dualapps.cloner.util.IconProcessor
import com.dualapps.cloner.util.InstallProgressState
import com.dualapps.cloner.util.InstallProgressTracker
import com.dualapps.cloner.util.SplitApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CloneProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneProgressBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cloneJob: Job? = null

    private lateinit var request: CloneRequest
    private var customIconPath: String? = null
    private val stepBindings = LinkedHashMap<CloneStep, ItemCloneStepBinding>()
    private var currentActiveStep: CloneStep = CloneStep.VALIDATE_SOURCE
    private var completedStepsCount: Int = 0
    private var completedRecord: ClonedApkRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sourceDir = intent.getStringExtra(EXTRA_SOURCE_DIR).orEmpty()
        val originalPkg = intent.getStringExtra(EXTRA_ORIGINAL_PACKAGE).orEmpty()
        val clonePkg = intent.getStringExtra(EXTRA_CLONE_PACKAGE).orEmpty()
        if (sourceDir.isEmpty() || originalPkg.isEmpty() || clonePkg.isEmpty()) {
            finish()
            return
        }

        customIconPath = intent.getStringExtra(EXTRA_CUSTOM_ICON_PATH)
        val splitFiles = readSplitFilesFromIntent()
        val isUpdate = intent.getBooleanExtra(EXTRA_IS_UPDATE, false)
        request = CloneRequest(
            sourceApkPath = sourceDir,
            isSplitApk = intent.getBooleanExtra(EXTRA_IS_SPLIT, false),
            originalAppName = intent.getStringExtra(EXTRA_ORIGINAL_NAME).orEmpty(),
            originalPackageName = originalPkg,
            cloneName = intent.getStringExtra(EXTRA_CLONE_NAME).orEmpty(),
            clonePackageName = clonePkg,
            splitApkPaths = splitFiles,
            sourceVersionCode = intent.getLongExtra(EXTRA_VERSION_CODE, 0L),
            sourceVersionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty(),
            customIconSourcePath = customIconPath,
            isUpdate = isUpdate,
            existingRecordId = intent.getStringExtra(EXTRA_EXISTING_RECORD_ID),
            previousSourceVersionCode = intent.getLongExtra(EXTRA_PREVIOUS_VERSION_CODE, 0L),
            sourceOrigin = intent.getStringExtra(EXTRA_SOURCE_ORIGIN).orEmpty()
        )

        setupUi()

        val startAction = CloneProgressStartResolver.resolve(
            clonePackageName = request.clonePackageName,
            savedRecordJson = savedInstanceState?.getString(STATE_COMPLETED_RECORD_JSON),
            existingRecords = ClonedApksRepository(filesDir).loadAll(),
            isExplicitRetry = false,
            isUpdateRun = request.isUpdate
        )
        when (startAction) {
            is CloneStartAction.ShowCompleted -> showRestoredSuccess(startAction.record)
            CloneStartAction.RunPipeline -> startCloning()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        completedRecord?.let { record ->
            outState.putString(STATE_COMPLETED_RECORD_JSON, record.toJsonString())
        }
    }

    override fun onResume() {
        super.onResume()
        InstallProgressTracker.onActivityResumed()
    }

    override fun onDestroy() {
        super.onDestroy()
        cloneJob?.cancel()
        uiScope.cancel()
    }

    private fun setupUi() {
        binding.tvTargetSummary.text = request.clonePackageName
        binding.btnBack.setOnClickListener {
            cloneJob?.cancel()
            finish()
        }
        binding.btnCancel.setOnClickListener {
            cloneJob?.cancel()
        }
        binding.btnRetry.setOnClickListener {
            startCloning(skipCompatibilityCheck = false)
        }
        binding.btnOverrideClone.setOnClickListener {
            startCloning(skipCompatibilityCheck = true)
        }
        binding.btnInstall.setOnClickListener {
            val record = completedRecord ?: return@setOnClickListener
            if (record.isSplitClone) {
                SplitApkInstaller.installSplitClone(this, record)
            } else {
                val launched = ApkInstaller.installApk(this, record.apkFile)
                if (launched) {
                    InstallProgressTracker.onSingleApkInstallLaunched(record.clonePackageName, record.cloneName)
                }
            }
        }

        buildStepRows()
        observeInstallProgress()
    }

    private fun observeInstallProgress() {
        uiScope.launch {
            InstallProgressTracker.state.collect { state ->
                when (state) {
                    InstallProgressState.Idle -> {
                        binding.cardInstallProgress.visibility = View.GONE
                    }
                    is InstallProgressState.Installing -> {
                        binding.cardInstallProgress.visibility = View.VISIBLE
                        binding.tvInstallProgressText.text = if (state.cloneName.isNotBlank()) {
                            "${state.message} (${state.cloneName})"
                        } else {
                            state.message
                        }
                    }
                    is InstallProgressState.Succeeded,
                    is InstallProgressState.Failed -> {
                        binding.cardInstallProgress.visibility = View.GONE
                        InstallProgressTracker.consumeTerminalState()
                    }
                }
            }
        }
    }

    private fun buildStepRows() {
        binding.containerSteps.removeAllViews()
        stepBindings.clear()
        val inflater = LayoutInflater.from(this)
        CloneStep.entries.forEach { step ->
            val rowBinding = ItemCloneStepBinding.inflate(inflater, binding.containerSteps, false)
            rowBinding.tvStepBadge.text = (step.index + 1).toString()
            rowBinding.tvStepLabel.text = step.label
            stepBindings[step] = rowBinding
            binding.containerSteps.addView(rowBinding.root)
        }
    }

    private fun resetUiForRun() {
        completedStepsCount = 0
        completedRecord = null
        currentActiveStep = CloneStep.VALIDATE_SOURCE
        binding.tvHeadline.setText(
            if (request.isUpdate) R.string.headline_updating else R.string.headline_cloning
        )
        binding.progressBar.max = CloneStep.entries.size
        binding.progressBar.progress = 0
        binding.tvProgressCount.text = getString(R.string.progress_count_format, 0, CloneStep.entries.size)
        binding.tvFileProgress.visibility = View.GONE
        binding.cardWarning.visibility = View.GONE
        binding.cardError.visibility = View.GONE
        binding.cardSuccessFolder.visibility = View.GONE
        binding.btnInstall.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.btnOverrideClone.visibility = View.GONE
        binding.btnCancel.visibility = View.VISIBLE
        CloneStep.entries.forEach { setStepVisual(it, StepVisual.PENDING) }
    }

    private fun startCloning(skipCompatibilityCheck: Boolean = false) {
        cloneJob?.cancel()
        val action = CloneProgressStartResolver.resolve(
            clonePackageName = request.clonePackageName,
            savedRecordJson = null,
            existingRecords = emptyList(),
            isExplicitRetry = true,
            isUpdateRun = request.isUpdate,
            skipCompatibilityCheck = skipCompatibilityCheck
        )
        if (action !is CloneStartAction.RunPipeline) return
        resetUiForRun()

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = AndroidArchivePackageReader(packageManager),
            isPackageInstalled = ::isPackageInstalledOnDevice,
            contentProviderResolver = AndroidContentProviderResolver(packageManager)
        )

        cloneJob = uiScope.launch {
            try {
                val baseReq = request.copy(skipCompatibilityCheck = skipCompatibilityCheck)
                val effectiveRequest = if (!customIconPath.isNullOrBlank()) {
                    val densityPngs = withContext(Dispatchers.IO) {
                        IconProcessor.generateDensityPngsFromFile(File(customIconPath!!))
                    }
                    baseReq.copy(customIconPngs = densityPngs)
                } else {
                    baseReq
                }
                val record = pipeline.execute(
                    request = effectiveRequest,
                    onStepStart = { step ->
                        runOnUiThread {
                            currentActiveStep = step
                            setStepVisual(step, StepVisual.RUNNING)
                        }
                    },
                    onStepDone = { step ->
                        runOnUiThread {
                            setStepVisual(step, StepVisual.DONE)
                            completedStepsCount = (completedStepsCount + 1).coerceAtMost(CloneStep.entries.size)
                            binding.progressBar.progress = completedStepsCount
                            binding.tvProgressCount.text = getString(
                                R.string.progress_count_format,
                                completedStepsCount,
                                CloneStep.entries.size
                            )
                        }
                    },
                    onFileProgress = { fileIdx, totalFiles, fileName ->
                        if (totalFiles > 1) {
                            runOnUiThread {
                                binding.tvFileProgress.visibility = View.VISIBLE
                                binding.tvFileProgress.text = getString(
                                    R.string.progress_file_format,
                                    fileIdx,
                                    totalFiles,
                                    fileName
                                )
                            }
                        }
                    },
                    onCompatibilityWarning = { warningText ->
                        runOnUiThread {
                            binding.cardWarning.visibility = View.VISIBLE
                            binding.tvWarningMessage.text = warningText
                        }
                    }
                )
                onCloneSuccess(record)
            } catch (e: CloneException) {
                onCloneFailure(e.error, e.message)
            } catch (e: Exception) {
                onCloneFailure(CloneError.UNKNOWN, e.message)
            }
        }
    }

    private fun showRestoredSuccess(record: ClonedApkRecord) {
        binding.progressBar.max = CloneStep.entries.size
        completedStepsCount = CloneStep.entries.size
        CloneStep.entries.forEach { setStepVisual(it, StepVisual.DONE) }
        onCloneSuccess(record)
    }

    private fun onCloneSuccess(record: ClonedApkRecord) {
        completedRecord = record
        // Pastikan progress penuh meski langkah APPLY_IDENTITY dilewati saat tidak ada perubahan identitas
        binding.progressBar.progress = CloneStep.entries.size
        binding.tvProgressCount.text = getString(
            R.string.progress_count_format,
            CloneStep.entries.size,
            CloneStep.entries.size
        )
        binding.tvHeadline.setText(R.string.headline_results)
        binding.btnCancel.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.btnOverrideClone.visibility = View.GONE
        binding.cardError.visibility = View.GONE

        if (!record.compatibilityWarning.isNullOrBlank()) {
            binding.cardWarning.visibility = View.VISIBLE
            binding.tvWarningMessage.text = record.compatibilityWarning
        }

        binding.cardSuccessFolder.visibility = View.VISIBLE
        binding.tvSuccessName.text = record.cloneName
        binding.tvSuccessPackage.text = record.clonePackageName
        binding.tvSuccessSize.text = if (record.isSplitClone) {
            getString(
                R.string.label_split_apk_size_format,
                record.totalFileCount,
                FormatUtils.formatFileSize(record.apkSizeBytes)
            )
        } else {
            getString(
                R.string.label_apk_size_format,
                FormatUtils.formatFileSize(record.apkSizeBytes)
            )
        }
        binding.tvFileProgress.visibility = View.GONE
        binding.btnInstall.visibility = View.VISIBLE
    }

    private fun onCloneFailure(error: CloneError, detail: String?) {
        setStepVisual(currentActiveStep, StepVisual.FAILED)
        binding.btnCancel.visibility = View.GONE
        binding.btnInstall.visibility = View.GONE
        binding.cardSuccessFolder.visibility = View.GONE

        binding.cardError.visibility = View.VISIBLE
        binding.tvErrorCode.text = error.name
        val message = if (!detail.isNullOrBlank() && detail != error.userMessage && error != CloneError.CANCELLED) {
            "${error.userMessage}\n\nDetail: $detail"
        } else {
            error.userMessage
        }
        binding.tvErrorMessage.text = message
        binding.btnRetry.visibility = View.VISIBLE
        binding.btnOverrideClone.visibility =
            if (error == CloneError.INCOMPATIBLE_APP) View.VISIBLE else View.GONE
    }

    private enum class StepVisual { PENDING, RUNNING, DONE, FAILED }

    private fun setStepVisual(step: CloneStep, state: StepVisual) {
        val row = stepBindings[step] ?: return
        val inkPrimary = ContextCompat.getColor(this, R.color.ink_primary)
        val canvasColor = ContextCompat.getColor(this, R.color.bg_canvas)
        val mutedColor = ContextCompat.getColor(this, R.color.ink_muted)

        when (state) {
            StepVisual.PENDING -> {
                row.tvStepBadge.setBackgroundResource(R.drawable.bg_neo_step_pending)
                row.tvStepBadge.setTextColor(inkPrimary)
                row.tvStepBadge.text = (step.index + 1).toString()
                row.tvStepStatus.text = ""
                row.tvStepStatus.setTextColor(mutedColor)
            }
            StepVisual.RUNNING -> {
                row.tvStepBadge.setBackgroundResource(R.drawable.bg_neo_step_running)
                row.tvStepBadge.setTextColor(inkPrimary)
                row.tvStepBadge.text = "…"
                row.tvStepStatus.text = getString(R.string.step_status_running)
                row.tvStepStatus.setTextColor(inkPrimary)
            }
            StepVisual.DONE -> {
                row.tvStepBadge.setBackgroundResource(R.drawable.bg_neo_step_done)
                row.tvStepBadge.setTextColor(inkPrimary)
                row.tvStepBadge.text = "✓"
                row.tvStepStatus.text = getString(R.string.step_status_done)
                row.tvStepStatus.setTextColor(inkPrimary)
            }
            StepVisual.FAILED -> {
                row.tvStepBadge.setBackgroundResource(R.drawable.bg_neo_step_failed)
                row.tvStepBadge.setTextColor(canvasColor)
                row.tvStepBadge.text = "✕"
                row.tvStepStatus.text = getString(R.string.step_status_failed)
                row.tvStepStatus.setTextColor(inkPrimary)
            }
        }
    }

    private fun isPackageInstalledOnDevice(pkg: String): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }

    private fun readSplitFilesFromIntent(): List<SplitApkFile> {
        val names = intent.getStringArrayListExtra(EXTRA_SPLIT_NAMES) ?: return emptyList()
        val paths = intent.getStringArrayListExtra(EXTRA_SPLIT_PATHS) ?: return emptyList()
        val sizes = intent.getLongArrayExtra(EXTRA_SPLIT_SIZES) ?: LongArray(0)
        val count = minOf(names.size, paths.size)
        return (0 until count).map { i ->
            SplitApkFile(
                splitName = names[i],
                path = paths[i],
                sizeBytes = sizes.getOrElse(i) { 0L }
            )
        }
    }

    companion object {
        private const val EXTRA_SOURCE_DIR = "extra_source_dir"
        private const val EXTRA_IS_SPLIT = "extra_is_split"
        private const val EXTRA_ORIGINAL_NAME = "extra_original_name"
        private const val EXTRA_ORIGINAL_PACKAGE = "extra_original_package"
        private const val EXTRA_CLONE_NAME = "extra_clone_name"
        private const val EXTRA_CLONE_PACKAGE = "extra_clone_package"
        private const val EXTRA_CUSTOM_ICON_PATH = "extra_custom_icon_path"
        private const val EXTRA_VERSION_CODE = "extra_version_code"
        private const val EXTRA_VERSION_NAME = "extra_version_name"
        private const val EXTRA_IS_UPDATE = "extra_is_update"
        private const val EXTRA_EXISTING_RECORD_ID = "extra_existing_record_id"
        private const val EXTRA_PREVIOUS_VERSION_CODE = "extra_previous_version_code"
        private const val EXTRA_SOURCE_ORIGIN = "extra_source_origin"
        private const val EXTRA_SPLIT_NAMES = "extra_split_names"
        private const val EXTRA_SPLIT_PATHS = "extra_split_paths"
        private const val EXTRA_SPLIT_SIZES = "extra_split_sizes"
        private const val STATE_COMPLETED_RECORD_JSON = "state_completed_record_json"

        fun createIntent(
            context: Context,
            sourceDir: String,
            isSplitApk: Boolean,
            originalAppName: String,
            originalPackageName: String,
            cloneName: String,
            clonePackageName: String,
            customIconPath: String? = null,
            splitFiles: List<SplitApkFile> = emptyList(),
            sourceVersionCode: Long = 0L,
            sourceVersionName: String = "",
            sourceOrigin: String = ""
        ): Intent {
            return Intent(context, CloneProgressActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_DIR, sourceDir)
                putExtra(EXTRA_IS_SPLIT, isSplitApk)
                putExtra(EXTRA_ORIGINAL_NAME, originalAppName)
                putExtra(EXTRA_ORIGINAL_PACKAGE, originalPackageName)
                putExtra(EXTRA_CLONE_NAME, cloneName)
                putExtra(EXTRA_CLONE_PACKAGE, clonePackageName)
                putExtra(EXTRA_VERSION_CODE, sourceVersionCode)
                putExtra(EXTRA_VERSION_NAME, sourceVersionName)
                putExtra(EXTRA_SOURCE_ORIGIN, sourceOrigin)
                if (!customIconPath.isNullOrBlank()) {
                    putExtra(EXTRA_CUSTOM_ICON_PATH, customIconPath)
                }
                if (splitFiles.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_SPLIT_NAMES, ArrayList(splitFiles.map { it.splitName }))
                    putStringArrayListExtra(EXTRA_SPLIT_PATHS, ArrayList(splitFiles.map { it.path }))
                    putExtra(EXTRA_SPLIT_SIZES, splitFiles.map { it.sizeBytes }.toLongArray())
                }
            }
        }

        fun createUpdateIntent(
            context: Context,
            record: ClonedApkRecord,
            sourceApp: InstalledApp
        ): Intent {
            return createIntent(
                context = context,
                sourceDir = sourceApp.sourceDir,
                isSplitApk = sourceApp.isSplitApk,
                originalAppName = sourceApp.label,
                originalPackageName = sourceApp.packageName,
                cloneName = record.cloneName,
                clonePackageName = record.clonePackageName,
                customIconPath = record.customIconPath?.takeIf { File(it).exists() },
                splitFiles = sourceApp.splitFiles,
                sourceVersionCode = sourceApp.versionCode,
                sourceVersionName = sourceApp.versionName.orEmpty(),
                sourceOrigin = record.sourceOrigin
            ).apply {
                putExtra(EXTRA_IS_UPDATE, true)
                putExtra(EXTRA_EXISTING_RECORD_ID, record.id)
                putExtra(EXTRA_PREVIOUS_VERSION_CODE, record.sourceVersionCode)
            }
        }
    }
}
