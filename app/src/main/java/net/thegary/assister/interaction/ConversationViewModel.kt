package net.thegary.assister.interaction

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thegary.assister.network.ApiException
import net.thegary.assister.network.AssisterClient
import net.thegary.assister.protocol.Event
import net.thegary.assister.protocol.Interaction
import net.thegary.assister.protocol.Inspection
import net.thegary.assister.protocol.StepDetail
import net.thegary.assister.protocol.reduce
import net.thegary.assister.settings.ClientSettings
import net.thegary.assister.voice.VoiceRecorder
import net.thegary.assister.voice.ResponseAudioPlayer
import net.thegary.assister.voice.AndroidResponseAudioPlayer
import org.json.JSONArray
import org.json.JSONObject

data class ConversationState(
    val endpoint: String = ClientSettings.DEFAULT_ENDPOINT,
    val draft: String = "",
    val connection: String = "Not connected",
    val error: String? = null,
    val interactions: List<Interaction> = emptyList(),
    val working: Boolean = false,
    val connected: Boolean = false,
    val recording: Boolean = false,
    val recordedSeconds: Int = 0,
    val pending: Boolean = false,
    val cancelling: Boolean = false,
    val supportsVoice: Boolean = false,
    val supportsExecutionDetails: Boolean = false,
    val supportsModelOutput: Boolean = false
) {
    val active: Interaction? get() = interactions.firstOrNull { !it.terminal }
    val busy: Boolean get() = working || recording || active != null
}

