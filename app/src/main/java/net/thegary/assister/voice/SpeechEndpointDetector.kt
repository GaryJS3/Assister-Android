package net.thegary.assister.voice

import kotlin.math.sqrt

/** PCM energy endpointing, not transcription: 120 ms activity followed by 1.4 s quiet. */
class SpeechEndpointDetector {
    enum class Result { LISTENING, FINISHED, NO_SPEECH }
    private var noise = 80.0
    private var samples = 0
    private var frameSamples = 0
    private var squareSum = 0.0
    private var activityFrames = 0
    private var quietFrames = 0
    private var speech = false
    private var result = Result.LISTENING

    fun accept(bytes: ByteArray, count: Int = bytes.size): Result {
        require(count in 0..bytes.size && count % 2 == 0)
        for (offset in 0 until count step 2) {
            if (result != Result.LISTENING) break
            val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toDouble()
            squareSum += sample * sample
            samples++
            frameSamples++
            if (frameSamples == 320) {
                val rms = sqrt(squareSum / frameSamples)
                val active = rms >= maxOf(350.0, noise * 3.0)
                if (active) {
                    activityFrames++
                    quietFrames = 0
                    if (activityFrames >= 6) speech = true
                } else {
                    quietFrames++
                    if (!speech) activityFrames = 0
                    // Track background slowly; never train the floor on foreground speech.
                    noise = (noise * 0.98 + rms * 0.02).coerceAtMost(2500.0)
                }
                if (speech && quietFrames >= 70) result = Result.FINISHED
                else if (!speech && samples >= 8 * 16000) result = Result.NO_SPEECH
                frameSamples = 0
                squareSum = 0.0
            }
        }
        return result
    }
}
