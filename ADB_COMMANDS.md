# ADB commands for the accessibility service

Commands run from PowerShell. `adb` is not on `PATH`, so it is called from the SDK in `local.properties`:

```powershell
$adb = "K:\Android SDK\Sdk\platform-tools\adb.exe"
```

## Device info

```powershell
& $adb devices -l
& $adb shell getprop ro.build.version.release   # Android version
& $adb shell getprop ro.build.version.sdk       # API level
& $adb shell getprop ro.product.manufacturer
& $adb shell getprop ro.product.model
```

## Diagnose

```powershell
# Is the app installed?
& $adb shell pm path io.github.asaf400.handsfreecontrols

# Package state, including the service resolver table (shows which intent action the service registers)
& $adb shell dumpsys package io.github.asaf400.handsfreecontrols

# Does the system see the service under the correct accessibility action?
# "No services found" here means Android will not list it in accessibility settings.
& $adb shell cmd package query-services -a android.accessibilityservice.AccessibilityService io.github.asaf400.handsfreecontrols

# Restricted settings app-op (sideloaded apps on Android 13+); should be "allow"
& $adb shell appops get io.github.asaf400.handsfreecontrols

# Currently enabled accessibility services, and the global toggle
& $adb shell settings get secure enabled_accessibility_services
& $adb shell settings get secure accessibility_enabled
```

## Build and install

```powershell
.\gradlew.bat assembleDebug
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Enable the service

`enabled_accessibility_services` is a colon-separated list. Setting it replaces the whole list, so append to the existing value instead of overwriting other services (Bitwarden on this device):

```powershell
$ours = "io.github.asaf400.handsfreecontrols/io.github.asaf400.handsfreecontrols.BluetoothButtonService"
$existing = (& $adb shell settings get secure enabled_accessibility_services).Trim()
if (-not $existing -or $existing -eq "null") { $updated = $ours }
elseif ($existing -notlike "*$ours*") { $updated = "$existing`:$ours" }
else { $updated = $existing }

& $adb shell settings put secure enabled_accessibility_services $updated
& $adb shell settings put secure accessibility_enabled 1
```

## Disable the service

Put back the list without our entry, for example:

```powershell
& $adb shell settings put secure enabled_accessibility_services com.x8bit.bitwarden/.Accessibility.AccessibilityService
```

## Vector module for the car hands-free button

The steering-wheel button is not a key event. Vector hooks `HeadsetService.SynchronousStartVoiceRecognitionByHeadset` inside `com.android.bluetooth`. The CLI has to run as root, and `daemon.apk` must be copied somewhere `app_process` can read:

```powershell
$adb = "K:\Android SDK\Sdk\platform-tools\adb.exe"
& $adb shell su -c "cp /data/adb/modules/zygisk_vector/daemon.apk /data/local/tmp/daemon.apk"
$cli = "/system/bin/app_process -Djava.class.path=/data/local/tmp/daemon.apk /system/bin --nice-name=VectorCli org.matrix.vector.daemon.Cli"

& $adb shell su -c "$cli modules ls"
& $adb shell su -c "$cli modules enable io.github.asaf400.handsfreecontrols"
& $adb shell su -c "$cli scope set io.github.asaf400.handsfreecontrols com.android.bluetooth/0"
& $adb shell su -c "$cli scope ls io.github.asaf400.handsfreecontrols"

# The hook loads the next time the Bluetooth process starts.
& $adb shell su -c 'kill $(pidof com.android.bluetooth)'
```

Look for `HandsFreeControls: hooked` in logcat after Bluetooth restarts. A steering-wheel press should then appear in the app as `Car hands-free`.

## Verify

```powershell
& $adb logcat -c
& $adb shell am start -n io.github.asaf400.handsfreecontrols/.MainActivity

# Look for "BluetoothButtonService connected" and "installed in a11y list: true"
& $adb logcat -d -s MainActivity:D BluetoothButtonService:D AndroidRuntime:E

& $adb shell dumpsys accessibility
```
