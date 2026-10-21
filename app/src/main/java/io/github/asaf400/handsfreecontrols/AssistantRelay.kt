package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object AssistantRelay {
    const val PREFERENCES = "assistant_relay"
    const val PASS_TO_ANDROID = "pass_to_android"
    const val ACTION_CHANGED = "io.github.asaf400.handsfreecontrols.PASS_TO_ANDROID_CHANGED"
    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    fun passesToAndroid(context: Context): Boolean =
        preferences(context).getBoolean(PASS_TO_ANDROID, true)

    fun setPassesToAndroid(context: Context, pass: Boolean) {
        preferences(context).edit().putBoolean(PASS_TO_ANDROID, pass).commit()
        publish(context, pass)
    }

    fun publish(context: Context, pass: Boolean = passesToAndroid(context)) {
        context.sendBroadcast(
            Intent(ACTION_CHANGED)
                .setPackage(BLUETOOTH_PACKAGE)
                .putExtra(PASS_TO_ANDROID, pass)
        )
    }

    @Suppress("DEPRECATION")
    fun preferences(context: Context): SharedPreferences {
        return try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
    }
}
