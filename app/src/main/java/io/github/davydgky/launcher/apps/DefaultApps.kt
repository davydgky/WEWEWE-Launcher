package io.github.davydgky.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import io.github.davydgky.launcher.search.AppTarget

/**
 * The apps the user chose as default for common tasks. They fill the dock until the user arranges it,
 * and fill the suggestions while there is no usage history yet.
 */
object DefaultApps {

    private val roles: List<Intent> = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** Default apps in a fixed order (phone, messages, browser, camera, ...), without duplicates. */
    fun resolve(context: Context, apps: List<AppTarget>): List<AppTarget> {
        val pm = context.packageManager
        val own = apps.filter { !it.isWorkProfile }
        return roles.mapNotNull { intent ->
            val packageName = pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                ?.activityInfo?.packageName
                ?.takeIf { it != "android" } // the chooser: no default set
                ?: return@mapNotNull null
            own.firstOrNull { it.packageName == packageName }
        }.distinctBy { it.key }
    }
}
