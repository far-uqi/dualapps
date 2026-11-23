package com.dualapps.cloner.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dualapps.cloner.R
import com.dualapps.cloner.databinding.ActivityConfigureCloneBinding
import com.dualapps.cloner.engine.PackageNameValidator
import com.dualapps.cloner.model.InstalledApp
import com.dualapps.cloner.util.FormatUtils
import com.dualapps.cloner.util.IconProcessor
import java.io.File
import java.util.UUID

class ConfigureCloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigureCloneBinding

    private lateinit var sourcePackage: String
    private lateinit var sourceName: String
    private lateinit var sourceDir: String
    private var sourceSizeBytes: Long = 0L
    private var isSplitApk: Boolean = false
    private var isCurrentPackageValid: Boolean = false
    private var originalAppIcon: Drawable? = null
    private var customIconFile: File? = null

    private val pickIconLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedIconUri(uri)
        }
    }

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

        originalAppIcon = runCatching { packageManager.getApplicationIcon(sourcePackage) }.getOrNull()
        if (originalAppIcon != null) {
            binding.ivSourceIcon.setImageDrawable(originalAppIcon)
            binding.ivCloneIconPreview.setImageDrawable(originalAppIcon)
        } else {
            binding.ivSourceIcon.setImageResource(R.mipmap.ic_launcher)
            binding.ivCloneIconPreview.setImageResource(R.mipmap.ic_launcher)
        }

        binding.btnPickIcon.setOnClickListener {
            pickIconLauncher.launch("image/*")
        }
        binding.btnResetIcon.setOnClickListener {
            resetCustomIcon()
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
                    clonePackageName = clonePackage,
                    customIconPath = customIconFile?.absolutePath
                )
            )
        }

        validateInputs()
    }

    private fun handlePickedIconUri(uri: Uri) {
        val iconsDir = File(cacheDir, "picked_icons")
        val targetFile = File(iconsDir, "icon_${UUID.randomUUID()}.png")
        try {
            val previewBitmap = IconProcessor.decodeCropAndSaveSquarePng(this, uri, targetFile)
            // Hapus berkas ikon sementara sebelumnya bila ada
            customIconFile?.delete()
            customIconFile = targetFile
            binding.ivCloneIconPreview.setImageBitmap(previewBitmap)
            binding.tvCloneIconStatus.setText(R.string.note_clone_icon_custom)
            binding.btnResetIcon.visibility = View.VISIBLE
        } catch (e: Exception) {
            targetFile.delete()
            Toast.makeText(this, R.string.err_icon_pick_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetCustomIcon() {
        customIconFile?.delete()
        customIconFile = null
        if (originalAppIcon != null) {
            binding.ivCloneIconPreview.setImageDrawable(originalAppIcon)
        } else {
            binding.ivCloneIconPreview.setImageResource(R.mipmap.ic_launcher)
        }
        binding.tvCloneIconStatus.setText(R.string.note_clone_icon_default)
        binding.btnResetIcon.visibility = View.GONE
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
