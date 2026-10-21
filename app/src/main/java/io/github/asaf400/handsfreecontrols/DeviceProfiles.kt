package io.github.asaf400.handsfreecontrols

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent

object DeviceProfiles {
    const val PREFERENCES = "device_profiles"
    const val ACTION_CHANGED = "io.github.asaf400.handsfreecontrols.PASS_TO_ANDROID_CHANGED"
    const val ACTION_DEVICES_UPDATED = "io.github.asaf400.handsfreecontrols.DEVICES_UPDATED"
    const val EXTRA_ADDRESS = "address"
    const val PASS_TO_ANDROID = "pass_to_android"
    const val SEEDED_ADDRESS = "9C:28:BF:0E:38:38"
    const val SEEDED_NAME = "Citroen C5 RDRFJ 007"
    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    private const val ADDRESSES = "addresses"
    private const val MIGRATED = "migrated_legacy"
    private const val SEEDED_WINDOW_MS = 900
    private const val SEEDED_DEBOUNCE_MS = 25

    data class Profile(
        val address: String,
        val name: String,
        val single: Int,
        val doublePress: Int,
        val triple: Int,
        val windowMs: Int,
        val debounceMs: Int,
        val passToAndroid: Boolean,
    )

    fun profiles(context: Context): List<Profile> {
        ensureSeeded(context)
        return addresses(preferences(context)).map { read(preferences(context), it) }
    }

    fun profile(context: Context, address: String): Profile {
        ensureSeeded(context)
        return read(preferences(context), normalize(address))
    }

    fun keyCodeFor(context: Context, address: String, pressCount: Int): Int? {
        val stored = when (pressCount) {
            1 -> profile(context, address).single
            2 -> profile(context, address).doublePress
            3 -> profile(context, address).triple
            else -> ActionMapping.NO_ACTION
        }
        return stored.takeIf { it != ActionMapping.NO_ACTION }
    }

    fun setPress(context: Context, address: String, pressCount: Int, keyCode: Int?) {
        preferences(context).edit()
            .putInt(pressKey(address, pressCount), keyCode ?: ActionMapping.NO_ACTION)
            .commit()
    }

    fun setWindowMs(context: Context, address: String, milliseconds: Int) {
        preferences(context).edit()
            .putInt(windowKey(address), milliseconds.coerceIn(PressTiming.MIN_WINDOW_MS, PressTiming.MAX_WINDOW_MS))
            .commit()
    }

    fun setDebounceMs(context: Context, address: String, milliseconds: Int) {
        preferences(context).edit()
            .putInt(
                debounceKey(address),
                milliseconds.coerceIn(PressTiming.MIN_DEBOUNCE_MS, PressTiming.MAX_DEBOUNCE_MS)
            )
            .commit()
    }

    fun setName(context: Context, address: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        preferences(context).edit().putString(nameKey(normalize(address)), trimmed).commit()
    }

    fun setPassToAndroid(context: Context, address: String, pass: Boolean) {
        preferences(context).edit().putBoolean(passKey(address), pass).commit()
        publish(context, address)
    }

    fun publishAll(context: Context) {
        profiles(context).forEach { publish(context, it.address) }
    }

    fun remember(context: Context, address: String, reportedName: String): Boolean {
        ensureSeeded(context)
        val normalized = normalize(address)
        if (normalized.isEmpty()) return false
        val prefs = preferences(context)
        val known = addresses(prefs)
        val editor = prefs.edit()
        val created = normalized !in known
        if (created) {
            editor.putString(ADDRESSES, (known + normalized).joinToString("\n"))
            write(editor, normalized, reportedName.ifBlank { normalized }, newDevice())
        } else {
            val current = prefs.getString(nameKey(normalized), "").orEmpty()
            val incoming = reportedName.trim()
            if (incoming.isNotEmpty() && (current.isBlank() || current.equals(normalized, ignoreCase = true))) {
                editor.putString(nameKey(normalized), incoming)
            }
        }
        editor.commit()
        if (created) {
            context.sendBroadcast(
                Intent(ACTION_DEVICES_UPDATED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_ADDRESS, normalized)
            )
            publish(context, normalized)
        }
        return created
    }

    fun passKey(address: String) = "pass.${normalize(address)}"

    fun ensureSeeded(context: Context) {
        val prefs = preferences(context)
        if (prefs.getBoolean(MIGRATED, false)) return
        val editor = prefs.edit()
        editor.putString(ADDRESSES, SEEDED_ADDRESS)
        write(editor, SEEDED_ADDRESS, SEEDED_NAME, legacyOrSeed(context))
        editor.putBoolean(MIGRATED, true)
        editor.commit()
    }

