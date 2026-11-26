package com.dualapps.cloner.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dualapps.cloner.R
import com.dualapps.cloner.databinding.ItemClonedApkBinding
import com.dualapps.cloner.engine.CloneUpdateStatus
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.FormatUtils

data class ClonedResultItem(
    val record: ClonedApkRecord,
    val updateStatus: CloneUpdateStatus,
    val installedSourceApp: InstalledApp?
)

class ClonedApksAdapter(
    private val onInstallClick: (ClonedApkRecord) -> Unit,
    private val onShareClick: (ClonedApkRecord) -> Unit,
    private val onDeleteClick: (ClonedApkRecord) -> Unit,
    private val onUpdateClick: (ClonedApkRecord, InstalledApp) -> Unit
) : RecyclerView.Adapter<ClonedApksAdapter.ViewHolder>() {

    private val items = mutableListOf<ClonedResultItem>()

    fun submitList(newItems: List<ClonedResultItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClonedApkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemClonedApkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ClonedResultItem, position: Int) {
            val ctx = binding.root.context
            val record = item.record

            binding.tvFolderTab.setBackgroundResource(
                if (position % 2 == 0) R.drawable.bg_neo_folder_tab_teal
                else R.drawable.bg_neo_folder_tab_purple
            )
            binding.tvCloneName.text = record.cloneName
            binding.tvClonePackage.text = record.clonePackageName
            val sizeText = FormatUtils.formatFileSize(record.apkSizeBytes)
            binding.tvApkSize.text = if (record.isSplitClone) {
                ctx.getString(R.string.result_size_split_format, sizeText, record.totalFileCount)
            } else {
                sizeText
            }
            binding.tvCreatedAt.text = FormatUtils.formatTimestamp(record.createdAtMillis)

            when (val status = item.updateStatus) {
                is CloneUpdateStatus.UpdateAvailable -> {
                    binding.tvUpdateBadge.visibility = View.VISIBLE
                    val currentVer = status.currentSourceVersionName.ifBlank { status.currentSourceVersionCode.toString() }
                    val clonedVer = status.clonedFromVersionName.ifBlank { status.clonedFromVersionCode.toString() }
                    binding.tvUpdateStatus.text = ctx.getString(
                        R.string.update_version_compare_format,
                        currentVer,
                        clonedVer
                    )
                    binding.btnUpdate.isEnabled = item.installedSourceApp != null
                    binding.btnUpdate.alpha = 1.0f
                    binding.btnUpdate.setBackgroundResource(R.drawable.bg_neo_button_yellow)
                    binding.btnUpdate.setOnClickListener {
                        val src = item.installedSourceApp ?: return@setOnClickListener
                        onUpdateClick(record, src)
                    }
                }
                CloneUpdateStatus.UpToDate -> {
                    binding.tvUpdateBadge.visibility = View.GONE
                    binding.tvUpdateStatus.setText(R.string.update_status_up_to_date)
                    disableUpdateButton()
                }
                CloneUpdateStatus.SourceNotInstalled -> {
                    binding.tvUpdateBadge.visibility = View.GONE
                    binding.tvUpdateStatus.setText(R.string.update_status_source_missing)
                    disableUpdateButton()
                }
                CloneUpdateStatus.CloneNotInstalled -> {
                    binding.tvUpdateBadge.visibility = View.GONE
                    binding.tvUpdateStatus.setText(R.string.update_status_clone_missing)
                    disableUpdateButton()
                }
                CloneUpdateStatus.UnknownSourceVersion -> {
                    binding.tvUpdateBadge.visibility = View.GONE
                    binding.tvUpdateStatus.setText(R.string.update_status_unknown_version)
                    disableUpdateButton()
                }
            }

            binding.btnInstall.setOnClickListener { onInstallClick(record) }
            binding.btnShare.setOnClickListener { onShareClick(record) }
            binding.btnDelete.setOnClickListener { onDeleteClick(record) }
        }

        private fun disableUpdateButton() {
            binding.btnUpdate.isEnabled = false
            binding.btnUpdate.alpha = 0.45f
            binding.btnUpdate.setBackgroundResource(R.drawable.bg_neo_button_white)
            binding.btnUpdate.setOnClickListener(null)
        }
    }
}
