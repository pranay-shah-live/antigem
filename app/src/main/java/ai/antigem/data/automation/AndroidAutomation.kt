package ai.antigem.data.automation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.ContextCompat
import ai.antigem.data.adb.DeviceAccess
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * android_automation_mcp: lets the model see and drive the phone through the accessibility service,
 * with a few shell-user extras (keys, install, uninstall, force stop, shell) that go through the device access mode picked
 * in Settings → ADB Connect: ADB, Shizuku or root ([DeviceAccess]). Served by AndroidLocalBridgeServer at /android.
 */
object AndroidAutomation {

    const val INSTRUCTIONS = "Controls this Android phone and personal-assistant actions. " +
        "PREFER 1-shot assistant/API tools first instead of shell (`run_command`, `rish`, `android_adb_shell`) or UI-tapping loops: " +
        "NEVER run `adb shell` via `run_command` — use `android_*` MCP tools directly. " +
        "use android_get_schedule for calendar events, android_play_media for music/playback, android_call_contact + android_call_control for calls, " +
        "android_set_alarm / android_set_timer for alarms and timers, android_open_camera for camera, " +
        "android_get_notifications / android_reply_notification for WhatsApp/Gmail/app notifications (batch-triage 5+ items with `jev_batch` / `jev_ask`), " +
        "android_launch_app / android_open_intent / android_list_apps for apps and deep links, and android_get_device_info / android_get_status for phone state. " +
        "All of these non-UI tools work even when the Accessibility service is off. " +
        "Only when no 1-shot tool covers the task: prefer `android_get_ui_tree(format='compact')` + `android_tap(element_id)` / `android_type_text` / `android_scroll` " +
        "over `android_screenshot` + raw coordinates (ids are only valid for the latest tree; use screenshots only for unlabelled canvases/WebViews). " +
        "VERIFICATION: NEVER mark a UI action (Play, Update, Send) complete without verifying the resulting state on screen or via audio/status. " +
        "SAFETY: For android_call_contact, ALWAYS look up the contact first (confirm=false) and confirm the recipient with the user before calling with confirm=true. " +
        "Ask the user before anything destructive or outward-facing: replying to notifications (android_reply_notification), uninstalling, deleting, sending messages, payments, changing security settings. " +
        "If a tool reports a missing permission, Notification access, Accessibility service, or shell access, tell the user exactly what to grant and stop."

    /** Elements of the latest android_get_ui_tree, by id. */
    private val elements = ConcurrentHashMap<String, AccessibilityNodeInfo>()

    // ---------------- readiness ----------------

    private fun component(context: Context) = ComponentName(context, AutomationAccessibilityService::class.java)

    fun isServiceRunning(): Boolean = AutomationAccessibilityService.instance != null

