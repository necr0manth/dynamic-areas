package dev.necr0manthre.dynamicareas

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class ListenerCommandExecutor(private val plugin: JavaPlugin) {

    fun execute(areaId: String, commands: List<String>, context: Map<String, String>) {
        commands.forEach { raw ->
            val command = applyContext(raw, context)
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            } catch (ex: Exception) {
                plugin.logger.warning("Listener command failed in area '$areaId': '$raw' (${ex.message})")
            }
        }
    }

    private fun applyContext(raw: String, context: Map<String, String>): String {
        var result = raw
        context.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return result
    }
}

