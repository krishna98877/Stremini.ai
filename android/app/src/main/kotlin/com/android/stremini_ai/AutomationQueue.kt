package com.android.stremini_ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Priority-based async automation queue.
 *
 * 3 priority levels:
 * - HIGH: Customer support (WhatsApp/Gmail replies), security alerts (GitHub/Discord)
 *   Processed immediately, bypasses batch delay
 * - MEDIUM: Content publishing (Instagram/Facebook/LinkedIn posts), data sync (Sheets/Drive)
 *   Processed with small batch delay (500ms) to allow grouping
 * - LOW: Analytics collection (YouTube metrics, insights), backup operations
 *   Batched with 2s delay to minimize API calls
 *
 * High-priority items always jump ahead of medium/low.
 * Thread-safe: ConcurrentLinkedQueue + Mutex.
 */
class AutomationQueue(
    private val composioClient: ComposioClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private const val TAG = "AutoQueue"
        private const val HIGH_BATCH_DELAY_MS = 0L
        private const val MEDIUM_BATCH_DELAY_MS = 500L
        private const val LOW_BATCH_DELAY_MS = 2000L
        private const val MAX_CONCURRENT_HIGH = 3
        private const val MAX_CONCURRENT_MEDIUM = 2
        private const val MAX_CONCURRENT_LOW = 1
    }

    enum class Priority { HIGH, MEDIUM, LOW }

    data class QueuedAction(
        val id: String,
        val actionId: String,
        val params: Map<String, Any>,
        val connectedAccountId: String,
        val serviceId: String,
        val priority: Priority,
        val description: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val highQueue = ConcurrentLinkedQueue<QueuedAction>()
    private val mediumQueue = ConcurrentLinkedQueue<QueuedAction>()
    private val lowQueue = ConcurrentLinkedQueue<QueuedAction>()
    private val processingLock = Mutex()
    private var isProcessing = false

    /** Auto-classify priority based on service + instruction keywords. */
    fun classifyPriority(serviceId: String, actionId: String, instruction: String): Priority {
        val l = instruction.lowercase()
        val isReply = l.contains("reply") || l.contains("respond") || l.contains("answer")
        val isAlert = l.contains("alert") || l.contains("notify") || l.contains("urgent")
        val isSend = l.contains("send") && !l.contains("post")
        val isAnalytics = l.contains("stats") || l.contains("insights") || l.contains("analytics") ||
            l.contains("metrics") || l.contains("followers") || l.contains("performance")
        val isBackup = l.contains("backup") || l.contains("export") || l.contains("archive") ||
            l.contains("save to") || l.contains("download")

        if ((serviceId == "whatsapp" || serviceId == "gmail") && (isSend || isReply)) return Priority.HIGH
        if ((serviceId == "github" || serviceId == "discord") && (isAlert || actionId.contains("ISSUE"))) return Priority.HIGH
        if (isAlert || isReply) return Priority.HIGH
        if (isAnalytics || isBackup) return Priority.LOW
        if (actionId.contains("GET_USER_INFO") || actionId.contains("GET_USER_INSIGHTS") ||
            actionId.contains("GET_CHANNEL_STATISTICS") || actionId.contains("GET_IG_MEDIA")) return Priority.LOW
        return Priority.MEDIUM
    }

    /** Enqueue an action. Returns action ID. */
    fun enqueue(action: QueuedAction): String {
        when (action.priority) {
            Priority.HIGH -> { highQueue.add(action); Log.i(TAG, "HIGH queued: ${action.description} (${highQueue.size})") }
            Priority.MEDIUM -> { mediumQueue.add(action); Log.i(TAG, "MEDIUM queued: ${action.description} (${mediumQueue.size})") }
            Priority.LOW -> { lowQueue.add(action); Log.i(TAG, "LOW queued: ${action.description} (${lowQueue.size})") }
        }
        scope.launch { processNext() }
        return action.id
    }

    /** Process next: HIGH first (immediate), then MEDIUM (500ms batch), then LOW (2s batch). */
    private suspend fun processNext() {
        processingLock.withLock { if (isProcessing) return; isProcessing = true }
        try {
            // HIGH — immediate, up to 3 concurrent
            while (highQueue.isNotEmpty()) {
                val batch = mutableListOf<QueuedAction>()
                while (highQueue.isNotEmpty() && batch.size < MAX_CONCURRENT_HIGH) { highQueue.poll()?.let { batch.add(it) } }
                if (batch.isNotEmpty()) { Log.i(TAG, "Processing ${batch.size} HIGH"); processBatch(batch) }
            }
            // MEDIUM — 500ms batch delay, up to 2 concurrent
            if (mediumQueue.isNotEmpty()) {
                delay(MEDIUM_BATCH_DELAY_MS)
                val batch = mutableListOf<QueuedAction>()
                while (mediumQueue.isNotEmpty() && batch.size < MAX_CONCURRENT_MEDIUM) { mediumQueue.poll()?.let { batch.add(it) } }
                if (batch.isNotEmpty()) { Log.i(TAG, "Processing ${batch.size} MEDIUM"); processBatch(batch) }
            }
            // LOW — 2s batch delay, 1 at a time
            if (lowQueue.isNotEmpty()) {
                delay(LOW_BATCH_DELAY_MS)
                val batch = mutableListOf<QueuedAction>()
                while (lowQueue.isNotEmpty() && batch.size < MAX_CONCURRENT_LOW) { lowQueue.poll()?.let { batch.add(it) } }
                if (batch.isNotEmpty()) { Log.i(TAG, "Processing ${batch.size} LOW"); processBatch(batch) }
            }
        } finally {
            processingLock.withLock { isProcessing = false }
            // Memory cleanup after batch — hint GC to reclaim processed action data
            System.gc()
        }
        if (highQueue.isNotEmpty() || mediumQueue.isNotEmpty() || lowQueue.isNotEmpty()) { scope.launch { processNext() } }
    }

    /** Execute a batch — single item directly, multiple items concurrently. */
    private suspend fun processBatch(batch: List<QueuedAction>) {
        if (batch.size == 1) {
            val a = batch.first()
            try { composioClient.executeAction(a.actionId, a.params, a.connectedAccountId, serviceId = a.serviceId); Log.i(TAG, "✅ ${a.priority}: ${a.description}") }
            catch (e: Exception) { Log.e(TAG, "❌ ${a.priority}: ${a.description} — ${e.message}") }
        } else {
            val deferreds = batch.map { a ->
                scope.async {
                    try { composioClient.executeAction(a.actionId, a.params, a.connectedAccountId, serviceId = a.serviceId); Log.i(TAG, "✅ ${a.priority}: ${a.description}") }
                    catch (e: Exception) { Log.e(TAG, "❌ ${a.priority}: ${a.description} — ${e.message}") }
                }
            }
            deferreds.awaitAll()
        }
    }

    /** Queue status for diagnostics. */
    fun getQueueStatus(): String = "Queue: HIGH=${highQueue.size} MEDIUM=${mediumQueue.size} LOW=${lowQueue.size} processing=$isProcessing"

    /** Clear all queues. */
    fun clearAll() { highQueue.clear(); mediumQueue.clear(); lowQueue.clear(); Log.i(TAG, "All queues cleared") }
}
