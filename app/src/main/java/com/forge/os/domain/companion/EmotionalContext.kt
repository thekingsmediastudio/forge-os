package com.forge.os.domain.companion

import android.content.Context
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase K — active-listening classifier.
 *
 * For every user turn in COMPANION mode, [classify] returns a small structured
 * tag bundle that drives the reply pipeline:
 *   - Vent / Share  → Acknowledge → Reflect → (only if asked) Advise
 *   - Task / Ask    → normal helpful path (will route to AGENT in M2 follow-up)
 *   - Crisis        → bypass model, emit safe response with crisis-line numbers
 *
 * Phase O-1 update: crisis-line numbers are now loaded from
 * `res/raw/crisis_lines.json`, keyed by ISO 3166-1 alpha-2 country code.
 * The user's locale is used for auto-detection; they can override via
 * [com.forge.os.domain.config.FriendModeSettings.crisisLineRegion].
 */
@Serializable
enum class MessageIntent { TASK, VENT, SHARE, ASK, CRISIS }

@Serializable
data class MessageTags(
    val intent: MessageIntent,
    val emotion: String,
    val urgency: Int,           // 0 = none, 3 = severe
)

private val FALLBACK = MessageTags(MessageIntent.SHARE, "neutral", 0)

@Singleton
class EmotionalContext @Inject constructor(
    private val apiManager: AiApiManager,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, MessageTags>()

    private val sysPrompt = """
You are a message classifier for an AI companion app. For every user message you must
return ONE compact JSON object on a single line and nothing else:
{"intent":"task|vent|share|ask|crisis","emotion":"<one or two words>","urgency":0-3}

Definitions:
- task    : a concrete request to do something (set a reminder, write code, etc.)
- vent    : the user is expressing frustration / sadness / stress and wants to be heard
- share   : the user is telling you about their day or themselves with no clear ask
- ask     : a direct question seeking information or perspective
- crisis  : self-harm, suicidal ideation, abuse, immediate danger — ALWAYS prefer crisis if uncertain

urgency  : 0 none, 1 mild, 2 elevated, 3 severe. Crisis is always 3.
emotion  : one or two words (e.g. "anxious", "happy", "lonely", "calm").

Output JSON ONLY. No prose, no code fences.
""".trimIndent()

    /** Best-effort classification. On any failure returns a safe SHARE/neutral fallback. */
    suspend fun classify(message: String): MessageTags {
        val key = message.trim()
        if (key.isEmpty()) return FALLBACK
        cache[key]?.let { return it }

        val tags = try {
            val resp = apiManager.chatWithFallback(
                messages = listOf(ApiMessage(role = "user", content = key)),
                tools = emptyList(),
                systemPrompt = sysPrompt,
                spec = null,
            )
            parse(resp.content.orEmpty())
        } catch (e: Exception) {
            Timber.w(e, "EmotionalContext.classify failed; using fallback")
            if (looksLikeCrisis(key)) MessageTags(MessageIntent.CRISIS, "distress", 3)
            else FALLBACK
        }
        cache[key] = tags
        return tags
    }

    private fun parse(raw: String): MessageTags {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return FALLBACK
        val body = raw.substring(start, end + 1)
        return try {
            val node = json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            val intentStr = (node["intent"]?.toString() ?: "").trim('"').uppercase()
            val intent = runCatching { MessageIntent.valueOf(intentStr) }.getOrDefault(MessageIntent.SHARE)
            val emotion = (node["emotion"]?.toString() ?: "\"neutral\"").trim('"')
            val urgency = (node["urgency"]?.toString() ?: "0").toIntOrNull()?.coerceIn(0, 3) ?: 0
            MessageTags(intent, emotion, urgency)
        } catch (e: Exception) {
            Timber.w(e, "EmotionalContext.parse failed on: $body")
            FALLBACK
        }
    }

    private fun looksLikeCrisis(text: String): Boolean {
        val t = text.lowercase()
        val red = listOf(
            "kill myself", "suicide", "suicidal", "want to die", "end my life",
            "hurt myself", "self harm", "self-harm", "no reason to live",
            "going to die tonight"
        )
        return red.any { t.contains(it) }
    }
}

