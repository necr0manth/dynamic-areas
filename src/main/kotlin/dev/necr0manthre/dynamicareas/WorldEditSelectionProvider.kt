package dev.necr0manthre.dynamicareas

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.bukkit.WorldEditPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class WorldEditSelectionProvider {

    fun readPrimaryPosition(player: Player): Vec3i? {
        return try {
            val worldEditPlugin = Bukkit.getPluginManager().getPlugin("WorldEdit") ?: return null
            if (worldEditPlugin !is WorldEditPlugin) return null
            val adaptedPlayer = BukkitAdapter.adapt(player)
            val adaptedWorld = BukkitAdapter.adapt(player.world)

            val worldEditInstance = worldEditPlugin.worldEdit
            val sessionManager = worldEditInstance.sessionManager
            val localSession = sessionManager.get(adaptedPlayer) ?: return null
            val selector = localSession.getRegionSelector(adaptedWorld)
            val primary = selector.primaryPosition

            Vec3i(primary.x(), primary.y(), primary.z())
        } catch (_: Throwable) {
            null
        }
    }

    fun readSelection(player: Player): Pair<Vec3i, Vec3i>? {
        return try {
            val worldEditPlugin = Bukkit.getPluginManager().getPlugin("WorldEdit") ?: return null
            if (worldEditPlugin !is WorldEditPlugin) return null
            val adaptedPlayer = BukkitAdapter.adapt(player)
            val adaptedWorld = BukkitAdapter.adapt(player.world)

            val worldEditInstance = worldEditPlugin.worldEdit
            val sessionManager = worldEditInstance.sessionManager
            val localSession = sessionManager.get(adaptedPlayer) ?: return null

            val selection = localSession.getSelection(adaptedWorld)
                ?: return null

            val min = selection.minimumPoint
            val max = selection.maximumPoint

            val minX = min.x()
            val minY = min.y()
            val minZ = min.z()
            val maxX = max.x()
            val maxY = max.y()
            val maxZ = max.z()

            Vec3i(minX, minY, minZ) to Vec3i(maxX, maxY, maxZ)
        } catch (_: Throwable) {
            null
        }
    }
}



