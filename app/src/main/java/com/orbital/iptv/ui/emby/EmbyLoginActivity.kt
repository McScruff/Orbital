package com.orbital.iptv.ui.emby

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.R
import com.orbital.iptv.data.emby.ConnectServer
import com.orbital.iptv.data.emby.EmbyRepository
import com.orbital.iptv.databinding.ActivityEmbyLoginBinding
import com.orbital.iptv.utils.EmbyPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.orbital.iptv.utils.ThemeManager

class EmbyLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmbyLoginBinding
    private val repository = EmbyRepository()
    private var useConnect = false
    private var pollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityEmbyLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.root.findViewById<android.view.View>(R.id.layout_header)?.setBackgroundColor(p.bgHeader)
        binding.root.findViewById<android.view.View>(R.id.view_accent)?.setBackgroundColor(p.accent)

        val savedUrl = EmbyPrefsManager.getSavedServerUrl(this)
        if (savedUrl.isNotBlank()) binding.etServerUrl.setText(savedUrl)

        binding.btnBack.setOnClickListener { finish() }
        binding.tabDirect.setOnClickListener  { selectTab(false) }
        binding.tabConnect.setOnClickListener { selectTab(true) }

        binding.btnLogin.setOnClickListener { attemptDirectLogin() }
        binding.etPassword.setOnEditorActionListener { _, _, _ -> attemptDirectLogin(); true }

        binding.btnRefreshPin.setOnClickListener { startPinSession() }
        setupFocusHighlights()
    }

    private fun setupFocusHighlights() {
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        val inactiveBg = 0xFF1A2E4A.toInt()
        val yellowBg = getColor(R.color.sky_yellow)

        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) p.focus else p.bgHeader)
        }

        // Inactive tab gets focus highlight; active tab is already visually obvious
        binding.tabDirect.setOnFocusChangeListener { _, hasFocus ->
            if (!useConnect) return@setOnFocusChangeListener
            binding.tabDirect.setBackgroundColor(if (hasFocus) p.focus else inactiveBg)
        }
        binding.tabConnect.setOnFocusChangeListener { _, hasFocus ->
            if (useConnect) return@setOnFocusChangeListener
            binding.tabConnect.setBackgroundColor(if (hasFocus) p.focus else inactiveBg)
        }

        // EditTexts — bright accent border on focus
        listOf(binding.etServerUrl, binding.etUsername, binding.etPassword).forEach { et ->
            et.setOnFocusChangeListener { _, hasFocus ->
                et.background = GradientDrawable().apply {
                    setColor(inactiveBg)
                    cornerRadius = p.cornerRadiusDp * density
                    if (hasFocus) setStroke((2 * density).toInt(), p.accent)
                }
            }
        }

        // Login button — keep yellow but add accent border on focus
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

    private fun selectTab(connect: Boolean) {
        useConnect = connect

        if (!connect) {
            pollingJob?.cancel()
            pollingJob = null
        }

        binding.formDirect.visibility   = if (connect) View.GONE else View.VISIBLE
        binding.formConnect.visibility  = if (connect) View.VISIBLE else View.GONE
        binding.rowDirectBtn.visibility = if (connect) View.GONE else View.VISIBLE
        binding.tvError.visibility = View.GONE

        val activeText   = getColor(R.color.orbital_dark_blue)
        val activeBg     = getColor(R.color.sky_yellow)
        val inactiveText = 0xFFAABBCC.toInt()
        val inactiveBg   = 0xFF1A2E4A.toInt()

        binding.tabDirect.setTextColor(      if (!connect) activeText else inactiveText)
        binding.tabDirect.setBackgroundColor( if (!connect) activeBg  else inactiveBg)
        binding.tabConnect.setTextColor(     if (connect)  activeText else inactiveText)
        binding.tabConnect.setBackgroundColor(if (connect) activeBg  else inactiveBg)

        if (connect) startPinSession()
    }

    // ── Direct (server URL + username + password) ─────────────────────────────

    private fun attemptDirectLogin() {
        val serverUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val username  = binding.etUsername.text?.toString()?.trim() ?: ""
        val password  = binding.etPassword.text?.toString() ?: ""

        if (serverUrl.isBlank()) { showError("Please enter the server URL"); return }
        if (username.isBlank())  { showError("Please enter your username"); return }

        hideKeyboard(); setLoading(true); binding.tvError.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.authenticate(serverUrl, username, password)
            }
            setLoading(false)
            result.fold(
                onSuccess = { auth ->
                    saveAndOpen(serverUrl, auth.user.id, auth.accessToken, auth.user.name)
                },
                onFailure = { showError("Login failed: ${it.message?.take(100)}") }
            )
        }
    }

    // ── PIN linking flow ──────────────────────────────────────────────────────

    private fun startPinSession() {
        pollingJob?.cancel()
        pollingJob = null

        binding.tvPin.text = "——"
        binding.tvPinStatus.text = "Requesting PIN…"
        binding.progressPin.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.requestPin() }
            result.onFailure {
                binding.progressPin.visibility = View.GONE
                binding.tvPinStatus.text = "Failed to get PIN. Tap GET NEW PIN."
                showError("PIN error: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                return@launch
            }
            val pinResponse = result.getOrThrow()
            binding.tvPin.text = pinResponse.pin
            binding.tvPinStatus.text = "Waiting for confirmation…"
            startPolling(pinResponse.pin, pinResponse.deviceId)
        }
    }

    private fun startPolling(pin: String, deviceId: String) {
        pollingJob = lifecycleScope.launch {
            var attempts = 0
            var confirmed = false
            while (isActive && attempts < 60 && !confirmed) {
                delay(5_000)
                attempts++
                val response = withContext(Dispatchers.IO) { repository.checkPin(pin, deviceId) }.getOrNull()
                if (response?.isExpired == true) {
                    binding.progressPin.visibility = View.GONE
                    binding.tvPinStatus.text = "PIN expired. Tap GET NEW PIN."
                    return@launch
                }
                if (response != null && response.isConfirmed) {
                    confirmed = true
                    binding.tvPinStatus.text = "Confirmed! Signing in…"
                    // Exchange PIN for real ConnectUserId + ConnectAccessToken
                    onPinConfirmed(pin, deviceId)
                }
            }
            if (isActive && !confirmed) {
                binding.progressPin.visibility = View.GONE
                binding.tvPinStatus.text = "PIN expired. Tap GET NEW PIN."
            }
        }
    }

    private suspend fun onPinConfirmed(pin: String, deviceId: String) {
        // Step 1: exchange confirmed PIN for ConnectUserId + ConnectAccessToken
        val exchangeResult = withContext(Dispatchers.IO) { repository.exchangePin(pin, deviceId) }
        exchangeResult.onFailure {
            binding.progressPin.visibility = View.GONE
            showError("Exchange failed: ${it.message?.take(200)}")
            binding.tvPinStatus.text = "Error. Tap GET NEW PIN to try again."
            return
        }
        val pinAuth = exchangeResult.getOrThrow()
        val connectUserId = pinAuth.userId
        val connectToken  = pinAuth.accessToken

        android.util.Log.d("EmbyConnect", "exchangePin: userId=$connectUserId token=$connectToken")

        // Step 2: get list of servers for this Connect account
        val serversResult = withContext(Dispatchers.IO) {
            repository.getConnectServers(connectUserId, connectToken)
        }
        binding.progressPin.visibility = View.GONE
        serversResult.onFailure {
            showError("Could not fetch servers: ${it.message?.take(100)}")
            binding.tvPinStatus.text = "Error. Tap GET NEW PIN to try again."
            return
        }
        val servers = serversResult.getOrDefault(emptyList())
        when {
            servers.isEmpty() -> {
                showError("No servers found on your Emby Connect account. If you have a server URL and password, use the DIRECT tab instead.")
                binding.tvPinStatus.text = "No servers linked. Try DIRECT tab."
            }
            servers.size == 1 -> connectToServer(servers[0], connectUserId)
            else -> showServerPicker(servers, connectUserId)
        }
    }

    private fun showServerPicker(servers: List<ConnectServer>, connectUserId: String) {
        val names = servers.map { it.name.ifBlank { it.bestUrl() } }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SELECT SERVER")
            .setItems(names) { _, which ->
                lifecycleScope.launch { connectToServer(servers[which], connectUserId) }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private suspend fun connectToServer(server: ConnectServer, connectUserId: String) {
        val serverUrl = server.bestUrl()
        if (serverUrl.isBlank()) { showError("Server has no accessible URL"); return }

        binding.tvPinStatus.text = "Connecting to ${server.name.ifBlank { serverUrl }}…"
        binding.progressPin.visibility = View.VISIBLE

        // Step 3: exchange server AccessKey for local server credentials
        val exchangeResult = withContext(Dispatchers.IO) {
            repository.connectExchange(serverUrl, connectUserId, server.accessKey)
        }
        binding.progressPin.visibility = View.GONE
        exchangeResult.fold(
            onSuccess = { exchange ->
                saveAndOpen(serverUrl, exchange.localUserId, exchange.accessToken, server.name)
            },
            onFailure = { showError("Server connection failed: ${it.message?.take(100)}") }
        )
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun saveAndOpen(serverUrl: String, userId: String, token: String, name: String) {
        EmbyPrefsManager.saveSession(this,
            EmbyPrefsManager.EmbySession(serverUrl, userId, token, name)
        )
        startActivity(Intent(this, EmbyBrowserActivity::class.java))
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
