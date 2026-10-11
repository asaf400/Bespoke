package io.github.asaf400.handsfreecontrols

import android.content.Context

object AssistantRelay {
    const val PREFERENCES = "assistant_relay"
    const val PASS_TO_ANDROID = "pass_to_android"

    fun passesToAndroid(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(PASS_TO_ANDROID, true)

    fun setPassesToAndroid(context: Context, pass: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PASS_TO_ANDROID, pass)
            .commit()
    }
}
