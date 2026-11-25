package ai.antigem.data.jev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevMcpTest {

    @Test
    fun toolsExposeAllSixJevTools() {
        val tools = JevMcp.tools()
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(
            listOf("jev_health", "jev_yes_no", "jev_choice", "jev_rate", "jev_ask", "jev_batch"),
            names
        )
    }

    @Test
    fun buildRequestPayloadUsesFreeModelByDefault() {
        val payload = JevMcp.buildRequestPayload(
            state = "sample text",
            questions = JSONObject().put("q1", JSONObject().put("type", "noul").put("instructions", "Valid?"))
        )
        assertEquals(JevMcp.DEFAULT_MODEL, payload.getString("model"))
        assertEquals("sample text", payload.getString("state"))
        assertTrue(payload.getJSONObject("questions").has("q1"))
    }

    @Test
    fun compactAnswersStripsProbabilityMaps() {
        val raw = JSONObject()
            .put("is_urgent", JSONObject().put("type", "noul").put("noul", 0.91).put("extra", "ignored"))
            .put(
                "team",
                JSONObject()
                    .put("type", "choice")
                    .put("choice", "support")
                    .put("confidence", 0.88)
                    .put("probabilities", JSONObject().put("support", 0.88).put("sales", 0.12))
            )
            .put(
                "severity",
                JSONObject()
                    .put("type", "score")
                    .put("score", 4.2)
                    .put("confidence", 0.79)
                    .put("probabilities", JSONObject().put("4", 0.8))
            )

        val compact = JevMcp.compactAnswers(raw, includeProbabilities = false)
        assertEquals(0.91, compact.getJSONObject("is_urgent").getDouble("noul"), 0.0001)
        assertFalse(compact.getJSONObject("is_urgent").has("extra"))

        assertEquals("support", compact.getJSONObject("team").getString("choice"))
        assertEquals(0.88, compact.getJSONObject("team").getDouble("confidence"), 0.0001)
        assertFalse(compact.getJSONObject("team").has("probabilities"))

        assertEquals(4.2, compact.getJSONObject("severity").getDouble("score"), 0.0001)
        assertFalse(compact.getJSONObject("severity").has("probabilities"))

        val full = JevMcp.compactAnswers(raw, includeProbabilities = true)
        assertTrue(full.getJSONObject("team").has("probabilities"))
    }
}
