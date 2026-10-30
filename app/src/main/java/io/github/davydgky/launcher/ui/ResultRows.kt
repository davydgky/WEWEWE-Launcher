package io.github.davydgky.launcher.ui

import android.graphics.Rect
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.Snippets
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.messages.MessageTarget
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ContactTarget
import io.github.davydgky.launcher.search.EventTarget
import io.github.davydgky.launcher.search.FileTarget
import io.github.davydgky.launcher.search.ImageTarget
import io.github.davydgky.launcher.search.SettingTarget
import io.github.davydgky.launcher.search.ShortcutTarget
import io.github.davydgky.launcher.search.WebTarget
import io.github.davydgky.launcher.search.rememberIcon
import io.github.davydgky.launcher.sources.Velo
import io.github.davydgky.launcher.ui.glass.LocalGlassColors
import java.util.Calendar
import java.util.TimeZone

/** Remembers where an element is on screen, for launch animations that grow out of it. */
class ScreenBounds {
    var rect: Rect? = null
        private set

    fun update(coordinates: LayoutCoordinates) {
        val position = coordinates.positionOnScreen()
        rect = Rect(position.x.toInt(), position.y.toInt(), position.x.toInt() + coordinates.size.width, position.y.toInt() + coordinates.size.height)
    }
}

