package ai.antigem.ui.bubble

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ai.antigem.AppViewMode
import ai.antigem.ui.browser.BrowserScreen
import ai.antigem.ui.chat.ChatScreen
import ai.antigem.ui.chat.ChatViewModel
import ai.antigem.ui.components.LocalTerminalContent
import ai.antigem.ui.ide.IdeScreen

/** Permission entry point for the persistent WindowManager overlay. */
class FloatingChatActivity : ComponentActivity() {
    private var overlayStarted = false
    private var permissionScreenOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openOverlayOrPermission()
    }

    override fun onResume() {
        super.onResume()
        if (overlayStarted) return
        if (Settings.canDrawOverlays(this)) startOverlay()
        else if (permissionScreenOpened) showPermissionPrompt()
    }

    private fun openOverlayOrPermission() {
        if (Settings.canDrawOverlays(this)) {
            startOverlay()
            return
        }
        permissionScreenOpened = true
        showPermissionPrompt()
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun showPermissionPrompt() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
            addView(TextView(context).apply {
                text = "Allow AntiGem Fork to display over other apps to use the floating window."
                textSize = 18f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = "Grant overlay permission"
                setOnClickListener {
                    permissionScreenOpened = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            })
            addView(Button(context).apply { text = "Cancel"; setOnClickListener { finish() } })
        }
        setContentView(content)
    }

    private fun startOverlay() {
        if (overlayStarted) return
        overlayStarted = true
        androidx.core.content.ContextCompat.startForegroundService(
            this,
            Intent(this, FloatingChatOverlayService::class.java)
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

enum class FloatingResizeHandle(val west: Boolean = false, val east: Boolean = false, val north: Boolean = false, val south: Boolean = false) {
    LEFT(west = true), RIGHT(east = true), TOP(north = true), BOTTOM(south = true),
    TOP_LEFT(west = true, north = true), TOP_RIGHT(east = true, north = true),
    BOTTOM_LEFT(west = true, south = true), BOTTOM_RIGHT(east = true, south = true)
}

@Composable
fun FloatingChatWindow(
    viewModel: ChatViewModel,
    onOpenFullApp: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (FloatingResizeHandle, Float, Float, Boolean) -> Unit,
    onToggleMaximize: () -> Unit = {}
) {
    var currentViewMode by rememberSaveable { mutableStateOf(AppViewMode.CHAT) }
    var hasEverOpenedTerminal by rememberSaveable { mutableStateOf(false) }
    var aspectRatioLocked by rememberSaveable { mutableStateOf(false) }
    if (currentViewMode == AppViewMode.TERMINAL) hasEverOpenedTerminal = true

    BackHandler(enabled = currentViewMode == AppViewMode.IDE || currentViewMode == AppViewMode.TERMINAL) {
        currentViewMode = AppViewMode.CHAT
    }
    BackHandler(enabled = currentViewMode == AppViewMode.CHAT) { onMinimize() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().height(42.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AntiGem Fork",
                    modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                        detectDragGestures { change, amount -> change.consume(); onMove(amount.x, amount.y) }
                    }.padding(start = 14.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { aspectRatioLocked = !aspectRatioLocked }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (aspectRatioLocked) "Ratio on" else "Ratio off", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onToggleMaximize, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Max") }
                TextButton(onClick = onOpenFullApp, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Open") }
                TextButton(onClick = onMinimize, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Min") }
                TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("×") }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val visible = currentViewMode == AppViewMode.CHAT
                    alpha = if (visible) 1f else 0f
                    translationX = if (visible) 0f else -20000f
                }) {
                    ChatScreen(
                        viewModel = viewModel,
                        isInFloatingWindow = true,
                        onOpenFullScreen = onOpenFullApp,
                        onMinimizeWindow = onMinimize,
                        onNavigateToIde = { currentViewMode = AppViewMode.IDE },
                        onNavigateToTerminal = { currentViewMode = AppViewMode.TERMINAL },
                        onNavigateToBrowser = { currentViewMode = AppViewMode.BROWSER }
                    )
                }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val visible = currentViewMode == AppViewMode.IDE
                    alpha = if (visible) 1f else 0f
                    translationX = if (visible) 0f else 20000f
                }) {
                    IdeScreen(
                        viewModel = viewModel,
                        isVisible = currentViewMode == AppViewMode.IDE,
                        onNavigateToChat = { currentViewMode = AppViewMode.CHAT },
                        onExecuteRunCommand = {},
                        onNavigateToTerminal = { currentViewMode = AppViewMode.TERMINAL },
                        onNavigateToBrowser = { currentViewMode = AppViewMode.BROWSER }
                    )
                }
                val terminalSessions by ai.antigem.data.local.LocalTerminalManager.sessions.collectAsState()
                if (currentViewMode == AppViewMode.TERMINAL || (hasEverOpenedTerminal && terminalSessions.isNotEmpty())) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val visible = currentViewMode == AppViewMode.TERMINAL
                        alpha = if (visible) 1f else 0f
                        translationX = if (visible) 0f else 20000f
                    }) {
                        LocalTerminalContent(onClose = { currentViewMode = AppViewMode.CHAT })
                    }
                }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val visible = currentViewMode == AppViewMode.BROWSER
                    alpha = if (visible) 1f else 0f
                    translationX = if (visible) 0f else 20000f
                }) {
                    BrowserScreen(isVisible = currentViewMode == AppViewMode.BROWSER, onClose = { currentViewMode = AppViewMode.CHAT })
                }
            }

        }
        // All eight resize handles sit in the outer Box so they are never clipped by the rounded Column.
        ResizeGrip(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(10.dp), FloatingResizeHandle.LEFT, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp), FloatingResizeHandle.RIGHT, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(10.dp), FloatingResizeHandle.TOP, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(10.dp), FloatingResizeHandle.BOTTOM, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.TopStart).size(22.dp), FloatingResizeHandle.TOP_LEFT, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.TopEnd).size(22.dp), FloatingResizeHandle.TOP_RIGHT, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.BottomStart).size(22.dp), FloatingResizeHandle.BOTTOM_LEFT, aspectRatioLocked, onResize)
        ResizeGrip(Modifier.align(Alignment.BottomEnd).size(22.dp), FloatingResizeHandle.BOTTOM_RIGHT, aspectRatioLocked, onResize)
    }
}

@Composable
private fun ResizeGrip(
    modifier: Modifier,
    handle: FloatingResizeHandle,
    aspectRatioLocked: Boolean,
    onResize: (FloatingResizeHandle, Float, Float, Boolean) -> Unit
) {
    Box(modifier.pointerInput(handle, aspectRatioLocked) {
        detectDragGestures { change, amount ->
            change.consume()
            onResize(handle, amount.x, amount.y, aspectRatioLocked && handle in setOf(
                FloatingResizeHandle.TOP_LEFT, FloatingResizeHandle.TOP_RIGHT,
                FloatingResizeHandle.BOTTOM_LEFT, FloatingResizeHandle.BOTTOM_RIGHT
            ))
        }
    })
}
