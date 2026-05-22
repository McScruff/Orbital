package com.skyretro.iptv.ui.games

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/**
 * Bubble Shooter — shoot coloured bubbles from the bottom, match 3+ to pop them.
 * Rows are hex-packed (odd rows offset right by R). Bubbles bounce off side walls.
 * A new row is pushed from the top every SHOTS_PER_ROW shots.
 */
class BeehiveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLS = 11
        private const val ROWS = 12
        private const val NUM_COLORS = 5
        private const val SHOTS_PER_ROW = 8
        private const val DANGER_ROW = 9   // game over if a bubble lands here

        private val FILL = intArrayOf(
            0xFFFFDD00.toInt(), 0xFFFF6600.toInt(), 0xFFFF2222.toInt(),
            0xFF2299FF.toInt(), 0xFF33CC55.toInt()
        )
        private val DARK = intArrayOf(
            0xFF887700.toInt(), 0xFF884400.toInt(), 0xFF881111.toInt(),
            0xFF115588.toInt(), 0xFF117733.toInt()
        )
        private val LITE = intArrayOf(
            0xFFFFFF99.toInt(), 0xFFFFCC88.toInt(), 0xFFFF9999.toInt(),
            0xFFAADDFF.toInt(), 0xFF99EEBB.toInt()
        )

        // Hex adjacency for row-offset grid.
        // Even rows (r%2==0): not shifted. Odd rows: shifted right by R.
        private val EVEN_N = arrayOf(
            intArrayOf(-1, 0), intArrayOf(+1, 0),
            intArrayOf(-1, -1), intArrayOf(0, -1),
            intArrayOf(-1, +1), intArrayOf(0, +1)
        )
        private val ODD_N = arrayOf(
            intArrayOf(-1, 0), intArrayOf(+1, 0),
            intArrayOf(0, -1), intArrayOf(+1, -1),
            intArrayOf(0, +1), intArrayOf(+1, +1)
        )
    }

    enum class Phase { IDLE, FLYING, CLEARING, OVER }

    // grid[row][col]: -1 = empty, 0..NUM_COLORS-1 = colour
    private val grid = Array(ROWS) { IntArray(COLS) { -1 } }

    private var aimAngle = PI / 2   // radians; PI/2 = straight up
    private var curColor = Random.nextInt(NUM_COLORS)
    private var nxtColor = Random.nextInt(NUM_COLORS)
    private var shotsFired = 0

    private var flyX = 0f; private var flyY = 0f
    private var flyVX = 0f; private var flyVY = 0f
    private var flyColor = 0

    private var phase = Phase.IDLE
    private val flashing = mutableSetOf<Pair<Int, Int>>()
    private var flashAlpha = 255

    var score = 0; private set
    var onScoreChanged: ((Int, Int) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onGameOver: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = Color.WHITE
    }

    // Layout — derived in onSizeChanged
    private var R = 0f
    private var colStep = 0f
    private var rowStep = 0f
    private var leftMargin = 0f
    private var topMargin = 0f
    private var shooterX = 0f
    private var shooterY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        fillInitialRows()
    }

    private fun fillInitialRows(count: Int = 5) {
        for (r in 0 until count) for (c in 0 until COLS) grid[r][c] = Random.nextInt(NUM_COLORS)
        for (r in count until ROWS) for (c in 0 until COLS) grid[r][c] = -1
    }

    // ── Layout ────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val rFromW = w / (COLS * 2f + 1f)
        val rFromH = h / ((ROWS - 1) * sqrt(3f) + 4f)
        R = minOf(rFromW, rFromH)
        colStep = 2f * R
        rowStep = sqrt(3f) * R
        leftMargin = w / 2f - (COLS - 1) * R
        topMargin = R * 0.8f
        shooterX = w / 2f
        shooterY = h - R * 2.2f
        txtPaint.textSize = R * 0.62f
    }

    private fun bx(col: Int, row: Int) = leftMargin + col * colStep + if (row % 2 == 1) R else 0f
    private fun by(row: Int) = topMargin + row * rowStep

    // ── Draw ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (R == 0f) return
        drawGrid(canvas)
        drawDangerLine(canvas)
        if (phase == Phase.FLYING) drawBubble(canvas, flyX, flyY, flyColor, 255)
        drawShooter(canvas)
        if (phase == Phase.IDLE) drawAimLine(canvas)
        if (phase == Phase.OVER) drawGameOver(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val ci = grid[r][c]; if (ci < 0) continue
            val fl = Pair(r, c) in flashing
            drawBubble(canvas, bx(c, r), by(r), ci, if (fl) flashAlpha else 255)
        }
    }

    private fun drawDangerLine(canvas: Canvas) {
        val y = by(DANGER_ROW) + R
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        paint.color = 0x66FF3333
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
    }

    private fun drawShooter(canvas: Canvas) {
        // Base plate
        paint.style = Paint.Style.FILL; paint.color = 0xFF1E3D72.toInt()
        canvas.drawCircle(shooterX, shooterY, R * 1.35f, paint)
        // Arrow from centre in aim direction
        if (phase == Phase.IDLE) {
            val ax = shooterX + cos(aimAngle).toFloat() * R * 1.6f
            val ay = shooterY - sin(aimAngle).toFloat() * R * 1.6f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = 0xCCFFFFFF.toInt()
            canvas.drawLine(shooterX, shooterY, ax, ay, paint)
        }
        // Current bubble on shooter
        if (phase == Phase.IDLE) drawBubble(canvas, shooterX, shooterY, curColor, 255)

        // Next-bubble preview
        val nx = shooterX + R * 2.8f; val ny = shooterY
        paint.style = Paint.Style.FILL; paint.color = 0x44000000
        canvas.drawCircle(nx, ny, R * 0.85f, paint)
        drawBubble(canvas, nx, ny, nxtColor, 180)
        txtPaint.textSize = R * 0.38f; txtPaint.color = 0x99FFFFFF.toInt()
        canvas.drawText("NEXT", nx, ny + R * 1.25f, txtPaint)
        txtPaint.textSize = R * 0.62f; txtPaint.color = Color.WHITE
    }

    private fun drawAimLine(canvas: Canvas) {
        val vx = cos(aimAngle).toFloat()
        val vy = -sin(aimAngle).toFloat()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        paint.color = 0x44FFFFFF
        paint.pathEffect = DashPathEffect(floatArrayOf(R * 0.55f, R * 0.35f), 0f)
        val path = Path(); path.moveTo(shooterX, shooterY)
        var cx = shooterX; var cy = shooterY; var cvx = vx
        val step = R * 0.45f
        val wallL = R; val wallR = width - R
        repeat(260) {
            cx += cvx * step; cy += vy * step
            if (cx < wallL) { cx = 2 * wallL - cx; cvx = -cvx }
            if (cx > wallR) { cx = 2 * wallR - cx; cvx = -cvx }
            if (cy < topMargin) return@repeat
            path.lineTo(cx, cy)
        }
        canvas.drawPath(path, paint)
        paint.pathEffect = null
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float, ci: Int, alpha: Int) {
        val r = R - 1.5f
        // Shadow
        paint.style = Paint.Style.FILL; paint.color = 0x55000000; paint.alpha = 70
        canvas.drawCircle(x + 1.5f, y + 2f, r, paint)
        // Body
        paint.color = FILL[ci]; paint.alpha = alpha
        canvas.drawCircle(x, y, r, paint)
        // Gloss
        paint.color = LITE[ci]; paint.alpha = (alpha * 0.22f).toInt()
        canvas.drawCircle(x - r * 0.22f, y - r * 0.28f, r * 0.38f, paint)
        // Border
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
        paint.color = DARK[ci]; paint.alpha = alpha
        canvas.drawCircle(x, y, r, paint)
        paint.alpha = 255
    }

    private fun drawGameOver(canvas: Canvas) {
        paint.style = Paint.Style.FILL; paint.color = 0xCC000A20.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val mx = width / 2f; val my = height / 2f
        txtPaint.textSize = R * 1.2f; txtPaint.color = 0xFFFFCC00.toInt()
        canvas.drawText("GAME OVER", mx, my - R * 0.5f, txtPaint)
        txtPaint.textSize = R * 0.75f; txtPaint.color = 0xFF00CCFF.toInt()
        canvas.drawText("SCORE: $score", mx, my + R * 0.9f, txtPaint)
        txtPaint.textSize = R * 0.46f; txtPaint.color = 0xFFBBBBBB.toInt()
        canvas.drawText("PRESS NEW GAME TO PLAY AGAIN", mx, my + R * 2.1f, txtPaint)
    }

    // ── Input ─────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (phase == Phase.OVER) return super.onKeyDown(keyCode, event)
        if (phase != Phase.IDLE) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT ->
                { aimAngle = (aimAngle + 0.07).coerceIn(PI * 0.08, PI * 0.92); invalidate(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                { aimAngle = (aimAngle - 0.07).coerceIn(PI * 0.08, PI * 0.92); invalidate(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_UP ->
                { fire(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (phase != Phase.IDLE) return true
        val dx = event.x - shooterX
        val dy = shooterY - event.y
        if (dy > R) {
            aimAngle = atan2(dy.toDouble(), dx.toDouble()).coerceIn(PI * 0.08, PI * 0.92)
            invalidate()
        }
        if (event.action == MotionEvent.ACTION_UP && dy > R) fire()
        return true
    }

    // ── Shooting ─────────────────────────────────────────────────────

    private fun fire() {
        if (phase != Phase.IDLE) return
        phase = Phase.FLYING
        flyX = shooterX; flyY = shooterY; flyColor = curColor
        val speed = R * 0.38f
        flyVX = cos(aimAngle).toFloat() * speed
        flyVY = -sin(aimAngle).toFloat() * speed
        curColor = nxtColor; nxtColor = Random.nextInt(NUM_COLORS)
        shotsFired++
        scheduleFlyStep()
    }

    private fun scheduleFlyStep() {
        handler.postDelayed({
            if (phase == Phase.FLYING && !moveFly()) scheduleFlyStep()
        }, 11)
    }

    // Returns true when the bubble has landed.
    private fun moveFly(): Boolean {
        flyX += flyVX; flyY += flyVY
        val wallL = R; val wallR = width - R
        if (flyX < wallL) { flyX = 2 * wallL - flyX; flyVX = abs(flyVX) }
        if (flyX > wallR) { flyX = 2 * wallR - flyX; flyVX = -abs(flyVX) }

        // Hit ceiling
        if (flyY - R <= topMargin) { landAt(0f, topMargin); return true }

        // Hit existing bubble
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (grid[r][c] < 0) continue
            val dx = flyX - bx(c, r); val dy = flyY - by(r)
            if (dx * dx + dy * dy < (2 * R - 2) * (2 * R - 2)) {
                landAt(flyX, flyY); return true
            }
        }
        invalidate(); return false
    }

    private fun landAt(snapX: Float, snapY: Float) {
        // Push a new ceiling row if enough shots fired
        if (shotsFired >= SHOTS_PER_ROW) {
            shotsFired = 0
            pushNewRow()
            if (phase == Phase.OVER) return
        }

        // Find the nearest empty valid cell
        val lx = if (snapX == 0f) flyX else snapX
        val ly = if (snapY == topMargin) topMargin else flyY
        var bestR2 = -1; var bestC2 = -1; var bestD = Float.MAX_VALUE
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (grid[r][c] >= 0) continue
            if (r > 0 && !hasAdjacentBubble(r, c)) continue
            val dx = lx - bx(c, r); val dy = ly - by(r)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; bestR2 = r; bestC2 = c }
        }
        // Fallback: any empty cell
        if (bestR2 < 0) {
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                if (grid[r][c] >= 0) continue
                val dx = lx - bx(c, r); val dy = ly - by(r)
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; bestR2 = r; bestC2 = c }
            }
        }
        if (bestR2 < 0) { triggerGameOver(); return }

        grid[bestR2][bestC2] = flyColor
        if (bestR2 >= DANGER_ROW) { triggerGameOver(); return }

        phase = Phase.IDLE
        invalidate()
        handler.postDelayed({ checkAndClear(bestR2, bestC2) }, 80)
    }

    private fun hasAdjacentBubble(row: Int, col: Int): Boolean {
        val ns = if (row % 2 == 0) EVEN_N else ODD_N
        return ns.any { (dc, dr) ->
            val nr = row + dr; val nc = col + dc
            nr in 0 until ROWS && nc in 0 until COLS && grid[nr][nc] >= 0
        }
    }

    private fun pushNewRow() {
        // Shift every row down by one, insert new random row at top
        for (r in ROWS - 1 downTo 1) grid[r] = grid[r - 1].copyOf()
        for (c in 0 until COLS) grid[0][c] = Random.nextInt(NUM_COLORS)
        if ((0 until COLS).any { c -> grid[DANGER_ROW][c] >= 0 }) { triggerGameOver(); return }
        invalidate()
    }

    // ── Match / Clear ─────────────────────────────────────────────────

    private fun checkAndClear(row: Int, col: Int) {
        val color = grid[row][col]; if (color < 0) return
        val group = bfsColor(row, col, color)
        if (group.size < 3) { onMessage?.invoke("AIM · L/R TO MOVE · OK TO FIRE"); return }

        phase = Phase.CLEARING
        val pts = group.size * 10
        score += pts
        onScoreChanged?.invoke(score, score / 800 + 1)
        onMessage?.invoke(when {
            group.size >= 12 -> "INCREDIBLE!!  +$pts"
            group.size >= 8  -> "AMAZING!  +$pts"
            group.size >= 5  -> "GREAT SHOT!  +$pts"
            else             -> "+$pts"
        })
        flashAlpha = 255; flashing.addAll(group)
        animateFlash {
            group.forEach { (r, c) -> grid[r][c] = -1 }
            flashing.clear()
            val isolated = findIsolated()
            if (isolated.isNotEmpty()) {
                val bonus = isolated.size * 5; score += bonus
                isolated.forEach { (r, c) -> grid[r][c] = -1 }
                onScoreChanged?.invoke(score, score / 800 + 1)
            }
            phase = Phase.IDLE; invalidate()
            onMessage?.invoke("AIM · L/R TO MOVE · OK TO FIRE")
        }
    }

    private fun animateFlash(done: () -> Unit) {
        var step = 0
        val r = object : Runnable {
            override fun run() {
                flashAlpha = (255 * (1f - step / 9f)).toInt().coerceIn(0, 255)
                invalidate(); step++
                if (step <= 9) handler.postDelayed(this, 40) else done()
            }
        }
        handler.post(r)
    }

    private fun bfsColor(startR: Int, startC: Int, color: Int): Set<Pair<Int, Int>> {
        val found = mutableSetOf(Pair(startR, startC))
        val q = ArrayDeque<Pair<Int, Int>>(); q.add(Pair(startR, startC))
        while (q.isNotEmpty()) {
            val (r, c) = q.removeFirst()
            for ((dc, dr) in if (r % 2 == 0) EVEN_N else ODD_N) {
                val nr = r + dr; val nc = c + dc; val key = Pair(nr, nc)
                if (nr in 0 until ROWS && nc in 0 until COLS && grid[nr][nc] == color && key !in found) {
                    found.add(key); q.add(key)
                }
            }
        }
        return found
    }

    // Bubbles not reachable from the ceiling row should fall (score bonus).
    private fun findIsolated(): Set<Pair<Int, Int>> {
        val connected = mutableSetOf<Pair<Int, Int>>()
        val q = ArrayDeque<Pair<Int, Int>>()
        for (c in 0 until COLS) if (grid[0][c] >= 0) {
            val k = Pair(0, c); if (k !in connected) { connected.add(k); q.add(k) }
        }
        while (q.isNotEmpty()) {
            val (r, c) = q.removeFirst()
            for ((dc, dr) in if (r % 2 == 0) EVEN_N else ODD_N) {
                val nr = r + dr; val nc = c + dc; val key = Pair(nr, nc)
                if (nr in 0 until ROWS && nc in 0 until COLS && grid[nr][nc] >= 0 && key !in connected) {
                    connected.add(key); q.add(key)
                }
            }
        }
        val all = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until ROWS) for (c in 0 until COLS) if (grid[r][c] >= 0) all.add(Pair(r, c))
        return all - connected
    }

    private fun triggerGameOver() {
        phase = Phase.OVER; invalidate(); onGameOver?.invoke()
    }

    // ── Public API ────────────────────────────────────────────────────

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        phase = Phase.IDLE; score = 0; shotsFired = 0
        aimAngle = PI / 2; flashing.clear()
        curColor = Random.nextInt(NUM_COLORS); nxtColor = Random.nextInt(NUM_COLORS)
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = -1
        fillInitialRows()
        onScoreChanged?.invoke(0, 1)
        onMessage?.invoke("AIM WITH LEFT / RIGHT · FIRE WITH OK")
        invalidate(); requestFocus()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