/** One result: icon, title, a second line and optional actions on the right. */
@Composable
fun ResultRow(
    item: SearchItem,
    query: String,
    onOpen: (SearchItem, Rect?) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val bounds = remember { ScreenBounds() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (large) 72.dp else 60.dp)
            .onGloballyPositioned(bounds::update)
            .clickable { onOpen(item, bounds.rect) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResultIcon(item, if (large) 52.dp else 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = LocalGlassColors.current.content,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val second = secondLine(item, query)
            if (second.isNotBlank()) {
                Text(
                    text = second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalGlassColors.current.secondaryContent,
                    maxLines = if (item.payload is ImageTarget) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Subtitle, or for matches inside long text the passage that matched. */
private fun secondLine(item: SearchItem, query: String): String {
    if (item.body.isNotEmpty()) {
        val snippet = Snippets.around(item.body, query)
        if (snippet != null && !item.title.contains(snippet.trim('…'), ignoreCase = true)) {
            return if (item.subtitle.isBlank()) snippet else "${item.subtitle} — $snippet"
        }
    }
    return item.subtitle
}

/** E-mail, message and call buttons, as far as the contact has an address and a number. */
@Composable
fun ContactActions(contact: ContactTarget, onCall: () -> Unit, onMessage: () -> Unit, onMail: () -> Unit) {
    if (contact.phone == null && contact.email == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (contact.email != null) RoundAction(R.drawable.ic_mail, R.string.action_mail, onMail)
        if (contact.phone != null) {
            RoundAction(R.drawable.ic_message, R.string.action_message, onMessage)
            RoundAction(R.drawable.ic_call, R.string.action_call, onCall)
        }
    }
}

@Composable
private fun RoundAction(icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun ResultIcon(item: SearchItem, size: Dp) {
    when (val target = item.payload) {
        is AppTarget -> AppIconImage(target, size)
        is ShortcutTarget -> {
            val icons = LocalContext.current.launcherApp.icons
            val icon by rememberIcon(icons, target.key) { icons.shortcutIcon(target) }
            BitmapIcon(icon, size, RoundedCornerShape(size * 0.28f))
        }
        is ContactTarget -> ContactAvatar(target, size)
        is EventTarget -> DateTile(target.begin, target.allDay, Color(target.color), size)
        is WebTarget -> VeloIcon(size)
        is FileTarget -> FileTile(target, size)
        is ImageTarget -> {
            val icons = LocalContext.current.launcherApp.icons
            val thumb by rememberIcon(icons, "thumb:${target.uri}") { icons.thumbnail(target.uri) }
            BitmapIcon(thumb, size, RoundedCornerShape(8.dp), ContentScale.Crop)
        }
        is MessageTarget -> {
            // The icon of the app the message came from (personal profile), else a message symbol.
            val apps = LocalContext.current.launcherApp.apps
            val app = remember(target.packageName) { apps.appOf(0, target.packageName) }
            if (app != null) AppIconImage(app, size) else SymbolTile(R.drawable.ic_message, size)
        }
        is SettingTarget -> SymbolTile(
            if (target.special == SettingTarget.Special.Torch) R.drawable.ic_flashlight else R.drawable.ic_settings,
            size,
        )
        else -> Box(Modifier.size(size))
    }
}

/** A symbol on a round tile in the theme's colours, for settings and other launcher-made results. */
@Composable
fun SymbolTile(icon: Int, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun AppIconImage(app: AppTarget, size: Dp, modifier: Modifier = Modifier) {
    val icons = LocalContext.current.launcherApp.icons
    val icon by rememberIcon(icons, app.key) { icons.loadApp(app) }
    BitmapIcon(icon, size, null, modifier = modifier, filter = if (app.paused) PausedFilter else null)
}

/** Grey and faded, as paused work apps look on the system launcher. */
private val PausedFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun BitmapIcon(
    bitmap: ImageBitmap?,
    size: Dp,
    shape: RoundedCornerShape?,
    scale: ContentScale = ContentScale.Fit,
    modifier: Modifier = Modifier,
    filter: ColorFilter? = null,
) {
    val clipped = if (shape != null) modifier.size(size).clip(shape) else modifier.size(size)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = clipped, contentScale = scale, colorFilter = filter)
    } else {
        Box(clipped.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape ?: CircleShape))
    }
}

@Composable
private fun ContactAvatar(contact: ContactTarget, size: Dp) {
    val photoUri = contact.photoUri
    if (photoUri != null) {
        val icons = LocalContext.current.launcherApp.icons
        val photo by rememberIcon(icons, "contact:$photoUri") { icons.thumbnail(photoUri) }
        if (photo != null) {
            BitmapIcon(photo, size, RoundedCornerShape(50), ContentScale.Crop)
            return
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            contact.initials,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

/** A calendar sheet showing weekday and day of month, in the calendar's colour. */
@Composable
private fun DateTile(begin: Long, allDay: Boolean, color: Color, size: Dp) {
    val calendar = remember(begin, allDay) {
        Calendar.getInstance(if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()).apply { timeInMillis = begin }
    }
    val weekday = remember(calendar) { DateFormat.format("EEE", calendar).toString() }
    Column(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(weekday, color = if (color.alpha > 0f) color.copy(alpha = 1f) else MaterialTheme.colorScheme.primary,
            fontSize = (size.value * 0.22f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(calendar.get(Calendar.DAY_OF_MONTH).toString(), color = MaterialTheme.colorScheme.onSurface,
            fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Normal, maxLines = 1)
    }
}

@Composable
fun VeloIcon(size: Dp) {
    val context = LocalContext.current
    val icons = context.launcherApp.icons
    val icon by rememberIcon(icons, "velo") {
        // Missing when Velo is not installed.
        runCatching { icons.drawable(context.packageManager.getApplicationIcon(Velo.PACKAGE)) }.getOrNull()
    }
    if (icon != null) BitmapIcon(icon, size, null)
    else Icon(painterResource(R.drawable.ic_globe), null, Modifier.size(size).padding(size * 0.2f))
}

/** A document tile labelled with the file extension. */
@Composable
private fun FileTile(file: FileTarget, size: Dp) {
    if (file.obsidianUri != null) {
        val context = LocalContext.current
        val icons = context.launcherApp.icons
        val icon by rememberIcon(icons, "obsidian") {
            runCatching { icons.drawable(context.packageManager.getApplicationIcon("md.obsidian")) }.getOrNull()
        }
        if (icon != null) {
            BitmapIcon(icon, size, null)
            return
        }
    }
    val color = when (file.extension) {
        "pdf" -> Color(0xFFE5483B)
        "doc", "docx", "odt", "rtf" -> Color(0xFF2B6BE6)
        "xls", "xlsx", "ods", "csv" -> Color(0xFF1E9E5A)
        "ppt", "pptx", "odp" -> Color(0xFFE5822B)
        "md", "markdown", "org", "txt" -> Color(0xFF7A5AF0)
        else -> Color(0xFF7D8590)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.2f)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            file.extension.take(4).uppercase(),
            color = Color.White,
            fontSize = (size.value * 0.24f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
