package wrthub.core

import java.io.File
import wrthub.core.snapshot.Snapshot
import wrthub.core.snapshot.decodeSnapshot

object Fixtures {
    private val dir = File(requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir system property is not set" })

    val paths = listOf("mesh-site/router.json", "mesh-site/ap.json", "wired-site/router.json")

    fun text(path: String): String = File(dir, path).readText()

    fun snapshot(path: String): Snapshot = decodeSnapshot(text(path))

    val meshSite: Map<String, Snapshot>
        get() = linkedMapOf("router" to snapshot("mesh-site/router.json"), "ap" to snapshot("mesh-site/ap.json"))

    val wiredSite: Map<String, Snapshot>
        get() = linkedMapOf("router" to snapshot("wired-site/router.json"))
}
