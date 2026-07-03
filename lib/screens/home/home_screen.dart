import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/theme/app_colors.dart';
import '../../core/widgets/app_drawer.dart';
import '../../controllers/home_controller.dart';
import '../../providers/app_settings_provider.dart';
import '../../core/localization/app_strings.dart';
import '../../services/keyboard_service.dart';
import '../chat_screen.dart';
import '../settings_screen.dart';
import '../contact_us_screen.dart';

final keyboardServiceProvider =
    Provider<KeyboardService>((ref) => KeyboardService());
final keyboardStatusProvider = FutureProvider<KeyboardStatus>((ref) async {
  final service = ref.watch(keyboardServiceProvider);
  return await service.checkKeyboardStatus();
});

Widget _glassCard({required Widget child, double radius = 18, EdgeInsets? padding}) {
  return ClipRRect(
    borderRadius: BorderRadius.circular(radius),
    child: BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
      child: Container(
        padding: padding,
        decoration: BoxDecoration(
          color: AppColors.glass,
          borderRadius: BorderRadius.circular(radius),
          border: Border.all(color: AppColors.glassBorder, width: 0.5),
        ),
        child: child,
      ),
    ),
  );
}

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(homeControllerProvider.notifier).checkPermissions();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.read(homeControllerProvider.notifier).checkPermissions();
    }
  }

  String _greeting() {
    final hour = DateTime.now().hour;
    if (hour < 12) return 'Good morning,';
    if (hour < 18) return 'Good afternoon,';
    return 'Good evening,';
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(homeControllerProvider);
    final controller = ref.read(homeControllerProvider.notifier);
    final keyboardStatus = ref.watch(keyboardStatusProvider);
    final settings = ref.watch(appSettingsProvider);
    String tr(String key) => AppStrings.t(settings.language, key);

    ref.listen(homeControllerProvider, (previous, next) {
      if (next.errorMessage != null) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(next.errorMessage!),
            backgroundColor: AppColors.danger,
          ),
        );
        controller.clearError();
      }
    });

    return Scaffold(
      backgroundColor: Colors.black,
      drawer: _buildDrawer(context, tr),
      body: SafeArea(
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(),
          slivers: [
            SliverToBoxAdapter(child: _buildAppBar(context)),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const SizedBox(height: 28),
                    Text(
                      _greeting(),
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 40,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.5,
                        height: 1.1,
                      ),
                    ),

                    const SizedBox(height: 10),
                    Text(
                      'Operational status: ${state.bubbleActive ? "Nominal" : "Standby"}',
                      style: const TextStyle(
                        color: Color(0xFF6B7280),
                        fontSize: 15,
                        letterSpacing: 0.1,
                      ),
                    ),
                    const SizedBox(height: 28),
                    _buildAgentCard(state, controller),
                    const SizedBox(height: 16),
                    _buildSystemAccess(state, controller),
                    const SizedBox(height: 28),
                    _buildSectionLabel('CORE MODULES'),
                    const SizedBox(height: 14),
                    _buildKeyboardModule(
                        context, state, controller, keyboardStatus),
                    const SizedBox(height: 28),
                    if (!state.permissionStatus.hasAll) ...[
                      _buildSectionLabel('REQUIRED PERMISSIONS'),
                      const SizedBox(height: 14),
                      _buildPermissionsSection(state, controller),
                      const SizedBox(height: 28),
                    ],
                    const SizedBox(height: 40),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppBar(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      color: Colors.black,
      child: Row(
        children: [
          Builder(
            builder: (ctx) => GestureDetector(
              onTap: () => Scaffold.of(ctx).openDrawer(),
              child:
                  const Icon(Icons.menu, color: Colors.white, size: 28),
            ),
          ),
          const SizedBox(width: 16),
          const Text(
            'STREMINI AI',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w700,
              letterSpacing: 2.0,
            ),
          ),
          const Spacer(),
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: AppColors.glass,
              borderRadius: BorderRadius.circular(9),
              border: Border.all(color: AppColors.glassBorder, width: 0.5),
            ),
            clipBehavior: Clip.antiAlias,
            child: Image.asset(
              'lib/img/logo.jpg',
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => const Icon(
                Icons.auto_awesome,
                color: Color(0xFF23A6E2),
                size: 18,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAgentCard(HomeState state, HomeController controller) {
    final isActive = state.bubbleActive;
    return _glassCard(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            width: 9,
            height: 9,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isActive
                  ? const Color(0xFF00F0FF)
                  : const Color(0xFF3A4255),
              boxShadow: isActive
                  ? [BoxShadow(color: const Color(0xFF00F0FF).withValues(alpha: 0.5), blurRadius: 8)]
                  : null,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  isActive ? 'AI Agent Active' : 'AI Agent Inactive',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  isActive
                      ? 'System-wide assistant running'
                      : 'Tap Start to activate',
                  style: const TextStyle(
                      color: Color(0xFF6B7280), fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          _agentBtn('Pause', const Color(0xFF1A1A1A),
              const Color(0xFF8B95A6), () async {
            await controller.toggleBubble(false);
          }),
          const SizedBox(width: 8),
          _agentBtn(
            isActive ? 'Settings' : 'Start',
            const Color(0xFF1A1A1A),
            Colors.white,
            () async {
              if (!isActive) await controller.toggleBubble(true);
            },
          ),
        ],
      ),
    );
  }

  Widget _agentBtn(
      String label, Color bg, Color textColor, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          color: AppColors.glassDark,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AppColors.glassBorder, width: 0.5),
        ),
        child: Text(
          label,
          style: TextStyle(
              color: textColor,
              fontSize: 12,
              fontWeight: FontWeight.w500),
        ),
      ),
    );
  }

  Widget _buildSystemAccess(HomeState state, HomeController controller) {
    return _glassCard(
      child: Column(
        children: [
          _permissionRow(
            icon: Icons.layers_outlined,
            label: 'Screen Overlay',
            isEnabled: state.permissionStatus.hasOverlay,
            onTap: () => controller.requestOverlayPermission(),
            isLast: false,
          ),
          _permissionRow(
            icon: Icons.mic_none_outlined,
            label: 'Microphone',
            isEnabled: state.permissionStatus.hasMicrophone,
            onTap: () => controller.requestMicrophonePermission(),
            isLast: true,
          ),
        ],
      ),
    );
  }

  Widget _permissionRow({
    required IconData icon,
    required String label,
    required bool isEnabled,
    required VoidCallback onTap,
    required bool isLast,
  }) {
    return Column(
      children: [
        Padding(
          padding:
              const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
          child: Row(
            children: [
              Icon(icon, color: const Color(0xFF6B7280), size: 20),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.w400),
                ),
              ),
              if (isEnabled) ...[
                const Text(
                  'ENABLED',
                  style: TextStyle(
                    color: Color(0xFF4A5568),
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.0,
                  ),
                ),
                const SizedBox(width: 10),
                Container(
                  width: 22,
                  height: 22,
                  decoration: const BoxDecoration(
                    shape: BoxShape.circle,
                    color: Color(0xFF23A6E2),
                  ),
                  child: const Icon(Icons.check,
                      color: Colors.white, size: 13),
                ),
              ] else
                GestureDetector(
                  onTap: onTap,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: AppColors.glassDark,
                      borderRadius: BorderRadius.circular(7),
                      border:
                          Border.all(color: AppColors.glassBorder, width: 0.5),
                    ),
                    child: const Text(
                      'Enable',
                      style: TextStyle(
                          color: Color(0xFF23A6E2),
                          fontSize: 12,
                          fontWeight: FontWeight.w500),
                    ),
                  ),
                ),
            ],
          ),
        ),
        if (!isLast)
          Divider(
              height: 1,
              color: AppColors.glassBorder,
              indent: 18,
              endIndent: 18),
      ],
    );
  }

  Widget _buildSectionLabel(String label) {
    return Text(
      label,
      style: const TextStyle(
        color: Color(0xFF3A4255),
        fontSize: 11,
        fontWeight: FontWeight.w600,
        letterSpacing: 2.0,
      ),
    );
  }

  Widget _buildKeyboardModule(
    BuildContext context,
    HomeState state,
    HomeController controller,
    AsyncValue<KeyboardStatus> keyboardStatus,
  ) {
    return keyboardStatus.when(
      data: (status) => _moduleCard(
        icon: Icons.keyboard_outlined,
        iconColor: const Color(0xFF23A6E2),
        title: 'AI Keyboard',
        subtitle: status.isActive ? 'Ready to type' : 'Needs setup',
        statusLabel: status.isActive ? 'ACTIVE' : 'SETUP',
        statusColor: status.isActive
            ? const Color(0xFF34C47C)
            : const Color(0xFFE08A23),
        onTap: () => _openKeyboardSetup(context),
      ),
      loading: () => _moduleCard(
        icon: Icons.keyboard_outlined,
        iconColor: const Color(0xFF23A6E2),
        title: 'AI Keyboard',
        subtitle: 'Checking...',
        statusLabel: '...',
        statusColor: const Color(0xFF4A5568),
        onTap: () => _openKeyboardSetup(context),
      ),
      error: (_, __) => _moduleCard(
        icon: Icons.keyboard_outlined,
        iconColor: const Color(0xFF23A6E2),
        title: 'AI Keyboard',
        subtitle: 'Open setup',
        statusLabel: 'SETUP',
        statusColor: const Color(0xFFE08A23),
        onTap: () => _openKeyboardSetup(context),
      ),
    );
  }

  Widget _moduleCard({
    required IconData icon,
    required Color iconColor,
    required String title,
    required String subtitle,
    required String statusLabel,
    required Color statusColor,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: _glassCard(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 42,
              height: 42,
              decoration: BoxDecoration(
                color: iconColor.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: iconColor, size: 20),
            ),
            const SizedBox(height: 14),
            Text(title,
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 13,
                    fontWeight: FontWeight.w600)),
            const SizedBox(height: 3),
            Text(subtitle,
                style: const TextStyle(
                    color: Color(0xFF4A5568), fontSize: 11),
                maxLines: 1,
                overflow: TextOverflow.ellipsis),
            const SizedBox(height: 10),
            Row(
              children: [
                Container(
                  width: 6,
                  height: 6,
                  decoration: BoxDecoration(
                      shape: BoxShape.circle, color: statusColor),
                ),
                const SizedBox(width: 5),
                Text(
                  statusLabel,
                  style: TextStyle(
                    color: statusColor,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.8,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionsSection(
      HomeState state, HomeController controller) {
    return Column(
      children: [
        if (state.permissionStatus.needsOverlay)
          _permCard(
            'Overlay Permission',
            'Required for floating bubble',
            Icons.bubble_chart_outlined,
            const Color(0xFFE08A23),
            () => controller.requestOverlayPermission(),
          ),
        if (state.permissionStatus.needsMicrophone) ...[
          if (state.permissionStatus.needsOverlay)
            const SizedBox(height: 10),
          _permCard(
            'Microphone',
            'Required for voice commands',
            Icons.mic_none_outlined,
            const Color(0xFF23A6E2),
            () => controller.requestMicrophonePermission(),
          ),
        ],
      ],
    );
  }

  Widget _permCard(String title, String description, IconData icon,
      Color color, VoidCallback onTap) {
    return _glassCard(
      radius: 14,
      padding: const EdgeInsets.all(14),
      child: Row(
        children: [
          Icon(icon, color: color, size: 22),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: TextStyle(
                        color: color,
                        fontSize: 13,
                        fontWeight: FontWeight.w600)),
                const SizedBox(height: 2),
                Text(description,
                    style: const TextStyle(
                        color: Color(0xFF4A5568), fontSize: 11)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          GestureDetector(
            onTap: onTap,
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: color.withValues(alpha: 0.15), width: 0.5),
              ),
              child: Text('Enable',
                  style: TextStyle(
                      color: color,
                      fontSize: 12,
                      fontWeight: FontWeight.w500)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDrawer(
      BuildContext context, String Function(String) tr) {
    return AppDrawer(
      items: [
        AppDrawerItem(
          icon: Icons.home_outlined,
          title: 'Home',
          onTap: () => Navigator.pop(context),
        ),
        AppDrawerItem(
          icon: Icons.chat_bubble_outline,
          title: 'Quick Chat',
          onTap: () {
            Navigator.pop(context);
            Navigator.push(context,
                MaterialPageRoute(builder: (_) => ChatScreen()));
          },
        ),
        AppDrawerItem(
          icon: Icons.keyboard_outlined,
          title: 'AI Keyboard',
          onTap: () {
            Navigator.pop(context);
            _openKeyboardSetup(context);
          },
        ),
        AppDrawerItem(
          icon: Icons.settings_outlined,
          title: tr('settings'),
          onTap: () {
            Navigator.pop(context);
            Navigator.push(context,
                MaterialPageRoute(builder: (_) => SettingsScreen()));
          },
        ),
        AppDrawerItem(
          icon: Icons.help_outline,
          title: 'Contact Us',
          onTap: () {
            Navigator.pop(context);
            Navigator.push(context,
                MaterialPageRoute(builder: (_) => ContactUsScreen()));
          },
        ),

      ],
    );
  }

  Future<void> _openKeyboardSetup(BuildContext context) async {
    final service = ref.read(keyboardServiceProvider);
    final status = await service.checkKeyboardStatus();
    if (!status.isEnabled) {
      await Navigator.push(context,
          MaterialPageRoute(builder: (_) => const SettingsScreen()));
      if (mounted) ref.invalidate(keyboardStatusProvider);
      return;
    }
    if (!status.isSelected) {
      await service.showKeyboardPicker();
      if (mounted) ref.invalidate(keyboardStatusProvider);
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
          content: Text('AI Keyboard is already active'),
          backgroundColor: AppColors.success),
    );
  }
}