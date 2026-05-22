package com.forge.os.domain.channels

import com.forge.os.domain.memory.MemoryManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passively learns about the user from inbound channel messages.
 *
 * When [ChannelConfig.learnFromConversations] is true, every inbound message
 * is analysed for signals about the person sending it — their name, topics
 * they care about, communication style, preferences, and explicit facts they
 * state about themselves. These are stored in long-term memory so the agent
 * can recall them in future turns and give more personalised responses.
 *
 * ## What gets learned
 * - **Identity**: name, location, role/occupation
 * - **Interests**: recurring topics (coding, finance, health, travel, etc.)
 * - **Preferences**: explicit "I prefer/like/hate X" statements
 * - **Style**: message length, emoji usage, formality, question frequency
 * - **Timing**: what time of day the person is typically active
 *
 * ## What does NOT get stored
 * - Messages shorter than 4 words
 * - Purely transactional messages ("ok", "thanks", "yes")
 * - Sensitive patterns (passwords, card numbers)
 *
 * ## Storage keys
 * `user_profile:<platform>:<senderId>:<fact_type>`
 * Facts are upserted so the profile stays current.
 *
 * ## Retrieval
 * The agent can call `memory_recall("user profile")` or the profile is
 * injected automatically into the prompt via [buildProfileSummary].
 */
