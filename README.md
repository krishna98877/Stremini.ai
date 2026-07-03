# Stremini AI

## Table of Contents
- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Platform Support](#platform-support)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [Clone the Repository](#clone-the-repository)
  - [Install Dependencies](#install-dependencies)
  - [Run the App](#run-the-app)
- [Build Instructions per Platform](#build-instructions-per-platform)
  - [Android](#android)
  - [iOS](#ios)
  - [Web](#web)
  - [Desktop (Linux, macOS, Windows)](#desktop-linux-macos-windows)
- [Configuration](#configuration)
  - [pubspec.yaml](#pubspecyaml)
  - [analysis_options.yaml](#analysis_optionsyaml)
  - [devtools_options.yaml](#devtools_optionsyaml)
  - [ProGuard Rules](#proguard-rules)
  - [Gradle Settings](#gradle-settings)
  - [Xcode Project Settings](#xcode-project-settings)
- [Usage](#usage)
  - [Enabling the Keyboard](#enabling-the-keyboard)
  - [Chat Overlay](#chat-overlay)
  - [QR/Barcode Scanning](#qrbarcode-scanning)
  - [Screen Reader](#screen-reader)
  - [Voice Input (Roadmap)](#voice-input-roadmap)
- [Development Guide](#development-guide)
  - [Directory Structure](#directory-structure)
  - [Flutter Layer](#flutter-layer)
  - [Native Android Layer](#native-android-layer)
  - [Native iOS Layer](#native-ios-layer)
  - [Desktop & Web Layer](#desktop--web-layer)
  - [State Management](#state-management)
  - [Theming & UI](#theming--ui)
  - [Session Lifecycle Management](#session-lifecycle-management)
  - [System Overlay Control](#system-overlay-control)
- [Testing](#testing)
  - [Unit Tests](#unit-tests)
  - [Integration Tests](#integration-tests)
  - [UI Tests](#ui-tests)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)
- [Roadmap](#roadmap)

---

## Overview
Stremini AI is a **cross‑platform custom keyboard** that embeds AI‑driven assistance directly into the typing experience. It supports Android, iOS, Web, Linux, macOS, and Windows, offering a full‑featured Input Method Editor (IME), a floating chat overlay, screen‑reader capabilities, QR/barcode scanning, and robust permission handling.

The project follows a **modular architecture** with a clear separation between the Flutter UI layer and native platform code. This design enables rapid feature development while maintaining platform‑specific performance and integration.

---

## Key Features
- **AI‑driven chat overlay** – Real‑time suggestions, answers, and actions while typing.
- **Custom Input Method (IME)** – Fully functional keyboard with dynamic layouts, shortcuts, and theming.
- **Screen Reader Service** – Reads on‑screen content for accessibility, supporting TalkBack, VoiceOver, and platform‑specific APIs.
- **Scanner Provider** – Integrated QR and barcode scanning accessible from the keyboard or overlay.
- **Permission Management** – Automatic handling of runtime permissions (camera, microphone, storage, etc.) across all platforms.
- **Cross‑platform support** – Android, iOS, Web, Linux, macOS, Windows.
- **Provider‑based state management** – `ChatProvider`, `ScannerProvider`, `ChatWindowStateProvider` for reactive UI.
- **Theming & UI** – Gradient rings, feature cards, permission cards, customizable app drawer, and dark/light mode support.
- **Session Lifecycle** – Automatic handling of background tasks, app lifecycle events, and service persistence.
- **System Overlay Control** – Manages system UI overlays to ensure the floating chat overlay remains visible.
- **Extensible Architecture** – Clean separation of concerns, making it easy to add new AI agents, plugins, or platform integrations.

---

## Architecture
### High‑Level Overview
└─ lib/
   ├─ controllers/          # Navigation & UI flow controllers
   ├─ core/                  # Constants, theme, shared widgets, logging
   ├─ models/                # Data models (MessageModel, etc.)
   ├─ providers/             # State management (ChatProvider, ScannerProvider, etc.)
   ├─ services/              # API, Keyboard, Overlay, Permission services
   ├─ utils/                 # Helper functions, session lifecycle, system overlay
   └─ widgets/               # Reusable UI components (chat bubbles, floating chatbot, etc.)
└─ android/app/
   ├─ src/main/kotlin/...    # Native Android services (ChatOverlayService, ScreenReaderService, StreminiIME, etc.)
   ├─ build.gradle.kts
   └─ proguard-rules.pro
└─ ios/Runner/
   ├─ AppDelegate.swift
   ├─ GeneratedPluginRegistrant.swift
   └─ Runner-Bridging-Header.h
└─ desktop/ (linux, macos, windows)
   ├─ CMakeLists.txt
   └─ native integration files
### Layered Design
| Layer | Responsibility | Key Files |
|-------|----------------|-----------|
| **Flutter UI** | UI components, navigation, state management | `lib/` |
| **Domain** | Business logic, use‑cases | `lib/features/chat/domain/` |
| **Data** | API clients, repository implementations | `lib/features/chat/data/` |
| **Native Android** | IME implementation, overlay services, permission handling | `android/app/src/main/kotlin/...` |
| **Native iOS** | Keyboard extension, overlay handling, permission bridging | `ios/Runner/...` |
| **Desktop & Web** | Platform‑specific build scripts, native bridge services | `linux/`, `macos/`, `windows/`, `web/` |

---

## Platform Support
| Platform | Status | Notes |
|----------|--------|-------|
| Android | ✅ | Uses `StreminiIME` as an InputMethodService. |
| iOS | ✅ | Implemented as a custom keyboard extension. |
| Web | ✅ | Keyboard rendered via HTML/CSS/JS, overlay via Web Components. |
| Linux | ✅ | Desktop app with system tray and overlay. |
| macOS | ✅ | Native app with menu bar integration. |
| Windows | ✅ | Desktop app with taskbar overlay. |

---

## Prerequisites
- **Flutter SDK** (>= 3.10.0)
- **Android Studio** or **Xcode** (for native builds)
- **Java JDK 11** (Android) or **Apple clang** (iOS)
- **Git**
- **Node.js** (optional, for web tooling)
- **CMake** (for Linux/macOS/Windows builds)

---

## Getting Started

### Clone the Repository
git clone https://github.com/983111/Streminiai-.git
cd Streminiai-

### Install Dependencies
flutter pub get

### Run the App
flutter run
Select the target device (Android emulator, iOS simulator, web, or desktop).

---

## Build Instructions per Platform

### Android
1. **Configure Gradle**  
   Ensure `android/gradle.properties` contains:
      android.useAndroidX=true
   android.enableJetifier=true
   2. **Build**  
      cd android
   ./gradlew assembleRelease
   3. **Locate the APK**  
   `android/app/build/outputs/apk/release/app-release.apk`

### iOS
1. **Open Xcode Workspace**  
      open ios/Runner.xcodeproj
   2. **Select Device/Simulator** and press **Run**.
3. **Ensure Bridging Header** includes any required native headers.

### Web
flutter build web
Deploy the contents of `build/web` to any static web host.

### Desktop (Linux, macOS, Windows)
flutter build linux   # or macos, windows
- **Linux**: Build a `.tar.gz` or `.AppImage`.
- **macOS**: Build a `.app` bundle.
- **Windows**: Build an `.exe` or `.msi` (use `Runner.rc` for packaging).

---

## Configuration

### pubspec.yaml
Lists Flutter dependencies, assets, and plugins. Example snippet:
dependencies:
  flutter:
    sdk: flutter
  provider: ^6.0.0
  http: ^1.2.0
  permission_handler: ^11.0.0
  # ... other dependencies

flutter:
  assets:
    - assets/images/
    - assets/fonts/

### analysis_options.yaml
Defines linting rules and code style. Run `dart format` to enforce formatting.

### devtools_options.yaml
Configuration for Flutter DevTools (profile, inspector, etc.).

### ProGuard Rules
`android/app/proguard-rules.pro` contains rules to keep essential classes and methods from obfuscation:
-keep class com.Android.stremini_ai.** { *; }
-keep class **$$FlutterPlugin { *; }

### Gradle Settings
`android/app/build.gradle.kts` defines minSdk, compileSdk, and default config:
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        targetSdk = 34
        // ... other config
    }
}

### Xcode Project Settings
`ios/Runner.xcodeproj` includes:
- **Deployment Target**: iOS 15.0+
- **Signing**: Use your Apple Developer account.
- **Framework Search Paths**: Ensure Flutter plugins are linked.

---

## Usage

### Enabling the Keyboard
1. Install the app from the generated APK/IPA or from the Play Store/App Store.
2. Open **Settings → General → Keyboard → Keyboards → Add New Keyboard** and select **Stremini AI**.
3. Grant required permissions (camera, microphone, storage, etc.) when prompted.

### Chat Overlay
- While typing, tap the floating chat icon (top‑right corner) to open the overlay.
- Ask questions, request actions, or get suggestions directly from the AI.
- Drag the overlay to any screen location; it remembers the position across sessions.

### QR/Barcode Scanning
- Press the scanner button on the keyboard or invoke the overlay’s **Scan** command.
- Point the device camera at the QR code or barcode; the result is returned instantly and can be inserted into the text field.

### Screen Reader
- Activate the screen reader from the keyboard settings.
- The service reads highlighted text and UI elements as you navigate, supporting TalkBack (Android) and VoiceOver (iOS).

### Voice Input (Roadmap)
- Future feature: voice transcription integrated into the keyboard, allowing hands‑free typing.

---

## Development Guide

### Directory Structure
lib/
  controllers/
  core/
  features/
    chat/
    scanner/
  models/
  providers/
  services/
  utils/
  widgets/
android/
ios/
desktop/
web/

### Flutter Layer
- **Controllers**: Navigation logic (`HomeController`, `ChatCommandCoordinator`).
- **Core**: Shared constants, theme, logging, and generic widgets.
- **Providers**: State management using the `provider` package.
- **Services**: API (`ApiService`), Keyboard (`KeyboardService`), Overlay (`OverlayService`), Permission (`PermissionService`).
- **Utils**: Helper functions, session lifecycle manager, system overlay controller.
- **Widgets**: UI components (`MessageBubble`, `FloatingChatBot`, `GradientRing`, etc.).

### Native Android Layer
- **`StreminiIME.kt`**: Implements `InputMethodService` for the custom keyboard.
- **`ChatOverlayService.kt`**: Manages the floating chat overlay as a foreground service.
- **`ScreenReaderService.kt`**: Provides screen‑reading capabilities using AccessibilityService.
- **`KeyboardSettingsActivity.kt`**: Settings UI for the IME.
- **`proguard-rules.pro`**: Obfuscation rules.

### Native iOS Layer
- **`AppDelegate.swift`**: Entry point for the iOS app.
- **`GeneratedPluginRegistrant.swift`**: Registers Flutter plugins.
- **`Runner-Bridging-Header.h`**: Bridges Swift and Objective‑C native code.

### Desktop & Web Layer
- **CMake files**: Build configuration for Linux, macOS, Windows.
- **Native bridge services**: Platform‑specific implementations for overlay and permission handling.
- **Web assets**: `web/index.html`, `web/manifest.json`.

### State Management
- **Provider**: Reactive state containers for chat, scanner, and UI.
- **Riverpod** (optional): Can be introduced for more advanced state handling.

### Theming & UI
- **`app_theme.dart`**: Central theme definition (colors, text styles, gradients).
- **`gradient_ring.dart`**: Visual indicator for AI processing.
- **`feature_card.dart`**: UI component for feature listings in the drawer.

### Session Lifecycle Management
- **`session_lifecycle_manager.dart`**: Handles app background/foreground transitions, ensuring services persist correctly.

### System Overlay Control
- **`system_overlay_controller.dart`**: Adjusts system UI flags to keep the overlay visible.

---

## Testing

### Unit Tests
flutter test
Tests are located under `test/` and cover use‑cases, repository implementations, and utility functions.

### Integration Tests
flutter drive --target=test_driver/integration_test.dart
Simulates real user interactions across platforms.

### UI Tests
- **Android**: Instrumented tests in `android/app/src/androidTest/java/...`.
- **iOS**: UI tests in `ios/RunnerTests/`.
- **Desktop**: Platform‑specific test harnesses.

All tests must pass before merging.

---

## Contributing

### How to Contribute
1. **Fork** the repository.
2. **Create a feature branch**:
      git checkout -b feature/awesome-feature
   3. **Make your changes** and ensure all tests pass:
      flutter test
   4. **Update documentation** if you added new features or changed behavior.
5. **Submit a Pull Request** with a clear description of the changes.

### Code Style
- Run `dart format` on all Dart files.
- Follow the linting rules defined in `analysis_options.yaml`.
- For Kotlin code, consider using `ktlint` for formatting.

### Review Process
- PRs are reviewed by at least one maintainer.
- CI pipeline runs tests, linting, and builds for all supported platforms.

---

## License
This project is licensed under the **MIT License** – see the `LICENSE` file for details.

---

## Contact
- **Maintainer**: StreminiAI developers
- **GitHub**: https://github.com/983111/Streminiai-
- **Issues**: Open an issue on the GitHub repository for bug reports or feature requests.

---

## Roadmap
- **Voice Input Support** – Integrate speech‑to‑text for hands‑free typing.
- **Offline AI Inference** – Add on‑device model for suggestions without network.
- **Expanded Scanner Capabilities** – Support NFC, RFID, and additional barcode formats.
- **Multi‑Language Keyboard Layouts** – Dynamic layout switching based on locale.
- **Plugin System** – Allow third‑party extensions (e.g., custom AI agents).
- **Enhanced Theming** – User‑customizable themes and dynamic color palettes.
- **Improved Accessibility** – Full compliance with WCAG and platform accessibility guidelines.

---

## Acknowledgements
- **Flutter Community** – For the excellent framework and plugins.
- **AI API Contributors** – For providing the chat suggestion backend.
- **Open Source Libraries** – `provider`, `http`, `permission_handler`, and many others.