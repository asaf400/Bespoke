package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

object HandsFreePresses {
    private val handler = Handler(Looper.getMainLooper())
    private var clickCount = 0
    private var lastPressElapsed = 0L
    private var appContext: Context? = null

    private val finishRunnable = Runnable {
        val context = appContext ?: return@Runnable
        val count = clickCount
        clickCount = 0
        handleClicks(context, count)
    }

    fun press(context: Context, source: String) {
        val application = context.applicationContext
        handler.post {
            appContext = application
            val now = SystemClock.elapsedRealtime()
            if (now - lastPressElapsed < PressTiming.debounceMs(application)) return@post
            lastPressElapsed = now
            clickCount++
            EventLog.append(application, "$source press: $clickCount")
            handler.removeCallbacks(finishRunnable)
            handler.postDelayed(finishRunnable, PressTiming.windowMs(application))
        }
    }

    private fun handleClicks(context: Context, count: Int) {
        val pressName = when (count) {
            1 -> "Single"
            2 -> "Double"
            3 -> "Triple"
            else -> "$count"
        }
        val keyCode = if (count in 1..3) ActionMapping.keyCodeFor(context, count) else null
        if (keyCode == null) {
            EventLog.append(context, "$pressName press: no action")
            return
        }
        val action = MediaActions.find(keyCode)
        EventLog.append(context, "$pressName press -> ${context.getString(action.labelRes)}")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dispatchMediaKey(audioManager, keyCode)
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
