# Plan for Building Android App to Control SwitchBot Smart Plug Mini via BLE

## Overview
This document provides an exact, step-by-step plan for you (Claude) to build a lightweight Android app that runs in the background on an Android tablet. The app monitors the device's battery level and automatically toggles a SwitchBot Smart Plug Mini on/off via local Bluetooth Low Energy (BLE) based on thresholds (e.g., turn on charging when battery < 20%, turn off when > 80%). No UI interactions, official apps, WiFi, or internet are required—everything operates locally.

The app uses:
- Android's `BluetoothGatt` API for direct BLE control using reverse-engineered SwitchBot protocol.
- `BroadcastReceiver` for battery level changes.
- A foreground service to ensure persistent background execution.

Target: Android API 21+ (BLE support). Language: Kotlin (preferred for modern Android). Use Android Studio for development.

**Key Constraints:**
- Runs silently in background (screen off, app minimized).
- Handles BLE connection/disconnection reliably within ~10-30m range.
- Minimal battery drain: Poll battery sparingly, connect BLE only when needed.
- User-configurable thresholds via initial setup (one-time UI).

## Requirements
### Functional
1. Monitor battery level in real-time via broadcasts.
2. If battery < low_threshold (default 20%), connect to plug and send "ON" command.
3. If battery > high_threshold (default 80%), connect to plug and send "OFF" command.
4. Hysteresis: Avoid rapid toggling (e.g., 5% buffer).
5. Store plug's BLE MAC address and thresholds in SharedPreferences.
6. Initial setup screen to input MAC and thresholds.
7. Persistent foreground service with a non-intrusive notification (e.g., "Battery Charger Active").

### Non-Functional
- Offline-only: No network permissions or calls.
- Permissions: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION (for scanning), FOREGROUND_SERVICE.
- Error handling: Retry BLE connections (3 attempts, 5s delay), log errors to Logcat.
- Compatibility: Android 5.0+ tablets.
- Size: < 1MB APK.

### Dependencies
- No external libraries: Use AndroidX (Bluetooth, AppCompat).
- Build.gradle (app): 
  ```
  android {
      compileSdk 34
      defaultConfig {
          minSdk 21
          targetSdk 34
      }
  }
  dependencies {
      implementation 'androidx.appcompat:appcompat:1.6.1'
      implementation 'androidx.core:core-ktx:1.12.0'
  }
  ```

## Architecture
- **MainActivity**: One-time setup UI (input MAC, thresholds, start service). Finishes after launch.
- **BatteryMonitorService**: Foreground service that:
  - Registers BroadcastReceiver for `Intent.ACTION_BATTERY_CHANGED`.
  - On level change, checks thresholds and triggers BLE command if needed.
  - Starts/stops BLE manager as required.
- **BleManager**: Singleton-like class handling BLE:
  - Scans for device (using MAC filter).
  - Connects via `BluetoothDevice.connectGatt()`.
  - Discovers services, writes to characteristic.
  - Handles callbacks for connect, discover, write.
- **Data Storage**: SharedPreferences for MAC, low/high thresholds.
- Flow:
  1. User launches app, sets config, starts service.
  2. Service starts foreground, registers receiver.
  3. Receiver gets battery intent, extracts level via `EXTRA_LEVEL`.
  4. If threshold crossed, BleManager sends command.
  5. BLE: Scan → Connect → Discover → Write → Disconnect.

## Detailed Implementation Steps
Follow these steps exactly in Android Studio. Create a new project named "SwitchBotBatteryCharger" with Empty Activity.

### Step 1: Add Permissions and Manifest Entries
In `AndroidManifest.xml` (inside `<manifest>`):
```
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<application ... >
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
    <service android:name=".BatteryMonitorService" android:foregroundServiceType="connectedDevice" />
</application>
```

### Step 2: Implement MainActivity (Setup UI)
- Use EditText for MAC (format XX:XX:XX:XX:XX:XX), low/high thresholds (SeekBar or EditText, range 0-100).
- Button to save to SharedPreferences and start service.
- Code:
  ```kotlin
  class MainActivity : AppCompatActivity() {
      private val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_main) // Simple LinearLayout with inputs
          val macEdit = findViewById<EditText>(R.id.mac_edit)
          val lowEdit = findViewById<EditText>(R.id.low_edit)
          val highEdit = findViewById<EditText>(R.id.high_edit)
          val saveBtn = findViewById<Button>(R.id.save_btn)
          // Load existing
          macEdit.setText(prefs.getString("mac", ""))
          lowEdit.setText(prefs.getString("low", "20"))
          highEdit.setText(prefs.getString("high", "80"))
          saveBtn.setOnClickListener {
              prefs.edit().apply {
                  putString("mac", macEdit.text.toString())
                  putInt("low", lowEdit.text.toString().toInt())
                  putInt("high", highEdit.text.toString().toInt())
              }.apply()
              startService(Intent(this, BatteryMonitorService::class.java))
              finish()
          }
      }
  }
  ```
- layout/activity_main.xml: Basic form with TextViews, EditTexts, Button.

