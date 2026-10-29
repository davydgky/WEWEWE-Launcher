@file:OptIn(ExperimentalLayoutApi::class)

package io.github.davydgky.launcher.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.Answer
import io.github.davydgky.launcher.core.ScoredItem
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.perf.Metric
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ContactTarget
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.ResultsState
import io.github.davydgky.launcher.search.SearchViewModel
import io.github.davydgky.launcher.search.SettingTarget
import io.github.davydgky.launcher.search.SuggestionState
import io.github.davydgky.launcher.ui.glass.LocalGlassColors
import io.github.davydgky.launcher.ui.glass.glassWhen
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The search: results stacked upwards from the search field (or from the top, as set), the field
 * directly above the keyboard, a calculated answer when the input is a sum or conversion. It
 * stays composed (and its field focused) while closed, so opening it never waits for composition or
 * for the keyboard connection.
 */
@Composable
fun SearchPanel(
    state: SearchState,
    viewModel: SearchViewModel,
    actions: ResultActions,
    focusRequester: FocusRequester,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.launcherApp
    val results by viewModel.results.collectAsStateWithLifecycle()
    val suggestions by app.suggestions.current.collectAsStateWithLifecycle()
    val usageAccess by app.usage.hasAccess.collectAsStateWithLifecycle()
    val prefs by app.settings.prefs.collectAsStateWithLifecycle(initialValue = LauncherPrefs())
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    LaunchedEffect(state) {
        snapshotFlow { state.query.text.toString() }.collect { text ->
            viewModel.setQuery(text)
            // Typing on a hardware keyboard while the home screen shows opens the search.
            if (text.isNotEmpty() && !state.isActive) state.open()
        }
    }
    // Input-to-screen latency: wait for the frame that shows these results.
    LaunchedEffect(results) {
        if (results.query.isNotBlank() && results.inputNanos != 0L) {
            withFrameNanos { }
            app.perf.record(Metric.Results, (System.nanoTime() - results.inputNanos) / 1e6)
        }
    }

    // A search that led somewhere is remembered (on this device only) for the empty search.
    val rememberSearch: (String) -> Unit = { query -> if (query.isNotBlank()) scope.launch { app.settings.addRecentSearch(query) } }
    val open: (SearchItem, Rect?) -> Unit = { item, bounds ->
        rememberSearch(results.query)
        actions.open(item, bounds)
        // A switch (the flashlight) keeps the search open; everything else leaves it.
        if ((item.payload as? SettingTarget)?.special != SettingTarget.Special.Torch) state.reset()
    }
    val external: (String, (String) -> Unit) -> Unit = { query, action ->
        rememberSearch(query)
        action(query)
        state.reset()
    }
    // Scrolling through results makes room by hiding the keyboard, as on iOS.
    val hideKeyboardOnScroll = remember(keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > 4f) keyboard?.hide()
                return Offset.Zero
            }
        }
    }

    CompositionLocalProvider(LocalGlassActive provides state.isActive) {
        Column(
            modifier
                .fillMaxSize()
                // Closed: off screen, so it cannot catch touches meant for the home screen.
                .offset { IntOffset(0, if (state.isActive) 0 else OFFSCREEN_PX) }
                .graphicsLayer { alpha = state.progress.value }
                .pointerInput(state) { detectTapGestures { state.close() } }
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            val listState = rememberLazyListState()
            LaunchedEffect(results.query) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                // From the search field upwards: the best result sits right above the thumb.
                reverseLayout = prefs.resultsAtBottom,
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(hideKeyboardOnScroll)
                    .graphicsLayer {
                        val p = state.progress.value
                        translationY = (1f - p) * with(density) { -32.dp.toPx() }
                    },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, if (prefs.resultsAtBottom) Alignment.Bottom else Alignment.Top),
            ) {
                if (state.query.text.isBlank()) {
                    if (prefs.recentSearches.isNotEmpty()) {
                        item("recent") {
                            RecentSearches(
                                queries = prefs.recentSearches,
                                backdrop = backdrop,
                                onPick = { state.query.setTextAndPlaceCursorAtEnd(it) },
                                onClear = { scope.launch { app.settings.clearRecentSearches() } },
                            )
                        }
                    }
                    if (!usageAccess && !prefs.usageHintDismissed) {
                        item("usage-hint") {
                            UsageHint(
                                backdrop = backdrop,
                                onAllow = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                    )
                                },
                                onDismiss = { scope.launch { app.settings.dismissUsageHint() } },
                            )
                        }
                    }
                    suggestionItems(suggestions, actions, state, backdrop)
                } else {
                    resultItems(results, open, external, actions, backdrop)
                }
            }
            SearchField(state, focusRequester, backdrop, onSubmit = {
                // Enter: the top hit, else the calculated answer, else the first result, else the web.
                val top = results.results.top?.item
                val answer = results.answer
                val first = results.results.groups.firstOrNull()?.items?.firstOrNull()?.item
                when {
                    top != null -> open(top, null)
                    answer != null -> actions.copy(answer.copyText)
                    first != null -> open(first, null)
                    results.query.isNotBlank() -> external(results.query, actions::webSearch)
                }
            })
        }
    }
}

