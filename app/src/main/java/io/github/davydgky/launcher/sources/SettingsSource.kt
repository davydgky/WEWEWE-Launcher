package io.github.davydgky.launcher.sources

import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.search.SettingTarget

/**
 * Pages of the system settings, found by their name and common words in German and English, as in
 * Spotlight: "wlan", "bluetooth", "akku", "helligkeit". Also the flashlight as a switch and the
 * launcher's own settings. Needs nothing; the list is part of the app.
 */
class SettingsSource(private val context: Context) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? = null

    override suspend fun load(): List<SearchItem> {
        val subtitle = context.getString(R.string.setting_result_subtitle)
        return Catalog.map { entry ->
            SearchItem(
                key = "setting:${entry.id}",
                kind = SourceKind.Setting,
                title = context.getString(entry.title),
                subtitle = if (entry.special == SettingTarget.Special.Torch) context.getString(R.string.setting_torch_subtitle) else subtitle,
                keywords = entry.keywords,
                payload = SettingTarget(entry.action, entry.special),
            )
        }
    }

    private class Entry(
        val id: String,
        val title: Int,
        val action: String?,
        val keywords: List<String>,
        val special: SettingTarget.Special? = null,
    )

    companion object {
        const val ID = "settings"

        private fun entry(id: String, title: Int, action: String?, vararg keywords: String, special: SettingTarget.Special? = null) =
            Entry(id, title, action, keywords.toList(), special)

        private val Catalog = listOf(
            entry("torch", R.string.setting_torch, null, "taschenlampe", "flashlight", "torch", "licht", "lampe", "light",
                special = SettingTarget.Special.Torch),
            entry("wifi", R.string.setting_wifi, Settings.ACTION_WIFI_SETTINGS, "wlan", "wifi", "wi-fi", "internet", "netzwerk", "network"),
            entry("bluetooth", R.string.setting_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth", "kopfhörer", "headphones", "koppeln", "pair"),
            entry("network", R.string.setting_network, Settings.ACTION_WIRELESS_SETTINGS,
                "mobilfunk", "mobile daten", "mobile data", "sim", "hotspot", "tethering", "roaming", "esim"),
            entry("airplane", R.string.setting_airplane, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "flugmodus", "flugzeugmodus", "airplane", "flight"),
            entry("data_usage", R.string.setting_data_usage, Settings.ACTION_DATA_USAGE_SETTINGS, "datenverbrauch", "datennutzung", "data usage", "datenvolumen"),
            entry("vpn", R.string.setting_vpn, Settings.ACTION_VPN_SETTINGS, "vpn"),
            entry("nfc", R.string.setting_nfc, Settings.ACTION_NFC_SETTINGS, "nfc", "kontaktlos", "contactless", "bezahlen", "payment"),
            entry("cast", R.string.setting_cast, Settings.ACTION_CAST_SETTINGS, "streamen", "cast", "chromecast", "bildschirm übertragen"),
            entry("display", R.string.setting_display, Settings.ACTION_DISPLAY_SETTINGS,
                "display", "bildschirm", "helligkeit", "brightness", "schriftgröße", "font size", "drehen", "rotation", "timeout", "bildschirmzeit"),
            entry("dark", R.string.setting_dark, Settings.ACTION_DARK_THEME_SETTINGS, "dunkel", "dark", "dark mode", "nachtmodus", "theme", "design"),
            entry("night_light", R.string.setting_night_light, Settings.ACTION_NIGHT_DISPLAY_SETTINGS, "nachtlicht", "night light", "blaulicht", "blue light"),
            entry("wallpaper", R.string.setting_wallpaper, Intent.ACTION_SET_WALLPAPER, "hintergrund", "wallpaper", "hintergrundbild", "farben", "colors", "stil", "style"),
            entry("sound", R.string.setting_sound, Settings.ACTION_SOUND_SETTINGS,
                "ton", "sound", "lautstärke", "volume", "klingelton", "ringtone", "vibration", "vibrieren", "lautlos", "silent"),
            entry("dnd", R.string.setting_dnd, ACTION_ZEN_MODE, "nicht stören", "do not disturb", "dnd", "ruhezeit", "modi", "modes"),
            entry("notifications", R.string.setting_notifications, ACTION_NOTIFICATIONS, "benachrichtigungen", "notifications", "mitteilungen"),
            entry("battery", R.string.setting_battery, Intent.ACTION_POWER_USAGE_SUMMARY, "akku", "battery", "batterie", "laden", "charging", "verbrauch"),
            entry("battery_saver", R.string.setting_battery_saver, Settings.ACTION_BATTERY_SAVER_SETTINGS, "energiesparmodus", "battery saver", "stromsparen", "sparmodus"),
            entry("storage", R.string.setting_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "speicher", "storage", "speicherplatz", "aufräumen", "free up"),
            entry("apps", R.string.setting_apps, Settings.ACTION_APPLICATION_SETTINGS, "apps", "anwendungen", "applications", "berechtigungen", "permissions"),
            entry("default_apps", R.string.setting_default_apps, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "standard-apps", "default apps", "standardbrowser", "default browser", "launcher"),
            entry("location", R.string.setting_location, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "standort", "location", "gps", "ortung"),
            entry("security", R.string.setting_security, Settings.ACTION_SECURITY_SETTINGS,
                "sicherheit", "security", "displaysperre", "screen lock", "fingerabdruck", "fingerprint", "pin", "passwort", "face unlock", "gesichtserkennung"),
            entry("privacy", R.string.setting_privacy, Settings.ACTION_PRIVACY_SETTINGS, "datenschutz", "privacy", "berechtigungsmanager", "permission manager"),
            entry("accounts", R.string.setting_accounts, Settings.ACTION_SYNC_SETTINGS, "konten", "accounts", "google konto", "sync", "synchronisierung", "passwörter", "passwords"),
            entry("accessibility", R.string.setting_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS, "bedienungshilfen", "accessibility", "barrierefreiheit", "talkback", "vergrößerung"),
            entry("language", R.string.setting_language, Settings.ACTION_LOCALE_SETTINGS, "sprache", "language", "sprachen", "languages", "region"),
            entry("keyboard", R.string.setting_keyboard, Settings.ACTION_INPUT_METHOD_SETTINGS, "tastatur", "keyboard", "gboard", "eingabe", "input"),
            entry("date", R.string.setting_date, Settings.ACTION_DATE_SETTINGS, "datum", "uhrzeit", "date", "time", "zeitzone", "timezone"),
            entry("about", R.string.setting_about, Settings.ACTION_DEVICE_INFO_SETTINGS, "über das telefon", "about phone", "telefoninfo", "imei", "modell", "android version", "build"),
            entry("update", R.string.setting_update, ACTION_SYSTEM_UPDATE, "systemupdate", "system update", "software update", "aktualisieren", "update"),
            entry("developer", R.string.setting_developer, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "entwickleroptionen", "developer options", "usb debugging", "adb"),
            entry("launcher", R.string.setting_launcher, null, "launcher", "wewewe", "suche", "search", "home-bildschirm", "home screen",
                special = SettingTarget.Special.Launcher),
        )

        /** Not public constants, but understood by the settings of every current Android version. */
        private const val ACTION_ZEN_MODE = "android.settings.ZEN_MODE_SETTINGS"
        private const val ACTION_NOTIFICATIONS = "android.settings.NOTIFICATION_SETTINGS"
        private const val ACTION_SYSTEM_UPDATE = "android.settings.SYSTEM_UPDATE_SETTINGS"
    }
}
