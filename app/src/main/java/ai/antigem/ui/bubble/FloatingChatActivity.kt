package ai.antigem.ui.bubble

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ai.antigem.AppViewMode
import ai.antigem.MainActivity
import ai.antigem.theme.GeminiTheme
import ai.antigem.theme.isSystemInDarkThemeRobust
import ai.antigem.ui.browser.BrowserScreen
import ai.antigem.ui.chat.ChatScreen
import ai.antigem.ui.chat.ChatViewModel
import ai.antigem.ui.chat.ChatViewModelHolder
import ai.antigem.ui.components.LocalTerminalContent
import ai.antigem.ui.ide.IdeScreen
import ai.antigem.data.voice.VoiceCallShortcut

class FloatingChatActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVoiceCallIntent(intent)
        enableEdgeToEdge()
        overridePendingTransition(0, 0)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            GeminiTheme(darkTheme = useDarkTheme) {
                FloatingChatWindow(
                    viewModel = chatViewModel,
                    onOpenFullApp = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        finish()
                        overridePendingTransition(0, 0)
                    },
                    onMinimize = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceCallIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        VoiceCallShortcut.setFloatingWindowOpen(true)
    }

    override fun onStop() {
        VoiceCallShortcut.setFloatingWindowOpen(false)
        super.onStop()
    }

    private fun handleVoiceCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MainActivity.EXTRA_START_VOICE_CALL, false) != true) return
        intent.removeExtra(MainActivity.EXTRA_START_VOICE_CALL)
        VoiceCallShortcut.request()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

@Composable
fun FloatingChatWindow(
    viewModel: ChatViewModel,
    onOpenFullApp: () -> Unit,
    onMinimize: () -> Unit
) {
    var currentViewMode by rememberSaveable { mutableStateOf(AppViewMode.CHAT) }
    var hasEverOpenedTerminal by rememberSaveable { mutableStateOf(false) }

    if (currentViewMode == AppViewMode.TERMINAL) {
        hasEverOpenedTerminal = true
    }

    BackHandler(enabled = currentViewMode == AppViewMode.IDE || currentViewMode == AppViewMode.TERMINAL) {
        currentViewMode = AppViewMode.CHAT
    }

    BackHandler(enabled = currentViewMode == AppViewMode.CHAT) {
        onMinimize()
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val isKeyboardOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > navInsets.getBottom(density) }
    }

    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var customWidthDp by rememberSaveable { mutableFloatStateOf(0f) }
    var customHeightDp by rememberSaveable { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onMinimize() }
            )
            .statusBarsPadding()
            .imePadding()
    ) {
        val maxAvailableW = maxWidth
        val maxAvailableH = maxHeight
        val cardW = if (customWidthDp > 0f) customWidthDp.dp.coerceIn(280.dp, maxAvailableW) else maxAvailableW * 0.95f
        val cardH = if (isKeyboardOpen) maxAvailableH else if (customHeightDp > 0f) customHeightDp.dp.coerceIn(340.dp, maxAvailableH) else maxAvailableH * 0.90f

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offsetX.roundToInt(), if (isKeyboardOpen) 0 else offsetY.roundToInt()) }
                .size(cardW, cardH)
        ) {
            // Multitasking Floating Window Card
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Consume clicks inside card
                    ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 18.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Draggable Grab Handle at Top
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Persistent Floating Chat Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.CHAT
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else -20000f
                                }
                        ) {
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

                        // Persistent Floating IDE Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.IDE
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else 20000f
                                }
                        ) {
                            IdeScreen(
                                viewModel = viewModel,
                                isVisible = currentViewMode == AppViewMode.IDE,
                                onNavigateToChat = { currentViewMode = AppViewMode.CHAT },
                                onExecuteRunCommand = { cmd -> },
                                onNavigateToTerminal = { currentViewMode = AppViewMode.TERMINAL },
                                onNavigateToBrowser = { currentViewMode = AppViewMode.BROWSER }
                            )
                        }

                        // Persistent Floating Terminal Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.TERMINAL
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else 20000f
                                }
                        ) {
                            val terminalSessions by ai.antigem.data.local.LocalTerminalManager.sessions.collectAsState()
                            if (currentViewMode == AppViewMode.TERMINAL || (hasEverOpenedTerminal && terminalSessions.isNotEmpty())) {
                                LocalTerminalContent(
                                    onClose = { currentViewMode = AppViewMode.CHAT }
                                )
                            }
                        }

                        // Persistent Floating Browser Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.BROWSER
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else 20000f
                                }
                        ) {
                            BrowserScreen(
                                isVisible = currentViewMode == AppViewMode.BROWSER,
                                onClose = { currentViewMode = AppViewMode.CHAT }
                            )
                        }
                    }
                }
            }

            // Bottom-right Resize Grip
            if (!isKeyboardOpen) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.BottomEnd)
                        .pointerInput(density) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                with(density) {
                                    val currentW = cardW.toPx()
                                    val currentH = cardH.toPx()
                                    customWidthDp = (currentW + dragAmount.x).toDp().value
                                    customHeightDp = (currentH + dragAmount.y).toDp().value
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Canvas(modifier = Modifier.size(14.dp).padding(end = 4.dp, bottom = 4.dp)) {
                        val w = size.width
                        val h = size.height
                        val c = Color.Gray.copy(alpha = 0.45f)
                        drawLine(c, Offset(w, h * 0.4f), Offset(w * 0.4f, h), strokeWidth = 2.dp.toPx())
                        drawLine(c, Offset(w, h * 0.75f), Offset(w * 0.75f, h), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
    }
}
