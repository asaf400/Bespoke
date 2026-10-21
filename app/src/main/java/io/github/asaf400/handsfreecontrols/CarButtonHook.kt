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
                        val device = param.args?.getOrNull(0)?.toString() ?: "unknown"
                        XposedBridge.log("$TAG car hands-free from $device, pass=${passesToAndroid()}")
                        notifyApp(device)
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
                    if (passesToAndroid()) {
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
                    if (passesToAndroid()) return
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
                IntentFilter(AssistantRelay.ACTION_CHANGED),
                Context.RECEIVER_EXPORTED
            )
            receiverRegistered = true
            XposedBridge.log("$TAG listening for assistant toggle, pass=$passToAndroid")
        }
    }

    private val passReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.hasExtra(AssistantRelay.PASS_TO_ANDROID)) return
            val pass = intent.getBooleanExtra(AssistantRelay.PASS_TO_ANDROID, true)
            passToAndroid = pass
            refreshPreferences()
            XposedBridge.log("$TAG assistant toggle is now pass=$passToAndroid, readable=$prefsReadable")
            if (passToAndroid == false) stopActiveVoiceRecognition()
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
            passToAndroid = preferences.getBoolean(AssistantRelay.PASS_TO_ANDROID, true)
            if (preferenceListener != null) return
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                preferences.reload()
                passToAndroid = preferences.getBoolean(AssistantRelay.PASS_TO_ANDROID, true)
                XposedBridge.log("$TAG preference file changed, pass=$passToAndroid")
                if (passToAndroid == false) stopActiveVoiceRecognition()
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
                sharedPreferences = XSharedPreferences(APP_PACKAGE, AssistantRelay.PREFERENCES)
                refreshPreferences()
                XposedBridge.log("$TAG preferences readable=$prefsReadable, pass=$passToAndroid")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG could not watch assistant preferences: $t")
                prefsReadable = false
            }
            reportStatus()
        }
    }

    private fun passesToAndroid(): Boolean {
        passToAndroid?.let { return it }
        val preferences = sharedPreferences ?: return true
        return try {
            preferences.reload()
            val pass = preferences.getBoolean(AssistantRelay.PASS_TO_ANDROID, true)
            passToAndroid = pass
            pass
        } catch (t: Throwable) {
            XposedBridge.log("$TAG could not read assistant toggle: $t")
            true
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

    private fun notifyApp(device: String) {
        try {
            val context = applicationContext() ?: return
            context.sendBroadcast(
                Intent()
                    .setComponent(
                        ComponentName(APP_PACKAGE, "$APP_PACKAGE.CarButtonReceiver")
                    )
                    .putExtra(CarButtonReceiver.EXTRA_DEVICE, device)
                    .addFlags(FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG notify failed: $t")
        }
    }

    companion object {
        private const val TAG = "HandsFreeControls"
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val HEADSET_SERVICE = "com.android.bluetooth.hfp.HeadsetService"
        private const val HEADSET_SYSTEM_INTERFACE = "com.android.bluetooth.hfp.HeadsetSystemInterface"
        private const val APP_PACKAGE = "io.github.asaf400.handsfreecontrols"
        private const val FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020

        @Volatile
        private var passToAndroid: Boolean? = null

        @Volatile
        private var prefsReadable = false

        @Volatile
        private var headsetService: Any? = null

        private var sharedPreferences: XSharedPreferences? = null
        private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        private var receiverRegistered = false
    }
}
