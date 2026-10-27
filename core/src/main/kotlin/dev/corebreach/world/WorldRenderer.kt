package dev.corebreach.world

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import dev.corebreach.ui.DotFont
import dev.corebreach.ui.Palette
import kotlin.math.max
import kotlin.math.min

/** Zeichnet die Spielwelt komplett mit ShapeRenderer (keine Texturen). */
class WorldRenderer(private val sr: ShapeRenderer) {

    fun render(gw: GameWorld, cam: OrthographicCamera) {
        val t = gw.time
        val lv = gw.level
        val halfW = cam.viewportWidth * cam.zoom / 2f
        val halfH = cam.viewportHeight * cam.zoom / 2f
        val x0 = max(0, (cam.position.x - halfW).toInt() - 1)
        val x1 = min(lv.width - 1, (cam.position.x + halfW).toInt() + 1)
        val y0 = max(0, (cam.position.y - halfH).toInt() - 1)
        val y1 = min(lv.height - 1, (cam.position.y + halfH).toInt() + 1)

        sr.projectionMatrix = cam.combined
        sr.begin(ShapeRenderer.ShapeType.Filled)

        // Hintergrund-Raster
        sr.color = Palette.GRID
        for (y in y0..y1) for (x in x0..x1) {
            if (!lv.isSolid(x, y)) sr.circle(x.toFloat(), y.toFloat(), 0.035f, 4)
        }

        // Solide Bloecke: Dot-Fuellung + weisse Kontur nur an offenen Kanten
        for (y in y0..y1) for (x in x0..x1) {
            if (!lv.isSolid(x, y)) continue
            sr.color = Palette.RASTER
            sr.circle(x + 0.25f, y + 0.25f, 0.05f, 4)
            sr.circle(x + 0.75f, y + 0.25f, 0.05f, 4)
            sr.circle(x + 0.25f, y + 0.75f, 0.05f, 4)
            sr.circle(x + 0.75f, y + 0.75f, 0.05f, 4)
        }
        sr.color = Palette.SIGNAL
        val lw = 0.055f
        for (y in y0..y1) for (x in x0..x1) {
            if (!lv.isSolid(x, y)) continue
            val fx = x.toFloat()
            val fy = y.toFloat()
            if (!lv.isSolid(x, y + 1)) sr.rectLine(fx, fy + 1f, fx + 1f, fy + 1f, lw)
            if (!lv.isSolid(x, y - 1)) sr.rectLine(fx, fy, fx + 1f, fy, lw)
            if (!lv.isSolid(x - 1, y)) sr.rectLine(fx, fy, fx, fy + 1f, lw)
            if (!lv.isSolid(x + 1, y)) sr.rectLine(fx + 1f, fy, fx + 1f, fy + 1f, lw)
        }

        for (e in gw.entities) {
            if (e.tag != Tag.BOSS && (e.x < x0 - 1 || e.x > x1 + 1)) continue
            when (e.tag) {
                Tag.SPIKE -> drawSpike(e, t)
                Tag.SHARD -> drawShard(e, t)
                Tag.GRAVITY -> drawGravity(e, gw.gravSign, t)
                Tag.TERMINAL -> drawTerminal(e, t)
                Tag.FIREWALL -> drawFirewall(e, t)
                Tag.EXIT -> drawExit(e, t, gw.boss?.defeated == false)
                Tag.DAEMON -> drawDaemon(e, t)
                Tag.CHECKPOINT -> drawCheckpoint(e, t)
                Tag.GRAVITY_RESET -> drawReset(e, gw.gravSign, t)
                Tag.BOSS -> gw.boss?.let { drawBoss(it, gw, t) }
                else -> {}
            }
        }

        drawCore(gw, t)
        sr.end()
    }

