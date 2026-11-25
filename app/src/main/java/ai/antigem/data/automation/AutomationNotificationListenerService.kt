package ai.antigem.data.automation

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listens to active system notifications so android_automation_mcp can read and reply to
 * WhatsApp, Gmail, and other app notifications in a single tool call.
 */
class AutomationNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun listNotifications(
        packageFilter: String? = null,
        query: String? = null,
        includeOngoing: Boolean = false,
        limit: Int = 50
    ): JSONArray {
        val pm = packageManager
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val arr = JSONArray()
        val notifications = runCatching { activeNotifications }.getOrNull() ?: return arr

        for (sbn in notifications.sortedByDescending { it.postTime }) {
            if (arr.length() >= limit) break
            val n = sbn.notification ?: continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (isOngoing && !includeOngoing) continue
            if (!packageFilter.isNullOrBlank() && !sbn.packageName.contains(packageFilter, ignoreCase = true)) continue

            val extras = n.extras ?: Bundle.EMPTY
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)

            val messages = extractMessages(extras)

            if (!query.isNullOrBlank()) {
                val haystack = buildString {
                    append(appName).append(' ').append(sbn.packageName).append(' ')
                    append(title).append(' ').append(text).append(' ').append(subText)
                    for (i in 0 until messages.length()) {
                        val m = messages.getJSONObject(i)
                        append(' ').append(m.optString("sender")).append(' ').append(m.optString("text"))
                    }
                }
                if (!haystack.contains(query, ignoreCase = true)) continue
            }

            val actions = n.actions.orEmpty()
            val replyAction = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            val actionTitles = JSONArray().apply {
                actions.mapNotNull { it.title?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } }
                    .forEach { put(it) }
            }

            arr.put(JSONObject().apply {
                put("key", sbn.key)
                put("package", sbn.packageName)
                put("app_name", appName)
                put("time", fmt.format(Date(sbn.postTime)))
                put("post_time_ms", sbn.postTime)
                if (title.isNotEmpty()) put("title", title)
                if (text.isNotEmpty()) put("text", text)
                if (subText.isNotEmpty()) put("sub_text", subText)
                if (messages.length() > 0) put("messages", messages)
                put("ongoing", isOngoing)
                put("can_reply", replyAction != null)
                if (actionTitles.length() > 0) put("actions", actionTitles)
            })
        }
        return arr
    }

    private fun extractMessages(extras: Bundle): JSONArray {
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return arr
        @Suppress("DEPRECATION")
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return arr
        for (item in raw) {
            val b = item as? Bundle ?: continue
            val msgText = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            val sender = b.getCharSequence("sender")?.toString()?.trim()
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    b.getParcelable<android.app.Person>("sender_person")?.name?.toString()?.trim()
                } else {
                    null
                }
            if (msgText.isNotEmpty()) {
                arr.put(JSONObject().apply {
                    if (!sender.isNullOrEmpty()) put("sender", sender)
                    put("text", msgText)
                    b.getLong("time", 0L).takeIf { it > 0L }?.let { put("time_ms", it) }
                })
            }
        }
        return arr
    }

    fun replyToNotification(key: String, replyText: String): Result<String> {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
            ?: return Result.failure(IllegalArgumentException("No active notification found for key \"$key\". Call android_get_notifications first."))
        val replyAction = sbn.notification?.actions.orEmpty().firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: return Result.failure(IllegalStateException("Notification \"$key\" (${sbn.packageName}) does not support inline reply."))
        val remoteInputs = replyAction.remoteInputs ?: return Result.failure(IllegalStateException("Missing RemoteInput."))
        val intent = Intent()
        val bundle = Bundle().apply {
            for (ri in remoteInputs) {
                putCharSequence(ri.resultKey, replyText)
            }
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        return runCatching {
            replyAction.actionIntent.send(this, 0, intent)
            val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            "Sent reply to ${title ?: sbn.packageName} (${sbn.packageName})."
        }
    }

    companion object {
        @Volatile
        var instance: AutomationNotificationListenerService? = null
            private set

        fun component(context: Context) = ComponentName(context, AutomationNotificationListenerService::class.java)

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val c = component(context)
            return enabled.split(':').any { it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true) }
        }

        fun notReadyMessage(context: Context): String? = when {
            instance != null -> null
            isEnabledInSettings(context) ->
                "Notification access for AntiGem is enabled in Settings but the listener is not bound yet. " +
                    "Ask the user to toggle AntiGem off and on in Settings → Notification access."
            else ->
                "Notification access is not granted. Ask the user to enable AntiGem in Android Settings → Apps → Special app access → Notification access " +
                    "(or grant it via `cmd notification allow_listener ${component(context).flattenToString()}`)."
        }
    }
}
