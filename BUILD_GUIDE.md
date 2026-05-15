# Butler Android Build Guide

This guide explains how to build the Butler Android APK using Chaquopy for Python integration.

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17** or later
- **Android SDK** 24 (Android 7.0) minimum, SDK 34 recommended
- **Python 3.8+** (for running build scripts)
- **Gradle 8.2+** (bundled with Android Studio)

## Project Setup

### 1. Open in Android Studio

```bash
# Open Android Studio
# File > Open > select ButlerAndroid folder
# Wait for Gradle sync to complete
```

### 2. Copy Butler Source Files

The Butler Android project needs access to the Butler desktop source files.
You can either:

**Option A: Symlink (recommended for development)**
```bash
cd ButlerAndroid/app/src/main/python
ln -s ../../../Butler/butler butler
ln -s ../../../Butler/local_interpreter local_interpreter
ln -s ../../../Butler/plugin plugin
ln -s ../../../Butler/package package
ln -s ../../../Butler/markitdown markitdown
```

**Option B: Copy files**
```bash
cp -r Butler/butler ButlerAndroid/app/src/main/python/
cp -r Butler/local_interpreter ButlerAndroid/app/src/main/python/
cp -r Butler/plugin ButlerAndroid/app/src/main/python/
cp -r Butler/package ButlerAndroid/app/src/main/python/
cp -r Butler/markitdown ButlerAndroid/app/src/main/python/
```

### 3. Configure Chaquopy

Chaquopy is configured in `app/build.gradle.kts`. By default, it includes:
- Python 3.11
- Essential packages (see Chaquopy documentation)

To add additional Python packages, add them to the `build.gradle.kts`:

```kotlin
python {
    pip {
        install("requests")
        install("numpy")
    }
}
```

## Build Commands

### Via Android Studio

1. **Debug Build:**
   - Build > Make Project (Ctrl+F9)
   - Build > Build Bundle(s) / APK(s) > Build APK(s)

2. **Release Build:**
   - Build > Generate Signed Bundle / APK
   - Choose Android App Bundle or APK
   - Configure signing key or create new one
   - Build release version

### Via Command Line

```bash
# Navigate to project
cd ButlerAndroid

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Clean build
./gradlew clean

# Build with verbose output
./gradlew assembleDebug --info
```

## APK Location

After building:
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK:** `app/build/outputs/apk/release/app-release.apk`

## Installing on Device

### Via ADB (Debug)
```bash
# Connect device and enable USB debugging
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Via Android Studio
- Run > Run 'app' (Shift+F10)
- Select connected device or emulator

### Manual Install
- Transfer APK to device
- Enable "Install from unknown sources"
- Open and install

## Troubleshooting

### Python Import Errors

If you get import errors for Butler modules:
1. Verify source files are in `app/src/main/python/`
2. Check symlinks are correct
3. Rebuild after adding new Python files

### Chaquopy Build Failures

Common solutions:
1. Clean and rebuild: `./gradlew clean assembleDebug`
2. Invalidate caches: File > Invalidate Caches > Invalidate and Restart
3. Check Python version compatibility

### Memory Issues

If build runs out of memory, increase Gradle heap in `gradle.properties`:
```
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

## Release Build Signing

For Play Store distribution:

1. Create signing key:
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

2. Add to `gradle.properties`:
```
KEYSTORE_FILE=my-release-key.jks
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=my-key-alias
KEY_PASSWORD=your_password
```

3. Build release:
```bash
./gradlew assembleRelease
```

## App Bundle for Play Store

For Google Play Store, build an AAB instead of APK:

```bash
./gradlew bundleRelease
```

The AAB will be at: `app/build/outputs/bundle/release/app-release.aab`

## Size Optimization

To reduce APK size:
1. Enable ProGuard/R8 minification
2. Remove unused Butler modules
3. Use ABI splits to include only required native libraries

## Testing on Emulator

```bash
# Start emulator
emulator -avd Pixel_6_API_34 &

# Wait for boot
adb wait-for-device

# Install and run
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.butler.app/.MainActivity
```
