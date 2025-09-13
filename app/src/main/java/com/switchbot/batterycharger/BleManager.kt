package com.switchbot.batterycharger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*

object BleManager {
    private const val TAG = "BleManager"
    
    // SwitchBot Smart Plug Mini Service and Characteristic UUIDs
    private val SERVICE_UUID = UUID.fromString("cba20d00-224d-11e6-9fb8-0002a5d5c51b")
    private val WRITE_CHAR_UUID = UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b")
    
    // SwitchBot command bytes (matching working Python script)
    private val ON_BYTES = byteArrayOf(0x57.toByte(), 0x01.toByte(), 0x01.toByte())
    private val OFF_BYTES = byteArrayOf(0x57.toByte(), 0x01.toByte(), 0x02.toByte())
    
    private const val CONNECTION_TIMEOUT_MS = 10000L
    private const val SCAN_TIMEOUT_MS = 15000L
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 5000L
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var scanner: BluetoothLeScanner? = null
    private var currentGatt: BluetoothGatt? = null
    private var currentCallback: ((Boolean) -> Unit)? = null
    private var retryCount = 0
    private var currentMac: String? = null
    private var currentTurnOn: Boolean = false
    private var currentFromForeground: Boolean = false
    @SuppressLint("StaticFieldLeak")
    private var currentContext: Context? = null
    
    private val handler = Handler(Looper.getMainLooper())
    
    private fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ permissions
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 12 permissions
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun sendCommand(context: Context, mac: String, turnOn: Boolean, callback: (Boolean) -> Unit, fromForeground: Boolean = false) {
        Log.d(TAG, "Sending command to $mac: ${if (turnOn) "ON" else "OFF"} (from: ${if (fromForeground) "FOREGROUND" else "BACKGROUND"})")
        
        // Check for required permissions
        if (!hasBluetoothPermissions(context)) {
            Log.e(TAG, "Missing required Bluetooth permissions")
            callback(false)
            return
        }
        
        currentCallback = callback
        currentMac = mac
        currentTurnOn = turnOn
        currentFromForeground = fromForeground
        currentContext = context.applicationContext
        retryCount = 0
        
        initBluetoothAdapter(context)
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            callback(false)
            return
        }
        
