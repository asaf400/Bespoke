package io.github.asaf400.handsfreecontrols

import android.accessibilityservice.AccessibilityService
    import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(BluetoothButtonService.EXTRA_LOG_MESSAGE)
            if (message != null) {
                appendLog(message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tv_event_log)
        tvLog.text = BluetoothButtonService.getStoredLog(this)
        val btnEnable = findViewById<Button>(R.id.btn_enable_accessibility)

        val keyCaptureSwitch = findViewById<SwitchMaterial>(R.id.switch_key_capture)
        keyCaptureSwitch.isChecked = KeyCapture.isEnabled(this)
        keyCaptureSwitch.setOnCheckedChangeListener { _, isChecked ->
            KeyCapture.setEnabled(this, isChecked)
        }

        val passToAndroid = findViewById<SwitchMaterial>(R.id.switch_pass_to_android)
        passToAndroid.isChecked = AssistantRelay.passesToAndroid(this)
        passToAndroid.setOnCheckedChangeListener { _, isChecked ->
            AssistantRelay.setPassesToAndroid(this, isChecked)
        }

        setupActionSpinner(R.id.spinner_single, pressCount = 1)
        setupActionSpinner(R.id.spinner_double, pressCount = 2)
        setupActionSpinner(R.id.spinner_triple, pressCount = 3)

        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            EventLog.clear(this)
            tvLog.text = ""
        }

        btnEnable.setOnClickListener {
            val component = ComponentName(this, BluetoothButtonService::class.java)
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val filter = IntentFilter(BluetoothButtonService.ACTION_LOG_EVENT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        logAccessibilityServiceState()
    }

    private fun setupActionSpinner(spinnerId: Int, pressCount: Int) {
        val spinner = findViewById<Spinner>(spinnerId)
        val labels = MediaActions.all.map { getString(it.labelRes) }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selected = MediaActions.all.indexOfFirst {
            it.keyCode == ActionMapping.keyCodeFor(this, pressCount)
        }.coerceAtLeast(0)
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                ActionMapping.set(this@MainActivity, pressCount, MediaActions.all[position].keyCode)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun logAccessibilityServiceState() {
        val expected = ComponentName(this, BluetoothButtonService::class.java)
        try {
            val serviceInfo = packageManager.getServiceInfo(expected, PackageManager.GET_META_DATA)
            Log.d(TAG, "getServiceInfo succeeded: enabled=${serviceInfo.enabled}, exported=${serviceInfo.exported}")
        } catch (e: Exception) {
            Log.e(TAG, "getServiceInfo failed", e)
        }

        val flags = PackageManager.GET_META_DATA or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        val pmServices = packageManager.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE),
            flags
        )
        Log.d(TAG, "PackageManager accessibility services (${pmServices.size}):")
        pmServices.forEach { resolveInfo ->
            val service = resolveInfo.serviceInfo
            Log.d(TAG, "  ${service.packageName}/${service.name}")
        }

        val accessibilityManager = getSystemService(AccessibilityManager::class.java)
        val installed = accessibilityManager.installedAccessibilityServiceList
        Log.d(TAG, "AccessibilityManager installed services (${installed.size}):")
        installed.forEach { info ->
            val service = info.resolveInfo.serviceInfo
            Log.d(TAG, "  ${service.packageName}/${service.name}")
        }

        val isInstalled = installed.any {
            val service = it.resolveInfo.serviceInfo
            service.packageName == expected.packageName && service.name == expected.className
        }
        Log.d(TAG, "Hands Free Controls installed in a11y list: $isInstalled")
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }

    private fun appendLog(message: String) {
        val currentText = tvLog.text.toString()
        val newLog = "$message\n$currentText"
        tvLog.text = newLog
    }
}