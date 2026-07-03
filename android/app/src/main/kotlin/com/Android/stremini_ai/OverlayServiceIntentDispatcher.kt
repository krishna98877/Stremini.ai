package com.Android.stremini_ai

import android.content.Context
import android.content.Intent
import android.os.Build

class OverlayServiceIntentDispatcher {
    fun dispatch(context: Context, action: String?) {
        val serviceIntent = Intent(context, ChatOverlayService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
