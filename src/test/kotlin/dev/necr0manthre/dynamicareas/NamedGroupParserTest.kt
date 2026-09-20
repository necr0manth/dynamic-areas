package dev.necr0manthre.dynamicareas

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class NamedGroupParserTest {
    private val world = UUID.randomUUID()
    private val pivot = Vec3i(100, 64, 100)
    private val source = Vec3i(120, 70, 90)
    private fun parse(raw: String) = NamedGroupParser.parse(raw, pivot, source, world,
        mapOf("cb" to Vec3i(20, 6, -10)))

    @Test
    fun `short names use placement origin and collapse exact duplicates`() {
        assertEquals(setOf(GroupKey(world, pivot, "players"), GroupKey(world, pivot, "2")),
            parse("players 2 players").getOrThrow())
    }

    @Test
    fun `explicit mixed coordinates resolve from source rather than placement pivot`() {
        assertEquals(setOf(GroupKey(world, Vec3i(118, 64, 90), "players")),
            parse("at ~-2 64 ~ players").getOrThrow())
    }

    @Test
    fun `explicit vector and inverse use command source`() {
        assertEquals(setOf(GroupKey(world, pivot, "trial")), parse("at inv cb trial").getOrThrow())
        assertEquals(setOf(GroupKey(world, Vec3i(140, 76, 80), "trial")),
            parse("at cb trial").getOrThrow())
    }

    @Test
    fun `invalid explicit positions do not silently discard tokens or create partial memberships`() {
        listOf("at", "at ~ ~ players", "at ~ invalid ~ players", "at unknown players",
            "at inv", "at inv cb", "at ~0.5 ~ ~ players").forEach {
            assertTrue(parse(it).isFailure, it)
        }
    }
}