// ── Phase O-1 — Region-aware crisis lines ────────────────────────────────────

/**
 * Loads crisis line entries from `res/raw/crisis_lines.json`. Picks the best
 * match for [regionOverride] (or the device locale if empty).
 */
object CrisisLines {
    data class Line(val label: String, val value: String)

    fun forRegion(context: Context, regionOverride: String, customText: String): List<Line> {
        if (regionOverride == "custom" && customText.isNotBlank()) {
            return listOf(Line("", customText))
        }
        return try {
            val raw = context.resources.openRawResource(
                context.resources.getIdentifier("crisis_lines", "raw", context.packageName)
            ).bufferedReader().readText()
            val root = Json.parseToJsonElement(raw).jsonObject
            val region = regionOverride.uppercase().ifBlank {
                Locale.getDefault().country.uppercase()
            }
            val entries = (root[region] ?: root["default"])?.jsonArray ?: return emptyList()
            entries.mapNotNull { el ->
                val obj = el.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Line(label, value)
            }
        } catch (e: Exception) {
            Timber.w(e, "CrisisLines.forRegion failed; returning empty list")
            emptyList()
        }
    }
}

/**
 * Phase K-2 / O-1 — hardcoded safe response for CRISIS-tagged messages. This
 * never round-trips through the model.
 *
 * Phase O-1: crisis-line list is now loaded from the JSON resource and
 * injected at call time rather than hardcoded, so the text stays current
 * across locales and user overrides.
 */
object CrisisResponse {
    fun text(personaName: String, lines: List<CrisisLines.Line>): String {
        val lineBlock = if (lines.isEmpty()) {
            "• International — https://findahelpline.com"
        } else {
            lines.joinToString("\n") { "• ${it.label.trimEnd(':').ifBlank { "Resource" }}: ${it.value}" }
        }
        return """
$personaName here. I'm really glad you told me. What you're feeling is real, and I don't want you to face it alone.

If you might be in immediate danger, please reach out to a person who can be physically with you, or to one of these lines — they are free, confidential, and staffed 24/7:

$lineBlock

I'm right here. If it helps to talk to me too, tell me what's going on — I'll listen, no judgment.
""".trimIndent()
    }

    /** Convenience overload for callers that haven't yet loaded the lines. */
    fun text(personaName: String): String = text(personaName, emptyList())
}

/**
 * Phase K-2 — extra system-prompt steering injected per-turn based on tags.
 * Returned string is appended to the COMPANION system prompt for ONE turn only.
 */
object ListeningSteer {
    fun extraSystem(tags: MessageTags): String = when (tags.intent) {
        MessageIntent.VENT  -> """
INSTRUCTION FOR THIS REPLY:
The user is venting (emotion: ${tags.emotion}). Do NOT offer solutions, advice, or fixes.
1. Acknowledge what they feel in your own words.
2. Reflect back the specific thing they said (paraphrase, do not parrot).
3. Ask ONE gentle, open-ended question that invites them to say more.
Keep it short (2–4 sentences). No bullet lists. No "have you tried…".
""".trimIndent()
        MessageIntent.SHARE -> """
INSTRUCTION FOR THIS REPLY:
The user is sharing (emotion: ${tags.emotion}). Receive it warmly.
1. React with genuine interest to the specific thing they said.
2. Ask ONE follow-up question that shows you were paying attention.
Avoid jumping to advice or tasks unless they explicitly ask.
""".trimIndent()
        MessageIntent.ASK   -> """
INSTRUCTION FOR THIS REPLY:
The user asked a direct question. Answer it concisely and warmly. It is fine to add ONE short follow-up question if it fits naturally.
""".trimIndent()
        MessageIntent.TASK  -> ""
        MessageIntent.CRISIS -> ""
    }
}
