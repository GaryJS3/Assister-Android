package net.thegary.assister

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.thegary.assister.voice.AndroidResponseAudioPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AndroidResponseAudioPlayerTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()

    @Test fun foreground_player_decodes_cached_wave_and_completes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(activity.activity.cacheDir, "playback-test.wav").apply { writeBytes(testWave()) }
        val states = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val player = AndroidResponseAudioPlayer(activity.activity)
        try {
            instrumentation.runOnMainSync {
                player.play(file) { state -> states.add(state); if (state != "started") done.countDown() }
            }
            assertTrue("Playback did not finish: $states", done.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("started", "completed"), states.toList())
        } finally {
            instrumentation.runOnMainSync { player.stop() }
            file.delete()
        }
    }
}
