package dev.necr0manthre.dynamicareas

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ConfigStore(private val plugin: JavaPlugin) {
    val boxesById: MutableMap<String, BoxTemplate> = linkedMapOf()
    val areasById: MutableMap<String, AreaDefinition> = linkedMapOf()

    fun ensureDirectories() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }
        File(plugin.dataFolder, "areas").mkdirs()
        File(plugin.dataFolder, "boxes").mkdirs()
    }

    fun reloadAll() {
        boxesById.clear()
        areasById.clear()
        loadBoxes()
        loadAreas()
    }

    private fun loadBoxes() {
        val root = File(plugin.dataFolder, "boxes")
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .forEach { file ->
                val id = root.toRelativeId(file)
                val yml = YamlConfiguration.loadConfiguration(file)
                val offset = parseVec3(yml, "offset") ?: run {
                    plugin.logger.warning("Box '$id' skipped: missing offset")
                    return@forEach
                }
                val size = parseVec3(yml, "size") ?: run {
                    plugin.logger.warning("Box '$id' skipped: missing size")
                    return@forEach
                }
                if (size.x < 0 || size.y < 0 || size.z < 0) {
                    plugin.logger.warning("Box '$id' skipped: size must be >= 0")
                    return@forEach
                }
                boxesById[id] = BoxTemplate(id, offset, size)
            }
    }

    private fun loadAreas() {
        val root = File(plugin.dataFolder, "areas")
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .forEach { file ->
                val id = root.toRelativeId(file)
                val yml = YamlConfiguration.loadConfiguration(file)

                val listenersMap = linkedMapOf<AreaEventType, MutableList<String>>()
                yml.getMapList("listeners").forEach { entry ->
                    entry.forEach { (rawKey, rawValue) ->
                        val key = rawKey?.toString() ?: return@forEach
                        val eventType = AreaEventType.fromKey(key)
                        if (eventType == null) {
                            plugin.logger.warning("Area '$id' has unknown listener event '$key'")
                            return@forEach
                        }
                        val command = rawValue?.toString()?.trim().orEmpty()
                        if (command.isNotEmpty()) {
                            listenersMap.computeIfAbsent(eventType) { mutableListOf() }.add(command)
                        }
                    }
                }

                areasById[id] = AreaDefinition(
                    id = id,
                    blockBreaking = TriState.fromRaw(yml.getString("block_breaking")),
                    blockPlacing = TriState.fromRaw(yml.getString("block_placing")),
                    interactions = TriState.fromRaw(yml.getString("interactions")),
                    listeners = listenersMap,
                )
            }
    }

    fun saveBox(box: BoxTemplate): Result<Unit> {
        return runCatching {
            val target = File(plugin.dataFolder, "boxes/${box.id}.yml")
            target.parentFile?.mkdirs()
            val yml = YamlConfiguration()
            yml.set("offset.x", box.offset.x)
            yml.set("offset.y", box.offset.y)
            yml.set("offset.z", box.offset.z)
            yml.set("size.x", box.size.x)
            yml.set("size.y", box.size.y)
            yml.set("size.z", box.size.z)
            yml.save(target)
            boxesById[box.id] = box
        }
    }

    private fun parseVec3(yml: YamlConfiguration, path: String): Vec3i? {
        if (!yml.isInt("$path.x") || !yml.isInt("$path.y") || !yml.isInt("$path.z")) {
            return null
        }
        return Vec3i(yml.getInt("$path.x"), yml.getInt("$path.y"), yml.getInt("$path.z"))
    }

    private fun File.toRelativeId(file: File): String {
        val relative = this.toPath().relativize(file.toPath()).toString().replace('\\', '/')
        return relative.removeSuffix(".yml")
    }
}