@Singleton
class ChannelLearningEngine @Inject constructor(
    private val memoryManager: MemoryManager,
) {

    /**
     * Analyse [msg] and persist any learnable signals about the sender.
     * This is a fast, synchronous operation — all I/O is fire-and-forget
     * via [MemoryManager.store] which handles its own threading.
     */
    fun learn(msg: IncomingMessage, cfg: ChannelConfig) {
        val text = msg.text.trim()
        val sender = msg.fromName.ifBlank { msg.fromId }
        val platform = msg.channelType
        val keyPrefix = "user_profile:${platform}:${msg.fromId}"

        // Skip very short or purely transactional messages
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 4) return
        val lower = text.lowercase().trim()
        if (lower in TRANSACTIONAL) return

        // ── Identity signals ──────────────────────────────────────────────
        extractName(lower, sender)?.let { name ->
            store(keyPrefix, "name",
                "Name: $name (learned from $platform)",
                listOf("identity", "name", platform, "user_profile"))
        }
        extractLocation(lower)?.let { loc ->
            store(keyPrefix, "location",
                "Location: $loc (learned from $platform)",
                listOf("identity", "location", platform, "user_profile"))
        }
        extractRole(lower)?.let { role ->
            store(keyPrefix, "role",
                "Role/occupation: $role (learned from $platform)",
                listOf("identity", "role", platform, "user_profile"))
        }

        // ── Explicit preferences ──────────────────────────────────────────
        extractPreferences(text).forEachIndexed { i, pref ->
            store(keyPrefix, "pref_$i",
                "Stated preference: $pref",
                listOf("preference", platform, "user_profile"))
        }

        // ── Topic interests ───────────────────────────────────────────────
        val topics = detectTopics(lower)
        if (topics.isNotEmpty()) {
            // Load existing topic counts from memory, increment, store back
            val existing = memoryManager.recallByKey("$keyPrefix:topics")?.content
            val counts = parseTopicCounts(existing).toMutableMap()
            topics.forEach { t -> counts[t] = (counts[t] ?: 0) + 1 }
            val topTopics = counts.entries
                .sortedByDescending { it.value }
                .take(10)
                .joinToString(", ") { "${it.key}(${it.value})" }
            store(keyPrefix, "topics",
                "Topics $sender talks about most on $platform: $topTopics",
                listOf("interests", "topics", platform, "user_profile"))
        }

        // ── Communication style ───────────────────────────────────────────
        updateStyle(keyPrefix, sender, platform, text, words.size)

        // ── Active hours ──────────────────────────────────────────────────
        val hour = java.time.LocalDateTime.now().hour
        val timeSlot = when (hour) {
            in 5..11  -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else      -> "night"
        }
        store(keyPrefix, "active_time",
            "$sender is typically active in the $timeSlot on $platform",
            listOf("behaviour", "timing", platform, "user_profile"))

        Timber.v("ChannelLearningEngine: learned from $sender on $platform (${topics.size} topics detected)")
    }

    /**
     * Build a short profile summary for [senderId] on [platform] to inject
     * into the agent prompt before it replies. Returns null if nothing has
     * been learned yet.
     */
    fun buildProfileSummary(senderId: String, platform: String): String? {
        val keyPrefix = "user_profile:${platform}:${senderId}"
        val factKeys = listOf("name", "location", "role", "topics", "style", "active_time")
        val facts = factKeys.mapNotNull { fact ->
            memoryManager.recallByKey("$keyPrefix:$fact")?.content
                ?.takeIf { it.isNotBlank() }
        }
        // Also pull up to 3 preference entries
        val prefs = (0..4).mapNotNull { i ->
            memoryManager.recallByKey("$keyPrefix:pref_$i")?.content
        }

        if (facts.isEmpty() && prefs.isEmpty()) return null

        return buildString {
            appendLine("### What I know about this person")
            facts.forEach { appendLine("- $it") }
            if (prefs.isNotEmpty()) {
                appendLine("- Preferences: ${prefs.joinToString("; ")}")
            }
        }.trimEnd()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Extraction helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun extractName(lower: String, fallback: String): String? {
        val patterns = listOf(
            Regex("""(?:i'?m|i am|my name is|call me|it'?s me,?)\s+([a-z][a-z]{1,19})\b"""),
        )
        for (p in patterns) {
            val m = p.find(lower) ?: continue
            val name = m.groupValues[1].trim()
            if (name.length in 2..20 && name !in STOP_WORDS) {
                return name.replaceFirstChar { it.uppercase() }
            }
        }
        // Use display name if it looks like a real name (has a space, no underscores)
        if (fallback.contains(" ") && fallback.length in 3..40 &&
            !fallback.contains("_") && !fallback.contains("@") &&
            fallback.all { it.isLetter() || it.isWhitespace() }) {
            return fallback
        }
        return null
    }

    private fun extractLocation(lower: String): String? {
        val p = Regex(
            """(?:i'?m (?:in|from)|i live in|based in|located in|i'?m based in)\s+([a-z][a-z ,]{2,39})""")
        return p.find(lower)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length in 3..40 && it.split(" ").size <= 4 }
    }

    private fun extractRole(lower: String): String? {
        val p = Regex("""(?:i'?m a|i am a|i work as a?n?|i'?m an)\s+([a-z][a-z ]{2,39})""")
        val m = p.find(lower) ?: return null
        val role = m.groupValues[1].trim().trimEnd('.')
        return if (role.split(" ").size <= 4 && role !in STOP_WORDS) role else null
    }

    private fun extractPreferences(text: String): List<String> {
        val patterns = listOf(
            Regex("""I (?:prefer|like|love|enjoy|always use|hate|dislike|can't stand)\s+(.{5,60})""",
                RegexOption.IGNORE_CASE),
            Regex("""[Mm]y (?:favourite|favorite|preferred|go-to)\s+\w+\s+is\s+(.{3,40})"""),
        )
        return patterns.flatMap { p ->
            p.findAll(text).map { it.groupValues[1].trim().trimEnd('.', ',', '!') }
        }.filter { it.length in 5..60 }.distinct().take(3)
    }

    private fun detectTopics(lower: String): List<String> {
        return TOPIC_KEYWORDS.entries
            .filter { (_, keywords) -> keywords.any { kw -> lower.contains(kw) } }
            .map { it.key }
    }

    private fun updateStyle(
        keyPrefix: String, sender: String, platform: String,
        text: String, wordCount: Int,
    ) {
        val length = when {
            wordCount < 10 -> "brief"
            wordCount < 30 -> "moderate"
            else           -> "detailed"
        }
        val hasEmoji = text.any { it.code > 0x1F300 }
        val isQuestion = text.trimEnd().endsWith("?")
        val isCasual = text.contains(Regex("""[Hh]ey|[Hh]i |[Yy]o |[Ww]hat'?s up|lol|haha"""))

        val desc = buildString {
            append("$sender writes $length messages on $platform")
            if (hasEmoji) append(", uses emoji")
            if (isQuestion) append(", often asks questions")
            if (isCasual) append(", casual/informal tone")
        }
        store(keyPrefix, "style", desc, listOf("style", "communication", platform, "user_profile"))
    }

    private fun parseTopicCounts(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        // Format: "Topics X talks about most on Y: topic1(3), topic2(1)"
        val part = raw.substringAfterLast(":").trim()
        return part.split(",").mapNotNull { entry ->
            val m = Regex("""(\w+)\((\d+)\)""").find(entry.trim()) ?: return@mapNotNull null
            m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 1)
        }.toMap()
    }

    private fun store(keyPrefix: String, fact: String, content: String, tags: List<String>) {
        try {
            memoryManager.store(
                key = "$keyPrefix:$fact",
                content = content,
                tags = tags,
            )
        } catch (e: Exception) {
            Timber.w(e, "ChannelLearningEngine: store failed for $fact")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────

    companion object {
        private val TRANSACTIONAL = setOf(
            "ok", "okay", "yes", "no", "sure", "thanks", "thank you",
            "got it", "noted", "understood", "cool", "great", "nice",
            "👍", "👌", "✅", "lol", "haha", "hmm", "yep", "nope",
        )

        private val STOP_WORDS = setOf(
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "and",
            "or", "but", "not", "with", "from", "by", "as", "is", "are",
            "was", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "could", "should", "may",
            "might", "must", "shall", "can", "need", "good", "bad", "new",
            "old", "big", "small", "happy", "sad", "just", "very", "really",
        )

        private val TOPIC_KEYWORDS = mapOf(
            "coding"       to listOf("code", "coding", "programming", "python", "kotlin",
                                     "javascript", "typescript", "java", "swift", "rust",
                                     "golang", "c++", "sql", "api", "backend", "frontend",
                                     "fullstack", "developer", "engineer", "bug", "debug",
                                     "deploy", "git", "github", "docker", "kubernetes"),
            "ai_ml"        to listOf("ai", "machine learning", "llm", "gpt", "claude",
                                     "gemini", "neural", "model", "training", "inference",
                                     "prompt", "embedding", "chatgpt", "openai", "anthropic"),
            "finance"      to listOf("money", "invest", "stock", "crypto", "bitcoin",
                                     "ethereum", "trading", "portfolio", "budget", "savings",
                                     "bank", "finance", "salary", "income", "expense", "tax"),
            "health"       to listOf("health", "fitness", "workout", "gym", "diet",
                                     "nutrition", "sleep", "meditation", "mental health",
                                     "doctor", "medicine", "exercise", "running", "yoga"),
            "travel"       to listOf("travel", "trip", "flight", "hotel", "vacation",
                                     "holiday", "country", "city", "passport", "visa",
                                     "backpack", "explore", "abroad"),
            "business"     to listOf("business", "startup", "company", "product", "market",
                                     "customer", "revenue", "growth", "strategy", "team",
                                     "management", "ceo", "founder", "entrepreneur"),
            "learning"     to listOf("learn", "study", "course", "book", "read",
                                     "university", "school", "degree", "tutorial",
                                     "practice", "skill", "knowledge"),
            "creative"     to listOf("design", "art", "music", "write", "writing",
                                     "photo", "video", "creative", "draw", "paint",
                                     "film", "podcast", "blog"),
            "productivity" to listOf("productivity", "task", "todo", "project", "deadline",
                                     "schedule", "calendar", "focus", "habit", "routine",
                                     "goal", "plan", "organize"),
            "gaming"       to listOf("game", "gaming", "play", "steam", "xbox",
                                     "playstation", "nintendo", "esports", "twitch",
                                     "minecraft", "fortnite"),
        )
    }
}
