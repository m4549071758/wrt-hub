package wrthub.core.ubus

import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import wrthub.core.json.arr
import wrthub.core.json.int
import wrthub.core.json.long
import wrthub.core.json.obj
import wrthub.core.json.str

data class UbusSession(val id: String, val timeoutSeconds: Long)

/** JSON-RPC client for uhttpd-mod-ubus at [endpoint] (usually `http(s)://router/ubus`). */
class UbusHttpClient(private val endpoint: HttpUrl, private val http: OkHttpClient) {
    private val nextId = AtomicLong(1)

    suspend fun login(username: String, password: String): UbusSession {
        val args = buildJsonObject {
            put("username", username)
            put("password", password)
        }
        val data = invoke(ANONYMOUS_SESSION, "session", "login", args)
            ?: throw UbusException(UbusException.NO_DATA, "session.login returned no data")
        val id = data.str("ubus_rpc_session")
            ?: throw UbusException(UbusException.NO_DATA, "session.login returned no ubus_rpc_session")
        return UbusSession(id, data.long("timeout") ?: DEFAULT_TIMEOUT_SECONDS)
    }

    suspend fun call(session: UbusSession, obj: String, method: String, args: JsonObject = EMPTY_ARGS): JsonObject? =
        invoke(session.id, obj, method, args)

    /**
     * Streams [commandLine]'s output from cgi-io next to /ubus. cgi-io checks the same ACL as `file.exec`
     * (the whole command line must be granted) and answers 403 when it is not.
     */
    suspend fun <T> cgiExec(session: UbusSession, commandLine: String, read: (InputStream) -> T): T {
        val url = endpoint.newBuilder().encodedPath("/cgi-bin/cgi-exec").build()
        // Built by hand to encode like LuCI's encodeURIComponent (space as %20, never '+').
        val form = listOf("sessionid" to session.id, "command" to commandLine, "stderr" to "0")
            .joinToString("&") { (key, value) -> "$key=${uriComponent(value)}" }
        val request = Request.Builder().url(url).post(form.toRequestBody(FORM_MEDIA_TYPE)).build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> read(response.body.byteStream())
                    403 -> throw UbusException(UbusException.PERMISSION_DENIED, "cgi-exec: $commandLine is not permitted")
                    404 -> throw UbusException(UbusException.NOT_FOUND, "cgi-exec: not available on this node")
                    else -> throw IOException("HTTP ${response.code} from $url")
                }
            }
        }
    }

    private suspend fun invoke(sid: String, obj: String, method: String, args: JsonObject): JsonObject? {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextId.getAndIncrement())
            put("method", "call")
            putJsonArray("params") {
                add(sid)
                add(obj)
                add(method)
                add(args)
            }
        }
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $endpoint for $obj.$method")
                response.body.string()
            }
        }
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IOException("JSON-RPC response for $obj.$method is not an object")
        root.obj("error")?.let { error ->
            throw UbusException(error.int("code") ?: 0, "$obj.$method: ${error.str("message") ?: "JSON-RPC error"}")
        }
        val result = root.arr("result") ?: throw IOException("JSON-RPC response for $obj.$method has no result")
        val status = (result.firstOrNull() as? JsonPrimitive)?.intOrNull
            ?: throw IOException("JSON-RPC result for $obj.$method has no status")
        if (status != 0) throw UbusException(status, "$obj.$method failed with ubus status $status")
        return result.getOrNull(1) as? JsonObject
    }

    companion object {
        const val ANONYMOUS_SESSION = "00000000000000000000000000000000"
        const val DEFAULT_TIMEOUT_SECONDS = 300L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}

/** `encodeURIComponent`: everything but unreserved characters is %-escaped, so '+' stays a literal plus. */
private fun uriComponent(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