    private fun drawCore(gw: GameWorld, t: Float) {
        if (gw.invulnerable > 0f && ((t * 14f).toInt() % 2 == 0)) return
        val p = gw.core.position
        val r = Phys.CORE_RADIUS

        // Trail
        gw.trail.forEachIndexed { i, v ->
            val a = 0.35f * (1f - i / gw.trail.size.toFloat())
            sr.color = Palette.a(Palette.CORE, a)
            sr.circle(v.x, v.y, r * (0.75f - i * 0.05f), 12)
        }

        // Puls-Glow
        val pulse = 0.5f + 0.5f * MathUtils.sin(t * MathUtils.PI2 * 1.2f)
        val speed = gw.core.linearVelocity.len()
        for (i in 4 downTo 1) {
            sr.color = Palette.a(Palette.CORE, 0.07f + 0.03f * pulse)
            sr.circle(p.x, p.y, r + i * (0.09f + 0.03f * pulse + speed * 0.006f), 24)
        }
        sr.color = Palette.CORE
        sr.circle(p.x, p.y, r, 24)

        // Rotations-Dot
        val ang = gw.core.angle
        sr.color = Palette.SIGNAL
        sr.circle(p.x + MathUtils.cos(ang) * r * 0.62f, p.y + MathUtils.sin(ang) * r * 0.62f, 0.065f, 8)
        sr.color = Palette.a(Palette.SIGNAL, 0.45f)
        sr.circle(p.x - MathUtils.cos(ang) * r * 0.62f, p.y - MathUtils.sin(ang) * r * 0.62f, 0.04f, 8)
    }

    private fun drawDaemon(e: Entity, t: Float) {
        if (!e.alive) {
            // Zerfall in Pixel
            val d = e.deathTime
            if (d < 0f || d > 0.6f) return
            val a = 1f - d / 0.6f
            sr.color = Palette.a(Palette.DAEMON, a)
            for (k in 0 until 10) {
                val ang = k * 36f * MathUtils.degreesToRadians + 0.3f
                val dist = d * (2.2f + (k % 3) * 0.8f)
                val s = 0.14f
                sr.rect(e.cx + MathUtils.cos(ang) * dist - s / 2, e.cy + MathUtils.sin(ang) * dist - s / 2, s, s)
            }
            sr.color = Palette.a(Palette.CORE, a)
            sr.circle(e.cx, e.cy, 0.5f * d / 0.6f + 0.05f, 12)
            return
        }
        val h = 0.4f
        val cx = e.cx
        val cy = e.cy
        val bob = MathUtils.sin(t * 6f + e.x) * 0.02f
        sr.color = Palette.DAEMON
        sr.rect(cx - h, cy - h + bob, h * 2, h * 2)
        sr.color = Palette.VOID
        sr.rect(cx - h + 0.09f, cy - h + 0.09f + bob, h * 2 - 0.18f, h * 2 - 0.18f)
        // 3x3 Dot-Muster
        sr.color = Palette.DIM
        for (i in -1..1) for (j in -1..1) sr.circle(cx + i * 0.2f, cy + j * 0.2f + bob, 0.04f, 4)
        // Rotes "Auge" schaut in Laufrichtung, weg vom Boden
        sr.color = Palette.CORE
        sr.circle(cx + e.dir * 0.17f, cy - e.groundDir * 0.12f + bob, 0.085f, 8)
    }

    private fun drawSpike(e: Entity, t: Float) {
        val flicker = 0.75f + 0.25f * MathUtils.sin(t * 22f + e.x * 1.7f)
        sr.color = Palette.a(Palette.CORE, flicker)
        for (k in 0..1) {
            val a = k * 0.5f
            tri(e, a + 0.05f, 0f, a + 0.45f, 0f, a + 0.25f, 0.62f)
        }
    }

    /** Dreieck in lokalen Tile-Koordinaten (u nach rechts, v Richtung Spitze). */
    private fun tri(e: Entity, u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float) {
        fun wx(u: Float, v: Float) = e.x + when (e.spikeDir) {
            0 -> u; 1 -> u; 2 -> 1f - v; else -> v
        }
        fun wy(u: Float, v: Float) = e.y + when (e.spikeDir) {
            0 -> v; 1 -> 1f - v; else -> u
        }
        sr.triangle(wx(u1, v1), wy(u1, v1), wx(u2, v2), wy(u2, v2), wx(u3, v3), wy(u3, v3))
    }

