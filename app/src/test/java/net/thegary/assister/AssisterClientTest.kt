package net.thegary.assister

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.thegary.assister.network.ApiException
import net.thegary.assister.network.AssisterClient
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssisterClientTest {
    @Test fun response_audio_is_authenticated_bounded_and_saved_without_partial_files() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("assister-audio-test").toFile()
        try {
            MockWebServer().use { server ->
                val wave = ByteArray(44).also {
                    "RIFF".toByteArray().copyInto(it, 0)
                    "WAVE".toByteArray().copyInto(it, 8)
                }
                val destination = java.io.File(directory, "reply.wav")
                server.enqueue(MockResponse().setBody(okio.Buffer().write(wave)))
                assertEquals(destination, client(server).downloadAudio("interaction", destination))
                assertArrayEquals(wave, destination.readBytes())
                val request = server.takeRequest()
                assertEquals("/api/client/interactions/interaction/audio", java.net.URI(request.requestLine.split(' ')[1]).path)
                assertEquals("Bearer test-credential", request.getHeader("Authorization"))
                assertFalse(java.io.File(directory, "reply.wav.part").exists())
                server.enqueue(MockResponse().setBody("Invalid audio"))
                val corrupt = java.io.File(directory, "corrupt.wav")
                try { client(server).downloadAudio("interaction", corrupt); fail("Invalid audio accepted") }
                catch (_: java.io.IOException) { }
                assertFalse(corrupt.exists())
                assertFalse(java.io.File(directory, "corrupt.wav.part").exists())
                server.enqueue(MockResponse().setBody("").setHeader("Content-Length", 9 * 1024 * 1024))
                try { client(server).downloadAudio("interaction", corrupt); fail("Oversized audio accepted") }
                catch (_: java.io.IOException) { }
                assertFalse(corrupt.exists())
            }
        } finally { directory.deleteRecursively() }
    }

    private fun client(server: MockWebServer) = AssisterClient("http://10.44.0.33:${server.port}", "test-credential",
        OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", server.port))).build())

    @Test fun native_auth_and_protocol_negotiation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"protocolVersion":1}"""))
            assertEquals(1, client(server).protocol().getInt("protocolVersion"))
            val request = server.takeRequest()
            assertEquals("/api/client/protocol", java.net.URI(request.requestLine.split(' ')[1]).path)
            assertEquals("Bearer test-credential", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains("credential"))
        }
    }

    @Test fun missing_rich_api_is_an_actionable_error() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            try { client(server).protocol(); fail("Expected API failure") }
            catch (error: ApiException) { assertEquals(404, error.code); assertTrue(error.message!!.contains("deployment")) }
        }
    }

    @Test fun voice_upload_preserves_pcm_and_submission_key() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"audio-id"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val api = client(server)
            val bytes = byteArrayOf(0, 0, 127, 0, -128, -1)
            assertEquals("audio-id", api.uploadAudio(bytes))
            val upload = server.takeRequest()
            assertEquals("audio/pcm", upload.getHeader("Content-Type"))
            assertArrayEquals(bytes, upload.body.readByteArray())
            api.json("/conversations/conversation/interactions", JSONObject()
                .put("message", "Voice input").put("idempotencyKey", "stable-key").put("audioAttachmentId", "audio-id").put("speak", false))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("stable-key", body.getString("idempotencyKey"))
            assertEquals("audio-id", body.getString("audioAttachmentId"))
        }
    }

    @Test fun websocket_replays_from_cursor_with_native_auth() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"subscription.ready","protocolVersion":1}""")
                    webSocket.send("""{"interactionId":"i","sequence":8,"type":"response.delta","data":{"text":"Live"}}""")
                }
            }))
            val frames = withTimeout(5000) { client(server).events("i", 7).take(2).toList() }
            assertEquals(8L, JSONObject(frames[1]).getLong("sequence"))
            val request = server.takeRequest()
            val uri = java.net.URI(request.requestLine.split(' ')[1])
            assertEquals("/api/client/interactions/i/stream", uri.path)
            assertEquals("afterSequence=7", uri.query)
            assertEquals("Bearer test-credential", request.getHeader("Authorization"))
        }
    }
}
