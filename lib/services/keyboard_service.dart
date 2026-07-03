import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class KeyboardService {
  static const MethodChannel _channel = MethodChannel('stremini.keyboard');

  Future<bool> isKeyboardEnabled() async {
    if (!Platform.isAndroid) return false;
    try {
      final bool? enabled =
          await _channel.invokeMethod<bool>('isKeyboardEnabled');
      return enabled ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<bool> isKeyboardSelected() async {
    if (!Platform.isAndroid) return false;
    try {
      final bool? selected =
          await _channel.invokeMethod<bool>('isKeyboardSelected');
      return selected ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<void> openKeyboardSettings() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('openKeyboardSettings');
    } catch (e) {
      debugPrint('Error opening keyboard settings: $e');
    }
  }

  Future<void> showKeyboardPicker() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('showKeyboardPicker');
    } catch (e) {
      debugPrint('Error showing keyboard picker: $e');
    }
  }

  Future<void> openKeyboardSettingsActivity() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('openKeyboardSettingsActivity');
    } catch (e) {
      debugPrint('Error opening keyboard settings activity: $e');
    }
  }

  Future<KeyboardStatus> checkKeyboardStatus() async {
    final enabled = await isKeyboardEnabled();
    final selected = await isKeyboardSelected();

    return KeyboardStatus(
      isEnabled: enabled,
      isSelected: selected,
    );
  }
}

class KeyboardStatus {
  final bool isEnabled;
  final bool isSelected;

  const KeyboardStatus({
    required this.isEnabled,
    required this.isSelected,
  });

  bool get isActive => isEnabled && isSelected;
  bool get needsSetup => !isEnabled || !isSelected;
}
