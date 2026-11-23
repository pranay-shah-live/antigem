package ai.antigem.ui.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.activity.ViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import android.view.ContextThemeWrapper
import androidx.compose.ui.unit.dp
import ai.antigem.MainActivity
import ai.antigem.theme.GeminiTheme
import ai.antigem.theme.isSystemInDarkThemeRobust
import ai.antigem.ui.chat.ChatViewModel
import ai.antigem.ui.chat.ChatViewModelHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Owns the floating chat UI independently of the Activity stack, so outside touches reach the app below it. */
class FloatingChatOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner, ActivityResultRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore by lazy { ViewModelStore() }
    override val onBackPressedDispatcher: OnBackPressedDispatcher by lazy {
        OnBackPressedDispatcher { minimizeWindow() }
    }
    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
            input: I,
            options: androidx.core.app.ActivityOptionsCompat?
        ) {
            val target = contract.createIntent(this@FloatingChatOverlayService, input)
            val relay = Intent(this@FloatingChatOverlayService, FloatingActivityResultRelayActivity::class.java)
                .putExtra(FloatingActivityResultRelayActivity.EXTRA_TARGET, target)
                .putExtra(FloatingActivityResultRelayActivity.EXTRA_REQUEST_CODE, requestCode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            target.getStringArrayExtra(EXTRA_PERMISSIONS)?.let {
                if (target.action == ACTION_REQUEST_PERMISSIONS) relay.putExtra(FloatingActivityResultRelayActivity.EXTRA_PERMISSIONS, it)
            }
            startActivity(relay)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var composeView: ComposeView
    private lateinit var chatViewModel: ChatViewModel
    private val minimized = mutableStateOf(false)
    private var expandedBounds: IntArray? = null
    private var preMaximizeBounds: IntArray? = null
    private val density get() = resources.displayMetrics.density
    private val displayWidth get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ::windowManager.isInitialized) {
        windowManager.currentWindowMetrics.bounds.width()
    } else {
        resources.displayMetrics.widthPixels
    }
    private val displayHeight get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ::windowManager.isInitialized) {
        windowManager.currentWindowMetrics.bounds.height()
    } else {
        resources.displayMetrics.heightPixels
    }
    private val prefs by lazy { getSharedPreferences("floating_window", Context.MODE_PRIVATE) }

    override fun onCreate() {
        savedStateController.performAttach()
        super.onCreate()
        savedStateController.performRestore(null)
        FloatingActivityResultBridge.dispatch = { requestCode, resultCode, data ->
            activityResultRegistry.dispatchResult(requestCode, resultCode, data)
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        chatViewModel = ChatViewModelHolder.get(application)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CLOSE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!::composeView.isInitialized) attachOverlay()
        return START_NOT_STICKY
    }

    private fun attachOverlay() {
        val maxWidth = (displayWidth * 0.96f).toInt()
        val maxHeight = (displayHeight * 0.88f).toInt()
        val width = prefs.getInt(KEY_WIDTH, min(dp(400), maxWidth)).coerceIn(min(dp(280), maxWidth), maxWidth)
        val height = prefs.getInt(KEY_HEIGHT, min(dp(650), maxHeight)).coerceIn(min(dp(260), maxHeight), maxHeight)
        windowParams = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (displayWidth - width) / 2).coerceIn(0, max(0, displayWidth - width))
            y = prefs.getInt(KEY_Y, dp(72)).coerceIn(0, max(0, displayHeight - height))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        composeView = ComposeView(FloatingWindowContext(this, ai.antigem.R.style.Theme_Gemini)).apply {
            ViewTreeLifecycleOwner.set(this, this@FloatingChatOverlayService)
            ViewTreeSavedStateRegistryOwner.set(this, this@FloatingChatOverlayService)
            ViewTreeViewModelStoreOwner.set(this, this@FloatingChatOverlayService)
            ViewTreeOnBackPressedDispatcherOwner.set(this, this@FloatingChatOverlayService)
            setContent {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides this@FloatingChatOverlayService,
                    LocalOnBackPressedDispatcherOwner provides this@FloatingChatOverlayService
                ) {
                    val themeMode by chatViewModel.themeMode.collectAsState(initial = chatViewModel.authPreferences.getThemeModeSync())
                    val systemDark = isSystemInDarkThemeRobust()
                    GeminiTheme(darkTheme = when (themeMode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                        if (minimized.value) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                FloatingBubbleContent(
                                    isVisible = true,
                                    onClick = { restoreWindow() },
                                    onDrag = { dx, dy -> moveWindow(dx, dy) },
                                    onSnap = { snapBubbleToEdge() },
                                    onDismissToDropZone = { stopSelf() }
                                )
                            }
                        } else {
                            FloatingChatWindow(
                                viewModel = chatViewModel,
                                onOpenFullApp = { openFullApp() },
                                onMinimize = { minimizeWindow() },
                                onClose = { stopSelf() },
                                onMove = { dx, dy -> moveWindow(dx, dy) },
                                onResize = { handle, dx, dy, lock -> resizeWindow(handle, dx, dy, lock) },
                                onToggleMaximize = { toggleMaximize() }
                            )
                        }
                    }
                }
            }
        }
        windowManager.addView(composeView, windowParams)
    }

    private fun moveWindow(dx: Float, dy: Float) {
        if (!::windowParams.isInitialized) return
        preMaximizeBounds = null
        var targetX = windowParams.x + dx.toInt()
        var targetY = windowParams.y + dy.toInt()
        val snapThreshold = dp(14)
        if (targetX <= snapThreshold) targetX = 0
        else if (targetX >= displayWidth - windowParams.width - snapThreshold) targetX = displayWidth - windowParams.width
        if (targetY <= snapThreshold) targetY = 0
        windowParams.x = targetX.coerceIn(0, max(0, displayWidth - windowParams.width))
        windowParams.y = targetY.coerceIn(0, max(0, displayHeight - windowParams.height))
        updateWindow()
    }

    private fun toggleMaximize() {
        if (!::windowParams.isInitialized || minimized.value) return
        val maxWidth = (displayWidth * 0.98f).toInt()
        val maxHeight = (displayHeight * 0.94f).toInt()
        if (preMaximizeBounds == null) {
            preMaximizeBounds = intArrayOf(windowParams.width, windowParams.height, windowParams.x, windowParams.y)
            windowParams.width = maxWidth
            windowParams.height = maxHeight
            windowParams.x = (displayWidth - maxWidth) / 2
            windowParams.y = dp(24)
        } else {
            val b = preMaximizeBounds ?: return
            preMaximizeBounds = null
            windowParams.width = b[0].coerceIn(min(dp(280), maxWidth), maxWidth)
            windowParams.height = b[1].coerceIn(min(dp(260), maxHeight), maxHeight)
            windowParams.x = b[2].coerceIn(0, max(0, displayWidth - windowParams.width))
            windowParams.y = b[3].coerceIn(0, max(0, displayHeight - windowParams.height))
        }
        updateWindow()
    }

    private fun resizeWindow(handle: FloatingResizeHandle, dx: Float, dy: Float, lockRatio: Boolean) {
        if (!::windowParams.isInitialized) return
        val oldWidth = windowParams.width
        val oldHeight = windowParams.height
        val oldX = windowParams.x
        val oldY = windowParams.y
        var width = oldWidth + when { handle.east -> dx.toInt(); handle.west -> -dx.toInt(); else -> 0 }
        var height = oldHeight + when { handle.south -> dy.toInt(); handle.north -> -dy.toInt(); else -> 0 }
        if (lockRatio && (handle.north || handle.south) && (handle.east || handle.west)) {
            val ratio = oldWidth.toFloat() / oldHeight.coerceAtLeast(1)
            // Use relative (normalised) deltas to pick the dominant drag axis.
            val relX = if (oldWidth > 0) kotlin.math.abs(dx) / oldWidth else 0f
            val relY = if (oldHeight > 0) kotlin.math.abs(dy) / oldHeight else 0f
            if (relX >= relY) height = (width / ratio).toInt()
            else width = (height * ratio).toInt()
        }
        val maxWidth = (displayWidth * 0.98f).toInt()
        val maxHeight = (displayHeight * 0.92f).toInt()
        width = width.coerceIn(min(dp(280), maxWidth), maxWidth)
        height = height.coerceIn(min(dp(260), maxHeight), maxHeight)
        windowParams.width = width
        windowParams.height = height
        windowParams.x = if (handle.west) oldX + oldWidth - width else oldX
        windowParams.y = if (handle.north) oldY + oldHeight - height else oldY
        windowParams.x = windowParams.x.coerceIn(0, max(0, displayWidth - width))
        windowParams.y = windowParams.y.coerceIn(0, max(0, displayHeight - height))
        updateWindow()
    }

    private fun minimizeWindow() {
        if (!::windowParams.isInitialized || minimized.value) return
        expandedBounds = intArrayOf(windowParams.width, windowParams.height, windowParams.x, windowParams.y)
        windowParams.width = dp(66)
        windowParams.height = dp(66)
        windowParams.x = if (windowParams.x + windowParams.width / 2 < displayWidth / 2) 0 else displayWidth - windowParams.width
        windowParams.y = windowParams.y.coerceIn(0, max(0, displayHeight - windowParams.height))
        minimized.value = true
        updateWindow()
    }

    private fun restoreWindow() {
        val bounds = expandedBounds
        if (bounds != null) {
            windowParams.width = bounds[0].coerceIn(min(dp(280), displayWidth), displayWidth)
            windowParams.height = bounds[1].coerceIn(min(dp(260), displayHeight), displayHeight)
            windowParams.x = bounds[2].coerceIn(0, max(0, displayWidth - windowParams.width))
            windowParams.y = bounds[3].coerceIn(0, max(0, displayHeight - windowParams.height))
        }
        minimized.value = false
        updateWindow()
    }

    private fun snapBubbleToEdge() {
        if (!minimized.value) return
        windowParams.x = if (windowParams.x + windowParams.width / 2 < displayWidth / 2) 0
        else max(0, displayWidth - windowParams.width)
        windowParams.y = windowParams.y.coerceIn(0, max(0, displayHeight - windowParams.height))
        updateWindow()
    }

    private fun updateWindow() {
        runCatching { windowManager.updateViewLayout(composeView, windowParams) }
        if (!minimized.value) prefs.edit()
            .putInt(KEY_WIDTH, windowParams.width).putInt(KEY_HEIGHT, windowParams.height)
            .putInt(KEY_X, windowParams.x).putInt(KEY_Y, windowParams.y).apply()
        else prefs.edit().putInt(KEY_X, windowParams.x).putInt(KEY_Y, windowParams.y).apply()
    }

    private fun openFullApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        stopSelf()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating AntiGem Fork window", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the floating AntiGem Fork window available over other apps"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = android.app.PendingIntent.getActivity(this, 71, openIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val closePending = android.app.PendingIntent.getService(this, 72, Intent(this, FloatingChatOverlayService::class.java).setAction(ACTION_CLOSE), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AntiGem Fork floating window")
            .setContentText("Window is available over other apps")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePending)
            .build()
    }

    override fun onDestroy() {
        if (::composeView.isInitialized) runCatching { windowManager.removeView(composeView) }
        FloatingActivityResultBridge.dispatch = null
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    companion object {
        private const val CHANNEL_ID = "antigem_fork_floating_window"
        private const val NOTIFICATION_ID = 7304
        private const val ACTION_CLOSE = "ai.antigem.action.CLOSE_FLOATING_WINDOW"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}

/** Service-hosted Compose has no Activity context; external intents must be launched in a new task. */
private class FloatingWindowContext(base: Context, themeResId: Int) : ContextThemeWrapper(base, themeResId) {
    override fun startActivity(intent: Intent) {
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
    }
}

/** Runs ActivityResultContracts from the service hosted Compose tree and returns their result to its registry. */
class FloatingActivityResultRelayActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (!permissions.isNullOrEmpty()) {
            requestPermissions(permissions, TARGET_REQUEST)
            return
        }
        val target = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_TARGET)
        }
        if (target == null) {
            finish()
            return
        }
        startActivityForResult(target, TARGET_REQUEST)
    }

    @Deprecated("Activity result contract relay")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TARGET_REQUEST) {
            val ownerRequestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
            if (ownerRequestCode >= 0) FloatingActivityResultBridge.dispatch?.invoke(ownerRequestCode, resultCode, data)
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TARGET_REQUEST) {
            val result = Intent()
                .putExtra(EXTRA_PERMISSIONS, permissions)
                .putExtra(EXTRA_PERMISSION_GRANT_RESULTS, grantResults)
            val ownerRequestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
            if (ownerRequestCode >= 0) FloatingActivityResultBridge.dispatch?.invoke(ownerRequestCode, Activity.RESULT_OK, result)
            finish()
        }
    }

    companion object {
        const val EXTRA_TARGET = "target"
        const val EXTRA_REQUEST_CODE = "registry_request_code"
        const val EXTRA_PERMISSIONS = "androidx.activity.result.contract.extra.PERMISSIONS"
        private const val EXTRA_PERMISSION_GRANT_RESULTS = "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS"
        private const val ACTION_REQUEST_PERMISSIONS = "androidx.activity.result.contract.action.REQUEST_PERMISSIONS"
        private const val TARGET_REQUEST = 1
    }
}

private object FloatingActivityResultBridge {
    @Volatile var dispatch: ((Int, Int, Intent?) -> Unit)? = null
}
