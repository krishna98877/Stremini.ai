package com.android.stremini_ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Backend Client — now delegates entirely to GroqClient.
 *
 * Kept as a thin wrapper so that existing code (ChatCommandCoordinator,
 * KeyboardPanels, etc.) doesn't need import changes.
 *
 * The old Cloudflare Worker backend is no longer used for chat.
 */
class AIBackendClient(context: Context) {

    private val groqClient = GroqClient(context)

    /** Expose the underlying GroqClient for direct access if needed */
    val groq: GroqClient get() = groqClient

    /** Initialize with the Groq API key */
    fun setGroqApiKey(key: String) = groqClient.setApiKey(key)

    /** Check if the brain is ready */
    fun isConfigured(): Boolean = groqClient.isConfigured()

    suspend fun sendChatMessage(
        message: String,
        history: List<Map<String, String>> = emptyList()
    ): Result<String> {
        return groqClient.sendMessage(message, history)
    }

    suspend fun sendDeviceCommand(command: String, screenContext: String): Result<String> {
        return groqClient.sendDeviceCommand(command, screenContext)
    }
}