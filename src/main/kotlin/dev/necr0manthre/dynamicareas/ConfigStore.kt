package dev.necr0manthre.dynamicareas

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

class ConfigStore(private val dataFolder: File, private val logger: Logger) {
    constructor(plugin: JavaPlugin) : this(plugin.dataFolder, plugin.logger)

    val boxesById: MutableMap<String, BoxTemplate> = linkedMapOf()
    val areasById: MutableMap<String, AreaDefinition> = linkedMapOf()
    val vectorsById: MutableMap<String, Vec3i> = linkedMapOf()
    val clustersById: MutableMap<String, ClusterDefinition> = linkedMapOf()

    fun ensureDirectories() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }
        File(dataFolder, "areas").mkdirs()
        File(dataFolder, "boxes").mkdirs()
        File(dataFolder, "vectors").mkdirs()
        File(dataFolder, "clusters").mkdirs()
    }

    fun reloadAll() {
        boxesById.clear()
        areasById.clear()
        vectorsById.clear()
        clustersById.clear()
        loadBoxes()
        loadAreas()
        loadVectors()
        loadClusters()
    }

    private fun loadBoxes() {
        val root = File(dataFolder, "boxes")
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .forEach { file ->
                val id = root.toRelativeId(file)
                runCatching {
                    val yml = YamlConfiguration.loadConfiguration(file)
                    val offset = parseVec3(yml, "offset") ?: error("missing offset")
                    val size = parseVec3(yml, "size") ?: error("missing size")
                    require(size.x >= 0 && size.y >= 0 && size.z >= 0) { "size must be >= 0" }
                    BoxTemplate(id, offset, size)
                }.onSuccess { boxesById[id] = it }
                    .onFailure { logger.warning("Box '$id' skipped: ${it.message}") }
            }
    }

    private fun loadAreas() {
        val root = File(dataFolder, "areas")
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .forEach { file ->
                val id = root.toRelativeId(file)
                runCatching {
                    val yml = YamlConfiguration.loadConfiguration(file)
                    val listenersMap = linkedMapOf<AreaEventType, MutableList<String>>()
                    yml.getMapList("listeners").forEach { entry ->
                        entry.forEach { (rawKey, rawValue) ->
                            val key = rawKey?.toString() ?: return@forEach
                            val eventType = AreaEventType.fromKey(key)
                            if (eventType == null) {
                                logger.warning("Area '$id' has unknown listener event '$key'")
                                return@forEach
                            }
                            val command = rawValue?.toString()?.trim().orEmpty()
                            if (command.isNotEmpty()) {
                                listenersMap.computeIfAbsent(eventType) { mutableListOf() }.add(command)
                            }
                        }
                    }
                    AreaDefinition(
                        id = id,
                        blockBreaking = TriState.fromRaw(yml.getString("block_breaking")),
                        blockPlacing = TriState.fromRaw(yml.getString("block_placing")),
                        interactions = TriState.fromRaw(yml.getString("interactions")),
                        protect = ProtectState.fromRaw(yml.getString("protect")),
                        listeners = listenersMap,
                    )
                }.onSuccess { areasById[id] = it }
                    .onFailure { logger.warning("Area '$id' skipped: ${it.message}") }
            }
    }

    private fun loadVectors() {
        val root = File(dataFolder, "vectors")
        if (!root.exists()) return
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .forEach { file ->
                val id = root.toRelativeId(file)
                runCatching {
                    parseVec3(YamlConfiguration.loadConfiguration(file), "vec") ?: error("missing vec")
                }.onSuccess { vectorsById[id] = it }
                    .onFailure { logger.warning("Vector '$id' skipped: ${it.message}") }
            }
    }

    private fun loadClusters() {
        val root = File(dataFolder, "clusters")
        if (!root.exists()) return
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .forEach { file ->
                val id = root.toRelativeId(file)
                runCatching {
                    parseCluster(id, YamlConfiguration.loadConfiguration(file)).also(::validateCluster)
                }.onSuccess { clustersById[id] = it }
                    .onFailure { logger.warning("Cluster '$id' skipped: ${it.message}") }
            }
    }

    fun saveVector(name: String, vec: Vec3i): Result<Unit> = runCatching {
        val target = checkedTarget("vectors", name)
        val yml = YamlConfiguration().also { setVec3(it, "vec", vec) }
        writeYaml(target, yml)
        vectorsById[name] = vec
    }

    fun saveBox(box: BoxTemplate): Result<Unit> = runCatching {
        validateBox(box)
        val target = checkedTarget("boxes", box.id)
        writeYaml(target, boxYaml(box))
        boxesById[box.id] = box
    }

    fun saveCluster(definition: ClusterDefinition): Result<Unit> = runCatching {
        validateCluster(definition)
        val target = checkedTarget("clusters", definition.id)
        writeYaml(target, clusterYaml(definition))
        clustersById[definition.id] = definition
    }

    fun deleteCluster(id: String): Result<Unit> = runCatching {
        validateId(id)
        val target = checkedTarget("clusters", id)
        if (target.exists() && !target.delete()) error("could not delete ${target.path}")
        clustersById.remove(id)
    }

    /** Saves a captured box and its cluster together, leaving neither new file on failure. */
    fun saveBoxAndCluster(box: BoxTemplate, definition: ClusterDefinition): Result<Unit> = runCatching {
        validateBox(box)
        validateCluster(definition, setOf(box.id))
        val boxTarget = checkedTarget("boxes", box.id)
        val clusterTarget = checkedTarget("clusters", definition.id)
        val boxTemp = tempTarget(boxTarget)
        val clusterTemp = tempTarget(clusterTarget)
        try {
            saveYamlFile(boxTemp, boxYaml(box))
            saveYamlFile(clusterTemp, clusterYaml(definition))
            replacePair(boxTarget, boxTemp, clusterTarget, clusterTemp)
            boxesById[box.id] = box
            clustersById[definition.id] = definition
        } finally {
            boxTemp.delete()
            clusterTemp.delete()
        }
    }

    private fun parseCluster(id: String, yml: YamlConfiguration): ClusterDefinition {
        val rawEntries = yml.getList("entries") ?: error("missing entries list")
        val entries = rawEntries.map { raw ->
            val map = raw as? Map<*, *> ?: error("entry must be a map")
            val entryId = map.requiredString("id")
            val boxId = map.requiredString("box_id")
            val areaId = map.requiredString("area_id")
            val groups = when (val rawGroups = map["groups"]) {
                null -> emptySet()
                is List<*> -> rawGroups.map { groupRaw ->
                    val group = groupRaw as? Map<*, *> ?: error("group must be a map")
                    ClusterGroup(group.requiredString("name"), group.optionalVec3("offset"))
                }.also { if (it.toSet().size != it.size) error("duplicate group reference") }.toSet()
                else -> error("groups must be a list")
            }
            ClusterEntry(entryId, boxId, areaId, map.optionalVec3("offset"), groups)
        }
        if (entries.map { it.id }.toSet().size != entries.size) error("duplicate entry id")
        return ClusterDefinition(id, entries)
    }

    private fun validateBox(box: BoxTemplate) {
        validateId(box.id)
        require(box.size.x >= 0 && box.size.y >= 0 && box.size.z >= 0) { "size must be >= 0" }
    }

    private fun validateCluster(definition: ClusterDefinition, additionalBoxIds: Set<String> = emptySet()) {
        validateId(definition.id)
        require(!definition.id.any(Char::isWhitespace)) {
            "cluster id must not contain whitespace; it is also the automatic group name"
        }
        require(definition.entries.map { it.id }.toSet().size == definition.entries.size) { "duplicate entry id" }
        definition.entries.forEach { entry ->
            validateId(entry.id)
            validateId(entry.boxId)
            validateId(entry.areaId)
            require(boxesById.containsKey(entry.boxId) || entry.boxId in additionalBoxIds) { "Unknown box_id '${entry.boxId}'" }
            require(areasById.containsKey(entry.areaId)) { "Unknown area_id '${entry.areaId}'" }
            require(entry.groups.size == entry.groups.distinct().size) { "duplicate group reference" }
            entry.groups.forEach {
                require(it.name.isNotBlank() && it.name == it.name.trim() && !it.name.any(Char::isWhitespace)) {
                    "group name must be a nonblank word"
                }
            }
        }
    }

    private fun validateId(id: String) {
        require(id.isNotBlank() && id == id.trim()) { "id must not be blank" }
        val normalized = id.replace('\\', '/')
        require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:/").containsMatchIn(normalized)) { "id must be relative" }
        require(normalized.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "id contains an invalid path segment" }
        require(!normalized.endsWith(".yml", true)) { "id must not include .yml" }
    }

    private fun checkedTarget(directory: String, id: String): File {
        validateId(id)
        val root = File(dataFolder, directory).canonicalFile
        val target = File(root, "$id.yml").canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "id escapes $directory" }
        require(!target.isDirectory) { "target is a directory" }
        return target
    }

    private fun boxYaml(box: BoxTemplate) = YamlConfiguration().also {
        setVec3(it, "offset", box.offset)
        setVec3(it, "size", box.size)
    }

    private fun clusterYaml(definition: ClusterDefinition) = YamlConfiguration().also { yml ->
        yml.set("entries", definition.entries.map { entry ->
            linkedMapOf<String, Any>(
                "id" to entry.id,
                "box_id" to entry.boxId,
                "area_id" to entry.areaId,
                "offset" to vecMap(entry.offset),
                "groups" to entry.groups.map { group ->
                    linkedMapOf<String, Any>(
                        "name" to group.name,
                        "offset" to vecMap(group.offset),
                    )
                },
            )
        })
    }

    private fun setVec3(yml: YamlConfiguration, path: String, vec: Vec3i) {
        yml.set("$path.x", vec.x)
        yml.set("$path.y", vec.y)
        yml.set("$path.z", vec.z)
    }
    private fun vecMap(vec: Vec3i): Map<String, Int> = mapOf("x" to vec.x, "y" to vec.y, "z" to vec.z)
    private fun parseVec3(yml: YamlConfiguration, path: String): Vec3i? {
        if (!yml.isInt("$path.x") || !yml.isInt("$path.y") || !yml.isInt("$path.z")) return null
        return Vec3i(yml.getInt("$path.x"), yml.getInt("$path.y"), yml.getInt("$path.z"))
    }

    private fun Map<*, *>.requiredString(key: String): String {
        val value = this[key] as? String ?: error("$key must be a string")
        return value.trim().takeIf { it.isNotEmpty() } ?: error("missing $key")
    }
    private fun Map<*, *>.optionalVec3(key: String): Vec3i {
        val raw = this[key] ?: return Vec3i(0, 0, 0)
        val map = raw as? Map<*, *> ?: error("$key must be a map")
        fun component(axis: String): Int {
            val value = map[axis]
            require(value is Byte || value is Short || value is Int || value is Long) { "$key.$axis must be an integer" }
            return (value as Number).toLong().let {
                require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$key.$axis is out of range" }
                it.toInt()
            }
        }
        return Vec3i(component("x"), component("y"), component("z"))
    }

    private fun writeYaml(target: File, yml: YamlConfiguration) {
        val temp = tempTarget(target)
        try {
            saveYamlFile(temp, yml)
            replace(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun saveYamlFile(target: File, yml: YamlConfiguration) {
        target.parentFile?.mkdirs()
        yml.save(target)
    }

    private fun replace(source: File, target: File) {
        target.parentFile?.mkdirs()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun replacePair(first: File, firstTemp: File, second: File, secondTemp: File) {
        val firstBackup = backupTarget(first)
        val secondBackup = backupTarget(second)
        var firstInstalled = false
        var secondInstalled = false
        try {
            if (first.exists()) Files.move(first.toPath(), firstBackup.toPath())
            if (second.exists()) Files.move(second.toPath(), secondBackup.toPath())
            replace(firstTemp, first)
            firstInstalled = true
            replace(secondTemp, second)
            secondInstalled = true
        } catch (failure: Throwable) {
            if (firstInstalled) first.delete()
            if (secondInstalled) second.delete()
            restoreBackup(firstBackup, first, failure)
            restoreBackup(secondBackup, second, failure)
            throw failure
        } finally {
            firstBackup.delete()
            secondBackup.delete()
        }
    }

    private fun restoreBackup(backup: File, target: File, failure: Throwable) {
        if (!backup.exists()) return
        try {
            Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (restoreFailure: Throwable) {
            failure.addSuppressed(restoreFailure)
        }
    }
    private fun tempTarget(target: File): File = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    private fun backupTarget(target: File): File = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.bak")

    private fun File.toRelativeId(file: File): String = toPath().relativize(file.toPath()).toString().replace('\\', '/').removeSuffix(".yml")
}
