package wrthub.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import wrthub.app.ui.theme.weight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import wrthub.app.R
import wrthub.app.data.SiteConfig

/** Horizontal padding of every screen's content. */
val ScreenPadding = 16.dp

private val CardShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(12.dp)

/** A 40dp outlined circle in a 48dp touch target, as the top bars' back and action buttons. */
@Composable
fun RingButton(icon: Int, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** An unframed icon button with a 48dp touch target. */
@Composable
fun PlainIconButton(icon: Int, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

/** Centered title with a small line under it; [onBack] adds the ringed back button. */
@Composable
fun AppTopBar(
    title: String,
    subtitle: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (onBack != null) {
            RingButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onBack, Modifier.align(Alignment.CenterStart))
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.invoke()
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SubtitleText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The site name with a chevron that opens a menu of the other sites; plain text when there is only one.
 * [suffix] follows the name, as in "Cabin · 5".
 */
@Composable
fun SiteSwitcher(
    sites: List<SiteConfig>,
    selected: SiteConfig?,
    onSelect: (String) -> Unit,
    suffix: String? = null,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = selected?.name ?: stringResource(R.string.app_name)
    val switchable = sites.size > 1
    val switchLabel = stringResource(R.string.switch_site)
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (switchable) {
                        Modifier
                            .clickable(role = Role.Button) { expanded = true }
                            .semantics { onClick(label = switchLabel) { expanded = true; true } }
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = if (switchable) 32.dp else 0.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            if (switchable) Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            if (suffix != null) Text(" · $suffix", style = style, color = color, maxLines = 1)
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
}

/** A bordered card; clickable when [onClick] is set. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = CardShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
            Column(content = content)
        }
    }
}

/** Section heading above a group: a bold title with an optional quiet value on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * One line of a list: icon, title, an optional value line, an optional trailing slot and a chevron when it opens
 * something. Use inside [RowGroup] for joined rows or alone with [standalone] for a separate card.
 */
@Composable
fun AppRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    dim: Boolean = false,
    standalone: Boolean = false,
    chevron: Boolean = onClick != null,
) {
    val colors = MaterialTheme.colorScheme
    val titleColor = if (dim || !enabled) colors.onSurfaceVariant else colors.onSurface
    val base = if (standalone) {
        Modifier
            .clip(RowShape)
            .background(colors.surface)
            .border(1.dp, colors.outline, RowShape)
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(base)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(start = 14.dp, end = if (chevron) 8.dp else 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = if (dim) colors.outline else iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                subtitleContent != null -> subtitleContent()
                subtitle != null -> Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
        }
        if (chevron) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
    }
}

/** Rows joined in one bordered card; put [GroupDivider] between them. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline)

/** Label and value lines in one card, as in an overview. */
@Composable
fun KeyValueCard(rows: List<Pair<String, @Composable () -> Unit>>, modifier: Modifier = Modifier) {
    AppCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    Spacer(Modifier.weight(1f))
                    value()
                }
            }
        }
    }
}

@Composable
fun ValueText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A small outlined tag such as "5 GHz" or "Wired". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** A colored dot followed by text, for a state. */
@Composable
fun StatusText(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pill segments; the selected one is filled with the text color. */
@Composable
fun <T> SegmentedToggle(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) colors.onSurface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (on) MaterialTheme.typography.labelMedium.weight(FontWeight.SemiBold) else MaterialTheme.typography.labelMedium,
                    color = if (on) colors.background else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** The filled pill button: the screen's main action. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Int = 50) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = height.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.primary else colors.outline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) colors.onPrimary else colors.onSurfaceVariant, maxLines = 1)
    }
}

/** The outlined pill button: a secondary action next to [PrimaryButton]. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Int = 50) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = height.dp)
            .clip(CircleShape)
            .border(1.dp, colors.outline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.weight(FontWeight.Medium),
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * A label over an underlined input, as in the reference's "Add Device". The text is owned by the caller: [value]
 * replaces the field's text when it differs, and every edit is reported through [onValueChange].
 */
