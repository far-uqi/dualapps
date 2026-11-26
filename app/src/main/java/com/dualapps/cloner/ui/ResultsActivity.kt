package com.dualapps.cloner.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.dualapps.cloner.R
import com.dualapps.cloner.data.ClonedApksRepository
import com.dualapps.cloner.data.InstalledAppsRepository
import com.dualapps.cloner.databinding.ActivityResultsBinding
import com.dualapps.cloner.databinding.DialogConfirmDeleteBinding
import com.dualapps.cloner.engine.CloneUpdateChecker
import com.dualapps.cloner.engine.InstalledPackageVersion
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.ApkInstaller
import com.dualapps.cloner.util.SplitApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding
    private lateinit var repository: ClonedApksRepository
    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var adapter: ClonedApksAdapter

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ClonedApksRepository(filesDir)
        installedAppsRepository = InstalledAppsRepository(packageManager)
        adapter = ClonedApksAdapter(
            onInstallClick = ::installRecord,
            onShareClick = ::shareRecord,
            onDeleteClick = ::confirmDeleteRecord,
            onUpdateClick = ::updateRecord
        )

        setupUi()
    }

    override fun onResume() {
        super.onResume()
        refreshResults()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun setupUi() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayProtectGuide.setOnClickListener {
            startActivity(PlayProtectGuideActivity.createIntent(this))
        }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
    }

    private fun refreshResults() {
        refreshJob?.cancel()
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
                        installedSourceApp = sourceApp
                    )
                }
            }

            adapter.submitList(items)
            binding.tvResultsCount.text = getString(R.string.section_results_count, items.size)

            val isEmpty = items.isEmpty()
            binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvResults.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
            SplitApkInstaller.installSplitClone(this, record)
        } else {
            ApkInstaller.installApk(this, record.apkFile)
        }
    }

    private fun shareRecord(record: ClonedApkRecord) {
        if (record.isSplitClone) {
            SplitApkInstaller.shareSplitApks(this, record)
        } else {
            ApkInstaller.shareApk(this, record.apkFile, record.cloneName)
        }
    }

    private fun confirmDeleteRecord(record: ClonedApkRecord) {
        val dialog = Dialog(this, R.style.Theme_Dualapps_Dialog)
        val dialogBinding = DialogConfirmDeleteBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.tvDeleteMessage.text = getString(
            R.string.delete_confirm_message,
            record.cloneName,
            record.clonePackageName
        )
        dialogBinding.btnCancelDelete.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.btnConfirmDelete.setOnClickListener {
            val deleted = repository.delete(record)
            dialog.dismiss()
            if (deleted) {
                Toast.makeText(this, R.string.msg_result_deleted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.err_result_delete_failed, Toast.LENGTH_SHORT).show()
            }
            refreshResults()
        }
        dialog.show()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, ResultsActivity::class.java)
        }
    }
}
