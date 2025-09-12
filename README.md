# SwitchBot Smart Plug Battery Charger

A lightweight Android app that automatically controls a SwitchBot Smart Plug Mini based on the device's battery level. The app runs silently in the background and uses Bluetooth Low Energy (BLE) to turn the smart plug on/off without requiring internet connectivity.

## Features

- **Background Battery Monitoring**: Continuously monitors device battery level
- **Automatic Smart Plug Control**: Turns plug ON when battery drops below threshold, OFF when charged
- **Configurable Thresholds**: Set custom low/high battery percentages (default: 20%/80%)
- **BLE Direct Control**: Uses reverse-engineered SwitchBot protocol for local control
- **Persistent Operation**: Runs as foreground service with minimal battery impact
- **No Internet Required**: 100% offline operation

## Requirements

- Android 5.0 (API 21) or higher
- Bluetooth Low Energy (BLE) support
- SwitchBot Smart Plug Mini
- Device must be within ~10-30m range of the smart plug

## Installation

### Method 1: Build from Source

1. **Prerequisites**:
   - Android Studio (latest version recommended)
   - Android SDK with API 21+ 
   - Java 8 or higher

2. **Clone and Build**:
   ```bash
   git clone <this-repository>
   cd android-smartplug-control
   ./gradlew assembleDebug
   ```

3. **Install APK**:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Method 2: Sideload Pre-built APK

1. Enable "Unknown Sources" in Android Settings > Security
2. Transfer the APK file to your device
3. Install using a file manager

## Setup Instructions

### 1. Get SwitchBot MAC Address

Use a Bluetooth scanner app (like nRF Connect) to find your SwitchBot Smart Plug Mini's MAC address:

1. Install nRF Connect or similar BLE scanner
2. Turn on your SwitchBot plug 
3. Scan for devices
4. Look for a device named "WoPlug" or similar
5. Note the MAC address (format: XX:XX:XX:XX:XX:XX)

### 2. Configure the App

1. Launch "SwitchBot Battery Charger" app
2. Enter the MAC address of your smart plug
3. Set battery thresholds:
   - **Low Threshold**: Battery percentage to start charging (default: 20%)
   - **High Threshold**: Battery percentage to stop charging (default: 80%)
4. Tap "Save & Start Service"
5. Grant required permissions when prompted
6. Allow the app to ignore battery optimization for reliable operation

### 3. Verify Operation

- Check notification area for "Battery Charger Active" 
- Monitor logcat for debug messages: `adb logcat | grep BatteryMonitor`
- Test by adjusting battery level near thresholds

## App Architecture

### Key Components

- **MainActivity**: One-time setup UI for configuration
- **BatteryMonitorService**: Foreground service that monitors battery changes
- **BleManager**: Handles Bluetooth Low Energy communication with SwitchBot
- **BatteryReceiver**: Broadcast receiver for battery level changes

### Data Flow

1. Service registers for `ACTION_BATTERY_CHANGED` broadcasts
2. On battery level change, service checks configured thresholds
3. If threshold crossed, BleManager initiates BLE connection
4. Command sent to SwitchBot using reverse-engineered protocol
5. Connection closed, state updated in SharedPreferences

## Protocol Details

### SwitchBot Smart Plug Mini BLE Protocol

- **Service UUID**: `cba20d00-224d-11e6-9fb8-0002a5d5c51b`
- **Write Characteristic**: `cba20002-224d-11e6-9fb8-0002a5d5c51b`

**Commands**:
- **Turn ON**: `57 0F 43 31 00 00 00 00 00 00 00 00 00 00 00 00`
- **Turn OFF**: `57 0F 43 30 00 00 00 00 00 00 00 00 00 00 00 00`

### Connection Process

1. Scan for device by MAC address (if not bonded)
2. Connect to GATT server
3. Discover services
4. Write command to characteristic
5. Disconnect

## Configuration

Settings are stored in SharedPreferences (`config`):

- `mac`: SwitchBot device MAC address
- `low`: Low battery threshold percentage
- `high`: High battery threshold percentage  
- `charging_on`: Current charging state

## Permissions

Required permissions explained:

- `BLUETOOTH` + `BLUETOOTH_ADMIN`: BLE communication
- `ACCESS_FINE_LOCATION`: Required for BLE scanning on Android 6+
- `FOREGROUND_SERVICE`: Run persistent background service
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`: Specify service type for Android 14+

## Troubleshooting

### Common Issues

1. **"Bluetooth not enabled"**
   - Enable Bluetooth in Android settings
   - Restart app after enabling

2. **"Device not found"**
   - Verify MAC address is correct (uppercase, with colons)
   - Ensure SwitchBot plug is powered and within range
   - Try scanning with nRF Connect to verify plug is discoverable

3. **"Connection timeout"**
   - Move device closer to SwitchBot plug
   - Check for Bluetooth interference
   - Restart Bluetooth on device

4. **Service stops running**
   - Disable battery optimization for the app
   - Check if device has aggressive power management
   - Verify app has all required permissions

### Debug Commands

```bash
# View logs
adb logcat | grep "BatteryMonitor\|BleManager"

# Check running services
adb shell dumpsys activity services | grep SwitchBot

# Test permissions
adb shell pm list permissions | grep bluetooth
```

## Technical Notes

- **Battery Optimization**: App requests exemption from battery optimization to ensure reliable background operation
- **Retry Logic**: 3 retry attempts with 5-second delays for failed BLE connections
- **Hysteresis**: Built-in to prevent rapid on/off switching near thresholds
- **Memory Efficient**: Uses application context to prevent memory leaks
- **Connection Management**: Properly closes GATT connections to prevent resource leaks

## Limitations

- Requires physical proximity to SwitchBot device (~10-30m)
- Only tested with SwitchBot Smart Plug Mini (may work with other SwitchBot devices)
- No support for multiple smart plugs
- Requires manual MAC address entry (no automatic discovery UI)

## Safety Considerations

- Use appropriate charging equipment to prevent device damage
- Monitor initial operation to ensure proper threshold behavior
- Consider fire safety when using automated charging systems

## License

This project is for educational and personal use. SwitchBot is a trademark of Wonderlabs Inc.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Test thoroughly on actual hardware
4. Submit pull request with detailed description

## Support

For issues related to:
- **App functionality**: Check logcat output and verify setup steps
- **SwitchBot device**: Consult official SwitchBot documentation
- **Android permissions**: Review Android developer guides for BLE permissions