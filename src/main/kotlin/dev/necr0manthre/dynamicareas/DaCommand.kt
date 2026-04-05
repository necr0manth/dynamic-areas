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
    private val zoneVisualizer: ZoneVisualizer,
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
                .then(buildSaveVector())
                .then(buildVisualize())
                .then(
                    Commands.literal("reload")
                        .executes { ctx ->
                            instance?.handleReload(ctx.source.sender)
                            Command.SINGLE_SUCCESS
                        }
                )
                .build()
        }

        // Adds a "groups <greedy_string>" terminal branch to the given builder node.
        // groupsStr is passed raw to the handler; parsing happens on the instance.
        private fun withGroups(
            parent: com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, *>,
            execute: (ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, groupsStr: String) -> Unit,
        ): com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, *> {
            return parent.then(
                Commands.literal("groups")
                    .then(
                        Commands.argument("groups_str", StringArgumentType.greedyString())
                            .executes { ctx ->
                                execute(ctx, StringArgumentType.getString(ctx, "groups_str"))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
        }

        private fun buildAddBoxToArea(): LiteralCommandNode<CommandSourceStack> {
            val ttlArg = Commands.argument("ttl", IntegerArgumentType.integer(1))
                .executes { ctx ->
                    val boxId = StringArgumentType.getString(ctx, "box_id")
                    val areaId = StringArgumentType.getString(ctx, "area_id")
                    val offset = ctx.getArgument("offset", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
                    val ttl = IntegerArgumentType.getInteger(ctx, "ttl")
                    instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, offset, ttl, "")
                    Command.SINGLE_SUCCESS
                }
            withGroups(ttlArg) { ctx, groupsStr ->
                val boxId = StringArgumentType.getString(ctx, "box_id")
                val areaId = StringArgumentType.getString(ctx, "area_id")
                val offset = ctx.getArgument("offset", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
                val ttl = IntegerArgumentType.getInteger(ctx, "ttl")
                instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, offset, ttl, groupsStr)
            }

            val offsetArg = Commands.argument("offset", ArgumentTypes.blockPosition())
                .executes { ctx ->
                    val boxId = StringArgumentType.getString(ctx, "box_id")
                    val areaId = StringArgumentType.getString(ctx, "area_id")
                    val offset = ctx.getArgument("offset", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
                    instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, offset, null, "")
                    Command.SINGLE_SUCCESS
                }
                .then(ttlArg)
            withGroups(offsetArg) { ctx, groupsStr ->
                val boxId = StringArgumentType.getString(ctx, "box_id")
                val areaId = StringArgumentType.getString(ctx, "area_id")
                val offset = ctx.getArgument("offset", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
                instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, offset, null, groupsStr)
            }

            val areaIdArg = Commands.argument("area_id", StringArgumentType.word())
                .executes { ctx ->
                    val boxId = StringArgumentType.getString(ctx, "box_id")
                    val areaId = StringArgumentType.getString(ctx, "area_id")
                    instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, null, null, "")
                    Command.SINGLE_SUCCESS
                }
                .then(offsetArg)
            withGroups(areaIdArg) { ctx, groupsStr ->
                val boxId = StringArgumentType.getString(ctx, "box_id")
                val areaId = StringArgumentType.getString(ctx, "area_id")
                instance?.handleAddBoxToArea(ctx.source.sender, boxId, areaId, null, null, groupsStr)
            }

            return Commands.literal("addboxtoarea")
                .then(
                    Commands.argument("box_id", StringArgumentType.word())
                        .then(areaIdArg)
                )
                .build()
        }

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

        private fun buildSaveVector() =
            Commands.literal("savevector")
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "name")
                            instance?.handleSaveVector(ctx.source.sender, name, null)
                            Command.SINGLE_SUCCESS
                        }
                        .then(
                            Commands.argument("pos", ArgumentTypes.blockPosition())
                                .executes { ctx ->
                                    val name = StringArgumentType.getString(ctx, "name")
                                    val pos = ctx.getArgument("pos", BlockPositionResolver::class.java)
                                        .resolve(ctx.source).toVec3i()
                                    instance?.handleSaveVector(ctx.source.sender, name, pos)
                                    Command.SINGLE_SUCCESS
                                }
                        )
                )

        private fun buildVisualize() =
            Commands.literal("visualize")
                .executes { ctx ->
                    instance?.handleVisualize(ctx.source.sender, null)
                    Command.SINGLE_SUCCESS
                }
                .then(
                    Commands.argument("area_id", StringArgumentType.word())
                        .executes { ctx ->
                            val areaId = StringArgumentType.getString(ctx, "area_id")
                            instance?.handleVisualize(ctx.source.sender, areaId)
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

    private fun handleAddBoxToArea(sender: CommandSender, boxId: String, areaId: String, offset: Vec3i?, ttlArg: Int?, groupsStr: String) {
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
        val groups = parseGroupsStr(groupsStr, baseOffset, worldId)
        val result = runtime.addOrRefreshBox(areaId, boxId, worldId, baseOffset, ttl, groups)
        result.onSuccess {
            sender.sendMessage("Box '$boxId' added/refreshed in area '$areaId' with ttl=$ttl, groups=${groups.size}")
        }.onFailure {
            sender.sendMessage("Error: ${it.message}")
        }
    }

    // Parses "groups_str" into a set of absolute GroupKeys.
    // Each token group in the string is one of:
    //   x y z          — relative Vec3i (three integers)
    //   <name>         — saved vector by name
    //   inv <name>     — negated saved vector
    // All relative coords are resolved against baseRef to produce absolute positions.
    private fun parseGroupsStr(str: String, baseRef: Vec3i, worldId: UUID): Set<GroupKey> {
        if (str.isBlank()) return emptySet()
        val result = mutableSetOf<GroupKey>()
        val tokens = str.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val rel: Vec3i? = when {
                token.equals("inv", ignoreCase = true) && i + 1 < tokens.size -> {
                    val vec = configStore.vectorsById[tokens[i + 1]]
                    i++
                    vec?.let { Vec3i(-it.x, -it.y, -it.z) }
                }
                token.toIntOrNull() != null
                    && i + 2 < tokens.size
                    && tokens[i + 1].toIntOrNull() != null
                    && tokens[i + 2].toIntOrNull() != null -> {
                    val v = Vec3i(tokens[i].toInt(), tokens[i + 1].toInt(), tokens[i + 2].toInt())
                    i += 2
                    v
                }
                else -> configStore.vectorsById[token]
            }
            if (rel != null) {
                result.add(GroupKey(worldId, Vec3i(baseRef.x + rel.x, baseRef.y + rel.y, baseRef.z + rel.z)))
            }
            // Unknown token: silently skip to avoid crashing command blocks
            i++
        }
        return result
    }

    private fun handleSetPivot(sender: CommandSender, pivot: Vec3i) {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only player can set pivot")
            return
        }
        pivots[player.uniqueId] = pivot
        sender.sendMessage("Pivot set to ${pivot.x} ${pivot.y} ${pivot.z}")
    }

    private fun handleSaveVector(sender: CommandSender, name: String, pos: Vec3i?) {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only player can save vector")
            return
        }
        val target = pos ?: worldEditSelectionProvider.readPrimaryPosition(player) ?: run {
            sender.sendMessage("WorldEdit first position unavailable, provide coordinates explicitly")
            return
        }
        val pivot = pivots[player.uniqueId] ?: Vec3i(0, 0, 0)
        val vec = Vec3i(target.x - pivot.x, target.y - pivot.y, target.z - pivot.z)
        configStore.saveVector(name, vec)
            .onSuccess { sender.sendMessage("Saved vector '$name': ${vec.x},${vec.y},${vec.z}") }
            .onFailure { sender.sendMessage("Failed to save vector: ${it.message}") }
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

    private fun handleVisualize(sender: CommandSender, areaId: String?) {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only a player can use visualize")
            return
        }
        val enabled = zoneVisualizer.toggle(player, areaId)
        if (enabled) {
            val target = if (areaId != null) "area '$areaId'" else "all areas"
            sender.sendMessage("Zone visualization enabled for $target (radius ${ZoneVisualizer.RENDER_RADIUS.toInt()} blocks)")
        } else {
            sender.sendMessage("Zone visualization disabled")
        }
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
