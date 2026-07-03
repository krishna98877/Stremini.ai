// File: project_structure.md
# Project Structure

## Overview
This repository contains a multi‑platform Flutter application named **Stremini‑AI** with native implementations for Android, iOS, macOS, Windows, Linux, and web. The project follows a modular architecture separating core, features, screens, widgets, services, and utilities.

---

## Directory Tree

.
├── .gitignore
├── analysis_options.yaml
├── final_analysis.txt
├── project_structure.md          ← (this file)
├── pubspec.yaml
├── README.md
├── web
│   ├── index.html
│   └── manifest.json
├── ios
│   ├── .gitignore
│   ├── Runner.xcworkspace
│   │   └── xcshareddata
│   │       └── WorkspaceSettings.xcsettings
│   ├── Runner.xcodeproj
│   │   └── Debug.xcconfig
│   │   └── Release.xcconfig
│   ├── Runner
│   │   ├── AppDelegate.swift
│   │   ├── Runner-Bridging-Header.h
│   │   ├── Base.lproj
│   │   │   ├── LaunchScreen.storyboard
│   │   │   └── Main.storyboard
│   │   └── Assets.xcassets
│   └── RunnerTests
│       └── RunnerTests.swift
├── android
│   ├── .gitignore
│   ├── app
│   │   ├── build.gradle.kts
│   │   ├── proguard-rules.pro
│   │   ├── src
│   │   │   ├── debug
│   │   │   │   └── AndroidManifest.xml
│   │   │   ├── main
│   │   │   │   ├── AndroidManifest.xml
│   │   │   │   └── kotlin
│   │   │   │       └── com
│   │   │   │           └── Android
│   │   │   │               └── stremini_ai
│   │   │   │                   ├── AIBackendClient.kt
│   │   │   │                   ├── AgenticBackendClient.kt
│   │   │   │                   ├── AgenticStepRunner.kt
│   │   │   │                   ├── BubbleController.kt
│   │   │   │                   ├── ChatCommandCoordinator.kt
│   │   │   │                   ├── ChatOverlayService.kt
│   │   │   │                   ├── DeviceCommandRouter.kt
│   │   │   │                   ├── FloatingChatController.kt
│   │   │   │                   ├── FullDeviceCommandExecutor.kt
│   │   │   │                   ├── IMEBackendClient.kt
│   │   │   │                   ├── IdleAnimationController.kt
│   │   │   │                   ├── KeyboardSettingsActivity.kt
│   │   │   │                   ├── KeyboardSettingsCoordinator.kt
│   │   │   │                   ├── MainActivity.kt
│   │   │   │                   ├── MainActivityChannelRegistry.kt
│   │   │   │                   ├── NotificationActionReceiver.kt
│   │   │   │                   ├── OverlayServiceIntentDispatcher.kt
│   │   │   │                   ├── ScreenAnalysisClient.kt
│   │   │   │                   ├── ScreenReaderCommandRouter.kt
│   │   │   │                   ├── ScreenReaderService.kt
│   │   │   │                   ├── StreminiIME.kt
│   │   │   │                   └── VoiceController.kt
│   │   │   └── profile
│   │   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── macos
│   ├── .gitignore
│   ├── Runner.xcworkspace
│   ├── Runner
│   │   ├── AppDelegate.swift
│   │   ├── MainFlutterWindow.swift
│   │   ├── Assets.xcassets
│   │   └── Base.lproj
│   │       └── MainMenu.xib
│   └── RunnerTests
│       └── RunnerTests.swift
├── windows
│   ├── .gitignore
│   ├── runner
│   │   ├── CMakeLists.txt
│   │   ├── flutter_window.cpp
│   │   ├── flutter_window.h
│   │   ├── generated_plugin_registrant.cc
│   │   ├── generated_plugin_registrant.h
│   │   ├── Runner.rc
│   │   └── CMakeLists.txt
│   └── flutter
│       ├── CMakeLists.txt
│       └── generated_plugin_registrant.cc
├── linux
│   ├── .gitignore
│   └── runner
│       ├── CMakeLists.txt
│       ├── main.cc
│       ├── my_application.cc
│       └── my_application.h
├── devtools_options.yaml
├── .metadata
├── lib
│   ├── img
│   │   └── README.md
│   ├── core
│   │   ├── config
│   │   │   ├── app_config.dart
│   │   │   └── env_config.dart
│   │   ├── constants
│   │   │   ├── app_assets.dart
│   │   │   └── app_constants.dart
│   │   ├── logging
│   │   │   └── app_logger.dart
│   │   ├── native
│   │   │   ├── android_native_bridge_service.dart
│   │   │   └── native_bridge_service.dart
│   │   ├── network
│   │   │   └── base_client.dart
│   │   ├── result
│   │   │   └── result.dart
│   │   ├── theme
│   │   │   ├── app_colors.dart
│   │   │   ├── app_text_styles.dart
│   │   │   └── app_theme.dart
│   │   └── widgets
│   │       ├── app_container.dart
│   │       ├── app_drawer.dart
│   │       ├── feature_card.dart
│   │       ├── gradient_ring.dart
│   │       ├── info_step.dart
│   │       ├── permission_card.dart
│   │       └── (other UI widgets)
│   ├── features
│   │   └── chat
│   │       ├── data
│   │       │   ├── chat_client.dart
│   │       │   └── chat_repository_impl.dart
│   │       ├── domain
│   │       │   ├── chat_repository.dart
│   │       │   ├── send_chat_message_usecase.dart
│   │       │   └── send_document_chat_message_usecase.dart
│   │       └── presentation
│   │           ├── chat_state.dart
│   │           └── (other presentation files)
│   ├── models
│   │   └── message_model.dart
│   ├── overlay
│   │   └── chat_overlay_manager.dart
│   ├── providers
│   │   ├── chat_provider.dart
│   │   ├── chat_window_state_provider.dart
│   │   └── scanner_provider.dart
│   ├── screens
│   │   ├── chat_screen.dart
│   │   ├── home
│   │   │   └── home_screen.dart
│   ├── services
│   │   ├── api_service.dart
│   │   ├── home_controller.dart
│   │   ├── keyboard_service.dart
│   │   ├── overlay_service.dart
│   │   └── permission_service.dart
│   ├── utils
│   │   ├── helpers.dart
│   │   ├── session_lifecycle_manager.dart
│   │   └── system_overlay_controller.dart
│   └── widgets
│       ├── chat_app_bar.dart
│       ├── chat_body.dart
│       ├── draggable_chat_icon.dart
│       ├── floating_chatbot.dart
│       ├── floating_scanner.dart
│       ├── html_floating_complete.dart
│       ├── message_bubble.dart
│       ├── message_input.dart
│       └── whatsapp_floating_chat.dart
├── test
│   └── features
│       └── chat
│           └── domain
│               └── send_chat_message_usecase_test.dart
└── (other platform‑specific files)
---

