# ADB Battery Control Limiter Testing

Use these commands to test the app's behavior in an emulator or on a device without physical power cycling.

**IMPORTANT:** Always run `adb shell dumpsys battery reset` when you are done testing to return the device to its actual state.

# ADB Basic Commands

| Purpose | Command |
|---|---|
| List devices | `adb devices` |
| Target specific device/emulator | `adb -s <serial> <command>` |
| Target only emulator | `adb -e <command>` |
| Target only physical device | `adb -d <command>` |
| Clear Logcat | `adb -s <serial> logcat -c` |

**Replace `<serial>` with the device/emulator id (e.g., `emulator-5554`)**

## Battery Status Overrides
| Status | Command                                              |
| :--- |:-----------------------------------------------------|
| **Charging** | `adb -s <serial> shell dumpsys battery set status 2` |
| **Discharging** | `adb -s <serial> shell dumpsys battery set status 3` |
| **Not Charging** | `adb -s <serial> shell dumpsys battery set status 4` |
| **Full** | `adb -s <serial> shell dumpsys battery set status 5` |


# ADB Battery Commands (mostly for use with emulator)
| Purpose                         | Command                                                                                                                                                         |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Plug in power source (emulator) | `adb -s <serial> shell dumpsys battery set ac 1`<br>`adb -s <serial> shell dumpsys battery set usb 1`<br>`adb -s <serial> shell dumpsys battery set wireless 1` |
| Unplug power source (emulator)  | `adb -s <serial> shell dumpsys battery set ac 0`<br>`adb -s <serial> shell dumpsys battery set usb 0`<br>`adb -s <serial> shell dumpsys battery set wireless 0` |
| Set battery level               | `adb -s <serial> shell dumpsys battery set capacity <0-100>`                                                                                                    |
| Reset battery to default values | `adb -s <serial> shell dumpsys battery reset`                                                                                                                   |
| Quick check battery status      | `adb -s <serial> shell dumpsys battery \| grep status`                                                                                                          |
| Full Plug In to AC              | `adb -s <serial> shell dumpsys battery set ac 1 && adb shell dumpsys battery set status 2`                                                                      |
| Full Unplug from AC             | `adb -s <serial> shell dumpsys battery set ac 0 && adb shell dumpsys battery set status 3`                               |

## Testing "Physical Unplug" (Wireless ADB)
To test behavior when the cable is physically removed without losing your debug session:

### For Android 11 and above:
1. Go to **Developer Options** > **Wireless Debugging** and turn it ON.
2. Tap **Pair device with pairing code**.
3. On your PC, run: `adb pair <ip_address>:<port>` and enter the code.
4. Then run: `adb connect <ip_address>:<port>`.
5. You can now **physically unplug** the USB cable.

### For Android 10 and below:
1. Connect the tablet via USB.
2. Run: `adb tcpip 5555`.
3. Find your tablet's IP (Settings > About > Status).
4. Run: `adb connect <tablet_ip>:5555`.
5. You can now **physically unplug** the USB cable.

## Common Testing Scenarios

### 1. Test "Limit Reached" Notification
1. Set level to 79%: `adb shell dumpsys battery set level 79`
2. Simulate Full Plug In: `adb shell dumpsys battery set ac 1 && adb shell dumpsys battery set status 2`
3. Increment to 80%: `adb shell dumpsys battery set level 80`
   - *The app should now trigger the "Stop Charging" logic (Silent 0).*
4. Verify logic by setting status to: `adb shell dumpsys battery set status 4`

### 2. Test "Recharge At" Logic
1. Set level to 71%: `adb shell dumpsys battery set level 71`
2. Simulate Plug In (Not Charging): `adb shell dumpsys battery set ac 1 && adb shell dumpsys battery set status 4`
3. Drop to 70%: `adb shell dumpsys battery set level 70`
   - *The app should now trigger the "Start Charging" logic (Write 1).*
4. Verify logic by setting status to: `adb shell dumpsys battery set status 2`

### 3. Test "Plug In at High Battery" (Pulse Bug Fix)
1. Set level to 85% (Limit is 80%): `adb shell dumpsys battery set level 85`
2. Simulate Full Plug In: `adb shell dumpsys battery set ac 1 && adb shell dumpsys battery set status 2`
   - *The app should immediately trigger a "Silent 0" and remain in "Not Charging" state without pulsing the charger (no Status 2 -> 4 cycling).*
3. Verify status override: `adb shell dumpsys battery set status 4`

### 4. Test "Unplug / Service Shutdown"
1. Start the service and plug in (Simulated).
2. Set status to Charging: `adb shell dumpsys battery set status 2`
3. Simulate Full Unplug: `adb shell dumpsys battery set ac 0 && adb shell dumpsys battery set usb 0 && adb shell dumpsys battery set status 3`
4. Result: The Foreground Service should automatically stop after a short delay (approx 10 seconds).
