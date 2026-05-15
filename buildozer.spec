# Buildozer Spec for Butler Android
# Alternative: Pure Python-to-APK using Buildozer (no Android Studio required)

[app]

# App metadata
title = Butler
package.name = butler
package.domain = org.butler

# Source files
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,xml,ttf,wav,mp3,pcm

# Main entry point
main.pyp = main.py

# App requirements (Python packages)
requirements = python3,kivy==2.2.0,kivymd,chaquopy-lib,deepseek-requests,aiohttp

# Android permissions
android.permissions = INTERNET,RECORD_AUDIO,MODIFY_AUDIO_SETTINGS,VIBRATE

# Android API level
android.minapi = 24
android.api = 34

# Build configuration
osx.python_version = 3
osx.kivy_version = 2.2.0

# Orientation (portrait only for phone)
orientation = portrait

# Fullscreen
fullscreen = 0

# Android manifest extra stuff
android.meta_data = com.google.android.gms.version=@integer/google_play_services_version

# Android manifest application attributes
android.application_theme = dark

# Icon
icon.filename = icon.png

# Splash screen
splash.filename = splash.png

#wakelock = 1
#win_logo = logo.png
#win_logo_dark = logo_dark.png

# Android specific
android.ndk_path = /path/to/android/ndk
android.ndk_api = 24
android.ndk_libs = sqlite3

# Presplash Color
android.presplash_color = #1A7FE6

# Enable Android X
android.enable_androidx = True

# Use a custom Gradle template
gradle_template = gradle.toml

[patches]

# Buildozer patches
# (usually no need to modify)

[桌面的]

# Desktop specific settings
# (usually not needed)

[ipos]

# iOS specific settings
# (iOS build not supported by Buildozer for Butler)

[kivy]

# Kivy version
version = 2.2.0

# Kivy log level
log_level = info

# Window settings
window.icon = icon.png

# Performance
profile = False
use_glsl = True

# Documentation
ksp_settings = True
kv_file = butler.kv

[python]

# Python version
version = 3.11

# Optimize
optimize = 2

# Pyinstaller (optional)
# full zip file
# zipfile = True

# Extract archives
# extract = True
