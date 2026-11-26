package ai.antigem.data.search

import android.util.Log
import ai.antigem.data.files.AttachedFiles
import ai.antigem.data.jarvis.JarvisEvents
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.data.remote.PromptExpansion
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import ai.antigem.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-prompt search for the sidebar, kept small on purpose.
 *
 * Only the chats' titles (already on [Conversation]) and the text the user typed are searchable: no replies, thinking,
 * tool calls, code or summaries are ever read into this cache. The entries live in memory and are rebuilt in the
 * background each launch through the same per-agent loaders the sidebar export uses; the UI only calls [matches] and
 * [snippet], so a persistent index (Room/FTS) can replace the backing store later without touching the drawer.
 *
 * Sub-agent chats are skipped: their "user" message is a task the parent agent generated, not the user's words.
 */
object ChatSearchCache {
    private const val TAG = "ChatSearchCache"

    /** Safety bounds for very long histories; with today's volume they are never reached. */
    private const val MAX_PROMPTS_PER_CHAT = 500
    private const val MAX_CHARS_PER_CHAT = 50_000
    private const val SNIPPET_BEFORE = 40
    private const val SNIPPET_AFTER = 90
    private val whitespaceRegex = Regex("\\s+")

    private data class Entry(
        val updatedAt: Long,
        val prompts: List<String>,
        val lowered: List<String>
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val _revision = MutableStateFlow(0)

    /** Bumps whenever an entry changes, so Compose recomposes search results as chats are loaded. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Conversation>(Channel.UNLIMITED)
    private val queued = Collections.synchronizedSet(mutableSetOf<String>())
    private val hub by lazy { AgyHubClient() }
    private var started = false
    private var vm: ChatViewModel? = null
    @Volatile private var pending: List<Conversation>? = null
    private val pendingDirty = AtomicBoolean(false)

    @Synchronized
    fun start(viewModel: ChatViewModel) {
        if (started) return
        started = true
        vm = viewModel

        // one chat at a time: a transcript read must not compete with the chat on screen
        scope.launch {
            for (conv in queue) {
                queued.remove(conv.id)
                runCatching { index(conv) }
                    .onFailure { Log.w(TAG, "could not index ${conv.id}: ${it.message}") }
            }
        }
        // a new list updates [pending] and marks it dirty; the worker below picks it up after a quiet moment
        scope.launch {
            viewModel.allConversations.collect {
                pending = it
                pendingDirty.set(true)
            }
        }
        scope.launch {
            while (true) {
                delay(1_500)
                if (!pendingDirty.getAndSet(false)) continue
                pending?.let(::enqueue)
            }
        }
    }

    /** The prompt was just sent: make it searchable immediately, before the chat is reloaded. */
    fun onPromptSent(chatId: String?, typed: String) {
        if (chatId.isNullOrBlank()) return
        val text = userText(typed)
        if (text.isBlank()) return
        val existing = entries[chatId]
        val prompts = (existing?.prompts ?: emptyList()) + text
        entries[chatId] = entry(existing?.updatedAt ?: 0L, prompts)
        _revision.update { it + 1 }
    }

    fun onChatDeleted(chatId: String) {
        if (chatId.isBlank()) return
        if (entries.remove(chatId) != null) _revision.update { it + 1 }
    }

    /** True when one of the chat's user prompts contains [query]. Titles are matched by the drawer itself. */
    fun matches(chatId: String, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        val entry = entries[chatId] ?: return false
        return entry.lowered.any { it.contains(q) }
    }

    /** The first matching user prompt, trimmed around the hit, for the sidebar result row. */
    fun snippet(chatId: String, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val entry = entries[chatId] ?: return null
        val lower = q.lowercase()
        val index = entry.lowered.indexOfFirst { it.contains(lower) }
        if (index < 0) return null
        val original = entry.prompts[index]
        val hit = original.indexOf(q, ignoreCase = true)
        if (hit < 0) return original.replace('\n', ' ').replace(whitespaceRegex, " ").trim().take(140)
        val start = (hit - SNIPPET_BEFORE).coerceAtLeast(0)
        val end = (hit + q.length + SNIPPET_AFTER).coerceAtMost(original.length)
        var text = original.substring(start, end)
            .replace('\n', ' ')
            .replace(whitespaceRegex, " ")
            .trim()
        if (start > 0) text = "…$text"
        if (end < original.length) text = "$text…"
        return text
    }

    private fun enqueue(list: List<Conversation>) {
        // newest first: a search right after launch finds recent prompts first
        for (conv in list.sortedByDescending { it.updatedAt }) {
            if (conv.id.isBlank()) continue
            // a sub-agent's "user" message is the parent agent's task prompt
            val parent = conv.parentConversationId
            if (parent != null && parent != conv.id) continue
            val existing = entries[conv.id]
            if (existing != null && existing.updatedAt == conv.updatedAt) continue
            if (queued.add(conv.id)) queue.trySend(conv)
        }
    }

    private suspend fun index(conv: Conversation) {
        // a running chat changes again when it stops: index it then, not on every streamed step
        if (conv.isRunning) return
        val viewModel = vm ?: return
        // deleted (or hidden) while it waited in the queue
        if (viewModel.allConversations.value.none { it.id == conv.id }) return
        val prompts = promptsOf(messagesOf(viewModel, conv))
        entries[conv.id] = entry(conv.updatedAt, prompts)
        _revision.update { it + 1 }
    }

    /**
     * The searchable text of a chat: only what the user typed. Replies, thinking, tool calls, generated code and
     * summaries are never read here, and Jarvis' generated event cards are skipped.
     */
    internal fun promptsOf(messages: List<ChatMessage>): List<String> {
        val prompts = mutableListOf<String>()
        for (message in messages) {
            if (message.role != MessageRole.USER) continue
            if (message.isStreaming) continue
            if (JarvisEvents.isEventMessage(message.content)) continue
            val text = userText(message.content)
            if (text.isNotBlank()) prompts.add(text)
        }
        return prompts
    }

    private suspend fun messagesOf(viewModel: ChatViewModel, conv: Conversation): List<ChatMessage> {
        if (viewModel.currentConversation.value?.id == conv.id) {
            viewModel.messages.value.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return runCatching {
            when (conv.agent) {
                AgentKind.CLAUDE -> viewModel.loadClaudeMessages(conv.id)
                AgentKind.OPENCODE -> viewModel.loadOpenCodeMessages(conv.id)
                AgentKind.CODEX -> viewModel.loadCodexMessages(conv.id)
                AgentKind.AGY -> {
                    val steps = hub.getCascadeTrajectorySteps(conv.id).getOrNull().orEmpty()
                    ai.antigem.domain.chat.TrajectoryEngine().ingestStepsDirect(steps, conv.id)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Only the user's words: hidden app blocks and attached-file path lists are stripped. */
    private fun userText(raw: String): String =
        PromptExpansion.collapse(AttachedFiles.extract(raw, "search").first).trim()

    private fun entry(updatedAt: Long, prompts: List<String>): Entry {
        // keep the newest prompts when a chat grows past the safety bounds
        val recent = if (prompts.size > MAX_PROMPTS_PER_CHAT) prompts.takeLast(MAX_PROMPTS_PER_CHAT) else prompts
        val kept = ArrayDeque<String>()
        var chars = 0
        for (prompt in recent.asReversed()) {
            if (kept.isNotEmpty() && chars + prompt.length > MAX_CHARS_PER_CHAT) break
            kept.addFirst(prompt)
            chars += prompt.length
        }
        val keptList = kept.toList()
        return Entry(updatedAt, keptList, keptList.map { it.lowercase() })
    }
}
