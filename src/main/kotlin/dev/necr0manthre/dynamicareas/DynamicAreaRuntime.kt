package dev.necr0manthre.dynamicareas

import org.bukkit.Location
import java.util.UUID

class DynamicAreaRuntime(private val configStore: ConfigStore) {
    private val activeBoxes: MutableMap<ActiveBoxKey, ActiveBox> = linkedMapOf()
    private val chunkIndex: MutableMap<UUID, MutableMap<Long, MutableSet<ActiveBoxKey>>> = hashMapOf()
    private val groupIndex: MutableMap<GroupKey, MutableSet<ActiveBoxKey>> = hashMapOf()
    private val clusterInstances: MutableMap<ClusterInvocationKey, MutableSet<ActiveBoxKey>> = hashMapOf()

    private data class ClusterInvocationKey(val clusterId: String, val worldId: UUID, val pivot: Vec3i)

    fun clearRuntime() {
        activeBoxes.clear()
        chunkIndex.clear()
        groupIndex.clear()
        clusterInstances.clear()
    }

    fun addOrRefreshBox(areaId: String, boxId: String, worldId: UUID, baseOffset: Vec3i, ttl: Int, groups: Set<GroupKey> = emptySet()): Result<Unit> =
        addOrRefreshBoxFromSource(areaId, boxId, worldId, baseOffset, ttl, groups, DIRECT_SOURCE_ID)

    private fun addOrRefreshBoxFromSource(
        areaId: String,
        boxId: String,
        worldId: UUID,
        baseOffset: Vec3i,
        ttl: Int,
        groups: Set<GroupKey>,
        sourceId: String,
    ): Result<Unit> = runCatching {
        require(ttl >= 1) { "ttl must be >= 1" }
        val area = configStore.areasById[areaId] ?: error("Unknown area_id '$areaId'")
        val boxTemplate = configStore.boxesById[boxId] ?: error("Unknown box_id '$boxId'")
        val key = ActiveBoxKey(area.id, boxTemplate.id, worldId, baseOffset, sourceId)
        val absolute = boxTemplate.toAbsolute(baseOffset)
        val normalizedGroups = groups.toSet()
        val existing = activeBoxes[key]
        if (existing == null) {
            val active = ActiveBox(key, absolute, ttl, normalizedGroups)
            activeBoxes[key] = active
            indexBox(active)
        } else {
            if (existing.absolute != absolute || existing.groups != normalizedGroups) {
                deindexBox(existing)
                existing.absolute = absolute
                existing.groups = normalizedGroups
                indexBox(existing)
            }
            existing.ttl = ttl
        }
    }

    /**
     * Refreshes one cluster invocation. All references are checked before any old
     * contribution is removed, so an invalid edit cannot partially replace it.
     */
    fun setCluster(clusterId: String, worldId: UUID, pivot: Vec3i, ttl: Int = 2): Result<Int> {
        if (ttl < 1) return Result.failure(IllegalArgumentException("ttl must be >= 1"))
        val invocation = ClusterInvocationKey(clusterId, worldId, pivot)
        val definition = configStore.clustersById[clusterId]
        if (definition == null) {
            // A deleted definition is cleaned when the command next invokes it;
            // until then existing instances still expire under their own TTL.
            clusterInstances.keys.filter { it.clusterId == clusterId }.toList().forEach { removeClusterInstance(it) }
            return Result.failure(IllegalArgumentException("Unknown cluster '$clusterId'"))
        }

        return runCatching {
            require(definition.entries.map { it.id }.toSet().size == definition.entries.size) { "duplicate cluster entry id" }
            val planned = definition.entries.map { entry ->
                val area = configStore.areasById[entry.areaId] ?: error("Unknown area_id '${entry.areaId}'")
                val box = configStore.boxesById[entry.boxId] ?: error("Unknown box_id '${entry.boxId}'")
                require(entry.id.isNotBlank()) { "cluster entry id must not be blank" }
                val base = Vec3i(pivot.x + entry.offset.x, pivot.y + entry.offset.y, pivot.z + entry.offset.z)
                val groups = linkedSetOf(GroupKey(worldId, pivot, definition.id))
                entry.groups.forEach { group ->
                    require(group.name.isNotBlank() && group.name == group.name.trim() && !group.name.any(Char::isWhitespace)) {
                        "group name must be a nonblank word"
                    }
                    groups += GroupKey(worldId, Vec3i(pivot.x + group.offset.x, pivot.y + group.offset.y, pivot.z + group.offset.z), group.name)
                }
                val sourceId = clusterSourceId(invocation, entry.id)
                PlannedBox(ActiveBoxKey(area.id, box.id, worldId, base, sourceId), box.toAbsolute(base), groups)
            }
            val oldKeys = clusterInstances[invocation].orEmpty().toSet()
            val newKeys = planned.map { it.key }.toSet()
            oldKeys.filter { it !in newKeys }.forEach(::removeActiveBox)
            planned.forEach { plannedBox ->
                val existing = activeBoxes[plannedBox.key]
                if (existing == null) {
                    val active = ActiveBox(plannedBox.key, plannedBox.absolute, ttl, plannedBox.groups)
                    activeBoxes[plannedBox.key] = active
                    indexBox(active)
                } else {
                    if (existing.absolute != plannedBox.absolute || existing.groups != plannedBox.groups) {
                        deindexBox(existing)
                        existing.absolute = plannedBox.absolute
                        existing.groups = plannedBox.groups
                        indexBox(existing)
                    }
                    existing.ttl = ttl
                }
            }
            clusterInstances[invocation] = newKeys.toMutableSet()
            planned.size
        }
    }

