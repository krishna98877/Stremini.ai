// lib/features/connectors/connectors_panel.dart
//
// Manus-style connector panel for Stremini.
// Drop-in: call ConnectorsPanel.show(context, manager) from anywhere.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../services/composio_service.dart';
import '../../core/theme/app_colors.dart';

// ─────────────────────────────────────────────────────────────
//  Entry point
// ─────────────────────────────────────────────────────────────
class ConnectorsPanel {
  static Future<void> show(
    BuildContext context,
    ComposioServiceManager manager,
  ) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black54,
      transitionAnimationController: AnimationController(
        vsync: Navigator.of(context),
        duration: const Duration(milliseconds: 380),
      ),
      builder: (_) => _ConnectorsPanelSheet(manager: manager),
    );
  }
}

// ─────────────────────────────────────────────────────────────
//  Bottom sheet
// ─────────────────────────────────────────────────────────────
class _ConnectorsPanelSheet extends StatefulWidget {
  final ComposioServiceManager manager;
  const _ConnectorsPanelSheet({required this.manager});

  @override
  State<_ConnectorsPanelSheet> createState() => _ConnectorsPanelSheetState();
}

class _ConnectorsPanelSheetState extends State<_ConnectorsPanelSheet>
    with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;
  late Animation<double> _fade;
  late Animation<Offset> _slide;

  String _search = '';
  String? _connecting; // serviceId currently loading

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 380));
    _fade = CurvedAnimation(parent: _ctrl, curve: Curves.easeOut);
    _slide = Tween<Offset>(begin: const Offset(0, 0.08), end: Offset.zero)
        .animate(CurvedAnimation(parent: _ctrl, curve: Curves.easeOutCubic));
    _ctrl.forward();

    // Refresh status when panel opens
    widget.manager.refreshServiceStatuses().then((_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  List<ComposioService> get _filtered {
    if (_search.isEmpty) return kComposioServices;
    final q = _search.toLowerCase();
    return kComposioServices.where((s) => s.name.toLowerCase().contains(q)).toList();
  }

  // Toggle state for connected services (enable/disable automation)
  final Map<String, bool> _toggleStates = {};

  Future<void> _toggle(ComposioService svc) async {
    HapticFeedback.lightImpact();
    final connected = widget.manager.isServiceConnected(svc.id);

    if (!connected) {
      // Connect — opens Chrome for OAuth
      setState(() => _connecting = svc.id);
      await widget.manager.connectService(svc.id);
      for (int i = 0; i < 30; i++) {
        await Future.delayed(const Duration(seconds: 1));
        await widget.manager.refreshServiceStatuses();
        if (widget.manager.isServiceConnected(svc.id)) break;
        if (!mounted) return;
      }
      if (mounted) setState(() => _connecting = null);
    }
    // If connected: the toggle switch handles enable/disable
  }

  void _onToggleChanged(ComposioService svc, bool value) {
    setState(() => _toggleStates[svc.id] = value);
  }

  Future<void> _disconnectWithConfirm(ComposioService svc) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1A1A1A),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text('Disconnect ${svc.name}?',
            style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600)),
        content: Text('You won\'t be able to automate ${svc.name} until you reconnect.',
            style: const TextStyle(color: Color(0xFF888888), fontSize: 13)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel', style: TextStyle(color: Color(0xFF666666))),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(
              backgroundColor: Colors.white,
              foregroundColor: Colors.black,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('Disconnect', style: TextStyle(fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    if (!mounted) return;

    setState(() => _connecting = svc.id);
    await widget.manager.disconnectService(svc.id);
    await widget.manager.refreshServiceStatuses();
    if (mounted) setState(() => _connecting = null);
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return FadeTransition(
      opacity: _fade,
      child: SlideTransition(
        position: _slide,
        child: Container(
          margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
          decoration: BoxDecoration(
            color: const Color(0xFF111111),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: const Color(0xFF252525)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildHandle(),
              _buildHeader(),
              _buildSearch(),
              _buildStatusBar(),
              // Compact list — shows ~4-5 rows, scrollable for the rest.
              // Matches the blue-box size the user drew (roughly 40% of screen).
              ConstrainedBox(
                constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.35),
                child: _buildGrid(),
              ),
              SizedBox(height: 12 + bottom),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHandle() => Padding(
        padding: const EdgeInsets.only(top: 12, bottom: 4),
        child: Container(
          width: 36,
          height: 4,
          decoration: BoxDecoration(
            color: const Color(0xFF333333),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
      );

  Widget _buildHeader() => Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
        child: Row(
          children: [
            Container(
              width: 34,
              height: 34,
              decoration: BoxDecoration(
                color: AppColors.primary.withOpacity(0.12),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(Icons.bolt_rounded, color: AppColors.primary, size: 18),
            ),
            const SizedBox(width: 12),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Text('Connectors',
                    style: TextStyle(
                        color: Colors.white,
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.3)),
                Text('Connect apps to automate anything',
                    style: TextStyle(color: Color(0xFF666666), fontSize: 12)),
              ],
            ),
            const Spacer(),
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: const Color(0xFF1E1E1E),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(Icons.close_rounded, color: Color(0xFF666666), size: 16),
              ),
            ),
          ],
        ),
      );

  Widget _buildSearch() => Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
        child: Container(
          height: 40,
          decoration: BoxDecoration(
            color: const Color(0xFF1A1A1A),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFF252525)),
          ),
          child: TextField(
            style: const TextStyle(color: Colors.white, fontSize: 14),
            decoration: InputDecoration(
              hintText: 'Search apps...',
              hintStyle: const TextStyle(color: Color(0xFF444444), fontSize: 14),
              prefixIcon: const Icon(Icons.search_rounded, color: Color(0xFF444444), size: 18),
              border: InputBorder.none,
              contentPadding: const EdgeInsets.symmetric(vertical: 11),
            ),
            onChanged: (v) => setState(() => _search = v),
          ),
        ),
      );

  Widget _buildStatusBar() {
    final connected = kComposioServices
        .where((s) => widget.manager.isServiceConnected(s.id))
        .length;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 10),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFF1A1A1A),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              children: [
                Container(
                  width: 6,
                  height: 6,
                  decoration: const BoxDecoration(
                    color: Color(0xFF666666),
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 6),
                Text(
                  '$connected connected',
                  style: const TextStyle(
                    color: Color(0xFF888888),
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
          const Spacer(),
          Text('${kComposioServices.length} apps',
              style: const TextStyle(color: Color(0xFF444444), fontSize: 12)),
        ],
      ),
    );
  }

  Widget _buildGrid() {
    final services = _filtered;
    if (services.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 32),
        child: Center(
            child: Text('No apps found',
                style: TextStyle(color: Color(0xFF444444), fontSize: 14))),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      shrinkWrap: true,
      itemCount: services.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (_, i) => _ServiceTile(
        service: services[i],
        isConnected: widget.manager.isServiceConnected(services[i].id),
        isLoading: _connecting == services[i].id,
        isToggledOn: _toggleStates[services[i].id] ?? false,
        onTap: () => _toggle(services[i]),
        onToggleChanged: (val) => _onToggleChanged(services[i], val),
        onDisconnect: () => _disconnectWithConfirm(services[i]),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────
//  Service tile — Manus-style row card
// ─────────────────────────────────────────────────────────────
class _ServiceTile extends StatefulWidget {
  final ComposioService service;
  final bool isConnected;
  final bool isLoading;
  final bool isToggledOn;
  final VoidCallback onTap;
  final ValueChanged<bool> onToggleChanged;
  final VoidCallback onDisconnect;

  const _ServiceTile({
    required this.service,
    required this.isConnected,
    required this.isLoading,
    required this.isToggledOn,
    required this.onTap,
    required this.onToggleChanged,
    required this.onDisconnect,
  });

  @override
  State<_ServiceTile> createState() => _ServiceTileState();
}

class _ServiceTileState extends State<_ServiceTile>
    with SingleTickerProviderStateMixin {
  late AnimationController _press;

  @override
  void initState() {
    super.initState();
    _press = AnimationController(vsync: this, duration: const Duration(milliseconds: 100));
  }

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = Color(widget.service.colorValue);
    return GestureDetector(
      onTapDown: (_) => _press.forward(),
      onTapUp: (_) {
        _press.reverse();
        widget.onTap();
      },
      onTapCancel: () => _press.reverse(),
      child: ScaleTransition(
        scale: Tween<double>(begin: 1.0, end: 0.97)
            .animate(CurvedAnimation(parent: _press, curve: Curves.easeOut)),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: const Color(0xFF181818),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: const Color(0xFF222222)),
          ),
          child: Row(
            children: [
              // Logo — brand-colored circle with white initial
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: color,
                  borderRadius: BorderRadius.circular(11),
                ),
                alignment: Alignment.center,
                child: Text(
                  widget.service.iconChar,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(width: 14),
              // Name + status text
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      widget.service.name,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 200),
                      child: Text(
                        widget.isConnected ? 'Connected' : 'Not connected',
                        key: ValueKey(widget.isConnected),
                        style: const TextStyle(
                          color: Color(0xFF666666),
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              // Right side: toggle (connected) or Connect button (disconnected)
              if (widget.isLoading)
                const SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              else if (widget.isConnected)
                // Toggle + disconnect button side by side
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Switch(
                      value: widget.isToggledOn,
                      onChanged: widget.onToggleChanged,
                      activeTrackColor: const Color(0xFF23A6E2),
                      inactiveTrackColor: const Color(0xFF333333),
                      activeThumbColor: Colors.white,
                      inactiveThumbColor: const Color(0xFF888888),
                    ),
                    // Small disconnect (×) button
                    GestureDetector(
                      onTap: widget.onDisconnect,
                      child: Container(
                        width: 28, height: 28,
                        margin: const EdgeInsets.only(left: 4),
                        decoration: BoxDecoration(
                          color: const Color(0xFF1A1A1A),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: const Color(0xFF333333)),
                        ),
                        child: const Icon(Icons.close_rounded,
                            color: Color(0xFF888888), size: 16),
                      ),
                    ),
                  ],
                )
              else
                // Connect button — white bg, black text
                _ActionButton(
                  isConnected: false,
                  onTap: widget.onTap,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  final bool isConnected;
  final VoidCallback onTap;

  const _ActionButton({
    required this.isConnected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 220),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        isConnected ? 'Disconnect' : 'Connect',
        style: const TextStyle(
          color: Colors.black,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