    private fun drawShard(e: Entity, t: Float) {
        if (!e.alive) {
            val d = e.deathTime
            if (d < 0f || d > 0.4f) return
            sr.color = Palette.a(Palette.SIGNAL, 1f - d / 0.4f)
            val r = 0.2f + d * 2.5f
            for (k in 0 until 8) {
                val ang = k * 45f * MathUtils.degreesToRadians
                sr.circle(e.cx + MathUtils.cos(ang) * r, e.cy + MathUtils.sin(ang) * r, 0.05f, 4)
            }
            return
        }
        val cx = e.x + 0.5f
        val cy = e.y + 0.5f + MathUtils.sin(t * 3f + e.x) * 0.08f
        val s = 0.2f + 0.03f * MathUtils.sin(t * 6f)
        sr.color = Palette.a(Palette.SIGNAL, 0.12f)
        sr.circle(cx, cy, 0.38f, 16)
        sr.color = Palette.SIGNAL
        sr.triangle(cx - s, cy, cx + s, cy, cx, cy + s * 1.5f)
        sr.triangle(cx - s, cy, cx + s, cy, cx, cy - s * 1.5f)
        sr.color = Palette.CORE
        sr.circle(cx, cy, 0.045f, 6)
    }

    private fun drawGravity(e: Entity, gravSign: Float, t: Float) {
        val cx = e.x + 0.5f
        val cy = e.y + 0.5f
        val pulse = 0.5f + 0.5f * MathUtils.sin(t * 4f)
        sr.color = Palette.a(Palette.CORE, 0.25f + 0.2f * pulse)
        sr.circle(cx, cy, 0.44f, 20)
        sr.color = Palette.VOID
        sr.circle(cx, cy, 0.36f, 20)
        // Pfeil zeigt, wohin die Gravitation nach dem Schalten zieht
        val d = gravSign  // aktuell unten (+1) -> nach Flip oben
        sr.color = Palette.SIGNAL
        sr.triangle(cx - 0.2f, cy + 0.02f * d, cx + 0.2f, cy + 0.02f * d, cx, cy + 0.26f * d)
        sr.rect(cx - 0.05f, if (d > 0) cy - 0.22f else cy + 0.02f, 0.1f, 0.2f)
    }

    private fun drawTerminal(e: Entity, t: Float) {
        val x = e.x + 0.12f
        val y = e.y + 0.18f
        val w = 0.76f
        val h = 0.6f
        val lw = 0.045f
        sr.color = if (e.used) Palette.DIM else Palette.SIGNAL
        sr.rectLine(x, y, x + w, y, lw)
        sr.rectLine(x, y + h, x + w, y + h, lw)
        sr.rectLine(x, y, x, y + h, lw)
        sr.rectLine(x + w, y, x + w, y + h, lw)
        sr.color = Palette.DIM
        sr.rect(x + 0.1f, y + h - 0.18f, 0.35f, 0.05f)
        sr.rect(x + 0.1f, y + h - 0.3f, 0.22f, 0.05f)
        if (!e.used) {
            if ((t * 3f).toInt() % 2 == 0) {
                sr.color = Palette.CORE
                sr.rect(x + 0.1f, y + 0.1f, 0.14f, 0.16f)
            }
        } else {
            sr.color = Palette.SIGNAL
            sr.rect(x + 0.1f, y + 0.12f, 0.5f, 0.06f)
        }
    }

    private fun drawFirewall(e: Entity, t: Float) {
        val alpha = if (e.alive) 1f else {
            val d = e.deathTime
            if (d < 0f || d > 0.5f) return
            1f - d / 0.5f
        }
        val fx = e.x.toFloat()
        val fy = e.y.toFloat()
        for (k in 0..2) {
            val flick = 0.55f + 0.45f * MathUtils.sin(t * 17f + k * 2.1f + e.y)
            sr.color = Palette.a(Palette.CORE, alpha * flick)
            val off = if (e.alive) 0f else e.deathTime * (k - 1) * 3f
            sr.rect(fx + 0.2f + k * 0.3f - 0.035f + off, fy, 0.07f, 1f)
        }
        sr.color = Palette.a(Palette.CORE, alpha * 0.15f)
        sr.rect(fx, fy, 1f, 1f)
        if ((e.y + (t * 8f).toInt()) % 4 == 0) {
            sr.color = Palette.a(Palette.SIGNAL, alpha * 0.6f)
            sr.rect(fx + 0.1f, fy + 0.48f, 0.8f, 0.04f)
        }
    }

