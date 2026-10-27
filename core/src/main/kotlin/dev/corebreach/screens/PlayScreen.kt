package dev.corebreach.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.viewport.ExtendViewport
import kotlin.math.min
import dev.corebreach.CoreBreachGame
import dev.corebreach.Haptics
import dev.corebreach.Save
import dev.corebreach.Sfx
import dev.corebreach.level.Levels
import dev.corebreach.ui.Controls
import dev.corebreach.ui.DotFont
import dev.corebreach.ui.Palette
import dev.corebreach.world.Boss
import dev.corebreach.world.GameWorld
import dev.corebreach.world.Phys
import dev.corebreach.world.WorldEvents
import dev.corebreach.world.WorldRenderer

class PlayScreen(game: CoreBreachGame, private val levelIndex: Int) : CyberScreen(game), WorldEvents {

    private val level = Levels.all[levelIndex]
    private var world = GameWorld(level, this)
    private val cam = OrthographicCamera()
    private val view = ExtendViewport(18f, 10f, cam)
    private val renderer = WorldRenderer(game.shapes)
    private val controls = Controls()

    private var doneTime = -1f
    private var segfaultTime = -1f
    private var shake = 0f
    private var alert = ""
    private var alertTime = 0f

    init {
        val p = world.core.position
        cam.position.set(p.x, p.y, 0f)
        glitch = 0.7f
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        view.update(width, height, false)
    }

    override fun update(dt: Float) {
        controls.poll()
        if (controls.pausePressed || backPressed()) {
            switchTo(SelectScreen(game, levelIndex))
            return
        }

        when {
            doneTime >= 0f -> {
                doneTime += dt
                if (doneTime > 2.0f) {
                    if (levelIndex + 1 < Levels.all.size) switchTo(PlayScreen(game, levelIndex + 1))
                    else switchTo(SelectScreen(game, levelIndex))
                }
            }
            segfaultTime >= 0f -> {
                segfaultTime += dt
                if (segfaultTime > 1.6f) restart()
            }
        }
        world.update(dt, controls)

        followCamera(dt)
        shake = (shake - dt * 3f).coerceAtLeast(0f)
        alertTime = (alertTime - dt).coerceAtLeast(0f)
    }

    private fun restart() {
        world.dispose()
        world = GameWorld(level, this)
        segfaultTime = -1f
        glitch = 1f
    }

    private fun followCamera(dt: Float) {
        val p = world.core.position
        val v = world.core.linearVelocity
        val tx = p.x + v.x * 0.25f
        val ty = p.y + v.y * 0.08f
        val k = 1f - Math.pow(0.002, dt.toDouble()).toFloat()
        cam.position.x += (tx - cam.position.x) * k
        cam.position.y += (ty - cam.position.y) * k

        val halfW = view.worldWidth / 2f
        val halfH = view.worldHeight / 2f
        cam.position.x = if (level.width <= halfW * 2) level.width / 2f
        else cam.position.x.coerceIn(halfW, level.width - halfW)
        cam.position.y = if (level.height <= halfH * 2) level.height / 2f
        else cam.position.y.coerceIn(halfH, level.height - halfH)

        if (shake > 0f) {
            cam.position.x += MathUtils.random(-1f, 1f) * shake * 0.25f
            cam.position.y += MathUtils.random(-1f, 1f) * shake * 0.25f
        }
        cam.update()
    }

    override fun draw() {
        view.apply()
        renderer.render(world, cam)
        drawHud()
    }

