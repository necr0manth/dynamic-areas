package dev.necr0manthre.dynamicareas

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger
import kotlin.test.*

class ClusterStoreTest {
    @TempDir lateinit var directory: File
    private lateinit var store: ConfigStore
    private val box = BoxTemplate("rooms/entrance", Vec3i(-5, 0, 2), Vec3i(2, 3, 4))

    @BeforeEach
    fun prepare() {
        store = ConfigStore(directory, Logger.getAnonymousLogger())
        store.ensureDirectories()
        File(directory, "areas/rules.yml").writeText("block_breaking: deny\n")
        store.reloadAll()
        store.saveBox(box).getOrThrow()
    }

    private fun definition(id: String = "trials/first") = ClusterDefinition(id, listOf(
        ClusterEntry("entrance", box.id, "rules", Vec3i(1, 0, 0), linkedSetOf(
            ClusterGroup("players"), ClusterGroup("shared", Vec3i(10, -1, 0))))))

    @Test
    fun `cluster round trip preserves order references offsets and named groups`() {
        val definition = definition().let { it.copy(entries = it.entries +
            it.entries.single().copy(id = "second", offset = Vec3i(-10, 0, 0))) }
        store.saveCluster(definition).getOrThrow()
        val reloaded = ConfigStore(directory, Logger.getAnonymousLogger())
        reloaded.reloadAll()
        assertEquals(definition, reloaded.clustersById[definition.id])
        assertEquals(box, reloaded.boxesById[box.id])
        assertEquals(TriState.DENY, reloaded.areasById.getValue("rules").blockBreaking)
    }

    @Test
    fun `invalid edits preserve the old file and in memory definition`() {
        val original = definition()
        store.saveCluster(original).getOrThrow()
        val file = File(directory, "clusters/${original.id}.yml")
        val bytes = file.readBytes()
        val invalid = original.copy(entries = original.entries.map { it.copy(boxId = "missing") })
        assertTrue(store.saveCluster(invalid).isFailure)
        assertEquals(original, store.clustersById[original.id])
        assertContentEquals(bytes, file.readBytes())
        assertTrue(store.saveCluster(original.copy(entries = original.entries + original.entries)).isFailure)
        assertContentEquals(bytes, file.readBytes())
    }

    @Test
    fun `nested ids are supported but traversal cannot write outside their directory`() {
        assertTrue(store.saveCluster(definition("../outside")).isFailure)
        assertFalse(File(directory, "outside.yml").exists())
        assertTrue(store.saveCluster(definition("nested/ok")).isSuccess)
        assertTrue(File(directory, "clusters/nested/ok.yml").isFile)
    }

    @Test
    fun `capture saves both box and entry and survives reload`() {
        val captured = box.copy(id = "captured")
        val definition = ClusterDefinition("trial", listOf(ClusterEntry("captured", "captured", "rules")))
        store.saveBoxAndCluster(captured, definition).getOrThrow()
        store.reloadAll()
        assertEquals(captured, store.boxesById["captured"])
        assertEquals(definition, store.clustersById["trial"])
    }

    @Test
    fun `failed capture does not leave a box when cluster cannot be written`() {
        File(directory, "clusters/blocked.yml").mkdirs()
        val captured = box.copy(id = "captured")
        val definition = ClusterDefinition("blocked", listOf(ClusterEntry("captured", "captured", "rules")))
        assertTrue(store.saveBoxAndCluster(captured, definition).isFailure)
        assertFalse(File(directory, "boxes/captured.yml").exists())
        assertNull(store.boxesById["captured"])
        assertNull(store.clustersById["blocked"])
    }

    @Test
    fun `deleting a template does not delete shared boxes or areas`() {
        val definition = definition()
        store.saveCluster(definition).getOrThrow()
        store.deleteCluster(definition.id).getOrThrow()
        assertNull(store.clustersById[definition.id])
        store.reloadAll()
        assertNull(store.clustersById[definition.id])
        assertNotNull(store.boxesById[box.id])
        assertNotNull(store.areasById["rules"])
    }

    @Test
    fun `malformed templates are rejected as a whole on reload`() {
        val malformed = listOf(
            "id: 123\n    box_id: rooms/entrance\n    area_id: rules",
            "id: entry\n    box_id: rooms/entrance\n    area_id: rules\n    offset: {x: 0.5, y: 0, z: 0}",
            "id: entry\n    box_id: missing\n    area_id: rules"
        )
        malformed.forEachIndexed { index, record ->
            File(directory, "clusters/bad$index.yml").writeText("entries:\n  - $record\n")
        }
        store.saveCluster(definition()).getOrThrow()
        store.reloadAll()
        assertEquals(setOf("trials/first"), store.clustersById.keys)
    }
}
