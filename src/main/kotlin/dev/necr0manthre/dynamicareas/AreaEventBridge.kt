package dev.necr0manthre.dynamicareas

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class AreaEventBridge(
    private val runtime: DynamicAreaRuntime,
    private val listenerCommandExecutor: ListenerCommandExecutor,
) : Listener {
    private val lastPlayerAreas: MutableMap<UUID, Set<String>> = hashMapOf()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        val clicked = event.clickedBlock ?: return
        val areaIds = runtime.resolveAreasAtBlock(clicked.location)
        if (areaIds.isEmpty()) {
            return
        }

        when (runtime.aggregateInteractions(areaIds)) {
            TriState.ALLOW -> event.isCancelled = false
            TriState.DENY -> event.isCancelled = true
            TriState.IGNORE -> Unit
        }

        val ctx = mutableMapOf<String, String>()
        ctx["player_name"] = event.player.name
        ctx["world"] = clicked.world.name
        ctx["x"] = clicked.x.toString()
        ctx["y"] = clicked.y.toString()
        ctx["z"] = clicked.z.toString()
        areaIds.forEach { areaId ->
            listenerCommandExecutor.execute(areaId, runtime.listeners(areaId, AreaEventType.ON_INTERACT), ctx)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockBreak(event: BlockBreakEvent) {
        val areaIds = runtime.resolveAreasAtBlock(event.block.location)
        if (areaIds.isEmpty()) {
            return
        }
        when (runtime.aggregateBlockBreaking(areaIds)) {
            TriState.ALLOW -> event.isCancelled = false
            TriState.DENY -> event.isCancelled = true
            TriState.IGNORE -> Unit
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastPlayerAreas.remove(event.player.uniqueId)
    }

    fun tickPlayers(players: Collection<Player>) {
        players.forEach { player ->
            val current = runtime.resolveAreasAtBlock(player.location)
            val previous = lastPlayerAreas[player.uniqueId].orEmpty()
            val entered = current - previous
            val left = previous - current

            if (entered.isNotEmpty()) {
                val ctx = mapOf(
                    "player_name" to player.name,
                    "world" to player.world.name,
                    "x" to player.location.blockX.toString(),
                    "y" to player.location.blockY.toString(),
                    "z" to player.location.blockZ.toString(),
                )
                entered.forEach { areaId ->
                    listenerCommandExecutor.execute(areaId, runtime.listeners(areaId, AreaEventType.ON_PLAYER_ENTER), ctx)
                }
            }
            if (left.isNotEmpty()) {
                val ctx = mapOf(
                    "player_name" to player.name,
                )
                left.forEach { areaId ->
                    listenerCommandExecutor.execute(areaId, runtime.listeners(areaId, AreaEventType.ON_PLAYER_LEAVE), ctx)
                }
            }

            if (current.isEmpty()) {
                lastPlayerAreas.remove(player.uniqueId)
            } else {
                lastPlayerAreas[player.uniqueId] = current
            }
        }
    }

    fun clearPlayerCache() {
        lastPlayerAreas.clear()
    }
}

