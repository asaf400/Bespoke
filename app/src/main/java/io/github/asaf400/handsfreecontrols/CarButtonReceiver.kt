package io.github.asaf400.handsfreecontrols

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CarButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val source = if (device.isEmpty()) "Car hands-free" else "Car hands-free ($device)"
        HandsFreePresses.press(context, source)
    }

    companion object {
        const val EXTRA_DEVICE = "device"
    }
}
