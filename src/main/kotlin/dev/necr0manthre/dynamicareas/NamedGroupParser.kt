package dev.necr0manthre.dynamicareas

import java.util.UUID

/** Parses the terminal group list; explicit positions use the command source. */
internal object NamedGroupParser {
    fun parse(
        raw: String,
        placementOrigin: Vec3i,
        sourcePosition: Vec3i,
        worldId: UUID,
        vectors: Map<String, Vec3i>,
    ): Result<Set<GroupKey>> = runCatching {
        if (raw.isBlank()) return@runCatching emptySet()
        val tokens = raw.trim().split(Regex("\\s+"))
        var namesStart = 0
        var point = placementOrigin

        if (tokens.first() == "at") {
            require(tokens.size > 1) { "Use groups at <position or vector> <names...>." }
            val first = tokens[1]
            if (first.startsWith("~") || first.toIntOrNull() != null) {
                require(tokens.size >= 5) { "Use groups at <x> <y> <z> <names...>." }
                point = Vec3i(
                    coordinate(tokens[1], sourcePosition.x),
                    coordinate(tokens[2], sourcePosition.y),
                    coordinate(tokens[3], sourcePosition.z),
                )
                namesStart = 4
            } else {
                val inverted = first == "inv"
                val vectorIndex = if (inverted) 2 else 1
                val name = tokens.getOrNull(vectorIndex)
                    ?: error("Use groups at inv <vector> <names...>.")
                val vector = vectors[name] ?: error("Unknown vector '$name'.")
                fun axis(base: Int, displacement: Int): Int = if (inverted) {
                    Math.subtractExact(base, displacement)
                } else {
                    Math.addExact(base, displacement)
                }
                point = Vec3i(axis(sourcePosition.x, vector.x), axis(sourcePosition.y, vector.y),
                    axis(sourcePosition.z, vector.z))
                namesStart = vectorIndex + 1
            }
        }

        require(namesStart < tokens.size) { "Provide at least one group name after the position." }
        tokens.drop(namesStart).mapTo(linkedSetOf()) { name -> GroupKey(worldId, point, name) }
    }

    private fun coordinate(token: String, source: Int): Int {
        if (!token.startsWith("~")) {
            return token.toIntOrNull() ?: error("Invalid block coordinate '$token'; use an integer or ~offset.")
        }
        val offset = token.drop(1).let { if (it.isEmpty()) 0 else it.toIntOrNull() }
            ?: error("Invalid relative block coordinate '$token'; use ~ or ~integer.")
        return Math.addExact(source, offset)
    }
}
