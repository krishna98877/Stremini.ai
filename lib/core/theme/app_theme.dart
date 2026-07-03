import 'package:flutter/material.dart';
import 'app_colors.dart';

class AppTheme {
  // ─────────────────────────────────────────────────────────────────────────
  // DARK THEME
  // ─────────────────────────────────────────────────────────────────────────
  static ThemeData get darkTheme {
    const bg     = Color(0xFF000000);
    const surface= Color(0xE6111111);
    const surfaceHi = Color(0xF21A1A1A);
    const border = Color(0x1AFFFFFF); // glass border — white 10%
    const primary= AppColors.primary;
    const white  = Color(0xFFFFFFFF);
    const muted  = Color(0xFF6B7280);

    final base = ThemeData.dark(useMaterial3: true);

    return base.copyWith(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: bg,
      primaryColor: primary,

      colorScheme: const ColorScheme.dark(
        primary:          primary,
        onPrimary:        white,
        secondary:        AppColors.secondary,
        onSecondary:      white,
        surface:          surface,
        onSurface:        white,
        surfaceContainerHigh: surfaceHi,
        // FIX: background and surfaceVariant so ALL widgets
        // (Card, BottomSheet, Dialog, Drawer, etc.) pick up dark colors
        surfaceContainerHighest: surfaceHi,
        background:       bg,
        onBackground:     white,
        error:            AppColors.danger,
        onError:          white,
        outline:          border,
        outlineVariant:   border,
        shadow:           Colors.black,
      ),

      // Cards
      cardColor: surface,
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        shadowColor: Colors.transparent,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: const BorderSide(color: AppColors.glassBorder, width: 0.5),
        ),
      ),

