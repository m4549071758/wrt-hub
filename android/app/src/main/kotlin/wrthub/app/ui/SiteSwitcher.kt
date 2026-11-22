package wrthub.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import wrthub.app.R
import wrthub.app.data.SiteConfig

/** The top bar of the per-site tabs: the site name, which opens a menu of the other sites when there are any. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSwitcherBar(sites: List<SiteConfig>, selected: SiteConfig?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Box {
                val title = selected?.name ?: stringResource(R.string.app_name)
                if (sites.size > 1) {
                    TextButton(onClick = { expanded = true }) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.switch_site), tint = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    sites.forEach { site ->
                        DropdownMenuItem(text = { Text(site.name) }, onClick = {
                            expanded = false
                            onSelect(site.id)
                        })
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}
