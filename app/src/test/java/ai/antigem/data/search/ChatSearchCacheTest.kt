package ai.antigem.data.search

import ai.antigem.data.remote.PromptExpansion
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sidebar's prompt search must only ever see what the user typed. */
class ChatSearchCacheTest {

    private fun message(role: MessageRole, content: String) = ChatMessage(
        conversationId = "test-chat",
        role = role,
        content = content
    )

    @Test
    fun `only user prompts are indexed`() {
        val prompts = ChatSearchCache.promptsOf(
            listOf(
                message(MessageRole.USER, "first question"),
                message(MessageRole.ASSISTANT, "the reply"),
                message(MessageRole.TOOL, "tool output"),
                message(MessageRole.SYSTEM, "system note"),
                message(MessageRole.SUMMARY, "summary"),
                message(MessageRole.USER, "second question")
            )
        )
        assertEquals(listOf("first question", "second question"), prompts)
    }

    @Test
    fun `hidden app blocks are stripped`() {
        val raw = "real question\n\n" + PromptExpansion.hidden("instructions the user never sees")
        val prompts = ChatSearchCache.promptsOf(listOf(message(MessageRole.USER, raw)))
        assertEquals(listOf("real question"), prompts)
    }

    @Test
    fun `jarvis event cards are not user prompts`() {
        val prompts = ChatSearchCache.promptsOf(
            listOf(message(MessageRole.USER, "<jarvis_events count=\"1\"><event>x</event></jarvis_events>"))
        )
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun `a sent prompt is searchable and removed with its chat`() {
        ChatSearchCache.onPromptSent("test-chat", "please inspect the parser bug today")
        assertTrue(ChatSearchCache.matches("test-chat", "parser"))
        assertNotNull(ChatSearchCache.snippet("test-chat", "parser"))
        assertTrue(ChatSearchCache.snippet("test-chat", "parser")!!.contains("parser bug"))

        ChatSearchCache.onChatDeleted("test-chat")
        assertFalse(ChatSearchCache.matches("test-chat", "parser"))
    }
}