        attemptConnection(context, mac, turnOn, fromForeground)
    }
    
    private fun initBluetoothAdapter(context: Context) {
        if (bluetoothAdapter == null) {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            scanner = bluetoothAdapter?.bluetoothLeScanner
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun attemptConnection(context: Context, mac: String, turnOn: Boolean, fromForeground: Boolean = false) {
        // First try to connect to bonded device  
        val bondedDevice = bluetoothAdapter?.bondedDevices?.find { it.address.equals(mac, ignoreCase = true) }
        
        if (bondedDevice != null) {
            Log.d(TAG, "Found bonded device, connecting directly")
            connectToDevice(context, bondedDevice, turnOn)
        } else {
            // For Android 15+, restrict background scanning only (allow foreground scanning)
            if (Build.VERSION.SDK_INT >= 35 && !fromForeground) {
                Log.w(TAG, "Device not bonded and Android 15+ detected - background scanning restricted")
                Log.i(TAG, "Please bond the device in foreground first using the test buttons")
                handleFailure()
                return
            }
            
            Log.d(TAG, "Device not bonded, scanning for device")
            scanForDevice(context, mac, turnOn)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun scanForDevice(context: Context, mac: String, turnOn: Boolean) {
        if (scanner == null) {
            Log.e(TAG, "Scanner is null")
            handleFailure()
            return
        }
        
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (device.address.equals(mac, ignoreCase = true)) {
                        Log.d(TAG, "Found target device: ${device.address}")
                        scanner?.stopScan(this)
                        handler.removeCallbacksAndMessages(null) // Cancel timeout
                        connectToDevice(context, device, turnOn)
                    }
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error: $errorCode")
                handleFailure()
            }
        }
        
        try {
            val filter = ScanFilter.Builder()
                .setDeviceAddress(mac)
                .build()
            
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "Started scanning for device: $mac")
            
            // Set timeout for scan
            handler.postDelayed({
                scanner?.stopScan(scanCallback)
                Log.w(TAG, "Scan timeout")
                handleFailure()
            }, SCAN_TIMEOUT_MS)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during scan", e)
            handleFailure()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun connectToDevice(context: Context, device: BluetoothDevice, turnOn: Boolean) {
        cleanupConnection()
        
        // Attempt to bond the device if not already bonded (helps with future background connections)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Device not bonded, attempting to bond")
            device.createBond()
        }
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to GATT server")
                        handler.removeCallbacksAndMessages(null) // Cancel timeout
                        gatt?.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from GATT server")
                        cleanupConnection()
                    }
                }
                
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Connection failed with status: $status")
                    cleanupConnection()
                    handleFailure()
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered")
                    val service = gatt?.getService(SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(WRITE_CHAR_UUID)
                        if (characteristic != null) {
                            writeCommand(gatt, characteristic, turnOn)
                        } else {
                            Log.e(TAG, "Write characteristic not found")
                            handleFailure()
                        }
                    } else {
                        Log.e(TAG, "SwitchBot service not found")
                        handleFailure()
                    }
                } else {
                    Log.e(TAG, "Service discovery failed with status: $status")
                    handleFailure()
                }
            }
            
            override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Command written successfully")
                    currentCallback?.invoke(true)
                } else {
                    Log.e(TAG, "Failed to write characteristic, status: $status")
                    handleFailure()
                }
                
                // Disconnect after write attempt
                gatt?.disconnect()
            }
        }
        
        try {
            currentGatt = device.connectGatt(context, false, gattCallback)
            
            // Set connection timeout
            handler.postDelayed({
                val connectionState = bluetoothManager?.getConnectionState(device, BluetoothProfile.GATT)
                if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                    Log.w(TAG, "Connection timeout")
                    cleanupConnection()
                    handleFailure()
                }
            }, CONNECTION_TIMEOUT_MS)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connection", e)
            handleFailure()
        }
    }
    
    private fun writeCommand(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, turnOn: Boolean) {
        val commandBytes = if (turnOn) ON_BYTES else OFF_BYTES
        
        Log.d(TAG, "Writing command: ${commandBytes.joinToString(" ") { "%02X".format(it) }}")
        
        try {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+) - use new API
                gatt.writeCharacteristic(characteristic, commandBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                // Android 12 and below - use deprecated API
                @Suppress("DEPRECATION")
                characteristic.value = commandBytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            
            if (success != BluetoothStatusCodes.SUCCESS && success != true) {
                Log.e(TAG, "Failed to initiate write, status: $success")
                handleFailure()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during write", e)
            handleFailure()
        }
    }
    
    private fun handleFailure() {
        cleanupConnection()
        
        if (retryCount < MAX_RETRY_ATTEMPTS && currentMac != null) {
            retryCount++
            Log.w(TAG, "Retrying connection attempt $retryCount/$MAX_RETRY_ATTEMPTS")
            
            handler.postDelayed({
                currentMac?.let { mac ->
                    currentContext?.let { context ->
                        attemptConnection(context, mac, currentTurnOn, currentFromForeground)
                    }
                }
            }, RETRY_DELAY_MS)
            
        } else {
            Log.e(TAG, "All retry attempts failed")
            currentCallback?.invoke(false)
            resetState()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun cleanupConnection() {
        handler.removeCallbacksAndMessages(null)
        currentGatt?.close()
        currentGatt = null
    }
    
    private fun resetState() {
        cleanupConnection()
        currentCallback = null
        currentMac = null
        currentFromForeground = false
        currentContext = null
        retryCount = 0
    }
}