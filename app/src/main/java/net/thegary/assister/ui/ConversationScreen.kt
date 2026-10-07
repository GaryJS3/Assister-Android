package net.thegary.assister.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.thegary.assister.R
import net.thegary.assister.interaction.ConversationState
import net.thegary.assister.interaction.ConversationViewModel

@Composable
fun ConversationScreen(model: ConversationViewModel, onRecordVoice: () -> Unit,
    updates: net.thegary.assister.updates.UpdateViewModel, onInstall: () -> Unit) {
    val state by model.state.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    ConversationContent(state, model::draft, model::send, onRecordVoice, model::finishRecording,
        model::discardRecording, model::cancelInteraction, { model.connect() }, { model.connect(true) }, { showSettings = true },
        model::inspect, model::toggleAudio, model::retryAudio, model::discardPending)
    if (showSettings) SettingsDialog(state.endpoint, updates, onInstall, onDismiss = { showSettings = false }, onSave = { endpoint, token ->
        model.saveSettings(endpoint, token)
        showSettings = false
    })
}

@Composable
fun ConversationContent(
    state: ConversationState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRecord: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
    onConnect: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onInspect: (String) -> Unit = {},
    onAudio: (String) -> Unit = {},
    onRetryAudio: (String) -> Unit = {},
    onDiscardPending: () -> Unit = {}
) {
    val scroll = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { !scroll.canScrollForward }.collect { atBottom -> if (atBottom) autoFollow = true }
    }
    LaunchedEffect(scroll.interactionSource) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) autoFollow = false
            if (interaction is DragInteraction.Stop && !scroll.canScrollForward) autoFollow = true
        }
    }
    val last = state.interactions.lastOrNull()
    val canConfigure = !state.working && !state.recording && (state.active == null || !state.connected)
    LaunchedEffect(last?.response?.length, last?.sequence, state.interactions.size) {
        withFrameNanos { }
        if (autoFollow && scroll.layoutInfo.totalItemsCount > 0) scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
    }
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(state.connection, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onSettings, enabled = canConfigure) { Text(stringResource(R.string.settings)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.endpoint, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = onNew, enabled = state.connected && !state.busy && !state.pending) { Text(stringResource(R.string.new_chat)) }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.recording) {
                        Text(stringResource(R.string.recording_seconds, state.recordedSeconds), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.buffered_voice_hint), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onFinish) { Text(stringResource(R.string.finish_send)) }
                            OutlinedButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
                        }
                    } else {
                        OutlinedTextField(value = state.draft, onValueChange = onDraft,
                            label = { Text(stringResource(R.string.ask_assister)) }, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && !state.pending, maxLines = 4,
                            supportingText = { Text(stringResource(R.string.character_count, state.draft.length)) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.pending) {
                                OutlinedButton(onClick = onRecord, enabled = state.connected && state.supportsVoice && !state.busy) {
                                    Text(stringResource(R.string.record_voice))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (state.pending) {
                                TextButton(onClick = onDiscardPending, enabled = !state.working) {
                                    Text(stringResource(R.string.cancel_send))
                                }
                            }
                            if (state.active != null) {
                                Button(onClick = onCancel, enabled = !state.cancelling) {
                                    Text(stringResource(if (state.cancelling) R.string.cancelling else R.string.cancel))
                                }
                            } else {
                                Button(onClick = onSend, enabled = state.connected && !state.busy && (state.draft.isNotBlank() || state.pending)) {
                                    Text(stringResource(if (state.pending) R.string.retry_send else R.string.send))
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.connected && !state.working) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.connect_hint), style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                            TextButton(onClick = onSettings, enabled = canConfigure) { Text(stringResource(R.string.settings)) }
                        }
                    }
                }
            }
            state.error?.let { error -> item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(error, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
            if (state.interactions.isEmpty() && !state.working) item {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.empty_hint), style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(state.interactions, key = { it.id }) { interaction ->
                InteractionCard(interaction, onInspect, state.supportsExecutionDetails, onAudio, onRetryAudio)
            }
            if (state.working || state.pending) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.draft.isNotBlank()) Text(state.draft, style = MaterialTheme.typography.bodyLarge)
                    if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.working) R.string.sending else R.string.pending_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            // A final anchor lets long single-message cards follow their bottom, rather than their top.
            item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
        }
    }
}

@Composable
private fun SettingsDialog(endpoint: String, updates: net.thegary.assister.updates.UpdateViewModel,
    onInstall: () -> Unit, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var server by rememberSaveable { mutableStateOf(endpoint) }
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.server_settings)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.server_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.bearer_token)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text(stringResource(R.string.token_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.lan_hint), style = MaterialTheme.typography.bodySmall)
                UpdateOptions(updates, endpoint, onInstall)
                AssistantSetupOptions()
            }
        }, confirmButton = { TextButton(onClick = { onSave(server, token) }, enabled = server.isNotBlank()) { Text(stringResource(R.string.save_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
