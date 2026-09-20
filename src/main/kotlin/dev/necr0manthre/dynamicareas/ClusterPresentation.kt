package dev.necr0manthre.dynamicareas

import com.mojang.brigadier.arguments.StringArgumentType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration

/** Pure chat rendering shared by players and the server console. */
internal object ClusterPresentation {
    private const val PAGE_SIZE = 6
    private val zero = Vec3i(0, 0, 0)
    private fun q(value: String) = StringArgumentType.escapeIfRequired(value)
    private fun vec(value: Vec3i) = DaCommandPresentation.vec(value)
    private fun heading(title: String) = Component.text("◆ ", AQUA)
        .append(Component.text(title, WHITE).decorate(TextDecoration.BOLD))
    private fun note(text: String) = Component.text("  $text", GRAY)
    private fun field(label: String, value: String) = Component.text("  $label  ", GRAY)
        .append(Component.text(value, WHITE))
    private fun action(label: String, command: String, tooltip: String, readOnly: Boolean = false): Component =
        Component.text("[$label]", if (readOnly) AQUA else YELLOW)
            .clickEvent(if (readOnly) ClickEvent.runCommand(command) else ClickEvent.suggestCommand(command))
            .hoverEvent(Component.text(tooltip, WHITE))

    private fun pages(count: Int) = maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE)
    private fun navigation(page: Int, total: Int, command: (Int) -> String): Component =
        DaCommandPresentation.page(page, total,
            if (page < total) command(page + 1) else null,
            if (page > 1) command(page - 1) else null)

    fun list(clusters: List<ClusterDefinition>, page: Int): List<Component> = buildList {
        val sorted = clusters.sortedBy { it.id }
        val total = pages(sorted.size)
        val current = page.coerceIn(1, total)
        add(heading("Clusters · ${sorted.size}"))
        add(note("Click a name to inspect its entries."))
        sorted.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).forEach { cluster ->
            add(Component.text("  ▸ ", DARK_GRAY)
                .append(Component.text(cluster.id, AQUA))
                .append(Component.text("  ${cluster.entries.size} entries", GRAY))
                .clickEvent(ClickEvent.runCommand("/da cluster show ${q(cluster.id)}"))
                .hoverEvent(Component.text("Open ${cluster.id}\nAutomatic group: ${cluster.id}\nNo changes will be made.", WHITE)))
        }
        if (sorted.isEmpty()) add(note("No clusters yet. Create one to start adding boxes."))
        add(navigation(current, total) { "/da cluster list $it" })
        add(action("create", "/da cluster create ", "Type a new cluster name. No pivot is needed.")
            .append(Component.text("  ")).append(action("help", "/da cluster help", "Open the editing guide", true)))
    }

    fun show(cluster: ClusterDefinition, boxes: Map<String, BoxTemplate>, entryId: String?, page: Int): List<Component> {
        if (entryId != null) return details(cluster, cluster.entries.first { it.id == entryId }, boxes, page)
        val total = pages(cluster.entries.size)
        val current = page.coerceIn(1, total)
        return buildList {
            add(heading("${cluster.id} · ${cluster.entries.size} entries"))
            add(note("Every entry joins '${cluster.id}' at the placement pivot."))
            add(note("Hover for bounds and groups; click a name for editing actions."))
            cluster.entries.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).forEach { entry ->
                val tooltip = buildString {
                    append("Entry: ${entry.id}\nBox: ${entry.boxId}\nArea: ${entry.areaId}\n")
                    append("Placement offset: ${vec(entry.offset)}\n")
                    boxes[entry.boxId]?.let { append("Box: ${DaCommandPresentation.boxSummary(it)}\n") }
                    append("Automatic group: ${cluster.id} @ 0, 0, 0")
                    entry.groups.forEach { append("\nGroup: ${it.name} @ ${vec(it.offset)}") }
                    append("\nGroup offsets are measured from the cluster pivot.\nClick for details.")
                }
                add(Component.text("  ▸ ", DARK_GRAY)
                    .append(Component.text(entry.id, YELLOW))
                    .append(Component.text("  ${entry.boxId}", WHITE))
                    .append(Component.text(" → ", DARK_GRAY))
                    .append(Component.text(entry.areaId, LIGHT_PURPLE))
                    .append(Component.text("  +${entry.groups.size} groups", GRAY))
                    .clickEvent(ClickEvent.runCommand("/da cluster show ${q(cluster.id)} entry ${q(entry.id)}"))
                    .hoverEvent(Component.text(tooltip, WHITE)))
            }
            if (cluster.entries.isEmpty()) add(note("This cluster is empty. Add an existing box or capture a new one."))
            add(navigation(current, total) { "/da cluster show ${q(cluster.id)} page $it" })
            add(action("add box", "/da cluster add ${q(cluster.id)} ", "Choose an existing box and area; optional: as <entry>.")
                .append(Component.text("  "))
                .append(action("capture", "/da cluster capture ${q(cluster.id)} ", "Create a box and entry using your saved pivot and selection."))
                .append(Component.text("  "))
                .append(action("all clusters", "/da cluster list", "Return to the cluster list", true)))
        }
    }

    private fun details(cluster: ClusterDefinition, entry: ClusterEntry, boxes: Map<String, BoxTemplate>, page: Int): List<Component> = buildList {
        val target = "${q(cluster.id)} ${q(entry.id)}"
        add(heading("${cluster.id} / ${entry.id}"))
        add(field("Box", entry.boxId).append(Component.text("  "))
            .append(action("change", "/da cluster set $target box ", "Choose another saved box. The original box is not modified.")))
        add(field("Area", entry.areaId).append(Component.text("  "))
            .append(action("change", "/da cluster set $target area ", "Choose an existing area definition.")))
        add(field("Placement offset", vec(entry.offset)).append(Component.text("  "))
            .append(action("change", "/da cluster set $target offset ", "Enter dx dy dz from the cluster pivot.")))
        boxes[entry.boxId]?.let { box ->
            add(field("Box offset", vec(box.offset)))
            add(field("Size (inclusive delta)", vec(box.size))
                .hoverEvent(Component.text("Each axis contains size + 1 blocks.\nThe box offset and placement offset are added to the cluster pivot.", WHITE)))
        }
        add(Component.text("  Groups ", AQUA).append(Component.text("(offsets from the cluster pivot)", GRAY)))
        add(Component.text("    ${cluster.id} @ ${vec(zero)}  ", GREEN)
            .append(Component.text("automatic", DARK_GREEN))
            .hoverEvent(Component.text("Always present. Clearing additional groups does not remove this membership.", WHITE)))
        val total = pages(entry.groups.size)
        val current = page.coerceIn(1, total)
        entry.groups.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).forEach { group ->
            val offset = group.offset
            val command = "/da cluster group remove $target ${q(group.name)} offset ${offset.x} ${offset.y} ${offset.z}"
            add(Component.text("    ${group.name} @ ${vec(offset)}  ", WHITE)
                .append(action("remove", command, "Suggest removal of this exact name and offset only.")))
        }
        if (entry.groups.isEmpty()) add(note("  No additional groups."))
        if (total > 1) add(navigation(current, total) { "/da cluster show ${q(cluster.id)} entry ${q(entry.id)} page $it" })
        add(action("add group", "/da cluster group add $target ", "Enter a name, optionally followed by offset dx dy dz.")
            .append(Component.text("  "))
            .append(action("clear groups", "/da cluster group clear $target", "Suggest clearing additional memberships; the automatic group remains.")))
        add(action("remove entry", "/da cluster remove $target", "Suggest removing this entry. Its saved box and area are kept.")
            .append(Component.text("  "))
            .append(action("back", "/da cluster show ${q(cluster.id)}", "Return to the cluster entries", true)))
    }

    fun help(page: Int = 1): List<Component> = buildList {
        val current = page.coerceIn(1, 3)
        fun syntax(command: String, description: String) {
            add(DaCommandPresentation.helpLine(command, description))
        }
        fun example(command: String) {
            add(Component.text("  Try: ", GREEN).append(action(command, command, "Put this example in the command input; edit it before sending.")))
        }
        when (current) {
            1 -> {
                add(heading("Cluster guide · create and inspect"))
                syntax("create <cluster>", "create an empty template; no pivot needed")
                syntax("add <cluster> <box> <area> [as <entry>]", "entry name defaults to the box ID")
                syntax("capture <cluster> <box> <area> [as <entry>]", "save a new box and entry from WorldEdit")
                add(note("Capture needs /da setpivot first. Without WorldEdit, append"))
                add(note("from <x> <y> <z> to <x> <y> <z>. Existing boxes are never overwritten."))
                syntax("list [page]", "browse saved clusters")
                syntax("show <cluster> [entry <entry> | page <page>]", "inspect entries, hover for details")
                example("/da cluster add trial entrance_box spawn_rules as entrance")
            }
            2 -> {
                add(heading("Cluster guide · edit entries and groups"))
                syntax("set <cluster> <entry> box <box>", "replace the box reference")
                syntax("set <cluster> <entry> area <area>", "replace the area reference")
                syntax("set <cluster> <entry> offset <dx> <dy> <dz>", "move the entry relative to the placement pivot")
                syntax("group add/remove <cluster> <entry> <name> [offset <dx> <dy> <dz>]", "edit an exact membership; default offset is 0 0 0")
                syntax("group clear <cluster> <entry>", "clear additional groups; keep the automatic cluster group")
                syntax("remove <cluster> <entry>", "remove the entry, keeping its saved box")
                syntax("delete <cluster>", "delete the template, keeping its boxes and areas")
                example("/da cluster group add trial entrance players")
            }
            else -> {
                add(heading("Cluster guide · activate and copy"))
                syntax("/da setcluster <cluster> [position | vector | inv vector] [ttl]", "refresh all entries; default TTL is 2 ticks")
                add(note("Put da setcluster trial ~ ~ ~ in the main repeating command block."))
                add(note("Keep it running: zones expire when refreshes stop. Copy the block with the build."))
                add(note("Every entry joins (world, pivot, cluster name), plus its explicit groups."))
                syntax("/da addboxtoarea <box> <area> [position] [ttl] groups <names...>", "use a separate command block for redstone-controlled boxes")
                add(note("Short group names use the placement origin. groups at <position/vector> <names...>"))
                add(note("uses an explicit point: ~ and vectors resolve from the command source."))
                example("/da addboxtoarea gate gate_rules inv gate_cb groups trial doors")
            }
        }
        add(note("All editing commands above start with /da cluster unless shown otherwise."))
        add(note("Edits save immediately; active copies update on their next setcluster call."))
        add(navigation(current, 3) { "/da cluster help $it" })
    }
}
