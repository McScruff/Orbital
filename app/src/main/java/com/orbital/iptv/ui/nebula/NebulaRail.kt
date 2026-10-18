package com.orbital.iptv.ui.nebula

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.orbital.iptv.databinding.ViewNebulaRailBinding
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.favourites.FavouritesActivity
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.search.GlobalSearchActivity
import com.orbital.iptv.ui.series.SeriesActivity
import com.orbital.iptv.ui.sports.SportsActivity
import com.orbital.iptv.ui.torbox.TorboxBrowserActivity
import com.orbital.iptv.ui.vod.VodActivity
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.utils.TorboxPrefsManager

/**
 * Top navigation bar shared by the NEBULA screens (still bound as "rail", its old side-rail id).
 * Each section is a coloured tile; the current section, and whichever one has D-pad focus,
 * expands to show its name. Also keeps the bar's clock ticking.
 *
 * Back works the same on every section: from the screen's content it moves focus up to the
 * section's own tile, and from the bar it goes to the home screen (closing any sections left open
 * underneath, so Back never wanders through previously opened screens).
 */
object NebulaRail {

    enum class Item { HOME, LIVE, GUIDE, MOVIES, SERIES, SEARCH, FAVS, SPORTS, EMBY, PLEX, TORBOX, MORE, SETTINGS }

