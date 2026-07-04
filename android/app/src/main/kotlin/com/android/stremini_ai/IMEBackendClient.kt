package com.android.stremini_ai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Keyboard AI client — now uses Groq API directly.
 *
 * Handles autocomplete, tone rewriting, grammar correction, and translation
 * by sending targeted prompts to Groq's fast LLM endpoint.
 */
class IMEBackendClient(context: Context) {

    companion object {
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.3-70b-versatile"
        private const val FAST_MODEL = "llama-3.1-8b-instant"  // faster for keyboard actions
    }

    private val prefs = EncryptedPrefs.getEncrypted(context, "groq_prefs")

    private fun getApiKey(): String? = prefs.getString("groq_api_key")

    /**
     * Request a keyboard AI action (autocomplete, tone, or correct).
     */
    fun requestKeyboardAction(
        originalText: String,
        appContext: String,
        actionType: String,
        selectedTone: String,
    ): Result<String> = runCatching {
        val apiKey = getApiKey() ?: error("Groq API key not set.")

        val systemPrompt = when (actionType) {
            "complete" -> "You are a text completion engine. Complete the user's text naturally based on the app context. Return ONLY the completion text, nothing else. No explanations, no quotes."
            "tone" -> "You are a tone rewriter. Rewrite the user's text in the specified tone. Return ONLY the rewritten text, nothing else."
            else -> "You are a grammar correction engine. Fix grammar, spelling, and punctuation. Return ONLY the corrected text, nothing else."
        }

        val userPrompt = when (actionType) {
            "complete" -> "App context: $appContext\n\nComplete this text:\n$originalText"
            "tone" -> "Rewrite in $selectedTone tone:\n$originalText"
            else -> "App context: $appContext\n\nFix this text:\n$originalText"
        }

        val messages = org.json.JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", sanitizeUserInput(userPrompt, maxLength = 4_000)) })
        }

        val requestBody = JSONObject().apply {
            put("model", FAST_MODEL)
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", when (actionType) { "tone" -> 0.8; "complete" -> 0.6; else -> 0.3 })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(GROQ_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        secureHttpClient(5, 10, "groq_keyboard").newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val body = response.body?.string() ?: return@use ""
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val msg = choices.getJSONObject(0).optJSONObject("message")
                if (msg != null) return@use msg.optString("content", "").trim()
            }
            ""
        }
    }.mapFailure()

    /**
     * Translate text using Groq.
     */
    fun translateText(text: String, targetLanguage: String): Result<String> = runCatching {
        val apiKey = getApiKey() ?: error("Groq API key not set.")

        val messages = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a translator. Translate the user's text to the target language. Return ONLY the translation, nothing else.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Translate to $targetLanguage:\n${sanitizeUserInput(text, maxLength = 4_000)}")
            })
        }

        val requestBody = JSONObject().apply {
            put("model", FAST_MODEL)
            put("messages", messages)
            put("max_tokens", 2048)
            put("temperature", 0.3)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(GROQ_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        secureHttpClient(5, 10, "groq_keyboard").newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val body = response.body?.string() ?: return@use ""
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val msg = choices.getJSONObject(0).optJSONObject("message")
                if (msg != null) return@use msg.optString("content", "").trim()
            }
            ""
        }
    }.mapFailure()

    /**
     * Return a hardcoded list of common translation languages.
     * (No longer depends on the backend endpoint.)
     */
    fun fetchTranslationLanguages(): Result<List<Pair<String, String>>> = runCatching {
        listOf(
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "bn" to "Bengali",
            "tr" to "Turkish",
            "nl" to "Dutch",
            "pl" to "Polish",
            "sv" to "Swedish",
            "da" to "Danish",
            "no" to "Norwegian",
            "fi" to "Finnish",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "id" to "Indonesian",
            "ms" to "Malay",
            "tl" to "Filipino",
            "uk" to "Ukrainian",
            "cs" to "Czech",
            "el" to "Greek",
            "he" to "Hebrew",
            "ro" to "Romanian",
            "hu" to "Hungarian",
        )
    }
}

/** Strip backend URLs from errors before they surface anywhere. */
private fun sanitizeErrorMessage(msg: String): String {
    return msg
        .replace(Regex("https?://[^\\s,]+"), "the server")
        .replace(Regex("[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,6}(:\\d+)?(/[^\\s]*)?"), "the server")
        .trim()
}

private fun <T> Result<T>.mapFailure(): Result<T> = recoverCatching { t ->
    throw RuntimeException(sanitizeErrorMessage(t.message ?: "Unknown error"))
}