import 'dart:ui';
import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

class AppContainer extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry? padding;
  final EdgeInsetsGeometry? margin;
  final Color? color;
  final double? borderRadius;
  final BorderSide? border;
  final List<BoxShadow>? shadows;
  final Gradient? gradient;
  final double? width;
  final double? height;
  final VoidCallback? onTap;
  final bool glass;
  
  const AppContainer({
    super.key,
    required this.child,
    this.padding,
    this.margin,
    this.color,
    this.borderRadius,
    this.border,
    this.shadows,
    this.gradient,
    this.width,
    this.height,
    this.onTap,
    this.glass = false,
  });
  
  @override
  Widget build(BuildContext context) {
    final radius = borderRadius ?? 16;
    final effectiveColor = gradient == null ? (color ?? AppColors.glass) : null;
    final effectiveBorder = border ?? BorderSide(color: AppColors.glassBorder, width: 0.5);

    Widget container;
    if (glass) {
      container = ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
          child: Container(
            width: width,
            height: height,
            padding: padding,
            margin: margin,
            decoration: BoxDecoration(
              color: effectiveColor,
              gradient: gradient,
              borderRadius: BorderRadius.circular(radius),
              border: Border.fromBorderSide(effectiveBorder),
              boxShadow: shadows,
            ),
            child: child,
          ),
        ),
      );
    } else {
      container = Container(
        width: width,
        height: height,
        padding: padding,
        margin: margin,
        decoration: BoxDecoration(
          color: effectiveColor,
          gradient: gradient,
          borderRadius: BorderRadius.circular(radius),
          border: border != null ? Border.fromBorderSide(border!) : null,
          boxShadow: shadows,
        ),
        child: child,
      );
    }
    
    if (onTap != null) {
      return InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(radius),
        child: container,
      );
    }
    
    return container;
  }
}