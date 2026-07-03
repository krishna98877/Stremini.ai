package com.Android.stremini_ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class IMEBackendClient(
    private val baseUrl: String = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev"
) {
    private val client = secureHttpClient(
        connectTimeoutSeconds = 5,
        readTimeoutSeconds = 10,
        useCase = "keyboard",
    )

    fun requestKeyboardAction(
        originalText: String,
        appContext: String,
        actionType: String,
        selectedTone: String,
    ): Result<String> = runCatching {
        val json = JSONObject().apply {
            put("text", sanitizeUserInput(originalText, maxLength = 12_000))
            put("appContext", sanitizeUserInput(appContext, maxLength = 1_000))
            if (actionType == "tone") put("tone", sanitizeUserInput(selectedTone, maxLength = 80))
        }

        val endpoint = when (actionType) {
            "complete" -> "complete"
            "tone" -> "tone"
            else -> "correct"
        }

        val request = Request.Builder()
            .url("$baseUrl/keyboard/$endpoint")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return@use ""
            val resultJson = JSONObject(body)
            when (actionType) {
                "complete" -> resultJson.optString("completion")
                "tone" -> resultJson.optString("rewritten")
                    .ifBlank { resultJson.optString("result") }
                    .ifBlank { resultJson.optString("text") }
                    .ifBlank { resultJson.optString("corrected") }
                else -> resultJson.optString("corrected")
            }
        }
    }.mapFailure()

    fun translateText(text: String, targetLanguage: String): Result<String> = runCatching {
        val json = JSONObject().apply {
            put("text", sanitizeUserInput(text, maxLength = 12_000))
            put("targetLanguage", sanitizeUserInput(targetLanguage, maxLength = 80))
        }

        val request = Request.Builder()
            .url("$baseUrl/keyboard/translate")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return@use ""
            val resultJson = JSONObject(body)
            resultJson.optString("translation")
        }
    }.mapFailure()

    fun fetchTranslationLanguages(): Result<List<Pair<String, String>>> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/keyboard/translate/languages")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return@use emptyList()

            val json = JSONObject(body)
            val languagesArray = when {
                json.has("languages") -> json.optJSONArray("languages")
                json.has("data") -> json.optJSONArray("data")
                else -> JSONArray(body)
            } ?: return@use emptyList()

            buildList {
                for (i in 0 until languagesArray.length()) {
                    val item = languagesArray.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val code = item.optString("code").ifBlank { item.optString("languageCode") }
                            val name = item.optString("name").ifBlank { item.optString("language") }
                            if (code.isNotBlank() && name.isNotBlank()) add(code to name)
                        }
                        is String -> if (item.isNotBlank()) add(item to item)
                    }
                }
            }
        }
    }.mapFailure()
}

/** Strip backend URLs from errors before they surface anywhere. */
private fun <T> Result<T>.mapFailure(): Result<T> = recoverCatching { t ->
    throw RuntimeException(sanitizeErrorMessage(t.message ?: "Unknown error"))
}