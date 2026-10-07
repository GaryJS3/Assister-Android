package net.thegary.assister.protocol

import org.json.JSONObject
import java.time.Instant

private fun JSONObject.text(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else optString(name, fallback)

data class Step(val id: String, val parentId: String, val label: String, val status: String,
    val kind: String = "", val summary: String = "", val startedAt: Long? = null,
    val finishedAt: Long? = null, val durationMs: Double? = null) {
    fun elapsedMs(now: Long): Double? = durationMs ?: startedAt?.let {
        ((finishedAt ?: now) - it).coerceAtLeast(0).toDouble()
    }
}

data class StepDetail(val input: String, val output: String, val metadata: String, val truncated: Boolean) {
    val modelText: String = runCatching { JSONObject(output).text("assistantContent") }.getOrDefault("")
}

data class ModelOutput(val modelRound: Int, val text: String = "", val status: String = "running",
    val truncated: Boolean = false)

private fun JSONObject.payload(name: String): String = if (isNull(name)) "" else when (val value = opt(name)) {
    is JSONObject -> value.toString(2)
    is org.json.JSONArray -> value.toString(2)
    else -> value?.toString().orEmpty()
}
data class Inspection(val loading: Boolean = false, val context: String = "", val trace: Map<String, StepDetail> = emptyMap(),
    val error: String? = null)

data class Interaction(
    val id: String,
    val input: String,
    val response: String = "",
    val status: String = "created",
    val sequence: Long = 0,
    val steps: List<Step> = emptyList(),
    val feed: List<String> = emptyList(),
    val error: String? = null,
    val contexts: Map<String, String> = emptyMap(),
    val inspection: Inspection = Inspection(),
    val stepDetails: Map<String, StepDetail> = emptyMap(),
    val modelOutputs: Map<String, ModelOutput> = emptyMap(),
    val audioAvailable: Boolean = false,
    val audioGenerating: Boolean = false,
    val audioPath: String? = null,
    val audioDownloading: Boolean = false,
    val audioError: String? = null,
    val playback: String = "idle",
    val eventTime: Long? = null,
    val receivedAt: Long = 0
) {
    val terminal: Boolean get() = status in setOf("completed", "failed", "cancelled")

    companion object {
        fun snapshot(json: JSONObject) = Interaction(
            id = json.getString("id"), input = json.text("input"),
            response = json.text("response"), status = json.getString("status"),
            sequence = json.optLong("lastSequence")
        )
    }
}

data class Event(val interactionId: String, val sequence: Long, val type: String, val data: JSONObject,
    val timestamp: Long? = null) {
    companion object {
        fun parse(json: JSONObject) = Event(
            json.getString("interactionId"), json.getLong("sequence"),
            json.getString("type"), json.optJSONObject("data") ?: JSONObject(),
            json.text("timestamp").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() }
        )
    }
}

/** Pure ordered reduction. Unknown events still advance the cursor; gaps trigger replay. */
fun Interaction.reduce(event: Event): Interaction {
    require(event.interactionId == id) { "Event belongs to another interaction" }
    if (event.sequence <= sequence) return this
    require(event.sequence == sequence + 1) { "Event gap; reconnect to recover" }
    var next = copy(sequence = event.sequence, eventTime = event.timestamp ?: eventTime,
        receivedAt = if (event.timestamp != null) System.nanoTime() / 1_000_000 else receivedAt)
    val data = event.data
    when (event.type) {
        "tts.started" -> next = next.copy(audioGenerating = true, audioError = null)
        "tts.audio" -> next = next.copy(audioAvailable = true, audioError = null)
        "tts.completed" -> next = next.copy(audioGenerating = false)
        "tts.failed" -> next = next.copy(audioGenerating = false, audioError = data.text("message"))
        "model.output.started", "model.output.delta", "model.output.completed" -> {
            val stepId = data.getString("stepId")
            val previous = modelOutputs[stepId] ?: ModelOutput(data.getInt("modelRound"))
            val output = previous.copy(
                text = previous.text + if (event.type == "model.output.delta") data.text("text") else "",
                status = if (event.type == "model.output.completed") data.text("status", "completed") else previous.status,
                truncated = previous.truncated || data.optBoolean("truncated"))
            next = next.copy(modelOutputs = modelOutputs + (stepId to output))
        }
        "step.updated" -> {
            val stepId = data.getString("stepId")
            val detail = StepDetail(data.payload("input"), data.payload("output"), "",
                data.optBoolean("inputTruncated") || data.optBoolean("outputTruncated"))
            next = next.copy(stepDetails = stepDetails + (stepId to detail))
        }
        "response.delta" -> next = next.copy(response = response + data.text("text"), status = "responding")
        "response.completed" -> next = next.copy(response = data.text("text"))
        "stt.started" -> next = next.copy(status = "transcribing")
        "stt.partial", "stt.final" -> next = next.copy(input = data.text("text"), status = "transcribing")
        "step.started", "step.completed", "step.failed" -> {
            val stepId = data.getString("stepId")
            val old = steps.find { it.id == stepId }
            val step = Step(stepId, data.text("parentStepId", old?.parentId ?: ""),
                data.text("label", old?.label ?: "Step"),
                if (event.type == "step.started") "running" else data.text("status", event.type.substringAfter('.')),
                kind = data.text("kind", old?.kind ?: ""), summary = data.text("summary", old?.summary ?: ""),
                startedAt = old?.startedAt ?: if (event.type == "step.started") event.timestamp else null,
                finishedAt = if (event.type == "step.started") null else event.timestamp,
                durationMs = if (!data.isNull("durationMs")) data.optDouble("durationMs").takeIf { it.isFinite() && it >= 0 } else old?.durationMs)
            next = next.copy(steps = if (old == null) steps + step else steps.map { if (it.id == stepId) step else it })
        }
        "interaction.created", "interaction.started" -> next = next.copy(status = if (event.type.endsWith("started")) "running" else "created")
        "context.added", "context.updated" -> next = next.copy(contexts = contexts +
            (data.text("id", "event-${event.sequence}") to data.toString(2)))
        "interaction.completed", "interaction.failed", "interaction.cancelled" -> next = next.copy(
            status = event.type.substringAfter('.'), error = data.text("message").takeIf { it.isNotBlank() })
    }
    // Bounded semantic feed. Steps are kept separately to preserve parent/child relationships.
    if (event.type in setOf("interaction.created", "interaction.started", "interaction.completed", "interaction.failed",
            "interaction.cancelled", "response.started", "response.completed", "stt.started", "stt.final", "context.added", "context.updated")) {
        val label = data.text("label").ifBlank { data.text("message") }.ifBlank { event.type }
        next = next.copy(feed = (feed + label).takeLast(100))
    }
    return next
}