## Platform‑Specific Entry Points

### Android – `MainActivity.kt`
package com.Android.stremini_ai

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity

class MainActivity: FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize native bridge and other platform services here
    }
}

### iOS – `AppDelegate.swift`
import UIKit
import Flutter

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    // Initialize FlutterEngine and native plugins
    GeneratedPluginRegistrant.register(with: self)
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}

### macOS – `AppDelegate.swift`
import Cocoa
import FlutterMacOS

@NSApplicationMain
class AppDelegate: FlutterAppDelegate {
  override func applicationDidFinishLaunching(_ notification: Notification) {
    // macOS specific initialization
    super.applicationDidFinishLaunching(notification)
  }
}

### Windows – `flutter_window.cpp`
#include "flutter/window.h"
#include "my_application.h"

int main(int argc, char** argv) {
  auto window = std::make_unique<flutter::FlutterWindow>();
  MyApplication::GetInstance().SetWindow(window.get());
  window->Run();
  return 0;
}

### Linux – `main.cc`
#include "my_application.h"

int main(int argc, char** argv) {
  return MyApplication::GetInstance().Run(argc, argv);
}

### Web – `index.html`
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Stremini‑AI</title>
  <meta name="viewport" content="width=device-width,initial-scale=1">
</head>
<body>
  <script src="main.dart.js" type="application/javascript"></script>
</body>
</html>

---

## Core Flutter Code

### `lib/main.dart` – Application Entry Point
import 'package:flutter/material.dart';
import 'package:stremini_ai/screens/home/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const StreminiAIApp());
}

