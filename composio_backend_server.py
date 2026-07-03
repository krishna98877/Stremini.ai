"""
Composio MCP Backend Integration
==================================
Add these two endpoints to your Cloudflare Worker backend.
They handle the auth code exchange and automation execution.

INSTALL:
  pip install composio-core anthropic

SETUP:
  1. Create a free Composio account at composio.dev
  2. Copy your API Key from composio.dev/settings
  3. Set it as environment variable: COMPOSIO_MASTER_KEY
  4. Set ANTHROPIC_API_KEY for Claude
"""

import os
from flask import Flask, request, jsonify
from flask_cors import CORS
from composio import ComposioToolSet
import anthropic

app = Flask(__name__)
CORS(app)

COMPOSIO_MASTER_KEY = os.environ.get("COMPOSIO_MASTER_KEY", "")
ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")


@app.route("/composio/exchange", methods=["POST"])
def exchange_code():
    """Exchange Composio auth code for a Bearer token.

    The Android app sends the auth code received from the
    stremini://composio?code=xxx deep-link callback.
    """
    data = request.get_json()
    auth_code = data.get("authCode")

    if not auth_code:
        return jsonify({"error": "Missing authCode"}), 400

    try:
        toolset = ComposioToolSet(api_key=COMPOSIO_MASTER_KEY)
        token = toolset.exchange_code(auth_code)
        return jsonify({"token": token})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/composio/automate", methods=["POST"])
def automate():
    """Run an automation instruction via Claude + Composio MCP.

    1. Get the user's Composio tools (apps they connected)
    2. Send instruction to Claude with those tools
    3. Execute the tool calls Composio returns
    """
    data = request.get_json()
    instruction = data.get("instruction", "")
    mcp_url = data.get("mcpUrl", "https://connect.composio.dev/mcp")

    # The user's Composio token is in the Authorization header
    auth_header = request.headers.get("Authorization", "")
    user_token = auth_header.replace("Bearer ", "") if auth_header else ""

    if not user_token:
        return jsonify({"error": "Missing Composio token"}), 401

    if not instruction:
        return jsonify({"error": "Missing instruction"}), 400

    try:
        # Initialize Composio with the USER's token (not master key)
        # This gives access to only the apps the user connected
        toolset = ComposioToolSet(api_key=user_token)
        tools = toolset.get_tools()

        # Ask Claude to pick the right action
        client = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)
        response = client.messages.create(
            model="claude-sonnet-4-6",
            tools=tools,
            max_tokens=4096,
            messages=[{"role": "user", "content": instruction}],
        )

        # Execute the tool calls through Composio
        if response.stop_reason == "tool_use":
            result = toolset.handle_tool_calls(response)
            # Get Claude's final text response after tool execution
            final_response = client.messages.create(
                model="claude-sonnet-4-6",
                tools=tools,
                max_tokens=1024,
                messages=[
                    {"role": "user", "content": instruction},
                    response,
                    *toolset.tool_call_messages(response, result),
                ],
            )
            return jsonify({"response": final_response.content[0].text})
        else:
            return jsonify({"response": response.content[0].text})

    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ── Health check ────────────────────────────────────────────────────────
@app.route("/composio/status", methods=["GET"])
def status():
    return jsonify({
        "composio_configured": bool(COMPOSIO_MASTER_KEY),
        "claude_configured": bool(ANTHROPIC_API_KEY),
    })


if __name__ == "__main__":
    app.run(debug=True, port=8080)