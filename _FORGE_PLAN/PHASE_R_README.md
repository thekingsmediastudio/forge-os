# Phase R — Cron Session Viewer

**Snapshot:** post-Phase O addition (2026-04-23)

A dedicated **Cron Session** screen that complements the existing CRON list,
giving the user a richer, log-style window into scheduled-job activity.

## What's new

### Files added
- `presentation/screens/cron/CronSessionScreen.kt`
- `presentation/screens/cron/CronSessionViewModel.kt`
- `_FORGE_PLAN/PHASE_R_README.md` (this file)

### Files edited
- `domain/cron/CronRepository.kt` — `clearAllHistory()` and `exportHistoryAsJson()`
- `presentation/MainActivity.kt` — new `cronSession` route, wired from CRON
- `presentation/screens/cron/CronScreen.kt` — `onOpenSession` parameter and a
  `SESSION` button in the top-bar actions

## Capabilities

The Cron Session screen is reached from CRON → SESSION. It surfaces:

1. **Session stats header** — total runs, success rate %, average duration,
   last-24-hour count, total failures, jobs currently due, active job count.
2. **Live tail toggle** — when on, the screen auto-refreshes every ~3 seconds
   so newly finished executions appear without manual reload.
3. **Status filter chips** — ALL / OK / FAIL.
4. **Search box** — matches job name, output text, or error message
   (case-insensitive).
5. **Per-job filter** — horizontally scrollable chip row of every active job;
   tap a chip to scope the feed to that job, tap "all" to reset.
6. **Live execution feed** — each row shows status glyph, job name, timestamp,
   duration, and (on failure) the error summary. Failures get a red border.
7. **Output dialog** — tap any row to see the full untruncated output (up to
   the 4 KB cap CronManager already enforces per record), with **COPY** to
   clipboard and **RUN AGAIN** to re-fire the same job.
8. **Clear history** — two-step confirmation; deletes every per-day JSONL file
   under `workspace/cron/history/`. Active scheduled jobs are not affected.
9. **Export** — writes the last 30 days (≤ 1000 records) as a pretty-printed
   JSON array to `workspace/cron/exports/cron-history-<ts>.json`. The toast
   confirms the workspace path so the user can open it in the file viewer.

## Data flow

```
CronSessionViewModel
  ├── reads CronRepository.recentHistory(days=14, limit=500)
  ├── reads CronManager.listJobs() for job-filter chip names + due/active counts
  ├── derives stats and filtered list in-memory
  ├── runJobAgain → CronManager.runJob(job)
  ├── clearHistory  → CronRepository.clearAllHistory()
  └── exportHistory → CronRepository.exportHistoryAsJson()
```

No new singletons, no new permissions, no new background workers. The existing
`CronExecutionWorker` continues to populate history files; this screen only
reads, summarises, and offers replay/export/cleanup actions.
