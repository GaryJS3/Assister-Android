package net.thegary.assister.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.thegary.assister.BuildConfig
import net.thegary.assister.R
import net.thegary.assister.updates.UpdateViewModel

@Composable
fun UpdateOptions(model: UpdateViewModel, endpoint: String, onInstall: () -> Unit) {
    val state by model.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.current_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        Text(stringResource(R.string.update_server_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { model.check(endpoint) }, enabled = !state.busy) {
            Text(stringResource(R.string.check_updates))
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.progress == null) stringResource(R.string.checking_updates)
                else stringResource(R.string.downloading_update, state.progress!!))
        }
        if (state.endpoint == endpoint && !state.busy) {
            if (state.checked && state.release == null) Text(stringResource(R.string.up_to_date))
            state.release?.let { release ->
                Text(stringResource(R.string.update_available, release.version))
                Button(onClick = { if (state.ready) onInstall() else model.download() }) {
                    Text(stringResource(if (state.ready) R.string.install_update else R.string.download_update))
                }
                Text(stringResource(R.string.install_update_hint), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
