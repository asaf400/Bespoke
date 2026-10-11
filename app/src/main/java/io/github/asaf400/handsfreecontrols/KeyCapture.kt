package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.content.SharedPreferences

object KeyCapture {
    const val PREFERENCE_KEY = "enabled"
    private const val PREFERENCES = "key_capture"

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(PREFERENCE_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(PREFERENCE_KEY, enabled).apply()
    }

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
