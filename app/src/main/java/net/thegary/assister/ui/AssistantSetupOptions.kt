package net.thegary.assister.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import net.thegary.assister.R

@Composable
internal fun AssistantSetupOptions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    Text(stringResource(R.string.assistant_settings_hint))
    TextButton(onClick = {
        val roles = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
        val intent = if (Build.VERSION.SDK_INT >= 29 && roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true &&
            !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        try { launcher.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { launcher.launch(Intent(Settings.ACTION_SETTINGS)) }
    }) { Text(stringResource(R.string.choose_phone_assistant)) }
}
