package net.thegary.assister

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import net.thegary.assister.interaction.ConversationState
import net.thegary.assister.protocol.Interaction
import net.thegary.assister.ui.AssistantContent
import net.thegary.assister.ui.theme.AssisterTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssistantContentTest {
    @Test fun failed_assistant_send_can_be_cancelled_while_offline() {
        var cancelled = false
        compose.setContent { AssisterTheme {
            AssistantContent(ConversationState(pending = true), {}, {}, {}, {}, {}, {}, {}, {},
                onDiscardPending = { cancelled = true })
        } }
        compose.onNodeWithText("Cancel send").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun compact_assistant_shows_listening_and_preserves_manual_controls_and_expansion() {
        var expanded = false
        var finished = false
        compose.setContent { AssisterTheme {
            AssistantContent(ConversationState(recording = true, recordedSeconds = 2), {}, { finished = true }, {}, {}, {}, {},
                { expanded = true }, {})
        } }
        compose.onNodeWithText("Listening · 2s").assertIsDisplayed()
        compose.onNodeWithText("Finish & send").performClick()
        compose.onNodeWithText("Expand").performClick()
        compose.runOnIdle { assertTrue(expanded); assertTrue(finished) }
    }
}
