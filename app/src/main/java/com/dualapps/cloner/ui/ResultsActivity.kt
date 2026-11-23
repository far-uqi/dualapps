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
import com.dualapps.cloner.databinding.ActivityResultsBinding
import com.dualapps.cloner.databinding.DialogConfirmDeleteBinding
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.util.ApkInstaller

class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding
    private lateinit var repository: ClonedApksRepository
    private lateinit var adapter: ClonedApksAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ClonedApksRepository(filesDir)
        adapter = ClonedApksAdapter(
            onInstallClick = ::installRecord,
            onShareClick = ::shareRecord,
            onDeleteClick = ::confirmDeleteRecord
        )

        setupUi()
    }

    override fun onResume() {
        super.onResume()
        refreshResults()
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
        val records = repository.loadAll()
        adapter.submitList(records)
        binding.tvResultsCount.text = getString(R.string.section_results_count, records.size)

        val isEmpty = records.isEmpty()
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvResults.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun installRecord(record: ClonedApkRecord) {
        ApkInstaller.installApk(this, record.apkFile)
    }

    private fun shareRecord(record: ClonedApkRecord) {
        ApkInstaller.shareApk(this, record.apkFile, record.cloneName)
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
