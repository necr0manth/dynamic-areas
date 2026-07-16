package dev.necr0manthre.dynamicareas

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class AreaEventBridge(
    private val runtime: DynamicAreaRuntime,
    private val listenerCommandExecutor: ListenerCommandExecutor,
    private val pivots: MutableMap<UUID, Vec3i>,
    private val zoneVisualizer: ZoneVisualizer,
) : Listener {
    private val lastPlayerAreas: MutableMap<UUID, Set<String>> = hashMapOf()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        // Only handle genuine block interactions; non-interactable blocks are clicked when placing.
        // Also skip when sneaking — sneaking bypasses block interaction in favour of item use.
        @Suppress("DEPRECATION")
        if (!clicked.type.isInteractable || event.player.isSneaking) return

        val areaIds = runtime.resolveAreasAtBlock(clicked.location)
        if (areaIds.isEmpty()) return

        // Use setUseInteractedBlock so we only affect the block interaction,
        // not the item-in-hand use (block placement), which is independent.
        when (runtime.aggregateInteractions(areaIds)) {
            TriState.DENY -> event.setUseInteractedBlock(Event.Result.DENY)
            TriState.ALLOW, TriState.IGNORE -> Unit
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
    fun onBlockPlace(event: BlockPlaceEvent) {
        val areaIds = runtime.resolveAreasAtBlock(event.block.location)
        if (areaIds.isEmpty()) {
            return
        }
        when (runtime.aggregateBlockPlacing(areaIds)) {
            TriState.DENY -> event.isCancelled = true
            TriState.ALLOW, TriState.IGNORE -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockBreak(event: BlockBreakEvent) {
        val areaIds = runtime.resolveAreasAtBlock(event.block.location)
        if (areaIds.isEmpty()) {
            return
        }
        when (runtime.aggregateBlockBreaking(areaIds)) {
            TriState.DENY -> event.isCancelled = true
            TriState.ALLOW, TriState.IGNORE -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { block ->
            val areaIds = runtime.resolveAreasAtBlock(block.location)
            areaIds.isNotEmpty() && runtime.aggregateProtect(areaIds)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { block ->
            val areaIds = runtime.resolveAreasAtBlock(block.location)
            areaIds.isNotEmpty() && runtime.aggregateProtect(areaIds)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val direction = event.direction
        // Check the position the piston head would occupy (handles empty-space extension into a zone)
        val headPos = event.block.getRelative(direction)
        var areaIds = runtime.resolveAreasAtBlock(headPos.location)
        if (areaIds.isNotEmpty() && runtime.aggregateProtect(areaIds)) {
            event.isCancelled = true
            return
        }
        for (block in event.blocks) {
            areaIds = runtime.resolveAreasAtBlock(block.location)
            if (areaIds.isNotEmpty() && runtime.aggregateProtect(areaIds)) {
                event.isCancelled = true
                return
            }
            val destAreaIds = runtime.resolveAreasAtBlock(block.getRelative(direction).location)
            if (destAreaIds.isNotEmpty() && runtime.aggregateProtect(destAreaIds)) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val direction = event.direction
        for (block in event.blocks) {
            var areaIds = runtime.resolveAreasAtBlock(block.location)
            if (areaIds.isNotEmpty() && runtime.aggregateProtect(areaIds)) {
                event.isCancelled = true
                return
            }
            val destAreaIds = runtime.resolveAreasAtBlock(block.getRelative(direction.oppositeFace).location)
            if (destAreaIds.isNotEmpty() && runtime.aggregateProtect(destAreaIds)) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val areaIds = runtime.resolveAreasAtBlock(event.block.location)
        if (areaIds.isNotEmpty() && runtime.aggregateProtect(areaIds)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastPlayerAreas.remove(event.player.uniqueId)
        pivots.remove(event.player.uniqueId)
        zoneVisualizer.removePlayer(event.player)
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

