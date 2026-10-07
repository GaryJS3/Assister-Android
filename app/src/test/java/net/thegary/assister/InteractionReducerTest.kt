package net.thegary.assister

import net.thegary.assister.protocol.Event
import net.thegary.assister.protocol.Interaction
import net.thegary.assister.protocol.reduce
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InteractionReducerTest {
    @Test fun speech_generation_and_failures_do_not_replace_the_answer_or_confirm_playback() {
        val state = Interaction("interaction", "Question")
            .reduce(event(1, "response.completed", """{"text":"The lights are on."}"""))
            .reduce(event(2, "tts.started"))
        assertTrue(state.audioGenerating)
        val ready = state.reduce(event(3, "tts.audio", """{"url":"/api/client/interactions/interaction/audio"}"""))
            .reduce(event(4, "tts.completed"))
        assertTrue(ready.audioAvailable)
        assertFalse(ready.audioGenerating)
        assertEquals("idle", ready.playback)
        assertEquals("The lights are on.", ready.response)
        val failed = state.reduce(event(3, "tts.failed", """{"message":"Speech unavailable"}"""))
        assertEquals("Speech unavailable", failed.audioError)
        assertFalse(failed.audioGenerating)
        assertEquals(state.response, failed.response)
        assertNull(failed.error)
    }

    @Test fun streamed_round_fragments_append_exactly_and_replay_never_duplicates_them() {
        val events = listOf(
            event(1, "model.output.started", """{"stepId":"r1","modelRound":1}"""),
            event(2, "model.output.delta", """{"stepId":"r1","modelRound":1,"text":"I will check ","truncated":false}"""),
            event(3, "reasoning.delta", """{"stepId":"r1","text":"private reasoning"}"""),
            event(4, "model.output.delta", """{"stepId":"r1","modelRound":1,"text":"the office.","truncated":true}"""),
            event(5, "model.output.completed", """{"stepId":"r1","modelRound":1,"status":"completed","truncated":false}"""),
            event(6, "step.updated", """{"stepId":"r1","output":{"assistantContent":"A different bounded snapshot"}}"""),
            event(7, "response.completed", """{"text":"The office light is on."}""")
        )
        val initial = Interaction("interaction", "Question")
        val state = events.fold(initial) { value, next -> value.reduce(next) }
        assertEquals("I will check the office.", state.modelOutputs.getValue("r1").text)
        assertEquals("completed", state.modelOutputs.getValue("r1").status)
        assertTrue(state.modelOutputs.getValue("r1").truncated)
        assertEquals("The office light is on.", state.response)
        assertEquals(state, events.fold(state) { value, next -> value.reduce(next) })
        assertEquals(state.modelOutputs, events.fold(initial) { value, next -> value.reduce(next) }.modelOutputs)
    }

    @Test fun model_round_failure_and_cancellation_preserve_partial_text_independently() {
        val state = Interaction("interaction", "Question")
            .reduce(event(1, "model.output.delta", """{"stepId":"r1","modelRound":1,"text":"Partial first round"}"""))
            .reduce(event(2, "model.output.completed", """{"stepId":"r1","modelRound":1,"status":"failed"}"""))
            .reduce(event(3, "model.output.delta", """{"stepId":"r2","modelRound":2,"text":"Partial second round"}"""))
            .reduce(event(4, "model.output.completed", """{"stepId":"r2","modelRound":2,"status":"cancelled"}"""))
        assertEquals("failed", state.modelOutputs.getValue("r1").status)
        assertEquals("Partial first round", state.modelOutputs.getValue("r1").text)
        assertEquals(2, state.modelOutputs.getValue("r2").modelRound)
        assertEquals("cancelled", state.modelOutputs.getValue("r2").status)
        assertEquals("Partial second round", state.modelOutputs.getValue("r2").text)
        assertEquals("", state.response)
    }

    @Test fun step_updates_replace_payloads_and_keep_round_text_out_of_final_answer() {
        val start = Interaction("interaction", "Question")
            .reduce(event(1, "step.started", """{"stepId":"round1","kind":"LanguageModel","label":"LLM Round 1"}"""))
            .reduce(event(2, "step.updated", """{"stepId":"round1","input":{"messages":[]},"output":null,"inputTruncated":false,"outputTruncated":false}"""))
        assertEquals("", start.stepDetails.getValue("round1").output)
        val update = event(3, "step.updated", """{"stepId":"round1","input":{"messages":[]},"output":{"assistantContent":"I'll check the office lights.","toolCalls":[{"name":"ha_search","arguments":{"query":"office"}}]},"outputTruncated":true}""")
        val done = start.reduce(update)
        assertEquals("I'll check the office lights.", done.stepDetails.getValue("round1").modelText)
        assertTrue(done.stepDetails.getValue("round1").output.contains("ha_search"))
        assertTrue(done.stepDetails.getValue("round1").truncated)
        assertEquals("", done.response)
        assertEquals("running", done.steps.single().status)
        assertEquals(done, done.reduce(update))
        val answered = done.reduce(event(4, "response.completed", """{"text":"The lights are on."}"""))
        assertEquals("The lights are on.", answered.response)
        assertEquals(done.stepDetails, answered.stepDetails)
    }

    @Test fun multiple_rounds_and_tool_details_replay_with_independent_payloads() {
        val events = listOf(
            event(1, "step.updated", """{"stepId":"r1","output":{"assistantContent":"Search first"}}"""),
            event(2, "step.updated", """{"stepId":"tool","input":{"query":"office"},"output":{"state":"on"}}"""),
            event(3, "step.updated", """{"stepId":"r2","output":{"assistantContent":"Now check brightness"}}"""),
            event(4, "reasoning.delta", """{"stepId":"r2","text":"Provider reasoning"}""")
        )
        val initial = Interaction("interaction", "Question")
        val state = events.fold(initial) { value, next -> value.reduce(next) }
        assertEquals(state.stepDetails, events.fold(initial) { value, next -> value.reduce(next) }.stepDetails)
        assertEquals(3, state.stepDetails.size)
        assertEquals("Search first", state.stepDetails.getValue("r1").modelText)
        assertEquals("Now check brightness", state.stepDetails.getValue("r2").modelText)
        assertEquals("", state.stepDetails.getValue("tool").modelText)
        assertEquals("", state.response)
        assertEquals(4L, state.sequence)
    }

    @Test fun timestamps_and_authoritative_duration_survive_replay_and_stop_ticking() {
        val started = Event.parse(JSONObject("""{"interactionId":"interaction","sequence":1,"type":"step.started","timestamp":"2026-10-05T12:00:00Z","data":{"stepId":"tool","kind":"ToolCall","label":"ha_search","summary":"Find entities"}}"""))
        val state = Interaction("interaction", "Question").reduce(started)
        val step = state.steps.single()
        assertEquals(700.0, step.elapsedMs(step.startedAt!! + 700)!!, 0.001)
        val done = state.reduce(Event("interaction", 2, "step.completed", JSONObject("""{"stepId":"tool","status":"succeeded","durationMs":720.3}"""), step.startedAt + 721))
        assertEquals("ToolCall", done.steps.single().kind)
        assertEquals("Find entities", done.steps.single().summary)
        assertEquals(720.3, done.steps.single().elapsedMs(step.startedAt + 90000)!!, 0.001)
        assertEquals(done, done.reduce(started))
    }

    @Test fun context_updates_replace_metadata_instead_of_adding_filler_rows() {
        val state = Interaction("interaction", "Question")
            .reduce(event(1, "context.added", """{"id":"ctx","name":"Office state","modelRounds":[1]}"""))
            .reduce(event(2, "context.updated", """{"id":"ctx","name":"Office state","modelRounds":[1,2]}"""))
        assertEquals(1, state.contexts.size)
        assertTrue(state.contexts.getValue("ctx").contains("2"))
    }

    private fun event(sequence: Long, type: String, body: String = "{}") = Event("interaction", sequence, type, JSONObject(body))

    @Test fun deltas_stream_and_completion_replaces_provisional_answer() {
        val initial = Interaction("interaction", "Question")
        val first = initial.reduce(event(1, "response.delta", """{"text":"Hello "}"""))
        assertEquals("Hello ", first.response)
        val second = first.reduce(event(2, "response.delta", """{"text":"world"}"""))
        assertEquals("Hello world", second.response)
        val corrected = second.reduce(event(3, "response.completed", """{"text":"Authoritative answer"}"""))
        assertEquals("Authoritative answer", corrected.response)
        assertFalse(corrected.terminal)
        assertTrue(corrected.reduce(event(4, "interaction.completed")).terminal)
    }

    @Test fun duplicate_replay_does_not_duplicate_text_and_unknown_events_advance_cursor() {
        val delta = event(1, "response.delta", """{"text":"Once"}""")
        val state = Interaction("interaction", "Question").reduce(delta)
        assertEquals(state, state.reduce(delta))
        val advanced = state.reduce(event(2, "future.optional"))
        assertEquals(2L, advanced.sequence)
        assertEquals("Once", advanced.response)
    }

    @Test(expected = IllegalArgumentException::class) fun sequence_gap_requires_replay() {
        Interaction("interaction", "Question").reduce(event(2, "response.delta"))
    }

    @Test(expected = IllegalArgumentException::class) fun wrong_interaction_is_rejected() {
        Interaction("different", "Question").reduce(event(1, "interaction.started"))
    }

    @Test fun nested_parallel_steps_update_independently() {
        val start = Interaction("interaction", "Question")
            .reduce(event(1, "step.started", """{"stepId":"a","label":"Research"}"""))
            .reduce(event(2, "step.started", """{"stepId":"b","parentStepId":"a","label":"Search"}"""))
            .reduce(event(3, "step.started", """{"stepId":"c","parentStepId":"a","label":"Lookup"}"""))
            .reduce(event(4, "step.completed", """{"stepId":"b","label":"Search","status":"completed"}"""))
        assertEquals("completed", start.steps.first { it.id == "b" }.status)
        assertEquals("running", start.steps.first { it.id == "c" }.status)
        assertEquals("a", start.steps.first { it.id == "b" }.parentId)
    }

    @Test fun transcript_and_failure_remain_visible_without_losing_response() {
        val state = Interaction("interaction", "Voice input")
            .reduce(event(1, "stt.partial", """{"text":"What time"}"""))
            .reduce(event(2, "stt.final", """{"text":"What time is it?"}"""))
            .reduce(event(3, "response.completed", """{"text":"No clock available"}"""))
            .reduce(event(4, "interaction.failed", """{"message":"Clock failed"}"""))
        assertEquals("What time is it?", state.input)
        assertEquals("No clock available", state.response)
        assertEquals("Clock failed", state.error)
        assertTrue(state.terminal)
    }

    @Test fun explicit_json_null_does_not_become_error_text_or_a_parent_id() {
        val state = Interaction("interaction", "Question")
            .reduce(event(1, "step.started", """{"stepId":"a","parentStepId":null,"label":"Check"}"""))
            .reduce(event(2, "interaction.completed", """{"message":null}"""))
        assertNull(state.error)
        assertEquals("", state.steps.single().parentId)
        assertEquals("interaction.completed", state.feed.last())
        assertEquals("", Interaction.snapshot(JSONObject("""{"id":"i","input":"Q","response":null,"status":"created"}""")).response)
    }
}
