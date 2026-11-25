package ai.antigem.data.jev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * jev_mcp: TypeSafe Jev (System One) decision model MCP server.
 * Served natively by AndroidLocalBridgeServer at /jev using the free jev-1.13-free tier.
 */
object JevMcp {

    const val DEFAULT_URL = "https://opencode.ai/inference/v1/systemone"
    const val DEFAULT_MODEL = "jev-1.13-free"
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_RETRIES = 2
    private const val BATCH_CONCURRENCY = 6
    private val RETRYABLE_STATUS = setOf(429, 502, 503, 504)
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    const val INSTRUCTIONS =
        "TypeSafe Jev is a System One decision model (calibrated probabilities, choices, and scores—never prose). " +
            "Use jev_batch (up to 100 items) for batch classification, tagging, or triage of 5+ items. " +
            "Use jev_ask with compact=true when evaluating 2+ criteria on the same record (shares input state). " +
            "Confidence gating: auto-act when confidence >= 0.75 (or noul >= 0.85 / <= 0.15); escalate borderline items. " +
            "Keep deterministic checks (regex, deduplication, schema validation) in code, and never use Jev for prose, code generation, or trivial single checks."

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // ---------------- tool schema ----------------

    private fun prop(type: String, description: String) = JSONObject().apply {
        put("type", type)
        put("description", description)
    }

    private fun anyProp(description: String) = JSONObject().apply {
        put("description", description)
    }

    private fun stringArray(description: String, minItems: Int? = null, maxItems: Int? = null) = JSONObject().apply {
        put("type", "array")
        put("description", description)
        put("items", JSONObject().put("type", "string"))
        minItems?.let { put("minItems", it) }
        maxItems?.let { put("maxItems", it) }
    }

