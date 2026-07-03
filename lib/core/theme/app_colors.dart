import 'package:flutter/material.dart';

class AppColors {
  static const primary = Color(0xFF23A6E2);
  static const primaryDark = Color(0xFF0066FF);
  static const secondary = Color(0xFFAA75F4);
  static const black = Color(0xFF000000);
  static const darkGray = Color(0xFF1A1A1A);
  static const mediumGray = Color(0xFF272727);
  static const lightGray = Color(0xFF666666);
  static const danger = Color(0xFFD32F2F);
  static const dangerLight = Color(0xFFFF1744);
  static const warning = Color(0xFFFF9800);
  static const warningLight = Color(0xFFF57C00);
  static const success = Color(0xFF4CAF50);
  static const info = Color(0xFF2196F3);
  static const emotional = Color(0xFF9C27B0);
  static const scanCyan = Color(0xFF00D9FF);
  static const scanPurple = Color(0xFFE040FB);
  static const white = Color(0xFFFFFFFF);
  static const textGray = Color(0xFFAAAAAA);
  static const textLight = Color(0xFFB3FFFFFF);
  static const hintGray = Color(0xFF666666);
  
  static const primaryGradient = LinearGradient(colors: [primary, primaryDark]);
  static const sweepGradient = SweepGradient(colors: [primary, secondary, primaryDark, primary]);

  // Glassmorphic palette
  static const glass = Color(0x0DFFFFFF);        // white @ 5%
  static const glassBorder = Color(0x1AFFFFFF);   // white @ 10%
  static const glassHi = Color(0x14FFFFFF);       // white @ 8%
  static const glassDark = Color(0x08FFFFFF);      // white @ 3%
  static const surfaceGlass = Color(0xCC111111);  // translucent dark
  static const accentGlow = Color(0x3300F0FF);    // cyan glow @ 20%
}
