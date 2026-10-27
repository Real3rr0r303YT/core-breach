package dev.corebreach.world

import com.badlogic.gdx.math.MathUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Bausteine des Boss-Verhaltens. Ein Boss kombiniert mehrere davon, der Endboss wechselt sie pro Phase. */
enum class Trait {
    COUNTDOWN,  // Ransomware: Timer laeuft ab -> Dateisystem verschluesselt -> SEGFAULT
    DISGUISE,   // Trojaner: tarnt sich als Data-Shard und schlaegt zu, wenn man nahe kommt
    MIRROR,     // Spyware: bewegt sich gespiegelt zu deinen Eingaben
    EXFIL,      // Spyware: klaut zeitweise das HUD
    REPLICATE,  // Worm: kopiert sich als Daemons ins Netz
    HIDDEN,     // Rootkit: unsichtbar + unverwundbar, bis ein Scan oder Terminal-Hack es aufdeckt
    POPUPS,     // Adware: Pop-ups verdecken den Bildschirm
    BOTS,       // Botnet: CNC-Knoten schwebt unerreichbar, solange seine Bots leben
    EXPLOITS,   // Zero-Day: zufaellige Exploits (Steuerung invertiert, Speed-Surge, Teleport)
    CHASE,      // verfolgt CORE-0
    REGEN,      // APT: heilt sich, wenn man nicht dranbleibt
    REVIVE,     // APT: steht einmal wieder auf (Persistence-Modul)
}

enum class BossType(
    val title: String,
    val tagline: String,
    /** Lebenspunkte pro Phase */
    val hp: Int,
    val speed: Float,
    val phases: List<Set<Trait>>,
    val countdown: Float = 0f,
) {
    RANSOMWARE("RANSOMWARE", "PAY OR LOSE YOUR FILES", 3, 2.0f, listOf(setOf(Trait.COUNTDOWN)), countdown = 60f),
    TROJAN("TROJAN.DLL", "TRUSTED SYSTEM FILE", 3, 3.4f, listOf(setOf(Trait.DISGUISE))),
    SPYWARE("SPYWARE", "WATCHING YOUR INPUT", 4, 3.6f, listOf(setOf(Trait.MIRROR, Trait.EXFIL))),
    WORM("WORM", "SELF-REPLICATING", 3, 2.6f, listOf(setOf(Trait.REPLICATE))),
    ROOTKIT("ROOTKIT", "HIDDEN IN THE KERNEL", 3, 2.2f, listOf(setOf(Trait.HIDDEN, Trait.CHASE))),
    ADWARE("ADWARE", "YOU HAVE WON!!!", 4, 2.4f, listOf(setOf(Trait.POPUPS))),
    BOTNET("BOTNET.CNC", "10000 INFECTED NODES", 3, 2.0f, listOf(setOf(Trait.BOTS))),
    ZERO_DAY("ZERO-DAY", "NO PATCH AVAILABLE", 4, 2.8f, listOf(setOf(Trait.EXPLOITS))),
    APT("APT.GHOST", "ADVANCED PERSISTENT THREAT", 4, 2.4f, listOf(setOf(Trait.CHASE, Trait.REGEN, Trait.REVIVE))),
    OMNIGRID(
        "OMNIGRID.EXE", "THE NETWORK ITSELF", 3, 2.6f,
        listOf(
            setOf(Trait.COUNTDOWN, Trait.REPLICATE),
            setOf(Trait.MIRROR, Trait.POPUPS),
            setOf(Trait.EXPLOITS, Trait.CHASE, Trait.REGEN),
        ),
        countdown = 75f,
    );

    /** Boss jeder 10. Stufe: Level 10 -> RANSOMWARE ... Level 100 -> OMNIGRID */
    companion object {
        fun forLevel(id: Int): BossType? = if (id % 10 == 0) entries.getOrNull(id / 10 - 1) else null
    }
}

class Popup(val x: Float, val y: Float, val text: String, var ttl: Float)

class Boss(val type: BossType, val entity: Entity, private val gw: GameWorld) {

    companion object {
        const val HALF = 0.8f
        private const val HOVER = 3.6f
        private val ADS = listOf("YOU WON!!!", "FREE RAM", "CLICK HERE", "HOT DAEMONS", "SPEED X10", "1 NEW VIRUS")
    }

    var phase = 0
        private set
    var hp = type.hp
        private set
    val maxHp: Int get() = type.hp
    val totalPhases: Int get() = type.phases.size
    val traits: Set<Trait> get() = type.phases[phase]

    var defeated = false
        private set

    /** Unverwundbarkeit nach einem Treffer (Blinken) */
    var hurt = 0f
        private set

    /** Ransomware-Timer in Sekunden, < 0 = aus */
    var countdown = if (Trait.COUNTDOWN in traits) type.countdown else -1f
        private set

