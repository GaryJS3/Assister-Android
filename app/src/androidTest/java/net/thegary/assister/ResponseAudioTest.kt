package net.thegary.assister

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import net.thegary.assister.interaction.ConversationViewModel
import net.thegary.assister.network.AssisterClient
import net.thegary.assister.settings.ClientSettings
import net.thegary.assister.voice.ResponseAudioPlayer
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun testWave(): ByteArray {
    val size = 3200
    return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(size).array()
}

class ResponseAudioTest {
    @Test fun typed_responses_cache_silently_and_manual_replay_reports_ordered_distinct_sessions() = verify(false)
    @Test fun voice_responses_autoplay_once_and_restored_history_stays_silent() = verify(true)
    @Test fun clearing_chat_cancels_an_inflight_download_without_recreating_cached_audio() = verify(false, true)

    private class FakePlayer : ResponseAudioPlayer {
        var count = 0
        var callback: ((String) -> Unit)? = null
        override fun play(file: File, onState: (String) -> Unit) { count++; callback = onState; onState("started") }
        override fun stop() { callback?.invoke("stopped"); callback = null }
        fun complete() { callback?.invoke("completed"); callback = null }
    }

    private fun verify(voice: Boolean, delayed: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val prefs = application.getSharedPreferences("assister", 0)
        val previous = prefs.all.mapValues { it.value as String }
        val store = ViewModelStore()
        val cache = File(application.noBackupFilesDir, "response-audio")
        val reports = CopyOnWriteArrayList<JSONObject>()
        val downloads = CopyOnWriteArrayList<String?>()
        val submissions = CopyOnWriteArrayList<JSONObject>()
        val player = FakePlayer()
        var conversations = 0
        val wave = testWave()
        fun event(seq: Int, type: String, data: String = "{}") =
            """{"interactionId":"audio-i","sequence":$seq,"type":"$type","data":$data}"""
        val events = listOf(event(1, "interaction.started"), event(2, "response.completed", """{"text":"Answer"}"""),
            event(3, "tts.started"), event(4, "tts.audio", """{"url":"https://untrusted.invalid/audio","size":${wave.size}}"""),
            event(5, "tts.completed"), event(6, "interaction.completed"))
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = java.net.URI(request.requestLine.split(' ')[1]).path
                    return when {
                        path.endsWith("/protocol") -> MockResponse().setBody("""{"protocolVersion":1,"features":["audio.input","audio.output"]}""")
                        path == "/api/client/conversations" -> { conversations++; MockResponse().setBody("""{"id":"c$conversations"}""") }
                        path == "/api/client/conversations/c1" -> MockResponse().setBody(if (submissions.isEmpty()) "[]" else """[{"id":"audio-i","input":"Question","response":"Answer","status":"completed","lastSequence":6}]""")
                        path == "/api/client/conversations/c2" -> MockResponse().setBody("[]")
                        path.endsWith("/interactions") -> {
                            submissions.add(JSONObject(request.body.readUtf8()))
                            MockResponse().setResponseCode(202).setBody("""{"id":"audio-i","input":"Question","status":"created","lastSequence":0}""")
                        }
                        path.endsWith("/stream") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(socket: WebSocket, response: Response) {
                                socket.send("""{"type":"subscription.ready","protocolVersion":1}""")
                                events.forEach { socket.send(it) }
                                socket.close(1000, "done")
                            }
                        })
                        path.endsWith("/events") -> MockResponse().setBody(events.joinToString(",", "[", "]"))
                        path.endsWith("/audio") -> {
                            downloads.add(request.getHeader("Authorization"))
                            MockResponse().setBody(okio.Buffer().write(wave)).apply { if (delayed) setBodyDelay(3, TimeUnit.SECONDS) }
                        }
                        path.endsWith("/playback") -> { reports.add(JSONObject(request.body.readUtf8())); MockResponse().setBody("{}") }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            try {
                cache.deleteRecursively()
                prefs.edit().clear().commit()
                val settings = ClientSettings(application)
                settings.endpoint = "http://10.44.0.33:${server.port}"
                if (voice) settings.pending = JSONObject().put("message", "Voice input").put("voice", true)
                    .put("speak", true).put("audioAttachmentId", "already-uploaded").put("idempotencyKey", "voice-key").toString()
                lateinit var model: ConversationViewModel
                instrumentation.runOnMainSync {
                    model = ConversationViewModel(application, { endpoint, credential ->
                        AssisterClient(endpoint, credential, OkHttpClient.Builder().proxy(
                            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port))).build())
                    }, player)
                    store.put("model", model)
                    model.setForeground(true)
                }
                Thread.sleep(100)
                instrumentation.runOnMainSync { model.saveSettings(settings.endpoint, "audio-test-token") }
                await { model.state.value.connected }
                instrumentation.runOnMainSync { if (!voice) model.draft("Question"); model.send() }
                await { model.state.value.interactions.lastOrNull()?.terminal == true && downloads.isNotEmpty() }
                assertTrue(submissions.single().getBoolean("speak"))
                assertEquals("Bearer audio-test-token", downloads.single())
                assertEquals("Answer", model.state.value.interactions.single().response)
                if (delayed) {
                    instrumentation.runOnMainSync { model.connect(true) }
                    await { model.state.value.connected && model.state.value.interactions.isEmpty() }
                    Thread.sleep(3500)
                    assertTrue(cache.listFiles().orEmpty().isEmpty())
                    assertEquals(0, player.count)
                    return
                }
                await { model.state.value.interactions.single().audioPath != null }
                assertArrayEquals(wave, File(model.state.value.interactions.single().audioPath!!).readBytes())
                if (voice) await { player.count == 1 } else assertEquals(0, player.count)
                if (!voice) instrumentation.runOnMainSync { model.toggleAudio("audio-i") }
                await { reports.size == 1 }
                instrumentation.runOnMainSync { player.complete() }
                await { reports.size == 2 }
                assertEquals(listOf("started", "completed"), reports.map { it.getString("state") })
                assertEquals(reports[0].getString("playbackId"), reports[1].getString("playbackId"))
                instrumentation.runOnMainSync { model.connect() }
                await { model.state.value.connected && model.state.value.interactions.single().audioPath != null && model.state.value.interactions.single().audioAvailable }
                Thread.sleep(200)
                assertEquals(1, player.count)
                assertEquals(1, downloads.size)
                instrumentation.runOnMainSync { model.toggleAudio("audio-i") }
                await { reports.size == 3 }
                assertNotEquals(reports[0].getString("playbackId"), reports[2].getString("playbackId"))
                instrumentation.runOnMainSync { model.connect(true) }
                await { model.state.value.connected && model.state.value.interactions.isEmpty() }
                await { reports.size == 4 }
                assertEquals("stopped", reports.last().getString("state"))
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            } finally {
                instrumentation.runOnMainSync { store.clear() }
                cache.deleteRecursively()
                prefs.edit().clear().apply { previous.forEach { (key, value) -> putString(key, value) } }.commit()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Audio condition timed out", condition())
    }
}
