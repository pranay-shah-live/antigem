package ai.antigem.data.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ai.antigem.MainActivity
import ai.antigem.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The launcher shortcut and the Quick Settings tile can start a Gemini Live voice call without opening a chat first.
 *
 * The intent ([MainActivity.EXTRA_START_VOICE_CALL]) only leaves a request here: the chat screen owns the call (it needs
 * its view model, the chat on screen and the microphone permission), so it takes the request and starts the call.
 */
object VoiceCallShortcut {

    /** The shortcut's id in the launcher (a dynamic shortcut, so the debug build's own package name works too). */
    private const val SHORTCUT_ID = "live_voice_call"

    private val _pending = MutableStateFlow(false)

    /** A call was asked for and not taken yet. */
    val pending: StateFlow<Boolean> = _pending

    fun request() {
        _pending.value = true
    }

    /** Takes the request: true once, then the next caller gets false. */
    fun consume(): Boolean {
        if (!_pending.value) return false
        _pending.value = false
        return true
    }

    /** Publishes "Live voice call" so the launcher offers it (long-press the icon, then drag it to the home screen). */
    fun publish(context: Context) {
        runCatching {
            val others = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id != SHORTCUT_ID }
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_START_VOICE_CALL, true)
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel("Live voice")
                .setLongLabel("Start a live voice call")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_jarvis_mic))
                .setIntent(intent)
                .setRank(0)
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(context, others + shortcut)
        }
    }
}
