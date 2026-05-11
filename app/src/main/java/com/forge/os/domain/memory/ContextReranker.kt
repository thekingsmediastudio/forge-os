package com.forge.os.domain.memory

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

/**
 * Phase O-5 — Context Reranker.
 *
 * Implements a weighted scoring algorithm to rank memory hits.
 * Combines:
 *  1. Keyword Score (TF-IDF) - exact word overlap.
 *  2. Semantic Score (Embeddings) - underlying meaning.
 *  3. Recency Score - favor newer events.
 *  4. Importance Score - favor frequently accessed facts.
 */
@Singleton
class ContextReranker @Inject constructor() {

    data class RerankItem(
        val hit: MemoryHit,
        val keywordScore: Float = 0f,
        val semanticScore: Float = 0f,
        val importance: Int = 0
    )

    fun rerank(
        query: String,
        items: List<RerankItem>,
        k: Int = 5,
        threshold: Float = 0.25f
    ): List<MemoryHit> {
        if (items.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val maxImportance = items.maxOfOrNull { it.importance }?.coerceAtLeast(1) ?: 1

        val scored = items.map { item ->
            val keywordPart = item.keywordScore * 0.20f
            val semanticPart = item.semanticScore * 0.60f
            
            // Recency: 1.0 for now, decays to 0 over 30 days
            val ageDays = (now - item.hit.timestamp).toDouble() / (1000 * 60 * 60 * 24)
            val recencyPart = (1.0 / (1.0 + ageDays / 7.0)).toFloat() * 0.10f
            
            // Importance: log-normalized access count
            val importancePart = (ln(1.0 + item.importance) / ln(1.0 + maxImportance)).toFloat() * 0.10f
            
            val totalScore = (keywordPart + semanticPart + recencyPart + importancePart)
                .coerceIn(0f, 1f)
            
            item.hit.copy(score = totalScore)
        }

        return scored
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(k)
    }

    /**
     * Helper to merge hits from different indexes by key.
     */
    fun mergeHits(
        keywordHits: List<MemoryHit>,
        semanticHits: List<MemoryHit>,
        importanceMap: Map<String, Int>
    ): List<RerankItem> {
        val allKeys = (keywordHits.map { it.key } + semanticHits.map { it.key }).toSet()
        
        return allKeys.map { key ->
            val kHit = keywordHits.find { it.key == key }
            val sHit = semanticHits.find { it.key == key }
            val baseHit = sHit ?: kHit!! // One must exist
            
            RerankItem(
                hit = baseHit,
                keywordScore = kHit?.score ?: 0f,
                semanticScore = sHit?.score ?: 0f,
                importance = importanceMap[key] ?: 0
            )
        }
    }
}
