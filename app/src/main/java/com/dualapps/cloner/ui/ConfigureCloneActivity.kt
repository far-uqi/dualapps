package com.dualapps.cloner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.dualapps.cloner.R
import com.dualapps.cloner.databinding.ActivityConfigureCloneBinding
import com.dualapps.cloner.engine.PackageNameValidator
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.FormatUtils

class ConfigureCloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigureCloneBinding

    private lateinit var sourcePackage: String
    private lateinit var sourceName: String
    private lateinit var sourceDir: String
    private var sourceSizeBytes: Long = 0L
    private var isSplitApk: Boolean = false
    private var isCurrentPackageValid: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigureCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourcePackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        sourceName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        sourceDir = intent.getStringExtra(EXTRA_SOURCE_DIR).orEmpty()
        sourceSizeBytes = intent.getLongExtra(EXTRA_APK_SIZE, 0L)
        isSplitApk = intent.getBooleanExtra(EXTRA_IS_SPLIT, false)

        if (sourcePackage.isEmpty() || sourceDir.isEmpty()) {
            finish()
            return
        }

        setupViews()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener { finish() }

        binding.tvSourceName.text = sourceName
        binding.tvSourcePackage.text = sourcePackage
        binding.tvSourceSize.text = FormatUtils.formatFileSize(sourceSizeBytes)

        val icon = runCatching { packageManager.getApplicationIcon(sourcePackage) }.getOrNull()
        if (icon != null) {
            binding.ivSourceIcon.setImageDrawable(icon)
        } else {
            binding.ivSourceIcon.setImageResource(R.mipmap.ic_launcher)
        }

        // Default nama clone: "<nama asli> 2"
        binding.etCloneName.setText(getString(R.string.default_clone_name_format, sourceName))

        // Default package clone otomatis (.clone, .clone2, .clone3, ...)
        val suggestedPkg = PackageNameValidator.suggestClonePackage(
            originalPackage = sourcePackage,
            isInstalled = ::isPackageInstalledOnDevice
        )
        binding.etClonePackage.setText(suggestedPkg)

        binding.etClonePackage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateInputs()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnStartClone.setOnClickListener {
            validateInputs()
            if (!isCurrentPackageValid) return@setOnClickListener

            val cloneName = binding.etCloneName.text?.toString()?.trim()
                .takeUnless { it.isNullOrEmpty() }
                ?: getString(R.string.default_clone_name_format, sourceName)
            val clonePackage = binding.etClonePackage.text?.toString()?.trim().orEmpty()

            startActivity(
                CloneProgressActivity.createIntent(
                    context = this,
                    sourceDir = sourceDir,
                    isSplitApk = isSplitApk,
                    originalAppName = sourceName,
                    originalPackageName = sourcePackage,
                    cloneName = cloneName,
                    clonePackageName = clonePackage
                )
            )
        }

        validateInputs()
    }

    private fun validateInputs() {
        val candidate = binding.etClonePackage.text?.toString().orEmpty()
        val result = PackageNameValidator.validate(
            packageName = candidate,
            originalPackage = sourcePackage,
            isInstalled = ::isPackageInstalledOnDevice
        )

        when (result) {
            is PackageNameValidator.ValidationResult.Valid -> {
                isCurrentPackageValid = true
                binding.tvPackageValidation.text = getString(R.string.validation_package_ok)
                binding.btnStartClone.isEnabled = true
                binding.btnStartClone.alpha = 1.0f
            }
            is PackageNameValidator.ValidationResult.Invalid -> {
                isCurrentPackageValid = false
                binding.tvPackageValidation.text = result.message
                binding.btnStartClone.isEnabled = false
                binding.btnStartClone.alpha = 0.5f
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
        private const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val EXTRA_APP_NAME = "extra_app_name"
        private const val EXTRA_SOURCE_DIR = "extra_source_dir"
        private const val EXTRA_APK_SIZE = "extra_apk_size"
        private const val EXTRA_IS_SPLIT = "extra_is_split"

        fun createIntent(context: Context, app: InstalledApp): Intent {
            return Intent(context, ConfigureCloneActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, app.packageName)
                putExtra(EXTRA_APP_NAME, app.label)
                putExtra(EXTRA_SOURCE_DIR, app.sourceDir)
                putExtra(EXTRA_APK_SIZE, app.apkSizeBytes)
                putExtra(EXTRA_IS_SPLIT, app.isSplitApk)
            }
        }
    }
}
