package io.github.davydgky.launcher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.davydgky.launcher.launcherApp

/** Receives the result of an install session started by [AppUpdater]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        context.launcherApp.updater.onInstallStatus(status, confirmation)
    }
}
