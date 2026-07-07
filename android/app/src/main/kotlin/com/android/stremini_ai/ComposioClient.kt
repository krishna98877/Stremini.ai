package com.android.stremini_ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.android.stremini_ai.BuildConfig
import com.android.stremini_ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Composio Managed Authentication client.
 *
 * Architecture:
 * - The DEVELOPER provides a Composio API key (stored in EncryptedPrefs).
 * - The END USER never sees or provides any key.
 * - User taps "Connect GitHub" → this client calls Composio's managed auth API
 *   → gets a temporary OAuth URL → opens it in ComposioAuthActivity (WebView).
 * - User logs in with THEIR OWN credentials on Composio's hosted page.
 * - Composio redirects to stremini://composio?provider=github&status=success.
 * - The connected account is now available for automation.
 *
 * Endpoints (all use x-api-key header with the developer key):
 * - POST /api/v3.1/sessions                 → create session for user
 * - GET  /api/v3.1/sessions/{id}/toolkits   → list toolkits (shows connected status)
 * - POST /api/v3.1/sessions/{id}/authorize  → generate Connect Link (returns redirect_url)
 * - POST /api/v3.1/sessions/{id}/execute    → execute a tool on behalf of the user
 * - DELETE /api/v3.1/connectedAccounts/{id} → disconnect a specific account (legacy)
 * - DELETE /api/v3.1/sessions/{id}/toolkits/{slug} → session-based disconnect
 *
 * Get your developer key: https://composio.dev/settings → API Keys
 */

/**
 * Service definition — id matches Composio's provider slug.
 *
 * Declared as a top-level data class (NOT nested inside ComposioClient's
 * companion object) because the Kotlin 2.0.21 compiler (with AGP 8.9.1)
 * inconsistently resolves nested companion types from other files in the
 * same package — `ComposioClient.ALL_SERVICES` resolves fine but
 * `ComposioClient.ServiceDef` does not. Promoting to top-level fixes this.
 *
 * @param iconRes drawable resource ID for the service logo (e.g. R.drawable.logo_github)
 */
data class ServiceDef(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val color: Long,
    val iconChar: String,
    val iconRes: Int,
    val authConfigId: String = "",
    val needsCustomAuth: Boolean = false,
)

