package wrthub.core.ubus

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * One router reached directly over /ubus. rpcd sessions expire after `timeout` seconds of inactivity,
 * so the session is renewed before that instead of reacting to access-denied errors (which rpcd also
 * returns for ACL denials and cannot be told apart).
 */
class DirectNode(
    private val client: UbusHttpClient,
    private val username: String,
    private val password: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : UbusCaller {
    private val mutex = Mutex()
    private var session: UbusSession? = null
    private var lastUsedAt = 0L

    override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject? =
        client.call(activeSession(), obj, method, args)

    private suspend fun activeSession(): UbusSession = mutex.withLock {
        val now = clock()
        val current = session
        val usable = current != null && now - lastUsedAt < (current.timeoutSeconds - RENEW_MARGIN_SECONDS) * 1000
        val active = if (usable) current else client.login(username, password).also { session = it }
        lastUsedAt = now
        active
    }

    private companion object {
        const val RENEW_MARGIN_SECONDS = 30
    }
}
