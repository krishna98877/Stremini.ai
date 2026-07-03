package com.android.stremini_ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections

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
    companion object {
        private const val MAX_HISTORY_SIZE = 20
    }

    // Thread-safe history (Bug 6 fix)
    private val sessionHistory: MutableList<Map<String, String>> =
        Collections.synchronizedList(mutableListOf())

    // Unified add + trim (Bug 7 fix)
    private fun addToHistory(role: String, content: String) {
        sessionHistory.add(mapOf("role" to role, "content" to content))
        while (sessionHistory.size > MAX_HISTORY_SIZE) {
            sessionHistory.removeAt(0)
        }
    }

    fun processUserMessage(userMessage: String) {
        scope.launch {
            val sanitizedMessage = sanitizeUserInput(userMessage)
            addToHistory("user", sanitizedMessage)

            val historyToSend = sessionHistory.dropLast(1)

            // Check if this should go to Composio automation
            val detectedService = composioClient.detectService(sanitizedMessage)

            if (detectedService != null) {
                // Bug 10 fix: confirm automation intent via LLM before routing
                val isAutomationIntent = confirmAutomationIntent(sanitizedMessage, detectedService.name)

                if (!isAutomationIntent) {
                    // Keyword was coincidental — treat as normal chat
                    sendToBackend(sanitizedMessage, historyToSend)
                    return@launch
                }

                // Composio is always configured (embedded key).
                // If the service is connected, route to Composio automation.
                // If not connected, suggest connecting it.
                val isConnected = runCatching {
                    composioClient.isServiceConnected(detectedService.id)
                }.getOrDefault(false)

                if (isConnected) {
                    // Service connected → route to Composio
                    onBotMessage("Working on it via ${detectedService.name}...")
                    composioClient.executeAutomation(
                        instruction = sanitizedMessage,
                        groqClient = backendClient.groq
                    )
                        .onSuccess { reply ->
                            addToHistory("assistant", reply)
                            onBotMessage(reply)
                        }
                        .onFailure { error ->
                            // Other Composio errors — fallback to Groq
                            val fallbackMessage = "The user tried to do something with ${detectedService.name} " +
                                "but the automation failed: ${error.message}. " +
                                "Help them with their request as best you can: $sanitizedMessage"
                            sendToBackend(fallbackMessage, historyToSend)
                        }
                } else {
                    // Service detected but not connected by the user yet
                    val helpMessage = "The user wants to use ${detectedService.name} but hasn't connected it yet. " +
                        "Tell them: Tap the plug icon in the chat bar, find ${detectedService.name}, and tap Connect. " +
                        "They'll log in with their own ${detectedService.name} account — no API key needed. " +
                        "Their request: $sanitizedMessage"
                    sendToBackend(helpMessage, historyToSend)
                }
            } else {
                // Normal AI chat via Groq
                sendToBackend(sanitizedMessage, historyToSend)
            }
        }
    }

    private suspend fun sendToBackend(message: String, history: List<Map<String, String>>) {
        backendClient.sendChatMessage(message, history)
            .onSuccess { reply ->
                addToHistory("assistant", reply)
                onBotMessage(reply)
            }
            .onFailure { error ->
                sessionHistory.removeLastOrNull()
                onBotMessage(error.message ?: "Something went wrong. Please try again.")
            }
    }

    /**
     * Bug 10 fix: Ask Groq whether this message is a genuine automation intent
     * or just a casual mention of a service name.
     */
    private suspend fun confirmAutomationIntent(message: String, serviceName: String): Boolean {
        val prompt = """Does this message represent a clear intent to perform an action
using $serviceName (send, post, read, create, search, upload, etc.)?
Message: "$message"
Reply with only YES or NO."""

        return runCatching {
            backendClient.sendChatMessage(prompt, emptyList())
                .getOrDefault("NO")
                .trim()
                .uppercase()
                .startsWith("YES")
        }.getOrDefault(true) // If LLM call fails, allow automation (fail-open)
    }

    fun clearHistory() {
        sessionHistory.clear()
    }
}