class StreminiAIApp extends StatelessWidget {
  const StreminiAIApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Stremini‑AI',
      theme: ThemeData.from(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: const HomeScreen(),
    );
  }
}

### `lib/core/config/app_config.dart` – Global Configuration
class AppConfig {
  static const String apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'https://api.stremini.ai');
  static const String clientId = String.fromEnvironment('CLIENT_ID', defaultValue: 'stremini-client');
  static const String clientSecret = String.fromEnvironment('CLIENT_SECRET', defaultValue: 'secret');
}

### `lib/core/theme/app_theme.dart` – Central Theme
import 'package:flutter/material.dart';
import 'app_colors.dart';
import 'app_text_styles.dart';

class AppTheme {
  static ThemeData lightTheme(BuildContext context) => ThemeData(
    colorScheme: ColorScheme.fromSeed(seedColor: AppColors.primary, brightness: Brightness.light),
    textTheme: AppTextStyles.textTheme,
    appBarTheme: const AppBarTheme(backgroundColor: AppColors.primary, foregroundColor: Colors.white),
    floatingActionButtonTheme: const FloatingActionButtonThemeData(backgroundColor: AppColors.accent),
  );
}

### `lib/core/theme/app_colors.dart` – Color Palette
class AppColors {
  static const Color primary = Color(0xFF3F51B5);
  static const Color accent = Color(0xFF00BCD4);
  static const Color background = Color(0xFFF5F5F5);
  static const Color surface = Colors.white;
}

### `lib/core/theme/app_text_styles.dart` – Text Styles
class AppTextStyles {
  static const TextTheme textTheme = TextTheme(
    headlineLarge: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
    titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
    bodySmall: TextStyle(fontSize: 14),
  );
}

### `lib/features/chat/domain/send_chat_message_usecase.dart` – Chat Use‑Case
import 'package:dartz/dartz.dart';
import '../../core/result/result.dart';
import '../../core/config/app_config.dart';
import '../data/chat_client.dart';

class SendChatMessageUseCase {
  final ChatClient _client;

  SendChatMessageUseCase(this._client);

  Future<Either<Error, Unit>> call(String message) async {
    try {
      await _client.send(message);
      return const Right(unit);
    } catch (e) {
      return Left(Error(e.toString()));
    }
  }
}

### `lib/features/chat/presentation/chat_state.dart` – Chat State Model
import 'package:equatable/equatable.dart';
import '../../models/message_model.dart';

class ChatState extends Equatable {
  final List<MessageModel> messages;
  final bool isLoading;

  const ChatState({this.messages = const [], this.isLoading = false});

  ChatState copyWith({List<MessageModel>? messages, bool? isLoading}) {
    return ChatState(
      messages: messages ?? this.messages,
      isLoading: isLoading ?? this.isLoading,
    );
  }

  @override
  List<Object?> get props => [messages, isLoading];
}

### `lib/features/chat/data/chat_client.dart` – API Client Wrapper
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../../core/config/app_config.dart';
import '../../models/message_model.dart';

class ChatClient {
  final http.Client _http;

  ChatClient(this._http);

  Future<void> send(String text) async {
    final uri = Uri.parse('${AppConfig.apiBaseUrl}/chat');
    final body = jsonEncode({'message': text});
    final response = await _http.post(
      uri,
      headers: {'Content-Type': 'application/json'},
      body: body,
    );

    if (response.statusCode != 200) {
      throw Exception('Failed to send chat message: ${response.statusCode}');
    }
  }

  Future<List<MessageModel>> fetchHistory() async {
    final uri = Uri.parse('${AppConfig.apiBaseUrl}/chat/history');
    final response = await _http.get(uri);
    if (response.statusCode != 200) {
      throw Exception('Failed to fetch chat history');
    }
    final List<dynamic> jsonList = jsonDecode(response.body);
    return jsonList.map((e) => MessageModel.fromJson(e)).toList();
  }
}

### `lib/screens/chat_screen.dart` – Chat UI
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../features/chat/presentation/chat_state.dart';
import '../features/chat/domain/send_chat_message_usecase.dart';
import '../widgets/message_bubble.dart';
import '../widgets/message_input.dart';
import '../providers/chat_provider.dart';

