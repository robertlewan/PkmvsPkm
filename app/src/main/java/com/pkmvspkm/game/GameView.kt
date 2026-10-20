package com.pkmvspkm.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Draws the menu, Pokedex and battle screens on a canvas and handles all touch input. */
class GameView(context: Context) : View(context) {

    private enum class Screen { MENU, POKEDEX, PLAYING, RESULT }

    private val appContext = context.applicationContext
    private var screen = Screen.MENU
    private var battle: Battle? = null
    private var selected: Card? = null
    private var resultText = ""
    private var resultSubText = ""
    private var unlocked = SaveData.unlockedLevel(appContext)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val levelButtons = LEVELS.map { RectF() }
    private val stars = List(40) { floatArrayOf(Random.nextFloat(), Random.nextFloat()) }
    private val pokedexButton = RectF()
    private val backButton = RectF()
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private var pokedexScroll = 0f
    private var listTop = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragged = false

    // Layout, computed in onSizeChanged
    private var hudH = 0f
    private var houseLeft = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridRight = 0f
    private var gridBottom = 0f
    private var cell = 0f
    private var cardTop = 0f

    /** Seconds since the view started; drives idle bobbing and pulses. */
    private var time = 0f

    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 0f
            else min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.1f)
            lastFrameNanos = frameTimeNanos
            time += dt

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

        // Landscape: size cells so the whole board fits both width and height, then center it
        val houseCells = 0.8f // width of the house strip that holds the lane traps, in cells
        hudH = fh * 0.12f
        val cardH = fh * 0.16f
        cardTop = fh - cardH
        cell = min(fw * 0.92f / (COLS + houseCells), fh * 0.68f / ROWS)
        houseLeft = (fw - cell * (COLS + houseCells)) / 2f
        gridLeft = houseLeft + cell * houseCells
        gridTop = hudH + (cardTop - hudH - cell * ROWS) / 2f
        gridRight = gridLeft + cell * COLS
        gridBottom = gridTop + cell * ROWS

        val bw = fw * 0.8f
        val bh = fh * 0.1f
        val gap = fh * 0.025f
        val startY = fh * 0.36f
        levelButtons.forEachIndexed { i, r ->
            val top = startY + i * (bh + gap)
            r.set((fw - bw) / 2f, top, (fw + bw) / 2f, top + bh)
        }
        val pokedexTop = startY + LEVELS.size * (bh + gap)
        pokedexButton.set((fw - bw) / 2f, pokedexTop, (fw + bw) / 2f, pokedexTop + bh * 0.8f)
        backButton.set(fw * 0.04f, fh * 0.03f, fw * 0.3f, fh * 0.03f + fh * 0.07f)
        listTop = fh * 0.16f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                lastY = event.y
                dragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y
                if (abs(event.y - downY) > touchSlop) dragged = true
                if (screen == Screen.POKEDEX && dragged) {
                    pokedexScroll = (pokedexScroll - dy).coerceIn(0f, maxPokedexScroll())
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) handleTap(event.x, event.y)
                performClick()
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        when (screen) {
            Screen.MENU -> {
                if (pokedexButton.contains(x, y)) {
                    pokedexScroll = 0f
                    screen = Screen.POKEDEX
                    return
                }
                LEVELS.forEachIndexed { i, lvl ->
                    if (lvl.id <= unlocked && levelButtons[i].contains(x, y)) startLevel(lvl)
                }
            }
            Screen.POKEDEX -> if (backButton.contains(x, y)) screen = Screen.MENU
            Screen.PLAYING -> handlePlayTap(x, y)
            Screen.RESULT -> screen = Screen.MENU
        }
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
            resultSubText = if (b.level.id < LEVELS.size) "Next level unlocked" else "You beat Gengar!"
        } else {
            resultText = "The dark Pokemon got through!"
            resultSubText = "Try again from the menu"
        }
        screen = Screen.RESULT
    }

    private fun handlePlayTap(x: Float, y: Float) {
        val b = battle ?: return

        // Card bar at the bottom: select or deselect a defender
        if (y >= cardTop) {
            val idx = (x / (width.toFloat() / ALL_CARDS.size)).toInt()
            val card = ALL_CARDS.getOrNull(idx) ?: return
            if (card is LegendType) {
                // Legendary helpers cast immediately; no grid tile needed
                b.summon(card)
                selected = null
            } else {
                selected = if (selected == card) null else card
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

        val type = selected as? DefenderType ?: return
        if (b.place(type, row, col)) selected = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (screen) {
            Screen.MENU -> drawMenu(canvas)
            Screen.POKEDEX -> drawPokedex(canvas)
            Screen.PLAYING -> battle?.let { drawBattle(canvas, it) }
            Screen.RESULT -> {
                battle?.let { drawBattle(canvas, it) }
                drawResult(canvas)
            }
        }
    }

    // ---------- Menu ----------

    private fun drawMenu(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()

        fillGradient(canvas, 0f, 0f, fw, fh, Color.rgb(25, 20, 60), Color.rgb(90, 35, 110))

        // Twinkling stars in the top half
        paint.style = Paint.Style.FILL
        for (s in stars) {
            paint.color = Color.argb((120 + 120 * sin(time * 2f + s[0] * 10f)).toInt(), 255, 255, 255)
            canvas.drawCircle(s[0] * fw, s[1] * fh * 0.6f, 2f + s[0] * 2f, paint)
        }

        // Title with drop shadow
        drawText(canvas, "PkmvsPkm", fw / 2f + 5f, fh * 0.2f + 5f, fw * 0.13f, Color.argb(170, 0, 0, 0), Paint.Align.CENTER)
        drawText(canvas, "PkmvsPkm", fw / 2f, fh * 0.2f, fw * 0.13f, Color.rgb(255, 213, 79), Paint.Align.CENTER)
        drawText(canvas, "Stop the dark Pokemon!", fw / 2f, fh * 0.27f, fw * 0.045f, Color.rgb(230, 220, 255), Paint.Align.CENTER)

        LEVELS.forEachIndexed { i, lvl ->
            val r = levelButtons[i]
            val open = lvl.id <= unlocked
            val bh = r.height()
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                r.left, r.top, r.left, r.bottom,
                if (open) Color.rgb(110, 200, 110) else Color.rgb(120, 120, 130),
                if (open) Color.rgb(50, 130, 60) else Color.rgb(75, 75, 85),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(r, 28f, 28f, paint)
            paint.shader = null

            if (open) {
                val subtitle = if (lvl.bossAtEnd) "${lvl.count} enemies  •  Boss: Gengar" else "${lvl.count} enemies"
                drawText(canvas, "${lvl.id}. ${lvl.name}", r.left + fw * 0.05f, r.centerY() - bh * 0.05f,
                    fw * 0.05f, Color.WHITE, Paint.Align.LEFT)
                drawText(canvas, subtitle, r.left + fw * 0.05f, r.centerY() + bh * 0.3f,
                    fw * 0.03f, Color.rgb(230, 255, 230), Paint.Align.LEFT)
                // Preview the enemies of this level on the right
                lvl.pool.take(3).forEachIndexed { j, et ->
                    val size = bh * 0.75f
                    val cx = r.right - fw * 0.07f - j * size * 0.8f
                    drawSprite(canvas, et.spriteId, cx, r.centerY(), size)
                }
            } else {
                drawText(canvas, "Locked", r.centerX(), r.centerY() + fw * 0.016f,
                    fw * 0.045f, Color.LTGRAY, Paint.Align.CENTER)
            }
        }
        drawPokedexButton(canvas)
    }

    private fun drawPokedexButton(canvas: Canvas) {
        val fw = width.toFloat()
        val r = pokedexButton
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            r.left, r.top, r.left, r.bottom,
            Color.rgb(140, 110, 200), Color.rgb(90, 60, 150), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, 28f, 28f, paint)
        paint.shader = null
        drawText(canvas, "Pokedex", r.centerX(), r.centerY() + fw * 0.016f,
            fw * 0.045f, Color.WHITE, Paint.Align.CENTER)
    }

    // ---------- Pokedex ----------

    private fun drawPokedex(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, 0f, fw, fh, Color.rgb(25, 20, 60), Color.rgb(40, 50, 90))

        drawText(canvas, "Pokedex", fw / 2f, fh * 0.08f, fw * 0.08f, Color.rgb(255, 213, 79), Paint.Align.CENTER)

        // Back button
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(70, 90, 140)
        canvas.drawRoundRect(backButton, 24f, 24f, paint)
        drawText(canvas, "< Back", backButton.centerX(), backButton.centerY() + fw * 0.014f,
            fw * 0.04f, Color.WHITE, Paint.Align.CENTER)

        // Scrolling list, clipped below the header
        canvas.save()
        canvas.clipRect(0f, listTop, fw, fh)
        var y = listTop - pokedexScroll + fw * 0.02f

        y = drawSectionTitle(canvas, "Defenders (your side)", y)
        for (t in DefenderType.entries) {
            val stats = "Cost ${t.cost}  •  HP ${t.hp.toInt()}"
            y = drawPokedexEntry(canvas, y, t.spriteId, t.label, stats, t.info)
        }
        y = drawSectionTitle(canvas, "Enemies (the dark Pokemon)", y)
        for (e in EnemyType.entries) {
            val stats = "HP ${e.hp.toInt()}  •  Speed ${e.speed}  •  Bite ${e.dps.toInt()}/s"
            y = drawPokedexEntry(canvas, y, e.spriteId, e.label, stats, e.info)
        }
        y = drawSectionTitle(canvas, "Legendary helpers (one use each)", y)
        for (l in LegendType.entries) {
            val stats = "Cost ${l.cost}  •  One use per battle"
            y = drawPokedexEntry(canvas, y, l.spriteId, l.label, stats, l.info)
        }
        canvas.restore()
    }

    private fun drawSectionTitle(canvas: Canvas, title: String, y: Float): Float {
        val fw = width.toFloat()
        val size = fw * 0.055f
        drawText(canvas, title, fw * 0.04f, y + size, size, Color.rgb(255, 213, 79), Paint.Align.LEFT)
        return y + size * 2f
    }

    /** Draws one Pokedex card and returns the y position for the next card. */
    private fun drawPokedexEntry(canvas: Canvas, y: Float, spriteId: Int, name: String, stats: String, info: String): Float {
        val fw = width.toFloat()
        val pad = fw * 0.03f
        val spriteSize = fw * 0.2f
        val h = entryHeight(fw, info)
        val bodySize = fw * 0.034f

        rect.set(pad, y, fw - pad, y + h)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 40, 45, 80)
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        drawSprite(canvas, spriteId, pad + pad + spriteSize / 2f, y + h / 2f, spriteSize)

        val textX = pad * 2f + spriteSize + pad
        val titleSize = fw * 0.045f
        drawText(canvas, name, textX, y + pad + titleSize, titleSize, Color.WHITE, Paint.Align.LEFT)
        drawText(canvas, stats, textX, y + pad + titleSize * 2.1f, bodySize, Color.rgb(160, 220, 255), Paint.Align.LEFT)
        var lineY = y + pad + titleSize * 2.1f + bodySize * 1.6f
        for (line in wrapText(info, fw - textX - pad, bodySize)) {
            drawText(canvas, line, textX, lineY, bodySize, Color.rgb(230, 230, 240), Paint.Align.LEFT)
            lineY += bodySize * 1.35f
        }
        return y + h + fw * 0.02f
    }

    private fun entryHeight(fw: Float, info: String): Float {
        val pad = fw * 0.03f
        val spriteSize = fw * 0.2f
        val bodySize = fw * 0.034f
        val titleSize = fw * 0.045f
        val textX = pad * 2f + spriteSize + pad
        val lines = wrapText(info, fw - textX - pad, bodySize).size
        val textH = titleSize * 2.1f + bodySize * 1.6f + lines * bodySize * 1.35f + pad
        return max(spriteSize + pad * 2f, textH)
    }

    private fun pokedexContentHeight(fw: Float): Float {
        var h = fw * 0.02f + fw * 0.055f * 2f * 3f
        for (t in DefenderType.entries) h += entryHeight(fw, t.info) + fw * 0.02f
        for (e in EnemyType.entries) h += entryHeight(fw, e.info) + fw * 0.02f
        for (l in LegendType.entries) h += entryHeight(fw, l.info) + fw * 0.02f
        return h
    }

    private fun maxPokedexScroll(): Float {
        val visible = height - listTop
        return max(0f, pokedexContentHeight(width.toFloat()) - visible)
    }

    /** Splits text into lines that fit in [maxWidth] at the given text size. */
    private fun wrapText(text: String, maxWidth: Float, size: Float): List<String> {
        paint.textSize = size
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    // ---------- Battle ----------

    private fun drawBattle(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()
        val fh = height.toFloat()

        // Sky
        fillGradient(canvas, 0f, 0f, fw, fh, Color.rgb(90, 160, 230), Color.rgb(170, 220, 250))

        drawHouse(canvas)
        drawLawn(canvas)
        drawTraps(canvas, b)

        for (d in b.defenders) {
            drawCreature(
                canvas, gx(d.col + 0.5f), gy(d.row + 0.5f), cell * 0.36f,
                phase = d.row * 1.3f + d.col,
                spriteId = d.type.spriteId,
                fallbackColor = d.type.color,
                label = d.type.label,
                glowColor = null,
                hpFrac = d.hp / d.type.hp,
                hitFlash = d.hitFlash,
            )
        }
        for (e in b.enemies) {
            drawCreature(
                canvas, gx(e.x + 0.5f), gy(e.row + 0.5f), if (e.type.boss) cell * 0.48f else cell * 0.36f,
                phase = e.row * 1.3f + e.x,
                spriteId = e.type.spriteId,
                fallbackColor = e.type.color,
                label = e.type.label,
                glowColor = enemyGlow(e),
                hpFrac = e.hp / e.type.hp,
                hitFlash = e.hitFlash,
            )
        }

        drawProjectiles(canvas, b)
        drawEnemyShots(canvas, b)
        drawSuns(canvas, b)
        drawCardBar(canvas, b)
        drawHud(canvas, b)
        drawBanner(canvas, b)
        drawLegendEffect(canvas, b)
    }

    /** Glow behind an enemy: blue when frozen, orange when burning, purple otherwise. */
    private fun enemyGlow(e: Enemy): Int = when {
        e.freezeTimer > 0f -> Color.argb(140, 120, 210, 255)
        e.burnTimer > 0f -> Color.argb(140, 255, 120, 40)
        else -> Color.argb(110, 150, 60, 200)
    }

    /** Full-screen tint and name when a legendary helper fires. */
    private fun drawLegendEffect(canvas: Canvas, b: Battle) {
        val type = b.legendEffect ?: return
        if (b.legendTimer <= 0f) return
        val fw = width.toFloat()
        val fh = height.toFloat()
        val alpha = (140 * b.legendTimer / 1.2f).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(alpha, Color.red(type.tint), Color.green(type.tint), Color.blue(type.tint))
        canvas.drawRect(0f, 0f, fw, fh, paint)
        drawText(canvas, type.label, fw / 2f, fh * 0.45f, fw * 0.12f,
            Color.argb(alpha, 255, 255, 255), Paint.Align.CENTER)
    }

    /** Event text (e.g. "Sableye stole 25 sun!") or the selected card's info, shown above the cards. */
    private fun drawBanner(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()
        val text = if (b.messageTimer > 0f) {
            b.message
        } else {
            selected?.info ?: return
        }
        val size = fw * 0.04f
        val lines = wrapText(text, fw * 0.85f, size)
        val lineH = size * 1.3f
        val boxH = lines.size * lineH + size * 1.2f
        val cy = (gridBottom + cardTop) / 2f

        rect.set(fw * 0.075f, cy - boxH / 2f, fw * 0.925f, cy + boxH / 2f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 20, 25, 45)
        canvas.drawRoundRect(rect, 24f, 24f, paint)
        lines.forEachIndexed { i, line ->
            drawText(canvas, line, fw / 2f, rect.top + size * 1.1f + i * lineH, size, Color.WHITE, Paint.Align.CENTER)
        }
    }

    private fun drawEnemyShots(canvas: Canvas, b: Battle) {
        for (s in b.enemyShots) {
            val px = gx(s.x)
            val py = gy(s.row + 0.5f)
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(px, py, cell * 0.3f, Color.argb(220, 170, 90, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, cell * 0.3f, paint)
            paint.shader = null
            drawCircle(canvas, px, py, cell * 0.12f, Color.rgb(90, 30, 140))
        }
    }

    private fun drawHouse(canvas: Canvas) {
        // Brick wall of the house to the left of the lawn
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(150, 100, 70)
        canvas.drawRect(houseLeft, gridTop, gridLeft - 4f, gridBottom, paint)
        paint.color = Color.rgb(110, 70, 50)
        for (r in 0 until ROWS) {
            canvas.drawRect(houseLeft, gy(r + 1f) - 3f, gridLeft - 4f, gy(r + 1f), paint)
        }
    }

    private fun drawLawn(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val left = gx(c.toFloat())
            val top = gy(r.toFloat())
            paint.color = if ((r + c) % 2 == 0) Color.rgb(124, 189, 90) else Color.rgb(108, 170, 78)
            canvas.drawRect(left, top, left + cell, top + cell, paint)
            // Soft highlight along the top edge of each tile
            paint.color = Color.argb(35, 255, 255, 255)
            canvas.drawRect(left, top, left + cell, top + cell * 0.06f, paint)
        }
        // Lawn border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.rgb(60, 100, 40)
        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawTraps(canvas: Canvas, b: Battle) {
        val r = cell * 0.26f
        for (row in 0 until ROWS) {
            val cx = houseLeft + cell * 0.4f
            val cy = gy(row + 0.5f)
            if (b.trapReady[row]) {
                drawPokeBall(canvas, cx, cy, r * (1f + 0.04f * sin(time * 4f + row)))
            }
            if (b.trapFlash[row] > 0f) {
                val alpha = (255 * b.trapFlash[row] / 0.8f).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawRect(gridLeft, gy(row.toFloat()), gridRight, gy(row + 1f), paint)
            }
        }
    }

    private fun drawPokeBall(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        rect.set(cx - r, cy - r, cx + r, cy + r)
        paint.color = Color.rgb(230, 40, 40)
        canvas.drawArc(rect, 180f, 180f, true, paint)
        paint.color = Color.WHITE
        canvas.drawArc(rect, 0f, 180f, true, paint)
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(cx - r, cy - r * 0.12f, cx + r, cy + r * 0.12f, paint)
        canvas.drawCircle(cx, cy, r * 0.28f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.16f, paint)
        // Outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCreature(
        canvas: Canvas,
        cx: Float,
        cy0: Float,
        radius: Float,
        phase: Float,
        spriteId: Int,
        fallbackColor: Int,
        label: String,
        glowColor: Int?,
        hpFrac: Float,
        hitFlash: Float,
    ) {
        // Gentle idle bob; the shadow stays on the ground
        val cy = cy0 + sin(time * 3f + phase) * cell * 0.03f

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(80, 0, 0, 0)
        rect.set(cx - radius * 0.8f, cy0 + radius * 0.55f, cx + radius * 0.8f, cy0 + radius * 0.85f)
        canvas.drawOval(rect, paint)

        if (glowColor != null) {
            paint.shader = RadialGradient(cx, cy, radius * 1.5f, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius * 1.5f, paint)
            paint.shader = null
        }

        val sprite = SpriteCache.get(spriteId) { invalidate() }
        if (sprite != null) {
            val s = radius * 1.3f
            rect.set(cx - s, cy - s, cx + s, cy + s)
            spritePaint.colorFilter = if (hitFlash > 0f) {
                val a = (200 * min(hitFlash / 0.12f, 1f)).toInt()
                PorterDuffColorFilter(Color.argb(a, 255, 255, 255), PorterDuff.Mode.SRC_ATOP)
            } else {
                null
            }
            canvas.drawBitmap(sprite, null, rect, spritePaint)
        } else {
            drawCircle(canvas, cx, cy, radius, fallbackColor)
            drawText(canvas, label, cx, cy + cell * 0.05f, cell * 0.12f, Color.WHITE, Paint.Align.CENTER)
        }

        // Health bar above the creature
        val barW = radius * 2.2f
        val barH = cell * 0.07f
        val barTop = cy - radius * 1.3f - cell * 0.12f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(160, 0, 0, 0)
        rect.set(cx - barW / 2f, barTop, cx + barW / 2f, barTop + barH)
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint)
        paint.color = healthColor(hpFrac)
        rect.set(cx - barW / 2f, barTop, cx - barW / 2f + barW * hpFrac.coerceIn(0f, 1f), barTop + barH)
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint)
    }

    private fun healthColor(frac: Float): Int = when {
        frac > 0.5f -> Color.rgb(76, 175, 80)
        frac > 0.25f -> Color.rgb(255, 160, 0)
        else -> Color.rgb(230, 60, 50)
    }

    private fun drawProjectiles(canvas: Canvas, b: Battle) {
        for (p in b.projectiles) {
            val px = gx(p.x)
            val py = gy(p.row + 0.5f)
            // Fading trail behind the orb
            for (i in 1..3) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(110 / i, 255, 230, 90)
                canvas.drawCircle(px - i * cell * 0.15f, py, cell * (0.09f - i * 0.02f), paint)
            }
            paint.shader = RadialGradient(px, py, cell * 0.22f, Color.argb(200, 255, 240, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, cell * 0.22f, paint)
            paint.shader = null
            drawCircle(canvas, px, py, cell * 0.08f, Color.rgb(255, 250, 200))
        }
    }

    private fun drawSuns(canvas: Canvas, b: Battle) {
        for (s in b.suns) {
            val px = gx(s.x)
            val py = gy(s.y)
            val pulse = 1f + 0.08f * sin(time * 5f + s.x)
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(px, py, cell * 0.55f, Color.argb(160, 255, 220, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, cell * 0.55f, paint)
            paint.shader = null
            drawSunIcon(canvas, px, py, cell * 0.24f * pulse)
        }
    }

    private fun drawSunIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawCircle(canvas, cx, cy, r, Color.rgb(255, 200, 60))
        drawCircle(canvas, cx - r * 0.3f, cy - r * 0.3f, r * 0.3f, Color.rgb(255, 250, 210))
    }

    private fun drawCardBar(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()
        val fh = height.toFloat()

        // Wooden tray behind the cards
        fillGradient(canvas, 0f, cardTop, fw, fh, Color.rgb(95, 70, 50), Color.rgb(60, 42, 30))

        val types = ALL_CARDS
        val slot = fw / types.size
        types.forEachIndexed { i, t ->
            val top = cardTop + 12f
            val bottom = fh - 12f
            val cardH = bottom - top
            val left = i * slot + 6f
            val right = (i + 1) * slot - 6f
            val affordable = b.canUse(t)

            rect.set(left, top, right, bottom)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, top, 0f, bottom,
                if (affordable) Color.rgb(255, 252, 235) else Color.rgb(185, 180, 170),
                if (affordable) Color.rgb(240, 225, 180) else Color.rgb(150, 145, 135),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            paint.shader = null

            // Sprite thumbnail
            drawSprite(canvas, t.spriteId, rect.centerX(), top + cardH * 0.36f, cardH * 0.4f)

            // Name
            drawText(canvas, t.label, rect.centerX(), top + cardH * 0.7f, cell * 0.15f,
                Color.rgb(40, 30, 20), Paint.Align.CENTER)

            // Cost badge
            val badgeR = cell * 0.2f
            drawSunIcon(canvas, left + badgeR * 1.1f, top + badgeR * 1.1f, badgeR)
            drawText(canvas, "${t.cost}", left + badgeR * 1.1f, top + badgeR * 1.1f + cell * 0.06f,
                cell * 0.16f, Color.rgb(90, 50, 0), Paint.Align.CENTER)

            // Legendary cards get a gold-style frame, and show USED once spent
            if (t is LegendType) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = t.tint
                canvas.drawRoundRect(rect, 18f, 18f, paint)
                paint.style = Paint.Style.FILL
                if (b.usedLegends.contains(t)) {
                    paint.color = Color.argb(160, 30, 30, 30)
                    canvas.drawRoundRect(rect, 18f, 18f, paint)
                    drawText(canvas, "USED", rect.centerX(), rect.centerY() + cell * 0.06f,
                        cell * 0.2f, Color.WHITE, Paint.Align.CENTER)
                }
            }

            // Selection frame
            if (selected == t) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 7f
                paint.color = Color.rgb(255, 193, 7)
                canvas.drawRoundRect(rect, 18f, 18f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawHud(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()

        // Translucent panel
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 20, 25, 45)
        rect.set(8f, 8f, fw - 8f, hudH - 8f)
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        // Sun counter
        val iconX = fw * 0.08f
        val iconY = hudH * 0.5f
        val iconR = hudH * 0.2f
        drawSunIcon(canvas, iconX, iconY, iconR)
        drawText(canvas, "${b.sun}", iconX + iconR * 1.6f, iconY + cell * 0.13f, cell * 0.36f,
            Color.WHITE, Paint.Align.LEFT)
        drawText(canvas, "Tap a card, then a tile", gridLeft, hudH * 0.86f, cell * 0.13f,
            Color.rgb(200, 210, 230), Paint.Align.LEFT)

        // Level and progress
        drawText(canvas, b.level.name, fw * 0.95f, hudH * 0.42f, cell * 0.2f,
            Color.WHITE, Paint.Align.RIGHT)
        drawText(canvas, "Enemies ${b.spawnedCount}/${b.level.count}", fw * 0.95f, hudH * 0.8f, cell * 0.15f,
            Color.rgb(200, 210, 230), Paint.Align.RIGHT)
    }

    // ---------- Result overlay ----------

    private fun drawResult(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(170, 0, 0, 0)
        canvas.drawRect(0f, 0f, fw, fh, paint)

        val pw = fw * 0.8f
        val ph = fh * 0.22f
        rect.set((fw - pw) / 2f, fh / 2f - ph / 2f, (fw + pw) / 2f, fh / 2f + ph / 2f)
        paint.color = Color.rgb(30, 40, 70)
        canvas.drawRoundRect(rect, 32f, 32f, paint)

        drawText(canvas, resultText, fw / 2f, fh / 2f - ph * 0.08f, fw * 0.055f, Color.WHITE, Paint.Align.CENTER)
        drawText(canvas, resultSubText, fw / 2f, fh / 2f + ph * 0.2f, fw * 0.035f, Color.rgb(230, 220, 255), Paint.Align.CENTER)
        drawText(canvas, "Tap to continue", fw / 2f, fh / 2f + ph * 0.4f, fw * 0.03f, Color.LTGRAY, Paint.Align.CENTER)
    }

    // ---------- Drawing helpers ----------

    private fun drawSprite(canvas: Canvas, spriteId: Int, cx: Float, cy: Float, size: Float) {
        val sprite = SpriteCache.get(spriteId) { invalidate() } ?: return
        spritePaint.colorFilter = null
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        canvas.drawBitmap(sprite, null, rect, spritePaint)
    }

    private fun fillGradient(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, from: Int, to: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.shader = null
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
        paint.shader = null
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
