package dev.corebreach.world

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.ContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener
import com.badlogic.gdx.physics.box2d.FixtureDef
import com.badlogic.gdx.physics.box2d.Manifold
import com.badlogic.gdx.physics.box2d.PolygonShape
import com.badlogic.gdx.physics.box2d.Shape
import com.badlogic.gdx.physics.box2d.World
import dev.corebreach.level.LevelData
import dev.corebreach.level.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Physik-Konstanten. 1 Einheit = 1 Meter = 1 Tile. */
object Phys {
    const val STEP = 1f / 60f
    const val GRAVITY = 20f
    const val CORE_RADIUS = 0.4f
    const val TORQUE = 2.2f
    const val GROUND_ASSIST = 3.0f
    const val AIR_FORCE = 4.2f
    const val MAX_SPEED = 8.0f
    const val MAX_SPIN = 22f
    const val JUMP_VELOCITY = 10f
    const val STOMP_BOUNCE = 8.5f
    const val COYOTE = 0.10f
    const val JUMP_BUFFER = 0.12f
    const val DAEMON_SPEED = 1.7f
    const val INVULNERABLE = 1.4f
    const val GRAVITY_COOLDOWN = 0.9f
    const val MAX_INTEGRITY = 3
}

enum class Tag { CORE, SOLID, DAEMON, SPIKE, SHARD, GRAVITY, TERMINAL, FIREWALL, EXIT, CHECKPOINT, BOSS, GRAVITY_RESET }

/** Alles, was im Level lebt. (x, y) = Tile-Ursprung unten links. */
class Entity(val tag: Tag, val x: Int, val y: Int) {
    var body: Body? = null
    var alive = true
    var deathTime = -1f
    var used = false

    // Daemon
    var dir = 1f
    var minX = 0f
    var maxX = 0f
    var groundDir = -1      // -1 = steht auf Boden, +1 = haengt an Decke

    // Spike: Richtung, in die die Spitze zeigt
    var spikeDir = 0        // 0 hoch, 1 runter, 2 links, 3 rechts

    // Shard
    var shardIndex = -1

    /** Letzte bekannte Position (bleibt nach dem Zerstoeren des Bodys fuer Effekte erhalten). */
    var lastX = x + 0.5f
    var lastY = y + 0.5f

    val cx: Float get() = body?.position?.x ?: lastX
    val cy: Float get() = body?.position?.y ?: lastY
}

/** Steuerungszustand pro Frame. */
interface ControlState {
    val left: Boolean
    val right: Boolean
    val jumpHeld: Boolean
    val jumpPressed: Boolean
}

interface WorldEvents {
    fun onJump() {}
    fun onStomp() {}
    fun onHit() {}
    fun onShard() {}
    fun onGravity() {}
    fun onTerminal() {}
    fun onExit() {}
    fun onSegfault() {}
    fun onCheckpoint() {}
    fun onBossHit() {}
    fun onBossDown() {}
    fun onBossAlert(text: String) {}
}

class GameWorld(val level: LevelData, private val events: WorldEvents) : ContactListener {

    val world = World(Vector2(0f, -Phys.GRAVITY), true)
    val entities = ArrayList<Entity>()
    lateinit var core: Body
        private set

    private val spawn = Vector2()
    private val coreEntity = Entity(Tag.CORE, 0, 0)

    /** +1 = Gravitation nach unten, -1 = nach oben. */
    var gravSign = 1f
        private set
    var integrity = Phys.MAX_INTEGRITY
        private set
    var shardMask = 0
        private set
    val shardsCollected: Int get() = Integer.bitCount(shardMask)
    var completed = false
        private set
    var dead = false
        private set
    var invulnerable = 0f
        private set
    var time = 0f
        private set
    var grounded = false
        private set

    /** Boss dieses Levels (nur Arena-Level), null sonst. */
    var boss: Boss? = null
        private set

