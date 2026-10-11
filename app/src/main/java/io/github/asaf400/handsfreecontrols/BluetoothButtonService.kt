package io.github.asaf400.handsfreecontrols

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

@SuppressLint("AccessibilityPolicy")
class BluetoothButtonService : AccessibilityService() {

    private val keyCaptureListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KeyCapture.PREFERENCE_KEY) applyKeyCapture()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for key events
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!KeyCapture.isEnabled(this)) return super.onKeyEvent(event)

        val keyCode = event.keyCode
        val action = event.action
        val actionName = when (action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> action.toString()
        }

        EventLog.append(
            this,
            "Key $actionName: " +
                "${KeyEvent.keyCodeToString(keyCode)}, device=${event.deviceId}, repeat=${event.repeatCount}"
        )

        // Different head units and Bluetooth profiles use different key codes.
        val isHandsFreeTrigger = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == KeyEvent.KEYCODE_CALL

        if (isHandsFreeTrigger) {
            if (action == KeyEvent.ACTION_DOWN) {
                HandsFreePresses.press(this, "Hands-free key")
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeyCapture.preferences(this).registerOnSharedPreferenceChangeListener(keyCaptureListener)
        applyKeyCapture()
    }

    override fun onDestroy() {
        KeyCapture.preferences(this).unregisterOnSharedPreferenceChangeListener(keyCaptureListener)
        super.onDestroy()
    }

    private fun applyKeyCapture() {
        val info = serviceInfo ?: return
        val captureKeys = KeyCapture.isEnabled(this)
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = if (captureKeys) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        info.notificationTimeout = 100
        serviceInfo = info
    }

    companion object {
        const val ACTION_LOG_EVENT = EventLog.ACTION_LOG_EVENT
        const val EXTRA_LOG_MESSAGE = EventLog.EXTRA_LOG_MESSAGE

        fun getStoredLog(context: Context): String = EventLog.get(context)
    }
}