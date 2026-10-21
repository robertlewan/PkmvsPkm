package com.pkmvspkm.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Draws every screen on a canvas and handles all touch input. */
class GameView(context: Context) : View(context) {

    private enum class Screen { MENU, PREP, PLAYING, RESULT, ROSTER, POKEDEX }

    /** A tappable area, registered while drawing so buttons always match what is on screen. */
    private class Hit(val rect: RectF, val action: () -> Unit)

    /** Summary shown after a stage ends. */
    private class StageResult(val stage: LevelDef, val won: Boolean, val reward: StageReward, val candyStolen: Int)

    private val appContext = context.applicationContext

    init {
        Progress.load(appContext)
    }

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    private var screen = Screen.MENU
    private var stage: LevelDef? = null
    private var battle: Battle? = null
    private var selected: DefenderType? = null
    private var stageResult: StageResult? = null
    /** Pokemon chosen on the prep screen for the next stage. */
    private val picks = mutableListOf<DefenderType>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val stageButtons = LEVELS.map { RectF() }
    private val twinkles = List(40) { floatArrayOf(Random.nextFloat(), Random.nextFloat()) }

    private val hits = mutableListOf<Hit>()
    /** Hits above this y are ignored, so rows scrolled under the header cannot be tapped. */
    private var hitMinY = 0f

    // Layout, computed in onSizeChanged
    private var hudH = 0f
    private var houseLeft = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridRight = 0f
    private var gridBottom = 0f
    private var cell = 0f
    private var cardTop = 0f
    private var listTop = 0f

    // Scrolling for list screens
    private var scroll = 0f
    private var contentHeight = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragged = false

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

        // Landscape: size cells so the whole board fits, then center it
        val houseCells = 0.8f // width of the house strip that holds the candy, in cells
        hudH = fh * 0.12f
        cardTop = fh * 0.84f
        cell = min(fw * 0.92f / (COLS + houseCells), fh * 0.68f / ROWS)
        houseLeft = (fw - cell * (COLS + houseCells)) / 2f
        gridLeft = houseLeft + cell * houseCells
        gridTop = hudH + (cardTop - hudH - cell * ROWS) / 2f
        gridRight = gridLeft + cell * COLS
        gridBottom = gridTop + cell * ROWS

        listTop = fh * 0.2f