    private data class PlannedBox(val key: ActiveBoxKey, val absolute: IntBox, val groups: Set<GroupKey>)

    private fun clusterSourceId(invocation: ClusterInvocationKey, entryId: String): String =
        "cluster:${sourcePart(invocation.clusterId)}${sourcePart(invocation.worldId.toString())}" +
            "${sourcePart(invocation.pivot.x.toString())}${sourcePart(invocation.pivot.y.toString())}" +
            "${sourcePart(invocation.pivot.z.toString())}${sourcePart(entryId)}"

    private fun sourcePart(value: String): String = "${value.length}:$value;"

    private fun removeClusterInstance(invocation: ClusterInvocationKey) {
        clusterInstances.remove(invocation)?.toList()?.forEach(::removeActiveBox)
    }

    fun tickTtl() {
        val toRemove = activeBoxes.values.filter { --it.ttl <= 0 }.map { it.key }
        toRemove.forEach(::removeActiveBox)
        clusterInstances.entries.removeIf { (_, keys) ->
            keys.retainAll(activeBoxes.keys)
            keys.isEmpty()
        }
    }

    fun resolveAreasAtBlock(location: Location): Set<String> {
        val worldId = location.world?.uid ?: return emptySet()
        val candidates = chunkIndex[worldId]?.get(chunkKey(location.blockX shr 4, location.blockZ shr 4)) ?: return emptySet()
        if (candidates.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        candidates.forEach { ref ->
            val active = activeBoxes[ref] ?: return@forEach
            if (active.absolute.contains(location.blockX, location.blockY, location.blockZ)) result += active.key.areaId
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

    fun getBoxesByGroup(groupKey: GroupKey): List<ActiveBox> = groupIndex[groupKey]?.mapNotNull { activeBoxes[it] } ?: emptyList()

    fun getBoxesByGroup(worldId: UUID, pos: Vec3i, name: String): List<ActiveBox> = getBoxesByGroup(GroupKey(worldId, pos, name))

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
        deindexBox(active)
    }

    private fun deindexBox(active: ActiveBox) {
        val worldMap = chunkIndex[active.key.worldId]
        if (worldMap != null) {
            chunkRange(active.absolute).let { range ->
                for (cx in range.first.first..range.first.second) for (cz in range.second.first..range.second.second) {
                    val chunk = chunkKey(cx, cz)
                    worldMap[chunk]?.remove(active.key)
                    if (worldMap[chunk].isNullOrEmpty()) worldMap.remove(chunk)
                }
            }
            if (worldMap.isEmpty()) chunkIndex.remove(active.key.worldId)
        }
        active.groups.forEach { groupKey ->
            groupIndex[groupKey]?.remove(active.key)
            if (groupIndex[groupKey].isNullOrEmpty()) groupIndex.remove(groupKey)
        }
    }

    private fun indexBox(active: ActiveBox) {
        val worldMap = chunkIndex.computeIfAbsent(active.key.worldId) { hashMapOf() }
        chunkRange(active.absolute).let { range ->
            for (cx in range.first.first..range.first.second) for (cz in range.second.first..range.second.second) {
                worldMap.computeIfAbsent(chunkKey(cx, cz)) { linkedSetOf() }.add(active.key)
            }
        }
        active.groups.forEach { groupKey -> groupIndex.computeIfAbsent(groupKey) { linkedSetOf() }.add(active.key) }
    }

    private fun chunkRange(box: IntBox): Pair<Pair<Int, Int>, Pair<Int, Int>> =
        ((box.min.x shr 4) to (box.max.x shr 4)) to ((box.min.z shr 4) to (box.max.z shr 4))
    private fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
}
