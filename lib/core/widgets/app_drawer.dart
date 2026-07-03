import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../theme/app_text_styles.dart';
import 'app_container.dart';

class AppDrawerItem {
  final IconData icon;
  final String title;
  final VoidCallback onTap;
  
  const AppDrawerItem({
    required this.icon,
    required this.title,
    required this.onTap,
  });
}

class AppDrawer extends StatelessWidget {
  final List<AppDrawerItem> items;
  
  const AppDrawer({
    super.key,
    required this.items,
  });
  
  @override
  Widget build(BuildContext context) {
    return Drawer(
      backgroundColor: const Color(0xCC0A0A0A),
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Search Bar
              AppContainer(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                glass: true,
                border: const BorderSide(color: AppColors.glassBorder, width: 0.5),
                borderRadius: 25,
                child: const Row(
                  children: [
                    Expanded(
                      child: Text(
                        'Search for features',
                        style: TextStyle(color: Color(0x3DFFFFFF), fontSize: 14),
                      ),
                    ),
                    Icon(Icons.search, color: Color(0x3DFFFFFF)),
                  ],
                ),
              ),
              const SizedBox(height: 40),
              
              // Menu Items
              ...items.map((item) => ListTile(
                leading: Icon(item.icon, color: AppColors.white, size: 24),
                title: Text(item.title, style: AppTextStyles.body2),
                onTap: item.onTap,
                hoverColor: AppColors.glass,
              )),
            ],
          ),
        ),
      ),
    );
  }
}