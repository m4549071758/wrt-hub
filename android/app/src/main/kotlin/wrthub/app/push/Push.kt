package wrthub.app.push

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import wrthub.app.MainActivity
import wrthub.app.R
import wrthub.app.WrtHubApplication
import wrthub.app.data.HubConnections
import wrthub.app.data.SiteRepository
import wrthub.app.ui.hub.eventText

/** A server's notification, as `notify.ts` sends it. */
@Serializable
data class HubNotification(
    val kind: String,
    val siteId: String,
    val siteName: String,
    val nodeId: String? = null,
    val nodeLabel: String? = null,
    val macs: List<String> = emptyList(),
    val at: Long,
)

private val notificationJson = Json { ignoreUnknownKeys = true }

fun parseHubNotification(body: String): HubNotification? = try {
    notificationJson.decodeFromString(HubNotification.serializer(), body)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/**
 * This phone's UnifiedPush endpoint and the hub servers it is registered with: every server that has a site here.
 * A server that cannot be reached keeps the old registration (or none) until the next [registerAll].
 */
class PushRegistry(private val repository: SiteRepository, private val hubs: HubConnections, private val store: DataStore<Preferences>) {
    suspend fun endpoint(): String? = store.data.first()[ENDPOINT]

    /** Moves the registrations to [url]; returns the servers that refused or could not be reached. */
    suspend fun onNewEndpoint(url: String): List<String> {
        val old = endpoint()
        store.edit { it[ENDPOINT] = url }
        if (old != null && old != url) each { it.unregisterPush(old) }
        return each { it.registerPush(url) }
    }

    /** Registers the current endpoint with every server, as after adding a site of a new server. */
    suspend fun registerAll(): List<String> {
        val url = endpoint() ?: return emptyList()
        return each { it.registerPush(url) }
    }

    suspend fun onUnregistered(): List<String> {
        val old = endpoint() ?: return emptyList()
        store.edit { it.remove(ENDPOINT) }
        return each { it.unregisterPush(old) }
    }

    private suspend fun each(call: suspend (wrthub.core.hub.HubClient) -> Unit): List<String> =
        repository.sites.first().mapNotNull { it.hub }.distinctBy { it.serverUrl }.mapNotNull { link ->
            try {
                call(hubs.client(link))
                null
            } catch (e: IOException) {
                "${link.serverUrl}: ${e.message}"
            }
        }

    private companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
    }
}

object EventNotifications {
    private const val CHANNEL = "events"

    fun show(context: Context, notification: HubNotification) {
        // False on Android 13+ until POST_NOTIFICATIONS is granted, and whenever the user turned them off.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_events), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = eventText(context.resources, notification.kind, notification.nodeLabel, notification.macs.size)
        val built = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_router)
            .setContentTitle(notification.siteName)
            .setContentText(text)
            .setWhen(notification.at)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("${notification.siteId}/${notification.nodeId}/${notification.kind}".hashCode(), built)
    }
}

/** Uses the distributor already chosen, or the only/default one, and asks it for an endpoint (it answers in [WrtHubPushService]). */
fun startPush(activity: Activity) {
    UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
        if (ok) UnifiedPush.register(activity) else Log.i(TAG, "no UnifiedPush distributor; notifications are off")
    }
}

fun pushAvailable(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

private const val TAG = "WrtHubPush"

class WrtHubPushService : PushService() {
    private val container get() = (application as WrtHubApplication).container

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        container.appScope.launch {
            container.push.onNewEndpoint(endpoint.url).forEach { Log.w(TAG, "push registration failed: $it") }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val notification = parseHubNotification(message.content.decodeToString())
        if (notification == null) Log.w(TAG, "unreadable push message") else EventNotifications.show(this, notification)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "UnifiedPush registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        container.appScope.launch {
            container.push.onUnregistered().forEach { Log.w(TAG, "push unregistration failed: $it") }
        }
    }
}
