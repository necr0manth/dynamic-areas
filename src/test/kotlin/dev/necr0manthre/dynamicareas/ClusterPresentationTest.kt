package dev.necr0manthre.dynamicareas

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import kotlin.test.*

class ClusterPresentationTest {
    private val entry = ClusterEntry("entry", "box", "rules", groups = linkedSetOf(
        ClusterGroup("players"), ClusterGroup("players", Vec3i(10, 0, 0))))
    private val cluster = ClusterDefinition("trial", listOf(entry))
    private fun flatten(component: Component): List<Component> = listOf(component) + component.children().flatMap(::flatten)
    private fun text(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    private fun command(click: ClickEvent<*>): String = (click.payload() as ClickEvent.Payload.Text).value()

    @Test
    fun `entry actions only suggest mutations and address exact membership offsets`() {
        val components = ClusterPresentation.show(cluster, emptyMap(), "entry", 1).flatMap(::flatten)
        val clicks = components.mapNotNull { it.clickEvent() }
        val mutations = clicks.filter { command(it).contains("group remove") }
        assertEquals(setOf(
            "/da cluster group remove trial entry players offset 0 0 0",
            "/da cluster group remove trial entry players offset 10 0 0"), mutations.map(::command).toSet())
        assertTrue(clicks.filter { it.action() == ClickEvent.Action.RUN_COMMAND }
            .all { command(it).startsWith("/da cluster show ") })
        assertTrue(mutations.all { it.action() == ClickEvent.Action.SUGGEST_COMMAND })
        assertTrue(components.any { text(it).contains("automatic") })
    }

    @Test
    fun `list entry hover includes automatic group even with explicit memberships`() {
        val components = ClusterPresentation.show(cluster, emptyMap(), null, 1).flatMap(::flatten)
        val hoverText = components.mapNotNull { it.hoverEvent()?.value() as? Component }.joinToString("\n", transform = ::text)
        assertContains(hoverText, "Automatic group: trial @ 0, 0, 0")
        assertContains(hoverText, "players @ 10, 0, 0")
    }

    @Test
    fun `pagination opens read only pages and quoted ids stay addressable`() {
        val many = ClusterDefinition("rooms/trial", (1..13).map { entry.copy(id = "entry$it") })
        val rows = ClusterPresentation.show(many, emptyMap(), null, 2)
        val clicks = rows.flatMap(::flatten).mapNotNull { it.clickEvent() }
        val pages = clicks.filter { command(it).contains(" page ") }
        assertEquals(setOf("/da cluster show \"rooms/trial\" page 1", "/da cluster show \"rooms/trial\" page 3"),
            pages.map(::command).toSet())
        assertTrue(pages.all { it.action() == ClickEvent.Action.RUN_COMMAND })
    }
}
