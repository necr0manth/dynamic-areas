package dev.necr0manthre.dynamicareas

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class DynamicAreas : JavaPlugin() {
    private lateinit var configStore: ConfigStore
    private lateinit var runtime: DynamicAreaRuntime
    private lateinit var areaEventBridge: AreaEventBridge
    private val pivots: MutableMap<UUID, Vec3i> = hashMapOf()

    companion object {
        @Volatile var instance: DynamicAreas? = null
    }

    fun getActiveBoxesByArea(areaId: String): List<ActiveBox> = runtime.getActiveBoxesByArea(areaId)

    override fun onEnable() {
        instance = this
        configStore = ConfigStore(this)
        configStore.ensureDirectories()
        configStore.reloadAll()

        runtime = DynamicAreaRuntime(configStore)
        val listenerExecutor = ListenerCommandExecutor(this)
        areaEventBridge = AreaEventBridge(runtime, listenerExecutor)

        DaCommand.instance = DaCommand(
            configStore = configStore,
            runtime = runtime,
            areaEventBridge = areaEventBridge,
            pivots = pivots,
            worldEditSelectionProvider = WorldEditSelectionProvider(),
        )

        server.pluginManager.registerEvents(areaEventBridge, this)

        // Global tick: update TTL and recalculate player area membership diff.
        server.globalRegionScheduler.runAtFixedRate(this, { _ ->
            runtime.tickTtl()
            areaEventBridge.tickPlayers(Bukkit.getOnlinePlayers())
        }, 1L, 1L)

        logger.info("DynamicAreas enabled")
    }

    override fun onDisable() {
        instance = null
        DaCommand.instance = null
        pivots.clear()
        runtime.clearRuntime()
        areaEventBridge.clearPlayerCache()
    }
}
