package wrthub.core.hub

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

@Serializable
data class HubOidc(val issuer: String, val clientId: String, val audience: String)

@Serializable
data class HubAuthOptions(val pairing: Boolean, val oidc: HubOidc? = null)

@Serializable
data class HubFeatures(val starlink: Boolean = false)

@Serializable
data class HubConfig(val auth: HubAuthOptions, val features: HubFeatures = HubFeatures())

@Serializable
data class HubPairing(val deviceId: String, val token: String)

/** [state]: `fresh`, `stale` (late), `offline` (silent for long) or `never` (no snapshot since the server started). */
@Serializable
data class HubNodeInfo(val id: String, val label: String, val state: String, val receivedAt: Long? = null)

@Serializable
data class HubSite(val id: String, val name: String, val nodes: List<HubNodeInfo>)

@Serializable
data class HubSites(val serverTime: Long, val sites: List<HubSite>)

@Serializable
data class HubNodeSnapshot(
    val id: String,
    val label: String,
    val state: String,
    val receivedAt: Long? = null,
    val snapshot: JsonObject? = null,
)

@Serializable
data class HubSnapshots(val serverTime: Long, val nodes: List<HubNodeSnapshot>)

/** One snapshot pushed over the site's event stream. */
@Serializable
data class HubSnapshotEvent(val nodeId: String, val receivedAt: Long, val serverTime: Long, val snapshot: JsonObject)

@Serializable
data class HubCommand(
    val id: String,
    val nodeId: String,
    val name: String,
    val status: String,
    val result: JsonElement? = null,
)

@Serializable
data class HubEvent(val id: Long, val siteId: String, val nodeId: String? = null, val kind: String, val detail: JsonElement? = null, val createdAt: Long)

@Serializable
private data class HubEvents(val events: List<HubEvent>)

@Serializable
data class HubCounterSample(val at: Long, val devices: Map<String, HubDeviceCounter>)

@Serializable
data class HubDeviceCounter(val rx: Long, val tx: Long)

@Serializable
private data class HubCounters(val samples: List<HubCounterSample>)

@Serializable
data class StarlinkSeries(val field: String, val points: List<List<Double>>)

@Serializable
data class StarlinkData(val range: String, val series: List<StarlinkSeries>)

/** The server answered with an HTTP error; [status] 401 means the credential is no longer accepted. */
class HubHttpException(val status: Int, message: String) : IOException(message)

/** A command came back failed or expired, or the agent's answer did not have the expected shape. */
class HubCommandException(message: String) : IOException(message)

/**
 * The app side of the hub server's API (`/app/v1`). [token] supplies the bearer token on every request (a paired
 * device's token, or a fresh OIDC access token); null leaves requests unauthenticated, which only config and pair
 * accept.
 */
