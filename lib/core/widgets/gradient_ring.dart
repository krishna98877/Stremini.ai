import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

class GradientRing extends StatelessWidget {
  final double size;
  final Widget child;
  
  const GradientRing({
    super.key,
    required this.size,
    required this.child,
  });
  
  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: const BoxDecoration(
        shape: BoxShape.circle,
        gradient: AppColors.sweepGradient,
      ),
      child: Center(
        child: Container(
          width: size - 10,
          height: size - 10,
          decoration: const BoxDecoration(
            shape: BoxShape.circle,
            color: AppColors.black,
          ),
          child: child,
        ),
      ),
    );
  }
}
