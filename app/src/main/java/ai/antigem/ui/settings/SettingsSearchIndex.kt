package ai.antigem.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.GeminiBlue
import ai.antigem.theme.QuotaAmber
import ai.antigem.theme.QuotaGreen

data class SettingsSearchEntry(
    val section: SettingsSection,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val keywords: List<String> = emptyList()
)

object SettingsSearchIndex {
    private val whitespaceRegex = Regex("\\s+")
    private val cachedEntries by lazy(LazyThreadSafetyMode.NONE) { entries() }

    fun search(query: String): List<SettingsSearchEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val terms = q.split(whitespaceRegex).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        return cachedEntries
            .mapNotNull { entry ->
                val titleLower = entry.title.lowercase()
                val subLower = entry.subtitle.lowercase()
                val kwLower = entry.keywords.joinToString(" ").lowercase()
                val haystack = "$titleLower $subLower $kwLower ${entry.section.name.lowercase()}"
                if (!terms.all { haystack.contains(it) }) return@mapNotNull null
                val score = when {
                    titleLower.startsWith(q) -> 0
                    titleLower.contains(q) -> 1
                    subLower.contains(q) -> 2
                    else -> 3
                }
                score to entry
            }
            .sortedWith(compareBy({ it.first }, { it.second.title }))
            .map { it.second }
    }

    private fun entries(): List<SettingsSearchEntry> = listOf(
        section(SettingsSection.THEMES, Icons.Outlined.Palette, ClaudeTerracotta,
            listOf("theme", "dark mode", "light mode", "color", "system theme")),
        section(SettingsSection.LANGUAGE, Icons.Outlined.Translate, GeminiBlue,
            listOf("language", "translation", "locale", "hindi", "english")),
        section(SettingsSection.APPEARANCE, Icons.Outlined.TextFields, GeminiBlue,
            listOf("font", "font size", "text size", "chat font", "bubble", "floating bubble", "vibrate", "stats", "message stats")),
        section(SettingsSection.PETS, Icons.Outlined.Pets, ClaudeTerracotta,
            listOf("pet", "pets", "companion", "mascot")),
        section(SettingsSection.HOME_SCREEN, Icons.Outlined.Widgets, QuotaGreen,
            listOf("home screen", "widget", "widgets", "shortcut", "shortcuts")),
        section(SettingsSection.FILE_PICKER, Icons.Outlined.FolderOpen, QuotaAmber,
            listOf("files", "file picker", "storage", "documents", "ubuntu")),
        section(SettingsSection.STEM_RENDERING, Icons.Outlined.Science, GeminiBlue,
            listOf("stem", "math", "latex", "chemistry", "graph", "diagrams")),
        section(SettingsSection.SERVERS, Icons.Outlined.Dns, GeminiBlue,
            listOf("server", "servers", "remote", "daemon", "restart server", "where agents run", "hub", "bridge")),
        section(SettingsSection.CONNECT_PC, Icons.Outlined.Computer, GeminiBlue,
            listOf("pc", "computer", "connect pc", "desktop", "ssh")),
        section(SettingsSection.WEBVIEW_EXTENSIONS, Icons.Outlined.Extension, ClaudeTerracotta,
            listOf("plugins", "extensions", "webview", "custom plugins")),
        section(SettingsSection.SLASH_COMMANDS, Icons.Outlined.Terminal, QuotaGreen,
            listOf("slash", "commands", "custom commands", "shortcuts")),
        section(SettingsSection.MCP, Icons.Outlined.Hub, GeminiBlue,
            listOf("mcp", "model context protocol", "tools", "servers")),
        section(SettingsSection.SKILLS_PLUGINS, Icons.Outlined.AutoAwesome, ClaudeTerracotta,
            listOf("skills", "plugins", "google plugins", "capabilities")),
        section(SettingsSection.TERMINAL, Icons.Outlined.Terminal, QuotaGreen,
            listOf("terminal", "termux", "ssh", "cursor", "font size", "buffer")),
        section(SettingsSection.BACKUPS, Icons.Outlined.Backup, GeminiBlue,
            listOf("backup", "restore", "export", "rootfs", "chats backup")),
        section(SettingsSection.DATA_PROTECTION, Icons.Outlined.Shield, QuotaAmber,
            listOf("data", "protection", "uninstall", "clear data")),
        section(SettingsSection.PROJECT_CHECKPOINTS, Icons.Outlined.History, GeminiBlue,
            listOf("checkpoint", "checkpoints", "undo", "revert")),
        section(SettingsSection.COMMANDS, Icons.Outlined.Security, QuotaAmber,
            listOf("permissions", "approvals", "auto run", "sandbox", "command")),
        section(SettingsSection.ANTIGRAVITY, Icons.Outlined.AutoAwesome, GeminiBlue,
            listOf("antigravity", "agy", "gemini", "quota", "google")),
        section(SettingsSection.AGY_CLI, Icons.Outlined.AccountCircle, GeminiBlue,
            listOf("account", "sign in", "login", "agy cli", "google account")),
        section(SettingsSection.AGY_CLOUD, Icons.Outlined.Cloud, GeminiBlue,
            listOf("cloud", "cloud agents", "remote agents")),
        section(SettingsSection.AGY_VOICE, Icons.Outlined.HeadsetMic, GeminiBlue,
            listOf("voice", "voice call", "call", "tile")),
        section(SettingsSection.CLAUDE, Icons.Outlined.SmartToy, ClaudeTerracotta,
            listOf("claude", "claude code", "anthropic", "provider", "openrouter", "deepseek", "effort", "thinking")),
        section(SettingsSection.OPENCODE, Icons.Outlined.Code, GeminiBlue,
            listOf("opencode", "openai", "provider", "endpoint")),
        section(SettingsSection.CODEX, Icons.Outlined.Terminal, QuotaGreen,
            listOf("codex", "openai", "cli")),
        section(SettingsSection.AGENTS, Icons.Outlined.Group, ClaudeTerracotta,
            listOf("agents", "enable", "disable", "new chat agent")),
        section(SettingsSection.GLOBAL_CONTEXT, Icons.Outlined.Public, GeminiBlue,
            listOf("global context", "system prompt", "instructions")),
        section(SettingsSection.AGENT_MEMORY, Icons.Outlined.Memory, ClaudeTerracotta,
            listOf("memory", "agent memory", "remember")),
        section(SettingsSection.AGENT_HISTORY, Icons.Outlined.History, GeminiBlue,
            listOf("history", "saved chats", "chats")),
        section(SettingsSection.EGM, Icons.Outlined.Psychology, ClaudeTerracotta,
            listOf("egm", "elephant", "goldfish", "design")),
        section(SettingsSection.JARVIS, Icons.Outlined.Mic, GeminiBlue,
            listOf("jarvis", "voice", "hands free", "assistant")),
        section(SettingsSection.JARVIS_AGENT, Icons.Outlined.SmartToy, GeminiBlue,
            listOf("jarvis agent", "jarvis model")),
        section(SettingsSection.JARVIS_PERSONALITY, Icons.Outlined.RecordVoiceOver, GeminiBlue,
            listOf("jarvis personality", "persona", "tone")),
        section(SettingsSection.JARVIS_SETUP, Icons.Outlined.Build, GeminiBlue,
            listOf("jarvis setup", "jarvis mcp", "jarvis permissions")),
        section(SettingsSection.JARVIS_WAKE, Icons.Outlined.NotificationsActive, GeminiBlue,
            listOf("wake", "wake phrase", "hands free", "button")),
        section(SettingsSection.JARVIS_LISTEN, Icons.Outlined.Hearing, GeminiBlue,
            listOf("listen", "auto send", "pause", "send phrase", "cancel phrase")),
        section(SettingsSection.JARVIS_VOICE, Icons.Outlined.VolumeUp, GeminiBlue,
            listOf("jarvis voice", "tts", "speed", "pitch", "quiet mode")),
        section(SettingsSection.JARVIS_TASKS, Icons.Outlined.TaskAlt, GeminiBlue,
            listOf("jarvis tasks", "manager", "background tasks")),
        section(SettingsSection.JARVIS_APPROVALS, Icons.Outlined.VerifiedUser, GeminiBlue,
            listOf("jarvis approvals", "voice approval")),
        section(SettingsSection.JARVIS_SCREEN, Icons.Outlined.Visibility, GeminiBlue,
            listOf("jarvis screen", "orb", "screen look")),
        section(SettingsSection.JARVIS_CHAT, Icons.Outlined.Chat, GeminiBlue,
            listOf("jarvis chat", "fresh chat", "live context")),
        section(SettingsSection.DIAGNOSTICS, Icons.Outlined.Speed, QuotaAmber,
            listOf("diagnostics", "performance", "network", "logs", "streams")),
        section(SettingsSection.GITHUB, Icons.Outlined.AccountTree, ClaudeTerracotta,
            listOf("github", "git", "gh", "repo")),
        section(SettingsSection.ADB_CONNECT, Icons.Outlined.PhonelinkSetup, QuotaGreen,
            listOf("adb", "wireless debugging", "pairing", "shizuku")),
        section(SettingsSection.ANDROID_AUTOMATION, Icons.Outlined.TouchApp, ClaudeTerracotta,
            listOf("automation", "accessibility", "tap", "control phone")),
        section(SettingsSection.REA, Icons.Outlined.BugReport, QuotaAmber,
            listOf("rea", "reverse engineering", "apk", "decompile")),
        section(SettingsSection.ANDROID_DEV, Icons.Outlined.Android, QuotaGreen,
            listOf("android dev", "kotlin", "compose", "flutter", "react native")),
        section(SettingsSection.TERMUX_API, Icons.Outlined.Api, QuotaGreen,
            listOf("termux api", "termux:api")),
        section(SettingsSection.X11_DESKTOP, Icons.Outlined.DesktopWindows, GeminiBlue,
            listOf("x11", "desktop", "linux gui")),
        section(SettingsSection.UPDATES, Icons.Outlined.SystemUpdate, GeminiBlue,
            listOf("update", "updates", "beta", "version")),
        section(SettingsSection.CHANGELOG, Icons.Outlined.NewReleases, ClaudeTerracotta,
            listOf("changelog", "whats new", "release notes")),
        section(SettingsSection.ABOUT, Icons.Outlined.Info, GeminiBlue,
            listOf("about", "version", "package", "info"))
    )

    private fun section(
        s: SettingsSection,
        icon: ImageVector,
        tint: Color,
        keywords: List<String> = emptyList()
    ) = SettingsSearchEntry(
        section = s,
        title = s.title,
        subtitle = s.subtitle,
        icon = icon,
        tint = tint,
        keywords = keywords
    )
}