    private fun drawHud() {
        val sr = beginHud()
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        val u = unit
        val small = u * 0.42f
        val top = h - u * 6f
        val boss = world.boss
        val exfil = boss != null && boss.exfiltrating > 0f

        if (exfil) {
            // Spyware hat das HUD geklaut
            if ((time * 6f).toInt() % 3 != 0) {
                DotFont.draw(sr, "DATA EXFILTRATED", u * 4f, top, small, Palette.CORE)
                DotFont.draw(sr, "?/3", w - u * 16f, top, small, Palette.DIM, DotFont.RIGHT)
            }
        } else {
            drawStatus(sr, w, u, small, top)
        }

        // Level (oben Mitte), schrumpft bei langen Namen, damit es nicht in Integrity/Shards laeuft
        val levelText = level.label + " // " + level.name
        val levelPitch = min(small, (w - u * 78f) / (levelText.length * 6f))
        DotFont.draw(sr, levelText, w / 2f, top, levelPitch, Palette.SIGNAL, DotFont.CENTER)
        if (world.gravSign < 0f) {
            if ((time * 3f).toInt() % 2 == 0) {
                DotFont.draw(sr, "GRAVITY INVERTED", w / 2f, top - u * 4f, small * 0.8f, Palette.CORE, DotFont.CENTER)
            }
        }

        if (boss != null && !boss.defeated) drawBossHud(sr, boss, w, h, u, small, top)

        controls.draw(sr)

        // Intro-Titel
        if (world.time < 1.8f && doneTime < 0f) {
            val a = 1f - (world.time / 1.8f)
            if (boss != null) {
                DotFont.draw(sr, "THREAT DETECTED", w / 2f, h * 0.55f + u * 12f, small, Palette.a(Palette.CORE, a), DotFont.CENTER)
                DotFont.draw(sr, boss.type.title, w / 2f, h * 0.55f, u * 1.2f, Palette.a(Palette.SIGNAL, a), DotFont.CENTER)
                DotFont.draw(sr, boss.type.tagline, w / 2f, h * 0.55f - u * 6f, small, Palette.a(Palette.CORE, a), DotFont.CENTER)
            } else {
                DotFont.draw(sr, level.name, w / 2f, h * 0.55f, u * 1.2f, Palette.a(Palette.SIGNAL, a), DotFont.CENTER)
                DotFont.draw(sr, "> BREACH " + level.label, w / 2f, h * 0.55f - u * 6f, small, Palette.a(Palette.CORE, a), DotFont.CENTER)
            }
        }

        // Alarm-Banner (Boss-Aktionen, Checkpoints)
        if (alertTime > 0f && doneTime < 0f && segfaultTime < 0f) {
            val a = (alertTime / 0.4f).coerceAtMost(1f)
            DotFont.draw(sr, alert, w / 2f, h * 0.7f, u * 0.6f, Palette.a(Palette.CORE, a), DotFont.CENTER)
        }

        if (doneTime >= 0f) overlay(sr, w, h, u, "ACCESS GRANTED", "SHARDS " + world.shardsCollected + "/3", Palette.SIGNAL)
        if (segfaultTime >= 0f) overlay(sr, w, h, u, "SEGFAULT", "REBOOTING CORE-0...", Palette.CORE)
        sr.end()
    }

    private fun drawBossHud(
        sr: com.badlogic.gdx.graphics.glutils.ShapeRenderer, boss: Boss,
        w: Float, h: Float, u: Float, small: Float, top: Float,
    ) {
        // Name + Phase
        val by = top - u * 8f
        val title = if (boss.totalPhases > 1) boss.type.title + " " + (boss.phase + 1) + "/" + boss.totalPhases else boss.type.title
        DotFont.draw(sr, title, w / 2f, by, small, Palette.CORE, DotFont.CENTER)
        // HP-Leiste aus Segmenten
        val segW = u * 5f
        val gap = u * 0.8f
        val total = boss.maxHp * segW + (boss.maxHp - 1) * gap
        // Dunkle Platte, damit Name und Leiste ueber der Level-Geometrie lesbar bleiben
        val plateW = maxOf(total, DotFont.width(title, small)) + u * 4f
        sr.color = Palette.a(Palette.VOID, 0.8f)
        sr.rect(w / 2f - plateW / 2f, by - u * 3.4f, plateW, u * 7f)
        for (i in 0 until boss.maxHp) {
            sr.color = if (i < boss.hp) Palette.CORE else Palette.DIM
            sr.rect(w / 2f - total / 2f + i * (segW + gap), by - u * 2.4f, segW, u * (if (i < boss.hp) 1.2f else 0.5f))
        }

        // Ransomware-Countdown
        if (boss.countdown >= 0f) {
            val secs = boss.countdown.toInt()
            val text = "DECRYPT IN " + (secs / 60).toString().padStart(2, '0') + ":" + (secs % 60).toString().padStart(2, '0')
            val urgent = boss.countdown < 10f
            if (!urgent || (time * 4f).toInt() % 2 == 0) {
                DotFont.draw(sr, text, w / 2f, by - u * 7f, u * 0.55f, if (urgent) Palette.CORE else Palette.SIGNAL, DotFont.CENTER)
            }
        }

        // Zero-Day-Exploit
        boss.exploit?.let {
            DotFont.draw(sr, "EXPLOIT // $it", w / 2f, by - u * 6f, small, Palette.SIGNAL, DotFont.CENTER)
        }

        // Adware-Pop-ups
        for (p in boss.popups) {
            val pw = u * 34f
            val ph = u * 15f
            val px = (w * p.x - pw / 2f).coerceIn(u, w - pw - u)
            val py = (h * p.y - ph / 2f).coerceIn(u, h - ph - u)
            sr.color = Palette.VOID
            sr.rect(px, py, pw, ph)
            sr.color = Palette.CORE
            sr.rect(px, py + ph - u * 3f, pw, u * 3f)
            sr.rectLine(px, py, px + pw, py, u * 0.3f)
            sr.rectLine(px, py, px, py + ph, u * 0.3f)
            sr.rectLine(px + pw, py, px + pw, py + ph, u * 0.3f)
            DotFont.draw(sr, "AD", px + u, py + ph - u * 2.4f, u * 0.25f, Palette.VOID)
            DotFont.draw(sr, "X", px + pw - u, py + ph - u * 2.4f, u * 0.25f, Palette.VOID, DotFont.RIGHT)
            val tp = min(u * 0.6f, (pw - u * 2f) / (p.text.length * 6f))
            DotFont.draw(sr, p.text, px + pw / 2f, py + ph * 0.32f, tp, Palette.SIGNAL, DotFont.CENTER)
        }
    }