/** Glass is drawn only while the search is open. */
private val LocalGlassActive = staticCompositionLocalOf { false }

private const val OFFSCREEN_PX = 100_000

private fun LazyListScope.suggestionItems(
    suggestions: SuggestionState,
    actions: ResultActions,
    state: SearchState,
    backdrop: Backdrop,
) {
    if (suggestions.apps.isNotEmpty()) {
        item("suggested-apps") {
            Section(stringResource(R.string.section_suggestions), backdrop) {
                AppGrid(suggestions.apps) { app, bounds ->
                    actions.launchApp(app, bounds)
                    state.reset()
                }
            }
        }
    }
    if (suggestions.shortcuts.isNotEmpty()) {
        item("suggested-shortcuts") {
            Section(stringResource(R.string.section_shortcuts), backdrop) {
                suggestions.shortcuts.forEachIndexed { index, shortcut ->
                    if (index > 0) RowDivider()
                    val label = shortcut.info.shortLabel?.toString().orEmpty()
                    val item = SearchItem(shortcut.key, SourceKind.Shortcut, label, shortcut.appLabel, payload = shortcut)
                    ResultRow(item, "", onOpen = { _, bounds ->
                        actions.launchShortcut(shortcut, bounds)
                        state.reset()
                    })
                }
            }
        }
    }
}

private fun LazyListScope.resultItems(
    state: ResultsState,
    open: (SearchItem, Rect?) -> Unit,
    external: (String, (String) -> Unit) -> Unit,
    actions: ResultActions,
    backdrop: Backdrop,
) {
    val results = state.results
    val query = state.query
    state.answer?.let { answer ->
        item("answer") {
            Section(null, backdrop) { AnswerRow(answer) { actions.copy(answer.copyText) } }
        }
    }
    results.top?.let { top ->
        item("top") {
            Section(stringResource(R.string.section_top_hit), backdrop) {
                ResultRow(top.item, query, open, large = true, trailing = contactTrailing(top.item, actions))
            }
        }
    }
    for (group in results.groups) {
        item("group-${group.kind}") {
            var showAll by remember(query) { mutableStateOf(false) }
            val visible = if (showAll) group.items else group.items.take(group.kind.groupLimit)
            Section(
                title = stringResource(sectionTitle(group.kind)),
                backdrop = backdrop,
                action = if (group.items.size > group.kind.groupLimit) {
                    stringResource(if (showAll) R.string.show_less else R.string.show_more) to { showAll = !showAll }
                } else null,
            ) {
                if (group.kind == SourceKind.App) {
                    AppGrid(visible.mapNotNull { it.item.payload as? AppTarget }) { app, bounds ->
                        open(visible.first { it.item.payload === app }.item, bounds)
                    }
                } else {
                    visible.forEachIndexed { index, scored: ScoredItem ->
                        if (index > 0) RowDivider()
                        ResultRow(scored.item, query, open, trailing = contactTrailing(scored.item, actions))
                    }
                }
            }
        }
    }
    item("web") {
        Section(null, backdrop) {
            ExternalRow(stringResource(R.string.search_web, query), onClick = { external(query, actions::webSearch) }) { VeloIcon(40.dp) }
            RowDivider()
            ExternalRow(stringResource(R.string.search_play_store, query), onClick = { external(query, actions::playStoreSearch) }) {
                SymbolTile(R.drawable.ic_store, 40.dp)
            }
        }
    }
    if (results.isEmpty && state.answer == null) {
        item("empty") {
            Text(
                stringResource(R.string.no_results),
                Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
                color = LocalGlassColors.current.secondaryContent,
            )
        }
    }
}