    private fun publish(context: Context, address: String) {
        val normalized = normalize(address)
        val pass = preferences(context).getBoolean(passKey(normalized), defaultPass(normalized))
        context.sendBroadcast(
            Intent(ACTION_CHANGED)
                .setPackage(BLUETOOTH_PACKAGE)
                .putExtra(EXTRA_ADDRESS, normalized)
                .putExtra(PASS_TO_ANDROID, pass)
        )
    }

    private fun legacyOrSeed(context: Context): StoredProfile {
        val actions = context.getSharedPreferences("action_mapping", Context.MODE_PRIVATE)
        val timing = context.getSharedPreferences("press_timing", Context.MODE_PRIVATE)
        val assistant = legacyAssistant(context)
        val hasLegacy = actions.contains("press_1") ||
            timing.contains("window_ms") ||
            assistant.contains("pass_to_android")
        if (!hasLegacy) return seeded()
        return StoredProfile(
            single = actions.getInt("press_1", KeyEvent.KEYCODE_MEDIA_NEXT),
            doublePress = actions.getInt("press_2", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            triple = actions.getInt("press_3", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            windowMs = timing.getInt("window_ms", SEEDED_WINDOW_MS),
            debounceMs = timing.getInt("debounce_ms", SEEDED_DEBOUNCE_MS),
            passToAndroid = assistant.getBoolean("pass_to_android", false),
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyAssistant(context: Context): SharedPreferences {
        return try {
            context.getSharedPreferences("assistant_relay", Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences("assistant_relay", Context.MODE_PRIVATE)
        }
    }

    private fun read(prefs: SharedPreferences, address: String): Profile {
        val normalized = normalize(address)
        val fallback = if (normalized == SEEDED_ADDRESS) seeded() else newDevice()
        val values = StoredProfile(
            single = prefs.getInt(pressKey(normalized, 1), fallback.single),
            doublePress = prefs.getInt(pressKey(normalized, 2), fallback.doublePress),
            triple = prefs.getInt(pressKey(normalized, 3), fallback.triple),
            windowMs = prefs.getInt(windowKey(normalized), fallback.windowMs),
            debounceMs = prefs.getInt(debounceKey(normalized), fallback.debounceMs),
            passToAndroid = prefs.getBoolean(passKey(normalized), fallback.passToAndroid),
        )
        return Profile(
            address = normalized,
            name = prefs.getString(nameKey(normalized), null)
                ?: if (normalized == SEEDED_ADDRESS) SEEDED_NAME else normalized,
            single = values.single,
            doublePress = values.doublePress,
            triple = values.triple,
            windowMs = values.windowMs,
            debounceMs = values.debounceMs,
            passToAndroid = values.passToAndroid,
        )
    }

    private fun write(editor: SharedPreferences.Editor, address: String, name: String, values: StoredProfile) {
        val normalized = normalize(address)
        editor.putString(nameKey(normalized), name.ifBlank { normalized })
        editor.putInt(pressKey(normalized, 1), values.single)
        editor.putInt(pressKey(normalized, 2), values.doublePress)
        editor.putInt(pressKey(normalized, 3), values.triple)
        editor.putInt(windowKey(normalized), values.windowMs)
        editor.putInt(debounceKey(normalized), values.debounceMs)
        editor.putBoolean(passKey(normalized), values.passToAndroid)
    }

    private fun addresses(prefs: SharedPreferences): List<String> =
        prefs.getString(ADDRESSES, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun seeded() = StoredProfile(
        single = KeyEvent.KEYCODE_MEDIA_NEXT,
        doublePress = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        triple = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        windowMs = SEEDED_WINDOW_MS,
        debounceMs = SEEDED_DEBOUNCE_MS,
        passToAndroid = false,
    )

    private fun newDevice() = StoredProfile(
        single = ActionMapping.defaultKeyCode(1),
        doublePress = ActionMapping.defaultKeyCode(2),
        triple = ActionMapping.defaultKeyCode(3),
        windowMs = PressTiming.DEFAULT_WINDOW_MS,
        debounceMs = PressTiming.DEFAULT_DEBOUNCE_MS,
        passToAndroid = true,
    )

    private fun defaultPass(address: String) = address != SEEDED_ADDRESS

    private fun normalize(address: String) = address.trim().uppercase()

    private fun nameKey(address: String) = "name.$address"

    private fun pressKey(address: String, pressCount: Int) = "press$pressCount.$address"

    private fun windowKey(address: String) = "window.$address"

    private fun debounceKey(address: String) = "debounce.$address"

    @Suppress("DEPRECATION")
    private fun preferences(context: Context): SharedPreferences {
        return try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
    }

    private data class StoredProfile(
        val single: Int,
        val doublePress: Int,
        val triple: Int,
        val windowMs: Int,
        val debounceMs: Int,
        val passToAndroid: Boolean,
    )
}
