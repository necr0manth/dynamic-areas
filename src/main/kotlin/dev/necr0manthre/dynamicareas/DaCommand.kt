@file:Suppress("UnstableApiUsage")

package dev.necr0manthre.dynamicareas

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.math.BlockPosition
import net.kyori.adventure.text.Component
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

        fun buildNode(): LiteralCommandNode<CommandSourceStack> = Commands.literal("da")
            .requires { source ->
                val sender = source.sender
                sender is BlockCommandSender || sender.isOp || sender.hasPermission("dynamicareas.use")
            }
            .executes { ctx -> instance?.handleHelp(ctx.source.sender); Command.SINGLE_SUCCESS }
            .then(buildAddBoxToArea())
            .then(buildSetPivot())
            .then(buildSetCluster())
            .then(buildSaveBox())
            .then(buildSaveVector())
            .then(buildVisualize())
            .then(buildCluster())
            .then(Commands.literal("help").executes { ctx -> instance?.handleHelp(ctx.source.sender); Command.SINGLE_SUCCESS })
            .then(Commands.literal("reload").executes { ctx -> instance?.handleReload(ctx.source.sender); Command.SINGLE_SUCCESS })
            .build()

        private fun word(name: String, provider: SuggestionProvider<CommandSourceStack>? = null) =
            if (provider == null) Commands.argument(name, StringArgumentType.string())
            else Commands.argument(name, StringArgumentType.string()).suggests(provider)

        private val clusters = SuggestionProvider<CommandSourceStack> { _, builder ->
            suggestIdentifiers(instance?.configStore?.clustersById?.keys.orEmpty(), builder)
        }
        private val boxes = SuggestionProvider<CommandSourceStack> { _, builder ->
            suggestIdentifiers(instance?.configStore?.boxesById?.keys.orEmpty(), builder)
        }
        private val areas = SuggestionProvider<CommandSourceStack> { _, builder ->
            suggestIdentifiers(instance?.configStore?.areasById?.keys.orEmpty(), builder)
        }
        private val vectors = SuggestionProvider<CommandSourceStack> { _, builder ->
            suggestIdentifiers(instance?.configStore?.vectorsById?.keys.orEmpty(), builder)
        }
        private val entries = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val id = runCatching { StringArgumentType.getString(ctx, "cluster") }.getOrNull()
            suggestIdentifiers(instance?.configStore?.clustersById?.get(id)?.entries.orEmpty().map { it.id }, builder)
        }
        private val groups = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val cluster = runCatching { StringArgumentType.getString(ctx, "cluster") }.getOrNull()
            val entry = runCatching { StringArgumentType.getString(ctx, "entry") }.getOrNull()
            val names = instance?.configStore?.clustersById?.get(cluster)?.entries?.firstOrNull { it.id == entry }
                ?.groups.orEmpty().map { it.name }
            suggestIdentifiers(names, builder)
        }

        private fun buildCluster(): LiteralCommandNode<CommandSourceStack> {
            val create = Commands.literal("create").then(word("cluster").executes { ctx ->
                instance?.handleClusterCreate(ctx.source.sender, StringArgumentType.getString(ctx, "cluster")); Command.SINGLE_SUCCESS
            })
            val list = Commands.literal("list").executes { ctx -> instance?.handleClusterList(ctx.source.sender, 1); Command.SINGLE_SUCCESS }
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx -> instance?.handleClusterList(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "page")); Command.SINGLE_SUCCESS })
            val show = Commands.literal("show")
            val showCluster = word("cluster", clusters)
            showCluster.executes { ctx -> instance?.handleClusterShow(ctx.source.sender, ctxCluster(ctx), null, 1); Command.SINGLE_SUCCESS }
            showCluster.then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                instance?.handleClusterShow(ctx.source.sender, ctxCluster(ctx), null, IntegerArgumentType.getInteger(ctx, "page")); Command.SINGLE_SUCCESS
            }))
            val showEntry = word("entry", entries)
            showEntry.executes { ctx -> instance?.handleClusterShow(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), 1); Command.SINGLE_SUCCESS }
            showEntry.then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                instance?.handleClusterShow(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), IntegerArgumentType.getInteger(ctx, "page")); Command.SINGLE_SUCCESS
            }))
            val showDirectEntry = word("entry", entries)
            showDirectEntry.executes { ctx -> instance?.handleClusterShow(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), 1); Command.SINGLE_SUCCESS }
            showDirectEntry.then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { ctx ->
                instance?.handleClusterShow(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), IntegerArgumentType.getInteger(ctx, "page")); Command.SINGLE_SUCCESS
            }))
            showCluster.then(Commands.literal("entry").then(showEntry))
            showCluster.then(showDirectEntry)
            show.then(showCluster)
            val remove = Commands.literal("remove").then(word("cluster", clusters).then(word("entry", entries).executes { ctx -> instance?.handleClusterRemove(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx)); Command.SINGLE_SUCCESS }))
            val delete = Commands.literal("delete").then(word("cluster", clusters).executes { ctx -> instance?.handleClusterDelete(ctx.source.sender, ctxCluster(ctx)); Command.SINGLE_SUCCESS })
            val set = Commands.literal("set")
            val setEntry = word("entry", entries)
            setEntry.then(Commands.literal("box").then(word("box", boxes).executes { ctx ->
                instance?.handleClusterSet(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), "box", StringArgumentType.getString(ctx, "box"), null); Command.SINGLE_SUCCESS
            }))
            setEntry.then(Commands.literal("area").then(word("area", areas).executes { ctx ->
                instance?.handleClusterSet(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), "area", StringArgumentType.getString(ctx, "area"), null); Command.SINGLE_SUCCESS
            }))
            setEntry.then(Commands.literal("offset").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes { ctx ->
                instance?.handleClusterSet(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), "offset", null, Vec3i(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"))); Command.SINGLE_SUCCESS
            }))))
            set.then(word("cluster", clusters).then(setEntry))

            val group = Commands.literal("group")
            val groupAddEntry = word("entry", entries)
            val groupAdd = word("group")
            groupAdd.executes { ctx ->
                instance?.handleGroupAdd(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), StringArgumentType.getString(ctx, "group"), Vec3i(0, 0, 0)); Command.SINGLE_SUCCESS
            }
            groupAdd.then(Commands.literal("offset").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes { ctx ->
                instance?.handleGroupAdd(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), StringArgumentType.getString(ctx, "group"), Vec3i(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"))); Command.SINGLE_SUCCESS
            }))))
            groupAddEntry.then(groupAdd)
            group.then(Commands.literal("add").then(word("cluster", clusters).then(groupAddEntry)))
            val groupRemoveEntry = word("entry", entries)
            val groupRemove = word("group", groups)
            groupRemove.executes { ctx ->
                instance?.handleGroupRemove(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), StringArgumentType.getString(ctx, "group"), Vec3i(0, 0, 0)); Command.SINGLE_SUCCESS
            }
            groupRemove.then(Commands.literal("offset").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes { ctx ->
                val offset = Vec3i(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"))
                instance?.handleGroupRemove(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx), StringArgumentType.getString(ctx, "group"), offset); Command.SINGLE_SUCCESS
            }))))
            groupRemoveEntry.then(groupRemove)
            group.then(Commands.literal("remove").then(word("cluster", clusters).then(groupRemoveEntry)))
            group.then(Commands.literal("clear").then(word("cluster", clusters).then(word("entry", entries).executes { ctx ->
                instance?.handleGroupClear(ctx.source.sender, ctxCluster(ctx), ctxEntry(ctx)); Command.SINGLE_SUCCESS
            })))
            return Commands.literal("cluster")
                .executes { ctx -> instance?.handleClusterHelp(ctx.source.sender, 1); Command.SINGLE_SUCCESS }
                .then(Commands.literal("help")
                    .executes { ctx -> instance?.handleClusterHelp(ctx.source.sender, 1); Command.SINGLE_SUCCESS }
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 3)).executes { ctx ->
                        instance?.handleClusterHelp(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "page")); Command.SINGLE_SUCCESS
                    }))
                .then(create).then(list).then(show).then(remove).then(delete).then(set).then(group).then(buildClusterAdd()).then(buildClusterCapture()).build()
        }

        private fun buildClusterAdd(): LiteralArgumentBuilder<CommandSourceStack> {
            fun invoke(ctx: CommandContext<CommandSourceStack>, capture: Boolean, start: Vec3i? = null, end: Vec3i? = null) {
                val entry = runCatching { StringArgumentType.getString(ctx, "entry") }.getOrNull()
                if (capture) instance?.handleClusterCapture(ctx.source.sender, ctxCluster(ctx), StringArgumentType.getString(ctx, "box"), StringArgumentType.getString(ctx, "area"), entry, start, end)
                else instance?.handleClusterAdd(ctx.source.sender, ctxCluster(ctx), StringArgumentType.getString(ctx, "box"), StringArgumentType.getString(ctx, "area"), entry)
            }
            val add = Commands.literal("add").then(word("cluster", clusters).then(word("box", boxes).then(word("area", areas)
                .executes { ctx -> invoke(ctx, false); Command.SINGLE_SUCCESS }
                .then(Commands.literal("as").then(word("entry").executes { ctx -> invoke(ctx, false); Command.SINGLE_SUCCESS })))))
            return add
        }

        private fun buildClusterCapture(): LiteralArgumentBuilder<CommandSourceStack> {
            fun invoke(ctx: CommandContext<CommandSourceStack>, start: Vec3i? = null, end: Vec3i? = null) {
                val entry = runCatching { StringArgumentType.getString(ctx, "entry") }.getOrNull()
                instance?.handleClusterCapture(ctx.source.sender, ctxCluster(ctx), StringArgumentType.getString(ctx, "box"), StringArgumentType.getString(ctx, "area"), entry, start, end)
            }
            val from = Commands.literal("from").then(Commands.argument("start", ArgumentTypes.blockPosition()).then(Commands.literal("to").then(Commands.argument("end", ArgumentTypes.blockPosition()).executes { ctx ->
                invoke(ctx, ctx.getArgument("start", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i(), ctx.getArgument("end", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()); Command.SINGLE_SUCCESS
            })))
            val asEntry = Commands.literal("as").then(word("entry").executes { ctx -> invoke(ctx); Command.SINGLE_SUCCESS }.then(from))
            return Commands.literal("capture").then(word("cluster", clusters).then(word("box", boxes).then(word("area", areas).executes { ctx -> invoke(ctx); Command.SINGLE_SUCCESS }.then(asEntry).then(from))))
        }

        private fun buildSetPivot() = Commands.literal("setpivot").then(Commands.argument("pos", ArgumentTypes.blockPosition()).executes { ctx -> instance?.handleSetPivot(ctx.source.sender, ctx.getArgument("pos", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()); Command.SINGLE_SUCCESS })

        private fun buildSetCluster(): LiteralCommandNode<CommandSourceStack> {
            fun run(ctx: CommandContext<CommandSourceStack>, pos: Vec3i? = null) {
                val explicit = pos ?: if (ctx.nodes.any { it.node.name == "pos" }) {
                    ctx.getArgument("pos", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
                } else null
                val vectorName = runCatching { StringArgumentType.getString(ctx, "vector") }.getOrNull()
                val vector = if (vectorName == null) null else {
                    val inverted = ctx.nodes.any { it.node.name == "inv" }
                    instance?.resolveSenderVector(ctx.source.sender, vectorName, inverted)
                }
                if (vectorName != null && vector == null) return
                instance?.handleSetCluster(ctx.source.sender, ctxCluster(ctx), explicit ?: vector, runCatching { IntegerArgumentType.getInteger(ctx, "ttl") }.getOrDefault(2))
            }
            val ttl = Commands.argument("ttl", IntegerArgumentType.integer(1)).executes { ctx -> run(ctx, null); Command.SINGLE_SUCCESS }
            val position = Commands.argument("pos", ArgumentTypes.blockPosition()).executes { ctx -> run(ctx, ctx.getArgument("pos", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()); Command.SINGLE_SUCCESS }.then(ttl)
            val vector = word("vector", vectors).executes { ctx -> run(ctx); Command.SINGLE_SUCCESS }.then(ttl)
            val inv = Commands.literal("inv").then(word("vector", vectors).executes { ctx -> run(ctx); Command.SINGLE_SUCCESS }.then(ttl))
            return Commands.literal("setcluster").then(word("cluster", clusters).executes { ctx -> run(ctx, null); Command.SINGLE_SUCCESS }.then(position).then(vector).then(inv)).build()
        }

        private fun buildSaveVector() = Commands.literal("savevector").then(word("name").executes { ctx -> instance?.handleSaveVector(ctx.source.sender, StringArgumentType.getString(ctx, "name"), null); Command.SINGLE_SUCCESS }.then(Commands.argument("pos", ArgumentTypes.blockPosition()).executes { ctx -> instance?.handleSaveVector(ctx.source.sender, StringArgumentType.getString(ctx, "name"), ctx.getArgument("pos", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()); Command.SINGLE_SUCCESS }))
        private fun buildSaveBox() = Commands.literal("savebox").then(word("box_id").executes { ctx -> instance?.handleSaveBox(ctx.source.sender, StringArgumentType.getString(ctx, "box_id"), null, null); Command.SINGLE_SUCCESS }.then(Commands.argument("start", ArgumentTypes.blockPosition()).then(Commands.argument("end", ArgumentTypes.blockPosition()).executes { ctx -> instance?.handleSaveBox(ctx.source.sender, StringArgumentType.getString(ctx, "box_id"), ctx.getArgument("start", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i(), ctx.getArgument("end", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()); Command.SINGLE_SUCCESS })))
        private fun buildVisualize() = Commands.literal("visualize").executes { ctx -> instance?.handleVisualize(ctx.source.sender, null); Command.SINGLE_SUCCESS }.then(word("area_id", areas).executes { ctx -> instance?.handleVisualize(ctx.source.sender, StringArgumentType.getString(ctx, "area_id")); Command.SINGLE_SUCCESS })

        private fun buildAddBoxToArea(): LiteralCommandNode<CommandSourceStack> {
            fun invoke(ctx: CommandContext<CommandSourceStack>, offset: Vec3i? = null, ttl: Int? = null, groups: String = "") {
                val command = instance ?: return
                val named = runCatching { StringArgumentType.getString(ctx, "offset_vec") }.getOrNull()
                val inverted = runCatching { StringArgumentType.getString(ctx, "offset_inv_vec") }.getOrNull()
                if ((named != null && !command.configStore.vectorsById.containsKey(named)) || (inverted != null && !command.configStore.vectorsById.containsKey(inverted))) {
                    command.error(ctx.source.sender, "Unknown vector '${named ?: inverted}'.")
                    return
                }
                command.handleAddBoxToArea(ctx.source.sender, StringArgumentType.getString(ctx, "box_id"), StringArgumentType.getString(ctx, "area_id"), offset ?: command.legacyOffset(ctx), ttl ?: runCatching { IntegerArgumentType.getInteger(ctx, "ttl") }.getOrNull(), groups)
            }
            val groups = fun(parent: ArgumentBuilder<CommandSourceStack, *>): ArgumentBuilder<CommandSourceStack, *> = parent.then(Commands.literal("groups").then(Commands.argument("groups_str", StringArgumentType.greedyString()).executes { ctx -> invoke(ctx, groups = StringArgumentType.getString(ctx, "groups_str")); Command.SINGLE_SUCCESS }))
            val ttl = Commands.argument("ttl", IntegerArgumentType.integer(1)).executes { ctx -> invoke(ctx, ttl = IntegerArgumentType.getInteger(ctx, "ttl")); Command.SINGLE_SUCCESS }
            // Brigadier snapshots a builder when attaching it: finish children first.
            groups(ttl)
            val offset = Commands.argument("offset", ArgumentTypes.blockPosition()).executes { ctx -> invoke(ctx, ctx.getArgument("offset", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i(), null, ""); Command.SINGLE_SUCCESS }.then(ttl)
            val named = word("offset_vec", vectors).executes { ctx -> invoke(ctx); Command.SINGLE_SUCCESS }.then(ttl)
            val invVector = word("offset_inv_vec", vectors).executes { ctx -> invoke(ctx); Command.SINGLE_SUCCESS }.then(ttl)
            groups(offset)
            groups(named)
            groups(invVector)
            val inv = Commands.literal("inv").then(invVector)
            val area = word("area_id", areas).executes { ctx -> invoke(ctx, null, null, ""); Command.SINGLE_SUCCESS }.then(offset).then(named).then(inv)
            groups(area)
            return Commands.literal("addboxtoarea").then(word("box_id", boxes).then(area)).build()
        }

        private fun ctxCluster(ctx: CommandContext<CommandSourceStack>) = StringArgumentType.getString(ctx, "cluster")
        private fun ctxEntry(ctx: CommandContext<CommandSourceStack>) = StringArgumentType.getString(ctx, "entry")
    }

    private fun send(sender: CommandSender, component: Component) = sender.sendMessage(component)
    private fun error(sender: CommandSender, message: String) = send(sender, DaCommandPresentation.error(message))
    private fun success(sender: CommandSender, message: String) = send(sender, DaCommandPresentation.ok(message))

    private fun handleHelp(sender: CommandSender) {
        send(sender, DaCommandPresentation.heading("help"))
        send(sender, DaCommandPresentation.helpLine("/da cluster help", "manage reusable cluster layouts"))
        send(sender, DaCommandPresentation.helpLine("/da setcluster <cluster> [position] [ttl]", "activate a cluster at a pivot"))
        send(sender, DaCommandPresentation.helpLine("/da addboxtoarea <box> <area> [offset] [ttl] [groups ...]", "legacy one-box activation"))
        send(sender, DaCommandPresentation.helpLine("/da setpivot <position>", "set the current player's capture pivot"))
        send(sender, DaCommandPresentation.helpLine("/da savebox <box> [start] [end]", "save an inclusive box"))
        send(sender, DaCommandPresentation.helpLine("/da savevector <name> [position]", "save a vector from the current pivot"))
        send(sender, DaCommandPresentation.helpLine("/da visualize [area]", "toggle particle outlines"))
        send(sender, DaCommandPresentation.helpLine("/da reload", "reload files and clear active boxes"))
    }

    private fun handleClusterHelp(sender: CommandSender, page: Int) {
        ClusterPresentation.help(page).forEach { send(sender, it) }
    }

    private fun handleClusterCreate(sender: CommandSender, id: String) {
        if (configStore.clustersById.containsKey(id)) return error(sender, "Cluster '$id' already exists.")
        configStore.saveCluster(ClusterDefinition(id)).onSuccess { success(sender, "Created cluster '$id'.") }.onFailure { error(sender, it.message ?: "save failed") }
    }

    private fun handleClusterList(sender: CommandSender, page: Int) {
        ClusterPresentation.list(configStore.clustersById.values.toList(), page).forEach { send(sender, it) }
    }

    private fun handleClusterShow(sender: CommandSender, clusterId: String, entryId: String?, page: Int) {
        val cluster = configStore.clustersById[clusterId] ?: return error(sender, "Unknown cluster '$clusterId'.")
        if (entryId != null && cluster.entries.none { it.id == entryId }) {
            return error(sender, "Unknown entry '$entryId'.")
        }
        ClusterPresentation.show(cluster, configStore.boxesById, entryId, page).forEach { send(sender, it) }
    }

    private fun handleClusterAdd(sender: CommandSender, clusterId: String, boxId: String, areaId: String, entryId: String?) {
        val c = configStore.clustersById[clusterId] ?: return error(sender, "Unknown cluster '$clusterId'. Create it first.")
        if (!configStore.boxesById.containsKey(boxId)) return error(sender, "Unknown box '$boxId'."); if (!configStore.areasById.containsKey(areaId)) return error(sender, "Unknown area '$areaId'.")
        val id = entryId?.takeIf(String::isNotBlank) ?: boxId; if (c.entries.any { it.id == id }) return error(sender, "Entry '$id' already exists in cluster '$clusterId'.")
        configStore.saveCluster(c.copy(entries = c.entries + ClusterEntry(id, boxId, areaId))).onSuccess { success(sender, "Added entry '$id' to cluster '$clusterId'.") }.onFailure { error(sender, it.message ?: "save failed") }
    }

    private fun handleClusterCapture(sender: CommandSender, clusterId: String, boxId: String, areaId: String, entryId: String?, start: Vec3i?, end: Vec3i?) {
        val player = sender as? Player ?: return error(sender, "Only a player can capture a cluster box.")
        val old = configStore.clustersById[clusterId] ?: return error(sender, "Unknown cluster '$clusterId'. Create it first.")
        if (configStore.boxesById.containsKey(boxId)) return error(sender, "Box '$boxId' already exists; capture will not overwrite it.")
        if (!configStore.areasById.containsKey(areaId)) return error(sender, "Unknown area '$areaId'.")
        val id = entryId?.takeIf(String::isNotBlank) ?: boxId
        if (old.entries.any { it.id == id }) return error(sender, "Entry '$id' already exists in cluster '$clusterId'.")
        val corners = if (start != null && end != null) {
            start to end
        } else {
            runCatching { worldEditSelectionProvider.readSelection(player) }.getOrNull()
        } ?: return error(sender, "WorldEdit selection unavailable; provide from <start> to <end>.")
        val pivot = pivots[player.uniqueId] ?: return error(sender, "Set a pivot first with /da setpivot <position>.")
        val lo = Vec3i(
            min(corners.first.x, corners.second.x),
            min(corners.first.y, corners.second.y),
            min(corners.first.z, corners.second.z),
        )
        val hi = Vec3i(
            max(corners.first.x, corners.second.x),
            max(corners.first.y, corners.second.y),
            max(corners.first.z, corners.second.z),
        )
        val box = BoxTemplate(
            boxId,
            Vec3i(lo.x - pivot.x, lo.y - pivot.y, lo.z - pivot.z),
            Vec3i(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z),
        )
        val cluster = old.copy(entries = old.entries + ClusterEntry(id, boxId, areaId))
        configStore.saveBoxAndCluster(box, cluster)
            .onSuccess { success(sender, "Captured box '$boxId' and entry '$id' in cluster '$clusterId'.") }
            .onFailure { error(sender, it.message ?: "capture failed; no files changed") }
    }

    private fun handleClusterRemove(sender: CommandSender, clusterId: String, entryId: String) {
        val c = configStore.clustersById[clusterId] ?: return error(sender, "Unknown cluster '$clusterId'."); if (c.entries.none { it.id == entryId }) return error(sender, "Unknown entry '$entryId'.")
        configStore.saveCluster(c.copy(entries = c.entries.filterNot { it.id == entryId })).onSuccess { success(sender, "Removed entry '$entryId'.") }.onFailure { error(sender, it.message ?: "save failed") }
    }
    private fun handleClusterDelete(sender: CommandSender, clusterId: String) {
        if (!configStore.clustersById.containsKey(clusterId)) {
            return error(sender, "Unknown cluster '$clusterId'.")
        }
        configStore.deleteCluster(clusterId)
            .onSuccess { success(sender, "Deleted cluster '$clusterId'.") }
            .onFailure { error(sender, it.message ?: "delete failed") }
    }
    private fun handleClusterSet(sender: CommandSender, clusterId: String, entryId: String, field: String, value: String?, offset: Vec3i?) {
        val cluster = configStore.clustersById[clusterId]
            ?: return error(sender, "Unknown cluster '$clusterId'.")
        val entry = cluster.entries.firstOrNull { it.id == entryId }
            ?: return error(sender, "Unknown entry '$entryId'.")
        if (field == "box" && !configStore.boxesById.containsKey(value)) {
            return error(sender, "Unknown box '$value'.")
        }
        if (field == "area" && !configStore.areasById.containsKey(value)) {
            return error(sender, "Unknown area '$value'.")
        }
        val updated = when (field) {
            "box" -> entry.copy(boxId = value!!)
            "area" -> entry.copy(areaId = value!!)
            else -> entry.copy(offset = offset!!)
        }
        configStore.saveCluster(cluster.copy(entries = cluster.entries.map { if (it.id == entryId) updated else it }))
            .onSuccess { success(sender, "Updated $field for '$entryId'.") }
            .onFailure { error(sender, it.message ?: "save failed") }
    }
    private fun handleGroupAdd(sender: CommandSender, clusterId: String, entryId: String, name: String, offset: Vec3i) {
        val cluster = configStore.clustersById[clusterId]
            ?: return error(sender, "Unknown cluster '$clusterId'.")
        val entry = cluster.entries.firstOrNull { it.id == entryId }
            ?: return error(sender, "Unknown entry '$entryId'.")
        if (name == clusterId && offset == Vec3i(0, 0, 0)) {
            return error(sender, "Automatic group '$clusterId' cannot be edited.")
        }
        if (entry.groups.any { it.name == name && it.offset == offset }) {
            return error(sender, "Group '$name' at offset ${DaCommandPresentation.vec(offset)} already exists on '$entryId'.")
        }
        saveEntry(sender, cluster, entryId, entry.copy(groups = entry.groups + ClusterGroup(name, offset)), "Added group '$name'.")
    }
    private fun handleGroupRemove(sender: CommandSender, clusterId: String, entryId: String, name: String, offset: Vec3i) {
        val cluster = configStore.clustersById[clusterId]
            ?: return error(sender, "Unknown cluster '$clusterId'.")
        val entry = cluster.entries.firstOrNull { it.id == entryId }
            ?: return error(sender, "Unknown entry '$entryId'.")
        if (name == clusterId && offset == Vec3i(0, 0, 0)) {
            return error(sender, "Automatic group '$clusterId' cannot be removed.")
        }
        val target = ClusterGroup(name, offset)
        if (target !in entry.groups) {
            return error(sender, "Group '$name' at offset ${DaCommandPresentation.vec(offset)} is not assigned to '$entryId'.")
        }
        saveEntry(sender, cluster, entryId, entry.copy(groups = entry.groups - target), "Removed group '$name'.")
    }
    private fun handleGroupClear(sender: CommandSender, clusterId: String, entryId: String) {
        val cluster = configStore.clustersById[clusterId]
            ?: return error(sender, "Unknown cluster '$clusterId'.")
        val entry = cluster.entries.firstOrNull { it.id == entryId }
            ?: return error(sender, "Unknown entry '$entryId'.")
        saveEntry(sender, cluster, entryId, entry.copy(groups = emptySet()), "Cleared groups from '$entryId'.")
    }
    private fun saveEntry(sender: CommandSender, c: ClusterDefinition, id: String, e: ClusterEntry, message: String) { configStore.saveCluster(c.copy(entries = c.entries.map { if (it.id == id) e else it })).onSuccess { success(sender, message) }.onFailure { error(sender, it.message ?: "save failed") } }

    private fun handleSetCluster(sender: CommandSender, clusterId: String, pivot: Vec3i?, ttl: Int) {
        val pos = pivot ?: baseOffsetFromSender(sender)
            ?: return error(sender, "Console must provide a pivot position.")
        val world = worldId(sender)
            ?: return error(sender, "Console must be a block command sender for setcluster.")
        runtime.setCluster(clusterId, world, pos, ttl)
            .onSuccess { count ->
                if (sender !is BlockCommandSender) {
                    success(sender, "Activated cluster '$clusterId' at ${DaCommandPresentation.vec(pos)} (entries=$count, ttl=$ttl).")
                }
            }
            .onFailure { error(sender, it.message ?: "activation failed") }
    }
    private fun handleAddBoxToArea(sender: CommandSender, boxId: String, areaId: String, explicitOffset: Vec3i?, ttlArg: Int?, groupsRaw: String) {
        val base = explicitOffset ?: baseOffsetFromSender(sender)
            ?: return error(sender, "Console must provide an offset.")
        val world = worldId(sender)
            ?: return error(sender, "Console is not supported for addboxtoarea.")
        val groups = parseGroups(sender, groupsRaw, base, world) ?: return
        val ttl = ttlArg ?: 2
        runtime.addOrRefreshBox(areaId, boxId, world, base, ttl, groups)
            .onSuccess {
                if (sender !is BlockCommandSender) {
                    success(sender, "Activated '$boxId' in '$areaId' (ttl=$ttl, groups=${groups.size}).")
                }
            }
            .onFailure { error(sender, it.message ?: "activation failed") }
    }

    private fun legacyOffset(ctx: CommandContext<CommandSourceStack>): Vec3i? {
        val sender = ctx.source.sender
        if (ctx.nodes.any { it.node.name == "offset" }) {
            return ctx.getArgument("offset", BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
        }
        val named = runCatching { StringArgumentType.getString(ctx, "offset_vec") }.getOrNull()
        if (named != null) return resolveSenderVector(sender, named, false)
        val inverted = runCatching { StringArgumentType.getString(ctx, "offset_inv_vec") }.getOrNull()
        if (inverted != null) return resolveSenderVector(sender, inverted, true)
        return null
    }

    private fun parseGroups(sender: CommandSender, raw: String, base: Vec3i, world: UUID): Set<GroupKey>? {
        val sourcePosition = baseOffsetFromSender(sender) ?: base
        return NamedGroupParser.parse(raw, base, sourcePosition, world, configStore.vectorsById)
            .onFailure { error(sender, it.message ?: "invalid groups syntax") }
            .getOrNull()
    }
    private fun groupError(sender: CommandSender, message: String): Set<GroupKey>? { error(sender, message); return null }
    private fun resolveSenderVector(sender: CommandSender, name: String, inverted: Boolean): Vec3i? { val base = baseOffsetFromSender(sender) ?: run { error(sender, "Console must provide a position."); return null }; val v = configStore.vectorsById[name] ?: run { error(sender, "Unknown vector '$name'."); return null }; return if (inverted) Vec3i(base.x - v.x, base.y - v.y, base.z - v.z) else Vec3i(base.x + v.x, base.y + v.y, base.z + v.z) }
    private fun handleSetPivot(sender: CommandSender, pivot: Vec3i) { val p = sender as? Player ?: return error(sender, "Only a player can set a pivot."); pivots[p.uniqueId] = pivot; success(sender, "Pivot set to ${DaCommandPresentation.vec(pivot)}.") }
    private fun handleSaveVector(sender: CommandSender, name: String, pos: Vec3i?) { val p = sender as? Player ?: return error(sender, "Only a player can save a vector."); val target = pos ?: worldEditSelectionProvider.readPrimaryPosition(p) ?: return error(sender, "WorldEdit first position unavailable; provide coordinates."); val pivot = pivots[p.uniqueId] ?: Vec3i(0, 0, 0); val v = Vec3i(target.x - pivot.x, target.y - pivot.y, target.z - pivot.z); configStore.saveVector(name, v).onSuccess { success(sender, "Saved vector '$name': ${DaCommandPresentation.vec(v)}.") }.onFailure { error(sender, it.message ?: "save failed") } }
    private fun handleSaveBox(sender: CommandSender, id: String, start: Vec3i?, end: Vec3i?) { val p = sender as? Player ?: return error(sender, "Only a player can save a box."); val c = if (start != null && end != null) start to end else runCatching { worldEditSelectionProvider.readSelection(p) }.getOrNull() ?: return error(sender, "WorldEdit selection unavailable; provide coordinates."); val pivot = pivots[p.uniqueId] ?: Vec3i(0, 0, 0); val lo = Vec3i(min(c.first.x, c.second.x), min(c.first.y, c.second.y), min(c.first.z, c.second.z)); val hi = Vec3i(max(c.first.x, c.second.x), max(c.first.y, c.second.y), max(c.first.z, c.second.z)); val b = BoxTemplate(id, Vec3i(lo.x - pivot.x, lo.y - pivot.y, lo.z - pivot.z), Vec3i(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)); configStore.saveBox(b).onSuccess { success(sender, "Saved box '$id' ${DaCommandPresentation.boxSummary(b)}.") }.onFailure { error(sender, it.message ?: "save failed") } }
    private fun handleVisualize(sender: CommandSender, areaId: String?) { val p = sender as? Player ?: return error(sender, "Only a player can use visualize."); if (zoneVisualizer.toggle(p, areaId)) success(sender, "Zone visualization enabled${if (areaId == null) "" else " for '$areaId'"}.") else send(sender, DaCommandPresentation.muted("Zone visualization disabled.")) }
    private fun handleReload(sender: CommandSender) { configStore.reloadAll(); runtime.clearRuntime(); areaEventBridge.clearPlayerCache(); success(sender, "DynamicAreas reloaded. Runtime boxes cleared.") }
    private fun worldId(sender: CommandSender): UUID? = when (sender) { is Player -> sender.world.uid; is BlockCommandSender -> sender.block.world.uid; else -> null }
    private fun baseOffsetFromSender(sender: CommandSender): Vec3i? = when (sender) { is Player -> Vec3i(sender.location.blockX, sender.location.blockY, sender.location.blockZ); is BlockCommandSender -> Vec3i(sender.block.x, sender.block.y, sender.block.z); else -> null }
}

private fun BlockPosition.toVec3i() = Vec3i(blockX(), blockY(), blockZ())

internal fun suggestIdentifiers(values: Iterable<String>, builder: SuggestionsBuilder) = builder.apply {
    val prefix = remaining.removePrefix("\"")
    values.distinct().filter { it.startsWith(prefix, ignoreCase = true) }.forEach {
        suggest(StringArgumentType.escapeIfRequired(it))
    }
}.buildFuture()
