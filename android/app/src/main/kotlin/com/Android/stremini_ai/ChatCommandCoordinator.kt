package com.Android.stremini_ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Routes user chat messages to either:
 * 1. Groq API (general conversation) — the brain
 * 2. Composio automation (when a service keyword is detected & developer key is configured)
 *
 * KEY ARCHITECTURE DIFFERENCE from before:
 * - The Composio developer key is embedded/configured by the app developer
 * - End users NEVER provide any API key
 * - Users just tap "Connect [Service]" in the Automations panel
 * - Once connected, chat messages involving that service are auto-routed to Composio
 *
 * When Composio is used, Groq is also called to parse the natural language
 * into a specific Composio actionId + structured parameters.
 */
class ChatCommandCoordinator(
    private val scope: CoroutineScope,
    private val backendClient: AIBackendClient,
    val composioClient: ComposioClient,
    private val onBotMessage: (String) -> Unit,
) {
    private val sessionHistory = mutableListOf<Map<String, String>>()

    fun processUserMessage(userMessage: String) {
        scope.launch {
            val sanitizedMessage = sanitizeUserInput(userMessage)
            sessionHistory.add(mapOf("role" to "user", "content" to sanitizedMessage))
            if (sessionHistory.size > 20) sessionHistory.removeAt(0)

            val historyToSend = sessionHistory.dropLast(1)

            // Check if this should go to Composio automation
            val detectedService = composioClient.detectService(sanitizedMessage)

            if (composioClient.isConfigured() && detectedService != null) {
                // Developer key is configured and service detected → try Composio
                onBotMessage("Working on it via ${detectedService.name}...")
                composioClient.executeAutomation(
                    instruction = sanitizedMessage,
                    groqClient = backendClient.groq
                )
                    .onSuccess { reply ->
                        sessionHistory.add(mapOf("role" to "assistant", "content" to reply))
                        onBotMessage(reply)
                    }
                    .onFailure { error ->
                        // If the service isn't connected, suggest connecting it
                        val errorMsg = error.message ?: ""
                        if (errorMsg.contains("not connected", ignoreCase = true) ||
                            errorMsg.contains("isn't connected", ignoreCase = true)) {
                            // Service detected but not yet connected by the user
                            val helpMessage = "The user wants to use ${detectedService.name} but hasn't connected it yet. " +
                                "Tell them: Tap the plug icon in the chat bar, find ${detectedService.name}, and tap Connect. " +
                                "They'll log in with their own ${detectedService.name} account — no API key needed. " +
                                "Their request: $sanitizedMessage"
                            sendToBackend(helpMessage, historyToSend)
                        } else {
                            // Other Composio errors — fallback to Groq
                            val fallbackMessage = "The user tried to do something with ${detectedService.name} " +
                                "but the automation failed: $errorMsg. " +
                                "Help them with their request as best you can: $sanitizedMessage"
                            sendToBackend(fallbackMessage, historyToSend)
                        }
                    }
            } else if (detectedService != null && !composioClient.isConfigured()) {
                // Service detected but developer key not configured
                val helpMessage = "The user wants to use ${detectedService.name} automation. " +
                    "Tell them: Automation features are being set up. They can connect ${detectedService.name} " +
                    "by tapping the plug icon in the chat bar. " +
                    "Their request: $sanitizedMessage"
                sendToBackend(helpMessage, historyToSend)
            } else {
                // Normal AI chat via Groq
                sendToBackend(sanitizedMessage, historyToSend)
            }
        }
    }

    private suspend fun sendToBackend(message: String, history: List<Map<String, String>>) {
        backendClient.sendChatMessage(message, history)
            .onSuccess { reply ->
                sessionHistory.add(mapOf("role" to "assistant", "content" to reply))
                onBotMessage(reply)
            }
            .onFailure { error ->
                sessionHistory.removeLastOrNull()
                onBotMessage(error.message ?: "Something went wrong. Please try again.")
            }
    }

    fun clearHistory() {
        sessionHistory.clear()
    }
}