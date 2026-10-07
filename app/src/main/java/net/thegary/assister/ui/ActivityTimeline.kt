package net.thegary.assister.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.thegary.assister.R
import net.thegary.assister.protocol.Interaction
import net.thegary.assister.protocol.Step
import java.util.Locale

@Composable
internal fun InteractionCard(item: Interaction, onInspect: (String) -> Unit, supportsExecutionDetails: Boolean = false,
    onAudio: (String) -> Unit = {}, onRetryAudio: (String) -> Unit = {}) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var contextOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var selected by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    var tick by remember { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    LaunchedEffect(item.terminal) {
        while (!item.terminal) { tick = System.nanoTime() / 1_000_000; delay(100) }
    }
    val now = (item.eventTime ?: 0) + if (item.terminal) 0 else (tick - item.receivedAt).coerceAtLeast(0)
    val inspecting = contextOpen || selected != null
    LaunchedEffect(inspecting, if (contextOpen || !supportsExecutionDetails) item.sequence else null) {
        if (inspecting) { delay(250); onInspect(item.id) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.input, style = MaterialTheme.typography.bodyLarge)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall)
                        if (item.audioPath != null) {
                            val playing = item.playback in setOf("preparing", "playing")
                            IconButton(onClick = { onAudio(item.id) }) {
                                Icon(painterResource(if (playing) R.drawable.ic_audio_stop else R.drawable.ic_speaker),
                                    contentDescription = stringResource(if (playing) R.string.stop_response_audio else R.string.play_response_audio))
                            }
                        }
                    }
                }
                if (item.response.isNotEmpty()) MarkdownText(item.response)
                if (item.steps.isNotEmpty()) {
                    if (item.terminal) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(pluralStringResource(if (expanded) R.plurals.hide_execution else R.plurals.show_execution, item.steps.size, item.steps.size))
                    }
                    if (!item.terminal || expanded) {
                        val origin = item.steps.mapNotNull { it.startedAt }.minOrNull() ?: now
                        val span = (now - origin).coerceAtLeast(1).toDouble()
                        item.steps.forEach { step ->
                            val streamed = item.stepDetails[step.id]
                            val inspected = item.inspection.trace[step.id]
                            val detail = remember(streamed, inspected) {
                                streamed?.copy(metadata = inspected?.metadata.orEmpty()) ?: inspected
                            }
                            val segment = item.modelOutputs[step.id]
                            val modelText = segment?.text ?: detail?.modelText.orEmpty()
                            TimelineRow(step, now, origin, span, selected == step.id) {
                                selected = if (selected == step.id) null else step.id
                            }
                            if (step.kind == "LanguageModel" && modelText.isNotBlank() &&
                                (item.response.isBlank() || item.terminal || selected == step.id)) {
                                Text(stringResource(if (segment?.status == "running") R.string.model_round_provisional else R.string.model_round_output),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                MarkdownText(modelText, MaterialTheme.typography.bodySmall)
                                if (segment?.status in setOf("failed", "cancelled")) Text(segment!!.status,
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                if (segment?.truncated == true || detail?.truncated == true) Text(stringResource(R.string.inspection_truncated), style = MaterialTheme.typography.labelSmall)
                            }
                            if (selected == step.id) {
                                if (step.summary.isNotBlank()) Text(step.summary, style = MaterialTheme.typography.bodySmall)
                                if (detail != null) {
                                    DetailText(R.string.step_input, detail.input)
                                    DetailText(R.string.step_output, detail.output)
                                    DetailText(R.string.step_metadata, detail.metadata)
                                    if (detail.truncated) Text(stringResource(R.string.inspection_truncated), style = MaterialTheme.typography.labelSmall)
                                } else Text(stringResource(if (item.inspection.loading) R.string.loading_details else R.string.details_unavailable), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (item.steps.isEmpty() && item.terminal) TextButton(onClick = { expanded = true; onInspect(item.id) }) {
                    Text(stringResource(R.string.load_execution))
                }
                TextButton(onClick = { contextOpen = !contextOpen }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.context_used, item.contexts.size))
                }
                if (contextOpen) {
                    val text = item.inspection.context.ifBlank { item.contexts.values.joinToString("\n\n") }
                    if (text.isNotBlank()) SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    else Text(stringResource(if (item.inspection.loading) R.string.loading_details else R.string.no_context), style = MaterialTheme.typography.bodySmall)
                }
                if (inspecting) item.inspection.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                item.audioError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (item.audioAvailable && item.audioPath == null && item.audioError != null) {
                    TextButton(onClick = { onRetryAudio(item.id) }, enabled = !item.audioDownloading) { Text(stringResource(R.string.retry_audio)) }
                }
            }
        }
    }
}

@Composable
private fun DetailText(label: Int, value: String) {
    if (value.isNotBlank()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun TimelineRow(step: Step, now: Long, origin: Long, span: Double, expanded: Boolean, onClick: () -> Unit) {
    val elapsed = step.elapsedMs(now)
    val color = when {
        step.status in setOf("failed", "rejected", "unavailable", "interrupted-or-failed") -> MaterialTheme.colorScheme.error
        step.kind == "ToolCall" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(step.label, Modifier.weight(1f).padding(start = if (step.parentId.isNotBlank()) 8.dp else 0.dp),
                style = MaterialTheme.typography.labelMedium, maxLines = 2)
            Text(if (elapsed == null) "—" else String.format(Locale.US, "%.1fs", elapsed / 1000),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = color)
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.labelMedium)
        }
        Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp)) {
            drawRect(track)
            if (step.startedAt != null && elapsed != null) {
                val start = ((step.startedAt - origin) / span).coerceIn(0.0, 1.0).toFloat() * size.width
                val width = (elapsed / span).coerceIn(0.0, 1.0).toFloat() * size.width
                drawRect(color, Offset(start, 0f), Size(width.coerceAtLeast(2f).coerceAtMost(size.width - start), size.height))
            }
        }
        if (expanded || step.status == "running" || step.status in setOf("failed", "rejected", "unavailable")) {
            Text(step.status, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
