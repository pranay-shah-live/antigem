package ai.antigem.data.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ai.antigem.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The launcher shortcut and the Quick Settings tile can start a Gemini Live voice call without opening a chat first.
 *
 * The intent (MainActivity.EXTRA_START_VOICE_CALL) only leaves a request here: the chat screen owns the call (it needs
 * its view model, the chat on screen and the microphone permission), so it takes the request and starts the call.
 */
object VoiceCallShortcut {

    data class Request(
        /** The voice-assistant entry point uses its own persistent Antigravity conversation. */
        val assistant: Boolean = true,
        /** Only the floating call window should consume requests launched from the tile or home shortcut. */
        val floatingOnly: Boolean = true
    )

    /** The shortcut's id in the launcher (a dynamic shortcut, so the debug build's own package name works too). */
    private const val SHORTCUT_ID = "live_voice_call"
    private const val PREFS = "agy_voice_assistant"
    private const val ASSISTANT_CHAT_ID = "conversation_id"

    private val _pending = MutableStateFlow<Request?>(null)
    private val _floatingWindowOpen = MutableStateFlow(false)

    /** A call was asked for and not taken yet. */
    val pending: StateFlow<Request?> = _pending
    val floatingWindowOpen: StateFlow<Boolean> = _floatingWindowOpen

    fun request(request: Request = Request()) {
        _pending.value = request
    }

    fun setFloatingWindowOpen(open: Boolean) {
        _floatingWindowOpen.value = open
    }

    /** Takes a matching request once; the main chat cannot steal a request meant for the floating window. */
    fun consume(floatingWindow: Boolean): Request? {
        val request = _pending.value ?: return null
        if (request.floatingOnly != floatingWindow) return null
        _pending.value = null
        return request
    }

    fun assistantConversationId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ASSISTANT_CHAT_ID, null)

    fun saveAssistantConversationId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ASSISTANT_CHAT_ID, id).apply()
    }

    /** Publishes "Live voice call" so the launcher offers it (long-press the icon, then drag it to the home screen). */
    fun publish(context: Context) {
        runCatching {
            val others = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id != SHORTCUT_ID }
            val intent = Intent(context, ai.antigem.ui.bubble.FloatingChatActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(ai.antigem.MainActivity.EXTRA_START_VOICE_CALL, true)
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel("Gemini")
                .setLongLabel("Gemini Voice Assistant")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_jarvis_mic))
                .setIntent(intent)
                .setRank(0)
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(context, others + shortcut)
        }
    }
}
