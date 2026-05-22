package com.skyretro.iptv.ui.epg

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.skyretro.iptv.data.model.EpgListing
import com.skyretro.iptv.data.model.getDecodedTitle
import java.util.Calendar
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

    private val rows = mutableListOf<EpgRow>()

    private val dp = resources.displayMetrics.density
    private val channelColWidth = (150 * dp).toInt()
    private val rowHeight = (50 * dp).toInt()
    private val headerHeight = (32 * dp).toInt()
    private val textPad = 8 * dp

    // Window: 30 min before now → 90 min after now (120 min total)
    private val windowMinutes = 120
    private val preNowMinutes = 30

    private var scrollY = 0
    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, GestureListener())

    private val bgPaint         = paint(0x0A1628)
    private val headerBgPaint   = paint(0x001133)
    private val chanEvenPaint   = paint(0x1A3A6A)
    private val chanOddPaint    = paint(0x122850)
    private val progPaint       = paint(0x243E6A)
    private val progAltPaint    = paint(0x1A3258)
    private val progNowPaint    = paint(0x2A5C2A)
    private val noDataPaint     = paint(0x080F20)
    private val dividerPaint    = Paint().apply {
        color = Color.parseColor("#00CCFF"); strokeWidth = dp; alpha = 70
    }
    private val mainDivPaint    = Paint().apply {
        color = Color.parseColor("#00CCFF"); strokeWidth = 2 * dp
    }
    private val nowLinePaint    = Paint().apply {
        color = Color.parseColor("#FF3333"); strokeWidth = 2 * dp
    }
    private val chanTextPaint   = textPaint(Color.WHITE, 11f, Typeface.BOLD)
    private val progTextPaint   = textPaint(Color.WHITE, 10f, Typeface.NORMAL)
    private val timeTextPaint   = textPaint(Color.parseColor("#00CCFF"), 10f, Typeface.BOLD)
    private val nowLabelPaint   = textPaint(Color.parseColor("#FF4444"), 9f, Typeface.BOLD)

    private fun paint(color: Int) = Paint().apply { this.color = Color.rgb(
        (color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF
    ) }

    private fun textPaint(color: Int, spSize: Float, style: Int) = Paint().apply {
        this.color = color
        textSize = spSize * dp
        isAntiAlias = true
        typeface = Typeface.create("sans-serif-condensed", style)
    }

    fun setRows(newRows: List<EpgRow>) {
        rows.clear(); rows.addAll(newRows); invalidate()
    }

    fun updateRow(streamId: Int, listings: List<EpgListing>) {
        rows.find { it.streamId == streamId }?.let { it.listings = listings; invalidate() }
    }

    private fun maxScrollY() = max(0, rows.size * rowHeight - (height - headerHeight))

    private fun pxPerMin() = (width - channelColWidth).toFloat() / windowMinutes

    private fun nowX() = channelColWidth + preNowMinutes * pxPerMin()

    private fun tsToX(tsSec: Long): Float {
        val windowStartSec = System.currentTimeMillis() / 1000 - preNowMinutes * 60
        return channelColWidth + ((tsSec - windowStartSec) / 60f) * pxPerMin()
    }

    override fun onDraw(canvas: Canvas) {
        val nowSec = System.currentTimeMillis() / 1000
        val winStartSec = nowSec - preNowMinutes * 60
        val winEndSec = winStartSec + windowMinutes * 60
        val w = width.toFloat()
        val h = height.toFloat()
        val chanW = channelColWidth.toFloat()
        val headH = headerHeight.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Channel rows
        for (i in rows.indices) {
            val rowTop = headH + i * rowHeight - scrollY
            val rowBot = rowTop + rowHeight
            if (rowBot < headH || rowTop > h) continue
            val row = rows[i]

            canvas.drawRect(0f, rowTop, chanW, rowBot, if (i % 2 == 0) chanEvenPaint else chanOddPaint)
            canvas.drawRect(chanW, rowTop, w, rowBot, noDataPaint)

            // Programme bars
            canvas.save()
            canvas.clipRect(chanW, rowTop, w, rowBot)
            var altBar = false
            for (listing in row.listings) {
                val startSec = listing.startTimestamp?.toLongOrNull() ?: continue
                val endSec   = listing.stopTimestamp?.toLongOrNull()  ?: continue
                val clampStart = max(startSec, winStartSec)
                val clampEnd   = min(endSec, winEndSec)
                if (clampStart >= clampEnd) continue

                val bLeft  = tsToX(clampStart)
                val bRight = tsToX(clampEnd)
                val isCurrent = startSec <= nowSec && endSec > nowSec
                val bPaint = if (isCurrent) progNowPaint else if (altBar) progAltPaint else progPaint
                altBar = !altBar

                canvas.drawRect(bLeft + 1, rowTop + 2, bRight - 1, rowBot - 2, bPaint)

                canvas.save()
                canvas.clipRect(bLeft + 2, rowTop, bRight - 2, rowBot)
                canvas.drawText(listing.getDecodedTitle(), bLeft + textPad, rowTop + rowHeight * 0.62f, progTextPaint)
                canvas.restore()
            }
            canvas.restore()

            // Channel name (drawn over prog area, clipped to its column)
            canvas.save()
            canvas.clipRect(0f, rowTop, chanW, rowBot)
            canvas.drawText(row.channelName, textPad, rowTop + rowHeight * 0.62f, chanTextPaint)
            canvas.restore()

            canvas.drawLine(0f, rowBot, w, rowBot, dividerPaint)
        }

        // Time header (fixed, drawn last so it's always on top)
        canvas.drawRect(0f, 0f, w, headH, headerBgPaint)

        // Time marks every 30 minutes
        val cal = Calendar.getInstance().apply {
            timeInMillis = winStartSec * 1000
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val min = get(Calendar.MINUTE)
            set(Calendar.MINUTE, if (min < 30) 0 else 30)
        }
        while (cal.timeInMillis / 1000 < winEndSec) {
            val markX = tsToX(cal.timeInMillis / 1000)
            if (markX > chanW) {
                canvas.drawLine(markX, headH, markX, h, dividerPaint)
                val label = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                canvas.drawText(label, markX + textPad / 2, headH * 0.72f, timeTextPaint)
            }
            cal.add(Calendar.MINUTE, 30)
        }

        canvas.drawRect(0f, 0f, chanW, headH, headerBgPaint)
        canvas.drawText("CHANNEL", textPad, headH * 0.72f, timeTextPaint)

        canvas.drawLine(chanW, 0f, chanW, h, mainDivPaint)
        canvas.drawLine(0f, headH, w, headH, mainDivPaint)

        // Now line + label
        val nx = nowX()
        canvas.drawLine(nx, 0f, nx, h, nowLinePaint)
        val nowLabel = "NOW"
        canvas.drawText(nowLabel, nx - nowLabelPaint.measureText(nowLabel) / 2, headH * 0.68f, nowLabelPaint)

        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.coerceIn(0, maxScrollY())
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent) =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    private fun handleTap(x: Float, y: Float) {
        if (y < headerHeight) return
        val idx = ((y - headerHeight + scrollY) / rowHeight).toInt()
        if (idx in rows.indices) onChannelSelected?.invoke(rows[idx].streamId)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollY = (scrollY + dy.toInt()).coerceIn(0, maxScrollY())
            invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollY, 0, (-vy).toInt(), 0, 0, 0, maxScrollY())
            invalidate(); return true
        }
    }
}
