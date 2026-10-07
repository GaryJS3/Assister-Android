package net.thegary.assister

import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import net.thegary.assister.settings.ClientSettings
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.thegary.assister.voice.VoiceRecorder

class PlatformFoundationTest {
    @Test fun credentials_roundtrip_through_keystore_without_plaintext_preferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("assister", 0)
        val previous = preferences.getString("credential", null)
        try {
            val settings = ClientSettings(context)
            settings.saveToken("instrumentation-only-secret")
            assertEquals("instrumentation-only-secret", settings.token())
            assertFalse(preferences.getString("credential", "")!!.contains("instrumentation-only-secret"))
        } finally { preferences.edit().putString("credential", previous).commit() }
    }

    @Test fun cleartext_is_limited_to_requested_lan_host() {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("10.44.0.33"))
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"))
    }

    @Test fun launcher_survives_activity_recreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun microphone_capture_produces_bounded_pcm_and_releases_recorder() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant net.thegary.assister android.permission.RECORD_AUDIO").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val recorder = VoiceRecorder(instrumentation.targetContext)
            val capture = async { recorder.capture {} }
            delay(500)
            recorder.stop()
            val bytes = capture.await()
            assertTrue(bytes.isNotEmpty())
            assertTrue(bytes.size <= VoiceRecorder.MAX_BYTES)
            assertEquals(0, bytes.size % 2)
        }
    }
}
