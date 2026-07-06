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
 * - POST /api/v3/connected_accounts/link   → initiate connection (returns redirect URL)
 * - GET  /api/v3/connected_accounts         → list all connected accounts
 * - DELETE /api/v3/connected_accounts/{id}  → disconnect a specific account
 * - POST /api/v3.1/tools/execute/{slug}     → execute a tool on behalf of the user
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

    companion object {
        private const val TAG = "ComposioClient"
        const val COMPOSIO_API_BASE = "https://backend.composio.dev/api/v3"
        const val COMPOSIO_TOOLS_API_BASE = "https://backend.composio.dev/api/v3.1"
        const val COMPOSIO_CONNECT_BASE = "https://connect.composio.dev"

        /** Deep-link scheme for OAuth callback */
        const val REDIRECT_URI = "stremini://composio"

        val ALL_SERVICES = listOf(
            ServiceDef("github",       "GitHub",       listOf("github","repo","repository","commit","pull request","issue","branch"),           0xFF6e40c9, "G", R.drawable.logo_github),
            ServiceDef("gmail",        "Gmail",        listOf("gmail","email","mail","send email","inbox","draft"),                               0xFFEA4335, "M", R.drawable.logo_gmail),
            ServiceDef("telegram",     "Telegram",     listOf("telegram","tg","telegram message","telegram chat","telegram channel"),               0xFF0088cc, "T", R.drawable.logo_telegram),
            ServiceDef("twitter",      "Twitter",      listOf("twitter","tweet","x.com","post tweet","timeline","retweet"),                         0xFF1DA1F2, "X", R.drawable.logo_twitter),
            ServiceDef("instagram",    "Instagram",    listOf("instagram","ig","instagram story","instagram reel","instagram dm","instagram post"), 0xFFE4405F, "I", R.drawable.logo_instagram),
            ServiceDef("facebook",     "Facebook",     listOf("facebook","fb","facebook post","facebook page","facebook group"),                      0xFF1877F2, "F", R.drawable.logo_facebook),
            ServiceDef("whatsapp",     "WhatsApp",     listOf("whatsapp","wa","whats app","whatsapp message"),                                       0xFF25D366, "W", R.drawable.logo_whatsapp),
            ServiceDef("googlechrome", "Chrome",       listOf("chrome","browser","open url","browse","search","tab"),                                 0xFF4285F4, "C", R.drawable.logo_chrome),
            ServiceDef("googledrive",  "Google Drive", listOf("drive","google drive","upload","drive file","drive folder","share file"),               0xFF0F9D58, "D", R.drawable.logo_googledrive),
            ServiceDef("discord",      "Discord",      listOf("discord","discord server","discord channel","discord dm","guild"),                      0xFF5865F2, "D", R.drawable.logo_discord),
            ServiceDef("linkedin",     "LinkedIn",     listOf("linkedin","linkedin profile","linkedin connection","linkedin job","linkedin post"),      0xFF0A66C2, "L", R.drawable.logo_linkedin),
            ServiceDef("reddit",       "Reddit",       listOf("reddit","subreddit","reddit post","upvote","comment","thread"),                        0xFFFF4500, "R", R.drawable.logo_reddit),
            ServiceDef("googlesheets",  "Google Sheets",listOf("sheet","spreadsheet","google sheets","cell","row","column","table"),                  0xFF0F9D58, "S", R.drawable.logo_googlesheets),
            ServiceDef("youtube",      "YouTube",      listOf("youtube","youtube video","youtube channel","upload video","youtube comment","subscribe","playlist","youtube shorts"), 0xFFFF0000, "Y", R.drawable.logo_youtube),
            ServiceDef("tiktok",       "TikTok",       listOf("tiktok","tiktok video","tiktok post","tiktok dm","tiktok comment","tiktok account","duet"),              0xFF000000, "Tk", R.drawable.logo_tiktok),
        )

        /** Map of common user intents → Composio action IDs */
        val INTENT_ACTION_MAP = mapOf(
            "send_email"      to "GMAIL_SEND_EMAIL",
            "read_email"      to "GMAIL_READ_EMAILS",
            "search_email"    to "GMAIL_SEARCH_EMAILS",
            "create_issue"    to "GITHUB_CREATE_AN_ISSUE",
            "create_repo"     to "GITHUB_CREATE_A_REPOSITORY",
            "list_repos"      to "GITHUB_LIST_REPOSITORIES_FOR_AUTHENTICATED_USER",
            "create_pr"       to "GITHUB_CREATE_A_PULL_REQUEST",
            "post_tweet"      to "TWITTER_CREATE_A_TWEET",
            "get_timeline"    to "TWITTER_GET_USER_TIMELINE",
            "send_discord"    to "DISCORD_SEND_A_MESSAGE_TO_A_CHANNEL",
            "linkedin_post"   to "LINKEDIN_CREATE_A_POST",
            "reddit_post"     to "REDDIT_CREATE_A_POST",
            "upload_drive"    to "GOOGLE_DRIVE_UPLOAD_FILE",
            "list_drive"      to "GOOGLE_DRIVE_LIST_FILES",
            "read_sheet"      to "GOOGLE_SHEETS_READ_SHEET",
            "update_sheet"    to "GOOGLE_SHEETS_UPDATE_SHEET",
            "upload_youtube"  to "YOUTUBE_UPLOAD_A_VIDEO",
            "youtube_comment" to "YOUTUBE_ADD_COMMENT",
            "tiktok_post"     to "TIKTOK_CREATE_A_VIDEO",
        )
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
     *    local.properties / env var / hardcoded default in build.gradle.kts)
     * 3. Empty string (not configured)
     */
    fun getDeveloperApiKey(): String {
        val stored = prefs.getString("composio_dev_key")
        if (!stored.isNullOrBlank()) return stored
        // Use BuildConfig-injected key (has a hardcoded default in build.gradle.kts)
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
                .url("$COMPOSIO_API_BASE/sessions/$sessionId/toolkits")
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
     * Get all connected services via session toolkits endpoint.
     */
    suspend fun getConnectedServices(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionId = getOrCreateSession()
            if (sessionId.isBlank()) return@withContext emptyMap<String, List<String>>()
            val apiKey = getDeveloperApiKey()
            val client = secureHttpClient(connectTimeoutSeconds = 10L, readTimeoutSeconds = 15L, useCase = "composio")
            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/sessions/$sessionId/toolkits")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap<String, List<String>>()
                handleSessionError(response.code)
                val body = response.body?.string() ?: return@use emptyMap<String, List<String>>()
                val json = JSONObject(body)
                val toolkits = json.optJSONArray("items") ?: json.optJSONArray("toolkits") ?: json.optJSONArray("data")
                val result = mutableMapOf<String, MutableList<String>>()
                if (toolkits != null) {
                    for (i in 0 until toolkits.length()) {
                        val tk = toolkits.optJSONObject(i) ?: continue
                        val slug = tk.optString("slug")
                        val isConnected = tk.optBoolean("is_connected", false)
                            || tk.optString("status") == "connected"
                            || !tk.optString("connected_account_id").isNullOrBlank()
                        if (slug.isNotBlank() && isConnected) {
                            // Store the real connected_account_id. If blank, use the slug
                            // so disconnectService can identify which toolkit to disconnect.
                            val acctId = tk.optString("connected_account_id", "")
                            result[slug] = mutableListOf(if (acctId.isNotBlank()) acctId else slug)
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
            Toast.makeText(context, "Composio not configured.", Toast.LENGTH_LONG).show()
            return
        }
        workScope.launch(Dispatchers.IO) {
            try {
                val apiKey = getDeveloperApiKey()
                val body = JSONObject().apply {
                    put("serviceId", serviceId)
                    put("redirectUri", REDIRECT_URI)
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
                    // Check if response is HTML (404 page) — means wrong endpoint
                    if (respBody.startsWith("<!DOCTYPE") || respBody.startsWith("<html")) {
                        Log.e(TAG, "Got HTML instead of JSON: HTTP ${resp.code}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Composio API error. Check your API key.", Toast.LENGTH_LONG).show()
                        }
                        return@use
                    }
                    val json = JSONObject(respBody)
                    val authUrl = json.optString("redirectUrl")
                        .ifBlank { json.optString("redirect_url") }
                        .ifBlank { json.optString("url") }
                        .ifBlank { json.optJSONObject("data")?.optString("redirectUrl") ?: "" }
                    if (authUrl.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            val intent = Intent(context, ComposioAuthActivity::class.java).apply {
                                putExtra(ComposioAuthActivity.EXTRA_AUTH_URL, authUrl)
                                putExtra(ComposioAuthActivity.EXTRA_SERVICE_NAME,
                                    ALL_SERVICES.find { it.id == serviceId }?.name ?: serviceId)
                                putExtra(ComposioAuthActivity.EXTRA_SERVICE_ID, serviceId)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { context.startActivity(intent) }
                            catch (e: Exception) { Log.e(TAG, "Cannot start ComposioAuthActivity", e); openInCustomTab(authUrl) }
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
                    Toast.makeText(context, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                        .url("$COMPOSIO_API_BASE/connectedAccounts/$accountId")
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

        val body = JSONObject().apply {
            put("arguments", JSONObject(params))
            if (connectedAccountId.isNotBlank()) put("connectedAccountId", connectedAccountId)
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
                resultData.optString("message",
                    resultData.optString("response",
                        resultData.optString("output",
                            if (resultData.length() > 0) resultData.toString().take(500) else "Done."
                        )
                    )
                )
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
            if (!isConfigured()) error("Automation not configured")

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
                        val stepLabel = "Step ${index + 1}/${steps.size} (${step.serviceName})"
                        Log.i(TAG, "$stepLabel: executing ${step.actionId}")
                        val stepResult = executeAction(
                            step.actionId, enrichedParams, accountId,
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
                    return@runCatching executeAction(
                        step.actionId, step.params, ids.first(),
                        serviceId = step.serviceId
                    ).getOrThrow()
                }
            }

            // ── Single-service fast path ─────────────────────────────
            val service = detectService(instruction)
                ?: error("Couldn't detect which service to use. Try mentioning the service name.")

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
            executeAction(actionId, params, accountId, serviceId = service.id).getOrThrow()
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
     */
    private suspend fun parseIntentWithLLM(
        instruction: String,
        service: ServiceDef,
        groqClient: GroqClient
    ): Pair<String, Map<String, Any>>? {
        val prompt = """You are an automation intent parser. Given a user request for ${service.name}, return a JSON object with exactly two fields:
- "actionId": The most appropriate Composio action ID for ${service.name}. Common ones: ${INTENT_ACTION_MAP.values.filter { it.startsWith(service.id.uppercase()) }.joinToString(", ")}
- "params": A flat key-value map of parameters needed for this action.

User request: ${protectForAi(instruction, source = "automation request")}

Return ONLY valid JSON, nothing else. Example: {"actionId":"GMAIL_SEND_EMAIL","params":{"to":"john@example.com","subject":"Hello","body":"Hi there"}}"""

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
            "twitter" -> "TWITTER_CREATE_A_TWEET" to mapOf("text" to instruction)
            "discord" -> "DISCORD_SEND_A_MESSAGE_TO_A_CHANNEL" to mapOf("content" to instruction)
            "linkedin" -> "LINKEDIN_CREATE_A_POST" to mapOf("text" to instruction)
            "reddit" -> "REDDIT_CREATE_A_POST" to mapOf("title" to instruction, "text" to instruction)
            "googledrive" -> "GOOGLE_DRIVE_UPLOAD_FILE" to mapOf("content" to instruction)
            "googlesheets" -> "GOOGLE_SHEETS_READ_SHEET" to mapOf("spreadsheetId" to "", "range" to "A1:Z100")
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
}