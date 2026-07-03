import 'package:flutter/services.dart';

import 'native_bridge_service.dart';

class AndroidNativeBridgeService implements NativeBridgeService {
  static const MethodChannel _platform = MethodChannel('com.Android.stremini_ai');

  @override
  Future<void> initialize({required Future<void> Function(String method) onEvent}) async {
    _platform.setMethodCallHandler((call) async {
      await onEvent(call.method);
      return true;
    });
  }
}
