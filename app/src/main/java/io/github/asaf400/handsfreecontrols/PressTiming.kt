package io.github.asaf400.handsfreecontrols

import android.content.Context

object PressTiming {
    const val DEFAULT_WINDOW_MS = 500
    const val DEFAULT_DEBOUNCE_MS = 250
    const val MIN_WINDOW_MS = 100
    const val MAX_WINDOW_MS = 8000
    const val MIN_DEBOUNCE_MS = 0
    const val MAX_DEBOUNCE_MS = 800
    const val STEP_MS = 25

    private const val PREFERENCES = "press_timing"
    private const val WINDOW = "window_ms"
    private const val DEBOUNCE = "debounce_ms"

    fun windowMs(context: Context): Long =
        snap(preferences(context).getInt(WINDOW, DEFAULT_WINDOW_MS), MIN_WINDOW_MS, MAX_WINDOW_MS)

    fun debounceMs(context: Context): Long =
        snap(preferences(context).getInt(DEBOUNCE, DEFAULT_DEBOUNCE_MS), MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS)

    fun setWindowMs(context: Context, milliseconds: Int) {
        preferences(context).edit()
            .putInt(WINDOW, snap(milliseconds, MIN_WINDOW_MS, MAX_WINDOW_MS).toInt())
            .apply()
    }

    fun setDebounceMs(context: Context, milliseconds: Int) {
        preferences(context).edit()
            .putInt(DEBOUNCE, snap(milliseconds, MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS).toInt())
            .apply()
    }

    private fun snap(milliseconds: Int, minimum: Int, maximum: Int): Long {
        val clamped = milliseconds.coerceIn(minimum, maximum)
        val snapped = ((clamped + STEP_MS / 2) / STEP_MS) * STEP_MS
        return snapped.coerceIn(minimum, maximum).toLong()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
