package wrthub.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The value at the end of a list row; ListItem would make it labelSmall, too small for a status or an address. */
@Composable
fun TrailingText(text: String, color: Color = Color.Unspecified) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}
