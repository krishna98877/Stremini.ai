package com.Android.stremini_ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ChatCommandCoordinator(
    private val scope: CoroutineScope,
    private val backendClient: AIBackendClient,
    private val onBotMessage: (String) -> Unit,
) {
    private val sessionHistory = mutableListOf<Map<String, String>>()

    fun processUserMessage(userMessage: String) {
        scope.launch {
            val sanitizedMessage = sanitizeUserInput(userMessage)
            sessionHistory.add(mapOf("role" to "user", "content" to sanitizedMessage))
            if (sessionHistory.size > 20) sessionHistory.removeAt(0)

            val historyToSend = sessionHistory.dropLast(1)

            backendClient.sendChatMessage(sanitizedMessage, historyToSend)
                .onSuccess { reply ->
                    sessionHistory.add(mapOf("role" to "assistant", "content" to reply))
                    onBotMessage(reply)
                }
                .onFailure { error ->
                    sessionHistory.removeLastOrNull()
                    onBotMessage(error.message ?: "Something went wrong. Please try again.")
                }
        }
    }

    fun clearHistory() {
        sessionHistory.clear()
    }
}
