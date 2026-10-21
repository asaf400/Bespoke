package io.github.asaf400.handsfreecontrols

import android.content.Context

object HookStatus {
    const val ACTION = "io.github.asaf400.handsfreecontrols.HOOK_STATUS"
    const val ACTION_UPDATED = "io.github.asaf400.handsfreecontrols.HOOK_STATUS_UPDATED"
    const val EXTRA_PREFS_READABLE = "prefs_readable"

    private const val PREFERENCES = "hook_status"
    private const val SEEN_AT = "seen_at"
    private const val PREFS_READABLE = "prefs_readable"

    fun record(context: Context, prefsReadable: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(SEEN_AT, System.currentTimeMillis())
            .putBoolean(PREFS_READABLE, prefsReadable)
            .apply()
    }

    fun seenAt(context: Context): Long =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(SEEN_AT, 0L)

    fun prefsReadable(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(PREFS_READABLE, false)
}
