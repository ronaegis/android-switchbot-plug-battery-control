package com.switchbot.batterycharger

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val REQUEST_PERMISSIONS = 1
    private lateinit var prefs: android.content.SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        
        val macEdit = findViewById<EditText>(R.id.mac_edit)
        val lowEdit = findViewById<EditText>(R.id.low_edit)
        val highEdit = findViewById<EditText>(R.id.high_edit)
        val autoStartSwitch = findViewById<Switch>(R.id.auto_start_switch)
        val saveBtn = findViewById<Button>(R.id.save_btn)
        val testOnBtn = findViewById<Button>(R.id.test_on_btn)
        val testOffBtn = findViewById<Button>(R.id.test_off_btn)
        val statusText = findViewById<TextView>(R.id.status_text)
        
        // Load existing settings
        macEdit.setText(prefs.getString("mac", ""))
        lowEdit.setText(prefs.getInt("low", 20).toString())
        highEdit.setText(prefs.getInt("high", 80).toString())
        autoStartSwitch.isChecked = prefs.getBoolean("auto_start", false)
        
        // Update button text based on service status
        updateButtonState(saveBtn)
        
        // Check and request permissions
        checkPermissions()
        
        // Request battery optimization exemption
        requestBatteryOptimizationExemption()
        
        // Auto-start switch listener
        autoStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_start", isChecked).apply()
            val message = if (isChecked) {
                "✅ Auto-start enabled - Service will start automatically at boot"
            } else {
                "❌ Auto-start disabled"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        
        saveBtn.setOnClickListener {
            if (isServiceRunning()) {
                // Stop the service
                stopService(Intent(this, BatteryMonitorService::class.java))
                Toast.makeText(this, "Service stopped. Battery monitoring disabled.", Toast.LENGTH_LONG).show()
                updateButtonState(saveBtn)
            } else {
                // Start the service - validate input first
                val mac = macEdit.text.toString().trim()
                val lowText = lowEdit.text.toString().trim()
                val highText = highEdit.text.toString().trim()
                
                if (mac.isEmpty()) {
                    Toast.makeText(this, "Please enter MAC address", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (!isValidMacAddress(mac)) {
                    Toast.makeText(this, "Invalid MAC address format", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                val low = lowText.toIntOrNull() ?: 20
                val high = highText.toIntOrNull() ?: 80
                
                if (low >= high || low < 0 || high > 100) {
                    Toast.makeText(this, "Invalid thresholds. Low must be < High, and both 0-100%", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Save configuration
                prefs.edit().apply {
                    putString("mac", mac.uppercase())
                    putInt("low", low)
                    putInt("high", high)
                    putBoolean("charging_on", false)
                }.apply()
                
                // Start the battery monitor service
                val serviceIntent = Intent(this, BatteryMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                Toast.makeText(this, "Service started! App will now monitor battery.", Toast.LENGTH_LONG).show()
                updateButtonState(saveBtn)
                finish()
            }
        }
        
        // Test button handlers
        testOnBtn.setOnClickListener {
            val mac = macEdit.text.toString().trim()
            if (mac.isEmpty() || !isValidMacAddress(mac)) {
                Toast.makeText(this, "Please enter a valid MAC address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            statusText.text = "Status: Connecting..."
            testOnBtn.isEnabled = false
            testOffBtn.isEnabled = false
            
            BleManager.sendCommand(this, mac.uppercase(), true) { success ->
                runOnUiThread {
                    if (success) {
                        statusText.text = "Status: ON command sent successfully"
                        Toast.makeText(this, "Plug turned ON", Toast.LENGTH_SHORT).show()
                    } else {
                        statusText.text = "Status: Failed to send ON command"
                        Toast.makeText(this, "Failed to connect or send command", Toast.LENGTH_SHORT).show()
                    }
                    testOnBtn.isEnabled = true
                    testOffBtn.isEnabled = true
                }
            }
        }
        
        testOffBtn.setOnClickListener {
            val mac = macEdit.text.toString().trim()
            if (mac.isEmpty() || !isValidMacAddress(mac)) {
                Toast.makeText(this, "Please enter a valid MAC address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            statusText.text = "Status: Connecting..."
            testOnBtn.isEnabled = false
            testOffBtn.isEnabled = false
            
            BleManager.sendCommand(this, mac.uppercase(), false) { success ->
                runOnUiThread {
                    if (success) {
                        statusText.text = "Status: OFF command sent successfully"
                        Toast.makeText(this, "Plug turned OFF", Toast.LENGTH_SHORT).show()
                    } else {
                        statusText.text = "Status: Failed to send OFF command"
                        Toast.makeText(this, "Failed to connect or send command", Toast.LENGTH_SHORT).show()
                    }
                    testOnBtn.isEnabled = true
                    testOffBtn.isEnabled = true
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Update button state when activity resumes
        val saveBtn = findViewById<Button>(R.id.save_btn)
        val autoStartSwitch = findViewById<Switch>(R.id.auto_start_switch)
        updateButtonState(saveBtn)
        autoStartSwitch.isChecked = prefs.getBoolean("auto_start", false)
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Android 12+ (API 31+) Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        
        // Android 13+ (API 33+) Notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Ignore if the intent is not available
            }
        }
    }
    
    private fun isValidMacAddress(mac: String): Boolean {
        val macPattern = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$".toRegex()
        return macPattern.matches(mac)
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "Some permissions were denied. App may not work correctly.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun isServiceRunning(): Boolean {
        return prefs.getBoolean("service_running", false)
    }
    
    private fun updateButtonState(button: Button) {
        if (isServiceRunning()) {
            button.text = "🛑 Stop Running Service"
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            button.text = getString(R.string.save_and_start)
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_bright))
        }
    }
}