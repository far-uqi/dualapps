package com.dualapps.cloner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dualapps.cloner.databinding.ActivityPlayProtectGuideBinding

/**
 * Halaman panduan statis berbahasa Indonesia saat Google Play Protect memblokir pemasangan APK hasil clone.
 * Aplikasi tidak pernah menonaktifkan Play Protect atau package verifier secara programatis.
 */
class PlayProtectGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayProtectGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayProtectGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, PlayProtectGuideActivity::class.java)
        }
    }
}
