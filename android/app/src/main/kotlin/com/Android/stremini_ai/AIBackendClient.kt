package com.Android.stremini_ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AIBackendClient(
    private val baseUrl: String = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev"
) {
    private val client = secureHttpClient(
        connectTimeoutSeconds = 15,
        readTimeoutSeconds = 30,
        useCase = "chat",
    )

    suspend fun sendChatMessage(
        message: String,
        history: List<Map<String, String>> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val historyArray = JSONArray()
            history.forEach { turn ->
                historyArray.put(JSONObject().apply {
                    put("role", turn["role"] ?: "user")
                    put("content", sanitizeUserInput(turn["content"] ?: "", maxLength = 4_000))
                })
            }

            // FIX: "message" field was missing — only history was being sent,
            // causing the backend to receive no user input and return an empty response.
            val requestBody = JSONObject().apply {
                put("message", sanitizeUserInput(message, maxLength = 12_000))
                put("history", historyArray)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/chat/message")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Unable to get response. Please check your connection.")
                val json = JSONObject(response.body?.string() ?: "{}")
                json.optString("reply", json.optString("response", json.optString("message", "No response")))
            }
        }.mapFailureMessage()
    }

    suspend fun sendDeviceCommand(command: String, screenContext: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestJson = JSONObject().apply {
                put("message", protectForAi(command, source = "device command"))
                put("screen_context", protectForAi(screenContext, source = "screen context"))
                put("mode", "device_control")
            }
            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/chat/message")
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Could not process command. Please check your connection.")
                val json = JSONObject(response.body?.string() ?: "{}")
                json.optString("reply", json.optString("response", json.optString("message", "Command processed")))
            }
        }.mapFailureMessage()
    }
}

/**
 * Strips any backend URL or internal host information from error messages
 * before they are shown to the user.
 */
private fun <T> Result<T>.mapFailureMessage(): Result<T> {
    return this.recoverCatching { throwable ->
        val rawMessage = throwable.message ?: "Unknown error"
        val safeMessage = sanitizeErrorMessage(rawMessage)
        throw RuntimeException(safeMessage)
    }
}

/**
 * Replaces any URL, host, or IP address patterns in the error message
 * with a generic user-friendly string.
 */
internal fun sanitizeErrorMessage(raw: String): String {
    val noUrl = raw.replace(Regex("https?://[^\\s,]+"), "the server")
    val noHost = noUrl.replace(Regex("[a-zA-Z0-9._-]+\\.workers\\.dev[^\\s,]*"), "the server")
    val noDomain = noHost.replace(Regex("[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,6}(:[0-9]+)?(/[^\\s]*)?"), "the server")
    val cleaned = noDomain
        .replace("java.net.UnknownHostException:", "Network error:")
        .replace("java.net.SocketTimeoutException:", "Connection timed out:")
        .replace("java.net.ConnectException:", "Connection failed:")
        .replace("javax.net.ssl.SSLException:", "Secure connection failed:")
        .replace("okhttp3.", "")
        .replace("com.Android.stremini_ai.", "")
        .trim()

    return when {
        cleaned.contains("Unable to resolve host", ignoreCase = true) ||
        cleaned.contains("UnknownHost", ignoreCase = true) ->
            "No internet connection. Please check your network and try again."
        cleaned.contains("timed out", ignoreCase = true) ||
        cleaned.contains("timeout", ignoreCase = true) ->
            "Connection timed out. Please try again."
        cleaned.contains("Connection refused", ignoreCase = true) ||
        cleaned.contains("Connection failed", ignoreCase = true) ->
            "Could not connect to the server. Please check your connection."
        cleaned.contains("SSL", ignoreCase = true) ->
            "Secure connection failed. Please check your network."
        cleaned.isBlank() ->
            "Something went wrong. Please try again."
        else -> cleaned
    }
}
