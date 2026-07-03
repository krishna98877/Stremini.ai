import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../theme/app_text_styles.dart';
import 'app_container.dart';

class FeatureCard extends StatelessWidget {
  final String title;
  final String description;
  final IconData icon;
  final Color iconColor;
  final String status;
  final Color statusColor;
  final List<String> badges;
  final Widget? trailing;
  final VoidCallback? onTap;

  const FeatureCard({
    super.key,
    required this.title,
    required this.description,
    required this.icon,
    required this.iconColor,
    required this.status,
    required this.statusColor,
    required this.badges,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return AppContainer(
      padding: const EdgeInsets.all(20),
      glass: true,
      border: const BorderSide(color: AppColors.glassBorder, width: 0.5),
      borderRadius: 18,
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              // Icon Container
              AppContainer(
                width: 56,
                height: 56,
                color: iconColor.withValues(alpha: 0.15),
                borderRadius: 14,
                child: Icon(icon, color: iconColor, size: 28),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: AppTextStyles.h3),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Container(
                          width: 8,
                          height: 8,
                          decoration: BoxDecoration(
                            color: statusColor,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          status,
                          style: AppTextStyles.body3.copyWith(
                            color: statusColor,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              if (trailing != null) trailing!,
            ],
          ),
          const SizedBox(height: 16),
          Text(description, style: AppTextStyles.subtitle1),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: badges.map((badge) {
              return AppContainer(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                color: iconColor.withValues(alpha: 0.08),
                borderRadius: 12,
                border: BorderSide(color: iconColor.withValues(alpha: 0.2)),
                child: Text(
                  badge,
                  style: AppTextStyles.caption.copyWith(color: iconColor),
                ),
              );
            }).toList(),
          ),
        ],
      ),
    );
  }
}