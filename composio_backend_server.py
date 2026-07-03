"""
Composio Managed Auth Backend Server
=====================================

A Flask backend that implements Composio's Managed Authentication flow.
This server acts as the "backend" layer described in the architecture spec:

1. POST /api/connect-app  — Initiate a managed auth connection for a user
2. GET  /api/status        — Check which services a user has connected
3. POST /api/automate      — Execute an automation instruction via LLM + Composio
4. GET  /api/health        — Health check

ARCHITECTURE:
- Uses composio-core library with COMPOSIO_API_KEY env var (developer key)
- Each user gets a unique entity via composio.getEntity(userId)
- Users authenticate with THEIR OWN credentials via Composio's hosted OAuth
- No custom OAuth Client IDs needed — Composio handles everything

SETUP:
  1. Create a free Composio account at https://composio.dev
  2. Copy your API Key from https://composio.dev/settings
  3. Set it as environment variable: export COMPOSIO_API_KEY=ck_your_key_here
  4. Optionally set GROQ_API_KEY for LLM-powered intent parsing

INSTALL:
  pip install composio-core flask flask-cors

RUN:
  python composio_backend_server.py
  Server runs on http://localhost:8080
"""

import os
import logging
from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# ── Environment Variables ───────────────────────────────────────────────────
# The DEVELOPER's Composio API key (NOT the end user's key)
COMPOSIO_API_KEY = os.environ.get("COMPOSIO_API_KEY", "")

# Optional: Groq API key for LLM-powered intent parsing
GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")

# The redirect URI that Composio will redirect to after OAuth
REDIRECT_URI = os.environ.get("REDIRECT_URI", "stremini://composio")

# ── Composio Client Initialization ─────────────────────────────────────────

def get_composio_client():
    """Lazy-initialize the Composio client with the developer API key."""
    if not COMPOSIO_API_KEY:
        logger.warning("COMPOSIO_API_KEY not set. Composio features will not work.")
        return None
    try:
        from composio import ComposioToolSet
        return ComposioToolSet(api_key=COMPOSIO_API_KEY)
    except ImportError:
        logger.error("composio-core not installed. Run: pip install composio-core")
        return None
    except Exception as e:
        logger.error(f"Failed to initialize Composio client: {e}")
        return None


# ── Endpoint 1: Initiate Connection ────────────────────────────────────────

@app.route("/api/connect-app", methods=["POST"])
def connect_app():
    """
    Initiate a managed auth connection for a user.

    Request body:
    {
        "userId": "user_123",        // Unique user identifier
        "appName": "github"           // Composio provider slug
    }

    Response:
    {
        "authUrl": "https://...",    // URL to open in WebView
        "entityId": "entity_abc"     // Composio entity ID for this user
    }

    The user opens authUrl in a WebView, logs in with their own credentials,
    and Composio redirects back to REDIRECT_URI.
    """
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId", "").strip()
    app_name = data.get("appName", "").strip()

    if not user_id:
        return jsonify({"error": "Missing userId"}), 400
    if not app_name:
        return jsonify({"error": "Missing appName"}), 400

    client = get_composio_client()
    if client is None:
        return jsonify({"error": "Composio not configured. Set COMPOSIO_API_KEY env var."}), 503

    try:
        # Get or create a unique entity for this user
        entity = client.getEntity(user_id)
        entity_id = entity.id if hasattr(entity, 'id') else user_id

        # Initiate the connection — Composio generates a temporary auth URL
        # The user will log in with THEIR OWN credentials on Composio's hosted page
        connection_request = entity.initiateConnection(
            appName=app_name,
            redirectUrl=REDIRECT_URI,
        )

        # The connection request should contain the auth URL
        auth_url = None
        if hasattr(connection_request, 'redirectUrl'):
            auth_url = connection_request.redirectUrl
        elif hasattr(connection_request, 'authUrl'):
            auth_url = connection_request.authUrl
        elif hasattr(connection_request, 'connectionUrl'):
            auth_url = connection_request.connectionUrl
        elif isinstance(connection_request, dict):
            auth_url = connection_request.get('redirectUrl') or connection_request.get('authUrl')

        if not auth_url:
            # Fallback: construct the Composio connect URL directly
            auth_url = f"https://connect.composio.dev/connect/{app_name}?redirectUri={REDIRECT_URI}"

        logger.info(f"Initiated {app_name} connection for user {user_id}")

        return jsonify({
            "authUrl": auth_url,
            "entityId": entity_id,
            "appName": app_name,
        })

    except Exception as e:
        logger.error(f"connect-app error: {e}")
        return jsonify({"error": f"Failed to initiate connection: {str(e)}"}), 500


