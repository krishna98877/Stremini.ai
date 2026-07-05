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
 * - POST /api/v1/connectedAccounts       → initiate connection (returns auth URL)
 * - GET  /api/v1/connectedAccounts        → list all connected accounts
 * - GET  /api/v1/connectedAccounts?providerName=github → check specific service
 * - DELETE /api/v1/connectedAccounts/{id} → disconnect a specific account
 * - POST /api/v1/actions/execute          → execute an automation action
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
        const val COMPOSIO_API_BASE = "https://backend.composio.dev/api/v1"
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

    // ── Connected Accounts ───────────────────────────────────────────

    /**
     * Check if a specific service has a connected account.
     */
    suspend fun isServiceConnected(serviceId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            val client = secureHttpClient(connectTimeoutSeconds = 10, readTimeoutSeconds = 15, useCase = "composio")
            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/connectedAccounts?providerName=${URLEncoder.encode(serviceId, "UTF-8")}")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use false
                    val json = JSONObject(body)
                    val accounts = json.optJSONArray("connectedAccounts") ?: json.optJSONArray("data")
                    accounts != null && accounts.length() > 0
                } else false
            }
        }.getOrDefault(false)
    }

    /**
     * Get all connected account IDs grouped by service provider.
     * Returns map of providerName → list of connectedAccountIds.
     */
    suspend fun getConnectedServices(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getDeveloperApiKey()
            val client = secureHttpClient(connectTimeoutSeconds = 10, readTimeoutSeconds = 15, useCase = "composio")
            val request = Request.Builder()
                .url("$COMPOSIO_API_BASE/connectedAccounts")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use emptyMap()
                    val json = JSONObject(body)
                    val accounts = json.optJSONArray("connectedAccounts")
                        ?: json.optJSONArray("data")
                        ?: return@use emptyMap()
                    val result = mutableMapOf<String, MutableList<String>>()
                    for (i in 0 until accounts.length()) {
                        val acct = accounts.getJSONObject(i)
                        val provider = acct.optString("providerName", acct.optString("provider", ""))
                        val id = acct.optString("id", acct.optString("connectedAccountId", ""))
                        if (provider.isNotBlank() && id.isNotBlank()) {
                            result.getOrPut(provider) { mutableListOf() }.add(id)
                        }
                    }
                    result
                } else emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    // ── Connect a Service (Managed Auth via WebView) ────────────────

    /**
     * Initiate Composio managed OAuth for a service.
     *
     * Flow:
     * 1. POST to /connectedAccounts with providerName + redirectUri → get auth URL
     * 2. Launch ComposioAuthActivity (WebView) with the auth URL
     * 3. User logs in with their own credentials on Composio's hosted OAuth page
     * 4. Composio redirects to stremini://composio?provider=xxx&status=success
     * 5. MainActivity.onNewIntent() handles the deep-link
     * 6. Connection is complete — the account appears in getConnectedServices()
     *
     * NO API KEY IS NEEDED FROM THE END USER.
     * The developer API key is used to authorize the connection request.
     */
    fun connectService(serviceId: String) {
        if (!isConfigured()) {
            Toast.makeText(context, "Automation not configured. Set the Composio developer key in Settings.", Toast.LENGTH_LONG).show()
            return
        }

        workScope.launch(Dispatchers.IO) {
            // Check connectivity BEFORE making the API call
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val isConnected = cm?.activeNetworkInfo?.isConnected == true
            if (!isConnected) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No internet. Connect to WiFi or mobile data and try again.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val apiKey = getDeveloperApiKey()
                val serviceName = ALL_SERVICES.find { it.id == serviceId }?.name ?: serviceId
                val body = JSONObject().apply {
                    put("providerName", serviceId)
                    put("redirectUri", REDIRECT_URI)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$COMPOSIO_API_BASE/connectedAccounts")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val client = secureHttpClient(
                    connectTimeoutSeconds = 10, readTimeoutSeconds = 15, useCase = "composio"
                )
                val response = client.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val respBody = resp.body?.string() ?: "{}"
                        val json = JSONObject(respBody)

                        // Composio returns the auth URL — try multiple field names
                        val authUrl = json.optString("redirectUrl")
                            .takeIf { it.isNotBlank() }
                            ?: json.optString("authUrl")
                                .takeIf { it.isNotBlank() }
                            ?: json.optString("connectionUrl")
                                .takeIf { it.isNotBlank() }
                            ?: json.optString("url")
                                .takeIf { it.isNotBlank() }

                        if (authUrl != null) {
                            // Open in ComposioAuthActivity (WebView) — NOT external browser
                            withContext(Dispatchers.Main) {
                                val intent = Intent(context, ComposioAuthActivity::class.java).apply {
                                    putExtra(ComposioAuthActivity.EXTRA_AUTH_URL, authUrl)
                                    putExtra(ComposioAuthActivity.EXTRA_SERVICE_NAME, serviceName)
                                    putExtra(ComposioAuthActivity.EXTRA_SERVICE_ID, serviceId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                
                                val pending = PendingIntent.getActivity(
                                    context, serviceId.hashCode(), intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )

                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Cannot start ComposioAuthActivity from Service", e)
                                    
                                    // Fallback: fire a system notification. Tapping it opens the WebView.
                                    // This respects Android 12+ background activity launch restrictions.
                                    try {
                                        val nm = androidx.core.app.NotificationManagerCompat.from(context)
                                        val notif = androidx.core.app.NotificationCompat.Builder(context, "chat_head_service")
                                            .setSmallIcon(R.drawable.ic_stremini_logo)
                                            .setContentTitle("Connect $serviceName")
                                            .setContentText("Tap to link your $serviceName account.")
                                            .setContentIntent(pending)
                                            .setAutoCancel(true)
                                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                                            .build()
                                        
                                        nm.notify(serviceId.hashCode(), notif)
                                        Toast.makeText(context, "Tap the notification to connect $serviceName", Toast.LENGTH_LONG).show()
                                    } catch (notifEx: Exception) {
                                        Log.e(TAG, "Notification fallback failed", notifEx)
                                        openInCustomTab(authUrl)
                                    }
                                }
                            }
                        } else {
                            // No URL returned — redirect to Composio dashboard for manual connection
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Opening Composio dashboard to connect $serviceName...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                openInCustomTab("$COMPOSIO_CONNECT_BASE/connect-apps")
                            }
                        }
                    } else {
                        Log.e(TAG, "connectService(${serviceId}) failed: ${resp.code}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Connection failed (error ${resp.code}). Please try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "connectService: no internet", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cannot reach Composio. Check your internet.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "connectService: security/background restriction", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Tap the notification to finish connecting.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectService(${serviceId}) error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not open connection. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Fallback: open URL in Chrome Custom Tab */
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
            val client = secureHttpClient(connectTimeoutSeconds = 10, readTimeoutSeconds = 15, useCase = "composio")

            var allSucceeded = true
            for (accountId in accountIds) {
                val request = Request.Builder()
                    .url("$COMPOSIO_API_BASE/connectedAccounts/$accountId")
                    .addHeader("x-api-key", apiKey)
                    .delete()
                    .build()
                client.newCall(request).execute().use { response ->
                    // Drain the body so the connection can be reused.
                    response.body?.close()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "DELETE connectedAccounts/$accountId failed: HTTP ${response.code}")
                        allSucceeded = false
                    }
                }
            }
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
            put("actionId", actionId)
            put("inputParams", JSONObject(params))
            put("connectedAccountId", connectedAccountId)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$COMPOSIO_API_BASE/actions/execute")
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return secureHttpClient(connectTimeoutSeconds = 15, readTimeoutSeconds = 60, useCase = "composio_execute")
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
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

    /** Remove a specific account from the local connected-services cache. */
    fun disconnectServiceLocally(serviceId: String, accountId: String) {
        val raw = prefs.getString("connected_services_${serviceId}") ?: "[]"
        runCatching {
            val arr = org.json.JSONArray(raw)
            val filtered = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "")
                if (id != accountId && id.isNotBlank()) filtered.put(id)
            }
            prefs.putString("connected_services_${serviceId}", filtered.toString())
        }
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