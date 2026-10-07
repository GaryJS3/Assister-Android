package net.thegary.assister

import net.thegary.assister.voice.SpeechEndpointDetector
import net.thegary.assister.voice.SpeechEndpointDetector.Result.*
import org.junit.Assert.*
import org.junit.Test

class SpeechEndpointDetectorTest {
    private fun pcm(milliseconds: Int, amplitude: Int = 0): ByteArray = ByteArray(milliseconds * 32).also { bytes ->
        for (offset in bytes.indices step 2) {
            val sample = if (offset % 4 == 0) amplitude else -amplitude
            bytes[offset] = sample.toByte()
            bytes[offset + 1] = (sample shr 8).toByte()
        }
    }

    @Test fun ends_after_speech_and_one_point_four_seconds_of_quiet() {
        val detector = SpeechEndpointDetector()
        assertEquals(LISTENING, detector.accept(pcm(300, 1800)))
        assertEquals(LISTENING, detector.accept(pcm(1380)))
        assertEquals(FINISHED, detector.accept(pcm(20)))
        assertEquals(FINISHED, detector.accept(pcm(500, 1800)))
    }

    @Test fun brief_pause_resets_when_speaking_resumes() {
        val detector = SpeechEndpointDetector()
        detector.accept(pcm(300, 1800))
        assertEquals(LISTENING, detector.accept(pcm(1000)))
        detector.accept(pcm(100, 1800))
        assertEquals(LISTENING, detector.accept(pcm(1380)))
        assertEquals(FINISHED, detector.accept(pcm(20)))
    }

    @Test fun no_speech_stops_at_eight_seconds_without_submitting_silence() {
        val detector = SpeechEndpointDetector()
        assertEquals(LISTENING, detector.accept(pcm(7980)))
        assertEquals(NO_SPEECH, detector.accept(pcm(20)))
    }

    @Test fun isolated_click_and_low_background_do_not_count_as_speech() {
        val detector = SpeechEndpointDetector()
        detector.accept(pcm(60, 12000))
        assertEquals(LISTENING, detector.accept(pcm(7900, 120)))
        assertEquals(NO_SPEECH, detector.accept(pcm(40, 120)))
    }

    @Test fun short_reads_produce_the_same_endpoint_as_large_chunks() {
        val detector = SpeechEndpointDetector()
        repeat(100) { assertEquals(LISTENING, detector.accept(pcm(3, 1700))) }
        repeat(1399) { assertEquals(LISTENING, detector.accept(pcm(1))) }
        assertEquals(FINISHED, detector.accept(pcm(1)))
    }

    @Test fun continuous_activity_stays_listening_for_recorder_duration_cap() {
        val detector = SpeechEndpointDetector()
        assertEquals(LISTENING, detector.accept(pcm(30000, 1800)))
    }

    @Test fun short_spoken_command_can_finish_without_waiting_for_no_speech_timeout() {
        val detector = SpeechEndpointDetector()
        assertEquals(LISTENING, detector.accept(pcm(120, 1800)))
        assertEquals(FINISHED, detector.accept(pcm(1400)))
    }
}
