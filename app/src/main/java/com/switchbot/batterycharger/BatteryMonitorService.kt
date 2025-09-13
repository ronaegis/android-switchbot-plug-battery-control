package com.switchbot.batterycharger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class BatteryMonitorService : Service() {
    private val TAG = "BatteryMonitorService"
    private val CHANNEL_ID = "battery_charger"
    private val NOTIFICATION_ID = 1
    
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var receiver: BatteryReceiver
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        createNotificationChannel()
        receiver = BatteryReceiver()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        // Mark service as running
        prefs.edit().putBoolean("service_running", true).apply()
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Register battery change receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(receiver, filter)
        
        // Set initial plug state based on current battery level
        setInitialPlugState()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        
        // Mark service as stopped
        prefs.edit().putBoolean("service_running", false).apply()
        
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SwitchBot Battery Monitor",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows battery monitoring status and charging control"
                setShowBadge(true)
                enableLights(true)
                enableVibration(false)
                setSound(null, null) // Silent but visible
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val mac = prefs.getString("mac", "Not configured") ?: "Not configured"
        val shortMac = if (mac.length > 8) "...${mac.takeLast(8)}" else mac
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔋 SwitchBot Battery Monitor ACTIVE")
            .setContentText("Monitoring battery for Smart Plug $shortMac")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSound(null) // Silent but visible
            .build()
    }
    
    private fun setInitialPlugState() {
        Log.d(TAG, "Setting initial plug state based on current battery level")
        
        // Get current battery level
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryStatus == null) {
            Log.w(TAG, "Could not get battery status for initial state")
            return
        }
        
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = (level.toFloat() / scale.toFloat() * 100).toInt()
        
        val low = prefs.getInt("low", 20)
        val high = prefs.getInt("high", 80)
        val mac = prefs.getString("mac", "")
        
        if (mac.isNullOrEmpty()) {
            Log.w(TAG, "MAC address not configured, skipping initial plug state")
            return
        }
        
        Log.i(TAG, "Initial battery level: $batteryPct%, thresholds: $low%-$high%")
        
        // Determine and set initial plug state based on battery level (always send command)
        val shouldBeOn = batteryPct <= low
        
        Log.i(TAG, "Initial plug should be: ${if (shouldBeOn) "ON" else "OFF"} (battery: $batteryPct%)")
        
        // Always send command to ensure plug is in correct state
        Log.i(TAG, "Setting initial plug state to ${if (shouldBeOn) "ON" else "OFF"}")
        BleManager.sendCommand(this, mac, shouldBeOn) { success ->
            if (success) {
                prefs.edit().putBoolean("charging_on", shouldBeOn).apply()
                Log.i(TAG, "Initial plug state set successfully to ${if (shouldBeOn) "ON" else "OFF"}")
            } else {
                Log.e(TAG, "Failed to set initial plug state")
            }
        }
        
        // Update notification with initial status
        updateNotification(batteryPct, shouldBeOn)
    }
    
    inner class BatteryReceiver : BroadcastReceiver() {
        private var lastLevel = -1
        
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = (level.toFloat() / scale.toFloat() * 100).toInt()
                
                if (batteryPct == lastLevel) return // Avoid duplicate processing
                lastLevel = batteryPct
                
                val low = prefs.getInt("low", 20)
                val high = prefs.getInt("high", 80)
                val mac = prefs.getString("mac", "") ?: return
                val isChargingOn = prefs.getBoolean("charging_on", false)
                
                Log.d(TAG, "Battery level: $batteryPct%, charging: $isChargingOn, low: $low%, high: $high%")
                
                if (mac.isEmpty()) {
                    Log.w(TAG, "MAC address not configured")
                    return
                }
                
                // Send commands based on battery thresholds (ignore current plug state)
                when {
                    batteryPct <= low -> {
                        Log.i(TAG, "Battery low ($batteryPct% <= $low%), turning ON charger")
                        BleManager.sendCommand(context!!, mac, true) { success ->
                            if (success) {
                                prefs.edit().putBoolean("charging_on", true).apply()
                                Log.i(TAG, "Charger turned ON successfully")
                            } else {
                                Log.e(TAG, "Failed to turn ON charger")
                            }
                        }
                    }
                    
                    batteryPct >= high -> {
                        Log.i(TAG, "Battery high ($batteryPct% >= $high%), turning OFF charger")
                        BleManager.sendCommand(context!!, mac, false) { success ->
                            if (success) {
                                prefs.edit().putBoolean("charging_on", false).apply()
                                Log.i(TAG, "Charger turned OFF successfully")
                            } else {
                                Log.e(TAG, "Failed to turn OFF charger")
                            }
                        }
                    }
                    
                    else -> {
                        Log.d(TAG, "Battery level $batteryPct% - between thresholds, no action needed")
                    }
                }
                
                // Update notification with current status
                updateNotification(batteryPct, isChargingOn)
            }
        }
    }
    
    private fun updateNotification(batteryLevel: Int, chargingOn: Boolean) {
        val mac = prefs.getString("mac", "Not configured") ?: "Not configured"
        val shortMac = if (mac.length > 8) "...${mac.takeLast(8)}" else mac
        
        val status = if (chargingOn) "⚡ ON" else "🔌 OFF"
        val lowThreshold = prefs.getInt("low", 20)
        val highThreshold = prefs.getInt("high", 80)
        
        // Add emoji indicator for battery level
        val batteryEmoji = when {
            batteryLevel <= 20 -> "🔴"
            batteryLevel <= 50 -> "🟡" 
            else -> "🟢"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔋 SwitchBot Monitor - $batteryEmoji $batteryLevel%")
            .setContentText("Smart Plug $shortMac: $status | Thresholds: $lowThreshold%-$highThreshold%")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSound(null) // Silent but visible
            .build()
            
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}