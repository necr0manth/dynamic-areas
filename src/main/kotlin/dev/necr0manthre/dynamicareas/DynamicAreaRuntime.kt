package dev.necr0manthre.dynamicareas

import org.bukkit.Location
import java.util.UUID

class DynamicAreaRuntime(
    private val configStore: ConfigStore,
) {
    private val activeBoxes: MutableMap<ActiveBoxKey, ActiveBox> = linkedMapOf()
    private val chunkIndex: MutableMap<UUID, MutableMap<Long, MutableSet<ActiveBoxKey>>> = hashMapOf()

    fun clearRuntime() {
        activeBoxes.clear()
        chunkIndex.clear()
    }

    fun addOrRefreshBox(areaId: String, boxId: String, worldId: UUID, baseOffset: Vec3i, ttl: Int): Result<Unit> {
        return runCatching {
            require(ttl >= 1) { "ttl must be >= 1" }
            val area = configStore.areasById[areaId] ?: error("Unknown area_id '$areaId'")
            val boxTemplate = configStore.boxesById[boxId] ?: error("Unknown box_id '$boxId'")
            val key = ActiveBoxKey(area.id, boxTemplate.id, worldId, baseOffset)
            val existing = activeBoxes[key]
            if (existing != null) {
                existing.ttl = ttl
                return@runCatching
            }
            val absolute = boxTemplate.toAbsolute(baseOffset)
            val active = ActiveBox(key, absolute, ttl)
            activeBoxes[key] = active
            indexBox(active)
        }
    }

    fun tickTtl() {
        val toRemove = mutableListOf<ActiveBoxKey>()
        activeBoxes.values.forEach { active ->
            active.ttl -= 1
            if (active.ttl <= 0) {
                toRemove += active.key
            }
        }
        toRemove.forEach { removeActiveBox(it) }
    }

    fun resolveAreasAtBlock(location: Location): Set<String> {
        val worldId = location.world?.uid ?: return emptySet()
        val worldMap = chunkIndex[worldId] ?: return emptySet()
        val key = chunkKey(location.blockX shr 4, location.blockZ shr 4)
        val candidates = worldMap[key] ?: return emptySet()
        if (candidates.isEmpty()) {
            return emptySet()
        }

        val result = linkedSetOf<String>()
        candidates.forEach { ref ->
            val active = activeBoxes[ref] ?: return@forEach
            if (active.absolute.contains(location.blockX, location.blockY, location.blockZ)) {
                result += active.key.areaId
            }
        }
        return result
    }

    fun aggregateBlockBreaking(areaIds: Set<String>): TriState {
        return aggregate(areaIds) { it.blockBreaking }
    }

    fun aggregateBlockPlacing(areaIds: Set<String>): TriState {
        return aggregate(areaIds) { it.blockPlacing }
    }

    fun aggregateInteractions(areaIds: Set<String>): TriState {
        return aggregate(areaIds) { it.interactions }
    }

    fun aggregateProtect(areaIds: Set<String>): Boolean {
        var hasEnable = false
        areaIds.forEach { id ->
            when (configStore.areasById[id]?.protect) {
                ProtectState.DISABLE -> return false
                ProtectState.ENABLE -> hasEnable = true
                else -> Unit
            }
        }
        return hasEnable
    }

    fun listeners(areaId: String, eventType: AreaEventType): List<String> {
        return configStore.areasById[areaId]?.listeners?.get(eventType).orEmpty()
    }

    fun getActiveBoxesByArea(areaId: String): List<ActiveBox> {
        return activeBoxes.values.filter { it.key.areaId == areaId }
    }

    fun getAllActiveBoxes(): List<ActiveBox> = activeBoxes.values.toList()

    private fun aggregate(areaIds: Set<String>, resolver: (AreaDefinition) -> TriState): TriState {
        var hasDeny = false
        areaIds.forEach { id ->
            val area = configStore.areasById[id] ?: return@forEach
            when (resolver(area)) {
                TriState.ALLOW -> return TriState.ALLOW
                TriState.DENY -> hasDeny = true
                TriState.IGNORE -> Unit
            }
        }
        return if (hasDeny) TriState.DENY else TriState.IGNORE
    }

    private fun removeActiveBox(key: ActiveBoxKey) {
        val active = activeBoxes.remove(key) ?: return
        val worldMap = chunkIndex[active.key.worldId] ?: return
        val range = chunkRange(active.absolute)
        for (cx in range.first.first..range.first.second) {
            for (cz in range.second.first..range.second.second) {
                val chunkKey = chunkKey(cx, cz)
                worldMap[chunkKey]?.remove(active.key)
                if (worldMap[chunkKey].isNullOrEmpty()) {
                    worldMap.remove(chunkKey)
                }
            }
        }
        if (worldMap.isEmpty()) {
            chunkIndex.remove(active.key.worldId)
        }
    }

    private fun indexBox(active: ActiveBox) {
        val worldMap = chunkIndex.computeIfAbsent(active.key.worldId) { hashMapOf() }
        val range = chunkRange(active.absolute)
        for (cx in range.first.first..range.first.second) {
            for (cz in range.second.first..range.second.second) {
                val chunkKey = chunkKey(cx, cz)
                worldMap.computeIfAbsent(chunkKey) { linkedSetOf() }.add(active.key)
            }
        }
    }

    private fun chunkRange(box: IntBox): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val minCx = box.min.x shr 4
        val maxCx = box.max.x shr 4
        val minCz = box.min.z shr 4
        val maxCz = box.max.z shr 4
        return (minCx to maxCx) to (minCz to maxCz)
    }

    private fun chunkKey(cx: Int, cz: Int): Long {
        return (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
    }
}

