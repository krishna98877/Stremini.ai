package com.android.stremini_ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct Groq API client — replaces the old Cloudflare Worker backend.
 *
 * Uses the user's Groq API key to call api.groq.com/chat/completions directly.
 * No certificate pinning, no trusted-host whitelist (Groq has valid public certs).
 * Supports:
 * - Multi-turn chat with system prompt (Stremini AI persona)
 * - Composio-aware system prompt that tells the LLM when to suggest automation
 * - Streaming-style response (full response, not SSE — Groq is fast enough)
 */
class GroqClient(context: Context) {

    companion object {
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.3-70b-versatile"

        /** System prompt — Stremini AI persona with Composio awareness */
        private const val SYSTEM_PROMPT = """You are Stremini AI, a powerful AI assistant built into a keyboard app. You help users with anything — writing, coding, research, creative tasks, and more.

You also have automation capabilities through Composio. When a user asks you to do something that involves an external service (like sending an email via Gmail, posting a tweet, creating a GitHub issue, sending a Discord message, etc.), you should help them understand that they can trigger that automation by mentioning the service name.

Available services: GitHub, Gmail, Telegram, Twitter/X, Instagram, Facebook, WhatsApp, Chrome, Google Drive, Discord, LinkedIn, Reddit, Google Sheets.

When a user's request clearly involves one of these services, acknowledge it and let them know the automation will be triggered. If their request is general conversation, just respond normally as a helpful AI assistant.

Keep responses concise and conversational. You're inside a floating chat bubble, so be quick and useful."""
    }

    /** Groq API key stored encrypted on device */
    private val prefs = EncryptedPrefs.getEncrypted(context, "groq_prefs")

    /** Secure HTTP client with rate limiting and trusted-host enforcement */
    // Calls are made via secureHttpClient() per-request to respect useCase routing

    /** Store the Groq API key */
    fun setApiKey(key: String) {
        prefs.putString("groq_api_key", key)
    }

    /** Get the stored Groq API key */
    fun getApiKey(): String? = prefs.getString("groq_api_key")

    /** Check if Groq is configured */
    fun isConfigured(): Boolean = !getApiKey().isNullOrBlank()

    /**
     * Send a chat message to Groq API.
     *
     * @param message The user's message
     * @param history Previous conversation turns (role → content)
     * @return The assistant's reply text, or an error message
     */
    suspend fun sendMessage(
        message: String,
        history: List<Map<String, String>> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = getApiKey() ?: error("Groq API key not set. Please configure in Settings.")

            // Build messages array
            val messages = JSONArray()

            // System prompt
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })

            // Conversation history
            history.forEach { turn ->
                val role = turn["role"] ?: "user"
                val content = sanitizeUserInput(turn["content"] ?: "", maxLength = 4_000)
                // Only include user and assistant roles (skip system duplicates)
                if (role == "user" || role == "assistant") {
                    messages.put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            }

            // Current user message
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", sanitizeUserInput(message, maxLength = 12_000))
            })

            // Build request body
            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 0.7)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            secureHttpClient(15, 45, "groq_chat").newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    when (response.code) {
                        401 -> error("Invalid API key. Please update your Groq key in Settings.")
                        429 -> error("Rate limit reached. Please wait a moment and try again.")
                        500, 502, 503 -> error("Groq service is temporarily unavailable. Please try again.")
                        else -> error("Could not get a response. Please try again. (Error ${response.code})")
                    }
                }

                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)

                // Extract the response text from Groq's response format
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    if (messageObj != null) {
                        return@withContext messageObj.optString("content", "I couldn't generate a response. Please try again.")
                    }
                }

                // Check for error in response body
                if (json.has("error")) {
                    val errorObj = json.getJSONObject("error")
                    error(errorObj.optString("message", "Unknown error from Groq."))
                }

                "I couldn't generate a response. Please try again."
            }
        }.mapGroqFailure()
    }

}

/** Map Groq API errors to user-friendly messages */
private fun <T> Result<T>.mapGroqFailure(): Result<T> {
    return this.recoverCatching { throwable ->
        val rawMessage = throwable.message ?: "Unknown error"
        val safeMessage = rawMessage
            .replace(Regex("https?://[^\\s,]+"), "the server")
            .replace(Regex("[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,6}(:\\d+)?(/[^\\s]*)?"), "the server")
            .trim()
        throw RuntimeException(if (safeMessage.isBlank()) "Something went wrong. Please try again." else safeMessage)
    }
}