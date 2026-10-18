package com.orbital.iptv.ui.nebula

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Vertical list layout for D-pad scrolling. Holding Down on a remote moves focus faster than
 * RecyclerView lays out new rows, so the next row often doesn't exist yet; Android's own focus
 * search then picks the nearest view anywhere on screen (a side list, the menu) and the scroll
 * stops. This keeps focus in the list: if the next row isn't laid out yet it scrolls to it and
 * focuses it once it is. At the bottom of the list focus simply stays put. Up from the first
 * row is left alone, so it can still leave the list for whatever sits above it.
 */
class DpadLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    private var recycler: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recycler = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        this.recycler = null
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction != View.FOCUS_DOWN && direction != View.FOCUS_UP) return null
        val row = findContainingItemView(focused) ?: return null
        val target = getPosition(row) + if (direction == View.FOCUS_DOWN) 1 else -1
        if (target < 0) return null
        if (target >= itemCount) return focused

        findViewByPosition(target)?.let { if (it.isFocusable) return it }

        // Not laid out yet (or a non-focusable header): scroll, then focus the first focusable
        // row at or beyond the target once the list has caught up.
        val rv = recycler ?: return focused
        scrollToPosition(target)
        rv.post { focusFrom(rv, target, if (direction == View.FOCUS_DOWN) 1 else -1, 4) }
        return focused
    }

    /**
     * Focuses the first focusable row from [start] in direction [step]. A header at the list's
     * edge can leave the next row off screen: then it scrolls to that row and tries again (up to
     * [tries] times), rather than giving up and leaving focus where it was.
     */
    private fun focusFrom(rv: RecyclerView, start: Int, step: Int, tries: Int) {
        var pos = start
        while (pos in 0 until itemCount) {
            val v = findViewByPosition(pos)
            if (v == null) {
                if (tries <= 0) return
                scrollToPosition(pos)
                val from = pos
                rv.post { focusFrom(rv, from, step, tries - 1) }
                return
            }
            if (v.isFocusable) { v.requestFocus(); return }
            pos += step
        }
    }
}
