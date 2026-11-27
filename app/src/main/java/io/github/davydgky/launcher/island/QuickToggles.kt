package io.github.davydgky.launcher.island

import io.github.davydgky.launcher.R

/**
 * The shortcuts in the opened island. Which ones and in what order is a setting
 * ([io.github.davydgky.launcher.data.LauncherPrefs.islandToggles]); [id] is what is saved.
 */
enum class QuickToggle(val id: String, val icon: Int, val label: Int, val color: Long, val switch: Boolean = false) {
    Camera("camera", R.drawable.ic_camera, R.string.toggle_camera, 0xFFFFFFFF),
    Torch("torch", R.drawable.ic_flashlight_filled, R.string.island_torch, 0xFFFFD60A, switch = true),
    DoNotDisturb("dnd", R.drawable.ic_dnd, R.string.island_dnd, 0xFF5E5CE6, switch = true),
    Awake("awake", R.drawable.ic_coffee, R.string.island_awake, 0xFFFF9F0A, switch = true),
    ScreenText("text", R.drawable.ic_text_scan, R.string.island_read_screen, 0xFF0A84FF),
    Screenshot("screenshot", R.drawable.ic_screenshot, R.string.toggle_screenshot, 0xFFFFFFFF),
    Reminder("reminder", R.drawable.ic_bell, R.string.toggle_reminder, 0xFFFFD60A),
    Lock("lock", R.drawable.ic_lock_filled, R.string.island_lock, 0xFFFFFFFF),
    Wifi("wifi", R.drawable.ic_wifi, R.string.toggle_wifi, 0xFF0A84FF),
    Bluetooth("bluetooth", R.drawable.ic_bluetooth, R.string.toggle_bluetooth, 0xFF0A84FF),
    Volume("volume", R.drawable.ic_volume, R.string.toggle_volume, 0xFFFFFFFF),
    Timer("timer", R.drawable.ic_timer_filled, R.string.toggle_timer, 0xFFFF9F0A),
    Alarm("alarm", R.drawable.ic_alarm_filled, R.string.toggle_alarm, 0xFFFF9F0A),
    QuickSettings("quick_settings", R.drawable.ic_tune, R.string.toggle_quick_settings, 0xFFFFFFFF),
    Recents("recents", R.drawable.ic_recents, R.string.toggle_recents, 0xFFFFFFFF),
    ;

    companion object {
        val DEFAULT = listOf(Camera, Torch, DoNotDisturb, Awake, ScreenText, Lock)

        /** At most this many fit two rows of the opened island. */
        const val MAX = 12

        fun fromIds(ids: List<String>): List<QuickToggle> = ids.mapNotNull { id -> entries.firstOrNull { it.id == id } }.distinct().take(MAX)
    }
}
