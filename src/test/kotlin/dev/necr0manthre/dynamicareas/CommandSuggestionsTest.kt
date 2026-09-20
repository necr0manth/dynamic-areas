package dev.necr0manthre.dynamicareas

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import org.junit.jupiter.api.Test
import kotlin.test.*

class CommandSuggestionsTest {
    @Test
    fun `nested identifiers are filtered and inserted as parseable quoted arguments`() {
        val input = "da cluster show \"rooms/"
        val suggestions = suggestIdentifiers(listOf("rooms/first", "rooms/second", "other"),
            SuggestionsBuilder(input, "da cluster show ".length)).join().list
        assertEquals(2, suggestions.size)
        assertEquals(setOf("rooms/first", "rooms/second"), suggestions.map {
            val full = it.apply(input)
            StringArgumentType.string().parse(StringReader(full.removePrefix("da cluster show ")))
        }.toSet())
    }
}
