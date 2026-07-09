package com.android.stremini_ai

import com.android.stremini_ai.ServiceDef

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ChatOverlayService : Service(), View.OnTouchListener {

    companion object {
        const val ACTION_SEND_MESSAGE  = "com.android.stremini_ai.SEND_MESSAGE"
        const val EXTRA_MESSAGE        = "message"
        const val ACTION_TOGGLE_BUBBLE = "com.android.stremini_ai.TOGGLE_BUBBLE"
        const val ACTION_STOP_SERVICE  = "com.android.stremini_ai.STOP_SERVICE"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID      = "chat_head_service"
        val CYAN       = Color.parseColor("#00F6FF")
        val WHITE      = Color.parseColor("#FFFFFF")
        val DARK       = Color.parseColor("#111111")
        val DARK_CARD  = Color.parseColor("#1A1A1A")
        val BORDER     = Color.parseColor("#333333")
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView:   View
    private lateinit var params:        WindowManager.LayoutParams
    private var floatingChatView:   View? = null
    private var connectedAppsPanel: View? = null
    private var isConnectedAppsPanelVisible = false
    private val activeConnectors = mutableMapOf<String, Boolean>()
    private var floatingChatParams: WindowManager.LayoutParams? = null
    private var isChatbotVisible    = false

    private lateinit var bubbleIcon: ImageView
    private lateinit var menuItems:  List<ImageView>
    private var isMenuExpanded = false
    private val activeFeatures  = mutableSetOf<Int>()
    private var isBubbleVisible = true

    // Touch / drag state
    private var initialX      = 0
    private var initialY      = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging    = false
    private var hasMoved      = false

    // Sizing (matches HTML reference: 40dp menu items, 55dp bubble)
    private val menuItemSizeDp = 40f
    private val bubbleSizeDp   = 55f
    private val radiusDp       = 70f

    private var bubbleScreenX = 0
    private var bubbleScreenY = 0

    private var isMenuAnimating       = false
    private var isWindowResizing      = false
    private var preventPositionUpdates= false

    private var idleRunnable: Runnable? = null
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isBubbleIdle     = false
    private val IDLE_TIMEOUT_MS  = 3000L
    private val IDLE_ALPHA       = 0.5f    // just dim when idle
    private val IDLE_ANIM_DURATION = 400L

    private val touchSlop by lazy {
        android.view.ViewConfiguration.get(this).scaledTouchSlop
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var aiBackendClient: AIBackendClient
    private lateinit var chatCommandCoordinator: ChatCommandCoordinator
    private lateinit var healthCheckMonitor: HealthCheckMonitor
    private lateinit var bubbleController:        BubbleController
    private lateinit var floatingChatController: FloatingChatController
    private lateinit var idleAnimationController: IdleAnimationController

    // Voice input
    private var chatSpeechRecognizer: SpeechRecognizer? = null
    private var isChatListening = false

    // Connectors panel
    private var connectorsView: View? = null
    private var isConnectorsVisible = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action != ACTION_SEND_MESSAGE) return
            val message = intent.getStringExtra(EXTRA_MESSAGE)
            if (!message.isNullOrBlank()) {
                val sanitized = sanitizeUserInput(message, maxLength = 4_000)
                if (sanitized.isNotBlank()) addMessageToChatbot(sanitized, isUser = false)
            }
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager     = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Initialize Groq brain with API key
        aiBackendClient = AIBackendClient(this)
        // Note: Groq API key must be set by the user in the Flutter Settings screen.
        // The key is stored in encrypted prefs and shared across the overlay service and IME.

        // Create notification channel BEFORE startForeground (required on Android 12+)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "Stremini AI Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating AI bubble is active"
            setShowBadge(false)
        })

        startForeground(NOTIFICATION_ID, buildNotification())

        bubbleController        = BubbleController(::hideBubble, ::showBubble).apply { setVisible(isBubbleVisible) }
        floatingChatController  = FloatingChatController(
            isCurrentlyVisible = { isChatbotVisible },
            onShow = ::showFloatingChatbot,
            onHide = ::hideFloatingChatbot
        )
        idleAnimationController = IdleAnimationController(
            onIdle = { if (!isMenuExpanded && !isDragging && !isMenuAnimating) shrinkBubble() },
            onWake = { restoreBubble() }
        )
        chatCommandCoordinator  = ChatCommandCoordinator(
            scope         = serviceScope,
            backendClient = aiBackendClient,
            composioClient = ComposioClient(this, serviceScope),
            onBotMessage  = { message -> addMessageToChatbot(message, isUser = false) }
        )
        composioClient = chatCommandCoordinator.composioClient

        // Start background health check monitor (runs every 6 hours)
        healthCheckMonitor = HealthCheckMonitor(this, composioClient)
        healthCheckMonitor.start()

        setupOverlay()

        val filter = IntentFilter().apply {
            addAction(ACTION_SEND_MESSAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_BUBBLE -> bubbleController.toggle()
            ACTION_STOP_SERVICE  -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            "com.android.stremini_ai.REFRESH_COMPOSIO" -> {
                // Refresh connected services cache when deep-link callback arrives
                if (isConnectorsVisible) {
                    serviceScope.launch { refreshServiceConnectionStates() }
                }
            }
        }
        return START_STICKY
    }

    // ── Bubble hide/show ─────────────────────────────────────────
    private fun hideBubble() {
        if (!isBubbleVisible) return
        if (isMenuExpanded) collapseMenu()
        idleAnimationController.cancel()
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        overlayView.visibility = View.GONE
        isBubbleVisible = false
        bubbleController.setVisible(false)
        updateNotification()
    }

    private fun showBubble() {
        if (isBubbleVisible) return
        overlayView.visibility = View.VISIBLE
        bubbleIcon.scaleX = 1f; bubbleIcon.scaleY = 1f; bubbleIcon.alpha = 1f
        isBubbleIdle    = false
        isBubbleVisible = true
        bubbleController.setVisible(true)
        updateNotification()
        resetIdleTimer()
    }

    // ── Overlay setup ─────────────────────────────────────────
    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.chat_bubble_layout, null)

        bubbleIcon = overlayView.findViewById(R.id.bubble_icon)
        menuItems   = listOf(
            overlayView.findViewById(R.id.btn_settings),
            overlayView.findViewById(R.id.btn_brain),
            overlayView.findViewById(R.id.btn_keyboard),
        )

        val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val radiusPx             = dpToPx(radiusDp).toFloat()
        val bubbleSizePx         = dpToPx(bubbleSizeDp).toFloat()
        val collapsedWindowSizePx = (bubbleSizePx + dpToPx(16f)).toInt()

        params = WindowManager.LayoutParams(
            collapsedWindowSizePx, collapsedWindowSizePx, typeParam,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val screenHeight = resources.displayMetrics.heightPixels
        bubbleScreenX = 60
        bubbleScreenY = (screenHeight * 0.25).toInt()
        val windowHalfSize = collapsedWindowSizePx / 2
        params.x = bubbleScreenX - windowHalfSize
        params.y = bubbleScreenY - windowHalfSize

        bubbleIcon.setOnTouchListener(this)
        bubbleIcon.setOnLongClickListener { openKeyboardSwitcher(); true }

        menuItems.forEach {
            it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            it.isClickable = true; it.isFocusable = true
            it.visibility = View.INVISIBLE
        }

        // ── Wire menu item click handlers
        // 0: Settings → opens Composio automation / connectors panel
        menuItems[0].setOnClickListener { handleAutomation() }
        // 1: Brain → toggles floating chatbot
        menuItems[1].setOnClickListener { handleAIChat() }
        // 2: Keyboard → opens system keyboard switcher
        menuItems[2].setOnClickListener { openKeyboardSwitcher() }

        updateMenuItemsColor()
        overlayView.background = null
        overlayView.isClickable = false; overlayView.isFocusable = false
        overlayView.setOnTouchListener { _, _ -> false }
        windowManager.addView(overlayView, params)

        (overlayView as? android.view.ViewGroup)?.apply {
            clipToPadding = false; clipChildren = false
            isMotionEventSplittingEnabled = false
        }
        overlayView.layoutParams = overlayView.layoutParams?.apply {
            width = params.width; height = params.height
        }
        overlayView.requestLayout()
        resetIdleTimer()
    }

    // ── Idle animation ────────────────────────────────────────
    private fun resetIdleTimer() { idleAnimationController.resetTimer() }

    /**
     * Dim-only idle animation. We previously MOVED the bubble position toward
     * the nearest edge during idle, which caused severe jitter on tap because
     * the moment a finger touched the screen, restoreBubble() + the user's
     * tap movement competed for the bubble position. The user explicitly
     * reported "the bubble on click glitch jitter a lot" — root cause was here.
     *
     * Fix: keep the bubble at its exact position; only dim the alpha. No
     * position animator runs anymore, so taps land cleanly.
     */
    private fun shrinkBubble() {
        if (isBubbleIdle) return
        isBubbleIdle = true
        // Alpha-only — NO position change, NO scale change.
        bubbleIcon.animate().alpha(IDLE_ALPHA)
            .setDuration(IDLE_ANIM_DURATION).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun restoreBubble() {
        if (!isBubbleIdle) return
        isBubbleIdle = false
        bubbleIcon.animate().alpha(1f)
            .setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── Menu ────────────────────────────────────────────────────────────

    private fun toggleMenu() { if (isMenuAnimating) return; if (isMenuExpanded) collapseMenu() else expandMenu() }

    /**
     * Detect which side of the screen the bubble is on so the menu opens
     * AWAY from the nearest edge (prevents menu items from being cut off).
     * Returns true if the menu should open to the LEFT (bubble is on right
     * half of screen), false if it should open to the RIGHT (bubble is on
     * left half).
     */
    private fun shouldMenuOpenLeft(): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        return bubbleScreenX > (screenWidth / 2)
    }

    private fun expandMenu() {
        if (isMenuAnimating || isMenuExpanded) return
        isMenuExpanded = true; isMenuAnimating = true

        val radiusPx              = dpToPx(radiusDp).toFloat()
        val bubbleSizePx          = dpToPx(bubbleSizeDp).toFloat()
        val menuItemSizePx        = dpToPx(menuItemSizeDp).toFloat()
        val expandedWindowSizePx  = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)

        // Set the final window size IMMEDIATELY (no animation) to eliminate
        // the glitch/jitter from per-frame windowManager.updateViewLayout calls.
        // The bubble icon stays centered via layout_gravity="center", so it
        // doesn't visually move — only the touch area grows.
        preventPositionUpdates = true
        val expandedHalf = expandedWindowSizePx / 2f
        params.width  = expandedWindowSizePx.toInt()
        params.height = expandedWindowSizePx.toInt()
        params.x = (bubbleScreenX - expandedHalf).toInt()
        params.y = (bubbleScreenY - expandedHalf).toInt()
        try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
        preventPositionUpdates = false

        val centerX    = expandedWindowSizePx / 2f; val centerY = expandedWindowSizePx / 2f
        val n = menuItems.size

        // Determine semicircle direction based on bubble position.
        // Default (bubble on LEFT half): angles -90° to +90° → menu opens to the RIGHT.
        // Flipped (bubble on RIGHT half): angles 90° to 270° → menu opens to the LEFT.
        val openLeft = shouldMenuOpenLeft()
        val startDeg = if (openLeft) 90.0 else -90.0
        val endDeg   = if (openLeft) 270.0 else 90.0

        // Animate menu items outward from the bubble center.
        for ((index, view) in menuItems.withIndex()) {
            view.visibility = View.VISIBLE; view.alpha = 0f
            view.translationX = 0f; view.translationY = 0f
            view.scaleX = 0.5f; view.scaleY = 0.5f
            val stepDeg = if (n > 1) (endDeg - startDeg) / (n - 1) else 0.0
            val angleDeg = startDeg + index * stepDeg
            val angle = Math.toRadians(angleDeg)
            val targetX = centerX + (radiusPx * cos(angle)).toFloat() - (menuItemSizePx / 2)
            val targetY = centerY + (radiusPx * -sin(angle)).toFloat() - (menuItemSizePx / 2)
            val initialCenteredX = centerX - (menuItemSizePx / 2)
            val initialCenteredY = centerY - (menuItemSizePx / 2)
            view.animate()
                .translationX(targetX - initialCenteredX).translationY(targetY - initialCenteredY)
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }
        updateMenuItemsColor()

        // Mark animation complete after the item animation finishes
        overlayView.postDelayed({ isMenuAnimating = false }, 220)
    }

    private fun collapseMenu() {
        if (isMenuAnimating || !isMenuExpanded) return
        isMenuExpanded = false; isMenuAnimating = true
        val bubbleSizePx          = dpToPx(bubbleSizeDp).toFloat()
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)

        // Animate menu items back to center + fade out
        for (view in menuItems) {
            view.animate().translationX(0f).translationY(0f).alpha(0f)
                .scaleX(0.5f).scaleY(0.5f)
                .setDuration(150).setInterpolator(AccelerateInterpolator())
                .withEndAction { view.visibility = View.INVISIBLE }.start()
        }

        // Shrink the window back to bubble-only size after items start fading
        overlayView.postDelayed({
            preventPositionUpdates = true
            val collapsedHalf = collapsedWindowSizePx / 2f
            params.width  = collapsedWindowSizePx.toInt()
            params.height = collapsedWindowSizePx.toInt()
            params.x = (bubbleScreenX - collapsedHalf).toInt()
            params.y = (bubbleScreenY - collapsedHalf).toInt()
            try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            preventPositionUpdates = false
            isMenuAnimating = false
            resetIdleTimer()
        }, 120)
    }

    private var snapAttempts = 0
    private fun snapToEdge() {
        if (isWindowResizing || preventPositionUpdates || isMenuAnimating) {
            snapAttempts++
            if (snapAttempts < 10) overlayView.postDelayed({ snapToEdge() }, 150)
            else snapAttempts = 0
            return
        }
        snapAttempts = 0
        val bubbleSizePx          = dpToPx(bubbleSizeDp).toFloat()
        val screenWidth           = resources.displayMetrics.widthPixels
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)
        val windowHalfSize        = collapsedWindowSizePx / 2
        val targetBubbleScreenX = if (bubbleScreenX > (screenWidth / 2))
            screenWidth - (bubbleSizePx / 2).toInt() else (bubbleSizePx / 2).toInt()
        ValueAnimator.ofInt(bubbleScreenX, targetBubbleScreenX).apply {
            duration = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                bubbleScreenX = animator.animatedValue as Int
                params.x = (bubbleScreenX - windowHalfSize).toInt()
                try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    // ── Chatbot ───────────────────────────────────────────────────────────

    private fun handleAIChat() {
        toggleFeature(menuItems[1].id)
        if (isFeatureActive(menuItems[1].id)) {
            floatingChatController.show()
        } else {
            floatingChatController.hide()
        }
        collapseMenu()
    }

    // ── Composio client for automation ─────────────────────────────────
    private lateinit var composioClient: ComposioClient
    private val serviceConnectionState = mutableMapOf<String, Boolean>()

    private fun handleAutomation() {
        collapseMenu()
        if (isConnectorsVisible) { hideConnectorsPanel(); return }
        showConnectorsPanel()
    }

    private fun showConnectorsPanel() {
        if (isConnectorsVisible) { hideConnectorsPanel(); return }
        isConnectorsVisible = true

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE must be cleared so the search EditText can accept input.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        // Manus-style bottom sheet — full width minus gutters, slides up.
        lp.gravity = Gravity.BOTTOM
        lp.x = 0
        lp.y = 0
        val density = resources.displayMetrics.density
        lp.horizontalMargin = (10 * density).toFloat() / resources.displayMetrics.widthPixels

        connectorsView = LayoutInflater.from(this).inflate(R.layout.connectors_panel_layout, null)

        val screenHeight = resources.displayMetrics.heightPixels
        val maxPanelHeight = (screenHeight * 0.78f).toInt()  // never exceed 78% of screen

        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        // Wire up search filter
        val searchInput = connectorsView?.findViewById<EditText>(R.id.composio_search_input)
        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                filterServicesList(query)
            }
        })

        // Wire close button
        connectorsView?.findViewById<ImageView>(R.id.btn_close_connectors)?.setOnClickListener {
            hideConnectorsPanel()
        }

        // Wire Composio connect banner (open main app for initial login)
        connectorsView?.findViewById<LinearLayout>(R.id.composio_connect_banner)?.setOnClickListener {
            hideConnectorsPanel()
            openMainApp()
        }

        // Build the 13 service rows
        buildServicesList()

        // Update Composio status label
        updateComposioStatusText()

        // Check which services are connected (async)
        serviceScope.launch {
            refreshServiceConnectionStates()
        }

        // Slide-up + fade-in (Manus-style bottom sheet transition)
        connectorsView?.alpha = 0f
        connectorsView?.translationY = dpToPx(60f).toFloat()
        try { wm.addView(connectorsView, lp) } catch (_: Exception) {}
        connectorsView?.animate()
            ?.alpha(1f)
            ?.translationY(0f)
            ?.setDuration(300)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()

        updateConnectorsToggleIcon()
    }

    /** Build all 15 service items in a 3-column grid. */
    private fun buildServicesList() {
        filterServicesList("")
    }

    /**
     * Build the services grid, filtered by [query] (case-insensitive).
     * Matches against the service name and its keyword list.
     */
    private fun filterServicesList(query: String) {
        val grid = connectorsView?.findViewById<LinearLayout>(R.id.services_grid) ?: return
        grid.removeAllViews()

        val q = query.lowercase()
        val filtered = ComposioClient.ALL_SERVICES.filter { svc ->
            q.isEmpty() ||
                svc.name.lowercase().contains(q) ||
                svc.keywords.any { it.contains(q) }
        }

        val columnsPerRow = 1
        var currentRow: LinearLayout? = null
        var itemsInRow = 0

        for ((index, svc) in filtered.withIndex()) {
            // Start a new row every [columnsPerRow] items
            if (itemsInRow == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(4, 4, 4, 4)
                }
            }

            val cell = buildServiceCell(svc)
            currentRow?.addView(cell)

            itemsInRow++
            if (itemsInRow == columnsPerRow || index == filtered.lastIndex) {
                grid.addView(currentRow)
                itemsInRow = 0
            }
        }

        // If no results, show a friendly empty state
        if (filtered.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No services match \"$query\""
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 32)
            }
            grid.addView(empty)
        }
    }

    /**
     * Build a Manus-style service row for the automation panel:
     *   [icon 36dp] [name + "Not connected"] [Connect/Connected button]
     *
     * Previously this was a vertical card with icon-on-top — switched to a
     * horizontal row so it matches the Manus reference UI the user wants.
     */
    private fun buildServiceCell(svc: ServiceDef): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * density).toInt(), (12 * density).toInt(),
                (14 * density).toInt(), (12 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
            // Tag for async status updates
            tag = svc.id
            // Subtle row background (matches Manus card_bg)
            background = ContextCompat.getDrawable(this@ChatOverlayService, R.drawable.manus_card_bg)
            isClickable = true
            isFocusable = true

            // Service logo (36dp, on the left)
            val icon = ImageView(this@ChatOverlayService).apply {
                setImageResource(svc.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val size = (36 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (14 * density).toInt()
                }
            }
            addView(icon)

            // Text container (name + status, weight=1)
            val textContainer = LinearLayout(this@ChatOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(this@ChatOverlayService).apply {
                text = svc.name
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val statusCaption = TextView(this@ChatOverlayService).apply {
                text = "Tap to connect"
                setTextColor(Color.parseColor("#71717A"))
                textSize = 11f
            }
            textContainer.addView(name)
            textContainer.addView(statusCaption)
            addView(textContainer)

            // Connect / Connected button on the right
            val status = TextView(this@ChatOverlayService).apply {
                text = "Connect"
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                id = View.generateViewId()
                tag = "status_label"
                background = ContextCompat.getDrawable(this@ChatOverlayService, R.drawable.manus_connect_btn)
                val padH = (16 * density).toInt()
                val padV = (6 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addView(status)

            // Click handler — entire row is clickable, AND the button is too.
            setOnClickListener {
                if (status.text == "Connected") {
                    Toast.makeText(this@ChatOverlayService, "${svc.name} is already connected. Disconnect from Settings.", Toast.LENGTH_SHORT).show()
                } else if (status.text != "Connecting..." && status.text != "Disconnecting...") {
                    status.text = "Connecting..."
                    status.setTextColor(Color.parseColor("#9CA3AF"))
                    hideConnectorsPanel()
                    composioClient.connectService(svc.id)
                }
            }
        }
    }

    /** Update the status text in the header */
    private fun updateComposioStatusText() {
        val statusTv = connectorsView?.findViewById<TextView>(R.id.tv_composio_status) ?: return
        val banner = connectorsView?.findViewById<LinearLayout>(R.id.composio_connect_banner) ?: return
        val connectedCount = serviceConnectionState.values.count { it }
        if (connectedCount > 0) {
            statusTv.text = "$connectedCount connected"
            statusTv.setTextColor(CYAN)
            banner.visibility = View.GONE
        } else {
            statusTv.text = "0 connected"
            statusTv.setTextColor(Color.parseColor("#6B7280"))
            banner.visibility = View.VISIBLE
        }
    }

    /** Async: check which services are connected and update UI */
    private suspend fun refreshServiceConnectionStates() {
        if (!composioClient.isConfigured()) return
        try {
            val connected = composioClient.getConnectedServices()
            val grid = connectorsView?.findViewById<LinearLayout>(R.id.services_grid) ?: return

            for (rowIdx in 0 until grid.childCount) {
                val row = grid.getChildAt(rowIdx) as? LinearLayout ?: continue
                for (cellIdx in 0 until row.childCount) {
                    val cell = row.getChildAt(cellIdx)
                    val svcId = cell.tag as? String ?: continue
                    val isConnected = connected.containsKey(svcId)
                    serviceConnectionState[svcId] = isConnected

                    // Update UI on main thread
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val statusLabel = cell.findViewWithTag<TextView>("status_label")
                        if (isConnected) {
                            statusLabel?.text = "Connected"
                            statusLabel?.setTextColor(Color.parseColor("#FFFFFF"))
                            statusLabel?.background = ContextCompat.getDrawable(this@ChatOverlayService, R.drawable.manus_connected_bg)
                        } else {
                            statusLabel?.text = "Connect"
                            statusLabel?.setTextColor(Color.parseColor("#FFFFFF"))
                            statusLabel?.background = ContextCompat.getDrawable(this@ChatOverlayService, R.drawable.manus_connect_btn)
                        }
                    }
                }
            }
            // Update header status
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updateComposioStatusText()
            }
        } catch (e: Exception) {
            Log.e("ChatOverlayService", "refreshServiceConnectionStates failed", e)
        }
    }

    private fun hideConnectorsPanel() {
        if (!isConnectorsVisible) return
        isConnectorsVisible = false
        // Slide-down + fade-out (Manus-style bottom sheet exit transition)
        connectorsView?.animate()
            ?.alpha(0f)
            ?.translationY(dpToPx(60f).toFloat())
            ?.setDuration(220)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction {
                try { windowManager.removeView(connectorsView) } catch (_: Exception) {}
                connectorsView = null
                updateChatConnectorsToggleIcon()
            }
            ?.start()
    }

    private fun updateConnectorsToggleIcon() {
        val btn = connectorsView?.findViewById<FrameLayout>(R.id.btn_connectors_toggle) ?: return
        val icon = btn?.findViewById<ImageView>(R.id.connectors_toggle_icon)
        val dot  = btn?.findViewById<View>(R.id.connectors_active_dot)
        val anyConnected = serviceConnectionState.values.any { it }
        if (anyConnected) {
            icon?.setColorFilter(CYAN)
            dot?.visibility = View.VISIBLE
        } else {
            icon?.setColorFilter(BORDER)
            dot?.visibility = View.GONE
        }
    }

    /** Update toggle icon in the floating chat input bar (independent of connectors panel).
     *  Shows the count of services that are BOTH connected AND actively toggled on. */
    private fun updateChatConnectorsToggleIcon() {
        val chatView = floatingChatView ?: return
        val btn = chatView.findViewById<FrameLayout>(R.id.btn_connectors_toggle) ?: return
        val icon = btn.findViewById<ImageView>(R.id.connectors_toggle_icon)
        val badge = btn.findViewById<TextView>(R.id.connectors_active_dot)
        // Count services that are BOTH connected AND actively enabled by the user.
        val activeCount = activeConnectors.count { (svcId, isActive) ->
            isActive && serviceConnectionState[svcId] == true
        }
        if (activeCount > 0) {
            icon?.setColorFilter(Color.parseColor("#3B82F6"))
            badge?.visibility = View.VISIBLE
            badge?.text = activeCount.toString()
        } else {
            // Dim the icon so the user can see at a glance that no plugins are active.
            icon?.setColorFilter(Color.parseColor("#6B7280"))
            badge?.visibility = View.GONE
        }
    }

    private fun toggleConnectedAppsPanel() {
        if (isConnectedAppsPanelVisible) {
            hideConnectedAppsPanel()
        } else {
            showConnectedAppsPanel()
        }
    }

    private fun showConnectedAppsPanel() {
        if (isConnectedAppsPanelVisible) return
        val chatView = floatingChatView ?: return

        connectedAppsPanel = LayoutInflater.from(this).inflate(R.layout.connected_apps_panel, null)

        val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Wider panel so the row layout (icon | name | toggle) has room to breathe.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT, typeParam,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        // Anchored to the bottom like a bottom sheet — matches Manus transitions.
        lp.gravity = Gravity.BOTTOM
        lp.x = 0
        lp.y = 0
        // Allow the panel to extend full width minus side gutters.
        val density = resources.displayMetrics.density
        lp.horizontalMargin = (12 * density).toFloat() / resources.displayMetrics.widthPixels

        // Header title & close button
        connectedAppsPanel?.findViewById<TextView>(R.id.tv_panel_title)?.text = "Connectors"
        connectedAppsPanel?.findViewById<ImageView>(R.id.btn_close_connected_apps)?.setOnClickListener {
            hideConnectedAppsPanel()
        }

        // Build the list of ALL services (not just connected) so the user can
        // connect + toggle from the same panel — Manus-style.
        val list = connectedAppsPanel?.findViewById<LinearLayout>(R.id.connected_apps_list)
        val emptyText = connectedAppsPanel?.findViewById<TextView>(R.id.tv_no_connected)
        val countText = connectedAppsPanel?.findViewById<TextView>(R.id.tv_connected_count)

        // Load connected services asynchronously, then build rows.
        serviceScope.launch {
            val connected = composioClient.getConnectedServices()
            val allServices = ComposioClient.ALL_SERVICES
            // Sort: connected first, then alphabetically.
            val sorted = allServices.sortedWith(
                compareByDescending<ServiceDef> { connected.containsKey(it.id) }
                    .thenBy { it.name }
            )

            withContext(Dispatchers.Main) {
                list?.removeAllViews()
                if (allServices.isEmpty()) {
                    emptyText?.visibility = View.VISIBLE
                    emptyText?.text = "No connectors available."
                    countText?.text = "0"
                } else {
                    emptyText?.visibility = View.GONE
                    val connectedCount = allServices.count { connected.containsKey(it.id) }
                    countText?.text = connectedCount.toString()

                    for (svc in sorted) {
                        val isConnected = connected.containsKey(svc.id)
                        list?.addView(buildManusStyleConnectorRow(svc, isConnected))
                    }
                }
            }
        }

        // Smooth slide-up + fade-in animation (Manus-style bottom sheet transition)
        connectedAppsPanel?.alpha = 0f
        connectedAppsPanel?.translationY = dpToPx(40f).toFloat()
        try { windowManager.addView(connectedAppsPanel, lp) } catch (_: Exception) {}
        connectedAppsPanel?.animate()
            ?.alpha(1f)
            ?.translationY(0f)
            ?.setDuration(280)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()

        isConnectedAppsPanelVisible = true
        // Refresh the plug icon state
        updateChatConnectorsToggleIcon()
    }

    /**
     * Build a Manus-style connector row:
     *   [icon 32dp] [name + status text] [toggle/Connect button]
     *
     * - If connected: a Switch toggle that defaults to OFF (per user request)
     * - If not connected: a "Connect" button that launches the OAuth flow
     */
    private fun buildManusStyleConnectorRow(svc: ServiceDef, isConnected: Boolean): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * density).toInt(), (12 * density).toInt(),
                (14 * density).toInt(), (12 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        // Icon — brand-colored rounded square with the actual logo drawable
        val icon = ImageView(this).apply {
            setImageResource(svc.iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val size = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (14 * density).toInt()
            }
            // White rounded-square background so the colored logo shows properly
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (10 * density)
                setColor(Color.WHITE)
            }
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
        row.addView(icon)

        // Name + status text (middle, weight=1)
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = TextView(this).apply {
            text = svc.name
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val status = TextView(this).apply {
            text = if (isConnected) "Connected" else "Not connected"
            setTextColor(Color.parseColor("#666666"))  // grey for both — no green
            textSize = 11f
            id = View.generateViewId()
            tag = "row_status"
        }
        textContainer.addView(name)
        textContainer.addView(status)
        row.addView(textContainer)

        if (isConnected) {
            // Toggle switch — lets user enable/disable automation per service
            val toggle = android.widget.Switch(this).apply {
                val isActive = activeConnectors[svc.id] ?: false
                if (!activeConnectors.containsKey(svc.id)) {
                    activeConnectors[svc.id] = isActive
                }
                isChecked = isActive
                trackDrawable = ContextCompat.getDrawable(this@ChatOverlayService, R.drawable.toggle_bg)
                thumbDrawable = ContextCompat.getDrawable(this@ChatOverlayService, R.drawable.toggle_thumb)
                setOnCheckedChangeListener { _, checked ->
                    activeConnectors[svc.id] = checked
                    composioClient.setConnectorActive(svc.id, checked)
                    Toast.makeText(this@ChatOverlayService,
                        if (checked) "${svc.name} enabled" else "${svc.name} disabled",
                        Toast.LENGTH_SHORT).show()
                    updateChatConnectorsToggleIcon()
                }
            }
            row.addView(toggle)
        } else {
            // Connect button — WHITE bg, BLACK text
            val connectBtn = TextView(this).apply {
                text = "Connect"
                setTextColor(Color.BLACK)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = (20 * density)
                    setColor(Color.WHITE)
                }
                val padH = (16 * density).toInt()
                val padV = (7 * density).toInt()
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    text = "..."
                    setTextColor(Color.GRAY)
                    hideConnectedAppsPanel()
                    composioClient.connectService(svc.id)
                }
            }
            row.addView(connectBtn)
        }

        return row
    }

    private fun hideConnectedAppsPanel() {
        if (!isConnectedAppsPanelVisible) return
        isConnectedAppsPanelVisible = false
        // Slide-down + fade-out (Manus-style bottom sheet exit transition)
        connectedAppsPanel?.animate()
            ?.alpha(0f)
            ?.translationY(dpToPx(40f).toFloat())
            ?.setDuration(200)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction {
                try { windowManager.removeView(connectedAppsPanel) } catch (_: Exception) {}
                connectedAppsPanel = null
            }?.start()
    }

    private fun showFloatingChatbot() {
        if (isChatbotVisible) return
        floatingChatView = LayoutInflater.from(this).inflate(R.layout.floating_chatbot_layout, null)

        val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        floatingChatParams = WindowManager.LayoutParams(
            dpToPx(300f), dpToPx(420f), typeParam,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        floatingChatParams?.gravity = Gravity.BOTTOM or Gravity.END
        floatingChatParams?.x = dpToPx(20f)
        floatingChatParams?.y = dpToPx(100f)
        floatingChatView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setupFloatingChatListeners()

        // Smooth fade-in + scale-up animation
        floatingChatView?.alpha = 0f
        floatingChatView?.scaleX = 0.92f
        floatingChatView?.scaleY = 0.92f
        windowManager.addView(floatingChatView, floatingChatParams)
        floatingChatView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(250)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()

        isChatbotVisible = true
        addMessageToChatbot("Hello! I'm Stremini AI. How can I help?", isUser = false)
    }

    private fun setupFloatingChatListeners() {
        val view = floatingChatView ?: return

        val header = view.findViewById<LinearLayout>(R.id.chat_header)
        var chatInitialX = 0; var chatInitialY = 0
        var chatInitialTouchX = 0f; var chatInitialTouchY = 0f
        var chatIsDragging = false

        header?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    chatInitialTouchX = event.rawX; chatInitialTouchY = event.rawY
                    chatInitialX = floatingChatParams?.x ?: 0
                    chatInitialY = floatingChatParams?.y ?: 0
                    chatIsDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val view = floatingChatView ?: return@setOnTouchListener true
                    val lp = floatingChatParams ?: return@setOnTouchListener true
                    if (chatIsDragging) {
                        lp.x = chatInitialX - (event.rawX - chatInitialTouchX).toInt()
                        lp.y = chatInitialY - (event.rawY - chatInitialTouchY).toInt()
                        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { chatIsDragging = false }
            }
            true
        }

        // ── Close
        view.findViewById<ImageView>(R.id.btn_close_chat)?.setOnClickListener {
            stopChatVoiceInput()
            floatingChatController.hide()
            toggleFeature(menuItems[1].id) // btn_brain
        }

        // ── Send text
        view.findViewById<ImageView>(R.id.btn_send_message)?.setOnClickListener {
            val input   = view.findViewById<EditText>(R.id.et_chat_input)
            val message = input?.text?.toString()?.trim()
            if (!message.isNullOrEmpty()) {
                addMessageToChatbot(message, isUser = true)
                input.text?.clear()
                processUserCommand(message)
            }
        }

        // ── Connectors toggle (wire icon like ChatGPT plugin button).
        // Visual feedback: tap scales the icon briefly so the user can see
        // the press is registered (was previously silent/broken-feeling).
        val connectorsBtn = view.findViewById<FrameLayout>(R.id.btn_connectors_toggle)
        connectorsBtn?.setOnClickListener {
            // Brief press feedback
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
            toggleConnectedAppsPanel()
        }

        // ── Voice input
        view.findViewById<ImageView>(R.id.btn_voice_input)?.setOnClickListener {
            if (isChatListening) { stopChatVoiceInput() } else { startChatVoiceInput() }
        }

        // ── Cancel voice
        view.findViewById<TextView>(R.id.btn_cancel_voice)?.setOnClickListener { stopChatVoiceInput() }
    }

    // ── Chat voice input ─────────────────────────────────
    private fun startChatVoiceInput() {
        if (isChatListening) return

        // Check microphone permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission needed. Grant it in app Settings.", Toast.LENGTH_LONG).show()
            // Open the main app so user can grant permission
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent?.let { startActivity(it) }
            return
        }

        // Check if speech recognition is available on this device
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        try {
            chatSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            chatSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isChatListening = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        floatingChatView?.findViewById<TextView>(R.id.btn_cancel_voice)?.visibility = View.VISIBLE
                        floatingChatView?.findViewById<ImageView>(R.id.btn_voice_input)?.setColorFilter(CYAN)
                        // Show voice status bar
                        floatingChatView?.findViewById<View>(R.id.voice_status_bar)?.visibility = View.VISIBLE
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { stopChatVoiceInput() }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        else -> "Voice input error ($error)"
                    }
                    Toast.makeText(this@ChatOverlayService, msg, Toast.LENGTH_SHORT).show()
                    stopChatVoiceInput()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        addMessageToChatbot(text, isUser = true)
                        processUserCommand(text)
                    }
                    stopChatVoiceInput()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partial.isNullOrEmpty()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            floatingChatView?.findViewById<TextView>(R.id.tv_voice_partial)?.text = partial[0]
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            chatSpeechRecognizer?.startListening(speechIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition failed: ${e.message}", Toast.LENGTH_LONG).show()
            isChatListening = false
        }
    }

    private fun stopChatVoiceInput() {
        if (!isChatListening && chatSpeechRecognizer == null) return
        isChatListening = false
        try { chatSpeechRecognizer?.stopListening(); chatSpeechRecognizer?.cancel(); chatSpeechRecognizer?.destroy() } catch (_: Exception) {}
        chatSpeechRecognizer = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            floatingChatView?.findViewById<TextView>(R.id.btn_cancel_voice)?.visibility = View.GONE
            floatingChatView?.findViewById<ImageView>(R.id.btn_voice_input)?.setColorFilter(WHITE)
            floatingChatView?.findViewById<View>(R.id.voice_status_bar)?.visibility = View.GONE
            floatingChatView?.findViewById<TextView>(R.id.tv_voice_partial)?.text = ""
        }
    }

    // ── Message handling
    private fun processUserCommand(userMessage: String) {
        // Intercept health check commands
        val lower = userMessage.lowercase().trim()
        if (lower == "health check" || lower == "run diagnostics" || lower == "status" || lower == "system status") {
            addMessageToChatbot(userMessage, isUser = true)
            addMessageToChatbot("Running health check...", isUser = false)
            serviceScope.launch {
                val report = healthCheckMonitor.forceCheckNow()
                addMessageToChatbot(report, isUser = false)
            }
            return
        }
        chatCommandCoordinator.processUserMessage(userMessage)
    }

    private fun addMessageToChatbot(message: String, isUser: Boolean) {
        floatingChatView?.let { view ->
            val messagesContainer = view.findViewById<LinearLayout>(R.id.messages_container) ?: return@let

            // Cap message bubbles to prevent OOM in long sessions
            val MAX_MESSAGES = 80
            while (messagesContainer.childCount >= MAX_MESSAGES) {
                messagesContainer.removeViewAt(0)
            }

            val messageView = LayoutInflater.from(this).inflate(
                if (isUser) R.layout.message_bubble_user else R.layout.message_bubble_bot,
                messagesContainer, false
            )
            val tvMessage = messageView.findViewById<TextView>(R.id.tv_message)
            tvMessage?.text = message
            tvMessage?.setTextIsSelectable(true)
            tvMessage?.setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Chat Message", tvMessage.text.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@ChatOverlayService, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }
            messagesContainer.addView(messageView)
            view.findViewById<ScrollView>(R.id.scroll_messages)?.post {
                view.findViewById<ScrollView>(R.id.scroll_messages)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun hideFloatingChatbot() {
        if (!isChatbotVisible) return
        stopChatVoiceInput()
        hideConnectedAppsPanel()
        // Smooth fade-out + scale-down animation
        floatingChatView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.92f)
            ?.scaleY(0.92f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction {
                try { windowManager.removeView(floatingChatView) } catch (_: Exception) {}
                floatingChatView = null
                floatingChatParams = null
                isChatbotVisible = false
            }
            ?.start()
    }

    // ── Feature handlers
    private fun openKeyboardSwitcher() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open keyboard picker", Toast.LENGTH_SHORT).show()
        }
    }
    private fun openMainApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun toggleFeature(featureId: Int) {
        if (activeFeatures.contains(featureId)) activeFeatures.remove(featureId) else activeFeatures.add(featureId)
        updateMenuItemsColor()
    }

    private fun isFeatureActive(featureId: Int): Boolean = activeFeatures.contains(featureId)

    private fun updateMenuItemsColor() {
        val cyan = android.graphics.Color.parseColor("#00F6FF")
        menuItems.forEach { item ->
            if (activeFeatures.contains(item.id)) {
                val outer = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(cyan)
                }
                val inner = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DARK)
                    setSize(dpToPx(46f), dpToPx(46f))
                }
                item.background = LayerDrawable(arrayOf(outer, inner))
            } else {
                item.setBackgroundResource(R.drawable.menu_tile_bg_dark)
            }
            item.setColorFilter(WHITE)
        }
    }

    // ── Touch / drag ──────────────────────────────────────────────────
    //
    // Tap vs. drag is decided using the system's scaledTouchSlop (typically
    // 24–32px = ~8dp). Below that threshold, every gesture is a tap and we
    // do NOT move the bubble — eliminating the "jitter on click" the user
    // reported. Above the threshold, it's a drag and we follow the finger.
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resetIdleTimer()
                // Restore bubble immediately on press so the user sees the
                // un-dimmed bubble under their finger (feels responsive).
                if (isBubbleIdle) restoreBubble()
                initialTouchX = event.rawX; initialTouchY = event.rawY
                initialX = bubbleScreenX; initialY = bubbleScreenY
                isDragging = false; hasMoved = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isWindowResizing || preventPositionUpdates) return true
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                // Use 1.5x touch slop for drag detection — gives the tap a
                // generous deadzone so micro-movements don't accidentally
                // start a drag and reposition the bubble.
                val dragThreshold = (touchSlop * 1.5f).toInt()
                if (abs(dx) > dragThreshold || abs(dy) > dragThreshold) {
                    hasMoved = true
                    if (!isMenuExpanded) {
                        isDragging = true
                        bubbleScreenX = initialX + dx; bubbleScreenY = initialY + dy
                        val bubbleSizePx       = dpToPx(bubbleSizeDp).toFloat()
                        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)
                        val windowHalfSize     = collapsedWindowSizePx / 2
                        params.x = (bubbleScreenX - windowHalfSize).toInt()
                        params.y = (bubbleScreenY - windowHalfSize).toInt()
                        try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    } else { if (!isMenuAnimating) collapseMenu() }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                resetIdleTimer()
                if (isWindowResizing || preventPositionUpdates) { isDragging = false; hasMoved = false; return true }
                if (!hasMoved && !isDragging) { if (!isMenuAnimating) toggleMenu() }
                else if (isDragging) {
                    if (isWindowResizing || preventPositionUpdates) overlayView.postDelayed({ snapToEdge() }, 200)
                    else snapToEdge()
                }
                isDragging = false; hasMoved = false; return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false; hasMoved = false
                snapToEdge()
                return true
            }
        }
        return false
    }

    // ── Notification
    private fun buildNotification(): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Stremini AI")
            .setContentText("Bubble active — tap to open app")
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)
        return builder
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        idleAnimationController.cancel()
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        serviceScope.cancel()
        stopChatVoiceInput()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}

        // Force-remove chat panel regardless of visibility state
        floatingChatView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            floatingChatView = null
            floatingChatParams = null
        }

        // Always try to remove connectors panel (flag could be wrong due to animation race)
        connectorsView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            connectorsView = null
        }
        isConnectorsVisible = false

        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }
}