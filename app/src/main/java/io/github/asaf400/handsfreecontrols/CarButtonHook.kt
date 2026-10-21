package io.github.asaf400.handsfreecontrols

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class CarButtonHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != BLUETOOTH_PACKAGE) return
        try {
            val service = XposedHelpers.findClassIfExists(HEADSET_SERVICE, lpparam.classLoader)
            if (service == null) {
                XposedBridge.log("$TAG HeadsetService not found")
                return
            }
            val hooked = XposedBridge.hookAllMethods(
                service,
                "SynchronousStartVoiceRecognitionByHeadset",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        headsetService = param.thisObject
                        val (address, name) = deviceIdentity(param.args?.getOrNull(0))
                        pendingAddress = address
                        XposedBridge.log("$TAG car hands-free from $address, pass=${passesToAndroid(address)}")
                        notifyApp(address, name)
                    }
                }
            )
            val assistantHooked = hookAssistantLaunch(lpparam.classLoader)
            hookVoiceCommandLaunch(lpparam.classLoader)
            rememberHeadsetService(service)
            registerPassReceiver()
            watchPreferences()
            XposedBridge.log(
                "$TAG hooked ${hooked.size} headset voice methods, assistant gate=$assistantHooked"
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hook failed: $t")
        }
    }

    private fun hookAssistantLaunch(classLoader: ClassLoader): Int {
        val systemInterface = XposedHelpers.findClassIfExists(HEADSET_SYSTEM_INTERFACE, classLoader)
            ?: return 0
        val hooked = XposedBridge.hookAllMethods(
            systemInterface,
            "activateVoiceRecognition",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (passesToAndroid(pendingAddress)) {
                        XposedBridge.log("$TAG allowing assistant launch")
                        return
                    }
                    XposedBridge.log("$TAG blocked assistant launch")
                    // False means voice recognition never started, so the headset
                    // stack does not hold the button for its 5 second timeout.
                    param.setResult(false)
                }
            }
        )
        return hooked.size
    }

    private fun hookVoiceCommandLaunch(classLoader: ClassLoader) {
        try {
            hookVoiceCommandLaunchOnce(classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG voice-command hook failed: $t")
        }
    }

    private fun hookVoiceCommandLaunchOnce(classLoader: ClassLoader) {
        val contextImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader)
            ?: XposedHelpers.findClassIfExists("android.app.ContextImpl", null)
            ?: return
        XposedBridge.hookAllMethods(
            contextImpl,
            "startActivity",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args?.firstOrNull { it is Intent } as? Intent ?: return
                    val action = intent.action ?: return
                    if (action != Intent.ACTION_VOICE_COMMAND && action != Intent.ACTION_ASSIST) return
                    if (passesToAndroid(pendingAddress)) return
                    XposedBridge.log("$TAG blocked startActivity $action")
                    param.setResult(null)
                }
            }
        )
    }

    private fun rememberHeadsetService(serviceClass: Class<*>) {
        XposedBridge.hookAllMethods(
            serviceClass,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    headsetService = param.thisObject
                }
            }
        )
    }

    private fun registerPassReceiver() {
        Handler(Looper.getMainLooper()).post {
            if (receiverRegistered) return@post
            val context = applicationContext() ?: return@post
            context.registerReceiver(
                passReceiver,
                IntentFilter(DeviceProfiles.ACTION_CHANGED),
                Context.RECEIVER_EXPORTED
            )
            receiverRegistered = true
            XposedBridge.log("$TAG listening for assistant toggle")
        }
    }

    private val passReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val address = intent.getStringExtra(DeviceProfiles.EXTRA_ADDRESS)?.trim()?.uppercase()
            if (address.isNullOrEmpty() || !intent.hasExtra(DeviceProfiles.PASS_TO_ANDROID)) return
            val pass = intent.getBooleanExtra(DeviceProfiles.PASS_TO_ANDROID, true)
            passOverrides[address] = pass
            refreshPreferences()
            XposedBridge.log("$TAG assistant toggle for $address is now pass=$pass, readable=$prefsReadable")
            if (!pass) stopActiveVoiceRecognition()
            reportStatus()
        }
    }

    private fun stopActiveVoiceRecognition() {
        val service = headsetService ?: return
        try {
            val activeDevice = XposedHelpers.getObjectField(service, "mActiveDevice")
            XposedHelpers.callMethod(service, "stopVoiceRecognition", activeDevice)
            XposedBridge.log("$TAG stopped the active voice-recognition session")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG could not stop voice recognition: $t")
        }
    }

    private fun refreshPreferences() {
        val preferences = sharedPreferences ?: return
        try {
            val file = XposedHelpers.callMethod(preferences, "getFile") as? File
            prefsReadable = file?.canRead() == true
            if (!prefsReadable) return
            preferences.reload()
            if (preferenceListener != null) return
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                preferences.reload()
                passOverrides.clear()
                XposedBridge.log("$TAG preference file changed")
                reportStatus()
            }
            preferenceListener = listener
            XposedHelpers.callMethod(
                preferences,
                "registerOnSharedPreferenceChangeListener",
                listener
            )
        } catch (t: Throwable) {
            prefsReadable = false
            XposedBridge.log("$TAG could not read assistant preferences: $t")
        }
    }

    private fun watchPreferences() {
        Handler(Looper.getMainLooper()).post {
            if (sharedPreferences != null) return@post
            try {
                sharedPreferences = XSharedPreferences(APP_PACKAGE, DeviceProfiles.PREFERENCES)
                refreshPreferences()
                XposedBridge.log("$TAG preferences readable=$prefsReadable")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG could not watch assistant preferences: $t")
                prefsReadable = false
            }
            reportStatus()
        }
    }

    private fun passesToAndroid(address: String?): Boolean {
        val normalized = address?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) return true
        passOverrides[normalized]?.let { return it }
        val defaultPass = !normalized.equals(DeviceProfiles.SEEDED_ADDRESS, ignoreCase = true)
        val preferences = sharedPreferences ?: return defaultPass
        return try {
            preferences.reload()
            preferences.getBoolean(DeviceProfiles.passKey(normalized), defaultPass)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG could not read assistant toggle: $t")
            defaultPass
        }
    }

    private fun deviceIdentity(device: Any?): Pair<String, String> {
        if (device == null) return "" to ""
        return try {
            val address = (XposedHelpers.callMethod(device, "getAddress") as? String)
                ?.trim()
                ?.uppercase()
                .orEmpty()
            val alias = try {
                XposedHelpers.callMethod(device, "getAlias") as? String
            } catch (_: Throwable) {
                null
            }
            val name = alias?.takeIf { it.isNotBlank() }
                ?: (XposedHelpers.callMethod(device, "getName") as? String).orEmpty()
            address to name
        } catch (t: Throwable) {
            XposedBridge.log("$TAG could not read device identity: $t")
            "" to ""
        }
    }

    private fun reportStatus() {
        val context = applicationContext() ?: return
        try {
            context.sendBroadcast(
                Intent()
                    .setComponent(ComponentName(APP_PACKAGE, "$APP_PACKAGE.HookStatusReceiver"))
                    .putExtra(HookStatus.EXTRA_PREFS_READABLE, prefsReadable)
                    .addFlags(FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG status report failed: $t")
        }
    }

    private fun applicationContext(): Context? {
        return try {
            Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null) as? Context
        } catch (t: Throwable) {
            XposedBridge.log("$TAG no application context: $t")
            null
        }
    }

    private fun notifyApp(address: String, name: String) {
        try {
            val context = applicationContext() ?: return
            context.sendBroadcast(
                Intent()
                    .setComponent(
                        ComponentName(APP_PACKAGE, "$APP_PACKAGE.CarButtonReceiver")
                    )
                    .putExtra(CarButtonReceiver.EXTRA_ADDRESS, address)
                    .putExtra(CarButtonReceiver.EXTRA_NAME, name)
                    .addFlags(FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG notify failed: $t")
        }
    }

    companion object {
        private const val TAG = "Bespoke"
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val HEADSET_SERVICE = "com.android.bluetooth.hfp.HeadsetService"
        private const val HEADSET_SYSTEM_INTERFACE = "com.android.bluetooth.hfp.HeadsetSystemInterface"
        private const val APP_PACKAGE = "io.github.asaf400.handsfreecontrols"
        private const val FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020

        @Volatile
        private var pendingAddress: String? = null

        @Volatile
        private var prefsReadable = false

        @Volatile
        private var headsetService: Any? = null

        private val passOverrides = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private var sharedPreferences: XSharedPreferences? = null
        private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        private var receiverRegistered = false
    }
}
