package wrthub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import wrthub.app.push.startPush
import wrthub.app.ui.WrtHubApp
import wrthub.app.ui.theme.WrtHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WrtHubApplication).container
        // Notifications come from hub servers only; FCM reports the installation ID again on every start, which re-registers it.
        lifecycleScope.launch {
            if (container.siteStore.sites.first().any { it.hub != null }) startPush(this@MainActivity)
        }
        setContent { WrtHubTheme { WrtHubApp(container) } }
    }
}
