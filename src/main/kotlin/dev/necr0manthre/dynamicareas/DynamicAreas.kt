package dev.necr0manthre.dynamicareas

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class DynamicAreas : JavaPlugin() {
    private lateinit var configStore: ConfigStore
    private lateinit var runtime: DynamicAreaRuntime
    private lateinit var areaEventBridge: AreaEventBridge
    private lateinit var zoneVisualizer: ZoneVisualizer
    private val pivots: MutableMap<UUID, Vec3i> = hashMapOf()

    companion object {
        @Volatile var instance: DynamicAreas? = null
    }

    fun getActiveBoxesByArea(areaId: String): List<ActiveBox> = runtime.getActiveBoxesByArea(areaId)
    fun getBoxesByGroup(worldId: UUID, pos: Vec3i): List<ActiveBox> = runtime.getBoxesByGroup(GroupKey(worldId, pos))
    fun getBoxesByGroup(groupKey: GroupKey): List<ActiveBox> = runtime.getBoxesByGroup(groupKey)
    fun getSavedVector(vectorId: String): Vec3i? = configStore.vectorsById[vectorId]

    override fun onEnable() {
        instance = this
        configStore = ConfigStore(this)
        configStore.ensureDirectories()
        configStore.reloadAll()

        runtime = DynamicAreaRuntime(configStore)
        val listenerExecutor = ListenerCommandExecutor(this)
        areaEventBridge = AreaEventBridge(runtime, listenerExecutor)
        zoneVisualizer = ZoneVisualizer(runtime)

        DaCommand.instance = DaCommand(
            configStore = configStore,
            runtime = runtime,
            areaEventBridge = areaEventBridge,
            pivots = pivots,
            worldEditSelectionProvider = WorldEditSelectionProvider(),
            zoneVisualizer = zoneVisualizer,
        )

        server.pluginManager.registerEvents(areaEventBridge, this)

        // Global tick: update TTL and recalculate player area membership diff.
        server.globalRegionScheduler.runAtFixedRate(this, { _ ->
            runtime.tickTtl()
            areaEventBridge.tickPlayers(Bukkit.getOnlinePlayers())
        }, 1L, 1L)

        // Particle tick: spawn zone outline particles for visualizing players every 5 ticks.
        server.globalRegionScheduler.runAtFixedRate(this, { _ ->
            zoneVisualizer.tick(Bukkit.getOnlinePlayers())
        }, 5L, 5L)

        logger.info("DynamicAreas enabled")
    }

    override fun onDisable() {
        instance = null
        DaCommand.instance = null
        pivots.clear()
        runtime.clearRuntime()
        areaEventBridge.clearPlayerCache()
        Bukkit.getOnlinePlayers().forEach { zoneVisualizer.removePlayer(it) }
    }
}