    /** Letzte horizontale Eingabe (-1, 0, 1) - die Spyware liest mit. */
    var inputDir = 0f
        private set

    /** Zero-Day-Exploit: links/rechts vertauscht. */
    var inputInverted = false

    /** Letzte Positionen fuer den Trail-Effekt. */
    val trail = ArrayDeque<Vector2>()

    private var accumulator = 0f
    private var coyote = 0f
    private var jumpBuffer = 0f
    private var gravityCooldown = 0f
    private var jumping = false

    private val pending = ArrayList<() -> Unit>()
    private val toDestroy = LinkedHashSet<Entity>()
    private val tmp = Vector2()

    init {
        world.setContactListener(this)
        buildSolids()
        buildEntities()
        core = createCore(spawn.x, spawn.y)
        level.boss?.let { type ->
            level.forEachTile { x, y, c -> if (c == Tile.BOSS) boss = createBoss(type, x, y) }
        }
    }

    // ------------------------------------------------------------------ build

    private fun buildSolids() {
        val solidEntity = Entity(Tag.SOLID, 0, 0)
        val body = world.createBody(BodyDef().apply { type = BodyDef.BodyType.StaticBody })
        solidEntity.body = body

        // Greedy-Merge: Tiles zu moeglichst grossen Rechtecken zusammenfassen,
        // damit der Ball nicht an Tile-Kanten haengen bleibt.
        val w = level.width
        val h = level.height
        val used = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!level.isSolid(x, y) || used[y][x]) { x++; continue }
                var runW = 0
                while (x + runW < w && level.isSolid(x + runW, y) && !used[y][x + runW]) runW++
                var runH = 1
                grow@ while (y + runH < h) {
                    for (i in x until x + runW) {
                        if (!level.isSolid(i, y + runH) || used[y + runH][i]) break@grow
                    }
                    runH++
                }
                for (j in y until y + runH) for (i in x until x + runW) used[j][i] = true

