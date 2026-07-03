import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../theme/app_text_styles.dart';

class InfoStep extends StatelessWidget {
  final String number;
  final String text;
  final Color? color;

  const InfoStep({
    super.key,
    required this.number,
    required this.text,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    final stepColor = color ?? AppColors.scanCyan;

    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              color: stepColor.withValues(alpha: 0.2),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: stepColor),
            ),
            child: Center(
              child: Text(
                number,
                style: AppTextStyles.body3.copyWith(
                  color: stepColor,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: AppTextStyles.subtitle1),
          ),
        ],
      ),
    );
  }
}
