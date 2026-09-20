package dev.necr0manthre.dynamicareas

import java.util.Locale
import java.util.UUID

data class GroupKey(val worldId: UUID, val pos: Vec3i, val name: String)

data class Vec3i(val x: Int, val y: Int, val z: Int)

data class IntBox(val min: Vec3i, val max: Vec3i) {
    fun contains(x: Int, y: Int, z: Int): Boolean {
        return x in min.x..max.x && y in min.y..max.y && z in min.z..max.z
    }
}

data class BoxTemplate(
    val id: String,
    val offset: Vec3i,
    val size: Vec3i,
) {
    fun toAbsolute(offsetBase: Vec3i): IntBox {
        val min = Vec3i(offsetBase.x + offset.x, offsetBase.y + offset.y, offsetBase.z + offset.z)
        // Size is inclusive, so max = min + size.
        val max = Vec3i(min.x + size.x, min.y + size.y, min.z + size.z)
        return IntBox(min, max)
    }
}

data class ClusterGroup(
    val name: String,
    val offset: Vec3i = Vec3i(0, 0, 0),
)

data class ClusterEntry(
    val id: String,
    val boxId: String,
    val areaId: String,
    val offset: Vec3i = Vec3i(0, 0, 0),
    val groups: Set<ClusterGroup> = emptySet(),
)

data class ClusterDefinition(
    val id: String,
    val entries: List<ClusterEntry> = emptyList(),
)

enum class AreaEventType(val key: String) {
    ON_INTERACT("on_interact"),
    ON_PLAYER_ENTER("on_player_enter"),
    ON_PLAYER_LEAVE("on_player_leave");

    companion object {
        fun fromKey(key: String): AreaEventType? {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        }
    }
}

enum class ProtectState {
    ENABLE,
    DISABLE,
    DEFAULT;

    companion object {
        fun fromRaw(raw: String?): ProtectState {
            return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                "enable" -> ENABLE
                "disable" -> DISABLE
                else -> DEFAULT
            }
        }
    }
}

enum class TriState {
    ALLOW,
    DENY,
    IGNORE;

    companion object {
        fun fromRaw(raw: String?): TriState {
            return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                "allow" -> ALLOW
                "deny" -> DENY
                else -> IGNORE
            }
        }
    }
}

data class AreaDefinition(
    val id: String,
    val blockBreaking: TriState,
    val blockPlacing: TriState,
    val interactions: TriState,
    val protect: ProtectState,
    val listeners: Map<AreaEventType, List<String>>,
)

data class ActiveBoxKey(
    val areaId: String,
    val boxId: String,
    val worldId: UUID,
    val baseOffset: Vec3i,
    val sourceId: String = DIRECT_SOURCE_ID,
)

data class ActiveBox(
    val key: ActiveBoxKey,
    var absolute: IntBox,
    var ttl: Int,
    var groups: Set<GroupKey> = emptySet(),
)

const val DIRECT_SOURCE_ID: String = "direct"

