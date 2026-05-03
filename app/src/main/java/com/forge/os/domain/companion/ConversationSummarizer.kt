package com.forge.os.domain.companion

import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Phase J1 — at the end of a COMPANION session, distills the transcript into
 * a single [EpisodicMemory] via one strict-JSON LLM call.
 *
 * Falls back to a deterministic local summary if the model is unreachable so
 * we never silently lose an episode.
 */
@Singleton
class ConversationSummarizer @Inject constructor(
    private val apiManager: AiApiManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val sysPrompt = """
You are a session summariser for an AI companion app. You will be given the
recent transcript of a conversation between the user and a companion AI.

Return exactly ONE JSON object on a single line and nothing else:
{
  "summary": "<1 to 3 sentences in third person about what the user shared/asked/needed>",
  "mood_trajectory": "<short phrase, e.g. 'anxious → calmer' or 'steady, curious'>",
  "key_topics": ["topic1", "topic2", ...],          // 1 to 6 short strings
  "follow_ups": [
    {"prompt": "<one short reminder phrased so the companion can ask later>",
     "due_in_hours": <integer 1..168>}
  ]                                                  // 0 to 3 items
}

Rules:
- Refer to the user as "the user", never by name.
- Do NOT include direct quotes, only paraphrased substance.
- Skip follow_ups entirely if nothing concrete is worth checking back on.
- Output JSON ONLY. No prose, no markdown fences.
""".trimIndent()

    /**
     * @return null when there is nothing meaningful to summarise (too few turns,
     *         or every model attempt failed and the local fallback also produced nothing).
     */
    suspend fun summarize(
        conversationId: String,
        turns: List<TranscriptTurn>,
        recentTags: List<MessageTags> = emptyList(),
    ): EpisodicMemory? {
        if (turns.size < 2) return null
        val transcript = turns.joinToString("\n") { "${it.role.uppercase()}: ${it.content.trim()}" }
        val prompt = "Transcript:\n```\n$transcript\n```\nSummarise per the system instructions."

        val now = System.currentTimeMillis()
        val started = turns.firstOrNull()?.tsMs ?: now
        val ended = turns.lastOrNull()?.tsMs ?: now

        val parsed = try {
            val resp = apiManager.chatWithFallback(
                messages = listOf(ApiMessage(role = "user", content = prompt)),
                tools = emptyList(),
                systemPrompt = sysPrompt,
                spec = null,
                mode = com.forge.os.domain.companion.Mode.SUMMARIZATION
            )
            parse(resp.content.orEmpty())
        } catch (e: Exception) {
            Timber.w(e, "ConversationSummarizer: model call failed; using local fallback")
            null
        } ?: localFallback(turns)

        return EpisodicMemory(
            id = "${now}-${Random.nextInt(1000, 9999)}",
            conversationId = conversationId,
            startedAt = started,
            endedAt = ended,
            turnCount = turns.size,
            summary = parsed.summary.ifBlank { localFallback(turns).summary },
            moodTrajectory = parsed.moodTrajectory.ifBlank {
                deriveMoodFromTags(recentTags) ?: "unspecified"
            },
            keyTopics = parsed.keyTopics.ifEmpty { listOf("general chat") },
            followUps = parsed.followUps.map {
                FollowUp(
                    prompt = it.prompt,
                    dueAtMs = now + (it.dueInHours.coerceIn(1, 24 * 30)) * 3_600_000L,
                )
            },
            tags = recentTags.takeLast(8),
        )
    }

    // ─── parsing ──────────────────────────────────────────────────────────

    private data class ParsedFollowUp(val prompt: String, val dueInHours: Int)
    private data class ParsedSummary(
        val summary: String,
        val moodTrajectory: String,
        val keyTopics: List<String>,
        val followUps: List<ParsedFollowUp>,
    )

    private fun parse(raw: String): ParsedSummary? {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = raw.substring(start, end + 1)
        return try {
            val node = json.parseToJsonElement(body) as JsonObject
            val summary = node["summary"]?.jsonPrimitive?.content?.trim().orEmpty()
            val mood = node["mood_trajectory"]?.jsonPrimitive?.content?.trim().orEmpty()
            val topics = (node["key_topics"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } }
                ?.take(6)
                .orEmpty()
            val followUps = (node["follow_ups"] as? JsonArray)
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val p = o["prompt"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val h = o["due_in_hours"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
                    if (p.isBlank()) null else ParsedFollowUp(p, h)
                }
                ?.take(3)
                .orEmpty()
            ParsedSummary(summary, mood, topics, followUps)
        } catch (e: Exception) {
            Timber.w(e, "ConversationSummarizer.parse failed on: $body")
            null
        }
    }

    // ─── fallback ─────────────────────────────────────────────────────────

    private fun localFallback(turns: List<TranscriptTurn>): ParsedSummary {
        val userTurns = turns.filter { it.role == "user" }
        val firstUser = userTurns.firstOrNull()?.content?.take(140).orEmpty()
        val n = userTurns.size
        return ParsedSummary(
            summary = if (firstUser.isBlank())
                "Short companion check-in with no significant content captured."
            else
                "The user opened with: \"${firstUser.replace("\"", "'")}\". $n message(s) exchanged.",
            moodTrajectory = "unspecified",
            keyTopics = listOf("general"),
            followUps = emptyList(),
        )
    }

    private fun deriveMoodFromTags(tags: List<MessageTags>): String? {
        if (tags.isEmpty()) return null
        val first = tags.first().emotion
        val last = tags.last().emotion
        return if (first == last) first else "$first → $last"
    }
}

/** Minimal transcript shape decoupled from any UI model. */
data class TranscriptTurn(
    val role: String,    // "user" | "assistant"
    val content: String,
    val tsMs: Long = System.currentTimeMillis(),
)
