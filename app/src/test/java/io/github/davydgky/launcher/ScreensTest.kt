package io.github.davydgky.launcher

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.davydgky.launcher.island.QuickNoteActivity
import io.github.davydgky.launcher.settings.SettingsActivity
import io.github.davydgky.launcher.ui.SearchActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** Every screen the island app opens starts, draws and closes without crashing. */
@RunWith(RobolectricTestRunner::class)
class ScreensTest {

    private val context get() = ApplicationProvider.getApplicationContext<LauncherApplication>()

    private fun <T : android.app.Activity> runScreen(intent: Intent) {
        ActivityScenario.launch<T>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { }
        }
    }

    @Test
    fun search() = runScreen<SearchActivity>(Intent(context, SearchActivity::class.java))

    @Test
    fun searchWithAWebSearchFromAnotherApp() =
        runScreen<SearchActivity>(Intent(Intent.ACTION_WEB_SEARCH).setClass(context, SearchActivity::class.java).putExtra("query", "wetter"))

    @Test
    fun settings() = runScreen<SettingsActivity>(Intent(context, SettingsActivity::class.java))

    @Test
    fun settingsFromTheUpdateNotification() =
        runScreen<SettingsActivity>(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_INSTALL_UPDATE, true))

    @Test
    fun reminders() = runScreen<io.github.davydgky.launcher.organizer.OrganizerActivity>(Intent(context, io.github.davydgky.launcher.organizer.OrganizerActivity::class.java))

    @Test
    fun quickNote() = runScreen<QuickNoteActivity>(Intent(context, QuickNoteActivity::class.java))
}
