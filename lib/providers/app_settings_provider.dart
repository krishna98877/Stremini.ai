import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/message_model.dart';

class AppSettings {
  final bool notificationsEnabled;
  final bool hapticFeedback;
  final bool saveChatHistory;
  final String theme;
  final String language;
  final bool isLoaded; // tracks whether prefs have been loaded

  const AppSettings({
    required this.notificationsEnabled,
    required this.hapticFeedback,
    required this.saveChatHistory,
    required this.theme,
    required this.language,
    this.isLoaded = false,
  });

  factory AppSettings.defaults() => const AppSettings(
        notificationsEnabled: true,
                hapticFeedback: false,
        saveChatHistory: false,
        theme: 'Dark',
        language: 'English',
        isLoaded: false,
      );

  AppSettings copyWith({
    bool? notificationsEnabled,
    bool? hapticFeedback,
    bool? saveChatHistory,
    String? theme,
    String? language,
    bool? isLoaded,
  }) {
    return AppSettings(
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      hapticFeedback: hapticFeedback ?? this.hapticFeedback,
      saveChatHistory: saveChatHistory ?? this.saveChatHistory,
      theme: theme ?? this.theme,
      language: language ?? this.language,
      isLoaded: isLoaded ?? this.isLoaded,
    );
  }

  ThemeMode get themeMode {
    switch (theme) {
      case 'Light':
        return ThemeMode.light;
      case 'System Default':
        return ThemeMode.system;
      default:
        return ThemeMode.dark;
    }
  }

  Locale? get locale {
    switch (language) {
      case 'Hindi':
        return const Locale('hi');
      case 'Spanish':
        return const Locale('es');
      case 'French':
        return const Locale('fr');
      case 'Arabic':
        return const Locale('ar');
      case 'Japanese':
        return const Locale('ja');
      default:
        return const Locale('en');
    }
  }
}

// Use a single SharedPreferences instance key prefix
class _Keys {
  static const notifications = 'settings.notifications';
  static const haptic = 'settings.haptic';
  static const saveHistory = 'settings.saveHistory';
  static const theme = 'settings.theme';
  static const language = 'settings.language';
  static const chatHistory = 'chat.history';
}

final appSettingsProvider = NotifierProvider<AppSettingsNotifier, AppSettings>(
  AppSettingsNotifier.new,
);

class AppSettingsNotifier extends Notifier<AppSettings> {
  @override
  AppSettings build() {
    // Load persisted settings immediately on build.
    // We do this synchronously-ish by scheduling a microtask so that
    // the first frame shows defaults and the second frame shows real values.
    _load();
    return AppSettings.defaults();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    // Replace state in one shot — this triggers a single rebuild of all
    // widgets that watch appSettingsProvider, including MaterialApp.
    state = AppSettings(
      notificationsEnabled:
          prefs.getBool(_Keys.notifications) ?? AppSettings.defaults().notificationsEnabled,
      hapticFeedback:
          prefs.getBool(_Keys.haptic) ?? AppSettings.defaults().hapticFeedback,
      saveChatHistory:
          prefs.getBool(_Keys.saveHistory) ?? AppSettings.defaults().saveChatHistory,
      theme: prefs.getString(_Keys.theme) ?? AppSettings.defaults().theme,
      language: prefs.getString(_Keys.language) ?? AppSettings.defaults().language,
      isLoaded: true,
    );
  }

  // ── Setters — each persists AND updates state immediately ──────────────────

  Future<void> setNotificationsEnabled(bool value) async {
    state = state.copyWith(notificationsEnabled: value);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_Keys.notifications, value);
  }

  Future<void> setHapticFeedback(bool value) async {
    state = state.copyWith(hapticFeedback: value);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_Keys.haptic, value);
  }

  Future<void> setSaveChatHistory(bool value) async {
    state = state.copyWith(saveChatHistory: value);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_Keys.saveHistory, value);
    if (!value) await prefs.remove(_Keys.chatHistory);
  }

  // ── Chat history persistence ───────────────────────────────────────────────

  Future<void> persistChatHistory(List<Message> messages) async {
    if (!state.saveChatHistory) return;
    final prefs = await SharedPreferences.getInstance();
    final data = messages
        .where((m) => m.type != MessageType.typing)
        .map((m) => {
              'id': m.id,
              'text': m.text,
              'type': m.type.index,
              'timestamp': m.timestamp.toIso8601String(),
            })
        .toList(growable: false);
    await prefs.setString(_Keys.chatHistory, jsonEncode(data));
  }

  Future<List<Message>> loadChatHistory() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_Keys.chatHistory);
    if (raw == null || raw.isEmpty) return const [];
    try {
      final decoded = (jsonDecode(raw) as List)
          .map((e) => e as Map<String, dynamic>)
          .map((e) => Message(
                id: e['id'] as String,
                text: e['text'] as String,
                type: MessageType.values[(e['type'] as num).toInt()],
                timestamp: DateTime.parse(e['timestamp'] as String),
              ))
          .toList();
      return decoded;
    } catch (_) {
      return const [];
    }
  }

  Future<void> clearChatHistory() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_Keys.chatHistory);
  }
}