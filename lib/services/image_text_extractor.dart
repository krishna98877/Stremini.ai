import 'dart:io';

import 'package:flutter/services.dart';
import '../core/security/input_sanitizer.dart';

class ImageTextExtractor {
  static const MethodChannel _channel = MethodChannel('stremini.ocr');

  static Future<String> extractText(String imagePath) async {
    if (!Platform.isAndroid) return '';
    final text = await _channel.invokeMethod<String>(
      'extractTextFromImage',
      {'path': imagePath},
    );
    return InputSanitizer.sanitizeExtractedImageText(text ?? '');
  }
}
