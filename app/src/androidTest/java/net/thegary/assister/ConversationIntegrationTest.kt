package net.thegary.assister

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import javax.net.ServerSocketFactory
import java.util.concurrent.CopyOnWriteArrayList
import net.thegary.assister.interaction.ConversationViewModel
import net.thegary.assister.network.AssisterClient
import net.thegary.assister.settings.ClientSettings
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationIntegrationTest {
    @Test fun uncertain_submission_retries_same_key_and_stream_recovers_without_duplicate_deltas() {
        verifyRecovery(resetAfterCompletion = false)
    }

    @Test fun socket_reset_after_completion_does_not_show_an_error_or_restart_the_request() {
        verifyRecovery(resetAfterCompletion = true)
    }

    @Test fun brief_disconnect_is_quiet_but_persistent_disconnect_shows_a_banner_then_clears() {
        verifyRecovery(resetAfterCompletion = false, persistentDisconnect = true)
    }

    private fun verifyRecovery(resetAfterCompletion: Boolean, persistentDisconnect: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val prefs = application.getSharedPreferences("assister", 0)
        val previous = prefs.all.mapValues { it.value as String }
        val submissions = CopyOnWriteArrayList<String>()
        val cursors = CopyOnWriteArrayList<String>()
        val store = ViewModelStore()
        val sockets = CopyOnWriteArrayList<Socket>()
        MockWebServer().use { server ->
            // Capture real server sockets so the regression can send a TCP reset, rather
            // than calling cancel() on MockWebServer's server-side WebSocket (no HTTP Call).
            server.serverSocketFactory = object : ServerSocketFactory() {
                override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                    override fun accept(): Socket = super.accept().also { sockets.add(it) }
                }
                override fun createServerSocket(port: Int): ServerSocket = createServerSocket().apply { bind(InetSocketAddress(port)) }
                override fun createServerSocket(port: Int, backlog: Int): ServerSocket = createServerSocket().apply { bind(InetSocketAddress(port), backlog) }
                override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket =
                    createServerSocket().apply { bind(InetSocketAddress(address, port), backlog) }
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val uri = java.net.URI(request.requestLine.split(' ')[1])
                    val path = uri.path + (uri.query?.let { "?$it" } ?: "")
                    return when {
                        path == "/api/client/protocol" -> MockResponse().setBody("""{"protocolVersion":1,"features":["audio.input","execution.details"]}""")
                        path == "/api/client/conversations" -> MockResponse().setBody("""{"id":"c"}""")
                        path == "/api/client/conversations/c" -> MockResponse().setBody("[]")
                        path == "/api/client/interactions/i/context" -> MockResponse().setBody("""[{"id":"ctx","name":"Office state","type":"tool_result","source":"ha_search","content":"Office light is on","modelRounds":[2],"provenance":{"tool":"ha_search"},"truncated":false}]""")
                        path == "/api/client/interactions/i/trace" -> MockResponse().setBody("""{"steps":[{"id":"tool","input":{"query":"office"},"output":{"state":"on"},"metadata":{"toolName":"ha_search"},"inputTruncated":false,"outputTruncated":true}]}""")
                        path.startsWith("/api/client/interactions/i/events") -> MockResponse().setBody(
                            "[${event(1, "interaction.started", "{}")},${roundOutput()},${event(3, "response.delta", """{"text":"Hello "}""")}," +
                                "${event(4, "response.delta", """{"text":"world"}""")},${event(5, "response.completed", """{"text":"Hello world"}""")},${event(6, "interaction.completed", "{}")}]")
                        path == "/api/client/conversations/c/interactions" -> {
                            submissions.add(request.body.readUtf8())
                            if (submissions.size == 1) MockResponse().setResponseCode(503)
                            else MockResponse().setResponseCode(202).setBody("""{"id":"i","conversationId":"c","input":"Hello","response":"","status":"created","lastSequence":0}""")
                        }
                        path.startsWith("/api/client/interactions/i/stream") -> {
                            cursors.add(path)
                            if (persistentDisconnect && cursors.size in 2..3) return MockResponse().setResponseCode(503)
                            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    val connection = sockets.last()
                                    webSocket.send("""{"type":"subscription.ready","protocolVersion":1}""")
                                    if (cursors.size == 1) {
                                        webSocket.send(event(1, "interaction.started", "{}"))
                                        webSocket.send(roundOutput())
                                        webSocket.send(event(3, "response.delta", """{"text":"Hello "}"""))
                                    } else {
                                        webSocket.send(roundOutput())
                                        webSocket.send(event(3, "response.delta", """{"text":"Hello "}"""))
                                        webSocket.send(event(4, "response.delta", """{"text":"world"}"""))
                                        webSocket.send(event(5, "response.completed", """{"text":"Hello world"}"""))
                                        webSocket.send(event(6, "interaction.completed", "{}"))
                                        if (resetAfterCompletion) {
                                            // Give the final frames time to leave the server, then abort
                                            // without a WebSocket close handshake, as on the phone.
                                            kotlin.concurrent.thread(isDaemon = true) {
                                                Thread.sleep(200)
                                                runCatching { connection.setSoLinger(true, 0); connection.close() }
                                            }
                                            return
                                        }
                                    }
                                    webSocket.close(1000, "test reconnect")
                                }
                            })
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            try {
                prefs.edit().clear().commit()
                lateinit var model: ConversationViewModel
                instrumentation.runOnMainSync {
                    model = ConversationViewModel(application) { endpoint, credential ->
                        AssisterClient(endpoint, credential, OkHttpClient.Builder()
                            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.port))).build())
                    }
                    store.put("model", model)
                }
                // Initialize encrypted credentials before connecting through the normal settings path.
                Thread.sleep(100)
                instrumentation.runOnMainSync { model.saveSettings("http://10.44.0.33:${server.port}", "instrumentation-token") }
                try { await { model.state.value.connected } }
                catch (e: AssertionError) { throw AssertionError("Connection: ${model.state.value.error}; requests: ${server.requestCount}", e) }
                instrumentation.runOnMainSync { model.draft("Hello"); model.send() }
                await { model.state.value.pending && !model.state.value.working && model.state.value.error != null }
                instrumentation.runOnMainSync { model.send() }
                await { model.state.value.connection == "Reconnecting" }
                assertNull("Brief reconnect must not show an error banner", model.state.value.error)
                if (persistentDisconnect) {
                    await { model.state.value.error == "Connection interrupted. Retrying automatically…" }
                    assertEquals("Reconnecting", model.state.value.connection)
                }
                await { model.state.value.interactions.lastOrNull()?.terminal == true }
                await { model.state.value.connection == "Connected" }
                Thread.sleep(250) // Allow any late transport teardown to be delivered.
                assertNull(model.state.value.error)
                assertTrue(model.state.value.connected)
                assertEquals(if (persistentDisconnect) 4 else 2, cursors.size)
                assertEquals("Hello world", model.state.value.interactions.single().response)
                assertEquals(6L, model.state.value.interactions.single().sequence)
                assertTrue(model.state.value.supportsExecutionDetails)
                assertEquals("Check office lights", model.state.value.interactions.single().stepDetails.getValue("round1").modelText)
                assertEquals(2, submissions.size)
                assertEquals(JSONObject(submissions[0]).getString("idempotencyKey"), JSONObject(submissions[1]).getString("idempotencyKey"))
                assertTrue(cursors.last().endsWith("afterSequence=3"))
                assertFalse(model.state.value.pending)
                instrumentation.runOnMainSync { model.inspect("i") }
                await { model.state.value.interactions.single().inspection.trace.isNotEmpty() }
                val inspected = model.state.value.interactions.single()
                assertEquals("Hello world", inspected.response)
                assertEquals(6L, inspected.sequence)
                assertEquals("Check office lights", inspected.stepDetails.getValue("round1").modelText)
                assertTrue(inspected.terminal)
                assertTrue(inspected.inspection.context.contains("Office light is on"))
                assertTrue(inspected.inspection.context.contains("Model rounds: [2]"))
                val detail = inspected.inspection.trace.getValue("tool")
                assertTrue(detail.input.contains("office"))
                assertTrue(detail.output.contains("on"))
                assertTrue(detail.truncated)
            } finally {
                instrumentation.runOnMainSync { store.clear() }
                prefs.edit().clear().apply { previous.forEach { (key, value) -> putString(key, value) } }.commit()
            }
        }
    }

    private fun event(sequence: Int, type: String, data: String) =
        """{"interactionId":"i","sequence":$sequence,"type":"$type","data":$data}"""

    private fun roundOutput() = event(2, "step.updated",
        """{"stepId":"round1","kind":"LanguageModel","input":null,"output":{"assistantContent":"Check office lights","toolCalls":[{"name":"ha_search"}]},"inputTruncated":false,"outputTruncated":false}""")

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Condition did not complete before timeout", condition())
    }
}
