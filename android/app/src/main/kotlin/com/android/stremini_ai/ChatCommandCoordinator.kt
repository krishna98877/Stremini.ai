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
                    // No action verb — the user is probably ASKING about the
                    // service (e.g., "gmail", "is it connected", "what can
                    // gmail do"). Inject the live connection status into the
                    // message so the AI can answer definitively instead of
                    // deflecting with "try sending an email to find out."
                    val isConn = runCatching {
                        composioClient.isServiceConnected(detectedService.id)
                    }.getOrDefault(false)
                    val isActive = runCatching {
                        composioClient.isConnectorActive(detectedService.id)
                    }.getOrDefault(false)
                    val statusHint = if (isConn && isActive) {
                        "[System: ${detectedService.name} is CONNECTED and toggled ON. The user can use it right now.]"
                    } else if (isConn && !isActive) {
                        "[System: ${detectedService.name} is CONNECTED but toggled OFF. Tell the user to flip the toggle ON in the connectors panel.]"
                    } else {
                        "[System: ${detectedService.name} is NOT CONNECTED. Tell the user to tap the plug icon and connect it.]"
                    }
                    sendToBackend("$statusHint\nUser: $sanitizedMessage", historyToSend)
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
                    // Show a single "On it!" message, then execute silently.
                    // Only show the result (success or error) — no intermediate messages.
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
                            val errorMsg = error.message ?: "Unknown error"
                            val isPermanentError = errorMsg.contains("not connected", ignoreCase = true) ||
                                errorMsg.contains("expired", ignoreCase = true) ||
                                errorMsg.contains("Permission denied", ignoreCase = true) ||
                                errorMsg.contains("not configured", ignoreCase = true)
                            if (isPermanentError) {
                                // Run diagnostic before showing error
                                val diagnosis = runDiagnostic(detectedService.id, errorMsg)
                                addToHistory("assistant", "[ERROR] $errorMsg")
                                onBotMessage("[ERROR] ${detectedService.name}: $errorMsg\n\n📋 Diagnostic:\n$diagnosis")
                                return@launch
                            }
                            // Retry once silently
                            kotlinx.coroutines.delay(1500)
                            composioClient.executeAutomation(
                                instruction = sanitizedMessage,
                                groqClient = backendClient.groq
                            )
                                .onSuccess { reply ->
                                    addToHistory("assistant", reply)
                                    onBotMessage(reply)
                                }
                                .onFailure { error2 ->
                                    val finalError = error2.message ?: "Unknown error"
                                    // Run full diagnostic on final failure
                                    val diagnosis = runDiagnostic(detectedService.id, finalError)
                                    addToHistory("assistant", "[ERROR] $finalError")
                                    onBotMessage("[ERROR] ${detectedService.name}: $finalError\n\n📋 Diagnostic:\n$diagnosis")
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
        // Pass connected services to Groq so the AI knows what's available
        val connectedServices = runCatching {
            composioClient.getConnectedServices()
        }.getOrDefault(emptyMap())
        backendClient.groq.sendMessage(message, history, connectedServices)
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
     * Complete automation diagnostic — runs when automation fails.
     *
     * Implements the debug_automation workflow:
     * 1. Check connection status for the involved app
     * 2. Check if the app is toggled ON in the connector panel
     * 3. Check if WhatsApp/Instagram IDs are set (if applicable)
     * 4. Check if the Groq AI brain is reachable
     * 5. Generate actionable fix recommendations
     *
     * Returns a human-readable diagnostic string.
     */
    private suspend fun runDiagnostic(serviceId: String, errorMsg: String): String {
        val sb = StringBuilder()
        val serviceName = ComposioClient.ALL_SERVICES.find { it.id == serviceId }?.name ?: serviceId

        sb.append("─── Diagnostic Report ───\n")

        // Step 1: Connection status
        val isConnected = runCatching {
            composioClient.isServiceConnected(serviceId)
        }.getOrDefault(false)
        sb.append("1. Connection: ${if (isConnected) "✅ Connected" else "❌ NOT connected"}\n")
        if (!isConnected) {
            sb.append("   → Fix: Tap the plug icon → find $serviceName → tap Connect\n")
        }

        // Step 2: Toggle status (only for floating chatbot)
        val isActive = runCatching {
            composioClient.isConnectorActive(serviceId)
        }.getOrDefault(false)
        sb.append("2. Toggle: ${if (isActive) "✅ ON" else "❌ OFF (or not connected)"}\n")
        if (isConnected && !isActive) {
            sb.append("   → Fix: Open connectors panel → flip the toggle ON for $serviceName\n")
        }

        // Step 3: WhatsApp/Instagram special IDs
        when (serviceId) {
            "whatsapp" -> {
                val waId = composioClient.getWhatsappPhoneNumberId()
                sb.append("3. WhatsApp Phone ID: ${if (waId.isNotBlank()) "✅ Set" else "❌ NOT set"}\n")
                if (waId.isBlank()) {
                    sb.append("   → Fix: Set your WhatsApp Business phone_number_id in connector settings\n")
                }
            }
            "instagram" -> {
                val igId = composioClient.getInstagramPsid()
                sb.append("3. Instagram PSID: ${if (igId.isNotBlank()) "✅ Set" else "❌ NOT set"}\n")
                if (igId.isBlank()) {
                    sb.append("   → Fix: Set your Instagram Page-Scoped ID in connector settings\n")
                }
            }
            else -> {
                sb.append("3. Special IDs: N/A for $serviceName\n")
            }
        }

        // Step 4: Groq AI brain status
        val groqOk = backendClient.isConfigured()
        sb.append("4. AI Brain (Groq): ${if (groqOk) "✅ Key set" else "❌ No key"}\n")
        if (!groqOk) {
            sb.append("   → Fix: Groq API key is missing. The app needs it for intent parsing.\n")
        }

        // Step 5: Error analysis
        sb.append("5. Error: $errorMsg\n")
        when {
            errorMsg.contains("403", ignoreCase = true) || errorMsg.contains("Forbidden", ignoreCase = true) ->
                sb.append("   → Cause: API access denied. Check account permissions/rate limits.\n")
            errorMsg.contains("401", ignoreCase = true) ->
                sb.append("   → Cause: Session expired. Reconnect $serviceName.\n")
            errorMsg.contains("429", ignoreCase = true) ->
                sb.append("   → Cause: Rate limited. Wait a moment and try again.\n")
            errorMsg.contains("not found", ignoreCase = true) ->
                sb.append("   → Cause: The action doesn't exist for this service.\n")
            errorMsg.contains("timeout", ignoreCase = true) ->
                sb.append("   → Cause: Network timeout. Check your internet connection.\n")
            errorMsg.contains("organization_restricted", ignoreCase = true) ->
                sb.append("   → Cause: Groq account is restricted. Generate a new key at console.groq.com.\n")
            else ->
                sb.append("   → See error details above for specifics.\n")
        }

        sb.append("─── End Diagnostic ───")
        return sb.toString()
    }
}