    fun isServiceEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val c = component(context)
        return enabled.split(':').any { it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true) }
    }

    fun canTakeScreenshots(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Why the MCP can't be used yet, or null when it can. */
    fun notReadyMessage(context: Context): String? = when {
        isServiceRunning() -> null
        isServiceEnabledInSettings(context) -> "Android Automation's accessibility service is turned on but not running. " +
            "Ask the user to turn \"AntiGem Android Automation\" off and on again in Android Settings → Accessibility, then reconnect this MCP server."
        else -> "Android Automation is not set up. Ask the user to open AntiGem → Settings → Android Automation and grant the permissions there " +
            "(the Accessibility service; on Android 13+ they may first need App info → ⋮ → Allow restricted settings). Then reconnect this MCP server."
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Turns the accessibility service on as the shell user or root (the picked ADB / Shizuku / root access), which also
     * works when Android blocks it as a restricted setting.
     */
    suspend fun enableViaShell(context: Context): Result<Unit> {
        DeviceAccess.notReadyMessage()?.let { return Result.failure(IllegalStateException(it)) }
        val name = component(context).flattenToString()
        val read = DeviceAccess.shell("settings get secure enabled_accessibility_services")
        if (read.exitCode != 0) return Result.failure(IllegalStateException(read.output.trim().ifBlank { "settings get failed" }))
        val services = read.output.trim().split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (services.none { it.equals(name, true) }) services += name
        DeviceAccess.shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
        DeviceAccess.shell("settings put secure accessibility_enabled 1")
        repeat(20) {
            if (isServiceRunning()) return Result.success(Unit)
            delay(250)
        }
        return if (isServiceEnabledInSettings(context)) Result.success(Unit) else Result.failure(IllegalStateException("Android didn't accept the change"))
    }

    // ---------------- tool list ----------------

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val elementId = "element_id" to prop("string", "Element id from the latest android_get_ui_tree, e.g. e12")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("android_get_status", "Phone state: screen size, app in front, screen on/locked, and whether screenshots and shell access (ADB, Shizuku or root) are available."))
        put(tool("android_get_device_info",
            "Device details: battery (level, charging, temperature, health), device and Android build, RAM, storage, CPU, display, " +
                "network, audio volumes and ringer mode, brightness, airplane/Do Not Disturb/location state, locale, time zone and uptime.",
            emptyList(),
            "sections" to prop("string", "Comma-separated subset: battery, device, memory, storage, display, network, audio, system (default: all)")
        ))
        put(tool("android_get_ui_tree",
            "List the on-screen elements (text, description, resource id, type, center point, bounds, and clickable/editable/scrollable/checked flags), " +
                "each with an id like e12 for the other tools. Ids are replaced on every call.",
            emptyList(),
            "filter" to prop("string", "Only list elements whose text, description, hint or resource id contains this (case-insensitive)"),
            "all_windows" to prop("boolean", "Include every window (status bar, keyboard, dialogs, other split-screen app), not just the active one. Default false"),
            "max_elements" to prop("integer", "Maximum elements to list (default 300)")
        ))
        put(tool("android_screenshot",
            "Screenshot of the whole screen as an image. Black if the app blocks screenshots. Tap coordinates are screen pixels; the result says how to convert if the image was scaled.",
            emptyList(),
            "max_width" to prop("integer", "Scale down to at most this width in pixels (default 1080)"),
            "quality" to prop("integer", "JPEG quality 10-100 (default 70)")
        ))
        put(tool("android_tap", "Tap (or long-press) an element by id, or a point in screen pixels.", emptyList(),
            elementId,
            "x" to prop("number", "Screen x in pixels, when no element_id"),
            "y" to prop("number", "Screen y in pixels, when no element_id"),
            "long_press" to prop("boolean", "Long-press instead of tap")
        ))
        put(tool("android_type_text", "Type into a text field: the given element, or the focused field. Replaces its text unless append is true.",
            listOf("text"),
            "text" to prop("string", "Text to type"),
            elementId,
            "append" to prop("boolean", "Add to the existing text instead of replacing it"),
            "submit" to prop("boolean", "Press the keyboard's enter/search/send action afterwards")
        ))
        put(tool("android_scroll", "Scroll a list or page. direction is where to move the view: down shows content further down.",
            listOf("direction"),
            "direction" to prop("string", "Which way to scroll", listOf("up", "down", "left", "right")),
            elementId.first to prop("string", "Scrollable element (or a child of one) from the latest tree; omit to swipe across the middle of the screen"),
            "distance" to prop("number", "When swiping: fraction of the screen to move, 0.1-0.9 (default 0.5)")
        ))
        put(tool("android_swipe", "Swipe from one point to another in screen pixels (drag, pull to refresh, sliders).",
            listOf("x1", "y1", "x2", "y2"),
            "x1" to prop("number", "Start x"), "y1" to prop("number", "Start y"),
            "x2" to prop("number", "End x"), "y2" to prop("number", "End y"),
            "duration_ms" to prop("integer", "How long the swipe takes (default 300; longer = slower drag)")
        ))
        put(tool("android_global_action", "System navigation: back, home, recent apps, notification shade, quick settings, power menu, lock screen, split screen.",
            listOf("action"),
            "action" to prop("string", "Action", listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen", "split_screen"))
        ))
        put(tool("android_list_apps", "List installed apps that can be opened, as label and package name.", emptyList(),
            "filter" to prop("string", "Only apps whose label or package contains this")
        ))
        put(tool("android_launch_app", "Open an app by package name or by its label.", emptyList(),
            "package" to prop("string", "Package name, e.g. com.android.settings"),
            "name" to prop("string", "App label as shown in the launcher, e.g. Settings")
        ))
        put(tool("android_open_intent", "Start an activity with an intent: open a link or deep link (https:, tel:, geo:, market:…) or a settings page (action android.settings.WIFI_SETTINGS).",
            emptyList(),
            "action" to prop("string", "Intent action (default android.intent.action.VIEW)"),
            "uri" to prop("string", "Data URI"),
            "package" to prop("string", "Only open in this app")
        ))
        put(tool("android_wait_for", "Wait until text appears (or disappears) on screen, or, without text, until the screen stops changing.", emptyList(),
            "text" to prop("string", "Text or description to wait for"),
            "gone" to prop("boolean", "Wait for the text to disappear instead"),
            "timeout_ms" to prop("integer", "Give up after this long (default 5000, max 30000)")
        ))
        put(tool("android_press_key", "Press a hardware or keyboard key, e.g. ENTER, DEL, VOLUME_UP, POWER, MEDIA_PLAY_PAUSE, or a key code number. BACK, HOME and APP_SWITCH always work; other keys need shell access (ADB, Shizuku or root).",
            listOf("key"),
            "key" to prop("string", "Key name (KEYCODE_ prefix optional) or key code")
        ))
        put(tool("android_install_apk", "Install an APK file from the terminal's storage. Needs shell access (ADB, Shizuku or root).", listOf("path"),
            "path" to prop("string", "APK path, absolute or relative to the terminal home")
        ))
        put(tool("android_uninstall_app", "Uninstall an app. With shell access (ADB, Shizuku or root) it's removed directly; without, Android's uninstall dialog opens and you must tap OK. Always ask the user first.",
            listOf("package"),
            "package" to prop("string", "Package name"),
            "keep_data" to prop("boolean", "Keep the app's data and cache (with shell access only)")
        ))
        put(tool("android_force_stop_app", "Force-stop an app. Needs shell access (ADB, Shizuku or root).", listOf("package"),
            "package" to prop("string", "Package name")
        ))
        put(tool("android_adb_shell", "Run a shell command on this phone (pm, am, settings, dumpsys, input, cmd, svc, wm…) through the access the user picked in AntiGem: ADB or Shizuku run it as the shell user, Root as root. android_get_status says which one.",
            listOf("command"),
            "command" to prop("string", "Shell command"),
            "timeout_seconds" to prop("integer", "Default 30, max 300")
        ))
        put(tool("android_get_schedule",
            "Read upcoming synced Android calendar events from CalendarContract.Instances (defaults to VISIBLE=1 calendars from today through days_ahead). No Accessibility needed.",
            emptyList(),
            "days_ahead" to prop("integer", "Number of days ahead from today to include (default 7, max 90)"),
            "include_hidden" to prop("boolean", "Include hidden/unsynced-visibility calendars (default false)"),
            "calendar_name" to prop("string", "Filter by calendar_displayName (case-insensitive substring)"),
            "query" to prop("string", "Filter by event title, location, or description (case-insensitive substring)"),
            "limit" to prop("integer", "Maximum events to return (default 50, max 200)")
        ))
        put(tool("android_play_media",
            "Control media playback (play, pause, toggle, next, previous, stop) via AudioManager media keys, or search and play a song/artist in YouTube Music or Spotify.",
            emptyList(),
            "action" to prop("string", "Playback action (default toggle if no query, or search_play if query is given)",
                listOf("toggle", "play_pause", "play", "pause", "next", "previous", "stop", "search_play")),
            "query" to prop("string", "Song, artist, album, or playlist to search and play"),
            "package" to prop("string", "Optional target music package (defaults to YouTube Music Morphe, YouTube Music, or Spotify)")
        ))
        put(tool("android_call_contact",
            "Look up a contact in ContactsContract and place a phone call. SAFETY: When confirm is false/omitted, ONLY returns matching contacts/numbers and asks for confirmation; pass confirm=true ONLY after the user confirms.",
            listOf("query"),
            "query" to prop("string", "Contact name or phone number to look up / call"),
            "number" to prop("string", "Exact phone number to call when confirming a specific number from multiple matches"),
            "confirm" to prop("boolean", "Must be true to actually place the call via ACTION_CALL; false/omitted only looks up and confirms")
        ))
        put(tool("android_call_control",
            "Toggle speakerphone or microphone mute during a call using AudioManager.",
            emptyList(),
            "speakerphone" to prop("boolean", "true to enable speakerphone, false to disable"),
            "mute" to prop("boolean", "true to mute microphone, false to unmute")
        ))
        put(tool("android_set_alarm",
            "Set an alarm using Android AlarmClock.ACTION_SET_ALARM (with EXTRA_SKIP_UI=true by default).",
            listOf("hour", "minute"),
            "hour" to prop("integer", "Hour in 24-hour format (0-23)"),
            "minute" to prop("integer", "Minute (0-59)"),
            "message" to prop("string", "Optional alarm label"),
            "days" to prop("string", "Optional repeat days, comma-separated e.g. 'mon,tue,wed' or '2,3,4' (1=Sun..7=Sat)"),
            "vibrate" to prop("boolean", "Whether alarm should vibrate"),
            "skip_ui" to prop("boolean", "Skip opening the Clock UI (default true)")
        ))
        put(tool("android_set_timer",
            "Start a countdown timer using Android AlarmClock.ACTION_SET_TIMER (with EXTRA_SKIP_UI=true by default).",
            listOf("seconds"),
            "seconds" to prop("integer", "Timer duration in seconds (>= 1)"),
            "message" to prop("string", "Optional timer label"),
            "skip_ui" to prop("boolean", "Skip opening the Clock UI (default true)")
        ))
        put(tool("android_open_camera",
            "Open the camera app directly in photo or video mode.",
            emptyList(),
            "mode" to prop("string", "Camera mode: photo (default) or video", listOf("photo", "video"))
        ))
        put(tool("android_get_notifications",
            "Read active notifications (WhatsApp, Gmail, SMS, etc.) via AntiGem's NotificationListenerService, including message threads and reply keys.",
            emptyList(),
            "package" to prop("string", "Filter by package name substring, e.g. whatsapp or gm"),
            "query" to prop("string", "Filter by app name, title, sender, or message text"),
            "include_ongoing" to prop("boolean", "Include ongoing/persistent notifications (default false)"),
            "limit" to prop("integer", "Maximum notifications to return (default 30, max 100)")
        ))
        put(tool("android_reply_notification",
            "Send an inline reply to an active notification (e.g. WhatsApp/SMS/messaging) using its key from android_get_notifications. Always ask the user before sending.",
            listOf("key", "message"),
            "key" to prop("string", "Notification key from android_get_notifications"),
            "message" to prop("string", "Reply text to send")
        ))
    }

    // ---------------- results ----------------

    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun error(message: String) = text(message).put("isError", true)
    private fun staleElement(id: String) = error("Element $id isn't in the latest UI tree (the screen changed). Call android_get_ui_tree again.")

    // ---------------- calls ----------------

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject {
        return runCatching {
            when (name) {
                "android_get_status" -> status(context)
                "android_get_device_info" -> text(deviceInfo(context, args.optString("sections")).toString(2))
                "android_get_ui_tree" -> withAccessibility(context) { svc ->
                    text(uiTree(svc, args.optString("filter").ifBlank { null }, args.optBoolean("all_windows"), args.optInt("max_elements", 300).coerceIn(10, 2000)))
                }
                "android_screenshot" -> screenshot(context, args.optInt("max_width", 1080).coerceIn(200, 4000), args.optInt("quality", 70).coerceIn(10, 100))
                "android_tap" -> withAccessibility(context) { svc -> tap(svc, args) }
                "android_type_text" -> withAccessibility(context) { svc -> typeText(svc, args) }
                "android_scroll" -> withAccessibility(context) { svc -> scroll(context, svc, args) }
                "android_swipe" -> withAccessibility(context) { svc ->
                    val path = Path().apply {
                        moveTo(args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat())
                        lineTo(args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat())
                    }
                    if (!svc.gesture(path, args.optLong("duration_ms", 300))) return@withAccessibility error("The swipe was cancelled (the screen may be off or locked).")
                    svc.awaitIdle()
                    text("Swiped. ${foreground(svc)}")
                }
                "android_global_action" -> withAccessibility(context) { svc -> globalAction(svc, args.getString("action")) }
                "android_list_apps" -> listApps(context, args.optString("filter"))
                "android_launch_app" -> launchApp(context, args)
                "android_open_intent" -> openIntent(context, args)
                "android_wait_for" -> withAccessibility(context) { svc -> waitFor(svc, args) }
                "android_press_key" -> pressKey(context, args.getString("key"))
                "android_install_apk" -> withShell {
                    val out = DeviceAccess.installApk(args.getString("path")).output.trim()
                    if (out.contains("Success")) text("Installed.\n$out") else error(out.ifBlank { "install failed" })
                }
                "android_uninstall_app" -> uninstall(context, args)
                "android_force_stop_app" -> withShell {
                    val pkg = packageArg(args)
                    DeviceAccess.shell("am force-stop $pkg")
                    text("Force-stopped $pkg.")
                }
                "android_adb_shell" -> withShell {
                    val seconds = args.optInt("timeout_seconds", 30).coerceIn(1, 300)
                    val result = DeviceAccess.shell(args.getString("command"), seconds)
                    val out = result.output.ifBlank { "(no output)" }.let { if (it.length > 60_000) it.take(60_000) + "\n…(truncated)" else it }
                    if (result.exitCode == 0) text(out) else error("Exit code ${result.exitCode}\n$out")
                }
                "android_get_schedule" -> getSchedule(context, args)
                "android_play_media" -> playMedia(context, args)
                "android_call_contact" -> callContact(context, args)
                "android_call_control" -> callControl(context, args)
                "android_set_alarm" -> setAlarm(context, args)
                "android_set_timer" -> setTimer(context, args)
                "android_open_camera" -> openCamera(context, args)
                "android_get_notifications" -> getNotifications(context, args)
                "android_reply_notification" -> replyNotification(context, args)
                else -> error("Unknown tool $name")
            }
        }.getOrElse { error("$name failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Runs [block] when the accessibility service is running; else returns [notReadyMessage]. */
    private suspend fun withAccessibility(context: Context, block: suspend (AutomationAccessibilityService) -> JSONObject): JSONObject {
        notReadyMessage(context)?.let { return error(it) }
        val svc = AutomationAccessibilityService.instance ?: return error("Accessibility service not running")
        return block(svc)
    }

    /** Runs [block] when the picked device access (ADB, Shizuku or root) can run commands now; else says why not. */
    private suspend fun withShell(block: suspend () -> JSONObject): JSONObject {
        DeviceAccess.notReadyMessage()?.let { return error(it) }
        return block()
    }

    private fun packageArg(args: JSONObject): String {
        val pkg = args.getString("package").trim()
        require(Regex("^[A-Za-z0-9_.]+$").matches(pkg)) { "invalid package name \"$pkg\"" }
        return pkg
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun foreground(svc: AutomationAccessibilityService?): String {
        svc ?: return ""
        val pkg = svc.rootInActiveWindow?.packageName ?: svc.foregroundPackage
        return "In front: ${pkg ?: "unknown"}"
    }

    private fun status(context: Context): JSONObject {
        val svc = AutomationAccessibilityService.instance
        val (w, h) = screenSize(context)
        val access = DeviceAccess.describe()
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return text(JSONObject().apply {
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("screen_px", "${w}x$h")
            put("screen_on", power.isInteractive)
            put("locked", keyguard.isKeyguardLocked)
            put("accessibility_service", svc != null)
            put("notification_listener", AutomationNotificationListenerService.instance != null)
            put("foreground_package", svc?.rootInActiveWindow?.packageName ?: svc?.foregroundPackage ?: JSONObject.NULL)
            put("foreground_activity", svc?.foregroundClass ?: JSONObject.NULL)
            put("screenshots", if (canTakeScreenshots()) "yes" else "through ${DeviceAccess.mode.value.label} (Android 10 and older)")
            put("shell_access", access)
        }.toString(2))
    }

    // ---------------- device info ----------------

    private fun deviceInfo(context: Context, sections: String): JSONObject {
        val wanted = sections.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun want(name: String) = wanted.isEmpty() || name in wanted
        val gb = 1024.0 * 1024 * 1024
        fun gib(bytes: Long) = "%.2f GB".format(bytes / gb)
        val info = JSONObject()

        if (want("battery")) info.put("battery", JSONObject().apply {
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            put("level_percent", if (level >= 0) level * 100 / scale.coerceAtLeast(1) else bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("status", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            })
            put("plugged", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "unplugged"
                else -> "other"
            })
            put("health", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                android.os.BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            })
            intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { put("temperature_c", it / 10.0) }
            intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let { put("voltage_mv", it) }
            intent?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY)?.let { put("technology", it) }
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE && it != 0 }?.let { put("current_now_ua", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { put("charge_time_remaining_min", it / 60_000) }
            put("power_save_mode", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
        })

        if (want("device")) info.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            put("android_version", Build.VERSION.RELEASE)
            put("api_level", Build.VERSION.SDK_INT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) put("security_patch", Build.VERSION.SECURITY_PATCH)
            put("build", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
        })

        if (want("memory")) info.put("memory", JSONObject().apply {
            val mi = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            put("ram_total", gib(mi.totalMem))
            put("ram_available", gib(mi.availMem))
            put("ram_used_percent", ((mi.totalMem - mi.availMem) * 100 / mi.totalMem.coerceAtLeast(1)).toInt())
            put("low_memory", mi.lowMemory)
        })

        if (want("storage")) info.put("storage", JSONObject().apply {
            fun stat(dir: java.io.File) = runCatching { android.os.StatFs(dir.path) }.getOrNull()?.let {
                JSONObject().put("total", gib(it.totalBytes)).put("free", gib(it.availableBytes))
                    .put("used_percent", ((it.totalBytes - it.availableBytes) * 100 / it.totalBytes.coerceAtLeast(1)).toInt())
            }
            stat(android.os.Environment.getDataDirectory())?.let { put("internal", it) }
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, dir -> stat(dir)?.let { put("sd_card_${i + 1}", it) } }
        })

        if (want("display")) info.put("display", JSONObject().apply {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            put("resolution_px", "${metrics.widthPixels}x${metrics.heightPixels}")
            put("density_dpi", metrics.densityDpi)
            put("refresh_rate_hz", display.refreshRate.toInt())
            put("rotation_degrees", display.rotation * 90)
            put("font_scale", context.resources.configuration.fontScale)
            put("dark_mode", (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()?.let { put("brightness_0_255", it) }
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrNull()?.let { put("auto_brightness", it) }
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.let { put("screen_timeout_s", it / 1000) }
            put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            put("locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        })

        if (want("network")) info.put("network", JSONObject().apply {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.getNetworkCapabilities(cm.activeNetwork) else null
            put("connected", caps != null)
            if (caps != null) {
                put("type", when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    else -> "other"
                })
                put("vpn", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                put("internet_validated", caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                put("metered", cm.isActiveNetworkMetered)
                put("down_kbps", caps.linkDownstreamBandwidthKbps)
                put("up_kbps", caps.linkUpstreamBandwidthKbps)
            }
            put("ip_addresses", JSONArray(runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { nif -> nif.inetAddresses.toList().filter { !it.isLinkLocalAddress }.map { "${nif.name}: ${it.hostAddress}" } }
            }.getOrDefault(emptyList())))
            put("airplane_mode", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            put("wireless_debugging", Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1)
        })

        if (want("audio")) info.put("audio", JSONObject().apply {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            put("ringer_mode", when (am.ringerMode) {
                android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
                android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            listOf(
                "media" to android.media.AudioManager.STREAM_MUSIC, "ring" to android.media.AudioManager.STREAM_RING,
                "notification" to android.media.AudioManager.STREAM_NOTIFICATION, "alarm" to android.media.AudioManager.STREAM_ALARM,
                "call" to android.media.AudioManager.STREAM_VOICE_CALL
            ).forEach { (label, stream) -> put("${label}_volume", "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}") }
            put("music_playing", am.isMusicActive)
            put("headphones_connected", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                } else am.isWiredHeadsetOn)
        })

        if (want("system")) info.put("system", JSONObject().apply {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            put("do_not_disturb", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL else false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                put("location_enabled", context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled)
            put("developer_options", Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
            put("locale", java.util.Locale.getDefault().toLanguageTag())
            put("time_zone", java.util.TimeZone.getDefault().id)
            put("local_time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
            put("uptime", android.os.SystemClock.elapsedRealtime().let { "%dd %02dh %02dm".format(it / 86_400_000, it / 3_600_000 % 24, it / 60_000 % 60) })
            put("shell_access", DeviceAccess.describe())
        })

        if (info.length() == 0) info.put("error", "No known sections in \"$sections\". Use battery, device, memory, storage, display, network, audio, system.")
        return info
    }

    // ---------------- UI tree ----------------

    private fun uiTree(svc: AutomationAccessibilityService, filter: String?, allWindows: Boolean, max: Int): String {
        elements.clear()
        val roots = if (allWindows) svc.windows.mapNotNull { w -> w.root?.let { "${w.title ?: it.packageName} (${windowType(w.type)})" to it } }
        else listOfNotNull(svc.rootInActiveWindow?.let { "${it.packageName}" to it })
        if (roots.isEmpty()) return "No window content available (the screen may be off, or showing a secure lock screen)."
        val out = StringBuilder()
        var count = 0
        var truncated = false
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (!node.isVisibleToUser) return
            val label = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
            val resId = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isCheckable
            val matches = filter == null || listOf(label, desc, hint, resId).any { it.contains(filter, ignoreCase = true) }
            var childDepth = depth
            if ((actionable || label.isNotEmpty() || desc.isNotEmpty()) && matches) {
                if (count >= max) { truncated = true; return }
                val id = "e${++count}"
                elements[id] = node
                node.getBoundsInScreen(rect)
                out.append("  ".repeat(depth)).append('[').append(id).append("] ").append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (label.isNotEmpty()) out.append(" \"").append(label.take(120).replace('\n', ' ')).append('"')
                if (desc.isNotEmpty() && desc != label) out.append(" desc=\"").append(desc.take(120).replace('\n', ' ')).append('"')
                if (hint.isNotEmpty() && hint != label) out.append(" hint=\"").append(hint.take(60)).append('"')
                if (resId.isNotEmpty()) out.append(" id=").append(resId)
                out.append(" @").append(rect.centerX()).append(',').append(rect.centerY())
                out.append(" [").append(rect.left).append(',').append(rect.top).append(',').append(rect.right).append(',').append(rect.bottom).append(']')
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isLongClickable) add("long-clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isSelected) add("selected")
                    if (node.isFocused) add("focused")
                    if (!node.isEnabled) add("disabled")
                    if (node.isPassword) add("password")
                }
                if (flags.isNotEmpty()) out.append(' ').append(flags.joinToString(","))
                out.append('\n')
                if (filter == null) childDepth = depth + 1
            }
            for (i in 0 until node.childCount) {
                if (truncated) return
                node.getChild(i)?.let { walk(it, childDepth) }
            }
        }

        roots.forEach { (title, root) ->
            out.append("Window: ").append(title).append('\n')
            walk(root, 1)
        }
        if (count == 0) out.append(if (filter != null) "No elements match \"$filter\".\n" else "No readable elements; try android_screenshot.\n")
        if (truncated) out.append("…more elements not shown; use filter or max_elements.\n")
        return out.toString()
    }

    private fun windowType(type: Int) = when (type) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> "other"
    }

    private fun element(args: JSONObject): Pair<String?, AccessibilityNodeInfo?> {
        val id = args.optString("element_id").trim().ifBlank { null } ?: return null to null
        return id to elements[id]?.takeIf { it.refresh() }
    }

    // ---------------- actions ----------------

    private suspend fun tapAt(svc: AutomationAccessibilityService, x: Float, y: Float, long: Boolean): Boolean =
        svc.gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)

    private suspend fun tap(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val long = args.optBoolean("long_press")
        val (id, node) = element(args)
        val what: String
        if (id != null) {
            node ?: return staleElement(id)
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            val target = generateSequence(node) { it.parent }.firstOrNull { if (long) it.isLongClickable else it.isClickable }
            if (target?.performAction(action) != true) {
                // Not clickable through accessibility (custom views, games): touch its center instead
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (!tapAt(svc, r.exactCenterX(), r.exactCenterY(), long)) return error("Couldn't tap $id (the screen may be off or locked).")
            }
            what = id
        } else {
            if (!args.has("x") || !args.has("y")) return error("Give element_id, or x and y.")
            val x = args.getDouble("x").toFloat()
            val y = args.getDouble("y").toFloat()
            if (!tapAt(svc, x, y, long)) return error("The tap was cancelled (the screen may be off or locked).")
            what = "${x.toInt()},${y.toInt()}"
        }
        svc.awaitIdle()
        return text("${if (long) "Long-pressed" else "Tapped"} $what. ${foreground(svc)}")
    }

    private suspend fun typeText(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val (id, node) = element(args)
        if (id != null && node == null) return staleElement(id)
        val target = node ?: svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return error("No text field is focused. Tap one first, or pass its element_id.")
        if (!target.isEditable) return error("${id ?: "The focused element"} is not a text field.")
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.isShowingHintText
        val existing = if (args.optBoolean("append") && !showingHint) target.text?.toString().orEmpty() else ""
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + args.getString("text"))
        })
        if (!ok) return error("The field didn't accept text. Try tapping it first, or android_adb_shell with `input text`.")
        var note = ""
        if (args.optBoolean("submit")) {
            val submitted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                target.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
            note = when {
                submitted -> " Submitted."
                DeviceAccess.shell("input keyevent 66", 15).exitCode == 0 -> " Pressed enter."
                else -> " Couldn't press enter (needs Android 11+ or shell access: ADB, Shizuku or root); tap the send/search button instead."
            }
        }
        svc.awaitIdle()
        return text("Typed.$note ${foreground(svc)}")
    }

    private suspend fun scroll(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val direction = args.getString("direction").lowercase()
        val (id, node) = element(args)
        if (id != null) {
            node ?: return staleElement(id)
            val scrollable = generateSequence(node) { it.parent }.firstOrNull { it.isScrollable }
                ?: return error("$id isn't inside anything scrollable. Omit element_id to swipe instead.")
            val specific = when (direction) {
                "up" -> AccessibilityAction.ACTION_SCROLL_UP
                "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                else -> AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val generic = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val ok = scrollable.performAction(specific.id) || scrollable.performAction(generic)
            svc.awaitIdle()
            return if (ok) text("Scrolled $direction.") else error("It can't scroll $direction any further.")
        }
        val (w, h) = screenSize(context)
        val d = args.optDouble("distance", 0.5).coerceIn(0.1, 0.9).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Swipe opposite to the direction of travel: to see content below, the finger moves up
        val (dx, dy) = when (direction) {
            "down" -> 0f to -h * d / 2
            "up" -> 0f to h * d / 2
            "right" -> -w * d / 2 to 0f
            else -> w * d / 2 to 0f
        }
        val path = Path().apply { moveTo(cx - dx, cy - dy); lineTo(cx + dx, cy + dy) }
        if (!svc.gesture(path, 350)) return error("The swipe was cancelled (the screen may be off or locked).")
        svc.awaitIdle()
        return text("Scrolled $direction.")
    }

    private suspend fun globalAction(svc: AutomationAccessibilityService, action: String): JSONObject {
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else return error("lock_screen needs Android 9+")
            else -> return error("Unknown action $action")
        }
        if (!svc.performGlobalAction(code)) return error("Android refused $action.")
        svc.awaitIdle()
        return text("Done: $action. ${foreground(svc)}")
    }

    private fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private fun listApps(context: Context, filter: String): JSONObject {
        val apps = launchableApps(context).filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }
        if (apps.isEmpty()) return text("No apps match \"$filter\".")
        return text(apps.take(500).joinToString("\n") { "${it.first} — ${it.second}" })
    }

    private fun startActivityBestEffort(context: Context, intent: Intent) {
        val starter: Context = AutomationAccessibilityService.instance ?: context
        starter.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchApp(context: Context, args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        val pkg = args.optString("package").trim()
            .ifBlank { args.optString("package_name").trim() }
            .ifBlank {
                if (name.isBlank()) return error("Give package or name.")
                val apps = launchableApps(context)
                (apps.firstOrNull { it.first.equals(name, true) } ?: apps.firstOrNull { it.first.contains(name, true) })?.second
                    ?: return error("No app called \"$name\". Use android_list_apps to see what's installed.")
            }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return error("$pkg isn't installed or can't be opened.")
        startActivityBestEffort(context, intent)
        return text("Opened $pkg.")
    }

    private fun openIntent(context: Context, args: JSONObject): JSONObject {
        val intent = Intent(args.optString("action").ifBlank { Intent.ACTION_VIEW })
        args.optString("uri").ifBlank { args.optString("url") }.ifBlank { null }?.let { intent.data = Uri.parse(it) }
        args.optString("package").ifBlank { args.optString("package_name") }.ifBlank { null }?.let { intent.setPackage(it) }
        if (intent.action == Intent.ACTION_VIEW && intent.data == null) return error("ACTION_VIEW needs a uri.")
        return try {
            startActivityBestEffort(context, intent)
            text("Started.")
        } catch (e: android.content.ActivityNotFoundException) {
            error("No app can handle that intent.")
        }
    }

    private suspend fun waitFor(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val timeout = args.optLong("timeout_ms", 5_000).coerceIn(100, 30_000)
        val wanted = args.optString("text").ifBlank { null }
        if (wanted == null) {
            svc.awaitIdle(500, timeout)
            return text("The screen is idle. ${foreground(svc)}")
        }
        val gone = args.optBoolean("gone")
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            val found = svc.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
                .any { root -> root.findAccessibilityNodeInfosByText(wanted).any { it.isVisibleToUser } }
            if (found != gone) return text(if (gone) "\"$wanted\" is gone." else "\"$wanted\" is on screen.")
            delay(300)
        }
        return error("Timed out after ${timeout}ms: \"$wanted\" ${if (gone) "is still on screen" else "never appeared"}.")
    }

    private suspend fun pressKey(context: Context, key: String): JSONObject {
        val name = key.trim().uppercase().removePrefix("KEYCODE_")
        if (!Regex("^[A-Z0-9_]+$").matches(name)) return error("Invalid key \"$key\".")
        val notReady = DeviceAccess.notReadyMessage()
        if (notReady != null) {
            val svc = AutomationAccessibilityService.instance
                ?: return error("Pressing $name needs shell access ($notReady) or Accessibility service for BACK/HOME/APP_SWITCH.")
            val global = when (name) {
                "BACK", "4" -> AccessibilityService.GLOBAL_ACTION_BACK
                "HOME", "3" -> AccessibilityService.GLOBAL_ACTION_HOME
                "APP_SWITCH", "187" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> return error("Pressing $name needs shell access. $notReady")
            }
            svc.performGlobalAction(global)
        } else {
            val code = if (name.all(Char::isDigit)) name else "KEYCODE_$name"
            val result = DeviceAccess.shell("input keyevent $code", 15)
            if (result.exitCode != 0) return error(result.output.ifBlank { "input keyevent failed" })
        }
        val svc = AutomationAccessibilityService.instance
        svc?.awaitIdle()
        return text("Pressed $name. ${foreground(svc)}".trim())
    }

    private suspend fun uninstall(context: Context, args: JSONObject): JSONObject {
        val pkg = packageArg(args)
        if (pkg == context.packageName) return error("Refusing to uninstall AntiGem itself.")
        if (DeviceAccess.notReadyMessage() == null) {
            val keep = if (args.optBoolean("keep_data")) "-k " else ""
            val out = DeviceAccess.shell("pm uninstall $keep$pkg", 60).output.trim()
            return if (out.contains("Success")) text("Uninstalled $pkg.") else error(out.ifBlank { "pm uninstall failed" })
        }
        @Suppress("DEPRECATION")
        startActivityBestEffort(context, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        AutomationAccessibilityService.instance?.awaitIdle(500, 3_000)
        return text("There's no shell access (ADB, Shizuku or root), so Android's uninstall dialog is open for $pkg. Read the UI tree and tap OK/Uninstall to confirm.")
    }

    // ---------------- screenshot ----------------

    private suspend fun screenshot(context: Context, maxWidth: Int, quality: Int): JSONObject {
        val svc = AutomationAccessibilityService.instance
        val bitmap: Bitmap = if (canTakeScreenshots() && svc != null) {
            svc.screenshot().getOrElse { return error("Screenshot failed: ${it.message}") }
        } else if (DeviceAccess.notReadyMessage() == null) {
            val bytes = DeviceAccess.screencapPng()
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return error("screencap failed")
        } else if (canTakeScreenshots()) {
            return error(notReadyMessage(context) ?: "Accessibility service not running")
        } else {
            return error("Screenshots need Android 11+ (Accessibility) or shell access. ${DeviceAccess.notReadyMessage()}")
        }
        val (screenW, screenH) = screenSize(context)
        val scaled = if (bitmap.width > maxWidth) Bitmap.createScaledBitmap(bitmap, maxWidth, bitmap.height * maxWidth / bitmap.width, true) else bitmap
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        val factor = screenW.toFloat() / scaled.width
        val note = if (factor in 0.99f..1.01f) "Screenshot ${scaled.width}x${scaled.height}; image pixels are screen pixels."
            else "Screenshot ${scaled.width}x${scaled.height} of a ${screenW}x$screenH screen: multiply image coordinates by ${"%.3f".format(factor)} for android_tap/android_swipe."
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))
            .put(JSONObject().put("type", "text").put("text", "$note ${foreground(svc)}".trim())))
    }

    // ---------------- 1-shot assistant tools ----------------

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun getSchedule(context: Context, args: JSONObject): JSONObject {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            return error("READ_CALENDAR permission is not granted. Ask the user to allow Calendar access for AntiGem in App Settings (or `pm grant ${context.packageName} android.permission.READ_CALENDAR`).")
        }
        val daysAhead = args.optInt("days_ahead", 7).coerceIn(1, 90)
        val includeHidden = args.optBoolean("include_hidden", false)
        val calendarFilter = args.optString("calendar_name").trim().ifBlank { null }
        val query = args.optString("query").trim().ifBlank { null }
        val limit = args.optInt("limit", 50).coerceIn(1, 200)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis
        val endMs = startMs + daysAhead * 86_400_000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMs)
            ContentUris.appendId(this, endMs)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION
        )
        val selection = if (includeHidden) null else "${CalendarContract.Instances.VISIBLE} = 1"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val events = JSONArray()

        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val idxId = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val idxTitle = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val idxBegin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val idxEnd = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val idxAllDay = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val idxLoc = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val idxCalName = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            val idxDesc = c.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)

            while (c.moveToNext() && events.length() < limit) {
                val calName = c.getString(idxCalName).orEmpty()
                val title = c.getString(idxTitle).orEmpty()
                val loc = c.getString(idxLoc).orEmpty()
                val desc = c.getString(idxDesc).orEmpty()
                if (calendarFilter != null && !calName.contains(calendarFilter, ignoreCase = true)) continue
                if (query != null && !listOf(title, loc, desc).any { it.contains(query, ignoreCase = true) }) continue
                val begin = c.getLong(idxBegin)
                val end = c.getLong(idxEnd)
                events.put(JSONObject().apply {
                    put("event_id", c.getLong(idxId))
                    put("calendar_displayName", calName)
                    put("title", title.ifBlank { "(No title)" })
                    put("start", fmt.format(Date(begin)))
                    put("end", fmt.format(Date(end)))
                    put("start_ms", begin)
                    put("end_ms", end)
                    put("all_day", c.getInt(idxAllDay) != 0)
                    if (loc.isNotBlank()) put("location", loc)
                    if (desc.isNotBlank()) put("description", desc.take(300))
                })
            }
        }
        return text(JSONObject().apply {
            put("days_ahead", daysAhead)
            put("visible_only", !includeHidden)
            put("count", events.length())
            put("events", events)
        }.toString(2))
    }

    private suspend fun resolveYouTubeVideoId(query: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val url = java.net.URL("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                }
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""").find(html)?.groupValues?.getOrNull(1)
            }.getOrNull()
        }

    private suspend fun awaitPlaybackActive(context: Context, timeoutMs: Long = 1800L): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (am.isMusicActive) return true
            delay(300)
        }
        return am.isMusicActive
    }

    private suspend fun playMedia(context: Context, args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        val rawAction = args.optString("action").trim().lowercase()
        val action = rawAction.ifBlank { if (query.isNotEmpty()) "search_play" else "toggle" }

        if (action == "search_play" || (query.isNotEmpty() && rawAction.isBlank())) {
            if (query.isEmpty()) return error("search_play requires a non-empty query.")
            val pm = context.packageManager
            val preferred = listOfNotNull(
                args.optString("package").trim().ifBlank { args.optString("package_name").trim() }.ifBlank { null },
                "app.morphe.android.apps.youtube.music",
                "com.google.android.apps.youtube.music",
                "com.spotify.music"
            ).distinct()
            val installedTarget = preferred.firstOrNull { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            }
            if (installedTarget != null) {
                if (installedTarget.contains("youtube.music")) {
                    val videoId = resolveYouTubeVideoId(query)
                    if (videoId != null) {
                        val watchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$videoId"))
                            .setPackage(installedTarget)
                        if (pm.resolveActivity(watchIntent, 0) != null) {
                            startActivityBestEffort(context, watchIntent)
                            val active = awaitPlaybackActive(context)
                            return if (active) {
                                text("Playing \"$query\" ($videoId) in $installedTarget (verified audio active).")
                            } else {
                                text("Opened \"$query\" ($videoId) in $installedTarget, but audio playback did not auto-start. Use android_get_ui_tree to tap Play if needed.")
                            }
                        }
                    }
                }
                val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    setPackage(installedTarget)
                }
                if (pm.resolveActivity(playIntent, 0) != null) {
                    startActivityBestEffort(context, playIntent)
                    val active = awaitPlaybackActive(context)
                    return if (active) {
                        text("Playing \"$query\" in $installedTarget (verified audio active).")
                    } else {
                        text("Opened $installedTarget for \"$query\", but audio playback did not auto-start. Use android_get_ui_tree to search and tap Play.")
                    }
                }
                val fallbackUri = when {
                    installedTarget.contains("youtube.music") ->
                        "https://music.youtube.com/search?q=${Uri.encode(query)}"
                    installedTarget == "com.spotify.music" ->
                        "spotify:search:${Uri.encode(query)}"
                    else -> null
                }
                if (fallbackUri != null) {
                    val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)).setPackage(installedTarget)
                    if (pm.resolveActivity(uriIntent, 0) != null) {
                        startActivityBestEffort(context, uriIntent)
                        return text("Opened search for \"$query\" in $installedTarget (tap a track to play).")
                    }
                }
            }
            val generic = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            return try {
                startActivityBestEffort(context, generic)
                val active = awaitPlaybackActive(context)
                if (active) text("Playing \"$query\" (verified audio active).")
                else text("Dispatched media search for \"$query\", but audio playback did not auto-start.")
            } catch (_: android.content.ActivityNotFoundException) {
                error("No media app found to handle \"$query\".")
            }
        }

        val keyCode = when (action) {
            "toggle", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return error("Unknown media action \"$action\".")
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return text("Dispatched media key: $action.")
    }

    private fun callContact(context: Context, args: JSONObject): JSONObject {
        val query = args.getString("query").trim()
        val explicitNumber = args.optString("number").trim().ifBlank { null }
        if (query.isBlank() && explicitNumber == null) return error("query must not be empty.")
        val confirm = args.optBoolean("confirm", false)
        val looksLikeNumber = Regex("^[+0-9()\\s-]{3,}$").matches(query)

        val matches = mutableListOf<Triple<String, String, String>>()
        if (hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val like = "%$query%"
            context.contentResolver.query(uri, projection, selection, arrayOf(like, like), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c ->
                val idxName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxNum = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxType = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val idxLabel = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                val seen = HashSet<String>()
                while (c.moveToNext() && matches.size < 15) {
                    val name = c.getString(idxName).orEmpty().trim()
                    val num = c.getString(idxNum).orEmpty().trim()
                    if (num.isEmpty()) continue
                    val normalized = num.replace(Regex("[\\s()-]"), "")
                    if (!seen.add("$name|$normalized")) continue
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources, c.getInt(idxType), c.getString(idxLabel)
                    ).toString()
                    matches += Triple(name, num, typeLabel)
                }
            }
        } else if (!looksLikeNumber && explicitNumber == null) {
            return error("READ_CONTACTS permission is not granted. Ask the user to allow Contacts access for AntiGem (or pass a phone number directly).")
        }

        if (!confirm) {
            if (matches.isEmpty() && !looksLikeNumber && explicitNumber == null) {
                return text("No contacts found matching \"$query\".")
            }
            return text(JSONObject().apply {
                put("status", "confirmation_required")
                put("message", "Confirm recipient with the user, then call android_call_contact again with confirm=true (and number if multiple matches).")
                put("query", query)
                if (explicitNumber != null) put("number", explicitNumber)
                put("matches", JSONArray().apply {
                    matches.forEach { (n, num, t) ->
                        put(JSONObject().put("name", n).put("number", num).put("type", t))
                    }
                })
            }.toString(2))
        }

        val dialNumber = explicitNumber ?: when {
            looksLikeNumber -> query
            matches.size == 1 -> matches[0].second
            matches.isEmpty() -> return error("No contact or phone number found for \"$query\".")
            else -> {
                val distinctNums = matches.map { it.second.replace(Regex("[\\s()-]"), "") }.distinct()
                if (distinctNums.size == 1) matches[0].second
                else return text(JSONObject().apply {
                    put("status", "ambiguous_matches")
                    put("message", "Multiple numbers matched \"$query\". Ask the user which number to call and pass it in `number` with confirm=true.")
                    put("matches", JSONArray().apply {
                        matches.forEach { (n, num, t) ->
                            put(JSONObject().put("name", n).put("number", num).put("type", t))
                        }
                    })
                }.toString(2))
            }
        }

        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return error("CALL_PHONE permission is not granted. Ask the user to allow Phone access for AntiGem (or `pm grant ${context.packageName} android.permission.CALL_PHONE`).")
        }
        startActivityBestEffort(context, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", dialNumber, null)))
        val label = matches.firstOrNull { it.second == dialNumber }?.first ?: dialNumber
        return text("Calling $label ($dialNumber).")
    }

    private fun callControl(context: Context, args: JSONObject): JSONObject {
        val am = context.getSystemService(AudioManager::class.java)
        if (!args.has("speakerphone") && !args.has("mute")) {
            return error("Provide speakerphone (boolean) and/or mute (boolean).")
        }
        if (args.has("speakerphone")) {
            val on = args.getBoolean("speakerphone")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                }
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
        if (args.has("mute")) {
            am.isMicrophoneMute = args.getBoolean("mute")
        }
        val speakerState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || @Suppress("DEPRECATION") am.isSpeakerphoneOn
        } else {
            @Suppress("DEPRECATION") am.isSpeakerphoneOn
        }
        return text(JSONObject().apply {
            put("speakerphone", speakerState)
            put("microphone_muted", am.isMicrophoneMute)
        }.toString(2))
    }

    private fun setAlarm(context: Context, args: JSONObject): JSONObject {
        val hour = args.getInt("hour").coerceIn(0, 23)
        val minute = args.getInt("minute").coerceIn(0, 59)
        val message = args.optString("message").trim()
        val skipUi = args.optBoolean("skip_ui", true)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (message.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            if (args.has("vibrate")) putExtra(AlarmClock.EXTRA_VIBRATE, args.getBoolean("vibrate"))
            val rawDays = args.optString("days").trim()
            if (rawDays.isNotEmpty()) {
                val dayMap = mapOf(
                    "sun" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY, "1" to Calendar.SUNDAY,
                    "mon" to Calendar.MONDAY, "monday" to Calendar.MONDAY, "2" to Calendar.MONDAY,
                    "tue" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY, "3" to Calendar.TUESDAY,
                    "wed" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY, "4" to Calendar.WEDNESDAY,
                    "thu" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY, "5" to Calendar.THURSDAY,
                    "fri" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY, "6" to Calendar.FRIDAY,
                    "sat" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY, "7" to Calendar.SATURDAY
                )
                val parsed = ArrayList(rawDays.split(',').mapNotNull { dayMap[it.trim().lowercase()] }.distinct())
                if (parsed.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, parsed)
            }
        }
        return try {
            startActivityBestEffort(context, intent)
            text("Alarm set for %02d:%02d%s.".format(hour, minute, if (message.isNotEmpty()) " ($message)" else ""))
        } catch (_: android.content.ActivityNotFoundException) {
            error("No Clock app available to handle ACTION_SET_ALARM.")
        }
    }

    private fun setTimer(context: Context, args: JSONObject): JSONObject {
        val seconds = args.getInt("seconds")
        if (seconds < 1) return error("seconds must be >= 1.")
        val message = args.optString("message").trim()
        val skipUi = args.optBoolean("skip_ui", true)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (message.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
        }
        return try {
            startActivityBestEffort(context, intent)
            text("Timer started for ${seconds}s${if (message.isNotEmpty()) " ($message)" else ""}.")
        } catch (_: android.content.ActivityNotFoundException) {
            error("No Clock app available to handle ACTION_SET_TIMER.")
        }
    }

    private fun openCamera(context: Context, args: JSONObject): JSONObject {
        val mode = args.optString("mode", "photo").trim().lowercase()
        val action = if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return try {
            startActivityBestEffort(context, Intent(action))
            text("Opened camera ($mode).")
        } catch (_: android.content.ActivityNotFoundException) {
            error("No camera app found.")
        }
    }

    private fun getNotifications(context: Context, args: JSONObject): JSONObject {
        AutomationNotificationListenerService.notReadyMessage(context)?.let { return error(it) }
        val svc = AutomationNotificationListenerService.instance
            ?: return error("Notification listener not bound.")
        val list = svc.listNotifications(
            packageFilter = args.optString("package").trim().ifBlank { null },
            query = args.optString("query").trim().ifBlank { null },
            includeOngoing = args.optBoolean("include_ongoing", false),
            limit = args.optInt("limit", 30).coerceIn(1, 100)
        )
        return text(JSONObject().apply {
            put("count", list.length())
            put("notifications", list)
        }.toString(2))
    }

    private fun replyNotification(context: Context, args: JSONObject): JSONObject {
        AutomationNotificationListenerService.notReadyMessage(context)?.let { return error(it) }
        val svc = AutomationNotificationListenerService.instance
            ?: return error("Notification listener not bound.")
        val key = args.getString("key").trim()
        val message = args.getString("message")
        return svc.replyToNotification(key, message).fold(
            onSuccess = { text(it) },
            onFailure = { error(it.message ?: "Failed to reply") }
        )
    }
}