    var disguised = Trait.DISGUISE in traits
        private set
    var hidden = Trait.HIDDEN in traits
        private set
    var exfiltrating = 0f
        private set
    val popups = ArrayList<Popup>()

    /** Name des aktiven Zero-Day-Exploits fuer das HUD */
    var exploit: String? = null
        private set

    var dir = 1f
        private set

    private var minX = 0f
    private var maxX = 0f
    private val floorY: Float
    private var attack = 0f
    private var exposed = 0f
    private var scan = 6f
    private var exfilTimer = 5f
    private var replicate = 3f
    private var popupTimer = 1.5f
    private var exploitTimer = 4f
    private var exploitLeft = 0f
    private var surge = 1f
    private var regenTimer = 0f
    private var revived = false
    private var botsUp = false
    private var offline = 0f
    private val minions = ArrayList<Entity>()

    init {
        val x = entity.x
        val y = entity.y
        var x0 = x
        while (!gw.level.isSolid(x0 - 1, y) && gw.level.isSolid(x0 - 1, y - 1)) x0--
        var x1 = x
        while (!gw.level.isSolid(x1 + 1, y) && gw.level.isSolid(x1 + 1, y - 1)) x1++
        minX = x0 + HALF
        maxX = x1 + 1f - HALF
        floorY = y + HALF
        applyPhase()
    }

    /** Kann gerade per Stomp getroffen werden? */
    val vulnerable: Boolean
        get() = !defeated && hurt <= 0f && !disguised && !hidden && !(Trait.BOTS in traits && offline <= 0f)

    /** Beruehrung schadet / blockiert nur, wenn der Boss materiell ist. */
    val solid: Boolean get() = !defeated && !hidden && !disguised

    val bossX: Float get() = entity.cx
    val bossY: Float get() = entity.cy

    private fun applyPhase() {
        val t = traits
        if (Trait.COUNTDOWN in t && countdown < 0f) countdown = type.countdown
        if (Trait.COUNTDOWN !in t) countdown = -1f
        disguised = Trait.DISGUISE in t
        hidden = Trait.HIDDEN in t
        if (Trait.POPUPS !in t) popups.clear()
        if (Trait.EXPLOITS !in t) clearExploit()
        entity.body?.fixtureList?.forEach { it.isSensor = hidden || disguised }
    }

    fun update(dt: Float) {
        if (defeated) return
        val body = entity.body ?: return
        val t = traits
        hurt = max(0f, hurt - dt)
        val core = gw.core.position
        var speed = type.speed * surge * (if (revived) 1.5f else 1f)
        var vx = dir * speed
        var targetY = floorY

        if (Trait.COUNTDOWN in t) {
            countdown -= dt
            if (countdown <= 0f) {
                countdown = 0f
                gw.bossAlert("FILES ENCRYPTED")
                gw.forceSegfault()
                return
            }
        }

        if (Trait.DISGUISE in t) {
            if (disguised) {
                vx = 0f
                if (abs(core.x - body.position.x) < 2.6f && abs(core.y - body.position.y) < 2.5f) {
                    disguised = false
                    attack = 4f
                    setSensor(false)
                    gw.bossAlert("TROJAN DETECTED")
                }
            } else {
                attack -= dt
                vx = sign(core.x - body.position.x) * speed
                if (attack <= 0f) disguiseAgain()
            }
        }

        if (Trait.MIRROR in t) vx = -gw.inputDir * speed

        if (Trait.EXFIL in t) {
            if (exfiltrating > 0f) {
                exfiltrating -= dt
            } else {
                exfilTimer -= dt
                if (exfilTimer <= 0f) {
                    exfilTimer = 7f
                    exfiltrating = 3f
                    gw.bossAlert("UI EXFILTRATED")
                }
            }
        }

        if (Trait.REPLICATE in t) {
            replicate -= dt
            minions.removeAll { !it.alive }
            if (replicate <= 0f) {
                replicate = 4.5f
                if (minions.size < 4) {
                    gw.spawnDaemon(body.position.x.toInt(), entity.y)?.let {
                        minions += it
                        gw.bossAlert("REPLICATING")
                    }
                }
            }
        }

        if (Trait.HIDDEN in t) {
            if (exposed > 0f) {
                exposed -= dt
                if (exposed <= 0f) setHidden(true)
            } else {
                scan -= dt
                if (scan <= 0f) {
                    scan = 9f
                    expose(3.5f, "KERNEL SCAN: ROOTKIT FOUND")
                }
            }
        }

        if (Trait.CHASE in t && !disguised) {
            val dx = core.x - body.position.x
            vx = if (abs(dx) > 0.3f) sign(dx) * speed else 0f
        }

        if (Trait.POPUPS in t) {
            popupTimer -= dt
            popups.forEach { it.ttl -= dt }
            popups.removeAll { it.ttl <= 0f }
            if (popupTimer <= 0f) {
                popupTimer = 3.5f
                if (popups.size < 4) {
                    popups += Popup(MathUtils.random(0.12f, 0.88f), MathUtils.random(0.25f, 0.75f), ADS.random(), 6f)
                }
            }
        }

        if (Trait.BOTS in t) {
            minions.removeAll { !it.alive }
            if (!botsUp && offline <= 0f) spawnBots()
            if (botsUp && minions.isEmpty()) {
                botsUp = false
                offline = 6f
                gw.bossAlert("CNC OFFLINE")
            }
            if (offline > 0f) {
                offline -= dt
                vx = 0f
                if (offline <= 0f) gw.bossAlert("BOTS REDEPLOYED")
            } else {
                targetY = floorY + HOVER
            }
        }

        if (Trait.EXPLOITS in t) {
            if (exploitLeft > 0f) {
                exploitLeft -= dt
                if (exploitLeft <= 0f) clearExploit()
            } else {
                exploitTimer -= dt
                if (exploitTimer <= 0f) {
                    exploitTimer = 5f
                    launchExploit()
                }
            }
        }

        if (Trait.REGEN in t) {
            regenTimer += dt
            if (regenTimer > 7f && hp < maxHp) {
                regenTimer = 0f
                hp++
                gw.bossAlert("REGENERATING")
            }
        }

        // Patrouillen-Grenzen
        val x = body.position.x
        if (x >= maxX && vx > 0f) { vx = 0f; dir = -1f }
        if (x <= minX && vx < 0f) { vx = 0f; dir = 1f }
        if (vx != 0f) dir = sign(vx)
        val vy = (targetY - body.position.y) * 4f
        body.setLinearVelocity(vx, vy)
    }

