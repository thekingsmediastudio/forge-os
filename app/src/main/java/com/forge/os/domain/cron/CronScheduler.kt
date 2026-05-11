package com.forge.os.domain.cron

import java.util.Calendar
import java.util.regex.Pattern

/**
 * Pure-function helpers for parsing natural-language schedule expressions
 * and computing next-run timestamps.
 */
object CronScheduler {

    private const val MIN_INTERVAL_MINUTES = 5

    private val INTERVAL_RE = Pattern.compile("""every\s+(\d+)\s*(m|min|minute|h|hour|d|day)s?""", Pattern.CASE_INSENSITIVE)
    private val DAILY_RE    = Pattern.compile("""(?:every\s+day\s+at|daily\s+at|at)\s+(\d{1,2}):(\d{2})""", Pattern.CASE_INSENSITIVE)
    private val EVERY_HOUR  = Pattern.compile("""every\s+hour""", Pattern.CASE_INSENSITIVE)
    private val EVERY_DAY   = Pattern.compile("""every\s+day""", Pattern.CASE_INSENSITIVE)

    /**
     * Parse a free-text schedule expression into a CronSchedule.
     * Returns null if no pattern matches.
     *
     * Call [diagnose] to get a human-readable reason when this returns null.
     */
    fun parse(text: String): CronSchedule? {
        val trimmed = text.trim()

        DAILY_RE.matcher(trimmed).takeIf { it.find() }?.let {
            val h = it.group(1)!!.toInt()
            val m = it.group(2)!!.toInt()
            if (h !in 0..23 || m !in 0..59) return null
            val hh = h.toString().padStart(2, '0')
            val mm = m.toString().padStart(2, '0')
            return CronSchedule(dailyAt = "$hh:$mm", description = "daily at $hh:$mm")
        }

        INTERVAL_RE.matcher(trimmed).takeIf { it.find() }?.let {
            val n = it.group(1)!!.toInt()
            val unit = it.group(2)!!.lowercase()
            val minutes = when (unit.first()) {
                'm' -> n
                'h' -> n * 60
                'd' -> n * 60 * 24
                else -> return null
            }
            if (minutes < MIN_INTERVAL_MINUTES) return null
            return CronSchedule(intervalMinutes = minutes, description = "every ${minutes}m")
        }

        if (EVERY_HOUR.matcher(trimmed).find()) {
            return CronSchedule(intervalMinutes = 60, description = "every hour")
        }
        if (EVERY_DAY.matcher(trimmed).find()) {
            return CronSchedule(intervalMinutes = 60 * 24, description = "every day")
        }

        return null
    }

    /**
     * Returns a human-readable explanation of why [text] could not be parsed,
     * to be surfaced in error messages when [parse] returns null.
     */
    fun diagnose(text: String): String {
        val trimmed = text.trim()

        // Check if the interval pattern matched but the value was too small
        INTERVAL_RE.matcher(trimmed).takeIf { it.find() }?.let {
            val n = it.group(1)!!.toInt()
            val unit = it.group(2)!!.lowercase()
            val minutes = when (unit.first()) {
                'm' -> n
                'h' -> n * 60
                'd' -> n * 60 * 24
                else -> return@let
            }
            if (minutes < MIN_INTERVAL_MINUTES) {
                return "Interval '$trimmed' is too short (minimum is $MIN_INTERVAL_MINUTES minutes)."
            }
        }

        // Check if daily pattern matched but time was invalid
        DAILY_RE.matcher(trimmed).takeIf { it.find() }?.let {
            val h = it.group(1)!!.toInt()
            val m = it.group(2)!!.toInt()
            if (h !in 0..23 || m !in 0..59) {
                return "Invalid time '$h:$m' — hours must be 0–23, minutes 0–59."
            }
        }

        return "Unrecognised schedule format '$trimmed'."
    }

    /**
     * Compute the next epoch-ms timestamp at which a job should run,
     * given its schedule and the time of its previous run (or null = never).
     */
    fun nextRun(schedule: CronSchedule, lastRun: Long?, now: Long = System.currentTimeMillis()): Long {
        return when {
            schedule.oneShotAt != null -> schedule.oneShotAt

            schedule.intervalMinutes != null -> {
                val intervalMs = schedule.intervalMinutes * 60_000L
                val base = lastRun ?: now
                val candidate = base + intervalMs
                if (candidate <= now) now + intervalMs else candidate
            }

            schedule.dailyAt != null -> {
                val (hh, mm) = schedule.dailyAt.split(":").map { it.toInt() }
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, hh)
                    set(Calendar.MINUTE, mm)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }

            else -> now + 60_000L  // fallback: 1 minute from now
        }
    }

    /**
     * Returns true if the job is due to run right now (with a small grace window).
     */
    fun isDue(job: CronJob, now: Long = System.currentTimeMillis(), graceMs: Long = 60_000L): Boolean {
        if (!job.enabled) return false
        return job.nextRunAt <= now + graceMs
    }
}
