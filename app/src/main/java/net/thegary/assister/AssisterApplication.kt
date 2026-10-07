package net.thegary.assister

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import net.thegary.assister.interaction.ConversationViewModel
import net.thegary.assister.assistant.AssistantActivity
import java.lang.ref.WeakReference

/** Created lazily when a visible client opens, never by the idle voice service. */
class AssisterApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val conversation: ConversationViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[ConversationViewModel::class.java]
    }
    private val visibleClients = mutableSetOf<Any>()
    internal var assistantWindow = WeakReference<AssistantActivity>(null)

    fun visible(client: Any, value: Boolean) {
        if (value) visibleClients.add(client) else visibleClients.remove(client)
        conversation.setForeground(visibleClients.isNotEmpty())
        if (visibleClients.isEmpty() && conversation.state.value.recording) conversation.discardRecording()
    }
}
