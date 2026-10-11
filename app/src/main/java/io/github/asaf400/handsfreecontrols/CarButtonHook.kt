package io.github.asaf400.handsfreecontrols

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
                        val device = param.args?.getOrNull(0)?.toString() ?: "unknown"
                        XposedBridge.log("$TAG car hands-free from $device")
                        notifyApp(device)
                    }
                }
            )
            val assistantHooked = hookAssistantLaunch(lpparam.classLoader)
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
                    if (passesToAndroid()) return
                    XposedBridge.log("$TAG blocked assistant launch")
                    param.setResult(true)
                }
            }
        )
        return hooked.size
    }

    private fun passesToAndroid(): Boolean {
        return try {
            val preferences = XSharedPreferences(APP_PACKAGE, AssistantRelay.PREFERENCES)
            preferences.reload()
            preferences.getBoolean(AssistantRelay.PASS_TO_ANDROID, true)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG could not read assistant toggle: $t")
            true
        }
    }

    private fun notifyApp(device: String) {
        try {
            val context = Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null) as? Context
            if (context == null) {
                XposedBridge.log("$TAG no application context")
                return
            }
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
    }
}
