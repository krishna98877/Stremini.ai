# GitHub Actions Setup Guide for Stremini AI

This guide explains how to set up GitHub Actions for building and deploying your Stremini AI Flutter application.

## Overview

The GitHub Actions workflow in `.github/workflows/build.yml` automatically builds your Flutter APK whenever you push to the `main` branch or manually trigger the workflow.

## Prerequisites

1. **GitHub Fine-Grained Personal Access Token (PAT)**
   - Go to https://github.com/settings/tokens?type=beta
   - Click "Generate new token"
   - Name it: `stremini-ai-build`
   - Select repository: `Stremini.ai`
   - Permissions needed:
     - `actions:read` - Read GitHub Actions
     - `contents:read` - Read repository contents
     - `secrets:write` - Write repository secrets (if setting them up)

2. **Repository Secrets**
   - GROQ_API_KEY
   - COMPOSIO_CONSUMER_KEY
   - AUTH_CONFIG_* (GitHub, Gmail, Instagram, Facebook, WhatsApp, GoogleDrive, Discord, LinkedIn, Reddit, GoogleSheets, YouTube)
   - WHATSAPP_PHONE_NUMBER_ID
   - INSTAGRAM_DEFAULT_PSID

## Setting Up Secrets

### Option 1: Using the Setup Script

```bash
# Set environment variables with your actual values
export GITHUB_FINE_GRAINED_PAT="your_token_here"
export GROQ_API_KEY="your_groq_key"
export COMPOSIO_CONSUMER_KEY="your_composio_key"
# ... set other variables as needed

# Run the setup script
chmod +x scripts/setup-github-secrets.sh
./scripts/setup-github-secrets.sh
```

### Option 2: Manual Setup via GitHub Web UI

1. Go to your repository settings: https://github.com/krishna98877/Stremini.ai/settings/secrets/actions
2. Click "New repository secret"
3. Add each secret from the list above

### Option 3: Using GitHub CLI (gh)

```bash
# First, authenticate with GitHub CLI
gh auth login

# Then use gh to set secrets
gh secret set GROQ_API_KEY --body "your_groq_api_key" --repo krishna98877/Stremini.ai
gh secret set COMPOSIO_CONSUMER_KEY --body "your_composio_key" --repo krishna98877/Stremini.ai
# ... repeat for other secrets
```

## Workflow Features

### Automatic Triggers
- **On push to main**: Workflow runs automatically when you push to the main branch
- **Manual trigger**: You can manually trigger the workflow from the Actions tab

### Build Process
1. Checks out your repository code
2. Sets up Java 17 (required for Flutter Android builds)
3. Sets up Flutter stable channel
4. Caches Gradle dependencies for faster builds
5. Gets Flutter dependencies
6. Creates `android/local.properties` with API keys from GitHub Secrets
7. Builds release APK with Dart-define variables
8. Renames APK to `stremini-final.apk`
9. Uploads artifact for download (retained for 30 days)

### Artifact Management
- Built APKs are uploaded as artifacts
- Artifacts are retained for 30 days
- Download from: Actions → [Build Run] → Artifacts

## Checking Build Status

### Via GitHub Web UI
1. Go to your repository
2. Click the "Actions" tab
3. Select the "Build APK" workflow
4. View build logs and status

### Via GitHub CLI
```bash
# List recent workflow runs
gh run list --repo krishna98877/Stremini.ai

# View a specific run's logs
gh run view <run_id> --repo krishna98877/Stremini.ai

# Download artifacts from a run
gh run download <run_id> --repo krishna98877/Stremini.ai
```

## Environment Variables Used in Build

The following environment variables are passed to the Flutter build:

```dart
--dart-define=GROQ_API_KEY=$GROQ_API_KEY
```

These can be accessed in your Flutter code using:

```dart
const String groqApiKey = String.fromEnvironment('GROQ_API_KEY');
```

## Troubleshooting

### Build Fails with "Secret not found"
- Verify the secret is set in repository settings
- Check the exact secret name (case-sensitive)
- Ensure the PAT has `secrets:read` permission

### Build Fails with "gradle build error"
- Check that Java 17 is properly installed
- Verify all dependencies in `pubspec.yaml` are correct
- Check `android/build.gradle` and `android/app/build.gradle` for issues

### APK not generated
- Check the Flutter build logs for errors
- Verify all Dart code compiles without errors
- Ensure `pubspec.yaml` has all required dependencies

### Artifacts not uploading
- Verify the APK is successfully generated
- Check that the file path is correct
- Ensure GitHub Actions has write permissions

## Security Best Practices

1. **Token Rotation**
   - Regenerate your PAT regularly
   - Update repository settings if token is compromised

2. **Secret Management**
   - Never commit secrets to Git
   - Use `.env.local` for local development only (not in version control)
   - Use GitHub Secrets for CI/CD

3. **Permissions**
   - Use fine-grained PATs with minimal required permissions
   - Limit token to specific repositories
   - Revoke unused tokens

## Additional Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Flutter CI/CD Guide](https://flutter.dev/docs/deployment/cd)
- [GitHub CLI Manual](https://cli.github.com/manual/)

## Support

For issues with:
- **GitHub Actions**: Check GitHub Actions documentation or open an issue on GitHub
- **Flutter Build**: Check Flutter documentation at flutter.dev
- **Stremini AI App**: Refer to project README and documentation
