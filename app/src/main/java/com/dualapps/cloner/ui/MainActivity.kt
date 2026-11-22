package com.dualapps.cloner.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.dualapps.cloner.R
import com.dualapps.cloner.data.InstalledAppsRepository
import com.dualapps.cloner.databinding.ActivityMainBinding
import com.dualapps.cloner.databinding.DialogSettingsBinding
import com.dualapps.cloner.model.AppSortOrder
import com.dualapps.cloner.model.CloneError
import com.dualapps.cloner.model.InstalledApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: InstalledAppsRepository
    private lateinit var adapter: InstalledAppsAdapter

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    private var allApps: List<InstalledApp> = emptyList()
    private var currentQuery: String = ""
    private var showSystemApps: Boolean = false
    private var sortOrder: AppSortOrder = AppSortOrder.NAME_ASC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = InstalledAppsRepository(packageManager)
        adapter = InstalledAppsAdapter(packageManager, ::onAppSelected)

        setupList()
        setupTopBar()
        setupSearchAndActions()
        setupSortButton()
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun setupList() {
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun setupTopBar() {
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
        binding.btnResults.setOnClickListener {
            Toast.makeText(this, R.string.msg_results_phase_note, Toast.LENGTH_SHORT).show()
        }
        binding.btnSearchFocus.setOnClickListener {
            binding.etSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSearchAndActions() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                binding.btnClearSearch.visibility =
                    if (currentQuery.isEmpty()) View.GONE else View.VISIBLE
                applyFilterAndSort()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }

        // Tombol + kuning (Bagian 2B): di MVP menampilkan pesan "Segera hadir", jangan dibuat fungsional
        binding.btnAddApk.setOnClickListener {
            Toast.makeText(this, R.string.msg_coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSortButton() {
        binding.btnSort.setOnClickListener {
            sortOrder = when (sortOrder) {
                AppSortOrder.NAME_ASC -> AppSortOrder.NAME_DESC
                AppSortOrder.NAME_DESC -> AppSortOrder.SIZE_DESC
                AppSortOrder.SIZE_DESC -> AppSortOrder.NAME_ASC
            }
            updateSortLabel()
            applyFilterAndSort()
        }
        updateSortLabel()
    }

    private fun updateSortLabel() {
        val labelRes = when (sortOrder) {
            AppSortOrder.NAME_ASC -> R.string.sort_az
            AppSortOrder.NAME_DESC -> R.string.sort_za
            AppSortOrder.SIZE_DESC -> R.string.sort_size
        }
        binding.tvSortLabel.setText(labelRes)
    }

    private fun loadApps() {
        loadJob?.cancel()
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutEmpty.visibility = View.GONE
        binding.rvApps.visibility = View.GONE

        loadJob = uiScope.launch {
            allApps = repository.loadInstalledApps()
            binding.layoutLoading.visibility = View.GONE
            applyFilterAndSort()
        }
    }

    private fun applyFilterAndSort() {
        val filtered = InstalledAppsRepository.filterAndSort(
            apps = allApps,
            query = currentQuery,
            showSystemApps = showSystemApps,
            sortOrder = sortOrder
        )
        adapter.submitList(filtered)

        binding.tvSectionTitle.text = getString(R.string.section_installed_apps_count, filtered.size)
        binding.tvFilterNote.setText(
            if (showSystemApps) R.string.filter_system_shown_note else R.string.filter_system_hidden_note
        )

        val isEmpty = filtered.isEmpty()
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvApps.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun onAppSelected(app: InstalledApp) {
        if (app.isSplitApk) {
            Toast.makeText(this, CloneError.SPLIT_UNSUPPORTED.userMessage, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(ConfigureCloneActivity.createIntent(this, app))
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this, R.style.Theme_Dualapps_Dialog)
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.switchShowSystem.isChecked = showSystemApps
        dialogBinding.switchShowSystem.setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            applyFilterAndSort()
        }
        dialogBinding.rowShowSystem.setOnClickListener {
            dialogBinding.switchShowSystem.toggle()
        }
        dialogBinding.btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }
}
