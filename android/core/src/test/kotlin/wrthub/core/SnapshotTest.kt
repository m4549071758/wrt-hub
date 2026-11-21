package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import wrthub.core.snapshot.SNAPSHOT_SCHEMA_VERSION
import wrthub.core.snapshot.UnsupportedSnapshotException
import wrthub.core.snapshot.decodeSnapshot
import wrthub.core.snapshot.encodeSnapshot

class SnapshotTest {
    @Test
    fun `all fixtures decode`() {
        for (path in Fixtures.paths) {
            assertEquals(SNAPSHOT_SCHEMA_VERSION, Fixtures.snapshot(path).schemaVersion, path)
        }
    }

    @Test
    fun `encode then decode is lossless`() {
        for (path in Fixtures.paths) {
            val snapshot = Fixtures.snapshot(path)
            assertEquals(snapshot, decodeSnapshot(encodeSnapshot(snapshot)), path)
        }
    }

    @Test
    fun `rejects other schema versions`() {
        val text = Fixtures.text("mesh-site/ap.json").replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertFailsWith<UnsupportedSnapshotException> { decodeSnapshot(text) }
    }

    @Test
    fun `rejects unknown top-level fields`() {
        val text = Fixtures.text("mesh-site/ap.json").replaceFirst("{", "{\"surprise\": true,")
        assertFailsWith<SerializationException> { decodeSnapshot(text) }
    }
}