    fun bind(
        activity: Activity,
        rail: ViewNebulaRailBinding,
        selected: Item,
        onHome: () -> Unit,
        onLive: () -> Unit
    ) {
        val items = listOf(
            Item.HOME to rail.navHome,
            Item.LIVE to rail.navLive,
            Item.GUIDE to rail.navGuide,
            Item.MOVIES to rail.navMovies,
            Item.SERIES to rail.navSeries,
            Item.SPORTS to rail.navSports,
            Item.EMBY to rail.navEmby,
            Item.PLEX to rail.navPlex,
            Item.TORBOX to rail.navTorbox,
            Item.FAVS to rail.navFavs,
            Item.SEARCH to rail.navSearch,
            Item.MORE to rail.navMore,
            Item.SETTINGS to rail.navSettings
        )
        items.forEach { (item, view) ->
            view.isSelected = item == selected
            style(view, item == selected)
            view.setOnFocusChangeListener { v, _ -> style(v as LinearLayout, item == selected) }
        }
        startClock(rail.navClock)
        refresh(activity, rail)

        if (selected != Item.HOME && activity is ComponentActivity) {
            activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = back(activity, rail, selected)
            })
        }

        rail.navHome.setOnClickListener { if (selected == Item.HOME) onHome() else goHome(activity) }
        rail.navLive.setOnClickListener { onLive() }
        rail.navGuide.setOnClickListener {
            if (selected != Item.GUIDE) activity.startActivity(Intent(activity, HomeActivity::class.java).apply {
                putExtra(HomeActivity.EXTRA_CLASSIC, true)
            }.front())
        }
        rail.navMovies.setOnClickListener {
            if (selected != Item.MOVIES) activity.startActivity(Intent(activity, VodActivity::class.java).front())
        }
        rail.navSeries.setOnClickListener {
            if (selected != Item.SERIES) activity.startActivity(Intent(activity, SeriesActivity::class.java).front())
        }
        rail.navSearch.setOnClickListener {
            if (selected != Item.SEARCH) activity.startActivity(Intent(activity, GlobalSearchActivity::class.java).front())
        }
        rail.navFavs.setOnClickListener {
            if (selected != Item.FAVS) activity.startActivity(Intent(activity, FavouritesActivity::class.java).front())
        }
        rail.navSports.setOnClickListener {
            if (selected != Item.SPORTS) activity.startActivity(Intent(activity, SportsActivity::class.java).front())
        }
        rail.navEmby.setOnClickListener {
            if (selected != Item.EMBY) activity.startActivity(Intent(activity, EmbyBrowserActivity::class.java).front())
        }
        rail.navPlex.setOnClickListener {
            if (selected != Item.PLEX) activity.startActivity(Intent(activity, PlexBrowserActivity::class.java).front())
        }
        rail.navTorbox.setOnClickListener {
            if (selected != Item.TORBOX) activity.startActivity(Intent(activity, TorboxBrowserActivity::class.java).front())
        }
        rail.navMore.setOnClickListener {
            if (selected != Item.MORE) activity.startActivity(Intent(activity, NebulaMoreActivity::class.java).front())
        }
        rail.navSettings.setOnClickListener {
            if (selected != Item.SETTINGS) activity.startActivity(Intent(activity, NebulaSettingsActivity::class.java).front())
        }
    }

    /**
     * Back from a section: content → the section's tile in the bar → the home screen. On a touch
     * screen (phones) nothing holds focus and the tile can't take it, so Back goes straight home.
     */
    fun back(activity: Activity, rail: ViewNebulaRailBinding, selected: Item) {
        if (rail.root.hasFocus() || !tile(rail, selected).requestFocus()) goHome(activity)
    }

    /** Back to the home screen, closing every section screen opened on top of it. */
    fun goHome(activity: Activity) {
        activity.startActivity(Intent(activity, NebulaHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        activity.finish()
    }

    private fun tile(rail: ViewNebulaRailBinding, item: Item): View = when (item) {
        Item.HOME -> rail.navHome
        Item.LIVE -> rail.navLive
        Item.GUIDE -> rail.navGuide
        Item.MOVIES -> rail.navMovies
        Item.SERIES -> rail.navSeries
        Item.SEARCH -> rail.navSearch
        Item.FAVS -> rail.navFavs
        Item.SPORTS -> rail.navSports
        Item.EMBY -> rail.navEmby
        Item.PLEX -> rail.navPlex
        Item.TORBOX -> rail.navTorbox
        Item.MORE -> rail.navMore
        Item.SETTINGS -> rail.navSettings
    }

    /**
     * Brings the section's screen forward if it's already open instead of stacking another copy:
     * each TV Guide copy loads the whole channel list itself, so a few of them could keep a TV
     * box busy for minutes and starve every other screen.
     */
    private fun Intent.front(): Intent = addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /** Label shows for the current or focused section; other tiles sit slightly dimmed. */
    private fun style(view: LinearLayout, selected: Boolean) {
        val on = selected || view.hasFocus()
        (view.getChildAt(1) as TextView).visibility = if (on) View.VISIBLE else View.GONE
        view.getChildAt(0).alpha = if (on) 1f else 0.82f
    }

    private fun startClock(clock: TextView) {
        val fmt = java.text.SimpleDateFormat("HH:mm  ·  EEE d MMM", java.util.Locale.UK)
        val tick = object : Runnable {
            override fun run() {
                clock.text = fmt.format(java.util.Date())
                // A view that's left the window never runs what's posted to it, so this stops
                // on its own when the screen goes away.
                clock.postDelayed(this, 20_000L)
            }
        }
        tick.run()
    }

    /** Binds the rail of an already-inflated NEBULA layout; Live TV opens the Live screen. */
    fun attach(activity: Activity, root: View, selected: Item): ViewNebulaRailBinding {
        val rail = ViewNebulaRailBinding.bind(root.findViewById(com.orbital.iptv.R.id.rail))
        bind(
            activity, rail, selected,
            onHome = { goHome(activity) },
            onLive = { activity.startActivity(Intent(activity, NebulaLiveActivity::class.java).front()) }
        )
        return rail
    }

    /** Shows/hides the Emby, Plex and Torbox tiles depending on whether those are signed in. */
    fun refresh(activity: Activity, rail: ViewNebulaRailBinding) {
        rail.navEmby.visibility = if (EmbyPrefsManager.getSession(activity) != null) View.VISIBLE else View.GONE
        rail.navPlex.visibility = if (PlexPrefsManager.getSession(activity) != null) View.VISIBLE else View.GONE
        rail.navTorbox.visibility = if (TorboxPrefsManager.getApiKey(activity) != null) View.VISIBLE else View.GONE
    }
}
