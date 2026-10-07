package net.thegary.assister.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import net.thegary.assister.AssisterApplication

/** Android keeps this service available; it owns no microphone, model, or network client. */
class AssisterVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

class AssisterVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssisterVoiceInteractionSession(this)
}

class AssisterVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Supported assistant Activity owns the compact window and runtime-permission flow.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onHide() {
        (context.applicationContext as AssisterApplication).assistantWindow.get()?.finish()
        super.onHide()
    }

    override fun onDestroy() {
        (context.applicationContext as AssisterApplication).assistantWindow.get()?.finish()
        super.onDestroy()
    }
}
