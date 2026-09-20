package dev.necr0manthre.dynamicareas

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.bukkit.Location
import org.bukkit.World
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.*

class ClusterRuntimeTest {
    @TempDir lateinit var directory: File
    private lateinit var store: ConfigStore
    private lateinit var runtime: DynamicAreaRuntime
    private val world = UUID.randomUUID()
    private val pivot = Vec3i(100, 64, 100)
    private val zero = Vec3i(0, 0, 0)

    @BeforeEach
    fun prepare() {
        store = ConfigStore(directory, Logger.getAnonymousLogger())
        store.boxesById["box"] = BoxTemplate("box", Vec3i(1, 2, 3), Vec3i(4, 5, 6))
        store.areasById["rules"] = AreaDefinition("rules", TriState.DENY, TriState.IGNORE,
            TriState.IGNORE, ProtectState.DEFAULT, emptyMap())
        runtime = DynamicAreaRuntime(store)
    }

    private fun entry(id: String = "entrance", offset: Vec3i = zero,
                      groups: Set<ClusterGroup> = emptySet()) =
        ClusterEntry(id, "box", "rules", offset, groups)

    private fun define(id: String = "trial", vararg entries: ClusterEntry) {
        store.clustersById[id] = ClusterDefinition(id, entries.toList())
    }

    private fun boxes(name: String, pos: Vec3i = pivot, worldId: UUID = world) =
        runtime.getBoxesByGroup(GroupKey(worldId, pos, name))

    @Test
    fun `copies and named groups are isolated while entry offsets do not move the group pivot`() {
        define(entries = arrayOf(entry(offset = Vec3i(10, 0, 0), groups = setOf(
            ClusterGroup("players"), ClusterGroup("triggers"), ClusterGroup("shared", Vec3i(0, 0, 20))))))
        val second = Vec3i(500, 64, 100)
        val otherWorld = UUID.randomUUID()
        runtime.setCluster("trial", world, pivot).getOrThrow()
        runtime.setCluster("trial", world, second).getOrThrow()
        runtime.setCluster("trial", otherWorld, pivot).getOrThrow()

        assertEquals(3, runtime.getAllActiveBoxes().size)
        assertEquals(Vec3i(111, 66, 103), boxes("trial").single().absolute.min)
        assertEquals(boxes("trial"), boxes("players"))
        assertEquals(boxes("trial"), boxes("triggers"))
        assertEquals(boxes("trial"), boxes("shared", Vec3i(100, 64, 120)))
        assertEquals(1, boxes("trial", second).size)
        assertEquals(1, boxes("trial", worldId = otherWorld).size)
        assertTrue(boxes("players", Vec3i(110, 64, 100)).isEmpty())
    }

    @Test
    fun `refresh does not duplicate boxes and applies geometry groups and deleted entries`() {
        define(entries = arrayOf(entry(groups = setOf(ClusterGroup("old"))), entry("walls")))
        repeat(5) { runtime.setCluster("trial", world, pivot, 20).getOrThrow() }
        assertEquals(2, runtime.getAllActiveBoxes().size)
        store.boxesById["box"] = BoxTemplate("box", zero, Vec3i(1, 1, 1))
        define(entries = arrayOf(entry(offset = Vec3i(5, 0, 0), groups = setOf(ClusterGroup("new")))))
        runtime.setCluster("trial", world, pivot, 20).getOrThrow()

        assertEquals(1, runtime.getAllActiveBoxes().size)
        assertTrue(boxes("old").isEmpty())
        assertEquals(Vec3i(105, 64, 100), boxes("new").single().absolute.min)
        assertEquals(Vec3i(106, 65, 101), boxes("new").single().absolute.max)
        define(entries = emptyArray())
        runtime.setCluster("trial", world, pivot, 20).getOrThrow()
        assertTrue(runtime.getAllActiveBoxes().isEmpty())
        assertTrue(boxes("trial").isEmpty())
    }

