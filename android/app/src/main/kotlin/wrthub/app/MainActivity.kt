package wrthub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import wrthub.app.ui.WrtHubApp
import wrthub.app.ui.theme.WrtHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WrtHubApplication).container
        setContent { WrtHubTheme { WrtHubApp(container) } }
    }
}