    private fun tool(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        vararg props: Pair<String, JSONObject>
    ) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    fun tools(): JSONArray = JSONArray().apply {
        put(tool(
            "jev_health",
            "Check that the Jev (TypeSafe System One) decision endpoint is reachable and responding."
        ))
        put(tool(
            "jev_yes_no",
            "Ask Jev one yes/no question about some state and get the probability that the answer is yes (0 to 1). " +
                "Use it for either/or judgments: does this need a reply, is this a genuine lead, is this content safe, should we escalate.",
            listOf("state", "question"),
            "state" to anyProp("The context to judge: a string, or an object/array of facts, records, or messages."),
            "question" to prop("string", "The yes/no question, e.g. 'Does this message require urgent attention?'"),
            "yes_means" to prop("string", "Optional: what a 'yes' (near 1) means."),
            "no_means" to prop("string", "Optional: what a 'no' (near 0) means."),
            "model" to prop("string", "Optional model override (defaults to $DEFAULT_MODEL).")
        ))
        put(tool(
            "jev_choice",
            "Ask Jev to pick exactly one option from a set you define, given some state. " +
                "Returns the chosen option, the probability of every option, and a confidence.",
            listOf("state", "question", "options"),
            "state" to anyProp("The context to decide from."),
            "question" to prop("string", "What to decide, e.g. 'Which team should handle this request?'"),
            "options" to stringArray("The candidate options.", minItems = 2, maxItems = 255),
            "descriptions" to anyProp("Optional object mapping option -> short description/rubric."),
            "model" to prop("string", "Optional model override.")
        ))
        put(tool(
            "jev_rate",
            "Ask Jev to rate some state along ordered levels you define. Returns a probability-weighted score " +
                "(can land between levels), the level legend, probabilities, and confidence.",
            listOf("state", "question", "levels"),
            "state" to anyProp("The context to rate."),
            "question" to prop("string", "What to rate, e.g. 'How frustrated is this customer?'"),
            "levels" to stringArray("Ordered levels from lowest to highest.", minItems = 2, maxItems = 10),
            "model" to prop("string", "Optional model override.")
        ))
        put(tool(
            "jev_ask",
            "Evaluate one state against a map of typed questions in a single parallel call (shared input context). " +
                "Prefer this over multiple jev_yes_no/jev_choice/jev_rate calls whenever asking 2+ questions about the same item. " +
                "`questions` maps an id to { type: 'noul'|'choice'|'score', instructions, criteria? }.",
            listOf("state", "questions"),
            "state" to anyProp("The context to judge."),
            "questions" to anyProp("Map of question id -> question object."),
            "compact" to prop("boolean", "If true, omit full probability distributions to save context tokens."),
            "model" to prop("string", "Optional model override.")
        ))
        put(tool(
            "jev_batch",
            "Evaluate an array of items against the same typed `questions` schema in one tool call using bounded concurrency. " +
                "Ideal for triaging emails, screening CVs, tagging scraped rows, or classifying survey responses. " +
                "Returns compact per-item decisions by default to keep agent context small.",
            listOf("items", "questions"),
            "items" to JSONObject().apply {
                put("type", "array")
                put("minItems", 1)
                put("maxItems", 100)
                put("description", "Items to evaluate (up to 100 per call).")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("id", prop("string", "Caller-supplied item identifier."))
                        put("state", anyProp("Item content/record to judge."))
                    })
                    put("required", JSONArray(listOf("id", "state")))
                })
            },
            "questions" to anyProp("Map of question id -> { type, instructions, criteria? }."),
            "include_probabilities" to prop("boolean", "Default false: set true to include full probability maps per question."),
            "model" to prop("string", "Optional model override.")
        ))
    }

    // ---------------- calls ----------------

    suspend fun call(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "jev_health" -> health()
            "jev_yes_no" -> yesNo(args)
            "jev_choice" -> choice(args)
            "jev_rate" -> rate(args)
            "jev_ask" -> ask(args)
            "jev_batch" -> batch(args)
            else -> err("Unknown tool: $name")
        }
    } catch (e: Exception) {
        err(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun health(): JSONObject {
        val questions = JSONObject().put(
            "up",
            JSONObject().put("type", "noul").put("instructions", "Is the service responding?")
        )
        val r = callJev("health check", questions)
        val out = JSONObject()
            .put("ok", true)
            .put("model", r.optString("model", DEFAULT_MODEL))
            .put("answer", r.optJSONObject("answers")?.opt("up") ?: JSONObject.NULL)
        return ok(out)
    }

    private suspend fun yesNo(args: JSONObject): JSONObject {
        val state = args.opt("state") ?: return err("`state` is required.")
        val question = args.optString("question").takeIf { it.isNotBlank() } ?: return err("`question` is required.")
        val yesMeans = args.optString("yes_means").takeIf { it.isNotBlank() }
        val noMeans = args.optString("no_means").takeIf { it.isNotBlank() }
        val model = args.optString("model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        val q = JSONObject().put("type", "noul").put("instructions", question)
        if (yesMeans != null || noMeans != null) {
            q.put("criteria", JSONObject().apply {
                put("true", yesMeans ?: JSONObject.NULL)
                put("false", noMeans ?: JSONObject.NULL)
            })
        }
        val r = callJev(state, JSONObject().put("answer", q), model)
        val noul = r.optJSONObject("answers")?.optJSONObject("answer")?.opt("noul") ?: JSONObject.NULL
        return ok(JSONObject().put("probability_yes", noul).put("model", r.optString("model", model)))
    }

    private suspend fun choice(args: JSONObject): JSONObject {
        val state = args.opt("state") ?: return err("`state` is required.")
        val question = args.optString("question").takeIf { it.isNotBlank() } ?: return err("`question` is required.")
        val options = args.optJSONArray("options") ?: return err("`options` array is required.")
        if (options.length() < 2 || options.length() > 255) return err("`options` must have 2 to 255 entries.")
        val descriptions = args.optJSONObject("descriptions")
        val model = args.optString("model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        val criteria = JSONObject()
        for (i in 0 until options.length()) {
            val opt = options.optString(i)
            val desc = descriptions?.opt(opt)
            criteria.put(opt, desc ?: JSONObject.NULL)
        }
        val q = JSONObject()
            .put("type", "choice")
            .put("instructions", question)
            .put("criteria", criteria)
        val r = callJev(state, JSONObject().put("answer", q), model)
        return ok(r.optJSONObject("answers")?.opt("answer") ?: JSONObject())
    }

    private suspend fun rate(args: JSONObject): JSONObject {
        val state = args.opt("state") ?: return err("`state` is required.")
        val question = args.optString("question").takeIf { it.isNotBlank() } ?: return err("`question` is required.")
        val levels = args.optJSONArray("levels") ?: return err("`levels` array is required.")
        if (levels.length() < 2 || levels.length() > 10) return err("`levels` must have 2 to 10 entries.")
        val model = args.optString("model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        val q = JSONObject()
            .put("type", "score")
            .put("instructions", question)
            .put("criteria", levels)
        val r = callJev(state, JSONObject().put("answer", q), model)
        return ok(r.optJSONObject("answers")?.opt("answer") ?: JSONObject())
    }

    private suspend fun ask(args: JSONObject): JSONObject {
        val state = args.opt("state") ?: return err("`state` is required.")
        val questions = args.optJSONObject("questions") ?: return err("`questions` must be an object.")
        val compact = args.optBoolean("compact", false)
        val model = args.optString("model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        val r = callJev(state, questions, model)
        if (compact) {
            return ok(
                JSONObject()
                    .put("model", r.optString("model", model))
                    .put("answers", compactAnswers(r.optJSONObject("answers"), includeProbabilities = false))
            )
        }
        return ok(r)
    }

    private suspend fun batch(args: JSONObject): JSONObject {
        val items = args.optJSONArray("items") ?: return err("`items` array is required.")
        if (items.length() < 1 || items.length() > 100) return err("`items` must have 1 to 100 entries.")
        val questions = args.optJSONObject("questions") ?: return err("`questions` must be an object.")
        val includeProbabilities = args.optBoolean("include_probabilities", false)
        val model = args.optString("model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        val semaphore = Semaphore(BATCH_CONCURRENCY)
        val itemResults = coroutineScope {
            (0 until items.length()).map { idx ->
                async(Dispatchers.IO) {
                    val item = items.optJSONObject(idx)
                    val id = item?.optString("id") ?: idx.toString()
                    val state = item?.opt("state")
                    if (item == null || state == null) {
                        return@async JSONObject()
                            .put("id", id)
                            .put("ok", false)
                            .put("error", "Item must be an object with `id` and `state`.")
                    }
                    semaphore.withPermit {
                        try {
                            val r = callJev(state, questions, model)
                            JSONObject()
                                .put("id", id)
                                .put("ok", true)
                                .put("answers", compactAnswers(r.optJSONObject("answers"), includeProbabilities))
                        } catch (e: Exception) {
                            JSONObject()
                                .put("id", id)
                                .put("ok", false)
                                .put("error", e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            }.awaitAll()
        }

        val resultsArray = JSONArray().apply { itemResults.forEach { put(it) } }
        return ok(
            JSONObject()
                .put("model", model)
                .put("count", resultsArray.length())
                .put("results", resultsArray)
        )
    }

    // ---------------- HTTP & helpers ----------------

    internal fun buildRequestPayload(state: Any, questions: JSONObject, model: String = DEFAULT_MODEL): JSONObject =
        JSONObject()
            .put("model", model)
            .put("state", state)
            .put("questions", questions)

    internal fun compactAnswers(answers: JSONObject?, includeProbabilities: Boolean = false): JSONObject {
        if (answers == null) return JSONObject()
        if (includeProbabilities) return answers
        val out = JSONObject()
        val keys = answers.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val ans = answers.optJSONObject(id)
            if (ans == null) {
                out.put(id, answers.opt(id) ?: JSONObject.NULL)
                continue
            }
            when (ans.optString("type")) {
                "noul" -> out.put(
                    id,
                    JSONObject().put("type", "noul").put("noul", ans.opt("noul") ?: JSONObject.NULL)
                )
                "choice" -> out.put(
                    id,
                    JSONObject()
                        .put("type", "choice")
                        .put("choice", ans.opt("choice") ?: JSONObject.NULL)
                        .put("confidence", ans.opt("confidence") ?: JSONObject.NULL)
                )
                "score" -> out.put(
                    id,
                    JSONObject()
                        .put("type", "score")
                        .put("score", ans.opt("score") ?: JSONObject.NULL)
                        .put("confidence", ans.opt("confidence") ?: JSONObject.NULL)
                )
                else -> out.put(id, ans)
            }
        }
        return out
    }

    private suspend fun callJev(
        state: Any,
        questions: JSONObject,
        model: String = DEFAULT_MODEL
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = buildRequestPayload(state, questions, model).toString()
        var lastError: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(DEFAULT_URL)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return@withContext JSONObject(bodyText)
                    }
                    val err = Exception("Jev HTTP ${response.code}: $bodyText")
                    if (response.code !in RETRYABLE_STATUS || attempt == MAX_RETRIES) {
                        throw err
                    }
                    lastError = err
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt == MAX_RETRIES) throw e
            }
            delay(300L shl attempt)
        }
        throw (lastError ?: IllegalStateException("Jev request failed"))
    }

    private fun ok(value: Any): JSONObject {
        val text = when (value) {
            is String -> value
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> value.toString()
        }
        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        }
    }

    private fun err(message: String): JSONObject = JSONObject().apply {
        put("isError", true)
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    }
}