    @Test
    fun `shared group membership does not merge cluster or direct lifetimes`() {
        val shared = setOf(ClusterGroup("shared"))
        define("first", entry(groups = shared))
        define("second", entry(groups = shared))
        runtime.setCluster("first", world, pivot, 2).getOrThrow()
        runtime.setCluster("second", world, pivot, 5).getOrThrow()
        runtime.addOrRefreshBox("rules", "box", world, pivot, 8,
            setOf(GroupKey(world, pivot, "shared"))).getOrThrow()
        assertEquals(3, boxes("shared").size)

        repeat(2) { runtime.tickTtl() }
        assertTrue(boxes("first").isEmpty())
        assertEquals(2, boxes("shared").size)
        define("second", *emptyArray())
        runtime.setCluster("second", world, pivot, 5).getOrThrow()
        assertEquals(1, boxes("shared").size)
        repeat(6) { runtime.tickTtl() }
        assertTrue(runtime.getAllActiveBoxes().isEmpty())
        assertTrue(boxes("shared").isEmpty())
    }

    @Test
    fun `a separate redstone box expires while main cluster keeps refreshing its group`() {
        define(entries = arrayOf(entry()))
        runtime.addOrRefreshBox("rules", "box", world, pivot, 2,
            setOf(GroupKey(world, pivot, "trial"))).getOrThrow()
        repeat(3) {
            runtime.setCluster("trial", world, pivot, 2).getOrThrow()
            runtime.tickTtl()
        }
        assertEquals(1, boxes("trial").size)
    }

    @Test
    fun `invalid cluster cannot partially replace an existing placement`() {
        define(entries = arrayOf(entry()))
        runtime.setCluster("trial", world, pivot, 20).getOrThrow()
        val before = runtime.getAllActiveBoxes().toList()
        define(entries = arrayOf(entry(offset = Vec3i(50, 0, 0)), entry("broken").copy(areaId = "missing")))

        assertTrue(runtime.setCluster("trial", world, pivot, 20).isFailure)
        assertEquals(before, runtime.getAllActiveBoxes())
        assertTrue(runtime.setCluster("trial", world, Vec3i(500, 64, 100)).isFailure)
        assertEquals(before, runtime.getAllActiveBoxes())
    }

    @Test
    fun `direct refresh replaces groups and geometry and expiration cleans indexes`() {
        runtime.addOrRefreshBox("rules", "box", world, pivot, 2,
            setOf(GroupKey(world, pivot, "old"))).getOrThrow()
        store.boxesById["box"] = BoxTemplate("box", zero, zero)
        runtime.addOrRefreshBox("rules", "box", world, pivot, 2,
            setOf(GroupKey(world, pivot, "new"))).getOrThrow()
        assertTrue(boxes("old").isEmpty())
        assertEquals(pivot, boxes("new").single().absolute.min)
        assertEquals(pivot, boxes("new").single().absolute.max)
        repeat(2) { runtime.tickTtl() }
        assertTrue(boxes("new").isEmpty())
    }

    @Test
    fun `moving geometry and changing area reconciles the spatial index`() {
        val worldHandle = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
            when (method.name) {
                "getUID" -> world
                "getName" -> "test"
                else -> error("Unexpected World call: ${method.name}")
            }
        } as World
        fun areasAt(x: Double) = runtime.resolveAreasAtBlock(Location(worldHandle, x, 66.0, 103.0))
        define(entries = arrayOf(entry()))
        runtime.setCluster("trial", world, pivot).getOrThrow()
        assertEquals(setOf("rules"), areasAt(101.0))

        store.areasById["other"] = store.areasById.getValue("rules").copy(id = "other")
        define(entries = arrayOf(entry(offset = Vec3i(32, 0, 0)).copy(areaId = "other")))
        runtime.setCluster("trial", world, pivot).getOrThrow()
        assertTrue(areasAt(101.0).isEmpty())
        assertEquals(setOf("other"), areasAt(133.0))
        repeat(2) { runtime.tickTtl() }
        assertTrue(areasAt(133.0).isEmpty())
    }
}
