package com.dualapps.cloner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dualapps.cloner.R
import com.dualapps.cloner.databinding.ItemClonedApkBinding
import com.dualapps.cloner.model.ClonedApkRecord
import com.dualapps.cloner.util.FormatUtils

class ClonedApksAdapter(
    private val onInstallClick: (ClonedApkRecord) -> Unit,
    private val onShareClick: (ClonedApkRecord) -> Unit,
    private val onDeleteClick: (ClonedApkRecord) -> Unit
) : RecyclerView.Adapter<ClonedApksAdapter.ViewHolder>() {

    private val items = mutableListOf<ClonedApkRecord>()

    fun submitList(newItems: List<ClonedApkRecord>) {
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

        fun bind(record: ClonedApkRecord, position: Int) {
            binding.tvFolderTab.setBackgroundResource(
                if (position % 2 == 0) R.drawable.bg_neo_folder_tab_teal
                else R.drawable.bg_neo_folder_tab_purple
            )
            binding.tvCloneName.text = record.cloneName
            binding.tvClonePackage.text = record.clonePackageName
            binding.tvApkSize.text = FormatUtils.formatFileSize(record.apkSizeBytes)
            binding.tvCreatedAt.text = FormatUtils.formatTimestamp(record.createdAtMillis)

            binding.btnInstall.setOnClickListener { onInstallClick(record) }
            binding.btnShare.setOnClickListener { onShareClick(record) }
            binding.btnDelete.setOnClickListener { onDeleteClick(record) }
        }
    }
}
