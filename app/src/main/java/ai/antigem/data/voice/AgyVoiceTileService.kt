package ai.antigem.data.voice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.antigem.MainActivity
import ai.antigem.ui.bubble.FloatingChatActivity

/** Dedicated Quick Settings entry point for the Antigravity/Gemini voice assistant, separate from Jarvis. */
class AgyVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val inCall = AgyVoiceCall.state.value.active
        qsTile?.apply {
            state = if (inCall) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Gemini Voice"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (inCall) "Call active" else "Tap to talk"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, FloatingChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_VOICE_CALL, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 79, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
