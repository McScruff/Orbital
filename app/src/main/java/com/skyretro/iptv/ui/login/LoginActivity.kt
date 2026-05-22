package com.skyretro.iptv.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skyretro.iptv.data.model.XtreamCredentials
import com.skyretro.iptv.data.repository.XtreamRepository
import com.skyretro.iptv.databinding.ActivityLoginBinding
import com.skyretro.iptv.ui.home.HomeActivity
import com.skyretro.iptv.utils.PrefsManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val repository = XtreamRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // Check if we should skip auto-login (e.g. from Services)
        val skipAuto = intent.getBooleanExtra("skip_auto", false)

        // Auto-login if credentials saved
        if (!skipAuto && PrefsManager.hasCredentials(this)) {
            startHomeActivity()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadLastCredentials()
        setupClickListeners()
        animateIn()
    }

    private fun loadLastCredentials() {
        val creds = PrefsManager.getCredentials(this)
        if (creds != null) {
            binding.etServerUrl.setText(creds.serverUrl)
            binding.etUsername.setText(creds.username)
            binding.etPassword.setText(creds.password)
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                binding.tvError.text = "PLEASE FILL IN ALL FIELDS"
                binding.tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            authenticate(url, user, pass)
        }
    }

    private fun authenticate(url: String, username: String, password: String) {
        binding.btnConnect.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
        binding.tvStatus.text = "CONNECTING TO SKY..."

        lifecycleScope.launch {
            val result = repository.authenticate(url, username, password)
            result.fold(
                onSuccess = {
                    val credentials = XtreamCredentials(url, username, password)
                    PrefsManager.saveCredentials(this@LoginActivity, credentials)
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
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun animateIn() {
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(800).start()
    }
}
