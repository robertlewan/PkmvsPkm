package com.pkmvspkm.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.min

/** Draws the menu and battle screens on a canvas and handles all touch input. */
class GameView(context: Context) : View(context) {

    private enum class Screen { MENU, PLAYING, RESULT }

    private val appContext = context.applicationContext
    private var screen = Screen.MENU
    private var battle: Battle? = null
    private var selected: DefenderType? = null
    private var resultText = ""
    private var unlocked = SaveData.unlockedLevel(appContext)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val levelButtons = LEVELS.map { RectF() }

    // Layout, computed in onSizeChanged
    private var gridLeft = 0f
    private var gridTop = 0f
    private var cell = 0f
    private var cardTop = 0f

    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 0f
            else min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.1f)
            lastFrameNanos = frameTimeNanos

            val b = battle
            if (screen == Screen.PLAYING && b != null) {
                b.update(dt)
                if (b.outcome != Outcome.RUNNING) finishBattle(b)
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()

        gridLeft = fw * 0.1f // margin on the left holds the lane traps
        cell = (fw - gridLeft - fw * 0.02f) / COLS
        val hudH = fh * 0.12f
        val cardH = fh * 0.13f
        cardTop = fh - cardH
        gridTop = hudH + (cardTop - hudH - cell * ROWS) / 2f

        val bw = fw * 0.7f
        val bh = fh * 0.08f
        val gap = fh * 0.02f
        val startY = fh * 0.35f
        levelButtons.forEachIndexed { i, r ->
            val top = startY + i * (bh + gap)
            r.set((fw - bw) / 2f, top, (fw + bw) / 2f, top + bh)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true

        when (screen) {
            Screen.MENU -> LEVELS.forEachIndexed { i, lvl ->
                if (lvl.id <= unlocked && levelButtons[i].contains(event.x, event.y)) startLevel(lvl)
            }
            Screen.PLAYING -> handlePlayTap(event.x, event.y)
            Screen.RESULT -> screen = Screen.MENU
        }
        performClick()
        return true
    }

    private fun startLevel(level: LevelDef) {
        battle = Battle(level)
        selected = null
        screen = Screen.PLAYING
    }

    private fun finishBattle(b: Battle) {
        if (b.outcome == Outcome.WON) {
            SaveData.unlockNext(appContext, b.level.id)
            unlocked = SaveData.unlockedLevel(appContext)
            resultText = "${b.level.name} cleared!"
        } else {
            resultText = "The dark Pokemon got through!"
        }
        screen = Screen.RESULT
    }

    private fun handlePlayTap(x: Float, y: Float) {
        val b = battle ?: return

        // Card bar at the bottom: select or deselect a defender
        if (y >= cardTop) {
            val types = DefenderType.entries
            val idx = (x / (width.toFloat() / types.size)).toInt()
            if (idx in types.indices) {
                val t = types[idx]
                selected = if (selected == t) null else t
            }
            return
        }

        // Grid-unit coordinates of the tap
        val gridX = (x - gridLeft) / cell
        val gridY = (y - gridTop) / cell

        // Suns can be collected anywhere, including above the grid
        b.sunAt(gridX, gridY)?.let {
            b.collect(it)
            return
        }

        if (gridX < 0f || gridY < 0f) return
        val col = floor(gridX).toInt()
        val row = floor(gridY).toInt()
        if (col !in 0 until COLS || row !in 0 until ROWS) return

        val type = selected ?: return
        if (b.place(type, row, col)) selected = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (screen) {
            Screen.MENU -> drawMenu(canvas)
            Screen.PLAYING -> battle?.let { drawBattle(canvas, it) }
            Screen.RESULT -> {
                battle?.let { drawBattle(canvas, it) }
                drawResult(canvas)
            }
        }
    }

    private fun drawMenu(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        canvas.drawColor(Color.rgb(30, 40, 70))

        drawText(canvas, "PkmvsPkm", fw / 2f, fh * 0.22f, fw * 0.12f, Color.WHITE, Paint.Align.CENTER)
        drawText(canvas, "Stop the dark Pokemon!", fw / 2f, fh * 0.28f, fw * 0.045f, Color.LTGRAY, Paint.Align.CENTER)

        LEVELS.forEachIndexed { i, lvl ->
            val r = levelButtons[i]
            val open = lvl.id <= unlocked
            paint.style = Paint.Style.FILL
            paint.color = if (open) Color.rgb(76, 175, 80) else Color.rgb(90, 90, 90)
            canvas.drawRoundRect(r, 28f, 28f, paint)
            val label = if (open) "${lvl.id}. ${lvl.name}" else "Locked"
            drawText(canvas, label, r.centerX(), r.centerY() + fw * 0.016f, fw * 0.045f, Color.WHITE, Paint.Align.CENTER)
        }
    }

    private fun drawBattle(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        canvas.drawColor(Color.rgb(135, 206, 235))

        // Lawn tiles
        paint.style = Paint.Style.FILL
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            paint.color = if ((r + c) % 2 == 0) Color.rgb(124, 189, 90) else Color.rgb(108, 170, 78)
            canvas.drawRect(gx(c.toFloat()), gy(r.toFloat()), gx(c + 1f), gy(r + 1f), paint)
        }

        // Lane traps in the left margin, plus the flash when one fires
        for (r in 0 until ROWS) {
            val cx = gridLeft * 0.5f
            val cy = gy(r + 0.5f)
            val rad = cell * 0.28f
            if (b.trapReady[r]) {
                drawCircle(canvas, cx, cy, rad, Color.rgb(220, 40, 40))
                drawCircle(canvas, cx, cy, rad * 0.4f, Color.WHITE)
            }
            if (b.trapFlash[r] > 0f) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((255 * b.trapFlash[r] / 0.8f).toInt(), 255, 255, 255)
                canvas.drawRect(gridLeft, gy(r.toFloat()), gx(COLS.toFloat()), gy(r + 1f), paint)
            }
        }

        for (d in b.defenders) {
            drawCreature(
                canvas, gx(d.col + 0.5f), gy(d.row + 0.5f), cell * 0.36f,
                d.type.spriteId, d.type.color, d.type.label, Color.BLACK, d.hp / d.type.hp
            )
        }
        for (e in b.enemies) {
            val radius = if (e.type.boss) cell * 0.48f else cell * 0.36f
            drawCreature(
                canvas, gx(e.x + 0.5f), gy(e.row + 0.5f), radius,
                e.type.spriteId, e.type.color, e.type.label, Color.WHITE, e.hp / e.type.hp
            )
        }
        for (p in b.projectiles) {
            drawCircle(canvas, gx(p.x), gy(p.row + 0.5f), cell * 0.08f, Color.rgb(255, 235, 59))
        }
        for (s in b.suns) {
            drawCircle(canvas, gx(s.x), gy(s.y), cell * 0.24f, Color.rgb(255, 213, 79))
            drawCircle(canvas, gx(s.x), gy(s.y), cell * 0.12f, Color.rgb(255, 245, 180))
        }

        // Card bar
        val types = DefenderType.entries
        val slot = fw / types.size
        types.forEachIndexed { i, t ->
            rect.set(i * slot + 6f, cardTop + 12f, (i + 1) * slot - 6f, fh - 12f)
            paint.style = Paint.Style.FILL
            paint.color = if (b.sun >= t.cost) Color.rgb(250, 240, 200) else Color.rgb(150, 140, 120)
            canvas.drawRoundRect(rect, 16f, 16f, paint)
            if (selected == t) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 6f
                paint.color = Color.rgb(255, 193, 7)
                canvas.drawRoundRect(rect, 16f, 16f, paint)
            }
            drawText(canvas, t.label, rect.centerX(), rect.centerY() - cell * 0.02f, cell * 0.14f, Color.BLACK, Paint.Align.CENTER)
            drawText(canvas, "${t.cost}", rect.centerX(), rect.centerY() + cell * 0.25f, cell * 0.16f, Color.rgb(160, 100, 0), Paint.Align.CENTER)
        }

        // HUD
        drawText(canvas, "Sun ${b.sun}", gridLeft, fh * 0.07f, cell * 0.3f, Color.WHITE, Paint.Align.LEFT)
        drawText(canvas, "Pick a card, then tap a tile", gridLeft, fh * 0.11f, cell * 0.12f, Color.LTGRAY, Paint.Align.LEFT)
        drawText(
            canvas, "${b.level.name}  ${b.spawnedCount}/${b.level.count}",
            fw - fw * 0.03f, fh * 0.07f, cell * 0.22f, Color.WHITE, Paint.Align.RIGHT
        )
    }

    private fun drawResult(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(170, 0, 0, 0)
        canvas.drawRect(0f, 0f, fw, fh, paint)
        drawText(canvas, resultText, fw / 2f, fh / 2f, fw * 0.06f, Color.WHITE, Paint.Align.CENTER)
        drawText(canvas, "Tap to continue", fw / 2f, fh / 2f + fw * 0.1f, fw * 0.04f, Color.LTGRAY, Paint.Align.CENTER)
    }

    private fun drawCreature(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        spriteId: Int,
        color: Int,
        label: String,
        textColor: Int,
        hpFrac: Float,
    ) {
        // Official sprite once downloaded; colored circle with the name until then
        val sprite = SpriteCache.get(spriteId) { invalidate() }
        if (sprite != null) {
            val spriteR = radius * 1.25f
            rect.set(cx - spriteR, cy - spriteR, cx + spriteR, cy + spriteR)
            canvas.drawBitmap(sprite, null, rect, null)
        } else {
            drawCircle(canvas, cx, cy, radius, color)
            drawText(canvas, label, cx, cy + cell * 0.05f, cell * 0.12f, textColor, Paint.Align.CENTER)
        }

        // HP bar above the creature
        val barTop = cy - radius - cell * 0.12f
        val barH = cell * 0.06f
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawRect(cx - radius, barTop, cx + radius, barTop + barH, paint)
        paint.color = Color.rgb(76, 175, 80)
        canvas.drawRect(cx - radius, barTop, cx - radius + radius * 2f * hpFrac.coerceIn(0f, 1f), barTop + barH, paint)
    }

    private fun drawCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        canvas.drawText(text, x, y, paint)
    }

    /** Grid-unit column to screen x. */
    private fun gx(col: Float) = gridLeft + col * cell

    /** Grid-unit row to screen y. */
    private fun gy(row: Float) = gridTop + row * cell
}
