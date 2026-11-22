package wrthub.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import wrthub.app.R
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.ScreenPadding

/** How a new site is reached: its routers directly, or a WrtHub server that their agents report to. */
@Composable
fun AddSiteScreen(onDirect: () -> Unit, onHub: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(stringResource(R.string.add_site), onBack = onBack)
        Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppRow(
                stringResource(R.string.add_site_direct),
                icon = R.drawable.ic_router,
                subtitle = stringResource(R.string.add_site_direct_hint),
                onClick = onDirect,
                standalone = true,
            )
            AppRow(
                stringResource(R.string.add_site_hub),
                icon = R.drawable.ic_hub,
                subtitle = stringResource(R.string.add_site_hub_hint),
                onClick = onHub,
                standalone = true,
            )
        }
    }
}