    private fun drawExit(e: Entity, t: Float, locked: Boolean) {
        val cx = e.x + 0.5f
        val cy = e.y + 0.5f
        if (locked) {
            // Gesperrt, solange der Boss lebt
            square(cx, cy, 0.42f, MathUtils.PI / 4f, Palette.DIM, 0.06f)
            sr.color = Palette.a(Palette.CORE, 0.5f + 0.5f * MathUtils.sin(t * 6f))
            sr.rectLine(cx - 0.22f, cy - 0.22f, cx + 0.22f, cy + 0.22f, 0.07f)
            sr.rectLine(cx - 0.22f, cy + 0.22f, cx + 0.22f, cy - 0.22f, 0.07f)
            return
        }
        square(cx, cy, 0.42f, t * 1.4f, Palette.a(Palette.CORE, 1f), 0.06f)
        square(cx, cy, 0.27f, -t * 2.2f, Palette.a(Palette.SIGNAL, 1f), 0.05f)
        val pulse = 0.5f + 0.5f * MathUtils.sin(t * 5f)
        sr.color = Palette.a(Palette.CORE, 0.4f + 0.6f * pulse)
        sr.circle(cx, cy, 0.07f + 0.04f * pulse, 10)
    }

    private fun drawReset(e: Entity, gravSign: Float, t: Float) {
        // Duenne Dot-Saeule, leuchtet auf, solange die Gravitation invertiert ist
        val active = gravSign < 0f
        val x = e.x + 0.5f
        for (k in 0..3) {
            val wave = 0.5f + 0.5f * MathUtils.sin(t * 6f - (e.y * 4 + k) * 0.8f)
            sr.color = if (active) Palette.a(Palette.CORE, 0.3f + 0.6f * wave) else Palette.a(Palette.DIM, 0.5f)
            sr.circle(x, e.y + 0.125f + k * 0.25f, 0.035f, 4)
        }
    }

    private fun drawCheckpoint(e: Entity, t: Float) {
        val x = e.x + 0.5f
        val y = e.y.toFloat()
        sr.color = if (e.used) Palette.SIGNAL else Palette.DIM
        sr.rect(x - 0.03f, y, 0.06f, 0.9f)
        // Fahne aus Dots, weht leicht
        sr.color = if (e.used) Palette.CORE else Palette.DIM
        for (i in 0..3) for (j in 0..2) {
            val wave = MathUtils.sin(t * 5f + i * 0.9f) * 0.04f
            sr.circle(x + 0.08f + i * 0.1f, y + 0.62f + j * 0.1f + wave, 0.035f, 4)
        }
    }