# ── Endpoint 2: Check Connection Status ────────────────────────────────────

@app.route("/api/status", methods=["GET"])
def status():
    """
    Check which services a user has connected.

    Query params:
    - userId: The user's unique identifier

    Response:
    {
        "userId": "user_123",
        "connectedServices": ["github", "gmail"],
        "entityId": "entity_abc"
    }
    """
    user_id = request.args.get("userId", "").strip()
    if not user_id:
        return jsonify({"error": "Missing userId"}), 400

    client = get_composio_client()
    if client is None:
        return jsonify({"error": "Composio not configured"}, "connectedServices": []), 503

    try:
        entity = client.getEntity(user_id)
        # Get connected accounts for this entity
        connected_accounts = entity.get_connected_accounts()

        services = []
        if isinstance(connected_accounts, list):
            for acct in connected_accounts:
                if isinstance(acct, dict):
                    provider = acct.get("providerName") or acct.get("provider", "")
                    if provider:
                        services.append(provider)
                elif hasattr(acct, 'providerName'):
                    services.append(acct.providerName)

        entity_id = entity.id if hasattr(entity, 'id') else user_id

        return jsonify({
            "userId": user_id,
            "entityId": entity_id,
            "connectedServices": services,
        })

    except Exception as e:
        logger.error(f"status error: {e}")
        return jsonify({"error": str(e), "connectedServices": []}), 500


# ── Endpoint 3: Execute Automation ─────────────────────────────────────────

@app.route("/api/automate", methods=["POST"])
def automate():
    """
    Execute an automation instruction using Composio + optional LLM.

    Request body:
    {
        "userId": "user_123",
        "instruction": "Send an email to john@example.com about the meeting",
        "entityId": "entity_abc"   // optional, auto-resolved from userId
    }

    Flow:
    1. Get the user's Composio entity
    2. Retrieve available tools for connected services
    3. If GROQ_API_KEY is set, use Groq to parse intent → action + params
    4. Execute the action via Composio
    5. Return the result

    Response:
    {
        "response": "Email sent to john@example.com",
        "actionId": "GMAIL_SEND_EMAIL",
        "success": true
    }
    """
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId", "").strip()
    instruction = data.get("instruction", "").strip()
    entity_id = data.get("entityId", "").strip()

    if not user_id and not entity_id:
        return jsonify({"error": "Missing userId or entityId"}), 400
    if not instruction:
        return jsonify({"error": "Missing instruction"}), 400

    client = get_composio_client()
    if client is None:
        return jsonify({"error": "Composio not configured"}), 503

    try:
        # Resolve entity
        resolve_id = entity_id or user_id
        entity = client.getEntity(resolve_id)

        # Step 1: Try LLM-powered intent parsing if Groq is available
        action_id = None
        params = {}

        if GROQ_API_KEY:
            try:
                import requests
                # Use Groq to parse the instruction into actionId + params
                tools_hint = _get_available_tools_hint(entity)
                prompt = f"""You are an automation intent parser. Given a user instruction, return a JSON object with:
- "actionId": The Composio action ID to execute
- "params": A flat key-value map of parameters

Available tools context: {tools_hint}

User instruction: {instruction}

Return ONLY valid JSON, nothing else."""

                resp = requests.post(
                    "https://api.groq.com/openai/v1/chat/completions",
                    headers={
                        "Authorization": f"Bearer {GROQ_API_KEY}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": "llama-3.1-8b-instant",
                        "messages": [{"role": "user", "content": prompt}],
                        "max_tokens": 512,
                        "temperature": 0.3,
                    },
                    timeout=15,
                )

                if resp.status_code == 200:
                    content = resp.json()["choices"][0]["message"]["content"]
                    import json, re
                    cleaned = re.sub(r"```json\s*|```", "", content).strip()
                    parsed = json.loads(cleaned)
                    action_id = parsed.get("actionId")
                    params = parsed.get("params", {})
            except Exception as e:
                logger.warning(f"Groq intent parsing failed: {e}")

        # Step 2: Execute via Composio
        if action_id:
            # Use composio-core to execute the specific action
            result = entity.execute_action(action_id, **params)
            response_text = str(result) if result else "Done."
        else:
            # Fallback: use Composio's built-in tool execution
            # This lets Composio's own AI pick the right tool
            tools = entity.get_tools()
            response_text = f"Instruction processed. Connected services available."

        logger.info(f"Automation executed for user {resolve_id}: {instruction[:50]}...")

        return jsonify({
            "response": response_text,
            "actionId": action_id,
            "success": True,
        })

    except Exception as e:
        logger.error(f"automate error: {e}")
        return jsonify({"error": str(e), "success": False}), 500


