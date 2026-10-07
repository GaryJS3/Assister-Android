package net.thegary.assister

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.thegary.assister.interaction.ConversationViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingCancellationTest {
    @Test fun typed_pending_send_can_be_discarded_offline_and_stays_cleared_after_restoration() = verify(false)
    @Test fun voice_pending_send_deletes_recording_and_preserves_independent_typed_draft() = verify(true)

    private fun verify(voice: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("assister", 0)
        val previous = prefs.all.mapValues { it.value as String }
        val audio = File(app.filesDir, "pending-voice.pcm")
        val previousAudio = if (audio.exists()) audio.readBytes() else null
        val store = ViewModelStore()
        try {
            prefs.edit().clear().putString("draft", "Draft text")
                .putString("pending", JSONObject().put("message", "Failed message").put("voice", voice)
                    .put("idempotencyKey", "old-key").toString()).commit()
            if (voice) audio.writeBytes(ByteArray(3200))
            lateinit var model: ConversationViewModel
            instrumentation.runOnMainSync { model = ConversationViewModel(app); store.put("first", model) }
            assertTrue(model.state.value.pending)
            assertFalse(model.state.value.connected)
            instrumentation.runOnMainSync { model.discardPending() }
            val deadline = System.currentTimeMillis() + 5000
            while (model.state.value.working && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse(model.state.value.pending)
            assertFalse(model.state.value.working)
            assertNull(model.state.value.error)
            assertEquals("", prefs.getString("pending", "missing"))
            assertFalse(audio.exists())
            assertEquals(if (voice) "Draft text" else "", model.state.value.draft)
            instrumentation.runOnMainSync {
                val restored = ConversationViewModel(app)
                store.put("restored", restored)
                assertFalse(restored.state.value.pending)
                restored.draft("New question")
                assertEquals("New question", restored.state.value.draft)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            if (previousAudio != null) audio.writeBytes(previousAudio) else audio.delete()
            prefs.edit().clear().apply { previous.forEach { (key, value) -> putString(key, value) } }.commit()
        }
    }
}
