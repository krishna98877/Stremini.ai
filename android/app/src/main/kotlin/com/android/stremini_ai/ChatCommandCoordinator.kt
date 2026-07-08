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
                // Skip LLM confirmation for clear action verbs — go straight to execution.
                // Note: "tweet" was removed when Twitter was dropped from ALL_SERVICES.
                val hasActionVerb = listOf("send", "post", "create", "read", "search",
                    "upload", "message", "email", "comment", "share", "update",
                    "list", "get", "delete", "reply").any {
                    sanitizedMessage.lowercase().contains(it)
                }

                if (!hasActionVerb) {
                    // No action verb — treat as normal chat
                    sendToBackend(sanitizedMessage, historyToSend)
                    return@launch
                }

                // Check if the user has toggled this connector ON in the panel.
                // Connectors default OFF — the user must explicitly opt in.
                // This is the user's "plugins should default OFF" request:
                // even if a service is connected, the chatbot won't touch it
                // until the user flips the toggle.
                val isActive = runCatching {
                    composioClient.isConnectorActive(detectedService.id)
                }.getOrDefault(false)

                if (isActive) {
                    // Connector toggled ON + service connected → route to automation
                    onBotMessage("On it! Using ${detectedService.name}...")
                    composioClient.executeAutomation(
                        instruction = sanitizedMessage,
                        groqClient = backendClient.groq
                    )
                        .onSuccess { reply ->
                            addToHistory("assistant", reply)
                            onBotMessage(reply)
                        }
                        .onFailure { error ->
                            // ── Retry logic: don't show failure immediately ──
                            // First attempt failed. Tell the user we're retrying,
                            // wait a moment, then try once more. Only if the second
                            // attempt also fails do we show the error.
                            val errorMsg = error.message ?: "Unknown error"
                            // Don't retry on permanent errors (auth, permission, not-connected)
                            val isPermanentError = errorMsg.contains("not connected", ignoreCase = true) ||
                                errorMsg.contains("expired", ignoreCase = true) ||
                                errorMsg.contains("Permission denied", ignoreCase = true) ||
                                errorMsg.contains("not configured", ignoreCase = true)
                            if (isPermanentError) {
                                // Don't retry — just tell the user what went wrong
                                addToHistory("assistant", "Automation failed: $errorMsg")
                                onBotMessage("Hmm, that didn't work. $errorMsg Want me to try a different approach?")
                                return@launch
                            }
                            // Retryable error — tell the user we're trying again
                            onBotMessage("Let me try that again...")
                            kotlinx.coroutines.delay(1500)  // brief pause before retry
                            composioClient.executeAutomation(
                                instruction = sanitizedMessage,
                                groqClient = backendClient.groq
                            )
                                .onSuccess { reply ->
                                    addToHistory("assistant", reply)
                                    onBotMessage(reply)
                                }
                                .onFailure { error2 ->
                                    // Second attempt also failed — show a helpful message
                                    val finalError = error2.message ?: "Unknown error"
                                    addToHistory("assistant", "Automation failed after retry: $finalError")
                                    onBotMessage("I tried twice but couldn't complete that. The error was: $finalError. You could try rephrasing, or check that ${detectedService.name} is properly connected in Settings → Manage Connectors.")
                                }
                        }
                } else {
                    // Either: not connected, OR connected but toggled OFF.
                    // Check which one to give the user an accurate hint.
                    val isConnected = runCatching {
                        composioClient.isServiceConnected(detectedService.id)
                    }.getOrDefault(false)
                    val helpMessage = if (isConnected) {
                        "The user wants to use ${detectedService.name} (which is connected) " +
                            "but the connector is toggled OFF in their panel. " +
                            "Tell them: Tap the plug icon next to the mic, find ${detectedService.name}, " +
                            "and flip the toggle ON. Then I can act on it. " +
                            "Their request: $sanitizedMessage"
                    } else {
                        "The user wants to use ${detectedService.name} but hasn't connected it yet. " +
                            "Tell them: Tap the plug icon in the chat bar, find ${detectedService.name}, " +
                            "and tap Connect. They'll log in with their own ${detectedService.name} " +
                            "account — no API key needed. Then toggle it ON. " +
                            "Their request: $sanitizedMessage"
                    }
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
}