    private fun drawStatus(sr: com.badlogic.gdx.graphics.glutils.ShapeRenderer, w: Float, u: Float, small: Float, top: Float) {
        // Integrity (oben links)
        DotFont.draw(sr, "INTEGRITY", u * 4f, top, small, Palette.a(Palette.SIGNAL, 0.6f))
        for (i in 0 until Phys.MAX_INTEGRITY) {
            val on = i < world.integrity
            sr.color = if (on) Palette.CORE else Palette.DIM
            sr.circle(u * 5f + i * u * 3.2f, top - u * 3f, if (on) u * 1.1f else u * 0.5f, 12)
        }

        // Shards (oben rechts, links vom Pause-Button)
        val shardText = "SHARD " + world.shardsCollected + "/3"
        DotFont.draw(sr, shardText, w - u * 16f, top, small, Palette.a(Palette.SIGNAL, 0.6f), DotFont.RIGHT)
        for (i in 0 until 3) {
            val on = world.shardMask and (1 shl i) != 0
            sr.color = if (on) Palette.SIGNAL else Palette.DIM
            val cx = w - u * 16f - i * u * 3f - u
            sr.circle(cx, top - u * 3f, if (on) u * 0.9f else u * 0.45f, 4)
        }
    }

    private fun overlay(
        sr: com.badlogic.gdx.graphics.glutils.ShapeRenderer, w: Float, h: Float, u: Float,
        title: String, sub: String, color: com.badlogic.gdx.graphics.Color,
    ) {
        sr.color = Palette.a(Palette.VOID, 0.75f)
        sr.rect(0f, h * 0.35f, w, h * 0.3f)
        sr.color = Palette.CORE
        sr.rect(0f, h * 0.65f - u * 0.4f, w, u * 0.4f)
        sr.rect(0f, h * 0.35f, w, u * 0.4f)
        DotFont.draw(sr, title, w / 2f, h * 0.5f, u * 1.3f, color, DotFont.CENTER)
        DotFont.draw(sr, sub, w / 2f, h * 0.41f, u * 0.5f, Palette.a(Palette.SIGNAL, 0.8f), DotFont.CENTER)
    }

    // ------------------------------------------------------------------ WorldEvents

    override fun onJump() = Sfx.play("jump", 0.5f, MathUtils.random(0.95f, 1.05f))

    override fun onStomp() {
        Sfx.play("stomp", 0.8f)
        Haptics.pulse(35)
        glitch = 0.35f
        shake = 0.6f
    }

    override fun onHit() {
        Sfx.play("hit", 0.8f)
        Haptics.pulse(140)
        glitch = 1f
        shake = 1f
    }

    override fun onShard() {
        Sfx.play("shard", 0.7f)
        Haptics.pulse(15)
    }

    override fun onGravity() {
        Sfx.play("gravity", 0.7f)
        Haptics.pulse(45)
        glitch = 0.55f
    }

    override fun onTerminal() {
        Sfx.play("terminal", 0.8f)
        Haptics.pulse(60)
        glitch = 0.7f
        shake = 0.5f
    }

    override fun onExit() {
        Sfx.play("win", 0.8f)
        Haptics.pulse(70)
        glitch = 0.6f
        doneTime = 0f
        Save.completeLevel(level.id, levelIndex, world.shardMask)
    }

    override fun onCheckpoint() {
        Sfx.play("tick", 0.8f)
        Haptics.pulse(25)
        showAlert("CHECKPOINT SAVED")
    }

    override fun onBossHit() {
        Sfx.play("stomp", 1f, 0.7f)
        Sfx.play("hit", 0.5f, 1.4f)
        Haptics.pulse(80)
        glitch = 0.9f
        shake = 1.2f
    }

    override fun onBossDown() {
        Sfx.play("segfault", 0.9f, 1.3f)
        Haptics.pulse(250)
        glitch = 1f
        shake = 2f
        showAlert("THREAT NEUTRALIZED // EXIT OPEN")
    }

    override fun onBossAlert(text: String) {
        Sfx.play("terminal", 0.4f, 1.5f)
        glitch = maxOf(glitch, 0.4f)
        showAlert(text)
    }

    private fun showAlert(text: String) {
        alert = text
        alertTime = 1.8f
    }

    override fun onSegfault() {
        Sfx.play("segfault", 0.8f)
        Haptics.pulse(300)
        glitch = 1f
        segfaultTime = 0f
    }

    override fun dispose() {
        world.dispose()
    }
}
