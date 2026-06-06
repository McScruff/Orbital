package com.orbital.iptv.ui.plex

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.R
import com.orbital.iptv.data.plex.PlexRepository
import com.orbital.iptv.data.plex.PlexServer
import com.orbital.iptv.databinding.ActivityPlexLoginBinding
import com.orbital.iptv.utils.PlexPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.orbital.iptv.utils.ThemeManager

class PlexLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlexLoginBinding
    private val repository = PlexRepository()
    private var usePin = false
    private var pollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityPlexLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.root.findViewById<android.view.View>(R.id.layout_header)?.setBackgroundColor(p.bgHeader)
        binding.root.findViewById<android.view.View>(R.id.view_accent)?.setBackgroundColor(p.accent)

        binding.btnBack.setOnClickListener { finish() }
        binding.tabDirect.setOnClickListener  { selectTab(false) }
        binding.tabConnect.setOnClickListener { selectTab(true) }

        binding.btnLogin.setOnClickListener { attemptDirectLogin() }
        binding.etToken.setOnEditorActionListener { _, _, _ -> attemptDirectLogin(); true }

        binding.btnRefreshPin.setOnClickListener { startPinSession() }
        setupFocusHighlights()
    }

    private fun setupFocusHighlights() {
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        val inactiveBg = 0xFF1A2E4A.toInt()
        val yellowBg = 0xFFE5A00D.toInt()

        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) p.focus else p.bgHeader)
        }

        binding.tabDirect.setOnFocusChangeListener { _, hasFocus ->
            if (!usePin) return@setOnFocusChangeListener
            binding.tabDirect.setBackgroundColor(if (hasFocus) p.focus else inactiveBg)
        }
        binding.tabConnect.setOnFocusChangeListener { _, hasFocus ->
            if (usePin) return@setOnFocusChangeListener
            binding.tabConnect.setBackgroundColor(if (hasFocus) p.focus else inactiveBg)
        }

        listOf(binding.etServerUrl, binding.etToken).forEach { et ->
            et.setOnFocusChangeListener { _, hasFocus ->
                et.background = GradientDrawable().apply {
                    setColor(inactiveBg)
                    cornerRadius = p.cornerRadiusDp * density
                    if (hasFocus) setStroke((2 * density).toInt(), p.accent)
                }
            }
        }

        binding.btnLogin.setOnFocusChangeListener { _, hasFocus ->
            binding.btnLogin.background = GradientDrawable().apply {
                setColor(yellowBg)
                cornerRadius = p.cornerRadiusDp * density
                if (hasFocus) setStroke((3 * density).toInt(), p.accent)
            }
        }

        binding.btnRefreshPin.setOnFocusChangeListener { _, hasFocus ->
            binding.btnRefreshPin.setBackgroundColor(if (hasFocus) p.focus else 0xFF0A1628.toInt())
        }
    }

    private fun selectTab(pin: Boolean) {
        usePin = pin
        if (!pin) { pollingJob?.cancel(); pollingJob = null }

        binding.formDirect.visibility   = if (pin) View.GONE else View.VISIBLE
        binding.formConnect.visibility  = if (pin) View.VISIBLE else View.GONE
        binding.rowDirectBtn.visibility = if (pin) View.GONE else View.VISIBLE
        binding.tvError.visibility = View.GONE

        val activeText   = getColor(R.color.orbital_dark_blue)
        val activeBg     = 0xFFE5A00D.toInt()
        val inactiveText = 0xFFAABBCC.toInt()
        val inactiveBg   = 0xFF1A2E4A.toInt()

        binding.tabDirect.setTextColor(      if (!pin) activeText else inactiveText)
        binding.tabDirect.setBackgroundColor( if (!pin) activeBg  else inactiveBg)
        binding.tabConnect.setTextColor(     if (pin)  activeText else inactiveText)
        binding.tabConnect.setBackgroundColor(if (pin) activeBg  else inactiveBg)

        if (pin) startPinSession()
    }

    // ── Direct (server URL + token) ────────────────────────────────────────────

    private fun attemptDirectLogin() {
        val serverUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val token     = binding.etToken.text?.toString()?.trim() ?: ""

        if (serverUrl.isBlank()) { showError("Please enter the server URL"); return }
        if (token.isBlank())     { showError("Please enter your Plex token"); return }

        hideKeyboard(); setLoading(true); binding.tvError.visibility = View.GONE

        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { repository.getUserName(token) }
                .getOrDefault("Plex User")
            setLoading(false)
            saveAndOpen(serverUrl, token, name)
        }
    }

    // ── PIN linking flow ───────────────────────────────────────────────────────

    private fun startPinSession() {
        pollingJob?.cancel(); pollingJob = null
        binding.tvPin.text = "——"
        binding.tvPinStatus.text = "Requesting PIN…"
        binding.progressPin.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.requestPin() }
            result.onFailure {
                binding.progressPin.visibility = View.GONE
                binding.tvPinStatus.text = "Failed to get PIN. Tap GET NEW PIN."
                showError("PIN error: ${it.message?.take(120)}")
                return@launch
            }
            val (pinId, code) = result.getOrThrow()
            binding.tvPin.text = code.uppercase()
            binding.tvPinStatus.text = "Waiting for confirmation…"
            startPolling(pinId, code)
        }
    }

    private fun startPolling(pinId: Long, code: String) {
        pollingJob = lifecycleScope.launch {
            var attempts = 0
            while (isActive && attempts < 72) {
                delay(5_000)
                attempts++
                val token = withContext(Dispatchers.IO) { repository.checkPin(pinId, code) }.getOrNull()
                if (token != null) {
                    binding.tvPinStatus.text = "Confirmed! Fetching servers…"
                    onPinConfirmed(token)
                    return@launch
                }
            }
            if (isActive) {
                binding.progressPin.visibility = View.GONE
                binding.tvPinStatus.text = "PIN expired. Tap GET NEW PIN."
            }
        }
    }

    private suspend fun onPinConfirmed(token: String) {
        val username = withContext(Dispatchers.IO) { repository.getUserName(token) }
            .getOrDefault("Plex User")

        val serversResult = withContext(Dispatchers.IO) { repository.getServers(token) }
        binding.progressPin.visibility = View.GONE

        serversResult.onFailure {
            showError("Could not fetch servers: ${it.message?.take(100)}")
            binding.tvPinStatus.text = "Error. Tap GET NEW PIN to try again."
            return
        }

        val servers = serversResult.getOrDefault(emptyList())
        when {
            servers.isEmpty() -> {
                showError("No Plex servers found. Use SERVER & TOKEN tab to enter your server URL directly.")
                binding.tvPinStatus.text = "No servers linked."
            }
            servers.size == 1 -> connectToServer(servers[0], token, username)
            else -> showServerPicker(servers, token, username)
        }
    }

    private fun showServerPicker(servers: List<PlexServer>, token: String, username: String) {
        val names = servers.map { it.name.ifBlank { it.bestUrl() } }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SELECT SERVER")
            .setItems(names) { _, which ->
                lifecycleScope.launch { connectToServer(servers[which], token, username) }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private suspend fun connectToServer(server: PlexServer, token: String, username: String) {
        // Shared servers have a server-specific accessToken; own servers may leave it blank.
        val serverToken = server.accessToken.ifBlank { token }

        binding.tvPinStatus.text = "Testing connections…"
        binding.progressPin.visibility = View.VISIBLE
        val url = withContext(Dispatchers.IO) { repository.findBestWorkingUrl(server, serverToken) }
        binding.progressPin.visibility = View.GONE

        if (url != null) {
            saveAndOpen(url, serverToken, username)
            return
        }

        // All probes failed — pre-fill token and switch to SERVER & TOKEN tab for manual URL entry
        binding.etToken.setText(serverToken)
        selectTab(false)
        showError(
            "Auto-detect failed for '${server.name}'. " +
            "Your token is pre-filled — enter your server URL above " +
            "(e.g. http://192.168.x.x:32400 for a local server)."
        )
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private fun saveAndOpen(serverUrl: String, token: String, username: String) {
        PlexPrefsManager.saveSession(this, PlexPrefsManager.PlexSession(serverUrl, token, username))
        startActivity(Intent(this, PlexBrowserActivity::class.java))
        finish()
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progressLogin.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.alpha = if (loading) 0.5f else 1f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}
