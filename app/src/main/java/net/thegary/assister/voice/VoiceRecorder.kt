package net.thegary.assister.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class VoiceRecorder(private val context: Context) {
    @Volatile private var stopping = false

    fun stop() { stopping = true }

    suspend fun capture(automaticEnd: Boolean = false, onSeconds: suspend (Int) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        stopping = false
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz microphone capture is unavailable" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone is unavailable" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(3200)
            val endpoint = if (automaticEnd) SpeechEndpointDetector() else null
            var seconds = -1
            while (!stopping && currentCoroutineContext().isActive && output.size() < MAX_BYTES) {
                val count = recorder.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size()))
                check(count > 0) { "Microphone read failed ($count)" }
                output.write(buffer, 0, count)
                val now = output.size() / 32000
                if (now != seconds) { seconds = now; onSeconds(now) }
                when (endpoint?.accept(buffer, count)) {
                    SpeechEndpointDetector.Result.FINISHED -> break
                    SpeechEndpointDetector.Result.NO_SPEECH -> error(context.getString(net.thegary.assister.R.string.no_speech_detected))
                    else -> Unit
                }
            }
            output.toByteArray()
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
    }

    companion object { const val MAX_BYTES = 30 * 16000 * 2 }
}
