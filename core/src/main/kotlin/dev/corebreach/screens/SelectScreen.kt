package dev.corebreach.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.math.Rectangle
import dev.corebreach.CoreBreachGame
import dev.corebreach.Save
import dev.corebreach.Sfx
import dev.corebreach.level.Levels
import dev.corebreach.level.Worlds
import dev.corebreach.ui.DotFont
import dev.corebreach.ui.Palette
import kotlin.math.abs
import kotlin.math.min

/** Zielauswahl: eine Seite pro Welt, 10 Level-Karten mit Shard-Status, Boss-Karte rot. */
class SelectScreen(game: CoreBreachGame, initial: Int = -1) : CyberScreen(game) {

    private val levels = Levels.all
    private val perPage = Worlds.LEVELS_PER_WORLD
    private val pages = (levels.size + perPage - 1) / perPage
    private var selected = if (initial >= 0) initial else (Save.unlocked - 1).coerceIn(0, levels.size - 1)
    private var page = selected / perPage
    private val cards = Array(perPage) { Rectangle() }
    private val prevBtn = Rectangle()
    private val nextBtn = Rectangle()
    private val soundBtn = Rectangle()
    private val hapticBtn = Rectangle()
    private var dragStartX = -1f

    private fun layout() {
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        val cols = 5
        val gap = w * 0.018f
        val cw = min((w * 0.76f - gap * (cols - 1)) / cols, h * 0.24f)
        val ch = cw * 1.05f
        val startX = (w - (cw * cols + gap * (cols - 1))) / 2f
        val topY = h * 0.70f - ch
        for (i in 0 until perPage) {
            val row = i / cols
            val col = i % cols
            cards[i].set(startX + col * (cw + gap), topY - row * (ch + gap), cw, ch)
        }
        val u = unit
        prevBtn.set(0f, h * 0.2f, startX - gap, h * 0.55f)
        nextBtn.set(w - startX + gap, h * 0.2f, startX - gap, h * 0.55f)
        soundBtn.set(u * 3f, u * 3f, u * 22f, u * 6f)
        hapticBtn.set(u * 27f, u * 3f, u * 22f, u * 6f)
    }

    private fun unlocked(i: Int) = i < Save.unlocked

    private fun select(i: Int) {
        val n = i.coerceIn(0, levels.size - 1)
        if (n != selected) Sfx.play("tick")
        selected = n
        page = selected / perPage
    }

    private fun flip(dir: Int) {
        val p = (page + dir).coerceIn(0, pages - 1)
        if (p == page) return
        page = p
        selected = page * perPage
        glitch = 0.35f
        Sfx.play("tick")
    }

