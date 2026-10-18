package com.orbital.iptv.ui.nebula

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.databinding.ActivityNebulaSettingsBinding
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.emby.EmbyLoginActivity
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.login.LoginActivity
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.plex.PlexLoginActivity
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.TorboxPrefsManager
import com.orbital.iptv.utils.UpdateChecker
import kotlinx.coroutines.launch

/**
 * NEBULA Settings screen. Simple preferences are handled here; the heavier server / EPG / PIN flows
 * live in HomeActivity, so those rows launch it with EXTRA_SETTING_ACTION and it opens the dialog.
 */
class NebulaSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNebulaSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ThemeManager.load(this)
        if (ThemeManager.current != ThemeManager.AppTheme.NEBULA) { routeToClassicHome(); return }
        binding = ActivityNebulaSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NebulaRail.bind(
            this, binding.rail, NebulaRail.Item.SETTINGS,
            onHome = { finish() },
            onLive = { startActivity(Intent(this, NebulaLiveActivity::class.java)) }
        )
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        ThemeManager.load(this)
        if (ThemeManager.current != ThemeManager.AppTheme.NEBULA) { routeToClassicHome(); return }
        // "Clear all saved data" (run from HomeActivity) leaves us signed out.
        if (!PrefsManager.hasCredentials(this)) {
            startActivity(Intent(this, LoginActivity::class.java).putExtra("skip_auto", true))
            finish()
            return
        }
        NebulaRail.refresh(this, binding.rail)
        build()
    }

    private fun routeToClassicHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    // ── List building ─────────────────────────────────────────────────────────

    private fun build() {
        val list = binding.nbSettingsList
        list.removeAllViews()
        val onOff = { b: Boolean -> if (b) "On" else "Off" }

        section(list, "APPEARANCE")
        row(list, "Theme", ThemeManager.current.label) { showThemePicker() }

        section(list, "PLAYBACK")
        row(list, "TV mode (testing)", onOff(PrefsManager.isTvModeEnabled(this))) {
            PrefsManager.setTvModeEnabled(this, !PrefsManager.isTvModeEnabled(this)); build()
        }
        row(list, "Picture in picture", onOff(PrefsManager.isPipEnabled(this))) {
            PrefsManager.setPipEnabled(this, !PrefsManager.isPipEnabled(this)); build()
        }
        row(list, "Channel logos", onOff(PrefsManager.isChannelLogosEnabled(this))) {
            PrefsManager.setChannelLogosEnabled(this, !PrefsManager.isChannelLogosEnabled(this)); build()
        }
        row(list, "Live stream format", if (PrefsManager.getLiveFormat(this) == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)") {
            val next = if (PrefsManager.getLiveFormat(this) == "ts") "m3u8" else "ts"
            PrefsManager.setLiveFormat(this, next)
            ApiClient.liveFormat = next
            if (next == "m3u8") Toast.makeText(this, "HLS: try this if TS shows a black screen", Toast.LENGTH_LONG).show()
            build()
        }

        section(list, "SERVER")
        val activeName = PrefsManager.getActiveProfile(this)?.name ?: "-"
        row(list, "Servers", "${PrefsManager.getProfiles(this).size} saved  ·  $activeName") { delegate("servers") }
        row(list, "Refresh server", "") { delegate("refresh") }
        row(list, "Full EPG refresh", "") { delegate("epg") }
        row(list, "EPG update indicator", onOff(PrefsManager.isEpgIndicatorEnabled(this))) {
            PrefsManager.setEpgIndicatorEnabled(this, !PrefsManager.isEpgIndicatorEnabled(this)); build()
        }
        row(list, "Manage visible categories", "") { delegate("categories") }

        section(list, "CONNECTIONS")
        val emby = EmbyPrefsManager.getSession(this)
        row(list, "Emby", emby?.let { "Signed in as ${it.username}" } ?: "Not connected") {
            if (emby == null) startActivity(Intent(this, EmbyLoginActivity::class.java))
            else confirmService("EMBY", "Signed in as ${emby.username}.", Intent(this, EmbyBrowserActivity::class.java)) {
                EmbyPrefsManager.clearSession(this); build()
            }
        }
        val plex = PlexPrefsManager.getSession(this)
        row(list, "Plex", plex?.let { "Signed in as ${it.username}" } ?: "Not connected") {
            if (plex == null) startActivity(Intent(this, PlexLoginActivity::class.java))
            else confirmService("PLEX", "Signed in as ${plex.username}.", Intent(this, PlexBrowserActivity::class.java)) {
                PlexPrefsManager.clearSession(this); build()
            }
        }
        row(list, "Torbox API key", if (TorboxPrefsManager.getApiKey(this) != null) "Set" else "Not set") {
            keyDialog("TORBOX API KEY", "Get your key at torbox.app > Settings > API",
                TorboxPrefsManager.getApiKey(this) ?: "",
                onSave = { TorboxPrefsManager.saveApiKey(this, it) },
                onRemove = { TorboxPrefsManager.clear(this) })
        }
        row(list, "OpenSubtitles API key", if (PrefsManager.getOpenSubsApiKey(this) != null) "Set" else "Not set") {
            keyDialog("OPENSUBTITLES API KEY", "Get a free key at opensubtitles.com > Consumers",
                PrefsManager.getOpenSubsApiKey(this) ?: "",
                onSave = { PrefsManager.setOpenSubsApiKey(this, it) },
                onRemove = { PrefsManager.setOpenSubsApiKey(this, "") })
        }
        row(list, "TMDB API key", if (PrefsManager.getTmdbApiKey(this) != null) "Set" else "Not set") {
            keyDialog("TMDB API KEY", "Get a free key at themoviedb.org > Settings > API. Used for EPG posters and synopsis.",
                PrefsManager.getTmdbApiKey(this) ?: "",
                onSave = { PrefsManager.setTmdbApiKey(this, it) },
                onRemove = { PrefsManager.setTmdbApiKey(this, "") })
        }

        section(list, "SECURITY")
        row(list, "PIN protected categories", "") { delegate("pin") }
        row(list, "Change PIN", "") { delegate("changepin") }

        section(list, "ABOUT")
        row(list, "Check for updates", "") { checkForUpdates() }
        row(list, "Clear all saved data", "") { delegate("clear") }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        row(list, "Orbital", "v$version", clickable = false) {}
    }

    private fun section(list: LinearLayout, title: String) {
        val d = resources.displayMetrics.density
        list.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF7C6CFF.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding((4 * d).toInt(), (18 * d).toInt(), 0, (6 * d).toInt())
        })
    }

    private fun row(list: LinearLayout, title: String, value: String, clickable: Boolean = true, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * d).toInt())
                .also { it.setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt()) }
            setBackgroundResource(com.orbital.iptv.R.drawable.bg_nb_row)
            isFocusable = clickable
            isClickable = clickable
            if (clickable) setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (value.isNotEmpty()) row.addView(TextView(this).apply {
            text = value
            setTextColor(0xFF22D3EE.toInt())
            textSize = 13f
        })
        list.addView(row)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun delegate(action: String) {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            putExtra(HomeActivity.EXTRA_CLASSIC, true)
            putExtra(HomeActivity.EXTRA_SETTING_ACTION, action)
        })
    }

    private fun showThemePicker() {
        val themes = ThemeManager.allThemes
        val labels = themes.map { t -> if (t == ThemeManager.current) "●  ${t.label}" else "○  ${t.label}" }.toTypedArray()
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("SELECT THEME")
            .setItems(labels) { _, which ->
                ThemeManager.set(this, themes[which])
                if (themes[which] != ThemeManager.AppTheme.NEBULA) routeToClassicHome() else build()
            }
            .show()
    }

    private fun confirmService(title: String, message: String, open: Intent, onSignOut: () -> Unit) {
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OPEN") { _, _ -> startActivity(open) }
            .setNegativeButton("SIGN OUT") { _, _ -> onSignOut() }
            .setNeutralButton("CANCEL", null)
            .show()
    }

    private fun keyDialog(title: String, hint: String, current: String, onSave: (String) -> Unit, onRemove: () -> Unit) {
        val et = EditText(this).apply {
            setText(current)
            this.hint = "PASTE API KEY HERE"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        val builder = AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle(title)
            .setMessage(hint)
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                val key = et.text.toString().trim()
                if (key.isNotBlank()) { onSave(key); Toast.makeText(this, "SAVED", Toast.LENGTH_SHORT).show(); build() }
            }
            .setNegativeButton("CANCEL", null)
        if (current.isNotBlank()) builder.setNeutralButton("REMOVE KEY") { _, _ -> onRemove(); build() }
        builder.show()
    }

    private fun checkForUpdates() {
        Toast.makeText(this, "CHECKING FOR UPDATES...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val update = UpdateChecker.check(this@NebulaSettingsActivity)
            if (update == null) {
                Toast.makeText(this@NebulaSettingsActivity, "ORBITAL IS UP TO DATE", Toast.LENGTH_LONG).show()
            } else {
                // HomeActivity runs the download/install flow and checks on launch.
                startActivity(Intent(this@NebulaSettingsActivity, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_CLASSIC, true))
            }
        }
    }
}
