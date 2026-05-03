package com.forge.os.domain.memory

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_CONTEXT_CHARS = 1500
private const val RECALL_HITS = 6

@Singleton
class MemoryManager @Inject constructor(
    val daily: DailyMemory,
    val longterm: LongtermMemory,
    val skill: SkillMemory,
    val semantic: SemanticFactIndex,
    val reranker: ContextReranker,
) {
    private val index = MemoryIndex()
    private var indexBuilt = false

    init {
        rebuildIndex()
    }

    // ─── Write API ────────────────────────────────────────────────────────────

    fun store(key: String, content: String, tags: List<String> = emptyList()) {
        longterm.store(key, content, tags)
        index.add("lt:$key", "$key: $content", MemoryTier.LONGTERM, System.currentTimeMillis())
        indexBuilt = false
        semantic.delete(key)
        Timber.d("MemoryManager: stored fact '$key'")
    }

    fun forgetFact(key: String): Boolean {
        val removed = longterm.delete(key)
        if (removed) {
            index.remove("lt:$key")
            indexBuilt = false
            semantic.delete(key)
        }
        return removed
    }

    fun storeSkill(name: String, description: String, code: String, tags: List<String> = emptyList()) {
        skill.store(name, description, code, tags)
        index.add("sk:$name", "$name $description", MemoryTier.SKILL, System.currentTimeMillis())
        indexBuilt = false
        Timber.d("MemoryManager: stored skill '$name'")
    }

    fun logEvent(role: String, content: String, tags: List<String> = emptyList()) {
        daily.append(DailyEvent(role = role, content = content, tags = tags))
    }

    // ─── Recall API ──────────────────────────────────────────────────────────

    suspend fun recall(query: String, k: Int = RECALL_HITS): List<MemoryHit> {
        if (!indexBuilt) {
            index.build()
            indexBuilt = true
        }
        
        // 1. TF-IDF Hits (fetch extra candidates for reranking)
        val keywordHitsRaw = index.query(query, k * 3)
        val keywordHits = keywordHitsRaw.mapNotNull { (doc, score) ->
            when {
                doc.id.startsWith("lt:") -> {
                    val key = doc.id.removePrefix("lt:")
                    val entry = longterm.recall(key) ?: return@mapNotNull null
                    MemoryHit(MemoryTier.LONGTERM, key, entry.content, score, entry.timestamp)
                }
                doc.id.startsWith("sk:") -> {
                    val name = doc.id.removePrefix("sk:")
                    val entry = skill.recall(name) ?: return@mapNotNull null
                    MemoryHit(MemoryTier.SKILL, name, "```python\n${entry.code}\n```", score, entry.lastUsed)
                }
                doc.id.startsWith("daily:") -> {
                    MemoryHit(MemoryTier.DAILY, doc.id, doc.text, score, doc.timestamp)
                }
                else -> null
            }
        }

        // 2. Semantic Hits (fetch extra candidates)
        val semanticHits = try { semanticRecallFacts(query, k * 3) } catch (e: Exception) { emptyList() }

        // 3. Importance Map (access counts)
        val importanceMap = longterm.getAll().mapValues { it.value.accessCount }

        // 4. Merge and Rerank
        val merged = reranker.mergeHits(keywordHits, semanticHits, importanceMap)
        return reranker.rerank(query, merged, k)
    }

    fun recallByKey(key: String): FactEntry? = longterm.recall(key)

    suspend fun semanticRecallFacts(query: String, k: Int = RECALL_HITS): List<MemoryHit> {
        val facts = longterm.getAll()
        if (facts.isEmpty()) return emptyList()
        semantic.reindex(facts)
        val ranked = semantic.recall(query, k)
        return ranked.mapNotNull { (key, score) ->
            val entry = facts[key] ?: return@mapNotNull null
            MemoryHit(MemoryTier.LONGTERM, key, entry.content, score, entry.timestamp)
        }
    }

    /**
     * Phase O-5 — Memory transparency: return every stored long-term fact as
     * a (key, content) pair, sorted by most recently updated. Used by the
     * Companion Memory screen to list everything the app knows about you.
     */
    fun recallAll(): List<Pair<String, String>> {
        return longterm.getAll()
            .values
            .sortedByDescending { it.timestamp }
            .map { it.key to it.content }
    }

    /**
     * Memory transparency, expanded: walks **every** tier — long-term facts,
     * skills, and recent daily-log events — and returns each entry tagged with
     * its source tier and a real timestamp. Sorted newest-first so the
     * Companion Memory screen can show a unified "what does Forge actually
     * remember about me, when, and where" timeline.
     *
     * Daily entries are read for the last [dailyDays] days (default 7) so the
     * UI doesn't drown in months of history; long-term + skills are returned
     * in full.
     */
    fun recallAllAcrossTiers(dailyDays: Int = 7): List<MemoryHit> {
        val out = mutableListOf<MemoryHit>()
        longterm.getAll().values.forEach { f ->
            out += MemoryHit(MemoryTier.LONGTERM, f.key, f.content, 1.0f, f.timestamp)
        }
        skill.getAll().forEach { s ->
            out += MemoryHit(
                source = MemoryTier.SKILL,
                key = s.name,
                content = if (s.description.isNotBlank()) s.description else "(skill: ${s.name})",
                score = 1.0f,
                timestamp = s.lastUsed,
            )
        }
        daily.readRecent(days = dailyDays).forEachIndexed { i, e ->
            out += MemoryHit(
                source = MemoryTier.DAILY,
                key = "${e.role}#$i",
                content = e.content,
                score = 1.0f,
                timestamp = e.timestamp,
            )
        }
        return out.sortedByDescending { it.timestamp }
    }

    /**
     * Phase O-5 — Memory transparency: nuclear wipe of every long-term fact,
     * the daily event log, and the search index. Skills are preserved (those
     * are user-authored). Semantic embeddings are wiped via [wipeSemantic].
     */
    fun wipeAll() {
        longterm.getAll().keys.toList().forEach { longterm.delete(it) }
        daily.oldFiles(olderThanDays = 0).forEach { it.delete() }
        index.clear()
        indexBuilt = true
        Timber.i("MemoryManager: wipeAll — long-term + daily cleared")
    }

    // ─── Context Injection ───────────────────────────────────────────────────

    suspend fun buildContext(userMessage: String): String {
        val sb = StringBuilder()

        val recent = daily.readRecent(days = 2)
        if (recent.isNotEmpty()) {
            sb.appendLine("RECENT ACTIVITY (last 2 days):")
            recent.takeLast(8).forEach { event ->
                val roleLabel = when (event.role) {
                    "user" -> "User"
                    "assistant" -> "Forge"
                    "tool_call" -> "Tool"
                    else -> event.role
                }
                val snippet = event.content.take(120).let {
                    if (event.content.length > 120) "$it…" else it
                }
                sb.appendLine("  [$roleLabel] $snippet")
            }
            sb.appendLine()
        }

        val hits = recall(userMessage, k = 5)
        if (hits.isNotEmpty()) {
            sb.appendLine("RELEVANT MEMORIES:")
            hits.forEach { hit ->
                val tierLabel = when (hit.source) {
                    MemoryTier.LONGTERM -> "Fact"
                    MemoryTier.SKILL -> "Skill"
                    MemoryTier.DAILY -> "Log"
                }
                sb.appendLine("  [$tierLabel:${hit.key}] ${hit.content.take(200)}")
            }
            sb.appendLine()
        }

        val topFacts = longterm.getAll().values
            .sortedByDescending { it.accessCount }
            .take(4)
            .filter { !hits.any { h -> h.key == it.key } }
        if (topFacts.isNotEmpty()) {
            sb.appendLine("PINNED FACTS:")
            topFacts.forEach { f ->
                sb.appendLine("  [${f.key}] ${f.content.take(150)}")
            }
            sb.appendLine()
        }

        // Phase 3 — Explicitly inject recent reflections to ensure learning from errors
        val recentReflections = longterm.filterByTags(listOf("reflection"))
            .sortedByDescending { it.timestamp }
            .take(2)
        if (recentReflections.isNotEmpty()) {
            sb.appendLine("RECENT LESSONS (Self-Reflections on past errors/inefficiencies):")
            recentReflections.forEach { f ->
                sb.appendLine("  • ${f.content.substringAfter("]:").trim().take(300)}")
            }
            sb.appendLine()
        }

        val result = sb.toString().take(MAX_CONTEXT_CHARS)
        return if (result.isBlank()) "" else "MEMORY CONTEXT:\n$result"
    }

    // ─── Maintenance ─────────────────────────────────────────────────────────

    fun wipeSemantic() {
        semantic.deleteAll()
    }

    fun rebuildIndex() {
        index.clear()
        longterm.getAll().forEach { (key, entry) ->
            index.add("lt:$key", "$key: ${entry.content}", MemoryTier.LONGTERM, entry.timestamp)
        }
        skill.getAll().forEach { entry ->
            index.add("sk:${entry.name}", "${entry.name} ${entry.description}", MemoryTier.SKILL, entry.lastUsed)
        }
        daily.readRecent(days = 1).forEachIndexed { i, event ->
            index.add("daily:$i", event.content, MemoryTier.DAILY, event.timestamp)
        }
        index.build()
        indexBuilt = true
        Timber.d("MemoryManager: index rebuilt — ${longterm.getAll().size} facts, ${skill.getAll().size} skills")
    }

    fun fullSummary(): String = buildString {
        appendLine("📦 Memory Summary")
        appendLine(daily.todaySummary())
        appendLine(longterm.summary())
        appendLine(skill.summary())
    }
}
