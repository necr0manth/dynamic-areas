package dev.necr0manthre.dynamicareas

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.sqrt

class ZoneVisualizer(private val runtime: DynamicAreaRuntime) {

    // player UUID -> area filter (null = all areas)
    private val visualizing: MutableMap<UUID, String?> = hashMapOf()

    /**
     * Toggles visualization for the player.
     * Returns true if visualization was enabled, false if disabled.
     * If already visualizing with a different filter, updates the filter instead.
     */
    fun toggle(player: Player, areaId: String?): Boolean {
        val existing = visualizing[player.uniqueId]
        return if (existing == null) {
            visualizing[player.uniqueId] = areaId
            true
        } else if (existing == areaId) {
            visualizing.remove(player.uniqueId)
            false
        } else {
            visualizing[player.uniqueId] = areaId
            true
        }
    }

    fun removePlayer(player: Player) {
        visualizing.remove(player.uniqueId)
    }

    fun tick(players: Collection<Player>) {
        if (visualizing.isEmpty()) return

        for (player in players) {
            if (!visualizing.containsKey(player.uniqueId)) continue
            val areaFilter = visualizing[player.uniqueId]
            val boxes = if (areaFilter != null) runtime.getActiveBoxesByArea(areaFilter)
                        else runtime.getAllActiveBoxes()
            if (boxes.isEmpty()) continue

            val loc = player.location
            val px = loc.x; val py = loc.y; val pz = loc.z
            val worldId = player.world.uid

            for (box in boxes) {
                if (box.key.worldId != worldId) continue
                if (!boxInRange(box.absolute, px, py, pz)) continue
                val color = areaColor(box.key.areaId)
                spawnBoxOutline(player, box.absolute, px, py, pz, color)
            }
        }
    }

    // ---- private helpers ----

    private fun boxInRange(box: IntBox, px: Double, py: Double, pz: Double): Boolean {
        // Distance from player to closest point on box
        val cx = px.coerceIn(box.min.x.toDouble(), box.max.x + 1.0)
        val cy = py.coerceIn(box.min.y.toDouble(), box.max.y + 1.0)
        val cz = pz.coerceIn(box.min.z.toDouble(), box.max.z + 1.0)
        val dx = cx - px; val dy = cy - py; val dz = cz - pz
        return dx * dx + dy * dy + dz * dz <= RENDER_RADIUS_SQ
    }

    private fun spawnBoxOutline(
        player: Player, box: IntBox,
        px: Double, py: Double, pz: Double,
        color: Color,
    ) {
        val x1 = box.min.x.toDouble()
        val y1 = box.min.y.toDouble()
        val z1 = box.min.z.toDouble()
        val x2 = box.max.x + 1.0
        val y2 = box.max.y + 1.0
        val z2 = box.max.z + 1.0
        val dust = Particle.DustOptions(color, 1.2f)

        // 12 edges: bottom face, top face, 4 verticals
        spawnEdge(player, x1, y1, z1, x2, y1, z1, dust, px, py, pz)
        spawnEdge(player, x2, y1, z1, x2, y1, z2, dust, px, py, pz)
        spawnEdge(player, x2, y1, z2, x1, y1, z2, dust, px, py, pz)
        spawnEdge(player, x1, y1, z2, x1, y1, z1, dust, px, py, pz)
        spawnEdge(player, x1, y2, z1, x2, y2, z1, dust, px, py, pz)
        spawnEdge(player, x2, y2, z1, x2, y2, z2, dust, px, py, pz)
        spawnEdge(player, x2, y2, z2, x1, y2, z2, dust, px, py, pz)
        spawnEdge(player, x1, y2, z2, x1, y2, z1, dust, px, py, pz)
        spawnEdge(player, x1, y1, z1, x1, y2, z1, dust, px, py, pz)
        spawnEdge(player, x2, y1, z1, x2, y2, z1, dust, px, py, pz)
        spawnEdge(player, x2, y1, z2, x2, y2, z2, dust, px, py, pz)
        spawnEdge(player, x1, y1, z2, x1, y2, z2, dust, px, py, pz)
    }

    private fun spawnEdge(
        player: Player,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        dust: Particle.DustOptions,
        px: Double, py: Double, pz: Double,
    ) {
        val dx = x2 - x1; val dy = y2 - y1; val dz = z2 - z1
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01) return
        val steps = (len / PARTICLE_SPACING).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = x1 + dx * t
            val y = y1 + dy * t
            val z = z1 + dz * t
            val ex = x - px; val ey = y - py; val ez = z - pz
            if (ex * ex + ey * ey + ez * ez > RENDER_RADIUS_SQ) continue
            player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    private fun areaColor(areaId: String): Color {
        val hash = areaId.hashCode()
        return PALETTE[((hash % PALETTE.size) + PALETTE.size) % PALETTE.size]
    }

    companion object {
        const val RENDER_RADIUS = 48.0
        private const val RENDER_RADIUS_SQ = RENDER_RADIUS * RENDER_RADIUS
        private const val PARTICLE_SPACING = 0.75

        private val PALETTE = listOf(
            Color.fromRGB(0x00, 0xFF, 0x44),  // green
            Color.fromRGB(0x00, 0xAA, 0xFF),  // cyan
            Color.fromRGB(0xFF, 0xAA, 0x00),  // orange
            Color.fromRGB(0xFF, 0x44, 0x44),  // red
            Color.fromRGB(0xAA, 0x44, 0xFF),  // purple
            Color.fromRGB(0xFF, 0xFF, 0x00),  // yellow
        )
    }
}
