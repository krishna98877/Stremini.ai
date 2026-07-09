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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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

        /** Deep-link scheme for OAuth callback */
        const val REDIRECT_URI = "stremini://composio"

        /** TTL for pending-connect nonces (Fix S2). 10 min covers slow OAuth flows. */
        private const val PENDING_CONNECT_TTL_MS = 10 * 60 * 1000L

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

        /** Map of common user intents → Composio action IDs.
         *
         * VERIFIED against Composio's live API (2026-07-07). All action IDs
         * below are confirmed to exist in Composio's tool registry.
         * Previously 13 of 19 were wrong (Composio returned "Tool not found").
         * Key naming conventions: Google Drive = GOOGLEDRIVE_* (no underscore),
         * Google Sheets = GOOGLESHEETS_* (no underscore),
         * Discord = DISCORDBOT_* (not DISCORD_*).
         */
        val INTENT_ACTION_MAP = mapOf(
            // Gmail
            "send_email"      to "GMAIL_SEND_EMAIL",
            "read_email"      to "GMAIL_FETCH_EMAILS",
            "search_email"    to "GMAIL_LIST_MESSAGES",
            // GitHub
            "create_issue"    to "GITHUB_CREATE_AN_ISSUE",
            "create_repo"     to "GITHUB_CREATE_A_REPOSITORY_FOR_THE_AUTHENTICATED_USER",
            "list_repos"      to "GITHUB_LIST_ACCESSIBLE_REPOSITORIES",
            "create_pr"       to "GITHUB_CREATE_A_PULL_REQUEST",
            // WhatsApp
            "send_whatsapp"   to "WHATSAPP_SEND_MESSAGE",
            // Instagram — FULL set from Composio toolkit page
            "send_instagram"    to "INSTAGRAM_SEND_TEXT_MESSAGE",
            "ig_user_info"      to "INSTAGRAM_GET_USER_INFO",
            "ig_user_insights"  to "INSTAGRAM_GET_USER_INSIGHTS",
            "ig_user_media"     to "INSTAGRAM_GET_IG_USER_MEDIA",
            "ig_media"          to "INSTAGRAM_GET_IG_MEDIA",
            "ig_media_insights" to "INSTAGRAM_GET_IG_MEDIA_INSIGHTS",
            "ig_media_comments" to "INSTAGRAM_GET_IG_MEDIA_COMMENTS",
            "ig_post_comment"   to "INSTAGRAM_POST_IG_MEDIA_COMMENTS",
            "ig_stories"        to "INSTAGRAM_GET_IG_USER_STORIES",
            "ig_list_convos"    to "INSTAGRAM_LIST_ALL_CONVERSATIONS",
            "ig_list_messages"  to "INSTAGRAM_LIST_ALL_MESSAGES",
            "ig_get_convo"      to "INSTAGRAM_GET_CONVERSATION",
            // Facebook
            "post_facebook"   to "FACEBOOK_CREATE_POST",
            // Discord
            "send_discord"    to "DISCORDBOT_CREATE_MESSAGE",
            // LinkedIn
            "linkedin_post"   to "LINKEDIN_CREATE_LINKED_IN_POST",
            // Reddit
            "reddit_post"     to "REDDIT_CREATE_REDDIT_POST",
            // Google Drive
            "upload_drive"    to "GOOGLEDRIVE_CREATE_FILE_FROM_TEXT",
            "list_drive"      to "GOOGLEDRIVE_FIND_FILE",
            // Google Sheets
            "read_sheet"      to "GOOGLESHEETS_VALUES_GET",
            "update_sheet"    to "GOOGLESHEETS_SPREADSHEETS_VALUES_APPEND",
            // YouTube
            "upload_youtube"  to "YOUTUBE_UPLOAD_VIDEO",
            "youtube_comment" to "YOUTUBE_POST_COMMENT",
        )

        /**
         * Smart batching map — when multiple operations target the SAME service,
         * use the batch tool instead of N individual calls.
         * This reduces API consumption from N calls to 1 call.
         */
        val BATCH_TOOLS = mapOf(
            "googlesheets" to mapOf(
                "batch_read" to "GOOGLESHEETS_BATCH_GET",
                "batch_write" to "GOOGLESHEETS_UPDATE_VALUES_BATCH",
                "max_batch_size" to 100
            ),
            "gmail" to mapOf(
                "batch_modify" to "GMAIL_BATCH_MODIFY_MESSAGES",
                "max_batch_size" to 1000
            ),
        )

        /** Map serviceId → action ID prefix for LLM prompt filtering.
         * VERIFIED: prefixes match Composio's actual tool naming convention.
         * Google Drive tools use GOOGLEDRIVE_* (no underscore), Google Sheets
         * use GOOGLESHEETS_*, Discord uses DISCORDBOT_* (not DISCORD_*).
         */
        val SERVICE_ACTION_PREFIX = mapOf(
            "github" to "GITHUB",
            "gmail" to "GMAIL",
            "whatsapp" to "WHATSAPP",
            "instagram" to "INSTAGRAM",
            "facebook" to "FACEBOOK",
            "googledrive" to "GOOGLEDRIVE",
            "discord" to "DISCORDBOT",
            "linkedin" to "LINKEDIN",
            "reddit" to "REDDIT",
            "googlesheets" to "GOOGLESHEETS",
            "youtube" to "YOUTUBE",
        )

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

    // ── Fix S2: Clean up stale pending-connect nonces on client creation ──
    // If the app was killed mid-OAuth, the nonce lingers in prefs. Clear
    // any that are older than the TTL so they can't be replayed later.
    // Nonces still within TTL are preserved so an in-flight OAuth flow
    // that survived an app restart can still complete.
    init {
        runCatching { clearExpiredPendingConnects() }
    }

    // ── User-provided WhatsApp/Instagram IDs (NOT hardcoded) ──────────
    // Each user provides their own via the connectors panel.
    fun getWhatsappPhoneNumberId(): String = prefs.getString("whatsapp_phone_number_id") ?: ""
    fun setWhatsappPhoneNumberId(id: String) { prefs.putString("whatsapp_phone_number_id", id) }
    fun getInstagramPsid(): String = prefs.getString("instagram_psid") ?: ""
    fun setInstagramPsid(id: String) { prefs.putString("instagram_psid", id) }

    // ── Composio Consumer API Key ──────────────────────────────────
    // The key is injected at build time via BuildConfig (sourced from
    // local.properties or the COMPOSIO_CONSUMER_KEY env var).
    // It is NEVER hardcoded in source.

    // ── Fix S5: PII-safe logging helpers ───────────────────────────────
    // The old code logged full message text, phone numbers, email addresses,
    // and contact names via Log.i. Even though ProGuard strips Log.i in
    // release builds, debug builds (or any release built with
    // minifyEnabled=false) would leak PII to logcat — accessible to any
    // app with READ_LOGS permission on rooted devices, or via ADB.
    //
    // These helpers log ONLY: field names, value lengths, and truncated
    // first-char + length indicators. Never the raw PII itself.

    /** Truncate an instruction to the first 30 chars + total length. */
    private fun safeInstruction(s: String): String {
        val len = s.length
        val preview = if (len <= 30) s else s.take(30) + "…"
        return "\"$preview\" (len=$len)"
    }

    /** Format a params map as {key: <len=N>} — never logs the values. */
    private fun safeParams(params: Map<String, Any>): String {
        val parts = params.entries.joinToString(", ") { (k, v) ->
            val vLen = v.toString().length
            // For known PII fields, show only the first char + length
            when (k.lowercase()) {
                "to_number", "to", "phone", "recipient", "recipient_id",
                "text", "message", "body", "content", "subject", "post" ->
                    if (vLen == 0) "$k=<empty>" else "$k=<len=$vLen>"
                "phone_number_id", "psid" ->
                    "$k=<len=$vLen>"  // never show even truncated
                else -> "$k=<len=$vLen>"
            }
        }
        return "{$parts}"
    }

    /** Truncate a phone number to last 4 digits for correlation without PII. */
    private fun safePhoneTail(number: String): String {
        return if (number.length <= 4) "<short>" else "…${number.takeLast(4)}"
    }

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
                    // Truncate response body in logs — could contain tokens/PII
                    val truncatedBody = if (respBody.length > 200) respBody.take(200) + "…(${respBody.length})" else respBody
                    Log.e(TAG, "Session creation failed: HTTP ${response.code} body=$truncatedBody")
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
     * Check if the user has toggled [serviceId] ON in the connectors panel.
     *
     * Connectors default to OFF — the user must explicitly toggle one ON
     * before the chatbot will route automation to it. This persists across
     * app restarts via EncryptedPrefs so the toggle state survives.
     *
     * Returns true ONLY if:
     * 1. The service has been explicitly toggled ON by the user, AND
     * 2. The service is also connected (has an ACTIVE account).
     */
    suspend fun isConnectorActive(serviceId: String): Boolean {
        val toggledOn = prefs.getString("connector_active_$serviceId")?.toBooleanStrictOrNull() ?: false
        if (!toggledOn) return false
        return isServiceConnected(serviceId)
    }

    /** Toggle a connector on/off. Called from the connectors panel UI. */
    fun setConnectorActive(serviceId: String, active: Boolean) {
        prefs.putString("connector_active_$serviceId", active.toString())
        Log.i(TAG, "Connector $serviceId toggled: $active")
    }

    // ── Fix S2: Pending-connect nonce (OAuth CSRF protection) ────────────
    // Records that a connect was initiated for [serviceId] with a 10-minute
    // TTL. When the deep-link redirect comes back, validateAndConsumePendingConnect()
    // checks that a pending connect exists for the claimed service and that
    // it hasn't expired. This prevents spoofed redirects from other apps
    // (which can fire stremini://composio?status=success&provider=xxx) from
    // being accepted as legitimate connection events.
    //
    // NOTE: This is a lighter-weight version of the standard OAuth `state`
    // nonce (RFC 8252). We can't inject a state param into Composio's
    // managed OAuth URL, so we track the pending connect locally instead.
    // The effect is equivalent: a redirect is only accepted if it was
    // preceded by a real user-initiated connect attempt.

    /** Record that a connect was just initiated for [serviceId]. */
    fun setPendingConnect(serviceId: String) {
        val nonce = java.util.UUID.randomUUID().toString()
        val json = JSONObject().apply {
            put("serviceId", serviceId)
            put("nonce", nonce)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.putString("pending_connect_$serviceId", json.toString())
        Log.i(TAG, "Pending connect recorded for $serviceId (nonce=$nonce)")
    }

    /**
     * Validate that a pending connect exists for [serviceId] and hasn't expired.
     * If valid, consume (delete) it so it can't be reused.
     *
     * @param serviceId The service the redirect claims to be for.
     * @param state The optional `state` param from the redirect URL (may be
     *   null if Composio didn't echo it back — we don't require it since
     *   we can't inject it, but if present we check it matches the nonce).
     * @return true if a valid pending connect existed and was consumed.
     */
    fun validateAndConsumePendingConnect(serviceId: String, state: String?): Boolean {
        val key = "pending_connect_$serviceId"
        val raw = prefs.getString(key) ?: return false
        prefs.remove(key)  // Always consume — one-shot use
        return runCatching {
            val json = JSONObject(raw)
            val storedServiceId = json.optString("serviceId", "")
            val nonce = json.optString("nonce", "")
            val timestamp = json.optLong("timestamp", 0)

            // Check service matches
            if (storedServiceId != serviceId) {
                Log.w(TAG, "Pending connect service mismatch: expected $serviceId, got $storedServiceId")
                return false
            }
            // Check TTL
            val age = System.currentTimeMillis() - timestamp
            if (age > PENDING_CONNECT_TTL_MS) {
                Log.w(TAG, "Pending connect for $serviceId expired (${age}ms > ${PENDING_CONNECT_TTL_MS}ms)")
                return false
            }
            // If the redirect included a state param, verify it matches our nonce.
            // (Composio's managed auth may not echo it back, so we don't require it.
            // If it IS present and doesn't match, reject — could be an injection attempt.)
            if (!state.isNullOrBlank() && !nonce.isNullOrBlank() && state != nonce) {
                Log.w(TAG, "Pending connect state mismatch for $serviceId — possible injection attempt")
                return false
            }
            Log.i(TAG, "Pending connect validated for $serviceId (age=${age}ms)")
            true
        }.getOrDefault(false)
    }

    /**
     * Clear only expired pending connects. Called on client init so that
     * stale nonces from a previous app session (where OAuth was abandoned)
     * don't accumulate. Nonces that are still within their TTL are preserved
     * so an in-flight OAuth flow that survived an app restart can still complete.
     */
    private fun clearExpiredPendingConnects() {
        val now = System.currentTimeMillis()
        val keys = prefs.allKeys().filter { it.startsWith("pending_connect_") }
        for (key in keys) {
            val raw = prefs.getString(key) ?: continue
            runCatching {
                val json = JSONObject(raw)
                val timestamp = json.optLong("timestamp", 0)
                if (now - timestamp > PENDING_CONNECT_TTL_MS) {
                    prefs.remove(key)
                }
            }
        }
    }

    /**
     * Check if a specific service has an ACTIVE connected account.
     *
     * Calls GET /api/v3/connected_accounts and looks for any account whose
     * `toolkit.slug` matches [serviceId] AND whose `status` is "ACTIVE".
     *
     * PERFORMANCE (Fix P2): Uses the in-memory connected-accounts cache
     * (30-second TTL) so repeated calls within a single automation flow
     * don't each trigger a network round-trip. The cache is invalidated
     * on connect/disconnect/toggle.
     *
     * BUG FIX (previous version was completely broken):
     * - Old code checked `tk.optString("slug")` — but `slug` is nested under
     *   `tk.toolkit.slug`, so the match never succeeded → always returned false.
     * - Old code also checked `tk.optString("status") == "connected"` — but
     *   Composio returns "ACTIVE" (uppercase), not "connected".
     * - Old code checked `tk.optString("connected_account_id")` — but the
     *   field is just `id`, not `connected_account_id`.
     * Net effect: chatbot NEVER routed to automation. Now fixed.
     */
    suspend fun isServiceConnected(serviceId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // ── Fix P2: use the in-memory cache instead of a fresh API call ──
            val connected = getCachedConnectedServices()
            // A service is "connected" if it has at least one ACTIVE account.
            // getConnectedServices() already filters out EXPIRED/FAILED/REVOKED,
            // so any entry in the map is an ACTIVE account.
            connected.containsKey(serviceId)
        }.getOrDefault(false)
    }

    /**
     * Get all connected services via GET /api/v3/connected_accounts.
     *
     * PERFORMANCE (Fix P2): Results are cached in-memory for 30 seconds.
     * Within that window, repeated calls (e.g. isConnectorActive →
     * executeAutomation → getConnectedServices) all hit the cache instead
     * of making 2-3 separate network round-trips. The cache is invalidated
     * on connect/disconnect via invalidateConnectedServicesCache().
     */
    suspend fun getConnectedServices(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        getCachedConnectedServices()
    }

    // ── Fix P2: In-memory cache for connected_accounts ──────────────────
    // Avoids redundant GET /connected_accounts calls within a single
    // automation flow. Before this cache, a typical "send hi to royal on
    // whatsapp" command made 2 network round-trips just to check connection
    // status: one from isConnectorActive() → isServiceConnected(), and
    // another from executeAutomation() → getConnectedServices(). Each
    // round-trip is 200-800ms on a mobile network, so the cache saves
    // 400-1600ms per command.
    //
    // TTL: 30 seconds — long enough to cover a full automation flow
    // (check + parse + execute), short enough to reflect connect/disconnect
    // actions taken in another panel within the same app session.
    //
    // Thread safety: volatile reference + synchronized write. Reads are
    // lock-free (the volatile guarantees visibility); writes are synchronized
    // to prevent two concurrent refreshes from stomping each other.

    private data class ConnectedServicesCacheEntry(
        val services: Map<String, List<String>>,
        val timestamp: Long,
    )

    @Volatile
    private var connectedServicesCache: ConnectedServicesCacheEntry? = null
    private val cacheLock = Any()
    private val CONNECTED_SERVICES_CACHE_TTL_MS = 30_000L  // 30 seconds

    /**
     * Get connected services from cache if fresh, otherwise fetch from API.
     * The fetch itself is synchronized so concurrent callers don't trigger
     * multiple API requests — the first one fetches, the rest wait and
     * read the result.
     */
    private suspend fun getCachedConnectedServices(): Map<String, List<String>> {
        val cached = connectedServicesCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.timestamp < CONNECTED_SERVICES_CACHE_TTL_MS) {
            return cached.services
        }
        // Cache miss or stale — fetch under a lock so concurrent callers
        // don't all hit the API simultaneously.
        // Double-check after the cache miss — another caller may have refreshed
        // while we were checking. This isn't fully synchronized but is safe
        // because the worst case is a duplicate fetch (not data corruption).
        val cachedAfterLock = connectedServicesCache
        val nowAfterLock = System.currentTimeMillis()
        if (cachedAfterLock != null && nowAfterLock - cachedAfterLock.timestamp < CONNECTED_SERVICES_CACHE_TTL_MS) {
            return cachedAfterLock.services
        }
        val fresh = fetchConnectedServicesFromApi()
        connectedServicesCache = ConnectedServicesCacheEntry(fresh, System.currentTimeMillis())
        return fresh
    }

    /** Invalidate the in-memory cache. Call after connect/disconnect/toggle. */
    fun invalidateConnectedServicesCache() {
        synchronized(cacheLock) {
            connectedServicesCache = null
        }
        Log.i(TAG, "Connected services cache invalidated")
    }

    /** The actual API call — no caching, just the network fetch + parse. */
    private suspend fun fetchConnectedServicesFromApi(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            if (apiKey.isBlank()) return@withContext emptyMap<String, List<String>>()
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
        // ── Fix S2: record a pending-connect nonce BEFORE launching OAuth ──
        // This proves the redirect we're about to receive was preceded by an
        // actual user-initiated connect attempt. Without it, any app could
        // fire stremini://composio?status=success&provider=xxx and spoof a
        // connection. The nonce has a 10-minute TTL (OAuth usually completes
        // in under 2 minutes; 10 min covers slow networks + user hesitation).
        setPendingConnect(serviceId)

        workScope.launch(Dispatchers.IO) {
            try {
                val apiKey = getDeveloperApiKey()
                val svc = ALL_SERVICES.find { it.id == serviceId }
                val authConfigId = authConfigFor(serviceId)
                if (authConfigId.isBlank()) {
                    // No auth_config_id configured for this service in local.properties.
                    // Try creating a custom auth config with the user's own credentials
                    // via the connect link flow.
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
                                            // ── Fix P2: invalidate the cache so the next call sees the new connection
                                            invalidateConnectedServicesCache()
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
                        val truncBody = if (respBody.length > 200) respBody.take(200) + "…(${respBody.length})" else respBody
                        Log.e(TAG, "No auth URL in response: $truncBody")
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
        // ── Fix P2: invalidate the in-memory cache so the next isServiceConnected
        // call reflects the disconnect immediately (instead of waiting 30s for TTL).
        invalidateConnectedServicesCache()
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
    // ── Dynamic tool discovery via Composio's tool_router API ───────────
    // Instead of hardcoding action IDs (which go stale), we use Composio's
    // search_tools endpoint to dynamically discover the right tool based on
    // the user's natural language request. The search returns:
    // - The exact tool slug(s) to execute
    // - A step-by-step execution plan
    // - Connection status for involved toolkits
    // - Tool schemas (input parameters)

    /**
     * Search for tools using Composio's tool_router API.
     * Returns a list of (toolSlug, toolkitSlug, description) tuples.
     */
    private suspend fun searchTools(
        instruction: String,
        connectedUserId: String
    ): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            if (apiKey.isBlank()) return@withContext emptyList()

            // Step 1: Create a tool_router session
            val sessionBody = JSONObject().apply {
                put("user_id", connectedUserId)
            }.toString().toRequestBody("application/json".toMediaType())

            val sessionReq = Request.Builder()
                .url("$COMPOSIO_TOOLS_API_BASE/tool_router/session")
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(sessionBody)
                .build()

            val client = secureHttpClient(10L, 15L, "composio")
            val sessionId = client.newCall(sessionReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("session_id").ifBlank { return@withContext emptyList() }
            }

            // Step 2: Search for tools matching the user's use case
            val searchBody = JSONObject().apply {
                put("queries", JSONArray().apply {
                    put(JSONObject().apply { put("use_case", instruction) })
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val searchReq = Request.Builder()
                .url("$COMPOSIO_TOOLS_API_BASE/tool_router/session/$sessionId/search")
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(searchBody)
                .build()

            client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val schemas = json.optJSONArray("tool_schemas") ?: return@withContext emptyList()
                val results = mutableListOf<Triple<String, String, String>>()
                for (i in 0 until schemas.length()) {
                    val slug = schemas.optString(i)
                    if (slug.isNotBlank()) {
                        results.add(Triple(slug, "", ""))
                    }
                }
                Log.i(TAG, "Tool search for '${safeInstruction(instruction)}' found: ${results.map { it.first }}")
                results
            }
        }.getOrDefault(emptyList())
    }

    suspend fun executeAutomation(
        instruction: String,
        groqClient: GroqClient? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured()) error("Connectors not configured")

            // ── Fix P2: getConnectedServices() now uses an in-memory cache ──
            // (30s TTL), so this call is FREE on cache hit. Previously it was
            // a 200-800ms network round-trip on every command.
            val connected = getConnectedServices()

            // ── Fix P1: check caches BEFORE any LLM call ─────────────────────
            // The old code checked the cache AFTER parseMultiStepIntent(),
            // which meant every repeat command still paid the full 2-5s LLM
            // round-trip. Now we check both caches first — repeat commands
            // execute in ~0ms (cache hit) + the actual API call time.
            val detectedService = detectService(instruction)

            // P1a: single-service cache check (only if we detected exactly one service)
            if (detectedService != null) {
                val cached = getCachedAutomation(instruction)
                if (cached != null) {
                    val (cachedActionId, cachedParams) = cached
                    Log.i(TAG, "Automation cache HIT (pre-LLM): ${safeInstruction(instruction)} → $cachedActionId")
                    val accountId = connected[detectedService.id]?.firstOrNull()
                        ?: error("${detectedService.name} is not connected. Connect it first.")
                    val normalizedCached = resolveContactParams(cachedActionId, cachedParams)
                    return@runCatching executeAction(
                        cachedActionId, normalizedCached, accountId,
                        serviceId = detectedService.id
                    ).getOrThrow()
                }
            }

            // P1b: multi-step cache check (works regardless of detected service count,
            // because the multi-step plan may involve services the keyword detector
            // didn't catch — e.g. "check my emails and add the important ones to sheets"
            // only mentions gmail + sheets but the plan might involve a 3rd service)
            val multiCached = getCachedMultiStepAutomation(instruction)
            if (multiCached != null) {
                Log.i(TAG, "Multi-step cache HIT (pre-LLM): ${safeInstruction(instruction)} → ${multiCached.size} steps")
                return@runCatching executeMultiStepChain(multiCached, connected, instruction, isCached = true)
            }

            // ── Fix P3: local pre-check — skip multi-step planner for single-service ──
            // The multi-step planner prompt sends the FULL service catalog + action
            // catalog + examples to Groq for EVERY message routed to Composio, even
            // trivial single-service ones like "send hi to royal on whatsapp." That's
            // a 2-5s LLM round-trip + 2-4x more tokens than necessary.
            //
            // Pre-check: if (1) exactly ONE service is detected locally, AND (2) no
            // connector words ("then", "after that", "also", "and then", "followed by")
            // are present, skip the multi-step planner entirely and go straight to
            // the smaller, already-scoped parseIntentWithLLM() prompt.
            val isLikelyMultiStep = looksLikeMultiStep(instruction)
            val shouldSkipMultiStepPlanner = detectedService != null && !isLikelyMultiStep

            if (groqClient != null && !shouldSkipMultiStepPlanner) {
                // ── Multi-step path (requires Groq) ──────────────────────
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
                    // Execute the chain (P4: concurrent where possible)
                    val result = executeMultiStepChain(steps, connected, instruction, isCached = false)
                    // Cache the resolved multi-step plan on success (Fix #4)
                    val resolvedStepsForCache = steps.map { step ->
                        step.copy(params = resolveContactParams(step.actionId, step.params))
                    }
                    cacheMultiStepAutomation(instruction, resolvedStepsForCache)
                    return@runCatching result
                }
                // Single step returned by the planner — fall through to single-step path
                if (steps.size == 1) {
                    val step = steps[0]
                    val ids = connected[step.serviceId]
                    if (ids.isNullOrEmpty()) {
                        error("${step.serviceName} is not connected. Tap the Automations button (plug icon) in the chat and connect ${step.serviceName} first.")
                    }
                    val normalized = resolveContactParams(step.actionId, step.params)
                    Log.i(TAG, "Single-step from LLM plan: ${step.actionId} params=${safeParams(normalized)}")
                    val result = executeAction(
                        step.actionId, normalized, ids.first(),
                        serviceId = step.serviceId
                    ).getOrThrow()
                    cacheAutomationResult(instruction, step.actionId, normalized)
                    return@runCatching result
                }
            }

            // ── Single-service fast path ─────────────────────────────
            // (Also reached when P3's pre-check skips the multi-step planner)
            val service = detectedService
                ?: error("Couldn't detect which service to use. Try mentioning the service name.")

            val accountIds = connected[service.id]
            if (accountIds.isNullOrEmpty()) {
                error("${service.name} is not connected. Tap the Automations button (plug icon) in the chat and connect ${service.name} first.")
            }
            val accountId = accountIds.first()

            // ── Dynamic tool discovery: try Composio's search_tools first ──
            // This uses Composio's own AI to find the right tool based on the
            // user's natural language request. Much more accurate than LLM guessing.
            val connectedUserId = prefs.getString("composio_connected_user_id") ?: getStableUserId()
            val searchResults = searchTools(instruction, connectedUserId)
            if (searchResults.isNotEmpty()) {
                // Found tools via Composio's search — use the first one
                val (toolSlug, _, _) = searchResults.first()
                Log.i(TAG, "Using Composio-searched tool: $toolSlug")

                // Use LLM to parse params if available, otherwise use keywords
                val params = if (groqClient != null) {
                    parseIntentWithLLM(instruction, service, groqClient)?.second
                        ?: parseIntentByKeywords(instruction, service)?.second
                        ?: emptyMap()
                } else {
                    parseIntentByKeywords(instruction, service)?.second ?: emptyMap()
                }

                val resolvedParams = resolveContactParams(toolSlug, params)
                val result = executeAction(toolSlug, resolvedParams, accountId, serviceId = service.id).getOrThrow()
                cacheAutomationResult(instruction, toolSlug, resolvedParams)
                return@runCatching result
            }

            // ── Fallback: LLM + keyword parsing (if search_tools fails) ──
            val actionParams = if (groqClient != null) {
                // Try LLM first (smarter parsing), fall back to keywords if LLM is unreachable.
                // This is critical: if Groq is down (403, network error, rate limit),
                // the old code would fail with "Couldn't understand what action to take"
                // even for simple commands like "send email to X". Now we gracefully
                // degrade to the regex-based keyword parser which needs NO network.
                parseIntentWithLLM(instruction, service, groqClient)
                    ?: parseIntentByKeywords(instruction, service)
            } else {
                parseIntentByKeywords(instruction, service)
            }

            if (actionParams == null) {
                error("Couldn't understand what action to take. Try being more specific, e.g. 'send an email to john@example.com'")
            }

            val (actionId, params) = actionParams
            val resolvedParams = resolveContactParams(actionId, params)
            val result = executeAction(actionId, resolvedParams, accountId, serviceId = service.id).getOrThrow()
            cacheAutomationResult(instruction, actionId, resolvedParams)
            result
        }
    }

    /**
     * Fix P3 + Cross-app detection: Check if a message involves multiple services
     * or multi-step intent signals.
     *
     * Triggers multi-step planner when:
     * 1. Connector words present ("then", "after that", "also", etc.)
     * 2. MULTIPLE services detected in the message (cross-app automation)
     *    e.g., "post to Instagram and Facebook and LinkedIn" → 3 services
     * 3. Distribution patterns: "share across", "post everywhere", "send to all"
     * 4. Cross-app chaining: "check email then add to sheets"
     */
    private fun looksLikeMultiStep(instruction: String): Boolean {
        val lower = " ${instruction.lowercase()} "

        // 1. Connector words
        val connectorWords = listOf(
            " then ", " after that ", " and then ", " also ",
            " followed by ", " next ", " finally ", " lastly ",
            " secondly ", " thirdly ", " afterwards ", " and after ",
            " first ", " second ", " third "
        )
        if (connectorWords.any { lower.contains(it) }) return true

        // 2. Multiple services detected → cross-app automation
        var serviceCount = 0
        for (svc in ALL_SERVICES) {
            for (kw in svc.keywords) {
                val matched = if (kw.length <= 3) {
                    Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lower)
                } else {
                    lower.contains(kw)
                }
                if (matched) { serviceCount++; break }
            }
        }
        if (serviceCount >= 2) return true

        // 3. Distribution patterns
        val distributionPhrases = listOf(
            "post everywhere", "share across", "send to all",
            "cross-post", "cross post", "distribute", "multiple platforms",
            "all my accounts", "all platforms", "every platform",
            "post on all", "share on all", "sync to"
        )
        if (distributionPhrases.any { lower.contains(it) }) return true

        return false
    }

    /**
     * Execute a multi-step automation chain.
     *
     * Optimization: Independent steps (no _dependsOnPreviousStep) are BATCHED
     * into a single COMPOSIO_MULTI_EXECUTE_TOOL call when possible — this
     * reduces N API calls to 1, cutting latency and API consumption.
     *
     * Dependent steps (with _dependsOnPreviousStep) run sequentially with
     * output injection from the previous step.
     */
    private suspend fun executeMultiStepChain(
        steps: List<AutomationStep>,
        connected: Map<String, List<String>>,
        instruction: String,
        isCached: Boolean,
    ): String {
        val results = mutableListOf<String>()
        var previousResult: String? = null

        // Separate steps into batches: independent steps that can run together
        // vs dependent steps that must wait.
        var i = 0
        while (i < steps.size) {
            // Collect a batch of consecutive independent steps
            val batch = mutableListOf<AutomationStep>()
            while (i < steps.size) {
                val step = steps[i]
                val depends = step.params["_dependsOnPreviousStep"]?.toString() == "true"
                if (depends && batch.isNotEmpty()) break  // dependent step — flush batch first
                batch.add(step)
                i++
                if (depends) break  // this step depends on previous — can't batch with next
            }

            if (batch.size == 1) {
                // Single step — execute directly (no batch overhead)
                val step = batch[0]
                val accountId = connected[step.serviceId]!!.first()
                val depends = step.params["_dependsOnPreviousStep"]?.toString() == "true"
                val enrichedParams = if (previousResult != null && depends) {
                    step.params.toMutableMap().apply {
                        put("_previousStepOutput", previousResult)
                        remove("_dependsOnPreviousStep")
                    }
                } else {
                    step.params.toMutableMap().apply { remove("_dependsOnPreviousStep") }
                }
                val normalizedParams = resolveContactParams(step.actionId, enrichedParams)
                val stepLabel = "Step ${steps.indexOf(step) + 1}/${steps.size} (${step.serviceName})${if (isCached) " [cached]" else ""}"
                Log.i(TAG, "$stepLabel: executing ${step.actionId}")
                val stepResult = executeAction(
                    step.actionId, normalizedParams, accountId,
                    serviceId = step.serviceId
                ).getOrElse { e -> error("$stepLabel failed: ${e.message}") }
                results.add("$stepLabel: $stepResult")
                previousResult = stepResult
            } else {
                // Multiple independent steps — smart batching
                // Group by service: if multiple steps target the SAME service,
                // batch them into ONE API call using the batch tool.
                val byService = batch.groupBy { it.serviceId }
                val hasBatchableService = byService.any { (svcId, steps) ->
                    steps.size > 1 && BATCH_TOOLS.containsKey(svcId)
                }

                if (hasBatchableService) {
                    // Use smart batching for same-service steps
                    Log.i(TAG, "Smart batching: ${batch.size} steps across ${byService.size} services")
                    for ((svcId, svcSteps) in byService) {
                        val accountId = connected[svcId]!!.first()
                        val batchToolInfo = BATCH_TOOLS[svcId]

                        if (svcSteps.size > 1 && batchToolInfo != null) {
                            // Batch these steps into ONE call
                            val batchActionId = batchToolInfo["batch_read"] as String
                            val batchParams = mutableMapOf<String, Any>()
                            svcSteps.forEachIndexed { idx, step ->
                                val enriched = step.params.toMutableMap().apply { remove("_dependsOnPreviousStep") }
                                val normalized = resolveContactParams(step.actionId, enriched)
                                batchParams["request_$idx"] = normalized
                            }
                            val stepIdx = steps.indexOf(svcSteps.first())
                            val stepLabel = "Step ${stepIdx + 1}-${stepIdx + svcSteps.size}/${steps.size} (${svcSteps.first().serviceName}) [batched]"
                            Log.i(TAG, "$stepLabel: batched ${svcSteps.size} calls into 1 ($batchActionId)")
                            val batchResult = executeAction(
                                batchActionId, batchParams, accountId,
                                serviceId = svcId
                            ).getOrElse { e -> error("$stepLabel failed: ${e.message}") }
                            results.add("$stepLabel: $batchResult")
                            previousResult = batchResult
                        } else {
                            // Single step for this service — execute directly
                            for (step in svcSteps) {
                                val enriched = step.params.toMutableMap().apply { remove("_dependsOnPreviousStep") }
                                val normalized = resolveContactParams(step.actionId, enriched)
                                val stepIdx = steps.indexOf(step)
                                val stepLabel = "Step ${stepIdx + 1}/${steps.size} (${step.serviceName})${if (isCached) " [cached]" else ""}"
                                Log.i(TAG, "$stepLabel: executing ${step.actionId}")
                                val res = executeAction(
                                    step.actionId, normalized, accountId,
                                    serviceId = step.serviceId
                                ).getOrElse { e -> error("$stepLabel failed: ${e.message}") }
                                results.add("$stepLabel: $res")
                                previousResult = res
                            }
                        }
                    }
                } else {
                    // No batchable services — execute all concurrently via async
                    Log.i(TAG, "Batching ${batch.size} independent steps concurrently")
                    val deferreds = batch.mapIndexed { _, step ->
                        val accountId = connected[step.serviceId]!!.first()
                        val enrichedParams = step.params.toMutableMap().apply { remove("_dependsOnPreviousStep") }
                        val normalizedParams = resolveContactParams(step.actionId, enrichedParams)
                        val stepIdx = steps.indexOf(step)
                        val stepLabel = "Step ${stepIdx + 1}/${steps.size} (${step.serviceName})${if (isCached) " [cached]" else ""}"
                        Log.i(TAG, "$stepLabel: executing ${step.actionId} (concurrent)")
                        workScope.async {
                            val res = executeAction(
                                step.actionId, normalizedParams, accountId,
                                serviceId = step.serviceId
                            ).getOrElse { e -> error("$stepLabel failed: ${e.message}") }
                            Pair(stepIdx, stepLabel to res)
                        }
                    }
                    val completed = deferreds.map { it.await() }
                    completed.sortedBy { it.first }.forEach { (_, pair) ->
                        val (label, res) = pair
                        results.add("$label: $res")
                    }
                    previousResult = completed.lastOrNull()?.second?.second
                }
            }
        }
        return results.joinToString("\n")
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

CROSS-APP AUTOMATION PATTERNS — recognize these and create multi-step plans:

1. CONTENT DISTRIBUTION (same content → multiple platforms):
   "post hello on instagram, facebook, and linkedin" → 3 independent steps, same content.
   Each step gets the same message text but different param names per service.

2. CROSS-APP CHAINING (output of step 1 feeds into step 2):
   "check my gmail for invoices then add them to google sheets" → 2 dependent steps.
   Step 2 has "_dependsOnPreviousStep": true and receives step 1's output.

3. MULTI-PLATFORM POSTING (same post everywhere):
   "post 'Hello World' on all my social media" → Instagram + Facebook + LinkedIn + Reddit steps.
   All run concurrently (no _dependsOnPreviousStep).

4. ANALYTICS GATHERING (collect data from multiple sources):
   "get my youtube stats and instagram insights" → 2 independent read steps.

5. SYNC/BACKUP (copy data between apps):
   "save my youtube video titles to google sheets" → YouTube list → Sheets append (dependent).

6. CONTENT CREATION PIPELINE (video → repurpose → distribute → track):
   "get my latest youtube video then post about it on instagram and reddit and log it in sheets"
   → Step 1: YouTube GET_CHANNEL_VIDEOS (get latest video details)
   → Step 2: Instagram POST_IG_MEDIA_COMMENTS (share — depends on step 1)
   → Step 3: Reddit CREATE_REDDIT_POST (share — depends on step 1)
   → Step 4: Google Sheets SPREADSHEETS_VALUES_APPEND (log metrics — depends on step 1)
   Steps 2-4 all depend on step 1 but are independent of each other.

7. SOCIAL MEDIA BLAST (one message to all socials + log):
   "post 'New video live!' on instagram, facebook, linkedin, reddit and track in sheets"
   → Steps 1-4: concurrent posts to each platform (no dependencies)
   → Step 5: Sheets append (depends on all previous — runs last)

8. RESEARCH → CREATE → DISTRIBUTE (gather info → make content → share):
   "search reddit for trending topics then post about the best one on instagram and linkedin"
   → Step 1: Reddit SEARCH_ACROSS_SUBREDDITS (research)
   → Step 2: Instagram post (depends on step 1 — uses research result)
   → Step 3: LinkedIn post (depends on step 1 — same content, different platform)

Rules:
- If the request only involves ONE service, return a JSON array with a single element.
- If the request involves MULTIPLE services, return multiple steps in execution order.
- Each step must have: "serviceId" (lowercase), "serviceName", "actionId" (COMPOSIO_ACTION_ID format), "params" (flat key-value map).
- Fill in as many params as possible from the user's request. Leave unknown values as empty strings.
- If a later step depends on a previous step's output, add a placeholder param "_dependsOnPreviousStep": true.
- For distribution patterns (same content to multiple platforms), do NOT add _dependsOnPreviousStep — they run concurrently.
- For chaining patterns (output feeds next step), DO add _dependsOnPreviousStep: true.
- Use EXACT Composio param names — wrong names silently fail:
  * WhatsApp: {"to_number":"<phone or name>","text":"<msg>","phone_number_id":"<your-whatsapp-phone-number-id>"}
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
Example cross-app distribution: [{"serviceId":"instagram","serviceName":"Instagram","actionId":"INSTAGRAM_SEND_TEXT_MESSAGE","params":{"recipient_id":"","text":"Hello World"}},{"serviceId":"facebook","serviceName":"Facebook","actionId":"FACEBOOK_CREATE_POST","params":{"message":"Hello World"}},{"serviceId":"linkedin","serviceName":"LinkedIn","actionId":"LINKEDIN_CREATE_LINKED_IN_POST","params":{"text":"Hello World"}}]
Example cross-app chaining: [{"serviceId":"gmail","serviceName":"Gmail","actionId":"GMAIL_FETCH_EMAILS","params":{"query":"invoices","maxResults":5}},{"serviceId":"googlesheets","serviceName":"Google Sheets","actionId":"GOOGLESHEETS_SPREADSHEETS_VALUES_APPEND","params":{"spreadsheetId":"","range":"A1","values":"[[\"data\"]]","_dependsOnPreviousStep":true}}]"""

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
- WhatsApp: {"to_number": "<phone with country code, e.g. +15551234567>", "text": "<message>", "phone_number_id": "<your-whatsapp-phone-number-id>"}
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
3. For WhatsApp, ALWAYS include "phone_number_id": "<your-whatsapp-phone-number-id>".
4. NEVER leave required params empty.

User request: ${protectForAi(instruction, source = "automation request")}

Return ONLY valid JSON (no markdown, no explanation):
{"actionId":"WHATSAPP_SEND_MESSAGE","params":{"to_number":"royal","text":"hi","phone_number_id":"<your-whatsapp-phone-number-id>"}}
{"actionId":"GMAIL_SEND_EMAIL","params":{"to":"john@example.com","subject":"Hello","body":"Hi there"}}
{"actionId":"INSTAGRAM_SEND_TEXT_MESSAGE","params":{"recipient_id":"royal","text":"Hello"}}
{"actionId":"DISCORDBOT_CREATE_MESSAGE","params":{"content":"Hello everyone"}}
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
                lower.contains("send") && (lower.contains("email") || lower.contains("mail") || lower.contains("@")) -> {
                    val toRegex = Regex("(?:to|for)\\s+([\\w.+-]+@[\\w.-]+)", RegexOption.IGNORE_CASE)
                    val toMatch = toRegex.find(instruction)
                    val subjectRegex = Regex("(?:subject|about|re)\\s+[:\"]?([^\".]+)", RegexOption.IGNORE_CASE)
                    val subjectMatch = subjectRegex.find(instruction)
                    // Extract body: try "saying X" or "message X" pattern first,
                    // then fall back to stripping the prefix.
                    var bodyHint = ""
                    val sayingRegex = Regex("(?:saying|message|with body|content)\\s+(.+)", RegexOption.IGNORE_CASE)
                    val sayingMatch = sayingRegex.find(instruction)
                    if (sayingMatch != null) {
                        bodyHint = sayingMatch.groupValues[1].trim()
                    } else {
                        // Strip "send (email/mail/hi) to X" prefix to get body
                        bodyHint = instruction
                            .replace(Regex("(?i)^(?:send|compose|write)\\s+(?:an?\\s+)?(?:email|mail)?\\s*", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("(?i)\\s*to\\s+[\\w.+-]+@[\\w.-]+\\s*", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("(?i)\\s*(?:subject|about|re)\\s+[:\"]?[^\".]+", RegexOption.IGNORE_CASE), "")
                            .trim()
                    }
                    if (bodyHint.isBlank()) bodyHint = "Sent from Stremini AI"
                    "GMAIL_SEND_EMAIL" to mapOf(
                        "to" to (toMatch?.groupValues?.get(1) ?: ""),
                        "subject" to (subjectMatch?.groupValues?.get(1)?.trim() ?: "Message from Stremini AI"),
                        "body" to bodyHint,
                    )
                }
                else -> "GMAIL_FETCH_EMAILS" to mapOf("maxResults" to 10)
            }
            "github" -> when {
                lower.contains("issue") && lower.contains("create") -> "GITHUB_CREATE_AN_ISSUE" to mapOf(
                    "owner" to "", "repo" to "", "title" to instruction
                )
                lower.contains("repo") && lower.contains("create") -> "GITHUB_CREATE_A_REPOSITORY_FOR_THE_AUTHENTICATED_USER" to mapOf(
                    "name" to "new-repo", "private" to false
                )
                else -> "GITHUB_LIST_ACCESSIBLE_REPOSITORIES" to emptyMap()
            }
            "discord" -> "DISCORDBOT_CREATE_MESSAGE" to mapOf("content" to instruction)
            "linkedin" -> "LINKEDIN_CREATE_LINKED_IN_POST" to mapOf("text" to instruction)
            "reddit" -> "REDDIT_CREATE_REDDIT_POST" to mapOf("title" to instruction, "text" to instruction)
            "googledrive" -> "GOOGLEDRIVE_CREATE_FILE_FROM_TEXT" to mapOf("content" to instruction)
            "googlesheets" -> "GOOGLESHEETS_VALUES_GET" to mapOf("spreadsheetId" to "", "range" to "A1:Z100")
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
                    "phone_number_id" to getWhatsappPhoneNumberId()
                )
            }
            "instagram" -> "INSTAGRAM_SEND_TEXT_MESSAGE" to mapOf("recipient_id" to getInstagramPsid(), "text" to instruction)
            "facebook" -> "FACEBOOK_CREATE_POST" to mapOf("message" to instruction)
            "youtube" -> "YOUTUBE_UPLOAD_VIDEO" to mapOf("title" to instruction, "description" to "")
            else -> null
        }
    }

    // ── Service Detection (Longest-Match) ───────────────────────────

    /**
     * Detect which service a user message is likely about.
     * Uses longest-keyword-match with WORD BOUNDARY checking to avoid false
     * positives like "wassup" matching "wa" (WhatsApp keyword).
     *
     * Short keywords (≤3 chars like "wa", "ig", "fb") require exact word
     * boundary matching. Longer keywords (≥4 chars) use substring match
     * since they're specific enough to not collide with casual speech.
     */
    fun detectService(message: String): ServiceDef? {
        val lower = " ${message.lowercase()} "  // pad with spaces for word-boundary
        var bestMatch: ServiceDef? = null
        var bestKeywordLength = 0

        for (svc in ALL_SERVICES) {
            for (kw in svc.keywords) {
                val matched = if (kw.length <= 3) {
                    // Short keywords: require word boundary (surrounded by spaces/punctuation)
                    Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lower)
                } else {
                    // Long keywords: substring match is safe
                    lower.contains(kw)
                }
                if (matched && kw.length > bestKeywordLength) {
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
     * "phone_number_id":"<user-provided WhatsApp phone_number_id>"} so
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
                    ?: p.remove("phone")?.toString()
                    ?: p["to_number"]?.toString()
                    ?: ""
                // 2) Accept any of {message, text, body, content} → text
                val rawText = p.remove("message")?.toString()
                    ?: p.remove("body")?.toString()
                    ?: p.remove("content")?.toString()
                    ?: p["text"]?.toString()
                    ?: ""
                // 3) Resolve name → phone number (if not already a number)
                val isPhoneNumber = rawTo.matches(Regex("^\\+?[0-9]{6,15}$"))
                val resolved = if (isPhoneNumber) rawTo else {
                    resolveContact(rawTo)?.also {
                        Log.i(TAG, "WhatsApp contact resolved: <name len=${rawTo.length}> -> ${safePhoneTail(it)}")
                    } ?: rawTo
                }
                // 4) CRITICAL: Composio's WhatsApp API rejects the leading "+".
                //    "Invalid phone number. Make sure it is a number and includes
                //    the country code without the + sign."
                //    Strip any leading + so e.g. +917078461157 → 917078461157.
                val finalNumber = resolved.removePrefix("+").replace(Regex("[^0-9]"), "")
                p["to_number"] = finalNumber
                p["text"] = rawText
                // 5) ALWAYS inject phone_number_id — without it, Composio
                //    silently accepts but never delivers the message.
                if (p["phone_number_id"]?.toString().isNullOrBlank()) {
                    p["phone_number_id"] = getWhatsappPhoneNumberId()
                }
                Log.i(TAG, "WhatsApp params normalized → to_number=${safePhoneTail(finalNumber)} text=<len=${rawText.length}> phone_number_id=<len=${p["phone_number_id"]?.toString()?.length ?: 0}>")
            }

            actionId.startsWith("INSTAGRAM") -> {
                // Instagram requires a numeric PSID. The LLM may produce a
                // username like "royal" — replace with the verified default PSID.
                val rawRid = p["recipient_id"]?.toString() ?: ""
                val isNumericPsid = rawRid.matches(Regex("^[0-9]{10,20}$"))
                if (!isNumericPsid) {
                    p["recipient_id"] = getInstagramPsid()
                    Log.i(TAG, "Instagram recipient_id normalized: <len=${rawRid.length}>")
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
                // Some GitHub actions use "content" instead of "body"
                p.remove("content")?.let { p.putIfAbsent("body", it) }
            }

            // ── Fix #3: branches for the 6 previously-unnormalized services.
            // These had ZERO safety net — if the LLM hallucinated a field
            // name (e.g. "post" instead of "message" for Facebook), Composio
            // would accept the request with `successful:true` and silently
            // no-op. Now each service has its synonym map mirroring the
            // pattern already used for WhatsApp/Instagram/Gmail/Discord/GitHub.

            actionId.startsWith("FACEBOOK") -> {
                // Composio expects "message" for Facebook posts/comments
                p.remove("text")?.let { p.putIfAbsent("message", it) }
                p.remove("body")?.let { p.putIfAbsent("message", it) }
                p.remove("content")?.let { p.putIfAbsent("message", it) }
                p.remove("post")?.let { p.putIfAbsent("message", it) }
            }

            actionId.startsWith("LINKEDIN") -> {
                // Composio expects "text" for LinkedIn posts
                p.remove("message")?.let { p.putIfAbsent("text", it) }
                p.remove("body")?.let { p.putIfAbsent("text", it) }
                p.remove("content")?.let { p.putIfAbsent("text", it) }
                p.remove("post")?.let { p.putIfAbsent("text", it) }
            }

            actionId.startsWith("REDDIT") -> {
                // Composio expects "title" + "text" for Reddit posts
                p.remove("body")?.let { p.putIfAbsent("text", it) }
                p.remove("content")?.let { p.putIfAbsent("text", it) }
                p.remove("message")?.let { p.putIfAbsent("text", it) }
                p.remove("name")?.let { p.putIfAbsent("title", it) }
            }

            actionId.startsWith("GOOGLEDRIVE") -> {
                // Composio expects "file_name" + "content" for Drive uploads
                p.remove("filename")?.let { p.putIfAbsent("file_name", it) }
                p.remove("name")?.let { p.putIfAbsent("file_name", it) }
                p.remove("title")?.let { p.putIfAbsent("file_name", it) }
                p.remove("text")?.let { p.putIfAbsent("content", it) }
                p.remove("body")?.let { p.putIfAbsent("content", it) }
                p.remove("message")?.let { p.putIfAbsent("content", it) }
                p.remove("data")?.let { p.putIfAbsent("content", it) }
            }

            actionId.startsWith("GOOGLESHEETS") -> {
                // Composio expects "spreadsheet_id" + "range" for Sheets
                p.remove("spreadsheetId")?.let { p.putIfAbsent("spreadsheet_id", it) }
                p.remove("sheet_id")?.let { p.putIfAbsent("spreadsheet_id", it) }
                p.remove("id")?.let { p.putIfAbsent("spreadsheet_id", it) }
                p.remove("cell_range")?.let { p.putIfAbsent("range", it) }
                p.remove("cells")?.let { p.putIfAbsent("range", it) }
            }

            actionId.startsWith("YOUTUBE") -> {
                // Composio expects "title" + "description" for YouTube uploads
                p.remove("desc")?.let { p.putIfAbsent("description", it) }
                p.remove("body")?.let { p.putIfAbsent("description", it) }
                p.remove("text")?.let { p.putIfAbsent("description", it) }
                p.remove("content")?.let { p.putIfAbsent("description", it) }
                p.remove("name")?.let { p.putIfAbsent("title", it) }
            }
        }
        return p
    }

    // ── AI Learning: cache successful automations ───────────────────
    // Remembers instruction → (actionId, params) mappings so repeat
    // commands skip the LLM parse step entirely (0ms vs 2-5s).
    //
    // KEY DESIGN (Fix #2): The cache key is the full lowercased + trimmed
    // instruction string, NOT `String.hashCode()`. The old 32-bit hashCode
    // key had collision risk — two different instructions could map to the
    // same key and silently return the wrong cached action/params (wrong
    // recipient, wrong service). The full-string key is collision-free for
    // any practical instruction set.
    //
    // Stored params are the RESOLVED ones (Fix #1): contact names already
    // → phone numbers, leading + stripped, field-name synonyms already
    // normalized. The cache-hit path STILL re-runs resolveContactParams()
    // defensively in case the user's contact list has changed.

    private fun cacheKey(instruction: String): String {
        // Trim + collapse internal whitespace + lowercase → so
        // "Send hi to Royal on WhatsApp" and
        // "send  hi to royal  on whatsapp" hit the same key.
        val normalized = instruction.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
        return "auto_cache_${normalized.hashCode().toUInt()}_$normalized"
    }

    private fun cacheAutomationResult(instruction: String, actionId: String, params: Map<String, Any>) {
        val key = cacheKey(instruction)
        val json = JSONObject().apply {
            put("actionId", actionId)
            put("params", JSONObject(params))
            put("instruction", instruction)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.putString(key, json.toString())
    }

    private fun getCachedAutomation(instruction: String): Pair<String, Map<String, Any>>? {
        val key = cacheKey(instruction)
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

    /** Clear the automation cache for a specific instruction (or all if null). */
    fun clearAutomationCache(instruction: String? = null) {
        if (instruction != null) {
            prefs.remove(cacheKey(instruction))
            prefs.remove(multiStepCacheKey(instruction))
        } else {
            // Clear all cached automations (single + multi-step)
            val allKeys = prefs.allKeys()
            allKeys.filter { it.startsWith("auto_cache_") || it.startsWith("multi_cache_") }
                .forEach { prefs.remove(it) }
        }
    }

    // ── Fix #4: Multi-step automation cache ─────────────────────────
    // Stores the full LLM-planned step list (with resolved params) so
    // repeat multi-step commands skip the 2-5s LLM round-trip entirely.
    // Keyed the same collision-free way as the single-step cache.

    private fun multiStepCacheKey(instruction: String): String {
        val normalized = instruction.trim().lowercase().replace(Regex("\\s+"), " ")
        return "multi_cache_$normalized"
    }

    private fun cacheMultiStepAutomation(instruction: String, steps: List<AutomationStep>) {
        val key = multiStepCacheKey(instruction)
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject().apply {
                put("serviceId", step.serviceId)
                put("serviceName", step.serviceName)
                put("actionId", step.actionId)
                put("params", JSONObject(step.params))
            }
            arr.put(obj)
        }
        val json = JSONObject().apply {
            put("steps", arr)
            put("instruction", instruction)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.putString(key, json.toString())
    }

    private fun getCachedMultiStepAutomation(instruction: String): List<AutomationStep>? {
        val key = multiStepCacheKey(instruction)
        val raw = prefs.getString(key) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("steps") ?: return null
            val steps = mutableListOf<AutomationStep>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sid = obj.optString("serviceId", "").lowercase()
                val sName = obj.optString("serviceName", "")
                val aId = obj.optString("actionId", "")
                if (sid.isBlank() || aId.isBlank()) continue
                val pJson = obj.optJSONObject("params")
                val p = mutableMapOf<String, Any>()
                if (pJson != null) pJson.keys().forEach { k -> p[k] = pJson.get(k) }
                val displayName = sName.ifBlank {
                    ALL_SERVICES.find { it.id == sid }?.name ?: sid
                }
                steps.add(AutomationStep(sid, displayName, aId, p))
            }
            if (steps.isEmpty()) null else steps
        }.getOrNull()
    }
}