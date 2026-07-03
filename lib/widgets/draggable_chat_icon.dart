import 'package:flutter/material.dart';
import 'dart:math' as math;
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// --------------------------------------------------------------
/// Radial menu item callback type
/// --------------------------------------------------------------
typedef RadialAction = void Function();

/// --------------------------------------------------------------
/// Round glowing menu button (matches screenshot: black circle, white icon)
/// --------------------------------------------------------------
class GlowCircleButton extends StatelessWidget {
  final IconData icon;
  final double size;
  final VoidCallback onTap;
  final bool isActive;

  const GlowCircleButton({
    super.key,
    required this.icon,
    required this.onTap,
    this.size = 44,
    this.isActive = false,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: const Color(0xFF1A1A1A),
          border: Border.all(
            color: isActive
                ? const Color(0xFF00F6FF)
                : const Color(0xFF333333),
            width: isActive ? 2 : 1,
          ),
          boxShadow: isActive
              ? [
                  BoxShadow(
                    color: const Color(0xFF00F6FF).withValues(alpha: 0.4),
                    blurRadius: 12,
                    spreadRadius: 2,
                  ),
                ]
              : [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.5),
                    blurRadius: 4,
                    spreadRadius: 0,
                  ),
                ],
        ),
        child: Icon(
          icon,
          color: isActive ? const Color(0xFF00F6FF) : Colors.white,
          size: size * 0.45,
        ),
      ),
    );
  }
}

/// --------------------------------------------------------------
/// Main floating bubble with cyan glow ring (matches screenshot)
/// --------------------------------------------------------------
class FloatingBubble extends StatelessWidget {
  final VoidCallback onTap;
  final bool isExpanded;

  const FloatingBubble({
    super.key,
    required this.onTap,
    this.isExpanded = false,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 68,
        height: 68,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          // Outer glow halo
          boxShadow: [
            BoxShadow(
              color: const Color(0xFF0D3A5C).withValues(alpha: 0.8),
              blurRadius: 8,
              spreadRadius: 4,
            ),
            BoxShadow(
              color: const Color(0xFF23A6E2).withValues(alpha: 0.3),
              blurRadius: 16,
              spreadRadius: 0,
            ),
          ],
          gradient: const RadialGradient(
            colors: [
              Color(0xFF0A0A0A),
              Color(0xFF000000),
            ],
            radius: 0.85,
          ),
          border: Border.all(
            color: const Color(0xFF23A6E2),
            width: 2.5,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Image.asset(
            'lib/img/logo.jpg',
            fit: BoxFit.contain,
            errorBuilder: (context, error, stackTrace) {
              return const Icon(
                Icons.bolt,
                color: Color(0xFF23A6E2),
                size: 32,
              );
            },
          ),
        ),
      ),
    );
  }
}

/// --------------------------------------------------------------
/// MAIN DRAGGABLE OVERLAY + RADIAL MENU
/// --------------------------------------------------------------
class DraggableChatIcon extends ConsumerStatefulWidget {
  final Offset position;
  final Function(Offset) onDragEnd;
  final String overlayMode;
  final VoidCallback onTapMain;
  final VoidCallback onOpenApp;
  final RadialAction? onSettingsTap;
  final RadialAction? onBrainTap;
  final RadialAction? onKeyboardTap;

  const DraggableChatIcon({
    super.key,
    required this.position,
    required this.onDragEnd,
    required this.overlayMode,
    required this.onTapMain,
    required this.onOpenApp,
    this.onSettingsTap,
    this.onBrainTap,
    this.onKeyboardTap,
  });

  @override
  ConsumerState<DraggableChatIcon> createState() => _DraggableChatIconState();
}

