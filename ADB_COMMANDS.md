# ADB commands

Commands run from PowerShell. `adb` is not on `PATH`, so it is called from the SDK in `local.properties`:

```powershell
$adb = "K:\Android SDK\Sdk\platform-tools\adb.exe"
```

## Device info

```powershell
& $adb devices -l
& $adb shell getprop ro.build.version.release
& $adb shell getprop ro.build.version.sdk
& $adb shell getprop ro.product.manufacturer
& $adb shell getprop ro.product.model
```

## Build and install

```powershell
.\gradlew.bat assembleDebug
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Vector module

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

Look for `Bespoke: hooked` in logcat after Bluetooth restarts. A steering-wheel press should then appear in the app under that device's name.

## Verify

```powershell
& $adb logcat -c
& $adb shell am start -n io.github.asaf400.handsfreecontrols/.MainActivity
& $adb logcat -d -s Bespoke:D AndroidRuntime:E
```
