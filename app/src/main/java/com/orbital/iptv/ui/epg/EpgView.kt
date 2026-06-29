package com.orbital.iptv.ui.epg

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.orbital.iptv.data.model.EpgListing
import com.orbital.iptv.data.model.getDecodedTitle
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

data class EpgRow(
    val streamId: Int,
    val channelName: String,
    var listings: List<EpgListing> = emptyList()
)

class EpgView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChannelSelected: ((streamId: Int) -> Unit)? = null
    var onChannelLongPress: ((streamId: Int) -> Unit)? = null
    var onProgrammeSelected: ((streamId: Int, channelName: String, listing: EpgListing) -> Unit)? = null
    var onRequestFocusLeft: (() -> Unit)? = null

    private val rows = mutableListOf<EpgRow>()

    private val dp              = resources.displayMetrics.density
    private val channelColWidth = (150 * dp).toInt()
    private val rowHeight       = (50 * dp).toInt()
    private val headerHeight    = (44 * dp).toInt()
    private val textPad         = 8 * dp
    private val pxPerMin        = 4f * dp   // fixed: 4dp/min → ~4-5 h visible on a 1080-wide screen

    private val totalWindowMinutes = 7 * 24 * 60   // 7 days from today midnight

    // -1 = not yet initialised; onDraw returns early until onSizeChanged fires
    private var scrollX = -1
    private var scrollY = 0
    private var focusedRowIndex = 0
    // -1 = channel-name column selected, 0+ = index into row.listings
    private var focusedCellIndex = -1
    private var okLongPressFired = false

    private val scroller        = OverScroller(context)
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val dayFmt          = SimpleDateFormat("EEE dd MMM", Locale.UK)

    // ── Paints ────────────────────────────────────────────────────────────────

    private fun solidPaint(rgb: Int) = Paint().apply {
        color = Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
    private fun textPaint(col: Int, sp: Float, style: Int) = Paint().apply {
        color = col; textSize = sp * dp; isAntiAlias = true
        typeface = Typeface.create("sans-serif-condensed", style)
    }

    private val bgPaint           = solidPaint(0x0A1628)
    private val headerBgPaint     = solidPaint(0x001133)
    private val chanEvenPaint     = solidPaint(0x1A3A6A)
    private val chanOddPaint      = solidPaint(0x122850)
    private val progPaint         = solidPaint(0x243E6A)
    private val progAltPaint      = solidPaint(0x1A3258)
    private val progNowPaint      = solidPaint(0x2A5C2A)
    private val noDataPaint       = solidPaint(0x080F20)
    private val dividerPaint      = Paint().apply { color = Color.parseColor("#00CCFF"); strokeWidth = dp; alpha = 60 }
    private val mainDivPaint      = Paint().apply { color = Color.parseColor("#00CCFF"); strokeWidth = 2 * dp }
    private val dayDivPaint       = Paint().apply { color = Color.parseColor("#FFD700"); strokeWidth = 1.5f * dp; alpha = 200 }
    private val nowLinePaint      = Paint().apply { color = Color.parseColor("#FF3333"); strokeWidth = 2 * dp }
    private val chanTextPaint     = textPaint(Color.WHITE, 11f, Typeface.BOLD)
    private val progTextPaint     = textPaint(Color.WHITE, 10f, Typeface.NORMAL)
    private val timeTextPaint     = textPaint(Color.parseColor("#00CCFF"), 10f, Typeface.BOLD)
    private val dayLabelPaint     = textPaint(Color.parseColor("#FFD700"), 9f, Typeface.BOLD)
    private val nowLabelPaint     = textPaint(Color.parseColor("#FF5555"), 9f, Typeface.BOLD)
    private val focusRowFillPaint = Paint().apply { color = 0x443A9EFF.toInt() }
    private val focusRowBordPaint = Paint().apply {
        color = 0xFF3A9EFF.toInt(); strokeWidth = 2f * dp; style = Paint.Style.STROKE
    }
    private val focusCellFillPaint = Paint().apply { color = 0x66FFFFFF.toInt() }
    private val focusCellBordPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 3f * dp; style = Paint.Style.STROKE
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun todayMidnightSec(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis / 1000

    private fun maxScrollX() = max(0, (totalWindowMinutes * pxPerMin).toInt() - (width - channelColWidth))
    private fun maxScrollY() = max(0, rows.size * rowHeight - (height - headerHeight))

    private fun tsToX(tsSec: Long, todayMidnight: Long): Float =
        channelColWidth + (tsSec - todayMidnight) / 60f * pxPerMin - scrollX

    // ── Public API ─────────────────────────────────────────────────────────────

    fun setRows(newRows: List<EpgRow>) {
        rows.clear(); rows.addAll(newRows)
        focusedRowIndex = 0
        focusedCellIndex = -1
        scrollY = 0   // reset vertical scroll on category change — stale scrollY from a larger category culls all rows
        invalidate()
    }

    fun updateRow(streamId: Int, listings: List<EpgListing>) {
        rows.find { it.streamId == streamId }?.let { it.listings = listings; invalidate() }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (scrollX < 0) scrollToNow()
    }

    fun scrollToNow() {
        if (width == 0) return
        val nowSec   = System.currentTimeMillis() / 1000
        val offsetPx = ((nowSec - todayMidnightSec()) / 60f * pxPerMin).toInt()
        val margin   = (30 * pxPerMin).toInt()   // 30 min of left margin before "now"
        scrollX = (offsetPx - margin).coerceIn(0, maxScrollX())
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (scrollX < 0) return   // not yet sized

        val nowSec        = System.currentTimeMillis() / 1000
        val todayMidnight = todayMidnightSec()
        val w    = width.toFloat()
        val h    = height.toFloat()
        val chanW = channelColWidth.toFloat()
        val headH = headerHeight.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // ── Channel rows ─────────────────────────────────────────────────────
        for (i in rows.indices) {
            val rowTop = headH + i * rowHeight - scrollY
            val rowBot = rowTop + rowHeight
            if (rowBot < headH || rowTop > h) continue
            val row = rows[i]

            canvas.drawRect(0f, rowTop, chanW, rowBot, if (i % 2 == 0) chanEvenPaint else chanOddPaint)
            canvas.drawRect(chanW, rowTop, w, rowBot, noDataPaint)

            canvas.save()
            canvas.clipRect(chanW, rowTop, w, rowBot)
            var altBar = false
            for (listing in row.listings) {
                val s  = listing.startTimestamp?.toLongOrNull() ?: continue
                val e  = listing.stopTimestamp?.toLongOrNull()  ?: continue
                val bL = tsToX(s, todayMidnight)
                val bR = tsToX(e, todayMidnight)
                if (bR < chanW || bL > w) continue   // entirely off-screen horizontally
                val bPaint = if (s <= nowSec && e > nowSec) progNowPaint
                             else if (altBar) progAltPaint else progPaint
                altBar = !altBar
                canvas.drawRect(bL + 1, rowTop + 2, bR - 1, rowBot - 2, bPaint)
                val textX = max(bL + textPad, chanW + textPad)
                if (bR - textX > 4 * dp) {
                    canvas.save()
                    canvas.clipRect(textX, rowTop, bR - 2, rowBot)
                    canvas.drawText(listing.getDecodedTitle(), textX, rowTop + rowHeight * 0.62f, progTextPaint)
                    canvas.restore()
                }
            }
            canvas.restore()

            if (isFocused && i == focusedRowIndex) {
                canvas.drawRect(0f, rowTop, w, rowBot, focusRowFillPaint)
                canvas.drawRect(1f, rowTop + 1f, w - 1f, rowBot - 1f, focusRowBordPaint)

                // Highlight focused programme cell
                if (focusedCellIndex >= 0 && focusedCellIndex < row.listings.size) {
                    val fl = row.listings[focusedCellIndex]
                    val fs = fl.startTimestamp?.toLongOrNull()
                    val fe = fl.stopTimestamp?.toLongOrNull()
                    if (fs != null && fe != null) {
                        val cL = tsToX(fs, todayMidnight).coerceAtLeast(chanW)
                        val cR = tsToX(fe, todayMidnight)
                        if (cR > chanW && cL < w) {
                            canvas.drawRect(cL + 1, rowTop + 2, cR - 1, rowBot - 2, focusCellFillPaint)
                            canvas.drawRect(cL + 2, rowTop + 3, cR - 2, rowBot - 3, focusCellBordPaint)
                        }
                    }
                }
            }

            canvas.save()
            canvas.clipRect(0f, rowTop, chanW, rowBot)
            canvas.drawText(row.channelName, textPad, rowTop + rowHeight * 0.62f, chanTextPaint)
            canvas.restore()

            canvas.drawLine(0f, rowBot, w, rowBot, dividerPaint)
        }

        // ── Time header (fixed, drawn on top of rows) ─────────────────────────
        canvas.drawRect(0f, 0f, w, headH, headerBgPaint)

        // Only iterate the visible portion (start slightly before visible left edge)
        val windowEndSec = todayMidnight + totalWindowMinutes * 60L
        val visStartSec  = todayMidnight + (scrollX / pxPerMin * 60).toLong()
        val visEndSec    = visStartSec + ((w - chanW) / pxPerMin * 60).toLong() + 3600L
        val cal = Calendar.getInstance().apply {
            timeInMillis = max(todayMidnight, visStartSec - 3600) * 1000
            val min = get(Calendar.MINUTE)
            set(Calendar.MINUTE, if (min < 30) 0 else 30)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        while (cal.timeInMillis / 1000 < min(visEndSec, windowEndSec)) {
            val tsSec = cal.timeInMillis / 1000
            if (tsSec >= todayMidnight) {
                val markX = tsToX(tsSec, todayMidnight)
                if (markX >= chanW && markX <= w) {
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val min  = cal.get(Calendar.MINUTE)
                    if (hour == 0 && min == 0) {
                        // Day boundary: gold line + day name on top row, "00:00" on bottom row
                        canvas.drawLine(markX, 0f, markX, h, dayDivPaint)
                        val dayLabel = when (tsSec) {
                            todayMidnight         -> "TODAY"
                            todayMidnight + 86400 -> "TOMORROW"
                            else                  -> dayFmt.format(Date(tsSec * 1000))
                        }
                        canvas.drawText(dayLabel, markX + textPad / 2, headH * 0.42f, dayLabelPaint)
                        canvas.drawText("00:00",  markX + textPad / 2, headH * 0.82f, timeTextPaint)
                    } else {
                        canvas.drawLine(markX, headH * 0.55f, markX, h, dividerPaint)
                        val label = if (min == 0) "%02d:00".format(hour)
                                    else "%02d:%02d".format(hour, min)
                        canvas.drawText(label, markX + textPad / 2, headH * 0.82f, timeTextPaint)
                    }
                }
            }
            cal.add(Calendar.MINUTE, 30)
        }

        // Channel column header + dividers (on top so they cover any overflow)
        canvas.drawRect(0f, 0f, chanW, headH, headerBgPaint)
        canvas.drawText("CHANNEL", textPad, headH * 0.72f, timeTextPaint)
        canvas.drawLine(chanW, 0f, chanW, h, mainDivPaint)
        canvas.drawLine(0f, headH, w, headH, mainDivPaint)

        // NOW line
        val nx = tsToX(nowSec, todayMidnight)
        if (nx >= chanW && nx <= w) {
            canvas.drawLine(nx, 0f, nx, h, nowLinePaint)
            val label = "NOW"
            canvas.drawText(label, nx - nowLabelPaint.measureText(label) / 2, headH * 0.82f, nowLabelPaint)
        }

        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.coerceIn(0, maxScrollX())
            scrollY = scroller.currY.coerceIn(0, maxScrollY())
            invalidate()
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent) =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    // ── D-pad ─────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusedRowIndex < rows.size - 1) {
                    focusedRowIndex++; focusedCellIndex = -1
                    scrollToShowFocusedRow(); invalidate()
                }
                return true   // always consume
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusedRowIndex > 0) {
                    focusedRowIndex--; focusedCellIndex = -1
                    scrollToShowFocusedRow(); invalidate(); return true
                }
                return false  // at top row: let focus escape
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val row = rows.getOrNull(focusedRowIndex)
                if (row != null && row.listings.isNotEmpty()) {
                    val nextIdx = if (focusedCellIndex < 0) {
                        // Jump to the current/upcoming programme
                        val nowSec = System.currentTimeMillis() / 1000
                        val idx = row.listings.indexOfFirst { l ->
                            val e = l.stopTimestamp?.toLongOrNull() ?: return@indexOfFirst false
                            e > nowSec
                        }
                        if (idx >= 0) idx else 0
                    } else {
                        focusedCellIndex + 1
                    }
                    if (nextIdx < row.listings.size) {
                        focusedCellIndex = nextIdx
                        scrollToShowFocusedCell(); scrollToShowFocusedRow(); invalidate()
                    } else if (scrollX < maxScrollX()) {
                        scrollX = (scrollX + (60 * pxPerMin).toInt()).coerceIn(0, maxScrollX())
                        invalidate()
                    }
                } else {
                    if (scrollX < maxScrollX()) {
                        scrollX = (scrollX + (60 * pxPerMin).toInt()).coerceIn(0, maxScrollX())
                        invalidate()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when {
                    focusedCellIndex > 0 -> {
                        focusedCellIndex--
                        scrollToShowFocusedCell(); scrollToShowFocusedRow(); invalidate()
                        return true
                    }
                    focusedCellIndex == 0 -> {
                        focusedCellIndex = -1; invalidate()
                        return true
                    }
                    else -> {
                        // Already at channel column — hand focus back to categories
                        onRequestFocusLeft?.invoke()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val row = rows.getOrNull(focusedRowIndex) ?: return true
                if (focusedCellIndex >= 0 && focusedCellIndex < row.listings.size) {
                    onProgrammeSelected?.invoke(row.streamId, row.channelName, row.listings[focusedCellIndex])
                } else {
                    // Channel column: track for long press (favourites); fire play on key-up
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                onRequestFocusLeft?.invoke()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && focusedCellIndex < 0) {
            rows.getOrNull(focusedRowIndex)?.let { onChannelLongPress?.invoke(it.streamId) }
            okLongPressFired = true
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && focusedCellIndex < 0) {
            if (!okLongPressFired) {
                rows.getOrNull(focusedRowIndex)?.let { onChannelSelected?.invoke(it.streamId) }
            }
            okLongPressFired = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) scrollToShowFocusedRow()
        invalidate()
    }

    private fun scrollToShowFocusedRow() {
        val rowTop = focusedRowIndex * rowHeight
        val rowBot = rowTop + rowHeight
        scrollY = when {
            rowTop < scrollY                            -> rowTop
            rowBot > scrollY + (height - headerHeight) -> rowBot - (height - headerHeight)
            else                                       -> scrollY
        }.coerceIn(0, maxScrollY())
    }

    private fun scrollToShowFocusedCell() {
        val listing = rows.getOrNull(focusedRowIndex)?.listings?.getOrNull(focusedCellIndex) ?: return
        val midnight = todayMidnightSec()
        val startSec = listing.startTimestamp?.toLongOrNull() ?: return
        val endSec   = listing.stopTimestamp?.toLongOrNull()  ?: return
        val availW   = (width - channelColWidth).coerceAtLeast(1)
        val startPx  = ((startSec - midnight) / 60f * pxPerMin).toInt()
        val endPx    = ((endSec   - midnight) / 60f * pxPerMin).toInt()
        scrollX = when {
            startPx < scrollX             -> startPx.coerceAtLeast(0)
            endPx > scrollX + availW      -> (startPx - availW / 4).coerceIn(0, maxScrollX())
            else                          -> scrollX
        }
    }

    // ── Tap handling ──────────────────────────────────────────────────────────

    private fun handleTap(x: Float, y: Float) {
        if (y < headerHeight) return
        val idx = ((y - headerHeight + scrollY) / rowHeight).toInt()
        if (idx !in rows.indices) return
        val row = rows[idx]
        val todayMidnight = todayMidnightSec()

        if (x < channelColWidth || onProgrammeSelected == null) {
            onChannelSelected?.invoke(row.streamId)
        } else {
            val tapMin = (x - channelColWidth + scrollX) / pxPerMin
            val tapSec = todayMidnight + (tapMin * 60).toLong()
            val listing = row.listings.firstOrNull { l ->
                val start = l.startTimestamp?.toLongOrNull() ?: return@firstOrNull false
                val end   = l.stopTimestamp?.toLongOrNull()  ?: return@firstOrNull false
                tapSec in start..end
            }
            if (listing != null) onProgrammeSelected?.invoke(row.streamId, row.channelName, listing)
            else onChannelSelected?.invoke(row.streamId)
        }
    }

    // ── Gesture listener ──────────────────────────────────────────────────────

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) {
            if (e.y < headerHeight) return
            val idx = ((e.y - headerHeight + scrollY) / rowHeight).toInt()
            if (idx in rows.indices && e.x < channelColWidth)
                onChannelLongPress?.invoke(rows[idx].streamId)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollX = (scrollX + dx.toInt()).coerceIn(0, maxScrollX())
            scrollY = (scrollY + dy.toInt()).coerceIn(0, maxScrollY())
            invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(scrollX, scrollY, (-vx).toInt(), (-vy).toInt(), 0, maxScrollX(), 0, maxScrollY())
            invalidate(); return true
        }
    }
}