    /** Stomp von oben. Liefert true, wenn der Boss Schaden genommen hat. */
    fun stomp(): Boolean {
        if (!vulnerable) return false
        hp--
        hurt = 0.9f
        regenTimer = 0f
        if (countdown >= 0f) countdown = min(99f, countdown + 8f)
        if (Trait.DISGUISE in traits && hp > 0) disguiseAgain()
        if (hp > 0) return true

        when {
            Trait.REVIVE in traits && !revived -> {
                revived = true
                hp = 3
                gw.bossAlert("PERSISTENCE MODULE LOADED")
            }
            phase + 1 < totalPhases -> {
                phase++
                hp = maxHp
                hurt = 1.6f
                killMinions()
                applyPhase()
                gw.bossAlert("PHASE " + (phase + 1) + "/" + totalPhases)
            }
            else -> {
                defeated = true
                killMinions()
                popups.clear()
                clearExploit()
                countdown = -1f
                gw.bossDefeated(this)
            }
        }
        return true
    }

    /** Terminal-Hack in der Arena deckt ein Rootkit auf. */
    fun onTerminal() {
        if (Trait.HIDDEN in traits) expose(6f, "ROOTKIT EXPOSED")
    }

    private fun expose(time: Float, msg: String) {
        exposed = time
        setHidden(false)
        gw.bossAlert(msg)
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        setSensor(h)
    }

    private fun setSensor(s: Boolean) {
        entity.body?.fixtureList?.forEach { it.isSensor = s }
    }

    private fun disguiseAgain() {
        disguised = true
        setSensor(true)
        val body = entity.body ?: return
        // Neues Versteck moeglichst weit weg vom Spieler
        val core = gw.core.position.x
        val nx = if (core < (minX + maxX) / 2f) MathUtils.random((minX + maxX) / 2f, maxX) else MathUtils.random(minX, (minX + maxX) / 2f)
        body.setTransform(nx, floorY, 0f)
    }

    private fun spawnBots() {
        botsUp = true
        val step = (maxX - minX) / 5f
        for (i in 1..4) {
            gw.spawnDaemon((minX + step * i).toInt(), entity.y)?.let { minions += it }
        }
    }

    private fun launchExploit() {
        when (MathUtils.random(2)) {
            0 -> { gw.inputInverted = true; exploit = "INPUT INVERTED" }
            1 -> { surge = 2.2f; exploit = "SPEED SURGE" }
            else -> {
                exploit = "BLINK"
                val core = gw.core.position.x
                val nx = (core + (if (MathUtils.randomBoolean()) 4f else -4f)).coerceIn(minX, maxX)
                entity.body?.setTransform(nx, entity.body!!.position.y, 0f)
            }
        }
        exploitLeft = 3.5f
        gw.bossAlert("EXPLOIT: " + exploit)
    }

    private fun clearExploit() {
        gw.inputInverted = false
        surge = 1f
        exploit = null
        exploitLeft = 0f
    }

    private fun killMinions() {
        minions.forEach { gw.killEntity(it) }
        minions.clear()
        botsUp = false
        offline = 0f
    }
}
