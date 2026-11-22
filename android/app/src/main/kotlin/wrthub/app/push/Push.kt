package wrthub.app.push

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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import wrthub.app.MainActivity
import wrthub.app.R
import wrthub.app.WrtHubApplication
import wrthub.app.data.HubConnections
import wrthub.app.data.SiteRepository
import wrthub.app.ui.hub.eventText
import wrthub.core.hub.HubClient

/** A server's notification, as `notify.ts` sends it in an FCM data message (every value a string). */
data class HubNotification(
    val kind: String,
    val siteId: String,
    val siteName: String,
    val nodeId: String? = null,
    val nodeLabel: String? = null,
    val macs: List<String> = emptyList(),
    val at: Long,
)

fun parseHubNotification(data: Map<String, String>): HubNotification? {
    val kind = data["kind"] ?: return null
    val siteId = data["siteId"] ?: return null
    val siteName = data["siteName"] ?: return null
    val at = data["at"]?.toLongOrNull() ?: return null
    val macs = data["macs"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
    return HubNotification(kind, siteId, siteName, data["nodeId"], data["nodeLabel"], macs, at)
}

/**
 * This phone's FCM registration (its Firebase Installation ID) and the hub servers it is registered with: every
 * server that has a site here. FCM reports the ID again on every start, so a server that could not be reached gets it
 * then.
 */
class PushRegistry(private val repository: SiteRepository, private val hubs: HubConnections, private val store: DataStore<Preferences>) {
    suspend fun installationId(): String? = store.data.first()[INSTALLATION_ID]

    /** Moves the registrations to [id]; returns the servers that refused or could not be reached. */
    suspend fun onRegistered(id: String): List<String> {
        val old = installationId()
        store.edit { it[INSTALLATION_ID] = id }
        if (old != null && old != id) each { it.unregisterPush(old) }
        return each { it.registerPush(id) }
    }

    private suspend fun each(call: suspend (HubClient) -> Unit): List<String> =
        repository.sites.first().mapNotNull { it.hub }.distinctBy { it.serverUrl }.mapNotNull { link ->
            try {
                call(hubs.client(link))
                null
            } catch (e: IOException) {
                "${link.serverUrl}: ${e.message}"
            }
        }

    private companion object {
        val INSTALLATION_ID = stringPreferencesKey("fcm_installation_id")
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

private const val TAG = "WrtHubPush"

private fun playServices(context: Context) =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

/** Notifications need Google Play services (FCM) and the user's permission to post them. */
fun pushAvailable(context: Context): Boolean =
    playServices(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Asks FCM to register this app instance; its Firebase Installation ID arrives in
 * [WrtHubMessagingService.onRegistered], which registers it with every hub server. FCM calls that again on its own
 * whenever the ID changes or on its routine sync.
 */
fun startPush(context: Context) {
    if (!playServices(context)) {
        Log.i(TAG, "no Google Play services; notifications are off")
        return
    }
    FirebaseMessaging.getInstance().register().addOnFailureListener { e -> Log.w(TAG, "FCM registration failed", e) }
}

class WrtHubMessagingService : FirebaseMessagingService() {
    private val container get() = (application as WrtHubApplication).container

    override fun onRegistered(installationId: String) {
        container.appScope.launch {
            container.push.onRegistered(installationId).forEach { Log.w(TAG, "push registration failed: $it") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = parseHubNotification(message.data)
        if (notification == null) Log.w(TAG, "unreadable push message") else EventNotifications.show(this, notification)
    }
}