    private fun drawBoss(b: Boss, gw: GameWorld, t: Float) {
        val e = b.entity
        val h = Boss.HALF
        val cx = e.cx
        val cy = e.cy
        if (!e.alive) {
            // Grosse Pixel-Explosion
            val d = e.deathTime
            if (d < 0f || d > 1.2f) return
            val a = 1f - d / 1.2f
            for (k in 0 until 28) {
                val ang = k * (360f / 28f) * MathUtils.degreesToRadians
                val dist = d * (3f + (k % 4) * 1.4f)
                sr.color = Palette.a(if (k % 3 == 0) Palette.CORE else Palette.SIGNAL, a)
                sr.rect(cx + MathUtils.cos(ang) * dist - 0.1f, cy + MathUtils.sin(ang) * dist - 0.1f, 0.2f, 0.2f)
            }
            sr.color = Palette.a(Palette.CORE, a * 0.6f)
            sr.circle(cx, cy, 1.4f * d, 24)
            return
        }
        if (b.disguised) {
            // Trojaner: sieht aus wie ein harmloser Data-Shard ... fast
            val y = cy - h + 0.5f + MathUtils.sin(t * 3f) * 0.08f
            val s = 0.2f + 0.03f * MathUtils.sin(t * 6f)
            sr.color = Palette.a(Palette.SIGNAL, 0.12f)
            sr.circle(cx, y, 0.38f, 16)
            sr.color = if ((t * 0.7f) % 1f < 0.06f) Palette.CORE else Palette.SIGNAL
            sr.triangle(cx - s, y, cx + s, y, cx, y + s * 1.5f)
            sr.triangle(cx - s, y, cx + s, y, cx, y - s * 1.5f)
            return
        }
        if (b.hidden) {
            // Rootkit: nur ein Flimmern im Raster
            if (MathUtils.sin(t * 13f) > 0.6f) {
                sr.color = Palette.a(Palette.SIGNAL, 0.12f)
                for (i in -2..2) for (j in -2..2) sr.circle(cx + i * 0.3f, cy + j * 0.3f, 0.03f, 4)
            }
            return
        }
        if (b.hurt > 0f && (t * 16f).toInt() % 2 == 0) return

        // Aura
        val pulse = 0.5f + 0.5f * MathUtils.sin(t * 4f)
        for (i in 3 downTo 1) {
            sr.color = Palette.a(Palette.CORE, 0.05f + 0.03f * pulse)
            sr.rect(cx - h - i * 0.12f, cy - h - i * 0.12f, (h + i * 0.12f) * 2, (h + i * 0.12f) * 2)
        }
        // Gehaeuse
        sr.color = if (b.vulnerable) Palette.SIGNAL else Palette.DAEMON
        sr.rect(cx - h, cy - h, h * 2, h * 2)
        sr.color = Palette.VOID
        sr.rect(cx - h + 0.1f, cy - h + 0.1f, h * 2 - 0.2f, h * 2 - 0.2f)
        // Schwachstelle oben
        sr.color = Palette.a(Palette.CORE, if (b.vulnerable) 0.6f + 0.4f * pulse else 0.25f)
        sr.rect(cx - h + 0.1f, cy + h - 0.22f, h * 2 - 0.2f, 0.12f)
        // Kuerzel im Dot-Font
        DotFont.draw(sr, code(b.type), cx, cy - 0.15f, 0.085f, Palette.SIGNAL, DotFont.CENTER)
        // Zwei Augen folgen CORE-0
        val p = gw.core.position
        val dx = MathUtils.clamp(p.x - cx, -1f, 1f) * 0.12f
        val dy = MathUtils.clamp(p.y - cy, -1f, 1f) * 0.08f
        sr.color = Palette.CORE
        sr.circle(cx - 0.3f + dx, cy + 0.3f + dy, 0.11f, 10)
        sr.circle(cx + 0.3f + dx, cy + 0.3f + dy, 0.11f, 10)
        // HP-Pips ueber dem Boss
        for (i in 0 until b.maxHp) {
            sr.color = if (i < b.hp) Palette.CORE else Palette.DIM
            sr.circle(cx + (i - (b.maxHp - 1) / 2f) * 0.3f, cy + h + 0.35f, if (i < b.hp) 0.09f else 0.05f, 8)
        }
    }

    private fun code(type: BossType): String = when (type) {
        BossType.RANSOMWARE -> "RSW"
        BossType.TROJAN -> "TRJ"
        BossType.SPYWARE -> "SPY"
        BossType.WORM -> "WRM"
        BossType.ROOTKIT -> "RKT"
        BossType.ADWARE -> "ADW"
        BossType.BOTNET -> "CNC"
        BossType.ZERO_DAY -> "0DY"
        BossType.APT -> "APT"
        BossType.OMNIGRID -> "OMG"
    }

    private fun square(cx: Float, cy: Float, r: Float, rot: Float, color: com.badlogic.gdx.graphics.Color, lw: Float) {
        sr.color = color
        var px = 0f
        var py = 0f
        for (i in 0..4) {
            val a = rot + i * MathUtils.HALF_PI
            val x = cx + MathUtils.cos(a) * r
            val y = cy + MathUtils.sin(a) * r
            if (i > 0) sr.rectLine(px, py, x, y, lw)
            px = x
            py = y
        }
    }
}
