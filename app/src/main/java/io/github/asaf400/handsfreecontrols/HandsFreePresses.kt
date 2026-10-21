package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

object HandsFreePresses {
    private val handler = Handler(Looper.getMainLooper())
    private val sequences = mutableMapOf<String, Sequence>()

    fun press(context: Context, address: String, reportedName: String) {
        val application = context.applicationContext
        val normalized = address.trim().uppercase()
        if (normalized.isEmpty()) return
        handler.post {
            DeviceProfiles.remember(application, normalized, reportedName)
            val sequence = sequences.getOrPut(normalized) { Sequence() }
            val now = SystemClock.elapsedRealtime()
            val debounce = DeviceProfiles.profile(application, normalized).debounceMs
            if (now - sequence.lastElapsed < debounce) return@post
            sequence.lastElapsed = now
            sequence.count++
            val source = DeviceProfiles.profile(application, normalized).name
            EventLog.append(application, "$source press: ${sequence.count}")
            handler.removeCallbacks(sequence.finish)
            sequence.finish = Runnable {
                val count = sequence.count
                sequence.count = 0
                handleClicks(application, normalized, count)
            }
            handler.postDelayed(sequence.finish, DeviceProfiles.profile(application, normalized).windowMs.toLong())
        }
    }

    private fun handleClicks(context: Context, address: String, count: Int) {
        val pressName = when (count) {
            1 -> "Single"
            2 -> "Double"
            3 -> "Triple"
            else -> "$count"
        }
        val keyCode = if (count in 1..3) DeviceProfiles.keyCodeFor(context, address, count) else null
        val source = DeviceProfiles.profile(context, address).name
        if (keyCode == null) {
            EventLog.append(context, "$source $pressName press: no action")
            return
        }
        val action = MediaActions.find(keyCode)
        EventLog.append(context, "$source $pressName press -> ${context.getString(action.labelRes)}")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dispatchMediaKey(audioManager, keyCode)
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private class Sequence {
        var count = 0
        var lastElapsed = 0L
        var finish: Runnable = Runnable { }
    }
}