### Step 3: Implement BatteryMonitorService
- Extend Service.
- In onStartCommand: Start foreground with notification.
- Register receiver dynamically.
- On battery change: Get level, check thresholds, call BleManager.
- Code skeleton:
  ```kotlin
  class BatteryMonitorService : Service() {
      private val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
      private lateinit var receiver: BatteryReceiver
      private val channelId = "battery_charger"
      override fun onCreate() {
          super.onCreate()
          createNotificationChannel()
          receiver = BatteryReceiver()
      }
      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
          val notification = NotificationCompat.Builder(this, channelId)
              .setContentTitle("Battery Charger Active")
              .setSmallIcon(R.drawable.ic_notification) // Add simple icon
              .build()
          startForeground(1, notification)
          val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
          registerReceiver(receiver, filter)
          return START_STICKY
      }
      override fun onDestroy() {
          unregisterReceiver(receiver)
          super.onDestroy()
      }
      private fun createNotificationChannel() { /* Standard channel creation for API 26+ */ }
      inner class BatteryReceiver : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
              if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                  val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                  val low = prefs.getInt("low", 20)
                  val high = prefs.getInt("high", 80)
                  val mac = prefs.getString("mac", "") ?: return
                  if (level < low && !isChargingOn) { // Track state in prefs
                      BleManager.sendCommand(this@BatteryMonitorService, mac, true) // on
                  } else if (level > high && isChargingOn) {
                      BleManager.sendCommand(this@BatteryMonitorService, mac, false) // off
                  }
              }
          }
      }
  }
  ```
- Track charging state in prefs (boolean "charging_on").

### Step 4: Implement BleManager
- Object or companion object.
- Use BluetoothAdapter, BluetoothLeScanner.
- Filter scan by MAC.
- GattCallback for connect/discover/write.
- Commands:
  - Service UUID: "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
  - Write Char UUID: "cba20002-224d-11e6-9fb8-0002a5d5c51b"
  - ON bytes: byteArrayOf(0x57.toByte(), 0x0F.toByte(), 0x43.toByte(), 0x31.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
  - OFF bytes: byteArrayOf(0x57.toByte(), 0x0F.toByte(), 0x43.toByte(), 0x30.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
- Code:
  ```kotlin
  object BleManager {
      private var gatt: BluetoothGatt? = null
      private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
      fun sendCommand(context: Context, mac: String, turnOn: Boolean) {
          if (adapter?.isEnabled != true) return
          val device = adapter.bondedDevices.find { it.address == mac } ?: run {
              // Scan if not bonded (add scan callback to find and connect)
              val scanner = adapter.bluetoothLeScanner
              val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
              scanner?.startScan(listOf(filter), ScanSettings.Builder().build(), object : ScanCallback() {
                  override fun onScanResult(callbackType: Int, result: ScanResult?) {
                      result?.device?.let { dev ->
                          scanner?.stopScan(this)
                          connect(dev, turnOn)
                      }
                  }
              })
              return
          }
          connect(device, turnOn)
      }
      private fun connect(device: BluetoothDevice, turnOn: Boolean) {
          gatt = device.connectGatt(null, false, object : BluetoothGattCallback() {
              override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                  if (newState == BluetoothProfile.STATE_CONNECTED) {
                      gatt?.discoverServices()
                  } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                      gatt?.close()
                  }
              }
              override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                  if (status == BluetoothGatt.GATT_SUCCESS) {
                      val service = gatt?.getService(UUID.fromString("cba20d00-224d-11e6-9fb8-0002a5d5c51b"))
                      val char = service?.getCharacteristic(UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b"))
                      char?.value = if (turnOn) onBytes else offBytes
                      gatt?.writeCharacteristic(char)
                  }
              }
              override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                  gatt?.disconnect()
              }
          })
      }
      private val onBytes = byteArrayOf(0x57.toByte(), 0x0F.toByte(), 0x43.toByte(), 0x31.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      private val offBytes = byteArrayOf(0x57.toByte(), 0x0F.toByte(), 0x43.toByte(), 0x30.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
  }
  ```
- Add retry logic: If write fails, reconnect up to 3 times.

### Step 5: Handle Location Permission
- In MainActivity onCreate: Request `Manifest.permission.ACCESS_FINE_LOCATION` if needed (for API 23+).

### Step 6: Battery Optimization
- In MainActivity: Intent to Settings for ignoring battery optimization for the app.

## Testing
1. Build APK, install on tablet.
2. Get SwitchBot MAC via nRF Connect app (scan, note address).
3. Set thresholds, start service.
4. Simulate low battery (drain to <20%), verify plug turns on (use Logcat: adb logcat | grep BleManager).
5. Charge to >80%, verify off.
6. Test range, disconnects, retries.
7. Edge cases: Invalid MAC, Bluetooth off, low API.

## Deployment Notes
- Sideload APK.
- For production: Add ProGuard, sign release.
- If issues: Check Android 12+ foreground service types.

The switchbot mini plug DOES NOT NEED to be bonded in order to work over BLE.

Follow this plan verbatim to generate the full codebase. Output the complete project files (Kotlin, XML, Gradle) in a ZIP or zipped response if possible, or as code blocks.