    override fun update(dt: Float) {
        layout()
        if (backPressed()) {
            switchTo(BootScreen(game)); return
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT) || Gdx.input.isKeyJustPressed(Input.Keys.A)) select(selected - 1)
        if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT) || Gdx.input.isKeyJustPressed(Input.Keys.D)) select(selected + 1)
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP) || Gdx.input.isKeyJustPressed(Input.Keys.W)) select(selected - 5)
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN) || Gdx.input.isKeyJustPressed(Input.Keys.S)) select(selected + 5)
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP) || Gdx.input.isKeyJustPressed(Input.Keys.Q)) flip(-1)
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN) || Gdx.input.isKeyJustPressed(Input.Keys.E)) flip(1)
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) || Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            launch(selected)
        }

        // Wischen = Welt wechseln, Tippen = Karte waehlen
        val x = Gdx.input.x.toFloat()
        val y = Gdx.graphics.height - Gdx.input.y.toFloat()
        if (Gdx.input.justTouched()) dragStartX = x
        if (dragStartX >= 0f && !Gdx.input.isTouched) {
            val dx = x - dragStartX
            val start = dragStartX
            dragStartX = -1f
            when {
                abs(dx) > Gdx.graphics.width * 0.12f -> flip(if (dx < 0f) 1 else -1)
                soundBtn.contains(x, y) -> { Save.sound = !Save.sound; Sfx.play("tick") }
                hapticBtn.contains(x, y) -> Save.haptics = !Save.haptics
                prevBtn.contains(start, y) -> flip(-1)
                nextBtn.contains(start, y) -> flip(1)
                else -> cards.forEachIndexed { i, r ->
                    val idx = page * perPage + i
                    if (idx < levels.size && r.contains(x, y)) {
                        selected = idx
                        launch(idx)
                    }
                }
            }
        }
    }

    private fun launch(i: Int) {
        if (!unlocked(i)) {
            glitch = 0.8f
            Sfx.play("hit", 0.4f)
            return
        }
        Sfx.play("terminal", 0.5f)
        switchTo(PlayScreen(game, i))
    }

    override fun draw() {
        layout()
        val sr = beginHud()
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        val u = unit

        DotFont.draw(sr, "SELECT TARGET", w / 2f, h * 0.86f, u * 0.9f, Palette.SIGNAL, DotFont.CENTER)
        DotFont.draw(sr, "WORLD " + (page + 1) + " // " + Worlds.names[page], w / 2f, h * 0.86f - u * 6f, u * 0.4f,
            Palette.CORE, DotFont.CENTER)

        for (i in 0 until perPage) {
            val idx = page * perPage + i
            if (idx >= levels.size) break
            val r = cards[i]
            val lv = levels[idx]
            val open = unlocked(idx)
            val sel = idx == selected
            val isBoss = lv.boss != null
            val lw = if (sel) u * 0.6f else u * 0.3f
            sr.color = when {
                sel && open -> Palette.CORE
                isBoss && open -> Palette.a(Palette.CORE, 0.7f)
                open -> Palette.SIGNAL
                else -> Palette.DIM
            }
            sr.rectLine(r.x, r.y, r.x + r.width, r.y, lw)
            sr.rectLine(r.x, r.y + r.height, r.x + r.width, r.y + r.height, lw)
            sr.rectLine(r.x, r.y, r.x, r.y + r.height, lw)
            sr.rectLine(r.x + r.width, r.y, r.x + r.width, r.y + r.height, lw)

            val numPitch = r.width / 19f
            val num = lv.id.toString().padStart(2, '0')
            val numColor = if (open) Palette.SIGNAL else Palette.DIM
            DotFont.draw(sr, if (open) num else "##", r.x + r.width / 2f, r.y + r.height * 0.48f, numPitch, numColor, DotFont.CENTER)

            val label = when {
                !open -> "LOCKED"
                isBoss -> lv.boss!!.title
                else -> lv.name
            }
            val namePitch = min(r.width * 0.86f / (label.length * 6f), numPitch * 0.45f)
            DotFont.draw(sr, label, r.x + r.width / 2f, r.y + r.height * 0.27f, namePitch,
                when {
                    !open -> Palette.DIM
                    isBoss -> Palette.CORE
                    else -> Palette.a(Palette.SIGNAL, 0.7f)
                }, DotFont.CENTER)
            val tag = numPitch * 0.25f
            if (isBoss) {
                DotFont.draw(sr, "BOSS", r.x + tag * 3f, r.y + r.height - tag * 10f, tag,
                    if (open) Palette.CORE else Palette.DIM)
            }

            // Shards
            val mask = Save.shardMask(lv.id)
            val dot = r.width * 0.045f
            for (s in 0 until 3) {
                val cx = r.x + r.width / 2f + (s - 1) * dot * 3f
                val cy = r.y + r.height * 0.12f
                sr.color = if (mask and (1 shl s) != 0) Palette.CORE else Palette.DIM
                sr.circle(cx, cy, dot * (if (mask and (1 shl s) != 0) 1f else 0.5f), 10)
            }
            if (open && Save.isDone(lv.id)) {
                DotFont.draw(sr, "OK", r.x + r.width - tag * 3f, r.y + r.height - tag * 10f,
                    tag, Palette.CORE, DotFont.RIGHT)
            }
        }

        // Seiten-Navigation
        val arrow = u * 0.9f
        if (page > 0) DotFont.draw(sr, "<", prevBtn.x + prevBtn.width / 2f, h * 0.45f, arrow, Palette.SIGNAL, DotFont.CENTER)
        if (page < pages - 1) DotFont.draw(sr, ">", nextBtn.x + nextBtn.width / 2f, h * 0.45f, arrow, Palette.SIGNAL, DotFont.CENTER)
        for (p in 0 until pages) {
            sr.color = when {
                p == page -> Palette.CORE
                p * perPage < Save.unlocked -> Palette.SIGNAL
                else -> Palette.DIM
            }
            sr.circle(w / 2f + (p - (pages - 1) / 2f) * u * 2.4f, cards[perPage - 1].y - u * 4f,
                if (p == page) u * 0.7f else u * 0.4f, 10)
        }

        val small = u * 0.38f
        DotFont.draw(sr, "SOUND " + (if (Save.sound) "ON" else "OFF"), soundBtn.x, soundBtn.y + small * 4f, small,
            if (Save.sound) Palette.SIGNAL else Palette.DIM)
        DotFont.draw(sr, "HAPTIC " + (if (Save.haptics) "ON" else "OFF"), hapticBtn.x, hapticBtn.y + small * 4f, small,
            if (Save.haptics) Palette.SIGNAL else Palette.DIM)
        DotFont.draw(sr, "SWIPE FOR WORLDS", w - u * 3f, u * 3f + small * 4f, small, Palette.DIM, DotFont.RIGHT)
        sr.end()
    }
}
