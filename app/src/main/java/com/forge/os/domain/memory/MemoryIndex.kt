package com.forge.os.domain.memory

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * On-device TF-IDF vector index for semantic memory recall.
 * No external embeddings needed — runs entirely on-device.
 *
 * Builds a bag-of-words TF-IDF model over all indexed documents,
 * then scores queries using cosine similarity.
 *
 * Thread-safe: Uses CopyOnWriteArrayList to prevent ConcurrentModificationException
 * when multiple threads access the index simultaneously.
 */
class MemoryIndex {

    data class Document(
        val id: String,
        val text: String,
        val tier: MemoryTier,
        val timestamp: Long
    )

    // Thread-safe list to prevent ConcurrentModificationException
    private val documents = CopyOnWriteArrayList<Document>()
    private val idf = mutableMapOf<String, Double>()
    @Volatile private var built = false

    fun add(id: String, text: String, tier: MemoryTier, timestamp: Long) {
        documents.removeIf { it.id == id }
        documents.add(Document(id, text, tier, timestamp))
        built = false
    }

    fun remove(id: String) {
        documents.removeIf { it.id == id }
        built = false
    }

    fun clear() {
        documents.clear()
        idf.clear()
        built = false
    }

    /** Rebuild IDF table from current document set. O(D*T) where D=docs, T=terms. 
     * Synchronized to prevent concurrent builds. */
    @Synchronized
    fun build() {
        if (documents.isEmpty()) { built = true; return }
        val n = documents.size.toDouble()
        val dfCounts = mutableMapOf<String, Int>()
        documents.forEach { doc ->
            tokenize(doc.text).toSet().forEach { term ->
                dfCounts[term] = (dfCounts[term] ?: 0) + 1
            }
        }
        idf.clear()
        dfCounts.forEach { (term, df) ->
            idf[term] = ln((n + 1) / (df + 1)) + 1  // smooth IDF
        }
        built = true
    }

    /**
     * Query the index and return top-[k] hits scored by TF-IDF cosine similarity.
     * Automatically rebuilds if the index is stale.
     */
    fun query(queryText: String, k: Int = 5): List<Pair<Document, Float>> {
        if (!built) build()
        if (documents.isEmpty()) return emptyList()

        val queryVec = tfidfVector(queryText)
        val queryNorm = norm(queryVec)
        if (queryNorm == 0.0) return emptyList()

        return documents
            .map { doc ->
                val docVec = tfidfVector(doc.text)
                val score = cosine(queryVec, queryNorm, docVec).toFloat()
                doc to score
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(k)
    }

    private fun tfidfVector(text: String): Map<String, Double> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyMap()
        val tf = mutableMapOf<String, Double>()
        tokens.forEach { tf[it] = (tf[it] ?: 0.0) + 1.0 }
        val maxTf = tf.values.maxOrNull() ?: 1.0
        return tf.mapValues { (term, count) ->
            (count / maxTf) * (idf[term] ?: 0.0)
        }
    }

    private fun cosine(a: Map<String, Double>, normA: Double, b: Map<String, Double>): Double {
        val dot = a.entries.sumOf { (term, va) -> va * (b[term] ?: 0.0) }
        val normB = norm(b)
        return if (normB == 0.0) 0.0 else dot / (normA * normB)
    }

    private fun norm(vec: Map<String, Double>) = sqrt(vec.values.sumOf { it * it })

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "was", "you", "this", "that",
            "with", "have", "from", "not", "but", "they", "will", "can",
            "all", "been", "has", "its", "had", "him", "his", "her",
            "she", "our", "out", "one", "into", "than", "did", "get",
            "may", "use", "each", "how", "now", "also", "any"
        )
    }
}
