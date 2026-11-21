package wrthub.core.ubus

import java.io.InputStream

/**
 * Runs a command on the node and hands its standard output to [read] as a stream. For output too large for one ubus
 * message (rpcd's `file.exec` buffers everything); LuCI reads package lists the same way through cgi-io.
 */
interface CommandStreamer {
    suspend fun <T> stream(command: String, params: List<String>, read: (InputStream) -> T): T
}

/** cgi-io splits `command` on unescaped whitespace, so spaces and backslashes inside an argument are escaped. */
fun cgiCommandLine(command: String, params: List<String>): String =
    (listOf(command) + params).joinToString(" ") { part -> part.replace("\\", "\\\\").replace(Regex("(\\s)"), "\\\\$1") }
