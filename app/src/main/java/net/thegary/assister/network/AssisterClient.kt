package net.thegary.assister.network

import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class ApiException(val code: Int, message: String) : IOException(message)

class AssisterClient(endpoint: String, private val token: String, private val http: OkHttpClient = sharedHttp) {
    private val base = endpoint.trim().trimEnd('/').toHttpUrl()

    init {
        require(base.scheme == "https" || base.host == "10.44.0.33") {
            "Use HTTPS, or the permitted LAN server http://10.44.0.33:8081"
        }
        require(base.encodedPath == "/" && base.query == null && base.username.isEmpty() && base.password.isEmpty()) {
            "Enter a server origin without a path, credentials, or query string"
        }
    }

    private fun request(path: String) = Request.Builder()
        .url(base.newBuilder().encodedPath("/api/client" + path.substringBefore('?'))
            .encodedQuery(path.substringAfter('?', "").takeIf { it.isNotEmpty() }).build())
        .header("Authorization", "Bearer $token")

    suspend fun json(path: String, body: JSONObject? = null): String = execute(
        request(path).apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }.build())

    suspend fun protocol(): JSONObject = JSONObject(json("/protocol")).also {
        require(it.getInt("protocolVersion") == 1) { "Unsupported server protocol version" }
    }

    suspend fun uploadAudio(bytes: ByteArray): String = JSONObject(execute(
        request("/attachments?name=Voice.pcm&source=microphone")
            .post(bytes.toRequestBody("audio/pcm".toMediaType())).build())).getString("id")

    /** Authenticated fixed route, bounded streaming, atomic publication; never trust an event URL. */
    suspend fun downloadAudio(id: String, destination: File): File = suspendCancellableCoroutine { continuation ->
        val temporary = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        val call = http.newCall(request("/interactions/$id/audio").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                temporary.delete()
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw apiError(it.code)
                        val body = it.body ?: throw IOException("Missing response audio")
                        if (body.contentLength() > MAX_WAVE_BYTES) throw IOException("Response audio exceeds limit")
                        var size = 0L
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                if (size > MAX_WAVE_BYTES || continuation.isCancelled) throw IOException("Audio download interrupted or too large")
                                output.write(buffer, 0, count)
                            }
                        } }
                        val header = ByteArray(12)
                        val read = temporary.inputStream().use { input -> input.read(header) }
                        if (size < 44 || read != 12 || String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                            String(header, 8, 4, Charsets.US_ASCII) != "WAVE") throw IOException("Invalid WAV audio")
                        if (continuation.isCancelled) throw IOException("Audio download cancelled")
                        if (!temporary.renameTo(destination)) throw IOException("Cannot save response audio")
                        if (!continuation.isCancelled) continuation.resume(destination)
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    } finally { temporary.delete() }
                }
            }
        })
    }

    fun events(id: String, after: Long) = callbackFlow {
        val socket = http.newWebSocket(request("/interactions/$id/stream?afterSequence=$after").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!trySend(text).isSuccess) {
                    close(IOException("Event consumer fell behind; reconnecting for replay"))
                    webSocket.cancel()
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(response?.let { apiError(it.code) } ?: t)
            }
        })
        awaitClose { socket.cancel() }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!continuation.isCancelled) {
                            if (!it.isSuccessful) continuation.resumeWithException(apiError(it.code))
                            else continuation.resume(it.body?.string().orEmpty())
                        }
                    } catch (e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                }
            }
        })
    }

    companion object {
        private const val MAX_WAVE_BYTES = 8L * 1024 * 1024 + 44
        private val sharedHttp = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()

        private fun apiError(code: Int) = ApiException(code, when (code) {
            401, 403 -> "Authentication required: check the client bearer token"
            404 -> "Rich-client API not found. This server may need the current Assister deployment."
            409 -> "Submission conflicts with an existing idempotency key"
            413 -> "Input exceeds the server's upload limit"
            else -> "Server returned HTTP $code"
        })
    }
}