private fun contactTrailing(item: SearchItem, actions: ResultActions): (@Composable () -> Unit)? {
    val contact = item.payload as? ContactTarget ?: return null
    if (contact.phone == null && contact.email == null) return null
    return {
        ContactActions(
            contact,
            onCall = { actions.call(contact) },
            onMessage = { actions.message(contact) },
            onMail = { actions.mail(contact) },
        )
    }
}

/** A row that hands the query to another app: the web, the Play Store. */
@Composable
private fun ExternalRow(text: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = LocalGlassColors.current.content,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The calculated answer, large like on a calculator; a tap copies it. */
@Composable
private fun AnswerRow(answer: Answer, onCopy: () -> Unit) {
    val colors = LocalGlassColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.answer_copy), onClick = onCopy)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolTile(R.drawable.ic_calculator, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(answer.expression, color = colors.secondaryContent, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(answer.result, color = colors.content, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The last searches as chips; a tap searches again. */
@Composable
private fun RecentSearches(queries: List<String>, backdrop: Backdrop, onPick: (String) -> Unit, onClear: () -> Unit) {
    val colors = LocalGlassColors.current
    Section(stringResource(R.string.section_recent), backdrop, action = stringResource(R.string.clear_recent) to onClear) {
        FlowRow(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            queries.forEach { query ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.fill)
                        .clickable { onPick(query) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_history), null, Modifier.size(15.dp), tint = colors.secondaryContent)
                    Spacer(Modifier.width(6.dp))
                    Text(query, color = colors.content, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun sectionTitle(kind: SourceKind): Int = when (kind) {
    SourceKind.App -> R.string.section_apps
    SourceKind.Setting -> R.string.section_settings
    SourceKind.Shortcut -> R.string.section_shortcuts
    SourceKind.Contact -> R.string.section_contacts
    SourceKind.Event -> R.string.section_events
    SourceKind.VeloBookmark -> R.string.section_bookmarks
    SourceKind.VeloHistory -> R.string.section_history
    SourceKind.Note -> R.string.section_notes
    SourceKind.Document -> R.string.section_documents
    SourceKind.Image -> R.string.section_images
}

/** A group of results on a Liquid Glass platter, like Spotlight. */
@Composable
private fun Section(
    title: String?,
    backdrop: Backdrop,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    Column {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.content,
                )
                if (action != null) {
                    Text(
                        action.first,
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = action.second).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.secondaryContent,
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .glassWhen(LocalGlassActive.current, backdrop, PlatterShape, colors),
        ) {
            content()
        }
    }
}

private val PlatterShape = RoundedRectangle(24.dp)

/** Asks once for usage access, which makes suggestions and the order of results follow real use. */
@Composable
private fun UsageHint(backdrop: Backdrop, onAllow: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalGlassColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .glassWhen(LocalGlassActive.current, backdrop, PlatterShape, colors)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.usage_hint_title), color = colors.content, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.usage_hint_text), color = colors.secondaryContent, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.usage_hint_action),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAllow).padding(vertical = 4.dp),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear), Modifier.size(18.dp), tint = colors.secondaryContent)
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 68.dp), color = LocalGlassColors.current.hairline)
}

/** Apps as icons with labels, four per row. */
@Composable
private fun AppGrid(apps: List<AppTarget>, onClick: (AppTarget, Rect?) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    val bounds = remember { ScreenBounds() }
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onClick(app, bounds.rect) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIconImage(app, 52.dp, Modifier.onGloballyPositioned(bounds::update))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            app.label,
                            color = LocalGlassColors.current.content,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SearchField(state: SearchState, focusRequester: FocusRequester, backdrop: Backdrop, onSubmit: () -> Unit) {
    val colors = LocalGlassColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .glassWhen(LocalGlassActive.current, backdrop, Capsule(), colors, refraction = 12.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = colors.secondaryContent)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.text.isEmpty()) {
                    Text(stringResource(R.string.search_hint), color = colors.secondaryContent, maxLines = 1)
                }
                BasicTextField(
                    state = state.query,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.content),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search,
                        autoCorrectEnabled = false,
                        // The field is focused on the home screen already; the keyboard comes with the gesture.
                        showKeyboardOnFocus = false,
                    ),
                    onKeyboardAction = { onSubmit() },
                )
            }
            if (state.query.text.isNotEmpty()) {
                IconButton(onClick = { state.query.clearText() }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear), Modifier.size(18.dp), tint = colors.secondaryContent)
                }
            }
        }
        TextButton(onClick = state::close) {
            Text(stringResource(R.string.cancel), color = colors.content)
        }
    }
}