class HubClient(
    serverUrl: String,
    private val http: OkHttpClient,
    private val token: (suspend () -> String)? = null,
) {
    private val base: HttpUrl = normalizeHubUrl(serverUrl).toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid server address: $serverUrl")

    suspend fun config(): HubConfig = get("config", HubConfig.serializer())

    suspend fun pair(code: String): HubPairing = post("pair", buildJsonObject { put("code", code) }, HubPairing.serializer())

    suspend fun sites(): HubSites = get("sites", HubSites.serializer())

    suspend fun snapshots(siteId: String): HubSnapshots = get("sites/$siteId/snapshots", HubSnapshots.serializer())

    suspend fun events(siteId: String, limit: Int = 50): List<HubEvent> =
        get("sites/$siteId/events?limit=$limit", HubEvents.serializer()).events

    suspend fun counters(nodeId: String): List<HubCounterSample> = get("nodes/$nodeId/counters", HubCounters.serializer()).samples

    suspend fun starlink(range: String): StarlinkData = get("starlink?range=$range", StarlinkData.serializer())

    suspend fun registerPush(endpoint: String) = send("POST", "push", buildJsonObject { put("endpoint", endpoint) })

    suspend fun unregisterPush(endpoint: String) = send("DELETE", "push", buildJsonObject { put("endpoint", endpoint) })

    /**
     * Issues [name] on [nodeId] and waits for its outcome, long-polling the server. Returns the agent's result for a
     * succeeded command; a failed or expired one throws [HubCommandException].
     */
    suspend fun run(nodeId: String, name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val body = buildJsonObject {
            put("name", name)
            put("args", args)
        }
        var command = post("nodes/$nodeId/commands", body, HubCommand.serializer())
        while (command.status == "pending" || command.status == "sent") {
            command = get("commands/${command.id}?wait=$WAIT_SECONDS", HubCommand.serializer())
        }
        return when (command.status) {
            "succeeded" -> command.result
            "expired" -> throw HubCommandException("the node did not answer $name in time")
            else -> throw HubCommandException("$name failed on the node: ${command.result}")
        }
    }

    /**
     * Snapshots of [siteId] as the server receives them: first the current ones, then each new one. The flow ends with
     * an exception when the stream breaks; callers fall back to polling and retry.
     */
    fun stream(siteId: String): Flow<HubSnapshotEvent> = callbackFlow {
        val request = requestBuilder(url("sites/$siteId/stream")).header("Accept", "text/event-stream").build()
        val source = EventSources.createFactory(http.newBuilder().readTimeout(STREAM_READ_TIMEOUT).build())
            .newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (type == "snapshot") trySend(hubJson.decodeFromString(HubSnapshotEvent.serializer(), data))
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        close(t ?: response?.let { HubHttpException(it.code, "event stream: HTTP ${it.code}") } ?: IOException("event stream failed"))
                    }

                    override fun onClosed(eventSource: EventSource) {
                        close(IOException("event stream closed by the server"))
                    }
                },
            )
        awaitClose { source.cancel() }
    }

    private suspend fun requestBuilder(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        token?.let { builder.header("Authorization", "Bearer ${it()}") }
        return builder
    }

    private fun url(path: String): HttpUrl = "${base}app/v1/$path".toHttpUrlOrNull() ?: throw IllegalArgumentException("bad path $path")

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        hubJson.decodeFromString(serializer, execute(requestBuilder(url(path)).get().build()))

    private suspend fun <T> post(path: String, body: JsonObject, serializer: KSerializer<T>): T =
        hubJson.decodeFromString(serializer, execute(requestBuilder(url(path)).post(body.toBody()).build()))

    private suspend fun send(method: String, path: String, body: JsonObject) {
        execute(requestBuilder(url(path)).method(method, body.toBody()).build())
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val reason = runCatching { hubJson.decodeFromString(HubError.serializer(), text).error }.getOrNull() ?: "HTTP ${response.code}"
                throw HubHttpException(response.code, "${request.method} ${request.url.encodedPath}: $reason")
            }
            text
        }
    }

    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON)

    private companion object {
        const val WAIT_SECONDS = 25
        val STREAM_READ_TIMEOUT = java.time.Duration.ofSeconds(60)
        val JSON = "application/json".toMediaType()
    }
}

@Serializable
private data class HubError(val error: String)

internal val hubJson = Json { ignoreUnknownKeys = true }

/** `https://host[:port]/` (with a trailing slash), from what a user types or from a pairing link's server. */
fun normalizeHubUrl(input: String): String {
    val trimmed = input.trim()
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid server address: $input")
    return url.newBuilder().query(null).fragment(null).build().toString().let { if (it.endsWith("/")) it else "$it/" }
}

/** A `wrthub://pair?server=…&code=…` link as the admin CLI prints it (and its QR code holds). */
data class PairingLink(val server: String, val code: String)

fun parsePairingLink(text: String): PairingLink? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("wrthub://pair?")) return null
    val query = trimmed.removePrefix("wrthub://pair?")
    val params = query.split("&").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to java.net.URLDecoder.decode(part.substring(eq + 1), Charsets.UTF_8)
    }.toMap()
    val server = params["server"] ?: return null
    val code = params["code"] ?: return null
    return PairingLink(server, code)
}
