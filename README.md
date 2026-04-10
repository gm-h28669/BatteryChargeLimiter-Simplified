# Battery Charge Limiter (BCL)

A fork of **Battery Charge Limit** whose development has been stalled for some time.

**NOTE:** This is app currently requires root to function. While it is not possible to control charging without root, an alarm-based solution might be implemented for no-root users in the future.

## What's changed in release 1.2.0
- Removed all GUI elements and related code that allowed to stop charging based on voltage to make app interface cleaner and simpler to use.
- Fix: Refresh control file data on return to Main screen. This will now be in sync with what was selected in settings screen.
- Migrate build system to Kotlin DSL and Gradle 9.3.1
- Updated to JVM SDK 17

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

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" height="500dp" /><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" height="500dp" /><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" height="500dp" />

## License

GNU General Public License v3.0
