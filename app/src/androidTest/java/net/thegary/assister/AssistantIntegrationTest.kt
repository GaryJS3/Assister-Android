package net.thegary.assister

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.service.voice.VoiceInteractionService
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.FileInputStream
import net.thegary.assister.assistant.AssistantActivity
import org.junit.Assert.*
import org.junit.Test

class AssistantIntegrationTest {
    @Test fun selected_voice_service_invokes_compact_window_and_expands_shared_conversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as AssisterApplication
        val component = ComponentName(context, net.thegary.assister.assistant.AssisterVoiceInteractionService::class.java)
        val previous = shell("settings get secure voice_interaction_service").trim()
        val previousAssistant = shell("settings get secure assistant").trim()
        var previousDraft = ""
        try {
            instrumentation.runOnMainSync { previousDraft = app.conversation.state.value.draft; app.conversation.draft("Assistant handoff test") }
            shell("settings put secure voice_interaction_service ${component.flattenToString()}")
            shell("settings put secure assistant ${component.flattenToString()}")
            await { VoiceInteractionService.isActiveService(context, component) }
            // Actual framework session binding and startAssistantActivity, not a direct Activity launch.
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            // System assist key routes through the selected framework service.
            shell("input keyevent 219")
            await { activity<AssistantActivity>() != null }
            val assistant = activity<AssistantActivity>()!!
            instrumentation.runOnMainSync {
                assertSame(app.conversation, assistant.conversation)
                assertEquals("Assistant handoff test", assistant.conversation.state.value.draft)
            }
            shell("input keyevent 4")
            await { assistant.isFinishing }
            shell("input keyevent 219")
            await { activity<AssistantActivity>()?.let { it !== assistant } == true }
            val second = activity<AssistantActivity>()!!
            instrumentation.runOnMainSync { second.expand() }
            await { activity<MainActivity>() != null }
            instrumentation.runOnMainSync { assertEquals("Assistant handoff test", app.conversation.state.value.draft) }
            await { app.assistantWindow.get() == null || app.assistantWindow.get()?.isFinishing == true }
        } finally {
            val main = activity<MainActivity>()
            instrumentation.runOnMainSync {
                app.assistantWindow.get()?.finish()
                main?.finish()
                app.conversation.draft(previousDraft)
            }
            if (previous == "null" || previous.isEmpty()) shell("settings delete secure voice_interaction_service")
            else shell("settings put secure voice_interaction_service $previous")
            if (previousAssistant == "null" || previousAssistant.isEmpty()) shell("settings delete secure assistant")
            else shell("settings put secure assistant $previousAssistant")
        }
    }

    private inline fun <reified T : Activity> activity(): T? {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
        }
        return result
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Assistant condition timed out", condition())
    }
}
