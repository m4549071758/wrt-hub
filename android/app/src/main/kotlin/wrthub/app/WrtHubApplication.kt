package wrthub.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import wrthub.app.data.DirectNodeVerifier
import wrthub.app.data.KeystoreSecretCipher
import wrthub.app.data.NodeConnections
import wrthub.app.data.NodeVerifying
import wrthub.app.data.SecretCipher
import wrthub.app.data.SiteStore
import wrthub.app.domain.CounterReading
import wrthub.app.domain.DirectCounterReader
import wrthub.app.domain.SiteLoader
import wrthub.app.domain.SiteLoading

class WrtHubApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Process-wide singletons; DataStore must have exactly one instance per file. */
class AppContainer(context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    val siteStore = SiteStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("sites") })
    val cipher: SecretCipher = KeystoreSecretCipher()
    private val connections = NodeConnections(http, cipher)
    val siteLoader: SiteLoading = SiteLoader(connections::node)
    val counters: CounterReading = DirectCounterReader(connections::node)
    val verifier: NodeVerifying = DirectNodeVerifier(http)
}
