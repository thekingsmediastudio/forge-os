package com.forge.os.domain.companion

/**
 * Phase H — top-level conversational mode.
 *
 *  AGENT     = original Forge OS behavior (precise, security-conscious, tool-using).
 *  COMPANION = friend-mode persona, warmer tone, listening-first.
 *
 * Same models, same memory substrate; only the system prompt and (later) the
 * router/safety pipeline differ.
 */
enum class Mode { AGENT, COMPANION, SUMMARIZATION, SYSTEM, PLANNER, REFLECTION, VISION, REASONING }
