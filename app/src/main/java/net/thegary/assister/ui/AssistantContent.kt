package net.thegary.assister.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.thegary.assister.R
import net.thegary.assister.interaction.ConversationState

@Composable
fun AssistantContent(state: ConversationState, onRecord: () -> Unit, onFinish: () -> Unit,
    onDiscard: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onConnect: () -> Unit,
    onExpand: () -> Unit, onClose: () -> Unit, onInspect: (String) -> Unit = {},
    onAudio: (String) -> Unit = {}, onRetryAudio: (String) -> Unit = {}, onDiscardPending: () -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)
            .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onExpand) { Text(stringResource(R.string.expand_assistant)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close_assistant)) }
            }
            Text(if (state.recording) stringResource(R.string.listening_seconds, state.recordedSeconds) else state.connection,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.pending && !state.working && !state.recording) {
                TextButton(onClick = onDiscardPending) { Text(stringResource(R.string.cancel_send)) }
            }
            if (state.recording) {
                Text(stringResource(R.string.auto_finish_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onFinish) { Text(stringResource(R.string.finish_send)) }
                    OutlinedButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
                }
            } else if (state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (state.active != null) {
                TextButton(onClick = onCancel, enabled = !state.cancelling) { Text(stringResource(R.string.cancel)) }
            } else if (state.pending) {
                Button(onClick = onRetry, enabled = state.connected) { Text(stringResource(R.string.retry_send)) }
            } else if (!state.connected) {
                Text(stringResource(R.string.assistant_setup_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onConnect) { Text(stringResource(R.string.connect)) }
            } else {
                Button(onClick = onRecord, enabled = state.supportsVoice) { Text(stringResource(R.string.record_voice)) }
            }
            state.interactions.lastOrNull()?.let {
                InteractionCard(it, onInspect, state.supportsExecutionDetails, onAudio, onRetryAudio)
            }
        }
    }
}