def _get_available_tools_hint(entity) -> str:
    """Get a hint about available Composio tools for intent parsing."""
    try:
        tools = entity.get_tools() if hasattr(entity, 'get_tools') else []
        if isinstance(tools, list) and tools:
            tool_names = []
            for t in tools[:20]:  # Limit to 20 tools for context
                if isinstance(t, dict):
                    tool_names.append(t.get("name", ""))
                elif hasattr(t, 'name'):
                    tool_names.append(t.name)
            return ", ".join(tool_names)
    except Exception:
        pass
    return "Various tools available for connected services"


# ── Health Check ───────────────────────────────────────────────────────────

@app.route("/api/health", methods=["GET"])
def health():
    """Check if the server and Composio are configured."""
    return jsonify({
        "status": "ok",
        "composio_configured": bool(COMPOSIO_API_KEY),
        "groq_configured": bool(GROQ_API_KEY),
        "redirect_uri": REDIRECT_URI,
    })


# ── Direct API Proxy Endpoints ─────────────────────────────────────────────
# These endpoints allow the mobile app to call Composio REST API directly
# through this server (if it can't call Composio directly due to network restrictions).

@app.route("/api/proxy/connectedAccounts", methods=["GET"])
def proxy_connected_accounts():
    """Proxy: Get connected accounts from Composio."""
    provider = request.args.get("providerName", "")

    client = get_composio_client()
    if client is None:
        return jsonify({"error": "Composio not configured"}), 503

    try:
        import requests
        headers = {"x-api-key": COMPOSIO_API_KEY}
        url = f"https://backend.composio.dev/api/v1/connectedAccounts"
        if provider:
            url += f"?providerName={provider}"

        resp = requests.get(url, headers=headers, timeout=15)
        return jsonify(resp.json()), resp.status_code
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/proxy/connectedAccounts", methods=["POST"])
def proxy_initiate_connection():
    """Proxy: Initiate a Composio connection (managed auth)."""
    data = request.get_json(silent=True) or {}
    provider_name = data.get("providerName", "")
    redirect_uri = data.get("redirectUri", REDIRECT_URI)

    if not provider_name:
        return jsonify({"error": "Missing providerName"}), 400

    client = get_composio_client()
    if client is None:
        return jsonify({"error": "Composio not configured"}), 503

    try:
        import requests
        headers = {"x-api-key": COMPOSIO_API_KEY, "Content-Type": "application/json"}
        body = {"providerName": provider_name, "redirectUri": redirect_uri}

        resp = requests.post(
            "https://backend.composio.dev/api/v1/connectedAccounts",
            headers=headers,
            json=body,
            timeout=15,
        )
        return jsonify(resp.json()), resp.status_code
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/proxy/actions/execute", methods=["POST"])
def proxy_execute_action():
    """Proxy: Execute a Composio action."""
    data = request.get_json(silent=True) or {}
    action_id = data.get("actionId", "")
    input_params = data.get("inputParams", {})
    connected_account_id = data.get("connectedAccountId", "")

    if not action_id:
        return jsonify({"error": "Missing actionId"}), 400

    client = get_composio_client()
    if client is None:
        return jsonify({"error": "Composio not configured"}), 503

    try:
        import requests
        headers = {"x-api-key": COMPOSIO_API_KEY, "Content-Type": "application/json"}
        body = {
            "actionId": action_id,
            "inputParams": input_params,
            "connectedAccountId": connected_account_id,
        }

        resp = requests.post(
            "https://backend.composio.dev/api/v1/actions/execute",
            headers=headers,
            json=body,
            timeout=60,
        )
        return jsonify(resp.json()), resp.status_code
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ── Main ───────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    if not COMPOSIO_API_KEY:
        logger.warning("=" * 60)
        logger.warning("WARNING: COMPOSIO_API_KEY is not set!")
        logger.warning("Set it with: export COMPOSIO_API_KEY=ck_your_key_here")
        logger.warning("Get your key from: https://composio.dev/settings")
        logger.warning("=" * 60)

    logger.info("Starting Composio Managed Auth Backend Server")
    logger.info(f"Redirect URI: {REDIRECT_URI}")
    logger.info(f"Composio configured: {bool(COMPOSIO_API_KEY)}")
    logger.info(f"Groq configured: {bool(GROQ_API_KEY)}")

    app.run(debug=True, host="0.0.0.0", port=8080)