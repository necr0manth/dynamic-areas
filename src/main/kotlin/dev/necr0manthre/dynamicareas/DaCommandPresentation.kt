package dev.necr0manthre.dynamicareas

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/** Small, dependency-free presentation helpers for the /da command. */
internal object DaCommandPresentation {
    private val accent = NamedTextColor.AQUA
    private val muted = NamedTextColor.GRAY

    fun heading(text: String): Component = Component.text("Dynamic Areas", NamedTextColor.AQUA)
        .decorate(TextDecoration.BOLD)
        .append(Component.text("  $text", NamedTextColor.WHITE))

    fun ok(text: String): Component = Component.text("✓ ", NamedTextColor.GREEN)
        .append(Component.text(text, NamedTextColor.WHITE))

    fun error(text: String): Component = Component.text("✗ ", NamedTextColor.RED)
        .append(Component.text(text, NamedTextColor.WHITE))

    fun label(name: String, value: String): Component = Component.text(name, accent)
        .append(Component.text(value, NamedTextColor.WHITE))

    fun muted(text: String): Component = Component.text(text, muted)

    fun row(label: String, value: String, command: String? = null, hover: String? = null, run: Boolean = false): Component {
        var result = Component.text("  ", muted)
            .append(Component.text(label, NamedTextColor.YELLOW))
            .append(Component.text("  $value", NamedTextColor.WHITE))
        if (hover != null) {
            result = result.hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)))
        }
        if (command != null) {
            result = result.clickEvent(if (run) ClickEvent.runCommand(command) else ClickEvent.suggestCommand(command))
        }
        return result
    }

    fun page(current: Int, total: Int, nextCommand: String? = null, previousCommand: String? = null): Component {
        var result = Component.text("  page $current/$total", muted)
        if (previousCommand != null) {
            result = result.append(Component.text("  ", muted))
                .append(Component.text("previous", accent)
                    .clickEvent(ClickEvent.runCommand(previousCommand))
                    .hoverEvent(HoverEvent.showText(Component.text("Open the previous page", muted))))
        }
        if (nextCommand != null) {
            result = result.append(Component.text("  ", muted))
                .append(Component.text("next", accent)
                    .clickEvent(ClickEvent.runCommand(nextCommand))
                    .hoverEvent(HoverEvent.showText(Component.text("Open the next page", muted))))
        }
        return result
    }

    fun helpLine(syntax: String, description: String): Component = Component.text("  ", muted)
        .append(Component.text(syntax, NamedTextColor.YELLOW))
        .append(Component.text(" — $description", NamedTextColor.WHITE))

    fun commandSuggestion(command: String, description: String): Component =
        Component.text(command, accent)
            .clickEvent(ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(description, NamedTextColor.GRAY)))

    fun boxSummary(box: BoxTemplate): String =
        "offset=${vec(box.offset)} size=${vec(box.size)} (inclusive)"

    fun entrySummary(entry: ClusterEntry): String =
        "box=${entry.boxId} area=${entry.areaId} offset=${vec(entry.offset)} groups=${entry.groups.joinToString(",") { it.name }.ifEmpty { "(none)" }}"

    fun vec(value: Vec3i): String = "${value.x}, ${value.y}, ${value.z}"
}