class ComposioClient(
    private val context: Context,
    externalScope: CoroutineScope? = null
) {

    // Use a single stable scope for the lifetime of this client.
    // Previously this property created a brand-new CoroutineScope on every
    // access when externalScope was null — leading to unstructured, un-cancellable
    // work that leaked across Activity recreations.
    private val workScope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    companion object {
        private const val TAG = "ComposioClient"
        const val COMPOSIO_API_BASE = "https://backend.composio.dev/api/v3"
        const val COMPOSIO_TOOLS_API_BASE = "https://backend.composio.dev/api/v3.1"
        const val COMPOSIO_CONNECT_BASE = "https://connect.composio.dev"

        /** Deep-link scheme for OAuth callback */
        const val REDIRECT_URI = "stremini://composio"

        // Only 11 services with managed OAuth via Composio.
        // Telegram/Twitter/TikTok removed — they have no managed auth and
        // always fail at the OAuth step (user reported 14-app count, expecting 11).
        //
        // authConfigId is intentionally empty here — it's injected at runtime
        // from BuildConfig (see authConfigFor(serviceId) below). The repo
        // never contains real auth_config_ids because they're tied to the
        // developer's Composio project.
        val ALL_SERVICES = listOf(
            ServiceDef("github",       "GitHub",       listOf("github","repo","repository","commit","pull request","issue","branch"),           0xFF6e40c9, "G", R.drawable.logo_github),
            ServiceDef("gmail",        "Gmail",        listOf("gmail","email","mail","send email","inbox","draft"),                               0xFFEA4335, "M", R.drawable.logo_gmail),
            ServiceDef("instagram",    "Instagram",    listOf("instagram","ig","instagram story","instagram reel","instagram dm","instagram post"), 0xFFE4405F, "I", R.drawable.logo_instagram),
            ServiceDef("facebook",     "Facebook",     listOf("facebook","fb","facebook post","facebook page","facebook group"),                      0xFF1877F2, "F", R.drawable.logo_facebook),
            ServiceDef("whatsapp",     "WhatsApp",     listOf("whatsapp","wa","whats app","whatsapp message"),                                       0xFF25D366, "W", R.drawable.logo_whatsapp),
            ServiceDef("googledrive",  "Google Drive", listOf("drive","google drive","upload","drive file","drive folder","share file"),               0xFF0F9D58, "D", R.drawable.logo_googledrive),
            ServiceDef("discord",      "Discord",      listOf("discord","discord server","discord channel","discord dm","guild"),                      0xFF5865F2, "D", R.drawable.logo_discord),
            ServiceDef("linkedin",     "LinkedIn",     listOf("linkedin","linkedin profile","linkedin connection","linkedin job","linkedin post"),      0xFF0A66C2, "L", R.drawable.logo_linkedin),
            ServiceDef("reddit",       "Reddit",       listOf("reddit","subreddit","reddit post","upvote","comment","thread"),                        0xFFFF4500, "R", R.drawable.logo_reddit),
            ServiceDef("googlesheets", "Google Sheets",listOf("sheet","spreadsheet","google sheets","cell","row","column","table"),                  0xFF0F9D58, "S", R.drawable.logo_googlesheets),
            ServiceDef("youtube",      "YouTube",      listOf("youtube","youtube video","youtube channel","upload video","youtube comment","subscribe","playlist","youtube shorts"), 0xFFFF0000, "Y", R.drawable.logo_youtube),
        )

        /** Map of common user intents → Composio action IDs */
        val INTENT_ACTION_MAP = mapOf(
            "send_email"      to "GMAIL_SEND_EMAIL",
            "read_email"      to "GMAIL_GET_EMAILS",
            "search_email"    to "GMAIL_SEARCH_EMAILS",
            "create_issue"    to "GITHUB_CREATE_AN_ISSUE",
            "create_repo"     to "GITHUB_CREATE_A_REPOSITORY",
            "list_repos"      to "GITHUB_LIST_REPOSITORIES_FOR_AUTHENTICATED_USER",
            "create_pr"       to "GITHUB_CREATE_A_PULL_REQUEST",
            "send_whatsapp"   to "WHATSAPP_SEND_MESSAGE",
            "send_instagram"  to "INSTAGRAM_SEND_TEXT_MESSAGE",
            "post_facebook"   to "FACEBOOK_CREATE_POST",
            "send_discord"    to "DISCORD_SEND_A_MESSAGE_TO_A_CHANNEL",
            "linkedin_post"   to "LINKEDIN_CREATE_A_POST",
            "reddit_post"     to "REDDIT_CREATE_A_POST",
            "upload_drive"    to "GOOGLE_DRIVE_UPLOAD_FILE",
            "list_drive"      to "GOOGLE_DRIVE_LIST_FILES",
            "read_sheet"      to "GOOGLE_SHEETS_READ_SHEET",
            "update_sheet"    to "GOOGLE_SHEETS_UPDATE_SHEET",
            "upload_youtube"  to "YOUTUBE_UPLOAD_A_VIDEO",
            "youtube_comment" to "YOUTUBE_ADD_COMMENT",
        )

        /** Map serviceId → action ID prefix for LLM prompt filtering */
        val SERVICE_ACTION_PREFIX = mapOf(
            "github" to "GITHUB",
            "gmail" to "GMAIL",
            "whatsapp" to "WHATSAPP",
            "instagram" to "INSTAGRAM",
            "facebook" to "FACEBOOK",
            "googledrive" to "GOOGLE_DRIVE",
            "discord" to "DISCORD",
            "linkedin" to "LINKEDIN",
            "reddit" to "REDDIT",
            "googlesheets" to "GOOGLE_SHEETS",
            "youtube" to "YOUTUBE",
        )

        /**
         * Verified defaults for services that need fixed identifiers
         * (developer's connected WhatsApp Business number / Instagram page).
         * These are filled into params at execution time if the LLM did not
         * supply them. Without phone_number_id, WhatsApp silently accepts the
         * request but never delivers the message.
         *
         * SECURITY: Values are injected at build time from local.properties /
         * env vars — never hardcoded in source.
         */
        val WHATSAPP_PHONE_NUMBER_ID: String = BuildConfig.WHATSAPP_PHONE_NUMBER_ID ?: ""
        val INSTAGRAM_DEFAULT_PSID: String = BuildConfig.INSTAGRAM_DEFAULT_PSID ?: ""

        /**
         * Resolve the Composio auth_config_id for a given service at runtime.
         * Returns empty string if not configured (the connect flow will fail
         * gracefully and prompt the developer to set up the key in
         * local.properties).
         */
        fun authConfigFor(serviceId: String): String = when (serviceId) {
            "github"       -> BuildConfig.AUTH_CONFIG_GITHUB ?: ""
            "gmail"        -> BuildConfig.AUTH_CONFIG_GMAIL ?: ""
            "instagram"    -> BuildConfig.AUTH_CONFIG_INSTAGRAM ?: ""
            "facebook"     -> BuildConfig.AUTH_CONFIG_FACEBOOK ?: ""
            "whatsapp"     -> BuildConfig.AUTH_CONFIG_WHATSAPP ?: ""
            "googledrive"  -> BuildConfig.AUTH_CONFIG_GOOGLEDRIVE ?: ""
            "discord"      -> BuildConfig.AUTH_CONFIG_DISCORD ?: ""
            "linkedin"     -> BuildConfig.AUTH_CONFIG_LINKEDIN ?: ""
            "reddit"       -> BuildConfig.AUTH_CONFIG_REDDIT ?: ""
            "googlesheets" -> BuildConfig.AUTH_CONFIG_GOOGLESHEETS ?: ""
            "youtube"      -> BuildConfig.AUTH_CONFIG_YOUTUBE ?: ""
            else -> ""
        }
    }

    private val prefs = EncryptedPrefs.getEncrypted(context, "composio_prefs")
    private val userPrefs = EncryptedPrefs.getEncrypted(context, "stremini_prefs")

    // ── Composio Consumer API Key ──────────────────────────────────
    // The key is injected at build time via BuildConfig (sourced from
    // local.properties or the COMPOSIO_CONSUMER_KEY env var).
    // It is NEVER hardcoded in source.
    

    /**
     * Get the Composio Consumer API key.
     * Resolution order:
     * 1. EncryptedPrefs (if a custom key was set at runtime via setDeveloperApiKey)
     * 2. BuildConfig.COMPOSIO_CONSUMER_KEY (injected at build time from
     *    local.properties / env var — empty if not configured)
     * 3. Empty string (not configured — app will guide user to set up keys)
     */
    fun getDeveloperApiKey(): String {
        val stored = prefs.getString("composio_dev_key")
        if (!stored.isNullOrBlank()) return stored
        // Use BuildConfig-injected key (no hardcoded default — must come from
        // local.properties or env var, see SECURITY.md).
        val buildConfigKey = BuildConfig.COMPOSIO_CONSUMER_KEY
        if (buildConfigKey.isNotBlank()) {
            prefs.putString("composio_dev_key", buildConfigKey)
            return buildConfigKey
        }
        return ""
    }

    /**
     * Check if the Composio consumer key is available.
     */
    fun isConfigured(): Boolean = getDeveloperApiKey().isNotBlank()

    // ── Session Management (official Composio Sessions flow) ────────
    // Per the official Composio docs, the correct flow is:
    //   1. Create a session: POST /api/v1/sessions {user_id}
    //   2. Generate Connect Link: POST /api/v1/sessions/{id}/authorize {toolkit?}
    //   3. Open the redirect_url in a WebView
    //   4. List toolkits: GET /api/v3.1/sessions/{id}/toolkits
    //   5. Execute tools: POST /api/v3.1/sessions/{id}/execute {tool_slug, params}

    /**
     * Get or create a Composio session for this device.
     * Uses a stable user ID derived from ANDROID_ID. Session ID cached in EncryptedPrefs.
     * Uses lazy validation: if any session endpoint returns 401/404, the session is
     * cleared and recreated on the next call.
     */
    private suspend fun getOrCreateSession(): String = withContext(Dispatchers.IO) {
        // Fast path: return cached session without acquiring the lock
        val cached = prefs.getString("composio_session_id")
        if (!cached.isNullOrBlank()) return@withContext cached

        // Slow path: acquire mutex to prevent concurrent session creation
        sessionMutex.withLock {
            // Double-check: another caller may have created the session while we waited
            val cachedAfterLock = prefs.getString("composio_session_id")
            if (!cachedAfterLock.isNullOrBlank()) return@withLock cachedAfterLock

            val apiKey = getDeveloperApiKey()
            val userId = getStableUserId()
            val body = JSONObject().apply { put("user_id", userId) }
                .toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/sessions")
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                val json = JSONObject(respBody)
                val sid = json.optString("id").ifBlank {
                    json.optJSONObject("data")?.optString("id") ?: ""
                }
                if (sid.isNotBlank()) {
                    prefs.putString("composio_session_id", sid)
                } else {
                    Log.e(TAG, "Session creation failed: HTTP ${response.code} body=$respBody")
                }
                sid
            }
        }
    }

    /**
     * Handle a failed session endpoint call. If the error indicates the session
     * is invalid (401/404), clear the cached session so the next call creates a new one.
     */
    private fun handleSessionError(responseCode: Int) {
        if (responseCode == 401 || responseCode == 404) {
            Log.w(TAG, "Session endpoint returned $responseCode — clearing cached session")
            prefs.remove("composio_session_id")
        }
    }

    /** Clear the cached session ID (forces a new session on next call). */
    fun clearSession() {
        prefs.remove("composio_session_id")
    }

    private fun getStableUserId(): String {
        val cached = prefs.getString("composio_user_id")
        if (!cached.isNullOrBlank()) return cached
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "stremini_${System.currentTimeMillis()}"
        val userId = "stremini_$androidId"
        prefs.putString("composio_user_id", userId)
        return userId
    }

    // ── Connected Accounts (via session toolkits) ───────────────────

    /**
     * Check if a specific service has a connected account (via session toolkits).
     */
    suspend fun isServiceConnected(serviceId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sessionId = getOrCreateSession()
            if (sessionId.isBlank()) return@withContext false
            val apiKey = getDeveloperApiKey()
            val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/connected_accounts")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                handleSessionError(response.code)
                val body = response.body?.string() ?: return@use false
                val json = JSONObject(body)
                val toolkits = json.optJSONArray("items") ?: json.optJSONArray("toolkits") ?: json.optJSONArray("data")
                if (toolkits != null) {
                    for (i in 0 until toolkits.length()) {
                        val tk = toolkits.optJSONObject(i) ?: continue
                        if (tk.optString("slug") == serviceId) {
                            return@use tk.optBoolean("is_connected", false)
                                || tk.optString("status") == "connected"
                                || !tk.optString("connected_account_id").isNullOrBlank()
                        }
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Get all connected services via GET /api/v3/connected_accounts.
     */
    suspend fun getConnectedServices(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/connected_accounts")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap<String, List<String>>()
                handleSessionError(response.code)
                val body = response.body?.string() ?: return@use emptyMap<String, List<String>>()
                val json = JSONObject(body)
                val accounts = json.optJSONArray("items") ?: return@use emptyMap<String, List<String>>()
                val result = mutableMapOf<String, MutableList<String>>()
                for (i in 0 until accounts.length()) {
                    val acct = accounts.optJSONObject(i) ?: continue
                    val slug = acct.optJSONObject("toolkit")?.optString("slug") ?: ""
                    val acctId = acct.optString("id")
                    val status = acct.optString("status")
                    if (slug.isNotBlank() && status != "EXPIRED" && status != "FAILED" && status != "REVOKED" && acctId.isNotBlank()) {
                        result.getOrPut(slug) { mutableListOf() }.add(acctId)
                        // Save the user_id from the connected account for execute requests
                        val acctUserId = acct.optString("user_id", "")
                        if (acctUserId.isNotBlank()) {
                            prefs.putString("composio_connected_user_id", acctUserId)
                        }
                    }
                }
                result
            }
        }.getOrDefault(emptyMap())
    }

    // ── Connect a Service (Managed Auth via WebView) ────────────────

    /**
     * Exchange an auth code for a Bearer token.
     * In a production environment, this call MUST be routed through your backend.
     */
    suspend fun exchangeCodeForToken(code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            val body = JSONObject().apply {
                put("code", code)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/auth/exchange")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("token").takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    /**
     * Initiate Composio managed OAuth using the official Sessions + Connect Links flow.
     *
     * 1. Get or create a session for this user
     * 2. POST /sessions/{id}/authorize with {toolkit} -> get redirect_url
     * 3. Launch ComposioAuthActivity (WebView) with the redirect_url
     */
    fun connectService(serviceId: String) {
        if (!isConfigured()) {
            Toast.makeText(context, "Connectors not configured.", Toast.LENGTH_LONG).show()
            return
        }
        workScope.launch(Dispatchers.IO) {
            try {
                val apiKey = getDeveloperApiKey()
                val svc = ALL_SERVICES.find { it.id == serviceId }
                val authConfigId = authConfigFor(serviceId)
                if (authConfigId.isBlank() || svc?.needsCustomAuth == true) {
                    // For services without managed auth, create a custom auth config
                    // with the user's own credentials via the connect link flow.
                    // Composio will prompt the user to enter their credentials.
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Connecting to ${svc?.name ?: serviceId}...", Toast.LENGTH_SHORT).show()
                    }
                    // Try creating a custom auth config
                    val customBody = JSONObject().apply {
                        put("name", "${serviceId}_custom")
                        put("toolkit", JSONObject().put("slug", serviceId))
                        put("type", "use_custom_auth")
                    }.toString().toRequestBody("application/json".toMediaType())
                    val customReq = Request.Builder()
                        .url("$COMPOSIO_API_BASE/auth_configs")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(customBody)
                        .build()
                    val customClient = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
                    try {
                        customClient.newCall(customReq).execute().use { customResp ->
                            val customJson = JSONObject(customResp.body?.string() ?: "{}")
                            val newAuthConfigId = customJson.optJSONObject("auth_config")?.optString("id") ?: ""
                            if (newAuthConfigId.isNotBlank()) {
                                // Use this new auth config to create the connect link
                                val linkBody = JSONObject().apply {
                                    put("auth_config_id", newAuthConfigId)
                                    put("user_id", getStableUserId())
                                }.toString().toRequestBody("application/json".toMediaType())
                                val linkReq = Request.Builder()
                                    .url("$COMPOSIO_API_BASE/connected_accounts/link")
                                    .addHeader("x-api-key", apiKey)
                                    .addHeader("Content-Type", "application/json")
                                    .post(linkBody)
                                    .build()
                                customClient.newCall(linkReq).execute().use { linkResp ->
                                    val linkJson = JSONObject(linkResp.body?.string() ?: "{}")
                                    val authUrl = linkJson.optString("redirect_url").ifBlank { linkJson.optString("redirectUrl") }
                                    if (authUrl.isNotBlank()) {
                                        withContext(Dispatchers.Main) {
                                            try {
                                                val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    setPackage("com.android.chrome")
                                                }
                                                context.startActivity(chromeIntent)
                                            } catch (e: Exception) {
                                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(browserIntent)
                                            }
                                        }
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "${svc?.name} requires manual setup. Please connect via the dashboard.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "${svc?.name} requires manual setup. Please connect via the dashboard.", Toast.LENGTH_LONG).show()
                        }
                    }
                    return@launch
                }
                val userId = getStableUserId()
                val body = JSONObject().apply {
                    put("auth_config_id", authConfigId)
                    put("user_id", userId)
                    // Don't pass redirect_uri — Composio uses its own default
                    // which shows a success page the user can see in Chrome
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$COMPOSIO_API_BASE/connected_accounts/link")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
                client.newCall(request).execute().use { resp ->
                    val respBody = resp.body?.string() ?: "{}"
                    val json = JSONObject(respBody)
                    val authUrl = json.optString("redirect_url").ifBlank { json.optString("redirectUrl") }
                    if (authUrl.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            // Use Chrome Custom Tabs — faster, has saved passwords, more reliable
                            try {
                                val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    setPackage("com.android.chrome")
                                }
                                context.startActivity(chromeIntent)
                                // Poll for connection status — check every 3 seconds for up to 2 minutes
                                workScope.launch {
                                    var attempts = 0
                                    while (attempts < 40) {
                                        kotlinx.coroutines.delay(3000)
                                        attempts++
                                        val connected = isServiceConnected(serviceId)
                                        if (connected) {
                                            Log.i(TAG, "$serviceId connected after $attempts polls")
                                            // Broadcast connection success to Dart
                                            val intent = Intent("com.android.stremini_ai.SERVICE_DISCONNECTED").apply {
                                                putExtra("event", "connection_success")
                                                putExtra("serviceId", serviceId)
                                            }
                                            context.sendBroadcast(intent)
                                            break
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(browserIntent)
                                } catch (e2: Exception) {
                                    Log.e(TAG, "Cannot open browser", e2)
                                    Toast.makeText(context, "Cannot open browser. Please install Chrome.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "No auth URL in response: $respBody")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to get connection link", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating connection for $serviceId", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Connection failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openInCustomTab(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open connection page.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Disconnect a Service ───────────────────────────────────────

    suspend fun disconnectService(serviceId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            val connected = getConnectedServices()
            val accountIds = connected[serviceId] ?: return@withContext false
            val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")

            var allSucceeded = true
            for (accountId in accountIds) {
                // If accountId looks like a real ID (not a slug), use DELETE /connectedAccounts/{id}
                // Otherwise try the session-based disconnect: DELETE /sessions/{id}/toolkits/{slug}
                val isRealAccountId = accountId.length > 20 || accountId.contains("-")
                val request = if (isRealAccountId) {
                    Request.Builder()
                        .url("$COMPOSIO_API_BASE/connected_accounts/$accountId")
                        .addHeader("x-api-key", apiKey)
                        .delete()
                        .build()
                } else {
                    // accountId is actually the slug — use session-based disconnect
                    val sid = getOrCreateSession()
                    Request.Builder()
                        .url("$COMPOSIO_API_BASE/sessions/$sid/toolkits/$accountId")
                        .addHeader("x-api-key", apiKey)
                        .delete()
                        .build()
                }
                client.newCall(request).execute().use { response ->
                    response.body?.close()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Disconnect $serviceId ($accountId) failed: HTTP ${response.code}")
                        allSucceeded = false
                    }
                }
            }
            // Always clear locally regardless of server response
            disconnectServiceLocally(serviceId, accountIds.firstOrNull() ?: serviceId)
            allSucceeded
        }.getOrDefault(false)
    }

    // ── Execute Automation ─────────────────────────────────────────

    /**
     * Execute a Composio action by action ID with structured parameters.
     * On transient failures (429 / 5xx) retries once after a short backoff.
     *
     * @param actionId Composio action ID (e.g., "GMAIL_SEND_EMAIL")
     * @param params Map of input parameters for the action
     * @param connectedAccountId The connected account to use
     * @param serviceId Optional service ID for disconnect-on-401 logic
     */
    suspend fun executeAction(
        actionId: String,
        params: Map<String, Any>,
        connectedAccountId: String,
        serviceId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            executeActionInternal(actionId, params, connectedAccountId, serviceId)
        }.recoverCatching { firstError ->
            val msg = firstError.message ?: ""
            val isTransient = msg.contains("429") ||
                msg.contains("temporarily unavailable") ||
                msg.contains("500") ||
                msg.contains("502") ||
                msg.contains("503")
            if (isTransient) {
                Log.w(TAG, "Transient failure on action $actionId, retrying in 1.5s…")
                kotlinx.coroutines.delay(1500)
                executeActionInternal(actionId, params, connectedAccountId, serviceId)
            } else {
                throw firstError
            }
        }
    }

    private suspend fun executeActionInternal(
        actionId: String,
        params: Map<String, Any>,
        connectedAccountId: String,
        serviceId: String?
    ): String {
        val apiKey = getDeveloperApiKey()

        // Use the user_id from the connected account (fixes entity_id mismatch)
        val connectedUserId = prefs.getString("composio_connected_user_id") ?: getStableUserId()
        val body = JSONObject().apply {
            put("arguments", JSONObject(params))
            put("entity_id", connectedUserId)
            if (connectedAccountId.isNotBlank()) put("connected_account_id", connectedAccountId)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$COMPOSIO_TOOLS_API_BASE/tools/execute/$actionId")
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return secureHttpClient(connectTimeoutSeconds = 15L, readTimeoutSeconds = 60L, useCase = "composio_execute")
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    handleSessionError(response.code)
                    when (response.code) {
                        401 -> {
                            // The token is dead on Composio's server. Actually DELETE
                            // the account so getConnectedServices() won't bring it
                            // back on the next app restart.
                            serviceId?.let { sid ->
                                Log.w(TAG, "401 on $actionId — deleting $sid account $connectedAccountId from Composio")
                                runCatching { disconnectService(sid) }
                                // Also notify the Dart side immediately
                                disconnectServiceLocally(sid, connectedAccountId)
                            }
                            error("Automation session expired. The service has been disconnected. Please reconnect it to continue.")
                        }
                        403 -> error("Permission denied. Reconnect the service and try again.")
                        429 -> error("Rate limited (429). Please wait a moment and try again.")
                        in 500..599 -> error("Composio server error (${response.code}). Please try again shortly.")
                        else -> {
                            try {
                                val errJson = JSONObject(errBody)
                                error(errJson.optString("message", "Automation failed. Please try again."))
                            } catch (_: Exception) {
                                error("Automation failed (error ${response.code}). Please try again.")
                            }
                        }
                    }
                }
                val respBody = response.body?.string() ?: "{}"
                val json = JSONObject(respBody)
                val resultData = json.optJSONObject("result")
                    ?: json.optJSONObject("data")
                    ?: json
                // Check if the action was actually successful
                val successful = json.optBoolean("successful", false)
                if (!successful) {
                    val errorMsg = json.optString("error", "")
                        .ifBlank { "Action failed. Please try again." }
                    error(errorMsg)
                }
                // Extract success data
                val data = json.opt("data")
                when {
                    data is String -> data
                    data is org.json.JSONObject && data.has("display_url") ->
                        "Done! View it here: ${data.optString("display_url")}"
                    data is org.json.JSONObject && data.has("id") ->
                        "Done! ID: ${data.optString("id")}"
                    data != null -> data.toString().take(300)
                    else -> "Action completed successfully."
                }
            }
    }

    // ── Local disconnect tracking (no network call) ──────────────────
    // When a 401 is received we mark the service disconnected locally so
    // the UI and next automation attempt reflect the real state without
    // waiting for the user to manually disconnect.

    /** Notify the Flutter side that a service has been disconnected. */
    fun disconnectServiceLocally(serviceId: String, accountId: String) {
        // Notify Flutter side so it can update _serviceStatus immediately.
        // sendBroadcast is non-blocking and safe to call from any thread, so we
        // dispatch on workScope without requiring an external scope to be set.
        // (Previously this was wrapped in `externalScope?.launch { ... }`, which
        // silently dropped the broadcast when externalScope was null — i.e. for
        // the MainActivity-created client.)
        workScope.launch {
            try {
                val intent = Intent("com.android.stremini_ai.SERVICE_DISCONNECTED").apply {
                    putExtra("serviceId", serviceId)
                }
                context.sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    // ── Multi-account note ──────────────────────────────────────────
    // When a user connects multiple accounts for the same service,
    // `accountIds.first()` silently picks whichever was connected first.

    // ── Multi-step automation ────────────────────────────────────────

    /** A single step in a multi-step automation plan. */
    private data class AutomationStep(
        val serviceId: String,
        val serviceName: String,
        val actionId: String,
        val params: Map<String, Any>,
    )

    /**
     * High-level automation: takes natural language and executes one or more
     * Composio actions.
     *
     * If the instruction mentions multiple services (e.g. "check Gmail for
     * invoices, then add them to Google Sheets"), Groq plans an ordered list
     * of steps and each is executed sequentially. The output of earlier steps
     * is available as context for later steps.
     *
     * For single-service instructions the existing single-action path is used
     * as a fast path.
     */
    suspend fun executeAutomation(
        instruction: String,
        groqClient: GroqClient? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured()) error("Connectors not configured")

            val connected = getConnectedServices()

            // ── Multi-step path (requires Groq) ──────────────────────
            if (groqClient != null) {
                val steps = parseMultiStepIntent(instruction, groqClient)
                if (steps.size > 1) {
                    // Verify all services in the plan are connected
                    for (step in steps) {
                        val ids = connected[step.serviceId]
                        if (ids.isNullOrEmpty()) {
                            error("${step.serviceName} is needed for this automation but isn't connected. " +
                                "Tap the plug icon and connect ${step.serviceName} first.")
                        }
                    }
                    // Execute the chain
                    val results = mutableListOf<String>()
                    var previousResult: String? = null
                    for ((index, step) in steps.withIndex()) {
                        val accountId = connected[step.serviceId]!!.first()
                        // Merge previous step's output into this step's params
                        // so the LLM-planned params can reference dynamic data.
                        val enrichedParams = if (previousResult != null) {
                            step.params.toMutableMap().apply {
                                put("_previousStepOutput", previousResult)
                            }
                        } else {
                            step.params
                        }
                        // CRITICAL: normalize params so Composio doesn't
                        // silently swallow the action due to wrong field names.
                        val normalizedParams = resolveContactParams(step.actionId, enrichedParams)
                        val stepLabel = "Step ${index + 1}/${steps.size} (${step.serviceName})"
                        Log.i(TAG, "$stepLabel: executing ${step.actionId} with params=$normalizedParams")
                        val stepResult = executeAction(
                            step.actionId, normalizedParams, accountId,
                            serviceId = step.serviceId
                        ).getOrElse { e ->
                            error("$stepLabel failed: ${e.message}")
                        }
                        results.add("$stepLabel: $stepResult")
                        previousResult = stepResult
                    }
                    return@runCatching results.joinToString("\n")
                }
                // Single step returned — fall through to the fast path below
                // but reuse the LLM-parsed step if available.
                if (steps.size == 1) {
                    val step = steps[0]
                    val ids = connected[step.serviceId]
                    if (ids.isNullOrEmpty()) {
                        error("${step.serviceName} is not connected. Tap the Automations button (plug icon) in the chat and connect ${step.serviceName} first.")
                    }
                    val normalized = resolveContactParams(step.actionId, step.params)
                    Log.i(TAG, "Single-step from LLM plan: ${step.actionId} params=$normalized")
                    return@runCatching executeAction(
                        step.actionId, normalized, ids.first(),
                        serviceId = step.serviceId
                    ).getOrThrow()
                }
            }

            // ── Single-service fast path ─────────────────────────────
            val service = detectService(instruction)
                ?: error("Couldn't detect which service to use. Try mentioning the service name.")

            // ── AI Learning: check cache first ──────────────────────────
            val cached = getCachedAutomation(instruction)
            if (cached != null) {
                val (cachedActionId, cachedParams) = cached
                Log.i(TAG, "Automation cache HIT: $instruction → $cachedActionId")
                val accountId = connected[service.id]?.firstOrNull()
                    ?: error("${service.name} is not connected. Connect it first.")
                return@runCatching executeAction(
                    cachedActionId, cachedParams, accountId,
                    serviceId = service.id
                ).getOrThrow()
            }

            val accountIds = connected[service.id]
            if (accountIds.isNullOrEmpty()) {
                error("${service.name} is not connected. Tap the Automations button (plug icon) in the chat and connect ${service.name} first.")
            }
            val accountId = accountIds.first()

            val actionParams = if (groqClient != null) {
                parseIntentWithLLM(instruction, service, groqClient)
            } else {
                parseIntentByKeywords(instruction, service)
            }

            if (actionParams == null) {
                error("Couldn't understand what action to take. Try being more specific, e.g. 'send an email to john@example.com'")
            }

            val (actionId, params) = actionParams
            val resolvedParams = resolveContactParams(actionId, params)
            val result = executeAction(actionId, resolvedParams, accountId, serviceId = service.id).getOrThrow()
            // Cache this successful automation for instant repeat
            cacheAutomationResult(instruction, actionId, params)
            result
        }
    }

    /**
     * Ask Groq to break a natural-language instruction into an ordered list
     * of automation steps. Each step targets a specific service and action.
     *
     * Returns an empty list if the LLM response can't be parsed (caller
     * should fall back to single-service detection).
     */
    private suspend fun parseMultiStepIntent(
        instruction: String,
        groqClient: GroqClient
    ): List<AutomationStep> {
        val serviceCatalog = ALL_SERVICES.joinToString(", ") { "${it.name} (id: ${it.id})" }
        val actionCatalog = INTENT_ACTION_MAP.entries
            .sortedByDescending { it.key.length }
            .joinToString(", ") { "${it.key} → ${it.value}" }

        val prompt = """You are a multi-step automation planner for an app that connects to real services via Composio.

Given a user request, break it into sequential automation steps. Each step targets ONE service.

Available services: $serviceCatalog
Available actions (common ones): $actionCatalog

Rules:
- If the request only involves ONE service, return a JSON array with a single element.
- If the request involves MULTIPLE services, return multiple steps in execution order.
- Each step must have: "serviceId" (lowercase), "serviceName", "actionId" (COMPOSIO_ACTION_ID format), "params" (flat key-value map).
- Fill in as many params as possible from the user's request. Leave unknown values as empty strings.
- If a later step depends on a previous step's output, add a placeholder param "_dependsOnPreviousStep": true.
- Use EXACT Composio param names — wrong names silently fail:
  * WhatsApp: {"to_number":"<phone or name>","text":"<msg>","phone_number_id":"$WHATSAPP_PHONE_NUMBER_ID"}
  * Gmail: {"to":"<email>","subject":"<subj>","body":"<content>"}
  * Instagram: {"recipient_id":"<PSID or name>","text":"<msg>"}
  * Discord: {"content":"<msg>"}
  * GitHub: {"title":"<t>","body":"<b>"}
  * Facebook: {"message":"<post>"}
  * LinkedIn: {"text":"<post>"}
  * Reddit: {"title":"<t>","text":"<b>"}
  * Google Drive: {"file_name":"<n>","content":"<c>"}
  * Google Sheets: {"spreadsheet_id":"<id>","range":"A1:Z100"}
  * YouTube: {"title":"<t>","description":"<d>"}

Return ONLY a valid JSON array, nothing else.

User request: ${protectForAi(instruction, source = "multi-step automation")}

Example single-service: [{"serviceId":"gmail","serviceName":"Gmail","actionId":"GMAIL_SEND_EMAIL","params":{"to":"john@example.com","subject":"Hello","body":"Hi there"}}]
Example multi-service: [{"serviceId":"gmail","serviceName":"Gmail","actionId":"GMAIL_READ_EMAILS","params":{"query":"invoices","maxResults":5}},{"serviceId":"googlesheets","serviceName":"Google Sheets","actionId":"GOOGLE_SHEETS_UPDATE_SHEET","params":{"spreadsheetId":"","range":"A1","values":"[[\"data\"]]","_dependsOnPreviousStep":true}}]"""

        val response = groqClient.sendMessage(message = prompt, history = emptyList())
            .getOrDefault("")

        return runCatching {
            val jsonStr = response
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            if (jsonStr.isBlank()) return@runCatching emptyList()

            // Handle both array and single-object responses
            val arr = when {
                jsonStr.startsWith("[") -> org.json.JSONArray(jsonStr)
                jsonStr.startsWith("{") -> org.json.JSONArray().put(org.json.JSONObject(jsonStr))
                else -> return@runCatching emptyList()
            }

            val steps = mutableListOf<AutomationStep>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sid = obj.optString("serviceId", "").lowercase()
                val sName = obj.optString("serviceName", "")
                val aId = obj.optString("actionId", "")
                if (sid.isBlank() || aId.isBlank()) continue
                val pJson = obj.optJSONObject("params")
                val p = mutableMapOf<String, Any>()
                if (pJson != null) {
                    pJson.keys().forEach { key -> p[key] = pJson.get(key) }
                }
                // Use the canonical service name if we have it
                val displayName = sName.ifBlank {
                    ALL_SERVICES.find { it.id == sid }?.name ?: sid
                }
                steps.add(AutomationStep(sid, displayName, aId, p))
            }
            steps
        }.getOrDefault(emptyList())
    }

    /**
     * Use Groq to parse natural language into a Composio actionId + params.
     *
     * CRITICAL: param names MUST match the verified Composio schemas. Wrong
     * names cause Composio to return `successful: true` without actually
     * delivering the message — the exact "says done but didn't send" bug
     * the user reported.
     */
    private suspend fun parseIntentWithLLM(
        instruction: String,
        service: ServiceDef,
        groqClient: GroqClient
    ): Pair<String, Map<String, Any>>? {
        val prompt = """You are an expert automation parser for ${service.name}. Parse the user request into a JSON action.

Available actions: ${INTENT_ACTION_MAP.values.filter { aid -> SERVICE_ACTION_PREFIX[service.id]?.let { prefix -> aid.startsWith(prefix) } ?: false }.joinToString(", ")}

EXACT PARAM NAMES — using wrong names silently fails the action:
- WhatsApp: {"to_number": "<phone with country code, e.g. +15551234567>", "text": "<message>", "phone_number_id": "$WHATSAPP_PHONE_NUMBER_ID"}
  * If user said a name like "royal" or "john", put the NAME in to_number as-is — the system resolves it to a phone number later.
  * NEVER use "to", "message", "body" — those keys are silently ignored by Composio.
- Gmail: {"to": "<email>", "subject": "<subject>", "body": "<email content>"}
- Instagram: {"recipient_id": "<PSID number, e.g. 17841400000000000>", "text": "<message>"}
  * If user said a name, put the name in recipient_id — the system fills the default PSID.
- Discord: {"content": "<message>"}
- GitHub: {"title": "<title>", "body": "<description>"}
- Facebook: {"message": "<post content>"}
- LinkedIn: {"text": "<post content>"}
- Reddit: {"title": "<title>", "text": "<body>"}
- Google Drive: {"file_name": "<name>", "content": "<content>"}
- Google Sheets: {"spreadsheet_id": "<id>", "range": "A1:Z100"}
- YouTube: {"title": "<title>", "description": "<desc>"}

RULES:
1. ALWAYS use the EXACT param names above. Do NOT invent variations.
2. Extract message content faithfully — "send hi" → text="hi", "saying hello there" → text="hello there".
3. For WhatsApp, ALWAYS include "phone_number_id": "$WHATSAPP_PHONE_NUMBER_ID".
4. NEVER leave required params empty.

User request: ${protectForAi(instruction, source = "automation request")}

Return ONLY valid JSON (no markdown, no explanation):
{"actionId":"WHATSAPP_SEND_MESSAGE","params":{"to_number":"royal","text":"hi","phone_number_id":"$WHATSAPP_PHONE_NUMBER_ID"}}
{"actionId":"GMAIL_SEND_EMAIL","params":{"to":"john@example.com","subject":"Hello","body":"Hi there"}}
{"actionId":"INSTAGRAM_SEND_TEXT_MESSAGE","params":{"recipient_id":"royal","text":"Hello"}}
{"actionId":"DISCORD_SEND_A_MESSAGE_TO_A_CHANNEL","params":{"content":"Hello everyone"}}
{"actionId":"GITHUB_CREATE_AN_ISSUE","params":{"title":"Bug report","body":"Something is broken"}}"""

        val response = groqClient.sendMessage(message = prompt, history = emptyList())
            .getOrDefault("")

        return runCatching {
            val jsonStr = response
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            if (jsonStr.isBlank() || !jsonStr.startsWith("{")) return@runCatching null
            val json = JSONObject(jsonStr)
            val actionId = json.getString("actionId")
            val paramsJson = json.getJSONObject("params")
            val params = mutableMapOf<String, Any>()
            paramsJson.keys().forEach { key -> params[key] = paramsJson.get(key) }
            Pair(actionId, params)
        }.getOrNull()
    }

    /**
     * Keyword-based fallback for intent parsing (no LLM needed).
     */
    private fun parseIntentByKeywords(
        instruction: String,
        service: ServiceDef
    ): Pair<String, Map<String, Any>>? {
        val lower = instruction.lowercase()

        return when (service.id) {
            "gmail" -> when {
                lower.contains("send") && (lower.contains("email") || lower.contains("mail")) -> {
                    val toRegex = Regex("(?:to|for)\\s+([\\w.+-]+@[\\w.-]+)", RegexOption.IGNORE_CASE)
                    val toMatch = toRegex.find(instruction)
                    val subjectRegex = Regex("(?:subject|about|re)\\s+[:\"]?([^\".]+)", RegexOption.IGNORE_CASE)
                    val subjectMatch = subjectRegex.find(instruction)
                    // Extract body: remove the "send email to X about Y" prefix
                    var bodyHint = instruction
                    val sendPrefixRegex = Regex("(?i)^.*?(?:send|compose|write)\\s+(?:an?\\s+)?(?:email|mail)\\s+(?:to\\s+[\\w.+-]+@[\\w.-]+\\s*)?", RegexOption.IGNORE_CASE)
                    val subjectPart = subjectMatch?.groupValues?.get(1)?.trim()
                    bodyHint = sendPrefixRegex.replace(bodyHint, "").trim()
                    if (subjectPart != null) {
                        bodyHint = bodyHint.replace(Regex("(?i)\\b(?:about|subject|re)\\s+[:\"]?\\Q$subjectPart\\E", RegexOption.IGNORE_CASE), "").trim()
                    }
                    if (bodyHint.isBlank()) bodyHint = instruction
                    "GMAIL_SEND_EMAIL" to mapOf(
                        "to" to (toMatch?.groupValues?.get(1) ?: ""),
                        "subject" to (subjectPart ?: "No subject"),
                        "body" to bodyHint,
                    )
                }
                else -> "GMAIL_READ_EMAILS" to mapOf("maxResults" to 10)
            }
            "github" -> when {
                lower.contains("issue") && lower.contains("create") -> "GITHUB_CREATE_AN_ISSUE" to mapOf(
                    "owner" to "", "repo" to "", "title" to instruction
                )
                lower.contains("repo") && lower.contains("create") -> "GITHUB_CREATE_A_REPOSITORY" to mapOf(
                    "name" to "new-repo", "private" to false
                )
                else -> "GITHUB_LIST_REPOSITORIES_FOR_AUTHENTICATED_USER" to emptyMap()
            }
            "twitter" -> null  // removed from ALL_SERVICES (no managed auth)
            "discord" -> "DISCORD_SEND_A_MESSAGE_TO_A_CHANNEL" to mapOf("content" to instruction)
            "linkedin" -> "LINKEDIN_CREATE_A_POST" to mapOf("text" to instruction)
            "reddit" -> "REDDIT_CREATE_A_POST" to mapOf("title" to instruction, "text" to instruction)
            "googledrive" -> "GOOGLE_DRIVE_UPLOAD_FILE" to mapOf("content" to instruction)
            "googlesheets" -> "GOOGLE_SHEETS_READ_SHEET" to mapOf("spreadsheetId" to "", "range" to "A1:Z100")
            "whatsapp" -> {
                val toRegex = Regex("""(?:to|send\s+.*?\s+to)\s+([\w\s]+?)(?:\s+saying|\s+message|\s+that|\s+about|\s|$)""", RegexOption.IGNORE_CASE)
                val msgRegex = Regex("""(?:send|message|saying)\s+(.+?)(?:\s+to\s+|$)""", RegexOption.IGNORE_CASE)
                val recipient = toRegex.find(instruction)?.groupValues?.get(1)?.trim() ?: ""
                val message = msgRegex.find(instruction)?.groupValues?.get(1)?.trim() ?: instruction
                // WhatsApp API requires: phone_number_id, to_number, text.
                // phone_number_id is the developer's verified WhatsApp Business number.
                "WHATSAPP_SEND_MESSAGE" to mapOf(
                    "to_number" to recipient,
                    "text" to message,
                    "phone_number_id" to WHATSAPP_PHONE_NUMBER_ID
                )
            }
            "instagram" -> "INSTAGRAM_SEND_TEXT_MESSAGE" to mapOf("recipient_id" to INSTAGRAM_DEFAULT_PSID, "text" to instruction)
            "facebook" -> "FACEBOOK_CREATE_POST" to mapOf("message" to instruction)
            "youtube" -> "YOUTUBE_UPLOAD_A_VIDEO" to mapOf("title" to instruction, "description" to "")
            "telegram" -> null  // removed from ALL_SERVICES (no managed auth)
            "tiktok" -> null    // removed from ALL_SERVICES (no managed auth)
            else -> null
        }
    }

    // ── Service Detection (Longest-Match) ───────────────────────────

    /**
     * Detect which service a user message is likely about.
     * Uses longest-keyword-match to avoid collisions.
     */
    fun detectService(message: String): ServiceDef? {
        val lower = message.lowercase()
        var bestMatch: ServiceDef? = null
        var bestKeywordLength = 0

        for (svc in ALL_SERVICES) {
            for (kw in svc.keywords) {
                if (lower.contains(kw) && kw.length > bestKeywordLength) {
                    bestMatch = svc
                    bestKeywordLength = kw.length
                }
            }
        }
        return bestMatch
    }

    // ── Contact Resolution System ──────────────────────────────────
    // Maps contact names to phone numbers so users can say "send hi to royal"
    // instead of "send hi to +1234567890". Learns from user corrections
    // and persists across app restarts.

    fun resolveContact(name: String): String? {
        if (name.isBlank()) return null
        val cleanName = name.trim().lowercase()
        // 1. Exact match in saved contacts
        val exact = prefs.getString("contact_$cleanName")
        if (!exact.isNullOrBlank()) return exact
        // 2. Fuzzy match — check if any saved contact contains this name
        val allContacts = prefs.getString("all_contact_names") ?: ""
        if (allContacts.isNotBlank()) {
            for (savedName in allContacts.split(",")) {
                val sn = savedName.trim().lowercase()
                if (sn.isNotBlank() && (sn.contains(cleanName) || cleanName.contains(sn))) {
                    val num = prefs.getString("contact_$sn")
                    if (!num.isNullOrBlank()) return num
                }
            }
        }
        // 3. Check device contacts
        return try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%" + cleanName + "%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(0)?.replace(Regex("[^+0-9]"), "")
                    val contactName = it.getString(1)?.lowercase()?.trim()
                    if (!number.isNullOrBlank() && !contactName.isNullOrBlank()) {
                        saveContact(contactName, number)
                    }
                    number
                } else null
            }
        } catch (e: Exception) { null }
    }

    fun saveContact(name: String, phoneNumber: String) {
        val cleanName = name.trim().lowercase()
        prefs.putString("contact_$cleanName", phoneNumber)
        val allNames = prefs.getString("all_contact_names") ?: ""
        if (!allNames.split(",").contains(cleanName)) {
            val newAll = if (allNames.isBlank()) cleanName else allNames + "," + cleanName
            prefs.putString("all_contact_names", newAll)
        }
    }

    /**
     * Normalize LLM-produced params into the EXACT shape Composio expects.
     *
     * This is the critical safety net: even if the LLM returns {"to":"royal",
     * "message":"hi"} for WhatsApp (the OLD prompt did this), this function
     * rewrites it to {"to_number":"<resolved phone>", "text":"hi",
     * "phone_number_id":"<WHATSAPP_PHONE_NUMBER_ID from BuildConfig>"} so
     * Composio actually delivers.
     *
     * Without this normalization, Composio accepts the request with
     * `successful: true` but silently drops it — exactly the "says done but
     * didn't send" bug the user reported.
     */
    private fun resolveContactParams(actionId: String, params: Map<String, Any>): Map<String, Any> {
        val p = params.toMutableMap()

        when {
            actionId.startsWith("WHATSAPP") -> {
                // 1) Accept any of {to, to_number, recipient, phone} → to_number
                val rawTo = p.remove("to")?.toString()
                    ?: p.remove("recipient")?.toString()
                    ?: p["to_number"]?.toString()
                    ?: ""
                // 2) Accept any of {message, text, body, content} → text
                val rawText = p.remove("message")?.toString()
                    ?: p.remove("body")?.toString()
                    ?: p.remove("content")?.toString()
                    ?: p["text"]?.toString()
                    ?: ""
                // 3) Resolve name → phone number
                val isPhoneNumber = rawTo.matches(Regex("^\\+?[0-9]{6,15}$"))
                val finalNumber = if (isPhoneNumber) rawTo else {
                    resolveContact(rawTo)?.also {
                        Log.i(TAG, "WhatsApp contact resolved: $rawTo -> $it")
                    } ?: rawTo
                }
                p["to_number"] = finalNumber
                p["text"] = rawText
                // 4) ALWAYS inject phone_number_id — without it, Composio
                //    silently accepts but never delivers the message.
                if (p["phone_number_id"]?.toString().isNullOrBlank()) {
                    p["phone_number_id"] = WHATSAPP_PHONE_NUMBER_ID
                }
                Log.i(TAG, "WhatsApp params normalized → to_number=$finalNumber text=$rawText phone_number_id=${p["phone_number_id"]}")
            }

            actionId.startsWith("INSTAGRAM") -> {
                // Instagram requires a numeric PSID. The LLM may produce a
                // username like "royal" — replace with the verified default PSID.
                val rawRid = p["recipient_id"]?.toString() ?: ""
                val isNumericPsid = rawRid.matches(Regex("^[0-9]{10,20}$"))
                if (!isNumericPsid) {
                    p["recipient_id"] = INSTAGRAM_DEFAULT_PSID
                    Log.i(TAG, "Instagram recipient_id normalized: '$rawRid' → default PSID $INSTAGRAM_DEFAULT_PSID")
                }
                // Normalize message → text
                p.remove("message")?.let { p.putIfAbsent("text", it) }
                p.remove("body")?.let { p.putIfAbsent("text", it) }
            }

            actionId.startsWith("GMAIL") -> {
                // Gmail is forgiving but make sure body is set if message was provided
                p.remove("message")?.let { p.putIfAbsent("body", it) }
                p.remove("text")?.let { p.putIfAbsent("body", it) }
                p.remove("content")?.let { p.putIfAbsent("body", it) }
            }

            actionId.startsWith("DISCORD") -> {
                p.remove("message")?.let { p.putIfAbsent("content", it) }
                p.remove("text")?.let { p.putIfAbsent("content", it) }
                p.remove("body")?.let { p.putIfAbsent("content", it) }
            }

            actionId.startsWith("GITHUB") -> {
                p.remove("description")?.let { p.putIfAbsent("body", it) }
                p.remove("text")?.let { p.putIfAbsent("body", it) }
            }
        }
        return p
    }

    // ── AI Learning: cache successful automations ───────────────────
    // Remembers instruction → (actionId, params) mappings so repeat
    // commands skip the LLM parse step entirely (0ms vs 2-5s).

    private fun cacheAutomationResult(instruction: String, actionId: String, params: Map<String, Any>) {
        val key = "auto_cache_${instruction.lowercase().hashCode()}"
        val json = JSONObject().apply {
            put("actionId", actionId)
            put("params", JSONObject(params))
            put("instruction", instruction)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.putString(key, json.toString())
    }

    private fun getCachedAutomation(instruction: String): Pair<String, Map<String, Any>>? {
        val key = "auto_cache_${instruction.lowercase().hashCode()}"
        val raw = prefs.getString(key) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val actionId = json.getString("actionId")
            val paramsJson = json.optJSONObject("params") ?: JSONObject()
            val params = mutableMapOf<String, Any>()
            paramsJson.keys().forEach { k -> params[k] = paramsJson.get(k) }
            Pair(actionId, params)
        }.getOrNull()
    }
}