package com.android.stremini_ai

import android.content.Context

/**
 * AI Backend Client — delegates to GroqClient.
 * Thin wrapper so existing callers don't need import changes.
 */
class AIBackendClient(context: Context) {

    private val groqClient = GroqClient(context)

    /** Expose the underlying GroqClient for direct access if needed */
    val groq: GroqClient get() = groqClient

    /** Check if the brain is ready */
    fun isConfigured(): Boolean = groqClient.isConfigured()

    suspend fun sendChatMessage(
        message: String,
        history: List<Map<String, String>> = emptyList()
    ): Result<String> {
        return groqClient.sendMessage(message, history)
    }
}