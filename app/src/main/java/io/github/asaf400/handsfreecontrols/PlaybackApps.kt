package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
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

    private var browser: MediaBrowser? = null

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

    fun play(context: Context, target: Target) {
        val application = context.applicationContext
        try {
            if (target.kind != Kind.BROWSER) {
                sendPlay(application, target)
                return
            }
            browser?.disconnect()
            val component = android.content.ComponentName(target.packageName, target.className)
            val connection = object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val connected = browser ?: return
                    val controller = MediaController(application, connected.sessionToken)
                    controller.transportControls.play()
                    EventLog.append(application, "Started playback in ${target.packageName}")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connected.disconnect()
                    }, 1500)
                }

                override fun onConnectionFailed() {
                    EventLog.append(application, "Could not attach to ${target.packageName}; sending its play command")
                    sendPlay(application, target)
                }
            }
            browser = MediaBrowser(application, component, connection, Bundle()).also { it.connect() }
        } catch (error: Exception) {
            EventLog.append(application, "Could not start ${target.packageName}: ${error.message}")
            sendPlay(application, target)
        }
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

    private fun sendPlay(context: Context, target: Target) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
        val component = android.content.ComponentName(target.packageName, target.className)
        try {
            if (target.kind == Kind.SERVICE) {
                deliver(context, component, down, service = true)
                deliver(context, component, up, service = true)
                EventLog.append(context, "Sent play to ${target.packageName}")
                return
            }
            if (target.kind == Kind.BUTTON) {
                deliver(context, component, down, service = false)
                deliver(context, component, up, service = false)
                EventLog.append(context, "Sent play to ${target.packageName}")
                return
            }
        } catch (error: Exception) {
            EventLog.append(context, "Direct play failed for ${target.packageName}: ${error.message}")
        }
        openAndPressPlay(context, target)
    }

    private fun deliver(context: Context, component: android.content.ComponentName, event: KeyEvent, service: Boolean) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(component)
            .putExtra(Intent.EXTRA_KEY_EVENT, event)
        if (service) context.startService(intent) else context.sendBroadcast(intent)
    }

    private fun openAndPressPlay(context: Context, target: Target) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(target.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(target.packageName)
                    .putExtra(Intent.EXTRA_KEY_EVENT, down)
            )
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(target.packageName)
                    .putExtra(Intent.EXTRA_KEY_EVENT, up)
            )
            EventLog.append(context, "Opened ${target.packageName} and sent play")
        } catch (error: Exception) {
            EventLog.append(context, "Could not open ${target.packageName}: ${error.message}")
        }
    }

    private fun appLabel(manager: PackageManager, packageName: String, fallback: String): String {
        val application = runCatching { manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
        return application?.takeIf { it.isNotBlank() } ?: fallback.ifBlank { packageName }
    }

    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
    private val SKIPPED_PACKAGES = setOf(
        "com.android.bluetooth",
        "com.google.android.googlequicksearchbox",
    )
}
