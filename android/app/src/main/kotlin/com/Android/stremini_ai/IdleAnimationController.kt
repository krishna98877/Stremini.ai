package com.Android.stremini_ai

import android.os.Handler
import android.os.Looper

class IdleAnimationController(
    private val timeoutMs: Long = 3000L,
    private val onIdle: () -> Unit,
    private val onWake: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var idleRunnable: Runnable? = null
    private var isIdle = false

    fun resetTimer() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        if (isIdle) {
            onWake()
            isIdle = false
        }
        idleRunnable = Runnable {
            onIdle()
            isIdle = true
        }
        handler.postDelayed(idleRunnable!!, timeoutMs)
    }

    fun cancel() {
        idleRunnable?.let { handler.removeCallbacks(it) }
    }
}
