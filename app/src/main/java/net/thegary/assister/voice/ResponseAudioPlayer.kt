package net.thegary.assister.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import java.io.File

interface ResponseAudioPlayer {
    fun play(file: File, onState: (String) -> Unit)
    fun stop()
}

/** One foreground speech session at a time; stop on focus loss or headset removal. */
class AndroidResponseAudioPlayer(private val context: Context) : ResponseAudioPlayer {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var listener: ((String) -> Unit)? = null
    private var started = false
    private var registered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stop() }
    }

    override fun play(file: File, onState: (String) -> Unit) {
        stop()
        listener = onState
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }.build()
            focus = request
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                finish("failed")
                return
            }
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                if (player === it) {
                    try { it.start(); started = true; listener?.invoke("started") }
                    catch (_: Exception) { finish("failed") }
                }
            }
            media.setOnCompletionListener { if (player === it) finish("completed") }
            media.setOnErrorListener { mediaPlayer, _, _ -> if (player === mediaPlayer) finish("failed"); true }
            media.prepareAsync()
        } catch (_: Exception) { finish("failed") }
    }

    override fun stop() { if (listener != null) finish(if (started) "stopped" else "failed") }

    private fun finish(state: String) {
        val callback = listener
        listener = null
        player?.apply { setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null); release() }
        player = null
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        if (registered) { context.unregisterReceiver(noisy); registered = false }
        started = false
        callback?.invoke(state)
    }
}
