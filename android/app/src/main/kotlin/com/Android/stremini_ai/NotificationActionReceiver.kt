package com.Android.stremini_ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Static BroadcastReceiver for notification action buttons.
 * Forwards intents to ChatOverlayService so controls work
 * from the background without opening the app.
 *
 * Security: only whitelisted actions are accepted; everything else is silently dropped.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"
        private val ALLOWED_ACTIONS = setOf(
            ChatOverlayService.ACTION_TOGGLE_BUBBLE,
            ChatOverlayService.ACTION_STOP_SERVICE,
        )
    }

    private val dispatcher = OverlayServiceIntentDispatcher()

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == null || action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Rejected unrecognised or null action: $action")
            return
        }
        dispatcher.dispatch(context, action)
    }
}