@Composable
fun UnderlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val state = rememberTextFieldState(value)
    val report by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != current) report(it) }
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
    val keyboard = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction)
    val action = onImeAction?.let { run -> KeyboardActionHandler { run() } }
    val fieldModifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    val decorator = TextFieldDecorator { inner ->
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (state.text.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                }
                inner()
            }
            if (password) {
                PlainIconButton(
                    if (reveal) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    stringResource(if (reveal) R.string.hide_password else R.string.show_password),
                    onClick = { reveal = !reveal },
                )
            }
            trailing?.invoke()
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (error != null) colors.error else colors.onSurfaceVariant)
        if (password) {
            BasicSecureTextField(
                state = state,
                modifier = fieldModifier.semantics { contentDescription = label },
                enabled = enabled,
                textObfuscationMode = if (reveal) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                textStyle = textStyle,
                keyboardOptions = keyboard,
                onKeyboardAction = action,
                cursorBrush = SolidColor(colors.onSurface),
                decorator = decorator,
            )
        } else {
            BasicTextField(
                state = state,
                modifier = fieldModifier.semantics { contentDescription = label },
                enabled = enabled,
                textStyle = textStyle,
                keyboardOptions = keyboard,
                onKeyboardAction = action,
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
                cursorBrush = SolidColor(colors.onSurface),
                decorator = decorator,
            )
        }
        HorizontalDivider(
            thickness = if (focused) 1.5.dp else 1.dp,
            color = when {
                error != null -> colors.error
                focused -> colors.onSurface
                else -> colors.outline
            },
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A square check filled with the text color. */
@Composable
fun AppCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (checked) Modifier.background(colors.onSurface) else Modifier.border(1.5.dp, colors.onSurfaceVariant, RoundedCornerShape(6.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.background, modifier = Modifier.size(16.dp))
    }
}

/**
 * A knob that has to be dragged to the end to run [onConfirm], for actions that interrupt the network. TalkBack
 * users get a plain click action with the same [label].
 */
@Composable
fun SlideToConfirm(label: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (enabled) onClick(label = label) { onConfirm(); true }
            }
            .padding(4.dp),
    ) {
        val knobWidth = 64.dp
        val max = with(density) { (maxWidth - knobWidth).toPx() }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .width(knobWidth)
                .height(48.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (enabled) colors.onSurface else colors.outline)
                .draggable(
                    enabled = enabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, max)) } },
                    onDragStopped = {
                        if (offset.value >= max * 0.9f) {
                            offset.animateTo(max)
                            onConfirm()
                        }
                        offset.animateTo(0f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = colors.background, modifier = Modifier.size(20.dp))
        }
    }
}

/** A card announcing a problem: icon and title, a factual line, and up to two actions. */
@Composable
fun AlertCard(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium.weight(FontWeight.SemiBold))
            }
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (primary != null || secondary != null) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    secondary?.let { (text, action) -> SecondaryButton(text, action, Modifier.weight(1f), height = 40) }
                    primary?.let { (text, action) -> PrimaryButton(text, action, Modifier.weight(1f), height = 40) }
                }
            }
        }
    }
}

/**
 * A number with its unit and a line under it, over a sparkline of [history]; the sparkline is drawn in the text
 * color, as in the reference, so it reads in both themes.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    history: List<Double> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    AppCard(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            if (onClick != null) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            if (unit != null) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall.weight(FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                )
            }
        }
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))
        }
        Sparkline(history, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth().height(44.dp).padding(top = 6.dp))
    }
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().takeIf { it > 0 } ?: 1.0
        val top = 4.dp.toPx()
        val stepX = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, value ->
            val x = i * stepX
            val y = top + (size.height - top) * (1 - (value / max).toFloat())
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 1.4.dp.toPx()))
    }
}

/** Content padding for a screen's list, leaving room under the last item. */
val ListPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 24.dp)

class NavItem(val label: String, val icon: Int, val selectedIcon: Int, val selected: Boolean, val onClick: () -> Unit)

/** Line icons with small labels; the selected tab is filled and bold, with no indicator pill. */
@Composable
fun AppNavBar(items: List<NavItem>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(color = colors.outline)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            items.forEach { item ->
                val tint = if (item.selected) colors.onSurface else colors.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(if (item.selected) item.selectedIcon else item.icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(
                        item.label,
                        style = if (item.selected) MaterialTheme.typography.labelSmall.weight(FontWeight.SemiBold) else MaterialTheme.typography.labelSmall,
                        color = tint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
