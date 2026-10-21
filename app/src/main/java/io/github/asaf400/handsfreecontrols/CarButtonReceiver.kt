package io.github.asaf400.handsfreecontrols

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CarButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        HandsFreePresses.press(context, address, name)
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
    }
}
