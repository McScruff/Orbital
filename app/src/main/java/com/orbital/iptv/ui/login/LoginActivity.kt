package com.orbital.iptv.ui.login

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.data.model.ServerProfile
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivityLoginBinding
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.tv.TvModeActivity
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.launch
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.R

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val repository = XtreamRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val skipAuto = intent.getBooleanExtra("skip_auto", false)
        if (!skipAuto && PrefsManager.hasCredentials(this)) {
            startHomeActivity()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.root.findViewById<android.view.View>(R.id.layout_header)?.setBackgroundColor(p.bgHeader)
        binding.root.findViewById<android.view.View>(R.id.view_accent)?.setBackgroundColor(p.accent)

        prefillForm()
        setupClickListeners()
        animateIn()
    }

    private fun prefillForm() {
        val profile = PrefsManager.getActiveProfile(this)
        if (profile != null) {
            binding.etServerUrl.setText(profile.serverUrl)
            binding.etUsername.setText(profile.username)
            binding.etPassword.setText(profile.password)
        }
        // Suggest a default profile name
        val count = PrefsManager.getProfiles(this).size
        binding.etProfileName.hint = if (count == 0) "My Server" else "Server ${count + 1}"
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            val url  = binding.etServerUrl.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            val name = binding.etProfileName.text.toString().trim().ifEmpty {
                binding.etProfileName.hint.toString()
            }

            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                binding.tvError.text = "PLEASE FILL IN ALL FIELDS"
                binding.tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            authenticate(url, user, pass, name)
        }
    }

    private fun authenticate(url: String, username: String, password: String, name: String) {
        binding.btnConnect.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
        binding.tvStatus.text = "CONNECTING..."

        lifecycleScope.launch {
            val result = repository.authenticate(url, username, password)
            result.fold(
                onSuccess = {
                    val profile = ServerProfile(name = name, serverUrl = url, username = username, password = password)
                    PrefsManager.saveProfile(this@LoginActivity, profile)
                    PrefsManager.setActiveProfile(this@LoginActivity, profile.id)
                    binding.tvStatus.text = "AUTHENTICATION SUCCESSFUL"
                    startHomeActivity()
                },
                onFailure = { error ->
                    binding.btnConnect.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "READY TO CONNECT"
                    binding.tvError.text = "ERROR: ${error.message?.uppercase() ?: "CONNECTION FAILED"}"
                    binding.tvError.visibility = View.VISIBLE
                }
            )
        }
    }

    private fun startHomeActivity() {
        val dest = if (PrefsManager.isTvModeEnabled(this) && PrefsManager.getLastTvChannelUrl(this) != null) {
            Intent(this, TvModeActivity::class.java)
        } else {
            Intent(this, HomeActivity::class.java)
        }
        startActivity(dest)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun animateIn() {
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(800).start()
    }
}
