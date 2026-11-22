package com.dualapps.cloner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dualapps.cloner.R
import com.dualapps.cloner.databinding.ActivityCloneProgressBinding
import com.dualapps.cloner.databinding.ItemCloneStepBinding
import com.dualapps.cloner.engine.AndroidArchivePackageReader
import com.dualapps.cloner.engine.ClonePipeline
import com.dualapps.cloner.engine.CloneRequest
import com.dualapps.cloner.engine.CloneStep
import com.dualapps.cloner.engine.DeviceKeystoreManager
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.CloneException
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.util.ApkInstaller
import com.dualapps.cloner.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CloneProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneProgressBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cloneJob: Job? = null

    private lateinit var request: CloneRequest
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

        request = CloneRequest(
            sourceApkPath = sourceDir,
            isSplitApk = intent.getBooleanExtra(EXTRA_IS_SPLIT, false),
            originalAppName = intent.getStringExtra(EXTRA_ORIGINAL_NAME).orEmpty(),
            originalPackageName = originalPkg,
            cloneName = intent.getStringExtra(EXTRA_CLONE_NAME).orEmpty(),
            clonePackageName = clonePkg
        )

        setupUi()
        startCloning()
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
            startCloning()
        }
        binding.btnInstall.setOnClickListener {
            val record = completedRecord ?: return@setOnClickListener
            ApkInstaller.installApk(this, record.apkFile)
        }

        buildStepRows()
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
        binding.tvHeadline.setText(R.string.headline_cloning)
        binding.progressBar.max = CloneStep.entries.size
        binding.progressBar.progress = 0
        binding.tvProgressCount.text = getString(R.string.progress_count_format, 0, CloneStep.entries.size)
        binding.cardError.visibility = View.GONE
        binding.cardSuccessFolder.visibility = View.GONE
        binding.btnInstall.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.btnCancel.visibility = View.VISIBLE
        CloneStep.entries.forEach { setStepVisual(it, StepVisual.PENDING) }
    }

    private fun startCloning() {
        cloneJob?.cancel()
        resetUiForRun()

        val pipeline = ClonePipeline(
            cacheDir = cacheDir,
            filesDir = filesDir,
            keystoreManager = DeviceKeystoreManager(filesDir),
            archivePackageReader = AndroidArchivePackageReader(packageManager),
            isPackageInstalled = ::isPackageInstalledOnDevice
        )

        cloneJob = uiScope.launch {
            try {
                val record = pipeline.execute(
                    request = request,
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

    private fun onCloneSuccess(record: ClonedApkRecord) {
        completedRecord = record
        binding.tvHeadline.setText(R.string.headline_results)
        binding.btnCancel.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.cardError.visibility = View.GONE

        binding.cardSuccessFolder.visibility = View.VISIBLE
        binding.tvSuccessName.text = record.cloneName
        binding.tvSuccessPackage.text = record.clonePackageName
        binding.tvSuccessSize.text = getString(
            R.string.label_apk_size_format,
            FormatUtils.formatFileSize(record.apkSizeBytes)
        )
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

    companion object {
        private const val EXTRA_SOURCE_DIR = "extra_source_dir"
        private const val EXTRA_IS_SPLIT = "extra_is_split"
        private const val EXTRA_ORIGINAL_NAME = "extra_original_name"
        private const val EXTRA_ORIGINAL_PACKAGE = "extra_original_package"
        private const val EXTRA_CLONE_NAME = "extra_clone_name"
        private const val EXTRA_CLONE_PACKAGE = "extra_clone_package"

        fun createIntent(
            context: Context,
            sourceDir: String,
            isSplitApk: Boolean,
            originalAppName: String,
            originalPackageName: String,
            cloneName: String,
            clonePackageName: String
        ): Intent {
            return Intent(context, CloneProgressActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_DIR, sourceDir)
                putExtra(EXTRA_IS_SPLIT, isSplitApk)
                putExtra(EXTRA_ORIGINAL_NAME, originalAppName)
                putExtra(EXTRA_ORIGINAL_PACKAGE, originalPackageName)
                putExtra(EXTRA_CLONE_NAME, cloneName)
                putExtra(EXTRA_CLONE_PACKAGE, clonePackageName)
            }
        }
    }
}
