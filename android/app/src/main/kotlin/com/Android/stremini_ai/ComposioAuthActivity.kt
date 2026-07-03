package com.Android.stremini_ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * WebView activity for Composio managed OAuth authentication.
 *
 * This activity:
 * 1. Opens the Composio auth URL in a secure WebView
 * 2. The user logs in with THEIR OWN credentials (GitHub, Gmail, etc.)
 * 3. Composio handles the OAuth flow
 * 4. When Composio redirects to stremini://composio, we detect it and:
 *    - Show a success screen
 *    - Set the result so the caller knows the connection succeeded
 *    - Auto-close after a brief delay
 *
 * The user NEVER provides any API key. The developer key is used
 * server-side by Composio to authorize the connection request.
 */
class ComposioAuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ComposioAuth"
        const val EXTRA_AUTH_URL = "composio_auth_url"
        const val EXTRA_SERVICE_NAME = "composio_service_name"
        const val EXTRA_SERVICE_ID = "composio_service_id"
        const val RESULT_CONNECTED = 1001
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var statusIcon: TextView
    private lateinit var closeButton: ImageView
    private lateinit var successOverlay: LinearLayout
    private var serviceName: String = "Service"
    private var serviceId: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive WebView
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Build the UI programmatically (no XML layout needed)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }

        // ── Header bar ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(16, 16, 16, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        closeButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#9CA3AF"))
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(100, 100)
            setOnClickListener { finish() }
        }

        val headerTitle = TextView(this).apply {
            text = "Connecting $serviceName"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            }
        }

        header.addView(closeButton)
        header.addView(headerTitle)
        root.addView(header)

        // ── Progress bar ──
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8
            )
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        root.addView(progressBar)

        // ── WebView ──
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(false)
            }
        }
        root.addView(webView)

        // ── Success overlay (hidden initially) ──
        successOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setPadding(48, 48, 48, 48)
        }

        statusIcon = TextView(this).apply {
            text = "\u2713"  // checkmark
            setTextColor(Color.parseColor("#25D366"))
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusText = TextView(this).apply {
            text = "$serviceName connected!"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }

        val subText = TextView(this).apply {
            text = "You can now use $serviceName automations in chat."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        successOverlay.addView(statusIcon)
        successOverlay.addView(statusText)
        successOverlay.addView(subText)
        root.addView(successOverlay)

        setContentView(root)

        // Get intent extras
        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME) ?: "Service"
        serviceId = intent.getStringExtra(EXTRA_SERVICE_ID) ?: ""
        headerTitle.text = "Connecting $serviceName"

        if (authUrl.isNullOrBlank()) {
            showError("No authentication URL provided.")
            return
        }

        // Set up WebView client to detect redirect
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlRedirect(url)
            }

            // For older Android compatibility
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlRedirect(url ?: "")
            }
        }

        // Load the auth URL
        webView.loadUrl(authUrl)
    }

    /**
     * Detect when Composio redirects back to our deep-link.
     *
     * Expected redirect formats:
     * - stremini://composio?provider=github&status=success
     * - stremini://composio?code=xxx (legacy)
     * - Any URL starting with stremini://composio
     *
     * Also detect the Composio success page (as a fallback):
     * - connect.composio.dev/*success*
     */
    private fun handleUrlRedirect(url: String): Boolean {
        val uri = Uri.parse(url)

        // Check for our deep-link redirect
        if (uri.scheme == "stremini" && uri.host == "composio") {
            val status = uri.getQueryParameter("status")
            val provider = uri.getQueryParameter("provider") ?: serviceId

            if (status == "success" || status == "connected") {
                showSuccess()
            } else if (uri.getQueryParameter("code") != null) {
                // Legacy auth code flow — still a success
                showSuccess()
            } else if (status == "error" || status == "failed") {
                val error = uri.getQueryParameter("error") ?: "Connection failed"
                showError(error)
            } else {
                // Unknown status but still our redirect — assume success
                showSuccess()
            }
            return true  // Don't load the deep-link URL in WebView
        }

        // Fallback: detect Composio's own success callback page
        if (url.contains("composio.dev") &&
            (url.contains("success", ignoreCase = true) || url.contains("callback", ignoreCase = true))) {
            // Wait a moment then check if connection succeeded
            webView.postDelayed({
                runCatching {
                    val client = ComposioClient(this@ComposioAuthActivity)
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val connected = client.isServiceConnected(serviceId)
                        if (connected) {
                            showSuccess()
                        }
                        // If not connected yet, the redirect might still be processing
                        // The user can close manually
                    }
                }
            }, 2000)
            return false  // Let the page load to show the success state
        }

        return false  // Let all other URLs load normally in the WebView
    }

    private fun showSuccess() {
        Log.i(TAG, "$serviceName connected successfully")
        runOnUiThread {
            webView.visibility = View.GONE
            progressBar.visibility = View.GONE
            statusIcon.text = "\u2713"
            statusIcon.setTextColor(Color.parseColor("#25D366"))
            statusText.text = "$serviceName connected!"
            successOverlay.visibility = View.VISIBLE

            // Set result for the caller
            setResult(RESULT_CONNECTED, Intent().apply {
                putExtra("serviceId", serviceId)
                putExtra("serviceName", serviceName)
            })

            // Auto-close after 1.5 seconds
            successOverlay.postDelayed({ finish() }, 1500)
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, "Connection error: $message")
        runOnUiThread {
            webView.visibility = View.GONE
            progressBar.visibility = View.GONE
            statusIcon.text = "\u2717"
            statusIcon.setTextColor(Color.parseColor("#EF4444"))
            statusText.text = "Connection failed"
            successOverlay.visibility = View.VISIBLE

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            successOverlay.postDelayed({ finish() }, 3000)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}