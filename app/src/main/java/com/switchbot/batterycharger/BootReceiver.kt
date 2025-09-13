package com.switchbot.batterycharger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed - checking auto-start preference")
            
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
                val autoStartEnabled = prefs.getBoolean("auto_start", false)
                val macConfigured = prefs.getString("mac", "")?.isNotEmpty() ?: false
                
                Log.d(TAG, "Auto-start enabled: $autoStartEnabled, MAC configured: $macConfigured")
                
                if (autoStartEnabled && macConfigured) {
                    Log.i(TAG, "Starting BatteryMonitorService automatically")
                    
                    val serviceIntent = Intent(ctx, BatteryMonitorService::class.java)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ctx.startForegroundService(serviceIntent)
                        } else {
                            ctx.startService(serviceIntent)
                        }
                        Log.i(TAG, "BatteryMonitorService started successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start service", e)
                    }
                } else {
                    Log.d(TAG, "Auto-start disabled or MAC not configured - skipping service start")
                }
            }
        }
    }
}