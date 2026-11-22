package com.dualapps.cloner.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dualapps.cloner.R
import com.dualapps.cloner.databinding.ItemInstalledAppBinding
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.FormatUtils

class InstalledAppsAdapter(
    private val packageManager: PackageManager,
    private val onAppClick: (InstalledApp) -> Unit
) : ListAdapter<InstalledApp, InstalledAppsAdapter.AppViewHolder>(DiffCallback) {

    private val iconCache = LruCache<String, Drawable>(120)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemInstalledAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class AppViewHolder(
        private val binding: ItemInstalledAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: InstalledApp, position: Int) {
            binding.tvAppName.text = app.label
            binding.tvPackageName.text = app.packageName
            binding.tvApkSize.text = FormatUtils.formatFileSize(app.apkSizeBytes)

            // Tile ikon bergantian antara ungu dan teal sesuai Bagian 2B
            val tileRes = if (position % 2 == 0) {
                R.drawable.bg_neo_icon_tile_purple
            } else {
                R.drawable.bg_neo_icon_tile_teal
            }
            binding.iconTile.setBackgroundResource(tileRes)

            val cachedIcon = iconCache.get(app.packageName) ?: runCatching {
                packageManager.getApplicationIcon(app.packageName)
            }.getOrNull()?.also { iconCache.put(app.packageName, it) }

            if (cachedIcon != null) {
                binding.ivAppIcon.setImageDrawable(cachedIcon)
            } else {
                binding.ivAppIcon.setImageResource(R.mipmap.ic_launcher)
            }

            binding.tvSplitBadge.visibility = if (app.isSplitApk) View.VISIBLE else View.GONE
            binding.root.alpha = if (app.isSplitApk) 0.85f else 1.0f

            binding.root.setOnClickListener { onAppClick(app) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<InstalledApp>() {
        override fun areItemsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean {
            return oldItem == newItem
        }
    }
}
