package com.switchbot.batterycharger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
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
        val saveBtn = findViewById<Button>(R.id.save_btn)
        
        // Load existing settings
        macEdit.setText(prefs.getString("mac", ""))
        lowEdit.setText(prefs.getInt("low", 20).toString())
        highEdit.setText(prefs.getInt("high", 80).toString())
        
        // Check and request permissions
        checkPermissions()
        
        // Request battery optimization exemption
        requestBatteryOptimizationExemption()
        
        saveBtn.setOnClickListener {
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
            finish()
        }
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
}