        // Stage buttons: three columns, four rows
        val bw = fw * 0.28f
        val bh = fh * 0.14f
        val gap = fh * 0.02f
        val left0 = (fw - (3 * bw + 2 * gap)) / 2f
        stageButtons.forEachIndexed { i, r ->
            val left = left0 + (i % 3) * (bw + gap)
            val top = fh * 0.3f + (i / 3) * (bh + gap)
            r.set(left, top, left + bw, top + bh)
        }
    }

    // ---------- Input ----------

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
                if (dragged && scrollable()) scroll = (scroll - dy).coerceIn(0f, maxScroll())
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) handleTap(event.x, event.y)
                performClick()
            }
        }
        return true
    }

    private fun scrollable(): Boolean =
        screen == Screen.ROSTER || screen == Screen.POKEDEX || screen == Screen.PREP

    private fun maxScroll(): Float = max(0f, contentHeight - (height - listTop))

    private fun handleTap(x: Float, y: Float) {
        for (h in hits.asReversed()) {
            if (h.rect.contains(x, y)) {
                h.action()
                return
            }
        }
        if (screen == Screen.PLAYING) handlePlayTap(x, y)
    }

    /** Taps on the board: collect a drop, or place the selected Pokemon on a tile. */
    private fun handlePlayTap(x: Float, y: Float) {
        val b = battle ?: return
        val fw = width.toFloat()

        // Card bar at the bottom: select or deselect a Pokemon
        if (y >= cardTop) {
            val cards = b.loadout
            if (cards.isEmpty()) return
            val slot = fw / cards.size
            val card = cards.getOrNull((x / slot).toInt()) ?: return
            selected = if (selected == card) null else card
            return
        }

        // Grid-unit coordinates of the tap
        val gridX = (x - gridLeft) / cell
        val gridY = (y - gridTop) / cell

        // Drops can be collected anywhere, including above the grid
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

    private fun togglePick(t: DefenderType) {
        if (picks.contains(t)) {
            picks.remove(t)
        } else if (picks.size < 5) {
            picks.add(t)
        }
    }

    // ---------- Screen changes ----------

    private fun openPrep(level: LevelDef) {
        stage = level
        picks.clear()
        picks.addAll(Progress.loadout.filter { Progress.owns(it) })
        scroll = 0f
        screen = Screen.PREP
    }

    private fun startBattle(level: LevelDef) {
        Progress.setLoadout(picks)
        battle = Battle(level, Progress.loadout, { Progress.upgradesOf(it) })
        selected = null
        screen = Screen.PLAYING
    }

    private fun retreat() {
        battle = null
        stageResult = null
        selected = null
        screen = Screen.MENU
    }

    private fun finishBattle(b: Battle) {
        val won = b.outcome == Outcome.WON
        val reward = Progress.finishStage(b.level, won, b.candyStolen)
        stageResult = StageResult(b.level, won, reward, b.candyStolen)
        screen = Screen.RESULT
    }

    // ---------- Drawing entry point ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        hitMinY = 0f
        when (screen) {
            Screen.MENU -> drawMenu(canvas)
            Screen.PREP -> drawPrep(canvas)
            Screen.PLAYING -> battle?.let { drawBattle(canvas, it, live = true) }
            Screen.RESULT -> {
                battle?.let { drawBattle(canvas, it, live = false) }
                drawResult(canvas)
            }
            Screen.ROSTER -> drawRoster(canvas)
            Screen.POKEDEX -> drawPokedex(canvas)
        }
    }

    // ---------- Menu ----------

    private fun drawMenu(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, 0f, fw, fh, Color.rgb(25, 20, 60), Color.rgb(90, 35, 110))

        paint.style = Paint.Style.FILL
        for (s in twinkles) {
            paint.color = Color.argb((120 + 120 * sin(time * 2f + s[0] * 10f)).toInt(), 255, 255, 255)
            canvas.drawCircle(s[0] * fw, s[1] * fh * 0.3f, 2f + s[0] * 2f, paint)
        }

        drawText(canvas, "PkmvsPkm", fw / 2f + 4f, fh * 0.12f + 4f, fw * 0.07f, Color.argb(170, 0, 0, 0), Paint.Align.CENTER)
        drawText(canvas, "PkmvsPkm", fw / 2f, fh * 0.12f, fw * 0.07f, Color.rgb(255, 213, 79), Paint.Align.CENTER)
        drawStarRow(canvas, fw / 2f - fw * 0.03f, fh * 0.2f, fh * 0.025f, 1, 1)
        drawText(canvas, "${Progress.stars}", fw / 2f + fh * 0.01f, fh * 0.2f + fh * 0.01f, fh * 0.035f,
            Color.WHITE, Paint.Align.LEFT)

        button(canvas, RectF(fw * 0.03f, fh * 0.03f, fw * 0.17f, fh * 0.11f), "Roster", Color.rgb(70, 120, 200), true) {
            scroll = 0f
            screen = Screen.ROSTER
        }
        button(canvas, RectF(fw * 0.83f, fh * 0.03f, fw * 0.97f, fh * 0.11f), "Pokedex", Color.rgb(140, 110, 200), true) {
            scroll = 0f
            screen = Screen.POKEDEX
        }

        LEVELS.forEachIndexed { i, lvl ->
            val r = stageButtons[i]
            val open = Progress.isUnlocked(lvl.id)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                r.left, r.top, r.left, r.bottom,
                if (open) dim(lvl.theme.skyTop, 0.7f) else Color.rgb(110, 110, 120),
                if (open) dim(lvl.theme.skyBottom, 0.5f) else Color.rgb(70, 70, 80),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(r, 24f, 24f, paint)
            paint.shader = null
            if (open) {
                drawText(canvas, "${lvl.id}. ${lvl.name}", r.left + r.width() * 0.05f, r.centerY() - r.height() * 0.05f,
                    r.height() * 0.2f, Color.WHITE, Paint.Align.LEFT)
                drawStarRow(canvas, r.left + r.width() * 0.07f, r.centerY() + r.height() * 0.26f,
                    r.height() * 0.1f, Progress.bestStars(lvl.id), 3)
                hits.add(Hit(RectF(r)) { openPrep(lvl) })
            } else {
                drawText(canvas, "Locked", r.centerX(), r.centerY() + r.height() * 0.07f,
                    r.height() * 0.2f, Color.LTGRAY, Paint.Align.CENTER)
            }
        }
    }

    // ---------- Prep: choose up to five Pokemon for a stage ----------

    private fun drawPrep(canvas: Canvas) {
        val lvl = stage ?: return
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, 0f, fw, fh, lvl.theme.skyTop, lvl.theme.skyBottom)

        drawText(canvas, lvl.name, fw / 2f, fh * 0.1f, fw * 0.04f, Color.WHITE, Paint.Align.CENTER)
        drawText(canvas, "Choose up to 5 Pokemon   ${picks.size}/5", fw / 2f, fh * 0.16f,
            fw * 0.022f, Color.LTGRAY, Paint.Align.CENTER)

        val owned = Progress.owned()
        val cols = 4
        val cardW = fw * 0.2f
        val cardH = fh * 0.2f
        val gap = fw * 0.015f
        val left0 = (fw - (cols * cardW + (cols - 1) * gap)) / 2f

        hitMinY = listTop
        canvas.save()
        canvas.clipRect(0f, listTop, fw, fh)
        owned.forEachIndexed { i, t ->
            val x = left0 + (i % cols) * (cardW + gap)
            val y = listTop + (i / cols) * (cardH + gap) - scroll
            val chosen = picks.contains(t)
            rect.set(x, y, x + cardW, y + cardH)
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = if (chosen) Color.rgb(255, 236, 140) else Color.argb(220, 40, 45, 80)
            canvas.drawRoundRect(rect, 24f, 24f, paint)
            drawSprite(canvas, t.spriteId, x + cardW / 2f, y + cardH * 0.42f, cardH * 0.5f)
            drawText(canvas, t.label, x + cardW / 2f, y + cardH * 0.82f, fw * 0.02f,
                if (chosen) Color.BLACK else Color.WHITE, Paint.Align.CENTER)
            drawText(canvas, "Lv ${Progress.upgradesOf(t).total}", x + cardW / 2f, y + cardH * 0.94f, fw * 0.016f,
                if (chosen) Color.DKGRAY else Color.LTGRAY, Paint.Align.CENTER)
            if (y + cardH > listTop && y < fh) hits.add(Hit(RectF(x, y, x + cardW, y + cardH)) { togglePick(t) })
        }
        canvas.restore()
        val rows = (owned.size + cols - 1) / cols
        contentHeight = rows * (cardH + gap)

        hitMinY = 0f
        backButton(canvas)
        button(canvas, RectF(fw * 0.8f, fh * 0.87f, fw * 0.96f, fh * 0.96f), "Start", Color.rgb(76, 175, 80),
            picks.isNotEmpty()) { startBattle(lvl) }
    }

    // ---------- Roster: spend stars on upgrades and evolve ----------

    private fun drawRoster(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, 0f, fw, fh, Color.rgb(30, 30, 60), Color.rgb(60, 40, 90))

        val owned = Progress.owned()
        val rowH = fh * 0.19f
        val pad = fw * 0.012f
        hitMinY = listTop
        canvas.save()
        canvas.clipRect(0f, listTop, fw, fh)
        var y = listTop + pad - scroll
        for (t in owned) {
            drawRosterRow(canvas, y, t, rowH, pad)
            y += rowH + pad
        }
        canvas.restore()
        contentHeight = owned.size * (rowH + pad) + pad

        hitMinY = 0f
        drawText(canvas, "Roster", fw / 2f, fh * 0.1f, fw * 0.04f, Color.WHITE, Paint.Align.CENTER)
        drawStarRow(canvas, fw * 0.78f, fh * 0.09f, fh * 0.025f, 1, 1)
        drawText(canvas, "${Progress.stars} stars", fw * 0.78f + fh * 0.035f, fh * 0.1f, fh * 0.035f,
            Color.WHITE, Paint.Align.LEFT)
        drawText(canvas, "Spend stars to upgrade. Each level boosts one stat. Level 5 can evolve.",
            fw / 2f, fh * 0.16f, fw * 0.018f, Color.LTGRAY, Paint.Align.CENTER)
        backButton(canvas)
    }

    private fun drawRosterRow(canvas: Canvas, y: Float, t: DefenderType, rowH: Float, pad: Float) {
        val fw = width.toFloat()
        val up = Progress.upgradesOf(t)
        rect.set(pad, y, fw - pad, y + rowH)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(220, 40, 45, 80)
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        val spriteSize = rowH * 0.8f
        drawSprite(canvas, t.spriteId, pad * 3f + spriteSize / 2f, y + rowH / 2f, spriteSize)

        val textX = pad * 3f + spriteSize + pad * 2f
        drawText(canvas, t.label, textX, y + rowH * 0.3f, rowH * 0.2f, Color.WHITE, Paint.Align.LEFT)
        drawText(canvas, "Lv ${up.total}/$MAX_LEVEL   ${roleName(t)}", textX, y + rowH * 0.5f,
            rowH * 0.13f, Color.rgb(160, 220, 255), Paint.Align.LEFT)
        // Move name only, so the text stays clear of the upgrade buttons
        drawText(canvas, t.info.substringBefore(":"), textX, y + rowH * 0.66f, rowH * 0.12f,
            Color.rgb(220, 220, 235), Paint.Align.LEFT)

        val evolveTo = EVOLUTIONS[t]
        if (evolveTo != null && Progress.canEvolve(t)) {
            button(canvas, RectF(textX, y + rowH * 0.8f, textX + fw * 0.26f, y + rowH * 0.95f),
                "Evolve into ${evolveTo.label}", Color.rgb(76, 175, 80), true) { Progress.evolve(t) }
        }

        // Four upgrade buttons on the right, labelled for this Pokemon's role
        val labels = statLabels(t)
        val bw = fw * 0.11f
        val bh = rowH * 0.36f
        val by = y + rowH * 0.12f
        val cost = Progress.upgradeCost(up.total)
        for (i in 0..3) {
            val left = fw - pad * 3f - (4 - i) * bw - (3 - i) * pad
            button(canvas, RectF(left, by, left + bw, by + bh), "${labels[i]} ($cost)",
                Color.rgb(70, 120, 200), Progress.canUpgrade(t)) { Progress.upgrade(t, i) }
        }
    }

    // ---------- Pokedex ----------

    private fun drawPokedex(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, 0f, fw, fh, Color.rgb(25, 20, 60), Color.rgb(40, 50, 90))

        hitMinY = listTop
        canvas.save()
        canvas.clipRect(0f, listTop, fw, fh)
        var y = listTop - scroll + fw * 0.02f
        y = drawSectionTitle(canvas, "Pokemon you can recruit", y)
        for (t in DefenderType.entries) {
            val stats = "Cost ${t.cost}  •  HP ${t.hp.toInt()}  •  ${roleName(t)}"
            y = drawPokedexEntry(canvas, y, t.spriteId, t.label, stats, t.info)
        }
        y = drawSectionTitle(canvas, "Enemies (the dark Pokemon)", y)
        for (e in EnemyType.entries) {
            val stats = "HP ${e.hp.toInt()}  •  Speed ${e.speed}  •  Bite ${e.dps.toInt()}/s"
            y = drawPokedexEntry(canvas, y, e.spriteId, e.label, stats, e.info)
        }
        canvas.restore()
        contentHeight = y + scroll - listTop

        hitMinY = 0f
        drawText(canvas, "Pokedex", fw / 2f, fh * 0.1f, fw * 0.05f, Color.rgb(255, 213, 79), Paint.Align.CENTER)
        backButton(canvas)
    }

    private fun drawSectionTitle(canvas: Canvas, title: String, y: Float): Float {
        val fw = width.toFloat()
        val size = fw * 0.03f
        drawText(canvas, title, fw * 0.04f, y + size, size, Color.rgb(255, 213, 79), Paint.Align.LEFT)
        return y + size * 2.2f
    }

    /** Draws one Pokedex card and returns the y position for the next card. */
    private fun drawPokedexEntry(canvas: Canvas, y: Float, spriteId: Int, name: String, stats: String, info: String): Float {
        val fw = width.toFloat()
        val pad = fw * 0.03f
        val spriteSize = fw * 0.2f
        val h = entryHeight(fw, info)
        val bodySize = fw * 0.022f

        rect.set(pad, y, fw - pad, y + h)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(200, 40, 45, 80)
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        drawSprite(canvas, spriteId, pad * 2f + spriteSize / 2f, y + h / 2f, spriteSize)

        val textX = pad * 2f + spriteSize + pad
        val titleSize = fw * 0.03f
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
        val bodySize = fw * 0.022f
        val titleSize = fw * 0.03f
        val textX = pad * 2f + spriteSize + pad
        val lines = wrapText(info, fw - textX - pad, bodySize).size
        val textH = titleSize * 2.1f + bodySize * 1.6f + lines * bodySize * 1.35f + pad
        return max(spriteSize + pad * 2f, textH)
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

    private fun drawBattle(canvas: Canvas, b: Battle, live: Boolean) {
        drawBackdrop(canvas, b.level.theme)
        drawHouse(canvas)
        drawLawn(canvas, b.level.theme)
        drawTraps(canvas, b)

        for (d in b.defenders) {
            drawCreature(
                canvas, gx(d.col + 0.5f), gy(d.row + 0.5f), cell * 0.36f,
                phase = d.row * 1.3f + d.col,
                spriteId = d.type.spriteId,
                fallbackColor = d.type.shot.color,
                label = d.type.label,
                glowColor = null,
                hpFrac = d.hp / d.stats.maxHp,
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
        drawPickups(canvas, b)
        drawCardBar(canvas, b)
        drawHud(canvas, b, live)
        drawBanner(canvas, b)
    }

    private fun drawHouse(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.rgb(150, 100, 70)
        canvas.drawRect(houseLeft, gridTop, gridLeft - 4f, gridBottom, paint)
        paint.color = Color.rgb(110, 70, 50)
        for (r in 0 until ROWS) {
            canvas.drawRect(houseLeft, gy(r + 1f) - 3f, gridLeft - 4f, gy(r + 1f), paint)
        }
    }

    private fun drawLawn(canvas: Canvas, theme: Theme) {
        paint.style = Paint.Style.FILL
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val left = gx(c.toFloat())
            val top = gy(r.toFloat())
            paint.color = if ((r + c) % 2 == 0) theme.tileA else theme.tileB
            canvas.drawRect(left, top, left + cell, top + cell, paint)
            paint.color = Color.argb(35, 255, 255, 255)
            canvas.drawRect(left, top, left + cell, top + cell * 0.06f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.rgb(60, 100, 40)
        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, paint)
        paint.style = Paint.Style.FILL
    }

    /** The rare candy at the end of each row. It is the one that enemies steal. */
    private fun drawTraps(canvas: Canvas, b: Battle) {
        val r = cell * 0.26f
        for (row in 0 until ROWS) {
            val cx = houseLeft + cell * 0.4f
            val cy = gy(row + 0.5f)
            if (b.trapReady[row]) {
                drawCandy(canvas, cx, cy, r * (1f + 0.04f * sin(time * 4f + row)))
            }
            if (b.trapFlash[row] > 0f) {
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = Color.argb((255 * b.trapFlash[row] / 0.8f).toInt().coerceIn(0, 255), 255, 255, 255)
                canvas.drawRect(gridLeft, gy(row.toFloat()), gridRight, gy(row + 1f), paint)
            }
        }
    }

    private fun drawHud(canvas: Canvas, b: Battle, live: Boolean) {
        val fw = width.toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(150, 20, 25, 45)
        rect.set(8f, 8f, fw - 8f, hudH - 8f)
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        val iconY = hudH * 0.5f
        val iconR = hudH * 0.2f

        // Sunlight, the currency
        val sunX = fw * 0.05f + iconR
        drawSunIcon(canvas, sunX, iconY, iconR)
        drawText(canvas, "${b.sun}", sunX + iconR * 1.6f, iconY + cell * 0.13f, cell * 0.36f, Color.WHITE, Paint.Align.LEFT)

        // Rare candy left in the lanes
        val candyX = fw * 0.2f
        drawCandy(canvas, candyX, iconY, iconR)
        drawText(canvas, "${b.trapReady.count { it }}/$ROWS", candyX + iconR * 1.6f, iconY + cell * 0.13f,
            cell * 0.3f, Color.WHITE, Paint.Align.LEFT)

        if (live) {
            button(canvas, RectF(fw * 0.42f, hudH * 0.2f, fw * 0.58f, hudH * 0.8f), "Retreat",
                Color.rgb(150, 60, 60), true) { retreat() }
        }

        drawText(canvas, b.level.name, fw * 0.95f, hudH * 0.42f, cell * 0.2f, Color.WHITE, Paint.Align.RIGHT)
        drawText(canvas, "Enemies ${b.spawnedCount}/${b.level.count}", fw * 0.95f, hudH * 0.8f,
            cell * 0.15f, Color.rgb(200, 210, 230), Paint.Align.RIGHT)
    }

    private fun drawCardBar(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, cardTop, fw, fh, Color.rgb(95, 70, 50), Color.rgb(60, 42, 30))

        val cards = b.loadout
        if (cards.isEmpty()) return
        val slot = fw / cards.size
        cards.forEachIndexed { i, t ->
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

            drawSprite(canvas, t.spriteId, rect.centerX(), top + cardH * 0.36f, cardH * 0.4f)
            drawText(canvas, t.label, rect.centerX(), top + cardH * 0.7f, cell * 0.15f,
                Color.rgb(40, 30, 20), Paint.Align.CENTER)

            // Cost badge in sunlight
            val badgeR = cell * 0.2f
            drawSunIcon(canvas, left + badgeR * 1.1f, top + badgeR * 1.1f, badgeR)
            drawText(canvas, "${t.cost}", left + badgeR * 1.1f, top + badgeR * 1.1f + cell * 0.06f,
                cell * 0.16f, Color.rgb(90, 50, 0), Paint.Align.CENTER)

            if (selected == t) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 7f
                paint.color = Color.rgb(255, 193, 7)
                canvas.drawRoundRect(rect, 18f, 18f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    /** Event text (e.g. "Sableye stole 25 sun!") or the selected card's info, shown above the cards. */
    private fun drawBanner(canvas: Canvas, b: Battle) {
        val fw = width.toFloat()
        val text = if (b.messageTimer > 0f) {
            b.message
        } else {
            selected?.info ?: return
        }
        val size = fw * 0.022f
        val lines = wrapText(text, fw * 0.5f, size)
        val lineH = size * 1.3f
        val boxH = lines.size * lineH + size * 1.2f
        val cy = (gridBottom + cardTop) / 2f

        rect.set(fw * 0.25f, cy - boxH / 2f, fw * 0.75f, cy + boxH / 2f)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(200, 20, 25, 45)
        canvas.drawRoundRect(rect, 24f, 24f, paint)
        lines.forEachIndexed { i, line ->
            drawText(canvas, line, fw / 2f, rect.top + size * 1.1f + i * lineH, size, Color.WHITE, Paint.Align.CENTER)
        }
    }

    private fun drawProjectiles(canvas: Canvas, b: Battle) {
        for (p in b.projectiles) {
            val px = gx(p.x)
            val py = gy(p.row + 0.5f)
            // Trail in the shot's own color
            for (i in 1..3) {
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = Color.argb(110 / i, Color.red(p.shot.color), Color.green(p.shot.color), Color.blue(p.shot.color))
                canvas.drawCircle(px - i * cell * 0.15f, py, cell * (0.09f - i * 0.02f), paint)
            }
            drawShot(canvas, p.shot, px, py, cell * 0.14f)
        }
    }

    /** Draws a projectile in the shape of its attack type: a flame, a droplet, a bolt, a leaf, and so on. */
    private fun drawShot(canvas: Canvas, shot: ShotType, x: Float, y: Float, s: Float) {
        val c = shot.color
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = c
        when (shot) {
            ShotType.NORMAL -> canvas.drawCircle(x, y, s * 0.7f, paint)
            ShotType.FIRE -> {
                drawCircle(canvas, x, y, s * 0.8f, Color.rgb(255, 87, 34))
                drawCircle(canvas, x, y + s * 0.1f, s * 0.45f, Color.rgb(255, 235, 59))
            }
            ShotType.WATER -> {
                drawCircle(canvas, x, y, s * 0.6f, c)
                fillPath(canvas, c, floatArrayOf(x - s * 0.5f, y - s * 0.2f, x + s * 0.5f, y - s * 0.2f, x, y - s * 1.1f))
            }
            ShotType.GRASS -> oval(canvas, c, x - s, y - s * 0.5f, x + s, y + s * 0.5f)
            ShotType.ELECTRIC -> {
                val path = Path()
                path.moveTo(x - s, y - s * 0.6f)
                path.lineTo(x - s * 0.2f, y - s * 0.1f)
                path.lineTo(x - s * 0.5f, y + s * 0.1f)
                path.lineTo(x + s, y + s * 0.6f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.35f
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            ShotType.ROCK, ShotType.GROUND -> {
                rect.set(x - s * 0.6f, y - s * 0.6f, x + s * 0.6f, y + s * 0.6f)
                canvas.drawRect(rect, paint)
            }
            ShotType.POISON -> {
                drawCircle(canvas, x, y, s * 0.7f, c)
                drawCircle(canvas, x - s * 0.3f, y - s * 0.6f, s * 0.2f, Color.WHITE)
                drawCircle(canvas, x + s * 0.4f, y + s * 0.2f, s * 0.15f, Color.WHITE)
            }
            ShotType.GHOST, ShotType.SHADOW -> {
                drawCircle(canvas, x, y, s * 0.75f, c)
                drawCircle(canvas, x, y, s * 0.35f, Color.argb(200, 20, 10, 30))
            }
            ShotType.BUG -> drawCircle(canvas, x, y, s * 0.6f, c)
            ShotType.ICE -> fillPath(
                canvas, c,
                floatArrayOf(x, y - s, x + s * 0.7f, y, x, y + s, x - s * 0.7f, y),
            )
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

    /** Sun drops falling from the sky are sunlight. Drops made by defenders are rare candy. */
    private fun drawSuns(canvas: Canvas, b: Battle) {
        for (s in b.suns) {
            val px = gx(s.x)
            val py = gy(s.y)
            val pulse = 1f + 0.08f * sin(time * 5f + s.x)
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(px, py, cell * 0.55f, Color.argb(160, 255, 220, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, cell * 0.55f, paint)
            paint.shader = null
            if (s.candy) {
                drawCandy(canvas, px, py, cell * 0.24f * pulse)
            } else {
                drawSunIcon(canvas, px, py, cell * 0.24f * pulse)
            }
        }
    }

    /** Drops flying up to the counter. The count goes up when they arrive. */
    private fun drawPickups(canvas: Canvas, b: Battle) {
        val targetX = width.toFloat() * 0.05f + hudH * 0.2f
        val targetY = hudH * 0.5f
        for (p in b.pickups) {
            val fromX = gx(p.x)
            val fromY = gy(p.y)
            val t = p.progress * p.progress // speeds up as it nears the counter
            val x = fromX + (targetX - fromX) * t
            val y = fromY + (targetY - fromY) * t
            val r = cell * 0.24f * (1f - 0.5f * p.progress)
            if (p.candy) drawCandy(canvas, x, y, r) else drawSunIcon(canvas, x, y, r)
        }
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

    /** Glow behind an enemy shows its status: blue when slowed, orange when burning, green when poisoned. */
    private fun enemyGlow(e: Enemy): Int = when {
        e.slowTimer > 0f -> Color.argb(140, 120, 210, 255)
        e.burnTimer > 0f -> Color.argb(140, 255, 120, 40)
        e.poisonTimer > 0f -> Color.argb(140, 160, 90, 200)
        else -> Color.argb(110, 150, 60, 200)
    }

    private fun healthColor(frac: Float): Int = when {
        frac > 0.5f -> Color.rgb(76, 175, 80)
        frac > 0.25f -> Color.rgb(255, 160, 0)
        else -> Color.rgb(230, 60, 50)
    }

    // ---------- Result ----------

    private fun drawResult(canvas: Canvas) {
        val r = stageResult ?: return
        val fw = width.toFloat()
        val fh = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(170, 0, 0, 0)
        canvas.drawRect(0f, 0f, fw, fh, paint)

        val pw = fw * 0.6f
        val ph = fh * 0.66f
        rect.set((fw - pw) / 2f, (fh - ph) / 2f, (fw + pw) / 2f, (fh + ph) / 2f)
        paint.color = Color.rgb(30, 40, 70)
        canvas.drawRoundRect(rect, 32f, 32f, paint)

        val cx = fw / 2f
        var y = rect.top + ph * 0.13f
        val title = if (r.won) "${r.stage.name} cleared!" else "Defeated..."
        drawText(canvas, title, cx, y, fw * 0.035f, Color.WHITE, Paint.Align.CENTER)
        y += ph * 0.17f

        val starsEarned = if (r.won) r.reward.stars else 0
        drawStarRow(canvas, cx - fh * 0.05f, y, fh * 0.045f, starsEarned, 3)
        y += ph * 0.16f

        drawText(canvas, "Candy stolen: ${r.candyStolen}", cx, y, fw * 0.022f, Color.LTGRAY, Paint.Align.CENTER)
        y += ph * 0.1f
        if (r.won) {
            drawText(canvas, "Stars earned: +${r.reward.gained}   (total ${Progress.stars})", cx, y,
                fw * 0.022f, Color.rgb(255, 213, 79), Paint.Align.CENTER)
            y += ph * 0.1f
            if (r.reward.recruits.isNotEmpty()) {
                val names = r.reward.recruits.joinToString(", ") { it.label }
                drawText(canvas, "Recruited: $names", cx, y, fw * 0.022f, Color.rgb(160, 255, 180), Paint.Align.CENTER)
            }
        }

        button(canvas, RectF(cx - pw * 0.15f, rect.bottom - ph * 0.2f, cx + pw * 0.15f, rect.bottom - ph * 0.06f),
            "Continue", Color.rgb(76, 175, 80), true) {
            stageResult = null
            battle = null
            screen = Screen.MENU
        }
    }

    // ---------- Backdrops ----------

    /** Scenery behind the board, drawn with simple shapes so no image files are needed. */
    private fun drawBackdrop(canvas: Canvas, theme: Theme) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        fillGradient(canvas, 0f, 0f, fw, fh, theme.skyTop, theme.skyBottom)
        when (theme) {
            Theme.FOREST -> drawForest(canvas, fw, fh)
            Theme.CAVE -> drawCave(canvas, fw, fh)
            Theme.PLAIN -> drawPlain(canvas, fw, fh)
            Theme.FACTORY -> drawFactory(canvas, fw, fh)
            Theme.TOWER -> drawTower(canvas, fw, fh)
            Theme.SEA -> drawSea(canvas, fw, fh)
            Theme.VOLCANO -> drawVolcano(canvas, fw, fh)
            Theme.MOUNTAIN -> drawMountains(canvas, fw, fh, Color.rgb(70, 80, 100), Color.rgb(230, 235, 245))
            Theme.PLATEAU -> {
                drawClouds(canvas, fw, fh)
                drawMountains(canvas, fw, fh, Color.rgb(90, 130, 80), Color.rgb(245, 250, 255))
            }
        }
    }

    private fun drawForest(canvas: Canvas, fw: Float, fh: Float) {
        for (i in 0..8) {
            val x = fw * i / 8f
            val w = fw * 0.08f
            polygon(canvas, Color.rgb(30, 95, 45), x - w, 0f, x + w, 0f, x, fh * (0.14f + 0.04f * (i % 3)))
            polygon(canvas, Color.rgb(35, 105, 50), x - w, fh, x + w, fh, x, fh * (0.86f - 0.04f * (i % 2)))
        }
    }

    private fun drawCave(canvas: Canvas, fw: Float, fh: Float) {
        for (i in 0..10) {
            val x = fw * i / 10f
            val w = fw * 0.04f
            polygon(canvas, Color.rgb(50, 46, 56), x - w, 0f, x + w, 0f, x, fh * (0.12f + 0.06f * (i % 3)))
            polygon(canvas, Color.rgb(95, 88, 84), x - w, fh, x + w, fh, x, fh * (0.9f - 0.05f * (i % 2)))
        }
    }

    private fun drawPlain(canvas: Canvas, fw: Float, fh: Float) {
        oval(canvas, Color.rgb(255, 225, 90), fw * 0.84f, fh * 0.06f, fw * 0.94f, fh * 0.2f)
        oval(canvas, Color.rgb(100, 170, 80), -fw * 0.2f, fh * 0.78f, fw * 0.6f, fh * 1.3f)
        oval(canvas, Color.rgb(85, 150, 70), fw * 0.4f, fh * 0.82f, fw * 1.2f, fh * 1.4f)
    }

    private fun drawFactory(canvas: Canvas, fw: Float, fh: Float) {
        for (i in 0..5) {
            val x = fw * (0.05f + i * 0.18f)
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.rgb(70, 74, 84)
            rect.set(x, 0f, x + fw * 0.035f, fh * 0.22f)
            canvas.drawRect(rect, paint)
            paint.color = Color.argb(120, 200, 200, 210)
            canvas.drawCircle(x + fw * 0.02f, fh * (0.1f + 0.02f * i), fh * 0.05f, paint)
        }
        // Yellow hazard stripe near the bottom
        paint.color = Color.rgb(240, 200, 40)
        canvas.drawRect(0f, fh * 0.93f, fw, fh, paint)
    }

    private fun drawTower(canvas: Canvas, fw: Float, fh: Float) {
        for (i in 0..30) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.argb(180, 255, 255, 255)
            canvas.drawCircle(fw * ((i * 37) % 100) / 100f, fh * ((i * 53) % 90) / 100f, 2f + (i % 3), paint)
        }
        val cx = fw * 0.9f
        for (layer in 0..4) {
            val w = fw * (0.07f - layer * 0.01f)
            val top = fh * (0.9f - 0.13f * (layer + 1))
            val bottom = fh * (0.9f - 0.13f * layer)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(40, 28, 60)
            rect.set(cx - w, top, cx + w, bottom)
            canvas.drawRect(rect, paint)
        }
        polygon(canvas, Color.rgb(40, 28, 60), cx - fw * 0.04f, fh * 0.25f, cx + fw * 0.04f, fh * 0.25f, cx, fh * 0.12f)
    }

    private fun drawSea(canvas: Canvas, fw: Float, fh: Float) {
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = fh * 0.008f
        paint.color = Color.argb(120, 220, 245, 255)
        for (row in 0..6) {
            val y = fh * (0.4f + 0.09f * row)
            val path = Path()
            path.moveTo(0f, y)
            var x = 0f
            var up = true
            while (x < fw) {
                path.quadTo(x + fw * 0.03f, y + (if (up) -1f else 1f) * fh * 0.015f, x + fw * 0.06f, y)
                x += fw * 0.06f
                up = !up
            }
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawVolcano(canvas: Canvas, fw: Float, fh: Float) {
        for (i in 0..4) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.argb(90, 80, 70, 70)
            canvas.drawCircle(fw * (0.3f + i * 0.1f), fh * (0.06f + 0.03f * (i % 2)), fh * 0.06f, paint)
        }
        polygon(canvas, Color.rgb(60, 36, 32), fw * 0.15f, fh, fw * 0.85f, fh, fw * 0.5f, fh * 0.3f)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            fw * 0.5f, fh * 0.3f, fh * 0.08f,
            Color.argb(230, 255, 140, 40), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(fw * 0.5f, fh * 0.3f, fh * 0.08f, paint)
        paint.shader = null
    }

    private fun drawMountains(canvas: Canvas, fw: Float, fh: Float, rock: Int, snow: Int) {
        for (i in 0..4) {
            val px = fw * (i * 0.25f)
            val peakY = fh * (0.15f + 0.05f * (i % 2))
            polygon(canvas, rock, px - fw * 0.18f, fh, px + fw * 0.18f, fh, px, peakY)
            polygon(canvas, snow, px - fw * 0.05f, peakY + fh * 0.06f, px + fw * 0.05f, peakY + fh * 0.06f, px, peakY)
        }
    }

    private fun drawClouds(canvas: Canvas, fw: Float, fh: Float) {
        for (i in 0..4) {
            val x = fw * (0.1f + i * 0.22f)
            val y = fh * (0.1f + 0.08f * (i % 2))
            oval(canvas, Color.argb(220, 255, 255, 255), x, y, x + fw * 0.12f, y + fh * 0.05f)
        }
    }

    // ---------- Shared helpers ----------

    /** Button that draws itself and registers its tap area. Disabled buttons are drawn grey and do nothing. */
    private fun button(canvas: Canvas, r: RectF, label: String, color: Int, enabled: Boolean, action: () -> Unit) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = if (enabled) color else Color.rgb(90, 90, 100)
        canvas.drawRoundRect(r, 20f, 20f, paint)
        drawText(canvas, label, r.centerX(), r.centerY() + r.height() * 0.13f, r.height() * 0.36f,
            if (enabled) Color.WHITE else Color.LTGRAY, Paint.Align.CENTER)
        if (enabled && r.bottom > hitMinY && r.top < height) hits.add(Hit(RectF(r), action))
    }

    private fun backButton(canvas: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        button(canvas, RectF(fw * 0.03f, fh * 0.03f, fw * 0.2f, fh * 0.11f), "Back", Color.rgb(70, 90, 140), true) {
            scroll = 0f
            screen = Screen.MENU
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean) {
        val pts = FloatArray(20)
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            pts[2 * i] = cx + rr * cos(a).toFloat()
            pts[2 * i + 1] = cy + rr * sin(a).toFloat()
        }
        fillPath(canvas, if (filled) Color.rgb(255, 213, 79) else Color.rgb(90, 90, 110), pts)
    }

    /** Draws [total] stars starting at x, the first [filled] of them gold. */
    private fun drawStarRow(canvas: Canvas, x: Float, cy: Float, r: Float, filled: Int, total: Int) {
        for (i in 0 until total) drawStar(canvas, x + i * r * 2.6f, cy, r, i < filled)
    }

    private fun dim(color: Int, f: Float): Int =
        Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    private fun fillPath(canvas: Canvas, color: Int, pts: FloatArray) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        val path = Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun polygon(canvas: Canvas, color: Int, vararg pts: Float) = fillPath(canvas, color, pts)

    private fun oval(canvas: Canvas, color: Int, left: Float, top: Float, right: Float, bottom: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        rect.set(left, top, right, bottom)
        canvas.drawOval(rect, paint)
    }

    private fun fillGradient(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, from: Int, to: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.shader = null
    }

    private fun drawCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = null
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

    private fun drawSprite(canvas: Canvas, spriteId: Int, cx: Float, cy: Float, size: Float) {
        val sprite = SpriteCache.get(spriteId) { invalidate() } ?: return
        spritePaint.colorFilter = null
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        canvas.drawBitmap(sprite, null, rect, spritePaint)
    }

    /** Sunlight icon: the currency shown in the HUD and on card costs. */
    private fun drawSunIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawCircle(canvas, cx, cy, r, Color.rgb(255, 200, 60))
        drawCircle(canvas, cx - r * 0.3f, cy - r * 0.3f, r * 0.3f, Color.rgb(255, 250, 210))
    }

    /** Rare candy, drawn from the official item sprite. Shows a pink ball until the sprite loads. */
    private fun drawCandy(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val candy = SpriteCache.getItem("rare-candy") { invalidate() }
        if (candy != null) {
            val s = r * 1.6f
            rect.set(cx - s, cy - s, cx + s, cy + s)
            spritePaint.colorFilter = null
            canvas.drawBitmap(candy, null, rect, spritePaint)
        } else {
            drawCircle(canvas, cx, cy, r, Color.rgb(240, 110, 160))
            drawCircle(canvas, cx - r * 0.3f, cy - r * 0.3f, r * 0.3f, Color.rgb(255, 230, 240))
        }
    }

    /** Grid-unit column to screen x. */
    private fun gx(col: Float) = gridLeft + col * cell

    /** Grid-unit row to screen y. */
    private fun gy(row: Float) = gridTop + row * cell
}
