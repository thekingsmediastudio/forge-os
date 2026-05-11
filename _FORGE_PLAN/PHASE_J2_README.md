# Phase J2 — Semantic facts + embeddings

**Drop:** `forge-os-j2.zip` (overlays cleanly on the J1 source tree)
**Milestone:** Part 3 / M4
**Status:** shipped — see `CURRENT_STATUS.md` "What just landed (Phase J2)"

## What this phase delivers

A real vector search layer over Tier-2 long-term facts, with a graceful
local-only fallback so the feature is *always* available even with no API key
or no network.

- New persisted vector index at `workspace/memory/longterm/embeddings.json`
  (parallel to existing fact JSON, no Room migration).
- New agent tool `semantic_recall_facts(query, k=5)` with cosine-similarity
  ranking. Enabled by default.
- New FACTS-tab `LEX | SEM` toggle in the Memory screen, with `sim 0.xx`
  scores rendered on each result card.
- Two embedding spaces, never mixed:
  - **remote** — speaks the OpenAI `/embeddings` schema, routes via
    `modelRouting.routingRules[taskType="memory_embedding"]` (default
    `OPENAI / text-embedding-3-small`). Works against any OpenAI-compatible
    provider already wired in Phase A.
  - **local** — deterministic 256-dim hashed-trigram bag-of-features. No
    network, no model download, no permissions.

## Files

Added (1):
- `app/src/main/java/com/forge/os/domain/memory/SemanticFactIndex.kt`

Edited (6 + DI):
- `data/api/AiApiManager.kt` — `embed`, `embeddingModelLabel`,
  `resolveEmbeddingSpec`.
- `domain/memory/MemoryManager.kt` — `semanticRecallFacts`, `forgetFact`,
  `wipeSemantic`; `store()` invalidates the slot on content change.
- `domain/agent/ToolRegistry.kt` — `semantic_recall_facts` dispatch + tool
  spec.
- `domain/config/ForgeConfig.kt` — added to default `enabledTools`.
- `presentation/screens/memory/MemoryViewModel.kt` — `SearchMode`,
  `factScores`, async re-rank.
- `presentation/screens/memory/MemoryScreen.kt` — LEX/SEM toggle, score chip.
- `di/AppModule.kt` — `SemanticFactIndex` provider, `MemoryManager` ctor.

## Smoke test

1. Open **Memory → FACTS**, add 3–4 facts that share a *topic* but no shared
   words (e.g. "I have a peanut allergy", "Skip pad thai when ordering",
   "Always carry epi-pen").
2. Tap the `LEX` chip → it flips to `SEM`.
3. Type `nut allergy precautions`. Lexical mode would return nothing; SEM
   mode should return all three with descending `sim` scores.
4. From Chat, ask the agent: *"What do you know about my food restrictions?"* —
   expect it to call `semantic_recall_facts` and quote the same facts.
5. Memory → kebab → **Wipe all** → confirm `embeddings.json` is also gone
   (`workspace/memory/longterm/`).

## Trade-offs

- Remote and local spaces are isolated. Cold-start without a key uses local;
  switching to a key warms remote slots opportunistically as facts are
  touched (write or recall hit). A bulk "re-embed all" UI is intentionally
  deferred to Phase M.
- The local fallback finds near-duplicates and obvious topical clusters
  reliably, but it's not a real model — paraphrase recall is weaker.
- No vector quantisation. 10k facts ≈ 30 MB JSON; revisit when we hit the
  first user with that many.

## What's next

`PART3_PLAN.md` — Phase L (proactive check-ins). Phase J2 hands Phase L a
working semantic-recall surface so check-ins can pull thematically related
facts ("you mentioned X last week").
