# Battery Charge Limiter (BCL)

A fork of **Battery Charge Limit** whose development has been stalled for some time.

**NOTE:** This is app currently requires root to function. While it is not possible to control charging without root, an alarm-based solution might be implemented for no-root users in the future.

## What's changed in release 1.4.0
### Features
- **Dynamic UI**: Added real-time battery level indicators on the main screen with state-aware coloring (Green for charging/full, Orange for discharging).
- **Power Source Tracking**: Now displays the specific power source (AC, USB, Wireless) in the battery info dashboard.
- **Internationalization**: Improved clarity for notification settings and synchronized translations across all 12 supported languages.
- **Battery Health**: Updated default limits to 40% (Min) and 50% (Max) to maximize longevity for devices permanently connected to power.
- **Testing**: Added comprehensive ADB commands and instructions for simulating battery events in emulators.

### Optimizations
- **Performance & Stability**: Refactored battery info retrieval to be fully asynchronous, offloading blocking shell commands to a background executor to prevent UI stutters and ANRs.
- **Enhanced Battery Info**: Now prioritizes direct `sysfs` paths for correct current (mA) reporting.
- **Improved Responsiveness**: Added a 1-second debounce to sliders to prevent UI lag and excessive disk writes during adjustment.
- **Hardware Optimization**: Refactored charger file control logic to avoid redundant writes and unnecessary mount operations, improving efficiency and error handling.

### Bug Fixes
- **Fix**: Fixed notification sound issues on Android 8.0+ by implementing direct playback for ongoing service notifications.
- **Fix**: Resolved the "Pulse to 1" bug by forcing the charger OFF when plugging in while already above the limit.

### Refactoring
- **Maintenance**: Improved build stability with updated lint baselines and refined emulator detection logic.
- **Cleanup**: Removed unnecessary imports and obsolete notification sound management functions.

## What's changed in release 1.3.1
- **Localized About Dialog**: Added and synchronized translations for the "About" dialog across all 12 languages.
- **Fix**: Corrected the battery info format string across all languages to match the updated 3-parameter format used in the English version.
- **Updated Copyright**: Refreshed app authorship and credits to reflect 2026 and the new repository home.
- **Clarity Improvements**: Renamed "Disable charge now" to "Pause charging" across all supported languages for better consistency.

## What's changed in release 1.3.0
- **Modernized UI**: Refactored the dashboard with a cleaner look. Switches are now cards that change color (green/orange) to clearly show their state.
- **Better Sliders**: Both sliders now have a fixed 0-100% range for better stability—no more confusing jumps!
- **Intelligent Limits**: Added "push" logic so moving one slider automatically adjusts the other to maintain the "lower < upper" rule.
- **Pause Charging**: Renamed "Disable charge now" to "Pause charging" to be more intuitive.
- **Enhanced Battery Info**: Now shows real-time current (mA) with cleaner formatting for temperature and voltage.
- **Greater Flexibility**: Lowered the minimum stop limit to 1% and updated defaults to 70% (start) / 80% (stop).
- **Fixed Crashes**: Resolved stability issues when quickly adjusting sliders.

## What's changed in release 1.2.0
- **Simplified Interface**: Removed all GUI elements and related code that allowed to stop charging based on voltage to make app interface cleaner and simpler to use.
- **Data Sync Fix**: Fixed refreshing control file data on return to Main screen. This will now be in sync with what was selected in settings screen.
- **Build Migration**: Migrated build system to Kotlin DSL and Gradle 9.3.1.
- **SDK Update**: Updated to JVM SDK 17.

## Features
- Free and open source.
- Material 3 with dynamic colors.
- Control when to start and stop charging based on battery percentage — either directly or via a widget.
- Set custom battery control configuration if the supplied ones cannot be used properly.

### Same as ACC?
`acc` offers a broad range of features which might be too much for some people.

## Troubleshooting
If BCL cannot start or stop charging correctly, enable **Always Write CTRL File** in the settings.

## Screenshots

<img src="screenshots/main-light-theme.png" alt="App Screenshot Light Theme" />
<img src="screenshots/main-dark-theme.png" alt="App Screenshot Dark Theme" />

## License

GNU General Public License v3.0
