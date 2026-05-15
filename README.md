# Butler Android - Mobile Version

A hybrid mobile app that brings Butler's intelligent assistant capabilities to Android devices.

## Architecture

```
┌─────────────────────────────────────┐
│     Android App (Kotlin)             │
│   Shell + Lifecycle + Permissions     │
├─────────────────────────────────────┤
│      Chaquopy (Python Runtime)       │
│   Butler Core Logic (Unmodified)      │
│   • butler/        • plugin/          │
│   • local_interpreter/ • package/     │
├─────────────────────────────────────┤
│      Platform Channels                │
│   Kotlin ↔ Python Bridge             │
├─────────────────────────────────────┤
│      Android Native Features          │
│   • Microphone    • File I/O          │
│   • TTS           • Network          │
└─────────────────────────────────────┘
```

## Tech Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| App Shell | Kotlin + Jetpack Compose | Native Android UI |
| Python Runtime | Chaquopy | Run Python on Android |
| Core Logic | Butler (Original) | AI chat, interpreter, plugins |
| Communication | Platform Channels | Kotlin ↔ Python IPC |

## Features

- 🤖 **Conversational AI** - Chat with Butler using DeepSeek API
- 🎤 **Voice Input** - Voice commands with offline wake word support
- 🔊 **Voice Output** - Text-to-speech responses
- 💻 **Code Interpreter** - Execute Python code in sandbox
- 🔌 **Plugin System** - Extend functionality with plugins
- 📱 **Mobile Optimized** - Touch-friendly interface

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- Python 3.8+ (for development)
- JDK 17+
- Android SDK 24+ (Android 7.0)

### Build

```bash
# Open in Android Studio
# Sync Gradle
# Build > Run
```

Or via command line:

```bash
./gradlew assembleDebug
```

## Project Structure

```
app/
├── src/main/
│   ├── java/com/butler/app/
│   │   ├── MainActivity.kt          # Entry point
│   │   ├── ui/                      # Compose UI
│   │   │   ├── theme/
│   │   │   ├── screens/
│   │   │   └── components/
│   │   ├── bridge/                  # Python bridge
│   │   │   └── ButlerBridge.kt
│   │   ├── platform/                # Platform channels
│   │   └── audio/                   # Audio handling
│   │
│   ├── python/                      # Butler source
│   │   ├── butler/
│   │   ├── local_interpreter/
│   │   ├── plugin/
│   │   ├── package/
│   │   └── markitdown/
│   │
│   ├── res/
│   └── AndroidManifest.xml
│
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Permissions

- `INTERNET` - API calls
- `RECORD_AUDIO` - Voice input
- `VIBRATE` - Notifications

## License

MIT License - Same as Butler desktop version
