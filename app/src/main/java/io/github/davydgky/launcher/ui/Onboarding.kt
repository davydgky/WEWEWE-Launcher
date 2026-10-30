package io.github.davydgky.launcher.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.settings.SettingsActivity

/**
 * Shown once: what the launcher can do and which permissions make it better, with the way to the
 * settings where each one is granted. Nothing is asked for here; the user decides there.
 */
@Composable
fun OnboardingDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.onboarding_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Step(R.drawable.ic_search, R.string.onboarding_search)
                Step(R.drawable.ic_sparkle, R.string.onboarding_nano)
                Step(R.drawable.ic_message, R.string.onboarding_notifications)
                Step(R.drawable.ic_edit, R.string.onboarding_edit)
                Step(R.drawable.ic_lock, R.string.onboarding_privacy)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_done)) } },
        dismissButton = {
            TextButton(onClick = {
                onDone()
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }) { Text(stringResource(R.string.onboarding_settings)) }
        },
    )
}

@Composable
private fun Step(icon: Int, text: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}
