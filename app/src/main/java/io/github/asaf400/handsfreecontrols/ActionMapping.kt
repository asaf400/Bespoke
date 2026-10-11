package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.view.KeyEvent

object ActionMapping {
    private const val PREFERENCES = "action_mapping"
    private const val NO_ACTION = -1

    fun keyCodeFor(context: Context, pressCount: Int): Int? {
        val stored = preferences(context).getInt(key(pressCount), defaultKeyCode(pressCount))
        return stored.takeIf { it != NO_ACTION }
    }

    fun set(context: Context, pressCount: Int, keyCode: Int?) {
        preferences(context).edit().putInt(key(pressCount), keyCode ?: NO_ACTION).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun key(pressCount: Int) = "press_$pressCount"

    private fun defaultKeyCode(pressCount: Int): Int = when (pressCount) {
        2 -> KeyEvent.KEYCODE_MEDIA_NEXT
        3 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> NO_ACTION
    }
}