class ConversationViewModel(
    application: Application,
    private val clientFactory: (String, String) -> AssisterClient,
    private val audioPlayer: ResponseAudioPlayer
) : AndroidViewModel(application) {
    constructor(application: Application, clientFactory: (String, String) -> AssisterClient) :
        this(application, clientFactory, AndroidResponseAudioPlayer(application))
    constructor(application: Application) : this(application, { endpoint, token -> AssisterClient(endpoint, token) })
    private val settings = ClientSettings(application)
    private val mutable = MutableStateFlow(ConversationState(endpoint = settings.endpoint, draft = settings.draft,
        pending = settings.pending.isNotEmpty()))
    val state = mutable.asStateFlow()
    private var client: AssisterClient? = null
    private var observer: Job? = null
    private var recordingJob: Job? = null
    private val inspections = mutableSetOf<String>()
    private val recorder = VoiceRecorder(application)
    private val audioFile = File(application.filesDir, "pending-voice.pcm")
    private val responseAudio = File(application.noBackupFilesDir, "response-audio")
    private val audioDownloads = mutableMapOf<String, Job>()
    private val autoPlay = mutableSetOf<String>()
    private var audioGeneration = 0
    private var foreground = false
    private var playingId: String? = null
    private var token = ""

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                token = settings.token()
                if (token.isNotEmpty()) withContext(Dispatchers.Main) { connect() }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { update { copy(error = "Stored credential unavailable; re-enter it in Settings") } }
            }
        }
    }

    private fun update(change: ConversationState.() -> ConversationState) { mutable.value = mutable.value.change() }
    fun draft(value: String) { update { copy(draft = value.take(1000)) }; settings.draft = value.take(1000) }
    fun permissionDenied() { update { copy(error = "Allow microphone access to record voice input") } }

    fun saveSettings(endpoint: String, credential: String) {
        if (state.value.working || state.value.recording || (state.value.active != null && state.value.connected)) return
        viewModelScope.launch {
            try {
                val cleanEndpoint = endpoint.trim().trimEnd('/')
                val cleanToken = credential.trim().ifEmpty { token }
                require(cleanToken.isNotEmpty()) { "Enter the server's client bearer token" }
                require(!state.value.pending || cleanEndpoint == settings.endpoint) {
                    "Keep the same server while a submission is unconfirmed; you can refresh its token"
                }
                clientFactory(cleanEndpoint, cleanToken)
                val changed = cleanEndpoint != settings.endpoint || cleanToken != token
                if (changed && !state.value.pending) clearResponseAudio()
                withContext(Dispatchers.IO) {
                    settings.endpoint = cleanEndpoint
                    settings.saveToken(cleanToken)
                    if (changed && !state.value.pending) settings.conversationId = ""
                }
                token = cleanToken
                observer?.cancel()
                update { copy(endpoint = cleanEndpoint, connected = false, interactions = if (changed && !pending) emptyList() else interactions) }
                connect()
            } catch (e: Exception) { failure(e) }
        }
    }

    fun connect(newConversation: Boolean = false) {
        if (state.value.working || state.value.recording || (newConversation && (state.value.active != null || state.value.pending))) return
        observer?.cancel()
        update { copy(working = true, connected = false, connection = "Connecting", error = null) }
        viewModelScope.launch {
            try {
                require(token.isNotEmpty()) { "Enter a bearer token in Settings to connect" }
                val api = clientFactory(settings.endpoint, token)
                val protocol = withContext(Dispatchers.IO) { api.protocol() }
                val features = protocol.optJSONArray("features") ?: JSONArray()
                val voice = (0 until features.length()).any { features.optString(it) == "audio.input" }
                val executionDetails = (0 until features.length()).any { features.optString(it) == "execution.details" }
                val modelOutput = (0 until features.length()).any { features.optString(it) == "model.output.stream" }
                var conversation = settings.conversationId
                if (newConversation || conversation.isEmpty()) {
                    conversation = withContext(Dispatchers.IO) { JSONObject(api.json("/conversations", JSONObject())).getString("id") }
                    if (newConversation) clearResponseAudio()
                    withContext(Dispatchers.IO) { settings.conversationId = conversation }
                }
                val items = withContext(Dispatchers.IO) {
                    val array = JSONArray(api.json("/conversations/$conversation"))
                    (0 until array.length()).map { Interaction.snapshot(array.getJSONObject(it)) }
                }
                client = api
                update { copy(interactions = items, connected = true, connection = "Connected", working = false,
                    error = null, supportsVoice = voice, supportsExecutionDetails = executionDetails,
                    supportsModelOutput = modelOutput) }
                items.filter { it.terminal }.forEach { fetchAudio(it.id) }
                (items.firstOrNull { !it.terminal } ?: items.lastOrNull())?.let { restoreAndObserve(it) }
            } catch (e: Exception) { failure(e); update { copy(working = false, connected = false, connection = "Connection failed") } }
        }
    }

    private fun restoreAndObserve(snapshot: Interaction) {
        observer = viewModelScope.launch {
            try {
                var restored = snapshot.copy(response = "", sequence = 0, steps = emptyList(), feed = emptyList(),
                    contexts = emptyMap(), stepDetails = emptyMap(), modelOutputs = emptyMap())
                while (true) {
                    val batch = withContext(Dispatchers.IO) {
                        val rows = JSONArray(client!!.json("/interactions/${snapshot.id}/events?afterSequence=${restored.sequence}"))
                        (0 until rows.length()).map { Event.parse(rows.getJSONObject(it)) }
                    }
                    restored = withContext(Dispatchers.Default) { batch.fold(restored) { item, event -> item.reduce(event) } }
                    if (batch.size < 256) break
                }
                replace(restored)
                if (!restored.terminal) observe(restored.id)
            } catch (e: Exception) { failure(e); if (!snapshot.terminal) observe(snapshot.id) }
        }
    }

    fun send() {
        if (state.value.busy || !state.value.connected) return
        if (!state.value.pending && state.value.draft.isBlank()) return
        autoPlay.clear()
        audioPlayer.stop()
        update { copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                if (!state.value.pending) withContext(Dispatchers.IO) {
                    settings.pending = JSONObject().put("message", state.value.draft.trim())
                        .put("speak", true)
                        .put("idempotencyKey", UUID.randomUUID().toString()).toString()
                }
                update { copy(pending = true) }
                submitPending()
            } catch (e: Exception) { failure(e) }
            finally { update { copy(working = false) } }
        }
    }

    fun discardPending() {
        if (!state.value.pending || state.value.working || state.value.recording) return
        update { copy(working = true) }
        viewModelScope.launch {
            try {
                val voice = withContext(Dispatchers.IO) {
                    val wasVoice = runCatching { JSONObject(settings.pending).optBoolean("voice") }.getOrDefault(false)
                    check(!audioFile.exists() || audioFile.delete()) { "Could not remove pending voice recording" }
                    settings.pending = ""
                    if (!wasVoice) settings.draft = ""
                    wasVoice
                }
                update { copy(pending = false, draft = if (voice) draft else "", error = null) }
            } catch (e: Exception) { failure(e) }
            finally { update { copy(working = false) } }
        }
    }

    private suspend fun submitPending() {
        val api = client ?: error("Connect before submitting")
        val (submitted, wasVoice) = withContext(Dispatchers.IO) {
            val body = JSONObject(settings.pending)
            val voice = body.optBoolean("voice")
            if (body.optBoolean("voice") && !body.has("audioAttachmentId")) {
                check(audioFile.exists()) { "Voice recording is unavailable; start a new recording" }
                body.put("audioAttachmentId", api.uploadAudio(audioFile.readBytes()))
                settings.pending = body.toString()
            }
            body.remove("voice")
            val snapshot = Interaction.snapshot(JSONObject(api.json("/conversations/${settings.conversationId}/interactions", body)))
            settings.pending = ""
            if (!voice) settings.draft = ""
            audioFile.delete()
            snapshot to voice
        }
        update { copy(draft = if (wasVoice) draft else "", pending = false, interactions = interactions.filter { it.id != submitted.id } +
            submitted.copy(response = "", sequence = 0), error = null) }
        if (wasVoice) autoPlay.add(submitted.id)
        observer?.cancel()
        observer = viewModelScope.launch { observe(submitted.id) }
    }

    private suspend fun observe(id: String) {
        var backoff = 1000L
        var reconnectNotice: Job? = null
        try {
            while (true) {
                val item = state.value.interactions.find { it.id == id } ?: return
                if (item.terminal) {
                    update { copy(connection = "Connected", cancelling = false, error = null) }
                    state.value.active?.let { restoreAndObserve(it) }
                    return
                }
                try {
                    update { copy(connection = "Connecting live feed") }
                    // Terminal execution is authoritative. Close observation immediately rather than
                    // waiting for the server's socket teardown, which can arrive as a TCP reset.
                    client!!.events(id, item.sequence).first { raw ->
                        val json = withContext(Dispatchers.Default) { JSONObject(raw) }
                        when (json.optString("type")) {
                            "subscription.ready" -> {
                                require(json.getInt("protocolVersion") == 1) { "Unsupported server protocol version" }
                                reconnectNotice?.cancel()
                                reconnectNotice = null
                                update { copy(connection = "Live", connected = true, error = null) }
                                backoff = 1000
                            }
                            "heartbeat" -> Unit
                            else -> {
                                if (json.has("sequence")) {
                                    val current = state.value.interactions.first { it.id == id }
                                    val next = withContext(Dispatchers.Default) { current.reduce(Event.parse(json)) }
                                    replace(next)
                                }
                            }
                        }
                        state.value.interactions.first { it.id == id }.terminal
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // A terminal event may already have been reduced from the buffered stream.
                    if (state.value.interactions.first { it.id == id }.terminal) continue
                    if ((e is ApiException && e.code in setOf(401, 403, 404)) || e.message == "Unsupported server protocol version") {
                        failure(e); update { copy(connected = false, connection = "Reconnect required") }; return
                    }
                    // Transient socket failures recover through replay. Show a banner only
                    // if the connection stays unavailable through the grace period.
                    if (reconnectNotice == null) reconnectNotice = viewModelScope.launch {
                        delay(5000)
                        if (state.value.interactions.find { it.id == id }?.terminal == false) {
                            update { copy(error = getApplication<Application>().getString(net.thegary.assister.R.string.connection_interrupted)) }
                        }
                    }
                }
                if (state.value.interactions.first { it.id == id }.terminal) continue
                update { copy(connection = "Reconnecting") }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15000)
            }
        } finally { reconnectNotice?.cancel() }
    }

    private fun replace(item: Interaction) {
        update { copy(interactions = interactions.map { current -> if (current.id == item.id) item.copy(
            audioPath = current.audioPath, audioDownloading = current.audioDownloading,
            playback = current.playback, audioAvailable = item.audioAvailable || current.audioAvailable,
            audioError = item.audioError ?: current.audioError) else current },
            cancelling = if (item.terminal) false else cancelling) }
        if (item.audioAvailable) fetchAudio(item.id)
        else if (item.terminal) autoPlay.remove(item.id)
    }

    private fun updateAudio(id: String, change: Interaction.() -> Interaction) {
        update { copy(interactions = interactions.map { if (it.id == id) it.change() else it }) }
    }

    private fun fetchAudio(id: String) {
        val api = client ?: return
        val item = state.value.interactions.find { it.id == id } ?: return
        if (item.audioPath != null || audioDownloads[id]?.isActive == true) return
        val generation = audioGeneration
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${settings.endpoint}|${settings.conversationId}|$id".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val destination = File(responseAudio, "$key.wav")
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            updateAudio(id) { copy(audioDownloading = true) }
            try {
                var fetched: File? = null
                for (attempt in 0..2) {
                    try {
                        fetched = withContext(Dispatchers.IO) {
                            if (destination.isFile && destination.length() >= 44) destination else api.downloadAudio(id, destination)
                        }
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is ApiException && e.code == 404 && !item.audioAvailable) return@launch
                        if (attempt == 2 || e is ApiException && e.code in setOf(401, 403, 404)) throw e
                        delay(1000L * (attempt + 1))
                    }
                }
                if (generation != audioGeneration) return@launch
                updateAudio(id) { copy(audioPath = fetched!!.absolutePath, audioAvailable = true, audioError = null) }
                if (autoPlay.remove(id) && foreground && !state.value.recording) playAudio(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == audioGeneration) updateAudio(id) { copy(audioError = getApplication<Application>().getString(net.thegary.assister.R.string.audio_download_failed)) }
            } finally {
                if (generation == audioGeneration) updateAudio(id) { copy(audioDownloading = false) }
                audioDownloads.remove(id)
            }
        }
        audioDownloads[id] = job
        job.start()
    }

    fun retryAudio(id: String) { fetchAudio(id) }

    fun toggleAudio(id: String) {
        if (playingId == id) audioPlayer.stop() else playAudio(id)
    }

    private fun playAudio(id: String) {
        val api = client ?: return
        val file = state.value.interactions.find { it.id == id }?.audioPath?.let(::File) ?: return
        if (!foreground || state.value.recording) return
        audioPlayer.stop()
        if (!file.isFile) {
            updateAudio(id) { copy(audioPath = null) }
            fetchAudio(id)
            return
        }
        val playbackId = UUID.randomUUID().toString()
        val reports = Channel<String>(Channel.UNLIMITED)
        viewModelScope.launch {
            for (report in reports) {
                // Serial reports preserve started -> terminal order even for short clips.
                for (attempt in 0..1) {
                    try {
                        withContext(Dispatchers.IO) { api.json("/interactions/$id/playback",
                            JSONObject().put("state", report).put("playbackId", playbackId)) }
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (attempt == 0) delay(500)
                    }
                }
            }
        }
        playingId = id
        val generation = audioGeneration
        updateAudio(id) { copy(playback = "preparing", audioError = null) }
        audioPlayer.play(file) { report ->
            reports.trySend(report)
            if (report != "started") { reports.close(); if (playingId == id) playingId = null }
            if (generation == audioGeneration) updateAudio(id) { copy(
                playback = if (report == "started") "playing" else "idle",
                audioError = if (report == "failed") getApplication<Application>().getString(net.thegary.assister.R.string.audio_play_failed) else audioError) }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { autoPlay.clear(); audioPlayer.stop() }
    }

    private suspend fun clearResponseAudio() {
        audioPlayer.stop()
        autoPlay.clear()
        audioGeneration++
        audioDownloads.values.toList().forEach { it.cancelAndJoin() }
        audioDownloads.clear()
        withContext(Dispatchers.IO) { responseAudio.deleteRecursively() }
    }

    fun inspect(id: String) {
        val api = client ?: return
        if (!inspections.add(id)) return
        viewModelScope.launch {
            fun setInspection(value: Inspection) {
                update { copy(interactions = interactions.map { if (it.id == id) it.copy(inspection = value) else it }) }
            }
            val previous = state.value.interactions.find { it.id == id }?.inspection ?: Inspection()
            setInspection(previous.copy(loading = true, error = null))
            try {
                val snapshot = state.value.interactions.find { it.id == id }
                if (snapshot != null && snapshot.steps.isEmpty() && snapshot.terminal) {
                    var replay = snapshot.copy(sequence = 0, response = "", feed = emptyList(), contexts = emptyMap(),
                        stepDetails = emptyMap(), modelOutputs = emptyMap())
                    do {
                        val events = withContext(Dispatchers.IO) {
                            val rows = JSONArray(api.json("/interactions/$id/events?afterSequence=${replay.sequence}"))
                            (0 until rows.length()).map { Event.parse(rows.getJSONObject(it)) }
                        }
                        replay = withContext(Dispatchers.Default) { events.fold(replay) { value, event -> value.reduce(event) } }
                    } while (events.size == 256)
                    replace(replay.copy(inspection = previous.copy(loading = true)))
                }
                val result = withContext(Dispatchers.IO) {
                    val contexts = JSONArray(api.json("/interactions/$id/context"))
                    val contextText = (0 until contexts.length()).joinToString("\n\n") { index ->
                        val row = contexts.getJSONObject(index)
                        "${row.optString("name")} · ${row.optString("type")}\n" +
                            "${getApplication<Application>().getString(net.thegary.assister.R.string.model_rounds)}: ${row.optJSONArray("modelRounds")}\n" +
                            "${getApplication<Application>().getString(net.thegary.assister.R.string.context_source)}: ${row.optString("source")}\n" +
                            "${row.optString("content")}\n${getApplication<Application>().getString(net.thegary.assister.R.string.context_provenance)}: ${row.opt("provenance")}" +
                            if (row.optBoolean("truncated")) "\n${getApplication<Application>().getString(net.thegary.assister.R.string.inspection_truncated)}" else ""
                    }
                    val trace = try { JSONObject(api.json("/interactions/$id/trace")) } catch (e: ApiException) {
                        if (e.code == 404) JSONObject() else throw e
                    }
                    val rows = trace.optJSONArray("steps") ?: JSONArray()
                    val details = (0 until rows.length()).associate { index ->
                        val row = rows.getJSONObject(index)
                        fun payload(key: String): String = if (row.isNull(key)) "" else when (val value = row.opt(key)) {
                            is JSONObject -> value.toString(2)
                            is JSONArray -> value.toString(2)
                            else -> value?.toString().orEmpty()
                        }
                        row.getString("id") to StepDetail(payload("input"), payload("output"), payload("metadata"),
                            row.optBoolean("inputTruncated") || row.optBoolean("outputTruncated"))
                    }
                    Inspection(context = contextText, trace = details)
                }
                setInspection(result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                setInspection(previous.copy(error = e.message))
            } finally { inspections.remove(id) }
        }
    }

    fun cancelInteraction() {
        val active = state.value.active ?: return
        if (state.value.cancelling) return
        update { copy(cancelling = true, error = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { client!!.json("/interactions/${active.id}/cancel", JSONObject()) } }
            catch (e: Exception) { failure(e); update { copy(cancelling = false) } }
        }
    }

    fun startRecording() {
        if (recordingJob?.isCompleted == false || state.value.busy || state.value.pending || !state.value.connected || !state.value.supportsVoice) return
        autoPlay.clear()
        audioPlayer.stop()
        update { copy(recording = true, recordedSeconds = 0, error = null) }
        recordingJob = viewModelScope.launch {
            try {
                val bytes = recorder.capture(automaticEnd = true) { second -> withContext(Dispatchers.Main) { update { copy(recordedSeconds = second) } } }
                check(bytes.size >= 3200) { "Recording was too short; try again" }
                update { copy(recording = false, working = true) }
                withContext(Dispatchers.IO) {
                    audioFile.writeBytes(bytes)
                    settings.pending = JSONObject().put("message", "Voice input").put("voice", true)
                        .put("speak", true).put("idempotencyKey", UUID.randomUUID().toString()).toString()
                }
                update { copy(pending = true) }
                submitPending()
            } catch (e: Exception) { if (e !is CancellationException) failure(e) }
            finally { update { copy(recording = false, working = false) } }
        }
    }

    fun finishRecording() { recorder.stop() }
    fun discardRecording() { recorder.stop(); recordingJob?.cancel(); update { copy(recording = false) } }

    private fun failure(e: Exception) {
        if (e is CancellationException) throw e
        update { copy(error = e.message ?: "Request failed (${e.javaClass.simpleName}); check server connectivity") }
    }

    override fun onCleared() { recorder.stop(); audioPlayer.stop(); super.onCleared() }
}
