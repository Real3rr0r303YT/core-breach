package dev.corebreach.screens

import com.badlogic.gdx.Gdx
import dev.corebreach.CoreBreachGame
import dev.corebreach.Sfx
import dev.corebreach.ui.DotFont
import dev.corebreach.ui.Palette

/** Fake-Boot-Log, der sich eintippt. Tap = weiter. */
class BootScreen(game: CoreBreachGame) : CyberScreen(game) {

    private val lines = listOf(
        "> CORE//BREACH V0.1",
        "[ OK ] MOUNTING /DEV/CORE0",
        "[ OK ] LOADING PHYSICS KERNEL",
        "[ OK ] LINKING DOT-MATRIX DISPLAY",
        "[ !! ] OMNIGRID DAEMONS DETECTED",
        "[ !! ] FIREWALLS ACTIVE",
        "[ OK ] CORE-0 ONLINE",
    )
    private val charsPerSecond = 70f
    private var typed = 0f
    private var lastTick = 0

    private val totalChars = lines.sumOf { it.length }

    override fun update(dt: Float) {
        typed += dt * charsPerSecond
        val shown = typed.toInt()
        if (shown != lastTick && shown < totalChars && shown % 3 == 0) Sfx.play("tick", 0.25f)
        lastTick = shown

        if (backPressed()) Gdx.app.exit()
        if (confirmPressed()) {
            if (typed < totalChars) {
                typed = totalChars.toFloat()
            } else {
                glitch = 1f
                switchTo(SelectScreen(game))
            }
        }
    }

    override fun draw() {
        val sr = beginHud()
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        val u = unit

        // Logo
        val big = u * 1.6f
        val partA = "CORE"
        val partB = "//"
        val partC = "BREACH"
        val total = DotFont.width("$partA$partB$partC", big)
        var x = (w - total) / 2f
        val y = h * 0.66f
        DotFont.draw(sr, partA, x, y, big, Palette.SIGNAL)
        x += DotFont.width(partA, big) + big
        DotFont.draw(sr, partB, x, y, big, Palette.CORE)
        x += DotFont.width(partB, big) + big
        DotFont.draw(sr, partC, x, y, big, Palette.SIGNAL)

        // Boot-Log
        val small = u * 0.42f
        val lineH = small * 11f
        val logX = (w - DotFont.width("[ OK ] LINKING DOT-MATRIX DISPLAY", small)) / 2f
        var remaining = typed.toInt()
        var ly = h * 0.56f
        for (line in lines) {
            if (remaining <= 0) break
            val part = line.take(remaining)
            remaining -= line.length
            val color = if (line.contains("!!")) Palette.CORE else Palette.a(Palette.SIGNAL, 0.85f)
            DotFont.draw(sr, part, logX, ly, small, color)
            ly -= lineH
        }

        if (typed >= totalChars && ((time * 2f).toInt() % 2 == 0)) {
            DotFont.draw(sr, "TAP TO BREACH", w / 2f, h * 0.1f, u * 0.7f, Palette.CORE, DotFont.CENTER)
        }
        DotFont.draw(sr, "3RR0R // MIT", w - u * 2f, u * 2f, u * 0.3f, Palette.DIM, DotFont.RIGHT)
        sr.end()
    }
}
