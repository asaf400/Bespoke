package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    const val ACTION_LOG_EVENT = "io.github.asaf400.handsfreecontrols.LOG_EVENT"
    const val EXTRA_LOG_MESSAGE = "log_message"
    private const val PREFERENCES = "event_log"
    private const val CONTENT = "content"
    private const val MAX_CHARACTERS = 50_000

    fun append(context: Context, message: String) {
        Log.d("Bespoke", message)
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message"
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previousLog = preferences.getString(CONTENT, "").orEmpty()
        preferences.edit()
            .putString(CONTENT, "$line\n$previousLog".take(MAX_CHARACTERS))
            .apply()

        context.sendBroadcast(
            Intent(ACTION_LOG_EVENT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_LOG_MESSAGE, line)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(CONTENT)
            .apply()
    }

    fun get(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(CONTENT, "")
            .orEmpty()
}
