class AppConstants {
  // App Info
  static const String appName = 'Stremini AI';
  static const String appVersion = '1.0.0';
  
  // API
  static const String baseUrl = 'https://ai-keyboard-backend.vishwajeetadkine705.workers.dev';
  
  // Method Channels
  static const String overlayChannel = 'stremini.chat.overlay';
  static const String eventChannel = 'stremini.chat.overlay/events';
  static const String composioChannel = 'stremini.composio';
  static const String composioEventChannel = 'stremini.composio/events';
  
  // Composio MCP
  static const String composioMcpUrl = 'https://connect.composio.dev/mcp';
  static const String composioAuthUrl = 'https://composio.dev/login';
  
  // Dimensions
  static const double bubbleSize = 78.0;
  static const double menuItemSize = 60.0;
  static const double radialRadius = 110.0;
  static const double chatboxWidth = 320.0;
  static const double chatboxHeight = 480.0;
  
  // Durations
  static const Duration animationDuration = Duration(milliseconds: 300);
}