                val shape = PolygonShape()
                shape.setAsBox(runW / 2f, runH / 2f, tmp.set(x + runW / 2f, y + runH / 2f), 0f)
                body.createFixture(FixtureDef().apply {
                    this.shape = shape
                    friction = 0.9f
                }).userData = solidEntity
                shape.dispose()
                x += runW
            }
        }
    }

    private fun buildEntities() {
        var shardIdx = 0
        level.forEachTile { x, y, c ->
            when (c) {
                Tile.SPAWN -> spawn.set(x + 0.5f, y + Phys.CORE_RADIUS + 0.02f)
                Tile.DAEMON -> entities += createDaemon(x, y)
                Tile.SPIKE -> entities += createSpike(x, y)
                Tile.SHARD -> entities += createSensor(Tag.SHARD, x, y, circle(0.3f)).also { it.shardIndex = shardIdx++ }
                Tile.GRAVITY -> entities += createSensor(Tag.GRAVITY, x, y, circle(0.38f))
                Tile.TERMINAL -> entities += createSensor(Tag.TERMINAL, x, y, box(0.4f, 0.4f))
                Tile.EXIT -> entities += createSensor(Tag.EXIT, x, y, box(0.3f, 0.45f))
                Tile.FIREWALL -> entities += createFirewall(x, y)
                Tile.CHECKPOINT -> entities += createSensor(Tag.CHECKPOINT, x, y, box(0.3f, 0.45f))
                Tile.GRAVITY_RESET -> entities += createSensor(Tag.GRAVITY_RESET, x, y, box(0.15f, 0.5f))
            }
        }
    }

    private fun circle(r: Float): Shape = CircleShape().apply { radius = r }
    private fun box(hw: Float, hh: Float): Shape = PolygonShape().apply { setAsBox(hw, hh) }

    private fun createSensor(tag: Tag, x: Int, y: Int, shape: Shape, cx: Float = x + 0.5f, cy: Float = y + 0.5f): Entity {
        val e = Entity(tag, x, y)
        val body = world.createBody(BodyDef().apply {
            type = BodyDef.BodyType.StaticBody
            position.set(cx, cy)
        })
        body.createFixture(FixtureDef().apply {
            this.shape = shape
            isSensor = true
        }).userData = e
        shape.dispose()
        e.body = body
        return e
    }

    private fun createSpike(x: Int, y: Int): Entity {
        val dir = when {
            level.isSolid(x, y - 1) -> 0
            level.isSolid(x, y + 1) -> 1
            level.isSolid(x + 1, y) -> 2
            else -> 3
        }
        // Sensor sitzt auf der "Basis"-Seite des Tiles
        val (ox, oy, hw, hh) = when (dir) {
            0 -> arrayOf(0f, -0.2f, 0.38f, 0.25f)
            1 -> arrayOf(0f, 0.2f, 0.38f, 0.25f)
            2 -> arrayOf(0.2f, 0f, 0.25f, 0.38f)
            else -> arrayOf(-0.2f, 0f, 0.25f, 0.38f)
        }
        return createSensor(Tag.SPIKE, x, y, box(hw, hh), x + 0.5f + ox, y + 0.5f + oy).also { it.spikeDir = dir }
    }

    private fun createFirewall(x: Int, y: Int): Entity {
        val e = Entity(Tag.FIREWALL, x, y)
        val body = world.createBody(BodyDef().apply {
            type = BodyDef.BodyType.StaticBody
            position.set(x + 0.5f, y + 0.5f)
        })
        val shape = PolygonShape().apply { setAsBox(0.5f, 0.5f) }
        body.createFixture(FixtureDef().apply {
            this.shape = shape
            friction = 0.9f
        }).userData = e
        shape.dispose()
        e.body = body
        return e
    }

    private fun blocksDaemon(x: Int, y: Int): Boolean {
        val c = level.at(x, y)
        return c == Tile.SOLID || c == Tile.FIREWALL || c == Tile.SPIKE
    }

    private fun createDaemon(x: Int, y: Int): Entity {
        val e = Entity(Tag.DAEMON, x, y)
        e.groundDir = if (!level.isSolid(x, y - 1) && level.isSolid(x, y + 1)) 1 else -1
        val gy = y + e.groundDir

        var x0 = x
        while (!blocksDaemon(x0 - 1, y) && level.isSolid(x0 - 1, gy)) x0--
        var x1 = x
        while (!blocksDaemon(x1 + 1, y) && level.isSolid(x1 + 1, gy)) x1++
        e.minX = x0 + 0.5f
        e.maxX = x1 + 0.5f
        e.dir = if ((x + y) % 2 == 0) 1f else -1f

        val half = 0.4f
        val body = world.createBody(BodyDef().apply {
            type = BodyDef.BodyType.KinematicBody
            position.set(x + 0.5f, if (e.groundDir < 0) y + half else y + 1f - half)
        })
        val shape = PolygonShape().apply { setAsBox(half, half) }
        body.createFixture(FixtureDef().apply {
            this.shape = shape
            friction = 0.6f
        }).userData = e
        shape.dispose()
        e.body = body
        return e
    }

    private fun createBoss(bossType: BossType, x: Int, y: Int): Boss {
        val e = Entity(Tag.BOSS, x, y)
        val body = world.createBody(BodyDef().apply {
            type = BodyDef.BodyType.KinematicBody
            position.set(x + 0.5f, y + Boss.HALF)
        })
        val shape = PolygonShape().apply { setAsBox(Boss.HALF, Boss.HALF) }
        body.createFixture(FixtureDef().apply {
            this.shape = shape
            friction = 0.6f
        }).userData = e
        shape.dispose()
        e.body = body
        entities += e
        return Boss(bossType, e, this)
    }

    /** Daemon zur Laufzeit erzeugen (Worm-Kopien, Botnet-Bots). Nur ausserhalb von world.step() aufrufen. */
    fun spawnDaemon(x: Int, y: Int): Entity? {
        if (level.isSolid(x, y) || !level.isSolid(x, y - 1)) return null
        return createDaemon(x, y).also { entities += it }
    }

    fun killEntity(e: Entity) = kill(e)

    fun bossAlert(text: String) = events.onBossAlert(text)

    fun bossDefeated(b: Boss) {
        kill(b.entity)
        events.onBossDown()
    }

    /** Sofortiger Absturz (z.B. Ransomware-Countdown abgelaufen). */
    fun forceSegfault() {
        if (completed || dead) return
        integrity = 0
        dead = true
        core.setLinearVelocity(0f, 0f)
        events.onSegfault()
    }

    private fun createCore(x: Float, y: Float): Body {
        val body = world.createBody(BodyDef().apply {
            type = BodyDef.BodyType.DynamicBody
            position.set(x, y)
            bullet = true
            angularDamping = 0.5f
        })
        val shape = CircleShape().apply { radius = Phys.CORE_RADIUS }
        body.createFixture(FixtureDef().apply {
            this.shape = shape
            density = 1f
            friction = 0.95f
            restitution = 0.08f
        }).userData = coreEntity
        shape.dispose()
        coreEntity.body = body
        return body
    }

    // ------------------------------------------------------------------ update

    fun update(delta: Float, input: ControlState) {
        if (input.jumpPressed) jumpBuffer = Phys.JUMP_BUFFER
        accumulator += min(delta, 0.25f)
        while (accumulator >= Phys.STEP) {
            step(input)
            accumulator -= Phys.STEP
        }
    }

    private fun step(input: ControlState) {
        val dt = Phys.STEP
        time += dt
        invulnerable = max(0f, invulnerable - dt)
        gravityCooldown = max(0f, gravityCooldown - dt)
        jumpBuffer = max(0f, jumpBuffer - dt)

        if (!completed && !dead) control(input, dt)

        world.step(dt, 8, 3)

        // Aktionen aus den Contact-Callbacks erst nach dem Step ausfuehren
        if (pending.isNotEmpty()) {
            val copy = ArrayList(pending)
            pending.clear()
            copy.forEach { it() }
        }
        if (toDestroy.isNotEmpty()) {
            for (e in toDestroy) {
                e.body?.let {
                    e.lastX = it.position.x
                    e.lastY = it.position.y
                    world.destroyBody(it)
                }
                e.body = null
            }
            toDestroy.clear()
        }

        updateDaemons()
        if (!completed && !dead) boss?.update(dt)

        for (e in entities) if (!e.alive && e.deathTime >= 0f) e.deathTime += dt

        // Aus der Welt gefallen?
        val p = core.position
        if (p.x < -3 || p.x > level.width + 3 || p.y < -3 || p.y > level.height + 3) {
            damage(respawn = true)
        }

        trail.addFirst(Vector2(p))
        while (trail.size > 10) trail.removeLast()
    }

    private fun control(input: ControlState, dt: Float) {
        grounded = checkGrounded()
        coyote = if (grounded) Phys.COYOTE else max(0f, coyote - dt)

        var dir = (if (input.right) 1f else 0f) - (if (input.left) 1f else 0f)
        if (inputInverted) dir = -dir
        inputDir = dir
        val v = core.linearVelocity

        if (dir != 0f) {
            core.angularDamping = 0.3f
            // Rollen: Drehmoment. An der Decke dreht sich die Rollrichtung um.
            if (abs(core.angularVelocity) < Phys.MAX_SPIN || sign(core.angularVelocity) == dir * gravSign) {
                core.applyTorque(-dir * gravSign * Phys.TORQUE, true)
            }
            val force = if (grounded) Phys.GROUND_ASSIST else Phys.AIR_FORCE
            if (v.x * dir < Phys.MAX_SPEED) core.applyForceToCenter(dir * force, 0f, true)
        } else {
            core.angularDamping = if (grounded) 3.5f else 0.5f
        }

        if (abs(v.x) > Phys.MAX_SPEED) core.setLinearVelocity(sign(v.x) * Phys.MAX_SPEED, v.y)

        if (jumpBuffer > 0f && coyote > 0f) {
            core.setLinearVelocity(core.linearVelocity.x, Phys.JUMP_VELOCITY * gravSign)
            jumpBuffer = 0f
            coyote = 0f
            jumping = true
            events.onJump()
        }
        // Variable Sprunghoehe: Taste frueh loslassen = kuerzerer Sprung (nur echte Spruenge)
        val vy = core.linearVelocity.y * gravSign
        if (vy <= 0f) jumping = false
        if (jumping && !input.jumpHeld && vy > 3f) {
            core.setLinearVelocity(core.linearVelocity.x, core.linearVelocity.y * 0.86f)
        }
    }

    /** Steht der Core auf etwas (relativ zur aktuellen Gravitation)? */
    private fun checkGrounded(): Boolean {
        val contacts = world.contactList
        for (i in 0 until contacts.size) {
            val c = contacts[i]
            if (!c.isTouching) continue
            val fa = c.fixtureA
            val fb = c.fixtureB
            if (fa.isSensor || fb.isSensor) continue
            val ea = fa.userData as? Entity ?: continue
            val eb = fb.userData as? Entity ?: continue
            val coreIsA = ea.tag == Tag.CORE
            if (!coreIsA && eb.tag != Tag.CORE) continue
            val n = c.worldManifold.normal
            val ny = if (coreIsA) n.y else -n.y   // Richtung Core -> Gegenstueck
            if (ny * -gravSign > 0.5f) return true
        }
        return false
    }

    private fun updateDaemons() {
        for (e in entities) {
            if (e.tag != Tag.DAEMON || !e.alive) continue
            val b = e.body ?: continue
            if (e.maxX - e.minX < 0.1f) {
                b.setLinearVelocity(0f, 0f); continue
            }
            val x = b.position.x
            if (x >= e.maxX) e.dir = -1f
            if (x <= e.minX) e.dir = 1f
            b.setLinearVelocity(e.dir * Phys.DAEMON_SPEED * level.speed, 0f)
        }
    }

    // ------------------------------------------------------------------ gameplay

    private fun damage(respawn: Boolean) {
        if (completed || dead) return
        if (invulnerable <= 0f) {
            integrity--
            invulnerable = Phys.INVULNERABLE
            events.onHit()
            if (integrity <= 0) {
                dead = true
                core.setLinearVelocity(0f, 0f)
                events.onSegfault()
                return
            }
        }
        if (respawn) respawnCore()
    }

    private fun respawnCore() {
        if (gravSign < 0f) setGravity(1f)
        core.setTransform(spawn, 0f)
        core.setLinearVelocity(0f, 0f)
        core.angularVelocity = 0f
        trail.clear()
    }

    private fun setGravity(sign: Float) {
        gravSign = sign
        world.gravity = tmp.set(0f, -Phys.GRAVITY * gravSign)
        core.isAwake = true
    }

    private fun kill(e: Entity) {
        if (!e.alive) return
        e.alive = false
        e.deathTime = 0f
        toDestroy += e
    }

    private fun openFirewalls(terminal: Entity) {
        // Naechste Firewall-Spalte rechts vom Terminal
        val column = entities
            .filter { it.tag == Tag.FIREWALL && it.alive && it.x > terminal.x }
            .minOfOrNull { it.x } ?: return
        entities.filter { it.tag == Tag.FIREWALL && it.alive && it.x == column }.forEach { kill(it) }
    }

    // ------------------------------------------------------------------ contacts

    override fun beginContact(contact: Contact) {
        val ea = contact.fixtureA.userData as? Entity ?: return
        val eb = contact.fixtureB.userData as? Entity ?: return
        val coreIsA = ea.tag == Tag.CORE
        val other = when {
            coreIsA -> eb
            eb.tag == Tag.CORE -> ea
            else -> return
        }
        if (completed || dead || !other.alive) return

        when (other.tag) {
            Tag.SPIKE -> pending += { damage(respawn = true) }

            Tag.SHARD -> {
                kill(other)
                shardMask = shardMask or (1 shl other.shardIndex)
                events.onShard()
            }

            Tag.GRAVITY -> if (gravityCooldown <= 0f) {
                gravityCooldown = Phys.GRAVITY_COOLDOWN
                pending += {
                    setGravity(-gravSign)
                    events.onGravity()
                }
            }

            Tag.TERMINAL -> if (!other.used) {
                other.used = true
                pending += {
                    openFirewalls(other)
                    boss?.onTerminal()
                    events.onTerminal()
                }
            }

            // Reset-Feld: setzt die Gravitation immer auf normal (schaltet nicht um)
            Tag.GRAVITY_RESET -> if (gravSign < 0f) {
                gravityCooldown = Phys.GRAVITY_COOLDOWN
                pending += {
                    setGravity(1f)
                    events.onGravity()
                }
            }

            Tag.CHECKPOINT -> if (!other.used) {
                other.used = true
                spawn.set(other.x + 0.5f, other.y + Phys.CORE_RADIUS + 0.02f)
                events.onCheckpoint()
            }

            Tag.BOSS -> {
                val b = boss ?: return
                if (!b.solid) return
                val n = contact.worldManifold.normal
                val nx = if (coreIsA) n.x else -n.x
                val ny = if (coreIsA) n.y else -n.y
                if (ny < -0.55f) {
                    // Von oben: Stomp, auch wenn er gerade nicht verwundbar ist -> abprallen
                    pending += {
                        core.setLinearVelocity(core.linearVelocity.x, Phys.STOMP_BOUNCE)
                        if (b.stomp()) events.onBossHit() else events.onStomp()
                    }
                } else {
                    val away = if (abs(nx) > 0.01f) -sign(nx) else -b.dir
                    pending += {
                        if (invulnerable <= 0f) core.setLinearVelocity(away * 7f, 5f * gravSign)
                        damage(respawn = false)
                    }
                }
            }

            Tag.EXIT -> {
                if (boss?.defeated == false) return
                completed = true
                pending += {
                    core.setLinearVelocity(0f, 0f)
                    core.angularVelocity = 0f
                    core.gravityScale = 0f
                    events.onExit()
                }
            }

            Tag.DAEMON -> {
                val n = contact.worldManifold.normal
                val nx = if (coreIsA) n.x else -n.x  // Richtung Core -> Daemon
                val ny = if (coreIsA) n.y else -n.y
                // Stomp = Core trifft den Daemon von der "freien" Seite (gegenueber seinem Boden)
                val stomp = ny * other.groundDir > 0.55f
                if (stomp) {
                    kill(other)
                    pending += {
                        val bounceDir = -other.groundDir.toFloat()
                        core.setLinearVelocity(core.linearVelocity.x, Phys.STOMP_BOUNCE * bounceDir)
                        events.onStomp()
                    }
                } else {
                    val away = if (abs(nx) > 0.01f) -sign(nx) else MathUtils.randomSign().toFloat()
                    pending += {
                        if (invulnerable <= 0f) {
                            core.setLinearVelocity(away * 6f, 5f * gravSign)
                        }
                        damage(respawn = false)
                    }
                }
            }

            else -> {}
        }
    }

    override fun endContact(contact: Contact) {}
    override fun preSolve(contact: Contact, oldManifold: Manifold) {}
    override fun postSolve(contact: Contact, impulse: ContactImpulse) {}

    fun dispose() {
        world.dispose()
    }
}