class ChatScreen extends StatelessWidget {
  const ChatScreen({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final chatProvider = Provider.of<ChatProvider>(context);
    return Scaffold(
      appBar: const ChatAppBar(),
      body: Column(
        children: [
          Expanded(
            child: Consumer<ChatProvider>(
              builder: (_, state, __) => ListView.builder(
                reverse: true,
                itemCount: state.messages.length,
                itemBuilder: (_, index) => MessageBubble(message: state.messages[index]),
              ),
            ),
          ),
          const MessageInput(),
        ],
      ),
    );
  }
}

### `lib/widgets/message_bubble.dart` – Message Bubble Widget
import 'package:flutter/material.dart';
import '../../features/chat/models/message_model.dart';

class MessageBubble extends StatelessWidget {
  final MessageModel message;

  const MessageBubble({Key? key, required this.message}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0, horizontal: 8.0),
      child: Align(
        alignment: message.isUser ? Alignment.centerRight : Alignment.centerLeft,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: message.isUser ? Colors.blueAccent : Colors.grey[300],
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(message.content, style: const TextStyle(fontSize: 16)),
        ),
      ),
    );
  }
}

### `lib/widgets/message_input.dart` – Input Area
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../features/chat/domain/send_chat_message_usecase.dart';
import '../providers/chat_provider.dart';

class MessageInput extends StatefulWidget {
  const MessageInput({Key? key}) : super(key: key);
  @override
  State<MessageInput> createState() => _MessageInputState();
}

class _MessageInputState extends State<MessageInput> {
  final TextEditingController _controller = TextEditingController();
  final ChatProvider _chatProvider = ChatProvider();

  void _sendMessage() async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;
    _chatProvider.addUserMessage(text);
    final result = await _chatProvider.sendChatMessage(text);
    if (result.isLeft) {
      // Show error snackbar
    }
    _controller.clear();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 4.0),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _controller,
              decoration: const InputDecoration(
                hintText: 'Type a message',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (_) => _sendMessage(),
            ),
          ),
          const SizedBox(width: 8),
          ElevatedButton(
            onPressed: _sendMessage,
            child: const Icon(Icons.send),
          ),
        ],
      ),
    );
  }
}

---

## Native Bridge Services

### `lib/core/native/android_native_bridge_service.dart`
import 'dart:io';
import 'package:flutter/services.dart';

class AndroidNativeBridgeService {
  static const MethodChannel _channel = MethodChannel('stremini_ai/android');

  Future<String?> getDeviceInfo() async {
    final String? info = await _channel.invokeMethod<String>('getDeviceInfo');
    return info;
  }

  Future<void> executeCommand(String command) async {
    await _channel.invokeMethod('executeCommand', {'command': command});
  }
}

### `lib/core/native/native_bridge_service.dart`
import 'dart:io';
import 'android_native_bridge_service.dart';
import 'ios_native_bridge_service.dart';
import 'linux_native_bridge_service.dart';
import 'macos_native_bridge_service.dart';
import 'windows_native_bridge_service.dart';

class NativeBridgeService {
  static NativeBridgeService? _instance;
  NativeBridgeService._();

  static NativeBridgeService get instance {
    _instance ??= NativeBridgeService._();
    return _instance!;
  }

  late final AndroidNativeBridgeService android;
  late final IosNativeBridgeService ios;
  late final LinuxNativeBridgeService linux;
  late final MacosNativeBridgeService macos;
  late final WindowsNativeBridgeService windows;

  void init() {
    if (Platform.isAndroid) {
      android = AndroidNativeBridgeService();
    } else if (Platform.isIOS) {
      ios = IosNativeBridgeService();
    } else if (Platform.isLinux) {
      linux = LinuxNativeBridgeService();
    } else if (Platform.isMacOS) {
      macos = MacosNativeBridgeService();
    } else if (Platform.isWindows) {
      windows = WindowsNativeBridgeService();
    }
  }
}

---

## Dependency Management

### `pubspec.yaml` – Core Dependencies
name: stremini_ai
description: A multi‑platform AI assistant.
publish_to: "none"
version: 1.0.0+1
environment:
  sdk: ">=2.19.0 <4.0.0"

