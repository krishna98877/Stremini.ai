#!/bin/bash

# GitHub Secrets Setup Script for Stremini AI
# This script sets up all necessary secrets for the GitHub Actions workflow
# Usage: ./scripts/setup-github-secrets.sh

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if GITHUB_TOKEN is set
if [ -z "$GITHUB_FINE_GRAINED_PAT" ]; then
    echo -e "${RED}Error: GITHUB_FINE_GRAINED_PAT environment variable is not set${NC}"
    echo "Please export your GitHub Fine-Grained PAT:"
    echo "export GITHUB_FINE_GRAINED_PAT=your_token_here"
    exit 1
fi

REPO_OWNER="krishna98877"
REPO_NAME="Stremini.ai"

echo -e "${YELLOW}Setting up GitHub Secrets for $REPO_OWNER/$REPO_NAME${NC}"
echo ""

# Function to set a secret
set_secret() {
    local secret_name=$1
    local secret_value=$2
    
    if [ -z "$secret_value" ]; then
        echo -e "${YELLOW}Skipping $secret_name (not provided)${NC}"
        return
    fi
    
    # Encrypt the secret value
    echo -n "$secret_value" | base64 | curl -s -X PUT \
        -H "Authorization: Bearer $GITHUB_FINE_GRAINED_PAT" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/actions/secrets/$secret_name" \
        -d "{\"encrypted_value\":\"$(echo -n "$secret_value" | base64)\"}" \
        > /dev/null 2>&1
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Secret '$secret_name' set successfully${NC}"
    else
        echo -e "${RED}✗ Failed to set secret '$secret_name'${NC}"
    fi
}

# List of secrets to set
declare -A SECRETS=(
    ["GROQ_API_KEY"]="$GROQ_API_KEY"
    ["COMPOSIO_CONSUMER_KEY"]="$COMPOSIO_CONSUMER_KEY"
    ["AUTH_CONFIG_GITHUB"]="$AUTH_CONFIG_GITHUB"
    ["AUTH_CONFIG_GMAIL"]="$AUTH_CONFIG_GMAIL"
    ["AUTH_CONFIG_INSTAGRAM"]="$AUTH_CONFIG_INSTAGRAM"
    ["AUTH_CONFIG_FACEBOOK"]="$AUTH_CONFIG_FACEBOOK"
    ["AUTH_CONFIG_WHATSAPP"]="$AUTH_CONFIG_WHATSAPP"
    ["AUTH_CONFIG_GOOGLEDRIVE"]="$AUTH_CONFIG_GOOGLEDRIVE"
    ["AUTH_CONFIG_DISCORD"]="$AUTH_CONFIG_DISCORD"
    ["AUTH_CONFIG_LINKEDIN"]="$AUTH_CONFIG_LINKEDIN"
    ["AUTH_CONFIG_REDDIT"]="$AUTH_CONFIG_REDDIT"
    ["AUTH_CONFIG_GOOGLESHEETS"]="$AUTH_CONFIG_GOOGLESHEETS"
    ["AUTH_CONFIG_YOUTUBE"]="$AUTH_CONFIG_YOUTUBE"
    ["WHATSAPP_PHONE_NUMBER_ID"]="$WHATSAPP_PHONE_NUMBER_ID"
    ["INSTAGRAM_DEFAULT_PSID"]="$INSTAGRAM_DEFAULT_PSID"
)

# Set each secret
for secret_name in "${!SECRETS[@]}"; do
    set_secret "$secret_name" "${SECRETS[$secret_name]}"
done

echo ""
echo -e "${GREEN}GitHub secrets setup complete!${NC}"
echo ""
echo "You can now push to your repository and the GitHub Actions workflow will use these secrets."
echo "To verify secrets are set:"
echo "  gh secret list --repo $REPO_OWNER/$REPO_NAME"