      // App bar
      appBarTheme: const AppBarTheme(
        backgroundColor: bg,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: white, fontSize: 16,
          fontWeight: FontWeight.w700, letterSpacing: 0.3,
        ),
        iconTheme: IconThemeData(color: white),
        actionsIconTheme: IconThemeData(color: white),
      ),

      // Divider
      dividerColor: border,
      dividerTheme: const DividerThemeData(color: border, thickness: 1),

      // Icons
      iconTheme: const IconThemeData(color: white),

      // Text — every text style gets a color so Theme.of(ctx).textTheme works
      textTheme: const TextTheme(
        displayLarge:  TextStyle(color: white),
        displayMedium: TextStyle(color: white),
        displaySmall:  TextStyle(color: white),
        headlineLarge: TextStyle(color: white),
        headlineMedium:TextStyle(color: white),
        headlineSmall: TextStyle(color: white),
        titleLarge:    TextStyle(color: white),
        titleMedium:   TextStyle(color: white),
        titleSmall:    TextStyle(color: white),
        bodyLarge:     TextStyle(color: white),
        bodyMedium:    TextStyle(color: white),
        bodySmall:     TextStyle(color: muted),
        labelLarge:    TextStyle(color: white),
        labelMedium:   TextStyle(color: white),
        labelSmall:    TextStyle(color: Color(0xFF4A5568)),
      ),

      // Drawers
      drawerTheme: const DrawerThemeData(
        backgroundColor: Color(0xCC0A0A0A),
        surfaceTintColor: Colors.transparent,
      ),

      // Bottom sheet
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: Color(0xCC0F0F0F),
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),

      // Dialog
      dialogTheme: const DialogThemeData(
        backgroundColor: Color(0xE6111111),
        surfaceTintColor: Colors.transparent,
        titleTextStyle: TextStyle(color: white, fontSize: 17, fontWeight: FontWeight.w700),
        contentTextStyle: TextStyle(color: muted),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(24))),
      ),

      // Switch
      switchTheme: SwitchThemeData(
        thumbColor: MaterialStateProperty.resolveWith((s) =>
            s.contains(MaterialState.selected) ? primary : const Color(0xFF3A4255)),
        trackColor: MaterialStateProperty.resolveWith((s) =>
            s.contains(MaterialState.selected)
                ? primary.withOpacity(0.35)
                : const Color(0xFF1C1C1C)),
      ),

      // Input fields
      inputDecorationTheme: InputDecorationTheme(
        hintStyle: const TextStyle(color: Color(0xFF4A5568)),
        labelStyle: const TextStyle(color: muted),
        border: InputBorder.none,
        filled: true,
        fillColor: surface,
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: AppColors.glassBorder),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: AppColors.accentGlow, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: AppColors.danger),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: AppColors.danger),
        ),
      ),

      // Elevated button
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: white,
          surfaceTintColor: Colors.transparent,
          textStyle: const TextStyle(fontWeight: FontWeight.w600),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          elevation: 0,
        ),
      ),

      // Text button
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(foregroundColor: primary),
      ),

      // Popup menu
      popupMenuTheme: const PopupMenuThemeData(
        color: surface,
        surfaceTintColor: Colors.transparent,
        textStyle: TextStyle(color: white),
      ),

      // Dropdown
      dropdownMenuTheme: const DropdownMenuThemeData(
        menuStyle: MenuStyle(
          backgroundColor: MaterialStatePropertyAll(surface),
          surfaceTintColor: MaterialStatePropertyAll(Colors.transparent),
        ),
        textStyle: TextStyle(color: white),
      ),

      // Snackbar
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: surfaceHi,
        contentTextStyle: TextStyle(color: white),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(12))),
      ),

      // List tile
      listTileTheme: const ListTileThemeData(
        textColor: white,
        iconColor: white,
        tileColor: Colors.transparent,
      ),

      // Checkbox
      checkboxTheme: CheckboxThemeData(
        fillColor: MaterialStateProperty.resolveWith((s) =>
            s.contains(MaterialState.selected) ? primary : Colors.transparent),
        side: const BorderSide(color: border, width: 1.5),
      ),
    );
  }

  // ─────────────────────────────────────────────────────────────────────────
  // LIGHT THEME
  // ─────────────────────────────────────────────────────────────────────────
  static ThemeData get lightTheme {
    const bg      = Color(0xFFF7F8FA);
    const surface = Colors.white;
    const surfaceHi = Color(0xFFF3F4F6);
    const border  = Color(0xFFE8EAF0);
    const primary = AppColors.primary;
    const textDark= Color(0xFF0A0C10);
    const muted   = Color(0xFF6B7280);

    final base = ThemeData.light(useMaterial3: true);

    return base.copyWith(
      brightness: Brightness.light,
      scaffoldBackgroundColor: bg,
      primaryColor: primary,

      colorScheme: const ColorScheme.light(
        primary:          primary,
        onPrimary:        Colors.white,
        secondary:        AppColors.primary,
        onSecondary:      Colors.white,
        surface:          surface,
        onSurface:        textDark,
        surfaceContainerHigh: surfaceHi,
        surfaceContainerHighest: surfaceHi,
        background:       bg,
        onBackground:     textDark,
        error:            AppColors.danger,
        onError:          Colors.white,
        outline:          border,
        outlineVariant:   border,
      ),

      cardColor: surface,
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: const BorderSide(color: border),
        ),
      ),

      appBarTheme: const AppBarTheme(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: textDark, fontSize: 16,
          fontWeight: FontWeight.w700, letterSpacing: 0.3,
        ),
        iconTheme: IconThemeData(color: textDark),
      ),

      dividerColor: border,
      dividerTheme: const DividerThemeData(color: border, thickness: 1),
      iconTheme: const IconThemeData(color: textDark),

      textTheme: const TextTheme(
        displayLarge:   TextStyle(color: textDark),
        displayMedium:  TextStyle(color: textDark),
        displaySmall:   TextStyle(color: textDark),
        headlineLarge:  TextStyle(color: textDark),
        headlineMedium: TextStyle(color: textDark),
        headlineSmall:  TextStyle(color: textDark),
        titleLarge:     TextStyle(color: textDark),
        titleMedium:    TextStyle(color: textDark),
        titleSmall:     TextStyle(color: textDark),
        bodyLarge:      TextStyle(color: textDark),
        bodyMedium:     TextStyle(color: textDark),
        bodySmall:      TextStyle(color: muted),
        labelLarge:     TextStyle(color: textDark),
        labelMedium:    TextStyle(color: textDark),
        labelSmall:     TextStyle(color: Color(0xFF9CA3AF)),
      ),

      drawerTheme: const DrawerThemeData(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
      ),

      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),

      dialogTheme: const DialogThemeData(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: TextStyle(color: textDark, fontSize: 17, fontWeight: FontWeight.w700),
        contentTextStyle: TextStyle(color: muted),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(16))),
      ),

      switchTheme: SwitchThemeData(
        thumbColor: MaterialStateProperty.resolveWith((s) =>
            s.contains(MaterialState.selected) ? primary : const Color(0xFF9CA3AF)),
        trackColor: MaterialStateProperty.resolveWith((s) =>
            s.contains(MaterialState.selected)
                ? primary.withOpacity(0.3)
                : const Color(0xFFE8EAF0)),
      ),

      inputDecorationTheme: InputDecorationTheme(
        hintStyle: const TextStyle(color: Color(0xFF9CA3AF)),
        labelStyle: const TextStyle(color: muted),
        border: InputBorder.none,
        filled: true,
        fillColor: surfaceHi,
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: primary, width: 1.5),
        ),
      ),

      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: Colors.white,
          surfaceTintColor: Colors.transparent,
          textStyle: const TextStyle(fontWeight: FontWeight.w600),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          elevation: 0,
        ),
      ),

      snackBarTheme: SnackBarThemeData(
        backgroundColor: const Color(0xFF1A1A1A),
        contentTextStyle: const TextStyle(color: Colors.white),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),

      listTileTheme: const ListTileThemeData(
        textColor: textDark,
        iconColor: textDark,
      ),
    );
  }
}
