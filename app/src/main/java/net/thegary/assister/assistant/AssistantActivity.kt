package net.thegary.assister.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.thegary.assister.AssisterApplication
import net.thegary.assister.MainActivity
import net.thegary.assister.interaction.ConversationViewModel
import net.thegary.assister.ui.AssistantContent
import net.thegary.assister.ui.theme.AssisterTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class AssistantActivity : ComponentActivity() {
    private val runtime get() = application as AssisterApplication
    internal val conversation: ConversationViewModel get() = runtime.conversation
    private var awaitingInvocation = true
    private var permissionRequested = false
    private var resumed = false
    private var expanding = false
    private var ownsRecording = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) tryListen() else { awaitingInvocation = false; conversation.permissionDenied() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.assistantWindow = java.lang.ref.WeakReference(this)
        awaitingInvocation = savedInstanceState?.getBoolean("awaiting") ?: true
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        ownsRecording = savedInstanceState?.getBoolean("ownsRecording") ?: false
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.35f }
        setContent {
            val state by conversation.state.collectAsState()
            AssisterTheme {
                AssistantContent(state, ::record, conversation::finishRecording, conversation::discardRecording,
                    conversation::cancelInteraction, conversation::send, { conversation.connect() }, ::expand, ::finish,
                    conversation::inspect, conversation::toggleAudio, conversation::retryAudio, conversation::discardPending)
            }
        }
        lifecycleScope.launch { conversation.state.collect { tryListen() } }
    }

    private fun tryListen() {
        if (!resumed || !awaitingInvocation) return
        val state = conversation.state.value
        // Invoking over an existing request shows it, without opening a second microphone/request.
        if (state.busy || state.pending) { if (state.active != null || state.recording || state.pending) awaitingInvocation = false; return }
        if (!state.connected || !state.supportsVoice) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) { permissionRequested = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
            return
        }
        awaitingInvocation = false
        ownsRecording = true
        conversation.startRecording()
    }

    private fun record() { permissionRequested = false; awaitingInvocation = true; tryListen() }

    internal fun expand() {
        expanding = true
        // Retain foreground ownership until MainActivity starts; no capture/playback gap on handoff.
        runtime.visible("conversation", true)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        awaitingInvocation = true
        permissionRequested = false
        tryListen()
    }

    override fun onResume() { super.onResume(); resumed = true; tryListen() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStart() { super.onStart(); runtime.visible("assistant", true) }
    override fun onStop() {
        if (!isChangingConfigurations) {
            if (!expanding && ownsRecording && conversation.state.value.recording) conversation.discardRecording()
            ownsRecording = false
            runtime.visible("assistant", false)
            if (!expanding) awaitingInvocation = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaiting", awaitingInvocation)
        outState.putBoolean("permissionRequested", permissionRequested)
        outState.putBoolean("ownsRecording", ownsRecording)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (runtime.assistantWindow.get() === this) runtime.assistantWindow.clear()
        super.onDestroy()
    }
}
