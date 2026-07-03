class EnvConfig {
  // Use String.fromEnvironment to pull values from the build command
  static const String baseUrl = String.fromEnvironment(
    'BASE_URL',
    defaultValue: 'https://ai-keyboard-backend.vishwajeetadkine705.workers.dev',
  );
}