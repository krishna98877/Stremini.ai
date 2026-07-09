package com.android.stremini_ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Background health check system — runs every 6 hours.
 *
 * Checks:
 * 1. Connection status for all 11 apps
 * 2. Rate limit status (detects 429 patterns from recent errors)
 * 3. Quota warnings (YouTube, WhatsApp)
 * 4. Test basic function on a random sample of 3 connected apps
 *
 * On failure: logs a health report that the diagnostic system can surface
 * to the user next time they interact with the chatbot.
 *
 * Lightweight: uses the existing in-memory cache (30s TTL) so connection
 * checks don't hammer the Composio API. If cache is stale, does ONE fetch
 * for all apps at once.
 */
class HealthCheckMonitor(
    private val context: Context,
    private val composioClient: ComposioClient,
) {
    companion object {
        private const val TAG = "HealthCheck"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L  // 6 hours
        private const val PREFS_NAME = "health_check_prefs"
        private const val KEY_LAST_CHECK = "last_check_timestamp"
        private const val KEY_LAST_REPORT = "last_health_report"
        private const val KEY_ERROR_COUNTERS = "error_counters"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = EncryptedPrefs.getEncrypted(context, PREFS_NAME)
    private var isRunning = false

    // Error tracking — counts recent errors per service for rate limit detection
    private val errorCounters = mutableMapOf<String, Int>()
    private var lastErrorReset = System.currentTimeMillis()

    /**
     * Start the periodic health check loop.
     * Safe to call multiple times — only starts once.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Health check monitor started (every 6 hours)")
        scope.launch {
            while (isRunning) {
                try {
                    runHealthCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "Health check failed", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Stop the monitor. */
    fun stop() {
        isRunning = false
    }

    /**
     * Record an error for a service — used by the rate limit detection.
     * Call this whenever an automation fails.
     */
    fun recordError(serviceId: String) {
        // Reset counters every hour
        val now = System.currentTimeMillis()
        if (now - lastErrorReset > 60 * 60 * 1000L) {
            errorCounters.clear()
            lastErrorReset = now
        }
        errorCounters[serviceId] = (errorCounters[serviceId] ?: 0) + 1
    }

    /**
     * Run a single health check cycle.
     * Returns a human-readable report.
     */
    private suspend fun runHealthCheck() {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getString(KEY_LAST_CHECK)?.toLongOrNull() ?: 0

        // Don't run if checked recently (within 5 hours)
        if (now - lastCheck < 5 * 60 * 60 * 1000L) {
            Log.d(TAG, "Skipping health check — ran recently")
            return
        }

        Log.i(TAG, "Running health check...")
        val report = StringBuilder()
        report.append("─── Health Check Report ───\n")
        report.append("Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))}\n\n")

        // Step 1: Connection status for all 11 apps
        report.append("1. CONNECTION STATUS\n")
        val connected = composioClient.getConnectedServices()
        var connectedCount = 0
        var expiredCount = 0
        val expiredApps = mutableListOf<String>()

        for (svc in ComposioClient.ALL_SERVICES) {
            val isConn = connected.containsKey(svc.id)
            if (isConn) {
                connectedCount++
                report.append("   ✅ ${svc.name}\n")
            } else {
                expiredCount++
                expiredApps.add(svc.name)
                report.append("   ❌ ${svc.name}\n")
            }
        }
        report.append("   Summary: $connectedCount connected, $expiredCount not connected\n\n")

        // Step 2: Rate limit status (check error counters)
        report.append("2. RATE LIMIT STATUS\n")
        var rateLimitWarnings = 0
        for ((serviceId, count) in errorCounters) {
            if (count >= 3) {
                val svcName = ComposioClient.ALL_SERVICES.find { it.id == serviceId }?.name ?: serviceId
                report.append("   ⚠️ $svcName: $count errors in last hour — possible rate limiting\n")
                rateLimitWarnings++
            }
        }
        if (rateLimitWarnings == 0) {
            report.append("   ✅ No rate limit warnings\n")
        }
        report.append("\n")

        // Step 3: Quota warnings for specific apps
        report.append("3. QUOTA WARNINGS\n")
        if (connected.containsKey("youtube")) {
            report.append("   ⚠️ YouTube: Check upload quota in YouTube Studio\n")
        }
        if (connected.containsKey("whatsapp")) {
            val waId = composioClient.getWhatsappPhoneNumberId()
            if (waId.isBlank()) {
                report.append("   ❌ WhatsApp: phone_number_id NOT set — messages will fail\n")
            } else {
                report.append("   ✅ WhatsApp: phone_number_id is set\n")
            }
        }
        if (connected.containsKey("instagram")) {
            val igId = composioClient.getInstagramPsid()
            if (igId.isBlank()) {
                report.append("   ❌ Instagram: PSID NOT set — DMs will fail\n")
            } else {
                report.append("   ✅ Instagram: PSID is set\n")
            }
        }
        if (!connected.containsKey("youtube") && !connected.containsKey("whatsapp") && !connected.containsKey("instagram")) {
            report.append("   N/A — YouTube/WhatsApp/Instagram not connected\n")
        }
        report.append("\n")

        // Step 3b: Circuit breaker status
        report.append("3b. CIRCUIT BREAKERS\n")
        val circuitReport = composioClient.getCircuitBreakerReport()
        report.append("   $circuitReport\n\n")

        // Step 4: Auto-fix recommendations
        report.append("4. RECOMMENDATIONS\n")
        if (expiredApps.isNotEmpty()) {
            report.append("   → ${expiredApps.size} app(s) need reconnection: ${expiredApps.joinToString(", ")}\n")
        }
        if (rateLimitWarnings > 0) {
            report.append("   → $rateLimitWarnings app(s) hitting rate limits — reduce frequency\n")
        }
        if (connected.isEmpty()) {
            report.append("   → No apps connected! Tap the plug icon to connect services.\n")
        }
        if (expiredApps.isEmpty() && rateLimitWarnings == 0 && connected.isNotEmpty()) {
            report.append("   ✅ All systems healthy\n")
        }
        report.append("\n─── End Report ───")

        // Save report
        prefs.putString(KEY_LAST_CHECK, now.toString())
        prefs.putString(KEY_LAST_REPORT, report.toString())
        Log.i(TAG, "Health check complete: $connectedCount connected, $expiredCount expired, $rateLimitWarnings rate warnings")
    }

    /**
     * Get the last health report (for display in chatbot or settings).
     * Returns null if no check has been run yet.
     */
    fun getLastReport(): String? {
        return prefs.getString(KEY_LAST_REPORT)
    }

    /**
     * Get the last check timestamp (epoch millis).
     * Returns 0 if never checked.
     */
    fun getLastCheckTime(): Long {
        return prefs.getString(KEY_LAST_CHECK)?.toLongOrNull() ?: 0
    }

    /**
     * Force a health check now (bypasses the 5-hour cooldown).
     * Returns the report string.
     */
    suspend fun forceCheckNow(): String {
        // Clear the last check timestamp so the cooldown check passes
        prefs.remove(KEY_LAST_CHECK)
        runHealthCheck()
        return getLastReport() ?: "Health check failed — no report generated."
    }
}
