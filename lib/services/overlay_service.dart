import 'dart:io';
import 'package:flutter/services.dart';
import '../core/constants/app_constants.dart';

class OverlayService {
  static const MethodChannel _channel = MethodChannel(AppConstants.overlayChannel);
  
  Future<void> startOverlay() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('startOverlayService');
    } catch (e) {
      throw OverlayServiceException('Failed to start overlay: $e');
    }
  }
  
  Future<void> stopOverlay() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('stopOverlayService');
    } catch (e) {
      throw OverlayServiceException('Failed to stop overlay: $e');
    }
  }
}

class OverlayServiceException implements Exception {
  final String message;
  OverlayServiceException(this.message);
  
  @override
  String toString() => message;
}
