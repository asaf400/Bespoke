# Bespoke

Bespoke remaps the hands-free button on a car or Bluetooth headset. One, two, and three presses can each send a media key. Each device keeps its own actions, wait, debounce, and choice of whether Android's assistant also receives the press.

The button is the Bluetooth voice-recognition signal, not a keyboard event. Bespoke captures it with a [Vector](https://github.com/JingMatrix/Vector) hook inside `com.android.bluetooth`. Vector (or another LSPosed build with the new XSharedPreferences API, `xposedminversion` 93) has to be installed, and this module has to be scoped to `com.android.bluetooth`. After installing or updating the module, restart Bluetooth so the hook loads.

The first time a device presses the button, Bespoke remembers it. The installed app also starts with one seeded car profile so the list is not empty.

## License

Bespoke is free software under the [GNU General Public License v3.0](LICENSE). You can use, change, and share it, and distributed changes must stay under the same license.

