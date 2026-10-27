package dev.corebreach.level

import dev.corebreach.world.BossType

object Tile {
    const val SOLID = '#'
    const val EMPTY = '.'
    const val SPAWN = 'P'
    const val EXIT = 'X'
    const val DAEMON = 'D'
    const val SPIKE = '^'
    const val SHARD = 'S'
    const val GRAVITY = 'G'
    const val TERMINAL = 'T'
    const val FIREWALL = 'F'
    const val CHECKPOINT = 'C'
    const val BOSS = 'B'
    const val GRAVITY_RESET = 'R'

    const val ALL = "#.PXD^SGTFCBR"
}

/** Die 10 Welten der Kampagne, je 10 Level, das 10. ist immer ein Boss. */
object Worlds {
    val names = listOf(
        "BOOTLOADER", "USERSPACE", "FILESYSTEM", "NETSTACK", "KERNEL",
        "BROWSER", "CLOUD", "FIRMWARE", "MAINFRAME", "OMNIGRID",
    )
    const val LEVELS_PER_WORLD = 10
}

/**
 * Ein Level im ASCII-Format. Koordinaten: x nach rechts, y nach oben, (0,0) = unten links.
 *
 * @param speed Multiplikator fuer die Daemon-Geschwindigkeit (Schwierigkeitskurve der Kampagne)
 * @param boss  Boss dieses Levels (nur Arena-Level jeder 10. Stufe)
 */
class LevelData(
    val id: Int,
    val name: String,
    val rows: List<String>,
    val speed: Float = 1f,
    val boss: BossType? = null,
) {
    val height: Int = rows.size
    val width: Int = rows.maxOf { it.length }

    private val grid: Array<CharArray> = Array(height) { r -> rows[r].padEnd(width, Tile.EMPTY).toCharArray() }

    /** Ausserhalb der Karte gilt alles als solide. */
    fun at(x: Int, y: Int): Char {
        if (x < 0 || y < 0 || x >= width || y >= height) return Tile.SOLID
        return grid[height - 1 - y][x]
    }

    fun isSolid(x: Int, y: Int): Boolean = at(x, y) == Tile.SOLID

    fun count(c: Char): Int = rows.sumOf { row -> row.count { it == c } }

    inline fun forEachTile(action: (x: Int, y: Int, c: Char) -> Unit) {
        for (y in 0 until height) for (x in 0 until width) action(x, y, at(x, y))
    }

    /** Welt 1..10 */
    val world: Int get() = (id - 1) / Worlds.LEVELS_PER_WORLD + 1
    val worldName: String get() = Worlds.names[world - 1]

    val label: String get() = "W$world-" + ((id - 1) % Worlds.LEVELS_PER_WORLD + 1).toString().padStart(2, '0')
}