dependencies:
  flutter:
    sdk: flutter
  cupertino_icons: ^1.0.6
  provider: ^6.0.5
  http: ^1.2.0
  equatable: ^2.0.5
  dartz: ^0.10.1
  flutter_native_splash: ^2.3.2
  flutter_secure_storage: ^9.0.0
  permission_handler: ^11.3.0
  # Add other feature‑specific packages here

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^3.0.0

flutter:
  uses-material-design: true
  assets:
    - assets/
  fonts:
    - family: OpenSans
      fonts:
        - asset: fonts/OpenSans-Regular.ttf
        - weight: 400

### `analysis_options.yaml` – Linting Rules
analyzer:
  enable-experiment:
    - non-nullable
  strong-mode: true
  exclude:
    - lib/**/*.g.dart
    - test/**/*.test.dart
linter:
  rules:
    opt_in_registry: true
    strong-mode: true
    prefer_single_quotes: true
    avoid_dynamic_calls: true
    always_declare_return_types: true
    avoid_void_async: true
    sort_pub_dependencies: true

---

## Testing

### `test/features/chat/domain/send_chat_message_usecase_test.dart`
import 'package:flutter_test/flutter_test.dart';
import 'package:stremini_ai/features/chat/domain/send_chat_message_usecase.dart';
import 'package:stremini_ai/features/chat/data/chat_client.dart';
import 'package:mockito/mockito.dart';
import 'package:dartz/dartz.dart';

class MockChatClient extends Mock implements ChatClient {}

void main() {
  late MockChatClient mockClient;
  late SendChatMessageUseCase useCase;

  setUp(() {
    mockClient = MockChatClient();
    useCase = SendChatMessageUseCase(mockClient);
  });

  test('should send chat message successfully', () async {
    when(mockClient.send(any)).thenAnswer((_) async {});

    final result = await useCase('Hello');

    expect(result, Right(unit));
    verify(mockClient.send('Hello')).called(1);
  });

  test('should return error when API fails', () async {
    when(mockClient.send(any)).thenThrow(Exception('Network error'));

    final result = await useCase('Error');

    expect(result, Left(Error('Network error')));
  });
}

---

## Build Configuration

### Android – `android/app/build.gradle.kts`
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.doraemon.flutter") // Example custom plugin
}

android {
    namespace = "com.Android.stremini_ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.Android.stremini_ai"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packagingOptions {
        resources {
            excludes += "/api/**"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    // Add other Android dependencies
}

### iOS – `ios/Runner.xcconfig` (Debug)
FLUTTER_ROOT = $(SRCROOT)/../.. 
FLUTTER_FRAMEWORK = $(FLUTTER_ROOT)/Flutter.framework
OTHER_LDFLAGS = -framework $(FLUTTER_FRAMEWORK)
*(Release configuration similar with additional optimization flags.)*

### macOS – `macos/Runner/Configs/Debug.xcconfig`
GCC_OPTIMIZATION_LEVEL = 0
OTHER_CPLUSPLUSFLAGS = -DDEBUG

### Windows – `windows/runner/CMakeLists.txt`
cmake_minimum_required(VERSION 3.16)

project(stremini_ai LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(flutter)

add_executable(stremini_ai WIN32
    main.cc
    my_application.cc
    my_application.h
)

target_link_libraries(stremini_ai PRIVATE flutter flutter_plugin)

### Linux – `linux/runner/CMakeLists.txt`
cmake_minimum_required(VERSION 3.16)

project(stremini_ai LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(flutter)

add_executable(stremini_ai
    main.cc
    my_application.cc
    my_application.h
)

target_link_libraries(stremini_ai PRIVATE flutter)

---

## Summary
The repository is organized into:

- **Platform‑specific native code** (`android`, `ios`, `macos`, `windows`, `linux`).
- **Flutter core** (`lib/core`, `lib/features`, `lib/screens`, `lib/widgets`, `lib/services`, `lib/providers`, `lib/utils`).
- **Configuration & tooling** (`pubspec.yaml`, `analysis_options.yaml`, build scripts).
- **Testing** (`test/`).

All entry points, key business logic, UI components, and native bridges are highlighted with representative code snippets to give a clear view of the project's architecture and implementation details.