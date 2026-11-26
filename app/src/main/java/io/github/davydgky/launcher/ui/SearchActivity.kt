package io.github.davydgky.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.SearchViewModel
import io.github.davydgky.launcher.ui.glass.LocalGlassColors
import io.github.davydgky.launcher.ui.glass.LocalGlassStyle
import io.github.davydgky.launcher.ui.glass.frostedBackdrop
import io.github.davydgky.launcher.ui.glass.glassColors
import io.github.davydgky.launcher.ui.glass.glassStyle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * The search on its own, over whatever app is open: from the Dynamic Island, the assistant gesture
 * (swipe from a bottom corner or hold the power button, once WEWEWE is the digital assistant), the
 * Quick Settings tile and the search widget. So the search works with any launcher, the Pixel
 * Launcher too. The app below is blurred by the system, the panel is the same as on the home screen.
 */
class SearchActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (BLUR_DP * resources.displayMetrics.density).toInt() }
        }
        val ime = ImeController(window.decorView, launcherApp.perf)
        val actions = ResultActions(this)
        val initialQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        launcherApp.search.onResume()
        launcherApp.suggestions.refresh(this)

        setContent {
            val prefs by launcherApp.settings.prefs.collectAsStateWithLifecycle(initialValue = LauncherPrefs())
            CompositionLocalProvider(LocalGlassStyle provides prefs.glassStyle()) {
                LauncherTheme {
                    val scope = rememberCoroutineScope()
                    val state = remember { SearchState(scope, ime) { launcherApp.suggestions.refresh(this) } }
                    val focusRequester = remember { FocusRequester() }
                    val colors = glassColors()
                    val behind = rememberLayerBackdrop()
                    val panelBackdrop = rememberLayerBackdrop()

                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        runCatching { focusRequester.requestFocus() }
                        if (initialQuery.isNotEmpty()) state.query.setTextAndPlaceCursorAtEnd(initialQuery)
                        state.open()
                    }
                    // Closed, or an app was opened from it: the search window goes away.
                    LaunchedEffect(state) {
                        snapshotFlow { state.isActive }.drop(1).first { !it }
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, android.R.anim.fade_out)
                    }
                    BackHandler(enabled = state.isActive) { state.close() }

                    CompositionLocalProvider(LocalGlassColors provides colors) {
                        Box(Modifier.fillMaxSize()) {
                            // Nothing of the app below can be recorded; the system blurs it, the scrim tints it.
                            Box(Modifier.fillMaxSize().layerBackdrop(behind))
                            Box(Modifier.fillMaxSize().frostedBackdrop(behind, colors, progress = { state.progress.value }, exportedBackdrop = panelBackdrop))
                            CompositionLocalProvider(LocalContentColor provides colors.content) {
                                SearchPanel(state, viewModel, actions, focusRequester, panelBackdrop)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Another app came to the front: the search does not wait in the background.
        if (!isChangingConfigurations) finish()
    }

    companion object {
        const val EXTRA_FROM_ISLAND = "from_island"
        const val EXTRA_QUERY = "query"
        private const val BLUR_DP = 24f

        fun intent(context: Context, query: String = ""): Intent =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
