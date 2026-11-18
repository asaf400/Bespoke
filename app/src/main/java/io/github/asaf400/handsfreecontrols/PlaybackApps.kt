package io.github.asaf400.handsfreecontrols

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

object PlaybackApps {
    enum class Kind { BROWSER, BUTTON, SERVICE, LAUNCH }

    data class Target(
        val packageName: String,
        val className: String,
        val label: String,
        val kind: Kind,
    ) {
        val id: String = "${kind.name}|$packageName|$className"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null
    private var generation = 0

    fun targets(context: Context): List<Target> {
        val manager = context.packageManager
        val found = linkedMapOf<String, Target>()
        val browserServices = manager.queryIntentServices(Intent(MEDIA_BROWSER_SERVICE), PackageManager.MATCH_ALL)
        for (resolve in browserServices) {
            val service = resolve.serviceInfo ?: continue
            if (service.packageName in SKIPPED_PACKAGES) continue
            val label = appLabel(manager, service.packageName, resolve.loadLabel(manager).toString())
            found.putIfAbsent(
                service.packageName,
                Target(service.packageName, service.name, label, Kind.BROWSER)
            )
        }
        addComponents(
            context,
            found,
            manager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), PackageManager.MATCH_ALL),
            Kind.BUTTON
        ) { it.activityInfo }
        addComponents(
            context,
            found,
            manager.queryIntentServices(Intent(Intent.ACTION_MEDIA_BUTTON), PackageManager.MATCH_ALL),
            Kind.SERVICE
        ) { it.serviceInfo }
        addLaunchers(context, found, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC))
        addLaunchers(context, found, Intent("android.intent.action.MUSIC_PLAYER"))
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun fromId(id: String): Target? {
        val parts = id.split('|', limit = 3)
        if (parts.size != 3 || parts[1].isBlank()) return null
        val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return null
        return Target(parts[1], parts[2], parts[1], kind)
    }

    fun isPlaybackActive(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.isMusicActive
    }

    /**
     * Starts playback in [target]. A cold music app ignores a single media key sent right after it is
     * launched, so this sends the play command, checks whether audio actually started, and keeps
     * retrying (launching the app first if needed) for a few seconds.
     */
    fun play(context: Context, target: Target) {
        val application = context.applicationContext
        val attempt = ++generation
        handler.removeCallbacksAndMessages(null)
        if (target.kind == Kind.BROWSER) connectAndPlay(application, target)
        sendDirect(application, target)
        handler.postDelayed({ verify(application, target, attempt, 1) }, RETRY_MS)
    }

    private fun verify(context: Context, target: Target, attempt: Int, step: Int) {
        if (attempt != generation) return
        if (isPlaybackActive(context)) {
            EventLog.append(context, "Playback started in ${target.packageName}")
            return
        }
        if (step > MAX_STEPS) {
            EventLog.append(context, "Gave up starting playback in ${target.packageName}")
            return
        }
        if (step == 1) {
            launch(context, target)
        } else {
            sendSystemKey(context)
            sendDirect(context, target)
        }
        handler.postDelayed({ verify(context, target, attempt, step + 1) }, RETRY_MS)
    }

    private fun connectAndPlay(context: Context, target: Target) {
        try {
            browser?.disconnect()
            val component = ComponentName(target.packageName, target.className)
            val connection = object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val connected = browser ?: return
                    try {
                        val controller = MediaController(context, connected.sessionToken)
                        controller.transportControls.play()
                        EventLog.append(context, "Sent play through ${target.packageName} media session")
                    } catch (error: Exception) {
                        EventLog.append(context, "Media session play failed for ${target.packageName}: ${error.message}")
                    }
                    handler.postDelayed({ connected.disconnect() }, 3000)
                }

                override fun onConnectionFailed() {
                    EventLog.append(context, "${target.packageName} refused the media browser connection")
                }
            }
            browser = MediaBrowser(context, component, connection, Bundle()).also { it.connect() }
        } catch (error: Exception) {
            EventLog.append(context, "Could not connect to ${target.packageName}: ${error.message}")
        }
    }

    /** Sends a play key straight to every media button receiver or service the app declares. */
    private fun sendDirect(context: Context, target: Target) {
        val manager = context.packageManager
        val query = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(target.packageName)
        val components = mutableListOf<Pair<ComponentName, Boolean>>()
        runCatching {
            manager.queryBroadcastReceivers(query, PackageManager.MATCH_ALL).forEach { resolve ->
                resolve.activityInfo?.let { components += ComponentName(it.packageName, it.name) to false }
            }
        }
        runCatching {
            manager.queryIntentServices(query, PackageManager.MATCH_ALL).forEach { resolve ->
                resolve.serviceInfo?.let { components += ComponentName(it.packageName, it.name) to true }
            }
        }
        if (components.isEmpty()) {
            components += ComponentName(target.packageName, target.className) to (target.kind == Kind.SERVICE)
        }
        for ((component, isService) in components) {
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                try {
                    val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                        .setComponent(component)
                        .putExtra(Intent.EXTRA_KEY_EVENT, playKey(action))
                    if (isService) context.startService(intent) else context.sendBroadcast(intent)
                } catch (error: Exception) {
                    EventLog.append(context, "Play key to ${component.flattenToShortString()} failed: ${error.message}")
                }
            }
        }
        EventLog.append(context, "Sent play key to ${target.packageName}")
    }

    private fun sendSystemKey(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(playKey(KeyEvent.ACTION_DOWN))
        audio.dispatchMediaKeyEvent(playKey(KeyEvent.ACTION_UP))
        EventLog.append(context, "Sent play key to the active media session")
    }

    private fun launch(context: Context, target: Target) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
                ?: Intent(Intent.ACTION_MAIN).setComponent(ComponentName(target.packageName, target.className))
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            EventLog.append(context, "Opened ${target.packageName} to start playback")
        } catch (error: Exception) {
            EventLog.append(context, "Could not open ${target.packageName}: ${error.message}")
        }
    }

    private fun playKey(action: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
    }

    private fun addComponents(
        context: Context,
        found: MutableMap<String, Target>,
        resolves: List<android.content.pm.ResolveInfo>,
        kind: Kind,
        component: (android.content.pm.ResolveInfo) -> android.content.pm.ComponentInfo?,
    ) {
        val manager = context.packageManager
        for (resolve in resolves) {
            val info = component(resolve) ?: continue
            if (info.packageName in SKIPPED_PACKAGES || found.containsKey(info.packageName)) continue
            val label = appLabel(manager, info.packageName, resolve.loadLabel(manager).toString())
            found[info.packageName] = Target(info.packageName, info.name, label, kind)
        }
    }

    private fun addLaunchers(context: Context, found: MutableMap<String, Target>, intent: Intent) {
        val manager = context.packageManager
        for (resolve in manager.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
            val activity = resolve.activityInfo ?: continue
            if (activity.packageName in SKIPPED_PACKAGES || found.containsKey(activity.packageName)) continue
            val label = appLabel(manager, activity.packageName, resolve.loadLabel(manager).toString())
            found[activity.packageName] = Target(activity.packageName, activity.name, label, Kind.LAUNCH)
        }
    }

    private fun appLabel(manager: PackageManager, packageName: String, fallback: String): String {
        val application = runCatching { manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
        return application?.takeIf { it.isNotBlank() } ?: fallback.ifBlank { packageName }
    }

    private const val RETRY_MS = 1500L
    private const val MAX_STEPS = 5
    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
    private val SKIPPED_PACKAGES = setOf(
        "com.android.bluetooth",
        "com.google.android.googlequicksearchbox",
    )
}
