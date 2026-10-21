package io.github.asaf400.handsfreecontrols

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HookStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HookStatus.record(
            context,
            intent.getBooleanExtra(HookStatus.EXTRA_PREFS_READABLE, false)
        )
        context.sendBroadcast(
            Intent(HookStatus.ACTION_UPDATED).setPackage(context.packageName)
        )
    }
}
