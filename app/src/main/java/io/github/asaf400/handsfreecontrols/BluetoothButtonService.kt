package io.github.asaf400.handsfreecontrols

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.media.AudioManager
import android.content.Context
import android.util.Log

@SuppressLint("AccessibilityPolicy")
class BluetoothButtonService : AccessibilityService() {

    private var clickCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val clickTimeout = 500L // milliseconds

    private val clickRunnable = Runnable {
        handleClicks(clickCount)
        clickCount = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for key events
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // Intercept KEYCODE_VOICE_ASSIST (usually the hands-free button)
        // or KEYCODE_HEADSETHOOK (common for Bluetooth buttons)
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (action == KeyEvent.ACTION_DOWN) {
                clickCount++
                handler.removeCallbacks(clickRunnable)
                handler.postDelayed(clickRunnable, clickTimeout)
                
                broadcastLog("Button Pressed: $clickCount")
            }
            return true // Consume the event
        }

        return super.onKeyEvent(event)
    }

    private fun handleClicks(count: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        when (count) {
            2 -> {
                broadcastLog("Double Click -> Next Song")
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            3 -> {
                broadcastLog("Triple Click -> Previous Song")
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            4 -> {
                broadcastLog("Quad Click -> Play/Pause")
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            else -> {
                broadcastLog("Click count $count: No action mapped")
            }
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun broadcastLog(message: String) {
        Log.d("BluetoothButtonService", message)
        val intent = Intent(ACTION_LOG_EVENT)
        intent.putExtra(EXTRA_LOG_MESSAGE, message)
        sendBroadcast(intent)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.notificationTimeout = 100
        serviceInfo = info
        Log.d(TAG, "BluetoothButtonService connected")
    }

    companion object {
        private const val TAG = "BluetoothButtonService"
        const val ACTION_LOG_EVENT = "io.github.asaf400.handsfreecontrols.LOG_EVENT"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }
}