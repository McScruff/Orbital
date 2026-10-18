package com.orbital.iptv.ui.nebula

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.databinding.ActivityNebulaBrowseBinding

/** The views the Movies / Series browsers drive, so the classic and NEBULA layouts share one code path. */
class BrowseViews(
    val root: View,
    val btnBack: TextView,
    val etSearch: EditText,
    val catContainer: LinearLayout,
    val tvCurrentCategory: TextView,
    val progressBar: View,
    val tvError: TextView,
    val rv: RecyclerView,
    val tvCount: TextView,
    val layoutHeader: View?,
    val viewAccent: View?
) {
    companion object {
        fun from(b: ActivityNebulaBrowseBinding) = BrowseViews(
            b.root, b.btnBack, b.etSearch, b.catContainer, b.tvCurrentCategory,
            b.progressBar, b.tvError, b.rvContent, b.tvCount, null, null
        )
    }
}
