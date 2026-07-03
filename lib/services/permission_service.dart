import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../core/constants/app_constants.dart';

class PermissionService {
  static const MethodChannel _channel =
      MethodChannel(AppConstants.overlayChannel);

  Future<bool> hasOverlayPermission() async {
    if (!Platform.isAndroid) return true;
    try {
      final bool? has =
          await _channel.invokeMethod<bool>('hasOverlayPermission');
      return has ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<void> requestOverlayPermission() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('requestOverlayPermission');
    } catch (e) {
      debugPrint('Error requesting overlay permission: $e');
    }
  }

  Future<bool> hasMicrophonePermission() async {
    if (!Platform.isAndroid) return true;
    try {
      final bool? has =
          await _channel.invokeMethod<bool>('hasMicrophonePermission');
      return has ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<void> requestMicrophonePermission() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('requestMicrophonePermission');
    } catch (e) {
      debugPrint('Error requesting microphone permission: $e');
    }
  }

  Future<PermissionStatus> checkAllPermissions() async {
    final hasOverlay = await hasOverlayPermission();
    final hasMicrophone = await hasMicrophonePermission();

    return PermissionStatus(
      hasOverlay: hasOverlay,
      hasMicrophone: hasMicrophone,
    );
  }
}

class PermissionStatus {
  final bool hasOverlay;
  final bool hasMicrophone;

  const PermissionStatus({
    required this.hasOverlay,
    required this.hasMicrophone,
  });

  bool get hasAll => hasOverlay && hasMicrophone;
  bool get needsOverlay => !hasOverlay;
  bool get needsMicrophone => !hasMicrophone;
}
