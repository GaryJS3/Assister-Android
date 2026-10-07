package net.thegary.assister

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.thegary.assister.interaction.ConversationViewModel
import net.thegary.assister.settings.ClientSettings
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in: stages a token in private app files, never in the test APK or command arguments. */
@RequiresLiveServer
class LiveServerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun typed_request_streams_from_real_server_and_survives_recreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveServer") == "true")
        val credentialFile = File(compose.activity.filesDir, "live-test-token.txt")
        val token = credentialFile.readText().trim()
        credentialFile.delete()
        lateinit var model: ConversationViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[ConversationViewModel::class.java]
            model.saveSettings(ClientSettings.DEFAULT_ENDPOINT, token)
        }
        compose.waitUntil(30000) { model.state.value.connected && !model.state.value.working }
        compose.runOnUiThread {
            model.draft("Reply with exactly: Android MVP connection verified. Do not call tools or control devices.")
            model.send()
        }
        compose.waitUntil(45000) { model.state.value.interactions.lastOrNull()?.terminal == true }
        assertEquals("completed", model.state.value.interactions.last().status)
        assertTrue(model.state.value.interactions.last().steps.isNotEmpty())
        val id = model.state.value.interactions.last().id
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread {
            assertSame(model, ViewModelProvider(compose.activity)[ConversationViewModel::class.java])
            assertEquals(id, model.state.value.interactions.last().id)
        }
        compose.onNodeWithText("Android MVP connection verified.").assertIsDisplayed()
    }
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresLiveServer
