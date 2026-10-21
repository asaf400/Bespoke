package io.github.asaf400.handsfreecontrols

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var logPanel: View
    private lateinit var xposedStatus: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var profileName: EditText
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var passSwitch: SwitchMaterial
    private lateinit var showLogSwitch: SwitchMaterial
    private var bindingProfile = false
    private var selectedAddress: String? = null

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(EventLog.EXTRA_LOG_MESSAGE) ?: return
            appendLog(message)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showHookStatus()
        }
    }

    private val devicesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val address = intent?.getStringExtra(DeviceProfiles.EXTRA_ADDRESS)
            showDevices(address)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarPadding()
        DeviceProfiles.ensureSeeded(this)

        tvLog = findViewById(R.id.tv_event_log)
        logPanel = findViewById(R.id.event_log_panel)
        tvLog.text = EventLog.get(this)
        xposedStatus = findViewById(R.id.xposed_status)
        showHookStatus()

        deviceSpinner = findViewById(R.id.spinner_device)
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = deviceAdapter
        profileName = findViewById(R.id.profile_name)
        profileName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) selectedAddress?.let { commitProfileName(it) }
        }
        profileName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            selectedAddress?.let { commitProfileName(it) }
            profileName.clearFocus()
            true
        }
        deviceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (bindingProfile) return
                val next = DeviceProfiles.profiles(this@MainActivity).getOrNull(position)?.address
                val previous = selectedAddress
                if (previous != null && previous != next) commitProfileName(previous)
                selectedAddress = next
                selectedAddress?.let {
                    rememberDevice(it)
                    applyProfile(it)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        passSwitch = findViewById(R.id.switch_pass_to_android)
        passSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingProfile) return@setOnCheckedChangeListener
            selectedAddress?.let { DeviceProfiles.setPassToAndroid(this, it, isChecked) }
        }

        setupActionSpinner(R.id.spinner_single, pressCount = 1)
        setupActionSpinner(R.id.spinner_double, pressCount = 2)
        setupActionSpinner(R.id.spinner_triple, pressCount = 3)
        setupTimingSeekBar(
            R.id.seek_press_window,
            R.id.label_press_window,
            R.string.press_window_label,
            PressTiming.MIN_WINDOW_MS,
            PressTiming.MAX_WINDOW_MS,
            DeviceProfiles::setWindowMs
        )
        setupTimingSeekBar(
            R.id.seek_debounce,
            R.id.label_debounce,
            R.string.debounce_label,
            PressTiming.MIN_DEBOUNCE_MS,
            PressTiming.MAX_DEBOUNCE_MS,
            DeviceProfiles::setDebounceMs
        )

        showLogSwitch = findViewById(R.id.switch_show_log)
        val showLog = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).getBoolean(SHOW_LOG, false)
        showLogSwitch.isChecked = showLog
        setLogShown(showLog)
        showLogSwitch.setOnCheckedChangeListener { _, isChecked ->
            setLogShown(isChecked)
            getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).edit().putBoolean(SHOW_LOG, isChecked).apply()
        }

        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            EventLog.clear(this)
            tvLog.text = ""
        }

        val filter = IntentFilter(EventLog.ACTION_LOG_EVENT)
        val statusFilter = IntentFilter(HookStatus.ACTION_UPDATED)
        val devicesFilter = IntentFilter(DeviceProfiles.ACTION_DEVICES_UPDATED)
        registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(devicesReceiver, devicesFilter, Context.RECEIVER_NOT_EXPORTED)

        showDevices(rememberedDevice())
        DeviceProfiles.publishAll(this)
    }

    override fun onPause() {
        selectedAddress?.let { commitProfileName(it) }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        showHookStatus()
        showDevices(selectedAddress)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
        unregisterReceiver(statusReceiver)
        unregisterReceiver(devicesReceiver)
    }

    private fun showDevices(selectAddress: String?) {
        val profiles = DeviceProfiles.profiles(this)
        val names = profiles.map { it.name }
        bindingProfile = true
        deviceAdapter.clear()
        deviceAdapter.addAll(names)
        val wanted = selectAddress ?: rememberedDevice()
        val index = profiles.indexOfFirst { it.address == wanted }.coerceAtLeast(0)
        deviceSpinner.setSelection(index)
        selectedAddress = profiles.getOrNull(index)?.address
        bindingProfile = false
        selectedAddress?.let {
            rememberDevice(it)
            applyProfile(it)
        }
    }

    private fun applyProfile(address: String) {
        val profile = DeviceProfiles.profile(this, address)
        bindingProfile = true
        profileName.setText(profile.name)
        passSwitch.isChecked = profile.passToAndroid
        selectAction(R.id.spinner_single, profile.single)
        selectAction(R.id.spinner_double, profile.doublePress)
        selectAction(R.id.spinner_triple, profile.triple)
        showTiming(R.id.seek_press_window, R.id.label_press_window, R.string.press_window_label, PressTiming.MIN_WINDOW_MS, profile.windowMs)
        showTiming(R.id.seek_debounce, R.id.label_debounce, R.string.debounce_label, PressTiming.MIN_DEBOUNCE_MS, profile.debounceMs)
        bindingProfile = false
    }

    private fun commitProfileName(address: String) {
        if (bindingProfile) return
        val entered = profileName.text.toString().trim()
        if (entered.isEmpty()) {
            profileName.setText(DeviceProfiles.profile(this, address).name)
            return
        }
        val current = DeviceProfiles.profile(this, address).name
        if (entered == current) return
        DeviceProfiles.setName(this, address, entered)
        val position = deviceSpinner.selectedItemPosition
        bindingProfile = true
        deviceAdapter.clear()
        deviceAdapter.addAll(DeviceProfiles.profiles(this).map { it.name })
        if (position >= 0) deviceSpinner.setSelection(position)
        bindingProfile = false
    }

    private fun selectAction(spinnerId: Int, keyCode: Int) {
        val spinner = findViewById<Spinner>(spinnerId)
        val index = MediaActions.all.indexOfFirst { it.keyCode == keyCode.takeIf { code -> code != ActionMapping.NO_ACTION } }
            .coerceAtLeast(0)
        spinner.setSelection(index)
    }

    private fun showTiming(seekBarId: Int, labelId: Int, labelRes: Int, minimumMs: Int, valueMs: Int) {
        val seekBar = findViewById<SeekBar>(seekBarId)
        val step = ((valueMs - minimumMs) / PressTiming.STEP_MS).coerceIn(0, seekBar.max)
        seekBar.progress = step
        findViewById<TextView>(labelId).text = getString(labelRes, minimumMs + step * PressTiming.STEP_MS)
    }

    private fun setupActionSpinner(spinnerId: Int, pressCount: Int) {
        val spinner = findViewById<Spinner>(spinnerId)
        val labels = MediaActions.all.map { getString(it.labelRes) }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (bindingProfile) return
                val address = selectedAddress ?: return
                DeviceProfiles.setPress(this@MainActivity, address, pressCount, MediaActions.all[position].keyCode)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupTimingSeekBar(
        seekBarId: Int,
        labelId: Int,
        labelRes: Int,
        minimumMs: Int,
        maximumMs: Int,
        save: (Context, String, Int) -> Unit
    ) {
        val label = findViewById<TextView>(labelId)
        val seekBar = findViewById<SeekBar>(seekBarId)
        val stepCount = (maximumMs - minimumMs) / PressTiming.STEP_MS
        fun milliseconds(step: Int) = minimumMs + step * PressTiming.STEP_MS
        seekBar.min = 0
        seekBar.max = stepCount
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val milliseconds = milliseconds(progress)
                label.text = getString(labelRes, milliseconds)
                if (!fromUser || bindingProfile) return
                selectedAddress?.let { save(this@MainActivity, it, milliseconds) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setLogShown(shown: Boolean) {
        logPanel.visibility = if (shown) View.VISIBLE else View.GONE
    }

    private fun rememberDevice(address: String) {
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).edit().putString(SELECTED_DEVICE, address).apply()
    }

    private fun rememberedDevice(): String? =
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).getString(SELECTED_DEVICE, DeviceProfiles.SEEDED_ADDRESS)

    private fun applySystemBarPadding() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showHookStatus() {
        val seen = HookStatus.seenAt(this) > 0L
        xposedStatus.text = when {
            !seen -> getString(R.string.xposed_status_waiting)
            HookStatus.prefsReadable(this) -> getString(R.string.xposed_status_active)
            else -> getString(R.string.xposed_status_unreadable)
        }
    }

    private fun appendLog(message: String) {
        val currentText = tvLog.text.toString()
        tvLog.text = "$message\n$currentText"
    }

    companion object {
        private const val UI_PREFERENCES = "ui"
        private const val SHOW_LOG = "show_log"
        private const val SELECTED_DEVICE = "selected_device"
    }
}
