# goldenFinger

## Summary of Features

**goldenFinger** is an Android demo application built with Jetpack Compose and Material 3, featuring:
- **🏃 Jogging Tracker**: A Strava-like GPS and activity tracking screen for logging workout time, distance, pace, and routes.
- **🏋️ Lifting Tracker**: A workout logger to track strength exercises, sets, reps, and lifted weights.
- **🔒 Fingerprint Debugger**: A biometric authentication demo showcasing AndroidX Biometric API integration (TEE / StrongBox), app launch counting, and scan success/failure statistics.

---

## How to Build the App

To build debug APKs using Gradle from the command line:

```bash
# On Linux / macOS
./gradlew assembleDebug

# On Windows (PowerShell / Command Prompt)
.\gradlew.bat assembleDebug
```

The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## How to Install the App

You can install the app directly on a connected Android device or emulator:

### Option 1: Via Gradle (Build & Install)
```bash
# Linux / macOS
./gradlew installDebug

# Windows
.\gradlew.bat installDebug
```

### Option 2: Via ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## How to Run Unit Tests

To execute unit tests across the project:

```bash
# Linux / macOS
./gradlew test

# Windows
.\gradlew.bat test
```

To run unit tests specifically for the app module (`:app`):
```bash
./gradlew :app:testDebugUnitTest
```

Test HTML reports are generated at `app/build/reports/tests/testDebugUnitTest/index.html`.
