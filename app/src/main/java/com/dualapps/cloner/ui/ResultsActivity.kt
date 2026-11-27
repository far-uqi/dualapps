package com.dualapps.cloner.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.dualapps.cloner.R
import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.data.InstalledAppsRepository
import com.dualapps.cloner.databinding.ActivityResultsBinding
import com.dualapps.cloner.databinding.DialogConfirmDeleteBinding
import com.dualapps.cloner.databinding.DialogDeleteOptionsBinding
import com.dualapps.cloner.engine.CloneUpdateChecker
import com.dualapps.cloner.engine.InstalledPackageVersion
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.ApkInstaller
import com.dualapps.cloner.util.CloneDeleteCoordinator
import com.dualapps.cloner.util.CloneDeleteCoordinator.CloneDeleteMode
import com.dualapps.cloner.util.CloneDeleteCoordinator.DeleteExecutionOutcome
import com.dualapps.cloner.util.FormatUtils
import com.dualapps.cloner.util.InstallOrigin
import com.dualapps.cloner.util.InstallProgressState
import com.dualapps.cloner.util.InstallProgressTracker
import com.dualapps.cloner.util.ResultsLoadState
import com.dualapps.cloner.util.SplitApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding
    private lateinit var repository: ClonedApksRepository
    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var adapter: ClonedApksAdapter

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var statusAutoHideJob: Job? = null
    private var hasLoadedOnce: Boolean = false
    private var pendingUninstallRecord: ClonedApkRecord? = null

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val record = pendingUninstallRecord ?: return@registerForActivityResult
        pendingUninstallRecord = null
        val isStillInstalled = isPackageInstalledOnDevice(record.clonePackageName)
        when (
            CloneDeleteCoordinator.completeAfterSystemUninstall(
                record = record,
                isStillInstalledLive = isStillInstalled,
                deleteFiles = { repository.delete(it) }
            )
        ) {
            DeleteExecutionOutcome.DeletedFileAndUninstalled -> {
                Toast.makeText(
                    this,
                    R.string.msg_result_deleted_and_uninstalled,
                    Toast.LENGTH_SHORT
                ).show()
            }
            DeleteExecutionOutcome.CancelledAtSystemDialog -> {
                Toast.makeText(
                    this,
                    R.string.msg_delete_cancelled,
                    Toast.LENGTH_SHORT
                ).show()
            }
            DeleteExecutionOutcome.FileDeleteFailed -> {
                Toast.makeText(
                    this,
                    R.string.err_result_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> Unit
        }
        refreshResults()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.getString(STATE_PENDING_UNINSTALL_JSON)?.let { raw ->
            pendingUninstallRecord = ClonedApkRecord.fromJsonString(raw)
        }

        repository = ClonedApksRepository(filesDir)
        installedAppsRepository = InstalledAppsRepository(packageManager)
        adapter = ClonedApksAdapter(
            onOpenClick = ::openInstalledClone,
            onInstallClick = ::installRecord,
            onShareClick = ::shareRecord,
            onDeleteClick = ::startDeleteFlow,
            onUpdateClick = ::updateRecord
        )

        setupUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingUninstallRecord?.let { record ->
            outState.putString(STATE_PENDING_UNINSTALL_JSON, record.toJsonString())
        }
    }

    override fun onPause() {
        super.onPause()
        InstallProgressTracker.onActivityPaused()
    }

    override fun onResume() {
        super.onResume()
        InstallProgressTracker.onActivityResumed()
        refreshResults()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusAutoHideJob?.cancel()
        uiScope.cancel()
    }

    private fun setupUi() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayProtectGuide.setOnClickListener {
            startActivity(PlayProtectGuideActivity.createIntent(this))
        }
        binding.btnDismissInstallStatus.setOnClickListener {
            statusAutoHideJob?.cancel()
            InstallProgressTracker.consumeTerminalState()
        }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.itemAnimator = null
        binding.rvResults.adapter = adapter
        applyLoadState(ResultsLoadState.Loading)
        observeInstallProgress()
    }

    private fun applyLoadState(state: ResultsLoadState) {
        when (state) {
            ResultsLoadState.Loading -> {
                binding.layoutLoading.visibility = View.VISIBLE
                binding.layoutEmpty.visibility = View.GONE
                binding.rvResults.visibility = View.GONE
            }
            ResultsLoadState.Empty -> {
                binding.layoutLoading.visibility = View.GONE
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.rvResults.visibility = View.GONE
            }
            is ResultsLoadState.Content -> {
                binding.layoutLoading.visibility = View.GONE
                binding.layoutEmpty.visibility = View.GONE
                binding.rvResults.visibility = View.VISIBLE
            }
        }
    }

    private fun observeInstallProgress() {
        uiScope.launch {
            InstallProgressTracker.state.collect { state ->
                when (state) {
                    InstallProgressState.Idle -> {
                        binding.cardInstallProgress.visibility = View.GONE
                    }
                    is InstallProgressState.Installing -> {
                        statusAutoHideJob?.cancel()
                        binding.cardInstallProgress.visibility = View.VISIBLE
                        binding.pbInstallProgress.visibility = View.VISIBLE
                        binding.btnDismissInstallStatus.visibility = View.GONE
                        binding.tvInstallProgressText.text = if (state.cloneName.isNotBlank()) {
                            "${state.message} (${state.cloneName})"
                        } else {
                            state.message
                        }
                    }
                    is InstallProgressState.Succeeded -> {
                        binding.cardInstallProgress.visibility = View.VISIBLE
                        binding.pbInstallProgress.visibility = View.GONE
                        binding.btnDismissInstallStatus.visibility = View.VISIBLE
                        binding.tvInstallProgressText.text = if (state.cloneName.isNotBlank()) {
                            "${getString(R.string.status_installed_done)} (${state.cloneName})"
                        } else {
                            getString(R.string.status_installed_done)
                        }
                        refreshResults()
                        statusAutoHideJob?.cancel()
                        statusAutoHideJob = uiScope.launch {
                            delay(3500L)
                            InstallProgressTracker.consumeTerminalState()
                        }
                    }
                    is InstallProgressState.Failed -> {
                        statusAutoHideJob?.cancel()
                        binding.cardInstallProgress.visibility = View.VISIBLE
                        binding.pbInstallProgress.visibility = View.GONE
                        binding.btnDismissInstallStatus.visibility = View.VISIBLE
                        binding.tvInstallProgressText.text = state.message
                    }
                }
            }
        }
    }

    private fun refreshResults() {
        refreshJob?.cancel()
        if (!hasLoadedOnce) {
            applyLoadState(ResultsLoadState.Loading)
        }
        refreshJob = uiScope.launch {
            val items = withContext(Dispatchers.IO) {
                val records = repository.loadAll()
                val installedByPkg = installedAppsRepository.loadInstalledApps()
                    .associateBy { it.packageName }

                records.map { record ->
                    val sourceApp = installedByPkg[record.originalPackageName]
                    val sourceVer = sourceApp?.let {
                        InstalledPackageVersion(
                            packageName = it.packageName,
                            versionCode = it.versionCode,
                            versionName = it.versionName.orEmpty()
                        )
                    }
                    val isCloneInstalled = installedByPkg.containsKey(record.clonePackageName)
                    val updateStatus = CloneUpdateChecker.check(
                        record = record,
                        installedSource = sourceVer,
                        isCloneInstalled = isCloneInstalled
                    )
                    ClonedResultItem(
                        record = record,
                        updateStatus = updateStatus,
                        installedSourceApp = sourceApp,
                        isCloneInstalled = isCloneInstalled
                    )
                }
            }

            hasLoadedOnce = true
            binding.tvResultsCount.text = getString(R.string.section_results_count, items.size)
            // Sembunyikan indikator loading SEBELUM memperbarui & menampilkan daftar agar tidak ada frame tumpang tindih
            binding.layoutLoading.visibility = View.GONE
            adapter.submitList(items)
            applyLoadState(
                InstallProgressTracker.resolveResultsLoadState(
                    isLoading = false,
                    itemCount = items.size
                )
            )
        }
    }

    private fun openInstalledClone(record: ClonedApkRecord) {
        val launchIntent = packageManager.getLaunchIntentForPackage(record.clonePackageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, R.string.err_open_clone_no_launcher, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateRecord(record: ClonedApkRecord, sourceApp: InstalledApp) {
        startActivity(
            CloneProgressActivity.createUpdateIntent(
                context = this,
                record = record,
                sourceApp = sourceApp
            )
        )
    }

    private fun installRecord(record: ClonedApkRecord) {
        if (record.isSplitClone) {
            SplitApkInstaller.installSplitClone(this, record, origin = InstallOrigin.RESULTS)
        } else {
            val launched = ApkInstaller.installApk(this, record.apkFile)
            if (launched) {
                InstallProgressTracker.onSingleApkInstallLaunched(
                    record.clonePackageName,
                    record.cloneName,
                    origin = InstallOrigin.RESULTS
                )
            }
        }
    }

    private fun shareRecord(record: ClonedApkRecord) {
        if (record.isSplitClone) {
            SplitApkInstaller.shareSplitApks(this, record)
        } else {
            ApkInstaller.shareApk(this, record.apkFile, record.cloneName)
        }
    }

    private fun isPackageInstalledOnDevice(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun startDeleteFlow(record: ClonedApkRecord) {
        val isInstalledLive = isPackageInstalledOnDevice(record.clonePackageName)
        val formattedSize = FormatUtils.formatFileSize(record.apkSizeBytes)
        val choiceModel = CloneDeleteCoordinator.buildChoiceModel(
            record = record,
            isInstalledLive = isInstalledLive,
            formattedSize = formattedSize
        )

        val dialog = Dialog(this, R.style.Theme_Dualapps_Dialog)
        val dialogBinding = DialogDeleteOptionsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.tvDeleteOptionsApp.text = "${record.cloneName} (${record.clonePackageName})"
        dialogBinding.tvFileOnlySubtitle.text = choiceModel.fileOnlySubtitle

        if (choiceModel.showUninstallOption) {
            dialogBinding.optionDeleteFileAndUninstall.visibility = View.VISIBLE
            dialogBinding.tvFileAndUninstallSubtitle.text = choiceModel.fileAndUninstallSubtitle
            dialogBinding.optionDeleteFileAndUninstall.setOnClickListener {
                dialog.dismiss()
                showDeleteConfirmationDialog(record, CloneDeleteMode.FILE_AND_UNINSTALL)
            }
        } else {
            dialogBinding.optionDeleteFileAndUninstall.visibility = View.GONE
            dialogBinding.optionDeleteFileAndUninstall.setOnClickListener(null)
        }

        dialogBinding.optionDeleteFileOnly.setOnClickListener {
            dialog.dismiss()
            showDeleteConfirmationDialog(record, CloneDeleteMode.FILE_ONLY)
        }
        dialogBinding.btnCancelDeleteOptions.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showDeleteConfirmationDialog(record: ClonedApkRecord, mode: CloneDeleteMode) {
        val isInstalledLive = isPackageInstalledOnDevice(record.clonePackageName)
        val formattedSize = FormatUtils.formatFileSize(record.apkSizeBytes)
        val confirmModel = CloneDeleteCoordinator.buildConfirmModel(
            record = record,
            mode = mode,
            isInstalledLive = isInstalledLive,
            formattedSize = formattedSize
        )

        val dialog = Dialog(this, R.style.Theme_Dualapps_Dialog)
        val dialogBinding = DialogConfirmDeleteBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.tvDeleteMessage.text = confirmModel.message
        dialogBinding.btnCancelDelete.text = confirmModel.cancelLabel
        dialogBinding.btnConfirmDelete.text = confirmModel.confirmLabel

        dialogBinding.btnCancelDelete.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            executeConfirmedDelete(record, mode)
        }
        dialog.show()
    }

    private fun executeConfirmedDelete(record: ClonedApkRecord, mode: CloneDeleteMode) {
        val isInstalledLive = isPackageInstalledOnDevice(record.clonePackageName)
        when (
            val outcome = CloneDeleteCoordinator.beginConfirmedDelete(
                record = record,
                mode = mode,
                isInstalledLive = isInstalledLive,
                deleteFiles = { repository.delete(it) }
            )
        ) {
            DeleteExecutionOutcome.DeletedFileOnly -> {
                Toast.makeText(this, R.string.msg_result_deleted, Toast.LENGTH_SHORT).show()
                refreshResults()
            }
            DeleteExecutionOutcome.FileDeleteFailed -> {
                Toast.makeText(this, R.string.err_result_delete_failed, Toast.LENGTH_SHORT).show()
                refreshResults()
            }
            is DeleteExecutionOutcome.AppAlreadyUninstalled -> {
                Toast.makeText(this, R.string.err_uninstall_app_missing, Toast.LENGTH_LONG).show()
                refreshResults()
            }
            is DeleteExecutionOutcome.NeedsSystemUninstall -> {
                pendingUninstallRecord = record
                val uninstallIntent = Intent(
                    Intent.ACTION_DELETE,
                    Uri.parse("package:${outcome.packageName}")
                ).apply {
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
                try {
                    uninstallLauncher.launch(uninstallIntent)
                } catch (_: Exception) {
                    pendingUninstallRecord = null
                    Toast.makeText(
                        this,
                        R.string.err_uninstall_launch_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            else -> Unit
        }
    }

    companion object {
        private const val STATE_PENDING_UNINSTALL_JSON = "state_pending_uninstall_json"

        fun createIntent(context: Context): Intent {
            return Intent(context, ResultsActivity::class.java)
        }
    }
}
