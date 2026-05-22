package com.forge.os.domain.alarms

import java.util.*

object AlarmSchedulerLogic {

    /**
     * Calculates the next time an alarm should fire based on selected days and time of day.
     * @param from Epoch millis to start searching from (usually now).
     * @param daysOfWeek 1=Mon, ..., 7=Sun.
     * @param timeOfDay HH:mm format.
     */
    fun nextOccurrence(from: Long, daysOfWeek: List<Int>, timeOfDay: String): Long? {
        if (daysOfWeek.isEmpty()) return null
        
        val parts = timeOfDay.split(":")
        if (parts.size != 2) return null
        val targetHour = parts[0].toIntOrNull() ?: return null
        val targetMin = parts[1].toIntOrNull() ?: return null

        val calendar = Calendar.getInstance().apply { timeInMillis = from }
        
        // Map our 1=Mon...7=Sun to Calendar's values (1=Sun, 2=Mon...7=Sat)
        // Forge Mon-Sun: 1, 2, 3, 4, 5, 6, 7
        // Calendar:      2, 3, 4, 5, 6, 7, 1 (SUNDAY is 1)
        val calendarDays = daysOfWeek.map { 
            if (it == 7) Calendar.SUNDAY else it + 1
        }.toSet()

        // Try today and the next 7 days
        for (i in 0..7) {
            val candidate = (Calendar.getInstance().apply {
                timeInMillis = from
                add(Calendar.DAY_OF_YEAR, i)
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, targetMin)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            })

            // If it's today, was the time already passed?
            if (i == 0 && candidate.timeInMillis <= from) continue

            // Check if this day is selected
            if (calendarDays.contains(candidate.get(Calendar.DAY_OF_WEEK))) {
                return candidate.timeInMillis
            }
        }
        
        return null
    }
}
