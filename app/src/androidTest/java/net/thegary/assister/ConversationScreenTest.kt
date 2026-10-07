package net.thegary.assister

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import net.thegary.assister.interaction.ConversationState
import net.thegary.assister.protocol.Interaction
import net.thegary.assister.protocol.Step
import net.thegary.assister.protocol.Inspection
import net.thegary.assister.protocol.StepDetail
import net.thegary.assister.protocol.ModelOutput
import net.thegary.assister.ui.ConversationContent
import net.thegary.assister.ui.theme.AssisterTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationScreenTest {
    @Test fun failed_send_exposes_cancel_even_while_disconnected() {
        var cancelled = false
        compose.setContent { AssisterTheme { ConversationContent(ConversationState(pending = true), {}, {}, {}, {}, {}, {}, {}, {}, {},
            onDiscardPending = { cancelled = true }) } }
        compose.onNodeWithText("Cancel send").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun cached_response_exposes_speaker_and_playing_response_exposes_stop() {
        val item = Interaction("i", "Question", response = "Answer", status = "completed", audioPath = "/private/reply.wav")
        val state = mutableStateOf(ConversationState(connected = true, interactions = listOf(item)))
        var clicked = ""
        compose.setContent { AssisterTheme { ConversationContent(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {},
            onAudio = { clicked = it }) } }
        compose.onNodeWithContentDescription("Play response audio").assertIsDisplayed().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("i", clicked); state.value = state.value.copy(interactions = listOf(item.copy(playback = "playing"))) }
        compose.onNodeWithContentDescription("Stop response audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play response audio").assertDoesNotExist()
    }

    @Test fun model_output_streams_before_round_finishes_and_wins_over_trace_snapshot() {
        val item = Interaction("i", "Office lights?", status = "running",
            steps = listOf(Step("r1", "", "LLM Round 1", "running", "LanguageModel")),
            stepDetails = mapOf("r1" to StepDetail("", """{"assistantContent":"Snapshot fallback"}""", "", false)),
            modelOutputs = mapOf("r1" to ModelOutput(1, "I will check ")))
        val state = mutableStateOf(ConversationState(connected = true, supportsExecutionDetails = true,
            supportsModelOutput = true, interactions = listOf(item)))
        compose.setContent { AssisterTheme { ConversationContent(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("MODEL ROUND OUTPUT · PROVISIONAL").assertIsDisplayed()
        compose.onNodeWithText("I will check").assertIsDisplayed()
        compose.onNodeWithText("Snapshot fallback").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(interactions = listOf(item.copy(
            modelOutputs = mapOf("r1" to ModelOutput(1, "I will check the office.", "completed"))))) }
        compose.onNodeWithText("I will check the office.").assertIsDisplayed()
        compose.onNodeWithText("MODEL ROUND OUTPUT").assertIsDisplayed()
        compose.onNodeWithText("MODEL ROUND OUTPUT · PROVISIONAL").assertDoesNotExist()
    }

    @Test fun model_round_text_is_visible_during_tools_then_collapses_and_can_be_reopened() {
        val roundText = "I'll check the office lights."
        val item = Interaction("i", "Office lights?", status = "running",
            steps = listOf(Step("round1", "", "LLM Round 1", "tool-call", "LanguageModel")),
            stepDetails = mapOf("round1" to StepDetail("", """{"assistantContent":"$roundText","toolCalls":[{"name":"ha_search"}]}""", "", false)))
        val state = mutableStateOf(ConversationState(connected = true, supportsExecutionDetails = true, interactions = listOf(item)))
        compose.setContent { AssisterTheme { ConversationContent(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("MODEL ROUND OUTPUT").assertIsDisplayed()
        compose.onNodeWithText(roundText).assertIsDisplayed()
        compose.onNodeWithText("Thinking").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(interactions = listOf(item.copy(response = "The lights are on.", status = "completed"))) }
        compose.onNodeWithText(roundText).assertDoesNotExist()
        compose.onNodeWithText("The lights are on.").assertIsDisplayed()
        compose.onNodeWithText("▸ 1 step · timing & details").performClick()
        compose.onNodeWithText(roundText).assertIsDisplayed()
    }

    @Test fun completion_collapses_timeline_and_expansion_reveals_tool_payloads() {
        val item = Interaction("i", "Office lights?", response = "The lights are on.", status = "completed",
            steps = listOf(Step("tool", "", "ha_search", "succeeded", "ToolCall", startedAt = 1000, finishedAt = 1700, durationMs = 700.0)),
            eventTime = 1800, inspection = Inspection(trace = mapOf("tool" to StepDetail("{\"query\":\"office\"}", "{\"state\":\"on\"}", "", false))))
        compose.setContent { AssisterTheme { ConversationContent(ConversationState(connected = true, interactions = listOf(item)), {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("The lights are on.").assertIsDisplayed()
        compose.onNodeWithText("ha_search").assertDoesNotExist()
        compose.onNodeWithText("▸ 1 step · timing & details").performClick()
        compose.onNodeWithText("0.7s").assertIsDisplayed()
        compose.onNodeWithText("ha_search").performClick()
        compose.onNodeWithText("INPUT").assertIsDisplayed()
        compose.onNodeWithText("{\"query\":\"office\"}").assertIsDisplayed()
        compose.onNodeWithText("OUTPUT").assertIsDisplayed()
        compose.onNodeWithText("Response complete").assertDoesNotExist()
        compose.onNodeWithText("Response ready").assertDoesNotExist()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "timeline-details.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun disconnected_screen_disables_input_submission_and_exposes_setup() {
        compose.setContent {
            AssisterTheme { ConversationContent(ConversationState(), {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Record voice").assertIsNotEnabled()
        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("http://10.44.0.33:8081").assertIsDisplayed()
    }

    @Test fun progress_and_response_update_in_place_and_cancel_is_available() {
        val state = mutableStateOf(ConversationState(connected = true, connection = "Live", supportsVoice = true,
            interactions = listOf(Interaction("i", "Hello", response = "First", status = "responding",
                steps = listOf(Step("step", "", "Checking server", "running"))))))
        var cancelled = false
        compose.setContent {
            AssisterTheme { ConversationContent(state.value, {}, {}, {}, {}, {}, { cancelled = true }, {}, {}, {}) }
        }
        compose.onNodeWithText("First").assertIsDisplayed()
        compose.onNodeWithText("Checking server").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(interactions = listOf(state.value.interactions[0].copy(response = "First answer"))) }
        compose.onNodeWithText("First answer").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun recording_has_finish_and_discard_actions() {
        compose.setContent {
            AssisterTheme { ConversationContent(ConversationState(connected = true, recording = true, recordedSeconds = 5),
                {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Recording · 5 / 30 seconds").assertIsDisplayed()
        compose.onNodeWithText("Finish & send").assertIsDisplayed()
        compose.onNodeWithText("Discard").assertIsDisplayed()
    }
}
