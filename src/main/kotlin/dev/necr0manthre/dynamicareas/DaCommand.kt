@file:Suppress("UnstableApiUsage")

package dev.necr0manthre.dynamicareas

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.math.BlockPosition
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class DaCommand(
    private val configStore: ConfigStore,
    private val runtime: DynamicAreaRuntime,
    private val areaEventBridge: AreaEventBridge,
    private val pivots: MutableMap<UUID, Vec3i>,
    private val worldEditSelectionProvider: WorldEditSelectionProvider,
) {

    companion object {
        var instance: DaCommand? = null

        fun buildNode(): LiteralCommandNode<CommandSourceStack> {
            return Commands.literal("da")
                .requires { src ->
                    val s = src.sender
                    s is BlockCommandSender || s.isOp || s.hasPermission("dynamicareas.use")
                }
                .then(buildAddBoxToArea())
                .then(buildSetPivot())
                .then(buildSaveBox())
                .then(
                    Commands.literal("reload")
                        .executes { ctx ->
                            instance?.handleReload(ctx.source.sender)
                            Command.SINGLE_SUCCESS
                        }
                )
                .build()
        }

        private fun buildAddBoxToArea() =
            Commands.literal("addboxtoarea")
                .then(
                    Commands.argument("box_id", StringArgumentType.word())
                        .then(
                            Commands.argument("area_id", StringArgumentType.word())
                                .executes { ctx ->
                                    val boxId = StringArgumentType.getString(ctx, "box_id")
                                    val areaId = StringArgumentType.getString(ctx, "area_id")
                                    instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, null, null)
                                    Command.SINGLE_SUCCESS
                                }
                                .then(
                                    Commands.argument("offset", ArgumentTypes.blockPosition())
                                        .executes { ctx ->
                                            val boxId = StringArgumentType.getString(ctx, "box_id")
                                            val areaId = StringArgumentType.getString(ctx, "area_id")
                                            val offset = ctx.getArgument("offset", BlockPositionResolver::class.java)
                                                .resolve(ctx.source).toVec3i()
                                            instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, offset, null)
                                            Command.SINGLE_SUCCESS
                                        }
                                        .then(
                                            Commands.argument("ttl", IntegerArgumentType.integer(1))
                                                .executes { ctx ->
                                                    val boxId = StringArgumentType.getString(ctx, "box_id")
                                                    val areaId = StringArgumentType.getString(ctx, "area_id")
                                                    val offset = ctx.getArgument("offset", BlockPositionResolver::class.java)
                                                        .resolve(ctx.source).toVec3i()
                                                    val ttl = IntegerArgumentType.getInteger(ctx, "ttl")
                                                    instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, offset, ttl)
                                                    Command.SINGLE_SUCCESS
                                                }
                                        )
                                )
                        )
                )

        private fun buildSetPivot() =
            Commands.literal("setpivot")
                .then(
                    Commands.argument("pos", ArgumentTypes.blockPosition())
                        .executes { ctx ->
                            val pivot = ctx.getArgument("pos", BlockPositionResolver::class.java)
                                .resolve(ctx.source).toVec3i()
                            instance?.handleSetPivot(ctx.source.sender, pivot)
                            Command.SINGLE_SUCCESS
                        }
                )

        private fun buildSaveBox() =
            Commands.literal("savebox")
                .then(
                    Commands.argument("box_id", StringArgumentType.word())
                        .executes { ctx ->
                            val boxId = StringArgumentType.getString(ctx, "box_id")
                            instance?.handleSaveBox(ctx.source.sender, boxId, null, null)
                            Command.SINGLE_SUCCESS
                        }
                        .then(
                            Commands.argument("start", ArgumentTypes.blockPosition())
                                .then(
                                    Commands.argument("end", ArgumentTypes.blockPosition())
                                        .executes { ctx ->
                                            val boxId = StringArgumentType.getString(ctx, "box_id")
                                            val start = ctx.getArgument("start", BlockPositionResolver::class.java)
                                                .resolve(ctx.source).toVec3i()
                                            val end = ctx.getArgument("end", BlockPositionResolver::class.java)
                                                .resolve(ctx.source).toVec3i()
                                            instance?.handleSaveBox(ctx.source.sender, boxId, start, end)
                                            Command.SINGLE_SUCCESS
                                        }
                                )
                        )
                )
    }

    private fun handleAddBoxToArea(sender: CommandSender, boxId: String, areaId: String, offset: Vec3i?, ttlArg: Int?) {
        val baseOffset = offset ?: baseOffsetFromSender(sender) ?: run {
            sender.sendMessage("Console must provide offset")
            return
        }

        val worldId = when (sender) {
            is Player -> sender.world.uid
            is BlockCommandSender -> sender.block.world.uid
            else -> {
                sender.sendMessage("Console is not supported for addboxtoarea")
                return
            }
        }

        val ttl = ttlArg ?: 2
        val result = runtime.addOrRefreshBox(areaId, boxId, worldId, baseOffset, ttl)
        result.onSuccess {
            sender.sendMessage("Box '$boxId' added/refreshed in area '$areaId' with ttl=$ttl")
        }.onFailure {
            sender.sendMessage("Error: ${it.message}")
        }
    }

    private fun handleSetPivot(sender: CommandSender, pivot: Vec3i) {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only player can set pivot")
            return
        }
        pivots[player.uniqueId] = pivot
        sender.sendMessage("Pivot set to ${pivot.x} ${pivot.y} ${pivot.z}")
    }

    private fun handleSaveBox(sender: CommandSender, boxId: String, start: Vec3i?, end: Vec3i?) {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only player can save box")
            return
        }

        val (a, b) = if (start != null && end != null) {
            start to end
        } else {
            val selection = runCatching { worldEditSelectionProvider.readSelection(player) }.getOrNull()
            if (selection == null) {
                sender.sendMessage("WorldEdit selection unavailable, provide coordinates explicitly")
                return
            }
            selection
        }

        val pivot = pivots[player.uniqueId] ?: Vec3i(0, 0, 0)
        val minVec = Vec3i(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z))
        val maxVec = Vec3i(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))

        val offset = Vec3i(minVec.x - pivot.x, minVec.y - pivot.y, minVec.z - pivot.z)
        val size = Vec3i(maxVec.x - minVec.x, maxVec.y - minVec.y, maxVec.z - minVec.z)
        val box = BoxTemplate(boxId, offset, size)

        configStore.saveBox(box)
            .onSuccess { sender.sendMessage("Saved box '$boxId' offset=${offset.x},${offset.y},${offset.z} size=${size.x},${size.y},${size.z}") }
            .onFailure { sender.sendMessage("Failed to save box: ${it.message}") }
    }

    private fun handleReload(sender: CommandSender) {
        configStore.reloadAll()
        runtime.clearRuntime()
        areaEventBridge.clearPlayerCache()
        sender.sendMessage("DynamicAreas reloaded. Runtime boxes cleared.")
    }

    private fun baseOffsetFromSender(sender: CommandSender): Vec3i? = when (sender) {
        is Player -> Vec3i(sender.location.blockX, sender.location.blockY, sender.location.blockZ)
        is BlockCommandSender -> Vec3i(sender.block.x, sender.block.y, sender.block.z)
        else -> null
    }
}

private fun BlockPosition.toVec3i() = Vec3i(blockX(), blockY(), blockZ())
