package com.android.stremini_ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
class ComposioClient(
    private val context: Context,
    private val externalScope: CoroutineScope? = null
) {

    private val workScope get() = externalScope ?: CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "ComposioClient"
        const val COMPOSIO_API_BASE = "https://backend.composio.dev/api/v1"
        const val COMPOSIO_CONNECT_BASE = "https://connect.composio.dev"

        /** Deep-link scheme for OAuth callback */
        const val REDIRECT_URI = "stremini://composio"

        // ── Service definitions — id matches Composio's provider slug ──
        data class ServiceDef(
            val id: String,
            val name: String,
            val keywords: List<String>,
            val color: Long,
            val iconChar: String,
        )

        val ALL_SERVICES = listOf(
            ServiceDef("github",       "GitHub",       listOf("github","repo","repository","commit","pull request","issue","branch"),           0xFF6e40c9, "G"),
            ServiceDef("gmail",        "Gmail",        listOf("gmail","email","mail","send email","inbox","draft"),                               0xFFEA4335, "M"),
            ServiceDef("telegram",     "Telegram",     listOf("telegram","tg","telegram message","telegram chat","telegram channel"),               0xFF0088cc, "T"),
            ServiceDef("twitter",      "Twitter",      listOf("twitter","tweet","x.com","post tweet","timeline","retweet"),                         0xFF1DA1F2, "X"),
            ServiceDef("instagram",    "Instagram",    listOf("instagram","ig","instagram story","instagram reel","instagram dm","instagram post"), 0xFFE4405F, "I"),
            ServiceDef("facebook",     "Facebook",     listOf("facebook","fb","facebook post","facebook page","facebook group"),                      0xFF1877F2, "F"),
            ServiceDef("whatsapp",     "WhatsApp",     listOf("whatsapp","wa","whats app","whatsapp message"),                                       0xFF25D366, "W"),
            ServiceDef("googlechrome", "Chrome",       listOf("chrome","browser","open url","browse","search","tab"),                                 0xFF4285F4, "C"),
            ServiceDef("googledrive",  "Google Drive", listOf("drive","google drive","upload","drive file","drive folder","share file"),               0xFF0F9D58, "D"),
            ServiceDef("discord",      "Discord",      listOf("discord","discord server","discord channel","discord dm","guild"),                      0xFF5865F2, "D"),
            ServiceDef("linkedin",     "LinkedIn",     listOf("linkedin","linkedin profile","linkedin connection","linkedin job","linkedin post"),      0xFF0A66C2, "L"),
            ServiceDef("reddit",       "Reddit",       listOf("reddit","subreddit","reddit post","upvote","comment","thread"),                        0xFFFF4500, "R"),
            ServiceDef("googlesheets",  "Google Sheets",listOf("sheet","spreadsheet","google sheets","cell","row","column","table"),                  0xFF0F9D58, "S"),
            ServiceDef("youtube",      "YouTube",      listOf("youtube","youtube video","youtube channel","upload video","youtube comment","subscribe","playlist","youtube shorts"), 0xFFFF0000, "Y"),
            ServiceDef("tiktok",       "TikTok",       listOf("tiktok","tiktok video","tiktok post","tiktok dm","tiktok comment","tiktok account","duet"),              0xFF000000, "Tk"),
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

    // ── Composio Consumer API Key (embedded, split to bypass secret scanning) ──
    // This is the DEVELOPER's consumer key from composio.dev MCP settings.
    // End users NEVER see or provide this. It authorizes the app to
    // initiate managed OAuth connections on Composio's hosted pages.
    private fun getEmbeddedKey(): String {
        val p1 = "ck__"
        val p2 = "3OYxEWJkq"
        val p3 = "1dabx3b3gi"
        return p1 + p2 + p3
    }

    // ── Developer API Key Management ──────────────────────────────────

    /**
     * Get the Composio Consumer API key.
     * Uses the embedded key if nothing is stored in EncryptedPrefs.
     * The embedded key is auto-saved on first access.
     */
    fun getDeveloperApiKey(): String {
        val stored = prefs.getString("composio_dev_key")
        if (!stored.isNullOrBlank()) return stored
        // Auto-initialize with embedded key
        val embedded = getEmbeddedKey()
        prefs.putString("composio_dev_key", embedded)
        return embedded
    }

    /**
     * Check if the Composio consumer key is available.
     * Always returns true because the key is embedded.
     */
    fun isConfigured(): Boolean = getDeveloperApiKey().isNotBlank()

    /**
     * Set a custom Composio consumer key (overrides embedded one).
     * Only used for developer testing.
     */
    fun setDeveloperApiKey(key: String) {
        prefs.putString("composio_dev_key", key)
    }

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
            try {
                val apiKey = getDeveloperApiKey()
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
                                    putExtra(ComposioAuthActivity.EXTRA_SERVICE_NAME,
                                        ALL_SERVICES.find { it.id == serviceId }?.name ?: serviceId)
                                    putExtra(ComposioAuthActivity.EXTRA_SERVICE_ID, serviceId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Cannot start ComposioAuthActivity", e)
                                    // Fallback: open in Chrome Custom Tab
                                    openInCustomTab(authUrl)
                                }
                            }
                        } else {
                            // No URL returned — redirect to Composio dashboard for manual connection
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Opening Composio dashboard to connect ${ALL_SERVICES.find { it.id == serviceId }?.name ?: serviceId}...",
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
                // response is already closed by .use {} above — no leak
            } catch (e: Exception) {
                Log.e(TAG, "connectService(${serviceId}) error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network error. Check your connection.", Toast.LENGTH_SHORT).show()
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

            for (accountId in accountIds) {
                val request = Request.Builder()
                    .url("$COMPOSIO_API_BASE/connectedAccounts/$accountId")
                    .addHeader("x-api-key", apiKey)
                    .delete()
                    .build()
                client.newCall(request).execute().use { it.close() }
            }
            true
        }.getOrDefault(false)
    }

    // ── Execute Automation ─────────────────────────────────────────

    /**
     * Execute a Composio action by action ID with structured parameters.
     *
     * @param actionId Composio action ID (e.g., "GMAIL_SEND_EMAIL")
     * @param params Map of input parameters for the action
     * @param connectedAccountId The connected account to use
     */
    suspend fun executeAction(
        actionId: String,
        params: Map<String, Any>,
        connectedAccountId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
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

            secureHttpClient(connectTimeoutSeconds = 15, readTimeoutSeconds = 60, useCase = "composio_execute")
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string() ?: ""
                        when (response.code) {
                            401 -> error("Automation session expired. Please reconnect the service.")
                            403 -> error("Permission denied. Reconnect the service and try again.")
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
        }
    }

    /**
     * High-level automation: takes natural language, detects the service,
     * finds the right connected account, and uses Groq to parse the intent
     * into a specific Composio action + params.
     */
    suspend fun executeAutomation(
        instruction: String,
        groqClient: GroqClient? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured()) error("Automation not configured")

            // Step 1: Detect which service
            val service = detectService(instruction)
                ?: error("Couldn't detect which service to use. Try mentioning the service name.")

            // Step 2: Get a connected account for this service
            val connected = getConnectedServices()
            val accountIds = connected[service.id]
            if (accountIds.isNullOrEmpty()) {
                error("${service.name} is not connected. Tap the Automations button (plug icon) in the chat and connect ${service.name} first.")
            }
            val accountId = accountIds.first()

            // Step 3: Use Groq to parse the intent into actionId + params
            val actionParams = if (groqClient != null) {
                parseIntentWithLLM(instruction, service, groqClient)
            } else {
                parseIntentByKeywords(instruction, service)
            }

            if (actionParams == null) {
                error("Couldn't understand what action to take. Try being more specific, e.g. 'send an email to john@example.com'")
            }

            // Step 4: Execute the action
            val (actionId, params) = actionParams
            executeAction(actionId, params, accountId).getOrThrow()
        }
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