class _DraggableChatIconState extends ConsumerState<DraggableChatIcon>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _expandAnimation;
  late Animation<double> _rotateAnimation;
  late Offset _currentPosition;
  late bool _isRightSide;

  static const double _bubbleSize = 68.0;

  @override
  void initState() {
    super.initState();
    _currentPosition = widget.position;

    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );

    _expandAnimation =
        CurvedAnimation(parent: _controller, curve: Curves.easeOutBack);

    _rotateAnimation = Tween<double>(begin: 0.0, end: 0.125).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
    );

    _updateAnimation(widget.overlayMode == "radial");
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final screenWidth = MediaQuery.of(context).size.width;
      _isRightSide =
          (_currentPosition.dx + (_bubbleSize / 2)) > (screenWidth / 2);
    });
  }

  @override
  void didUpdateWidget(DraggableChatIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.position != widget.position) {
      _currentPosition = widget.position;
    }
    if (oldWidget.overlayMode != widget.overlayMode) {
      _updateAnimation(widget.overlayMode == "radial");
    }
  }

  void _updateAnimation(bool isRadial) {
    if (isRadial) {
      _controller.forward();
    } else {
      _controller.reverse();
    }
  }

  /// --------------------------------------------------------------
  /// RADIAL MENU — 3 items in a semicircle (matches screenshot style)
  /// Settings (gear) | Brain (psychology) | Keyboard
  /// --------------------------------------------------------------
  Widget _buildRadialIcons(BuildContext context) {
    const double radius = 80.0;

    final screenWidth = MediaQuery.of(context).size.width;
    final bool isOnRightSide =
        (_currentPosition.dx + (_bubbleSize / 2)) > (screenWidth / 2);

    final List<Map<String, dynamic>> icons = [
      {
        'icon': Icons.settings,
        'action': widget.onSettingsTap ?? () {},
      },
      {
        'icon': Icons.psychology,
        'action': widget.onBrainTap ?? () {},
      },
      {
        'icon': Icons.keyboard,
        'action': widget.onKeyboardTap ?? () {},
      },
    ];

    // Semicircle: expand away from the edge
    double startAngle;
    double endAngle;
    if (isOnRightSide) {
      // Menu expands to the left: 90° (top) → 270° (bottom)
      startAngle = 90.0;
      endAngle = 270.0;
    } else {
      // Menu expands to the right: 90° (top) → -90° (bottom)
      startAngle = 90.0;
      endAngle = -90.0;
    }
    final double step = (endAngle - startAngle) / (icons.length - 1);

    return Stack(
      alignment: Alignment.center,
      children: List.generate(icons.length, (index) {
        final double angle = startAngle + (index * step);
        final double rad = angle * (math.pi / 180.0);

        final double x = radius * math.cos(rad);
        final double y = radius * math.sin(rad);

        return AnimatedBuilder(
          animation: _expandAnimation,
          builder: (_, __) {
            return Transform.translate(
              offset: Offset(
                x * _expandAnimation.value,
                -y * _expandAnimation.value,
              ),
              child: Opacity(
                opacity: _expandAnimation.value.clamp(0.0, 1.0),
                child: GlowCircleButton(
                  icon: icons[index]['icon'] as IconData,
                  size: 44,
                  onTap: () => (icons[index]['action'] as RadialAction)(),
                  isActive: false,
                ),
              ),
            );
          },
        );
      }),
    );
  }

  /// --------------------------------------------------------------
  /// MAIN UI (drag bubble + radial menu)
  /// --------------------------------------------------------------
  @override
  Widget build(BuildContext context) {
    final bool isRadial = widget.overlayMode == "radial";

    return Positioned(
      left: _currentPosition.dx,
      top: _currentPosition.dy,
      child: AbsorbPointer(
        absorbing: false,
        child: Material(
          color: Colors.transparent,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              // Radial menu items
              if (isRadial)
                SizedBox(
                  width: _bubbleSize,
                  height: _bubbleSize,
                  child: _buildRadialIcons(context),
                ),

              // Main glowing bubble
              GestureDetector(
                onPanUpdate: (details) {
                  setState(() {
                    _currentPosition += details.delta;
                  });
                },
                onPanEnd: (details) {
                  final screenWidth = MediaQuery.of(context).size.width;
                  final screenHeight = MediaQuery.of(context).size.height;
                  final topPadding = MediaQuery.of(context).padding.top;
                  final clampedX =
                      _isRightSide ? (screenWidth - _bubbleSize) : 0.0;
                  final clampedY = _currentPosition.dy
                      .clamp(topPadding, screenHeight - _bubbleSize);
                  _currentPosition = Offset(clampedX, clampedY);
                  widget.onDragEnd(_currentPosition);
                },
                onTap: widget.onTapMain,
                child: RotationTransition(
                  turns: isRadial
                      ? _rotateAnimation
                      : const AlwaysStoppedAnimation(0.0),
                  child: FloatingBubble(
                    onTap: widget.onTapMain,
                    isExpanded: isRadial,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}