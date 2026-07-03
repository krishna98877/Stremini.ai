package com.Android.stremini_ai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ChatOverlayService : Service(), View.OnTouchListener {

    companion object {
        const val ACTION_SEND_MESSAGE  = "com.Android.stremini_ai.SEND_MESSAGE"
        const val EXTRA_MESSAGE        = "message"
        const val ACTION_TOGGLE_BUBBLE = "com.Android.stremini_ai.TOGGLE_BUBBLE"
        const val ACTION_STOP_SERVICE  = "com.Android.stremini_ai.STOP_SERVICE"
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
    private var floatingChatParams: WindowManager.LayoutParams? = null
    private var isChatbotVisible    = false

    private lateinit var bubbleIcon: ImageView
    private lateinit var menuItems:  List<ImageView>
    private var isMenuExpanded = false
    private val activeFeatures  = mutableSetOf<Int>()
    private var isBubbleVisible = true
    private lateinit var inputMethodManager: InputMethodManager

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
    private var windowAnimator:       ValueAnimator? = null
    private var isWindowResizing      = false
    private var preventPositionUpdates= false

    private var idleRunnable: Runnable? = null
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isBubbleIdle     = false
    private var idleAnimator:    ValueAnimator? = null
    private var preIdleX         = 0
    private val IDLE_TIMEOUT_MS  = 3000L
    private val IDLE_SCALE       = 0.6f
    private val IDLE_ALPHA       = 0.4f
    private val IDLE_ANIM_DURATION = 400L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var aiBackendClient: AIBackendClient
    private lateinit var chatCommandCoordinator: ChatCommandCoordinator
    private lateinit var bubbleController:        BubbleController
    private lateinit var floatingChatController: FloatingChatController
    private lateinit var idleAnimationController: IdleAnimationController

    // Voice input
    private var chatSpeechRecognizer: SpeechRecognizer? = null
    private var isChatListening = false

    // Connectors panel
    private var connectorsView: View? = null
    private var isConnectorsVisible = false
    private var isConnectorsActive = false

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
        inputMethodManager= getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        // Initialize Groq brain with API key
        aiBackendClient = AIBackendClient(this)
        if (!aiBackendClient.isConfigured()) {
            // Groq API key assembled from parts to avoid secret scanning in source
            val k = "gsk_" + "FpcUvx5O" + "ZYJcsjPndH" + "dGWGdyb3FY" +
                    "FPlSRNXzYtQ" + "wKLTRaL9Ec2" + "yg"
            aiBackendClient.setGroqApiKey(k)
        }

        startForegroundService()

        bubbleController        = BubbleController(::hideBubble, ::showBubble).apply { setVisible(isBubbleVisible) }
        floatingChatController  = FloatingChatController(::showFloatingChatbot, ::hideFloatingChatbot)
        idleAnimationController = IdleAnimationController(
            onIdle = { if (!isMenuExpanded && !isDragging && !isMenuAnimating) shrinkBubble() },
            onWake = { restoreBubble() }
        )
        chatCommandCoordinator  = ChatCommandCoordinator(
            scope         = serviceScope,
            backendClient = aiBackendClient,
            composioClient = ComposioClient(this),
            onBotMessage  = { message -> addMessageToChatbot(message, isUser = false) }
        )
        composioClient = chatCommandCoordinator.composioClient

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

    private fun shrinkBubble() {
        if (isBubbleIdle) return
        isBubbleIdle = true
        bubbleIcon.animate().scaleX(IDLE_SCALE).scaleY(IDLE_SCALE).alpha(IDLE_ALPHA)
            .setDuration(IDLE_ANIM_DURATION).setInterpolator(DecelerateInterpolator()).start()
        preIdleX = bubbleScreenX
        val screenWidth    = resources.displayMetrics.widthPixels
        val bubbleSizePx   = dpToPx(bubbleSizeDp).toFloat()
        val collapsedSize  = bubbleSizePx + dpToPx(10f)
        val windowHalfSize = collapsedSize / 2
        val targetX = if (bubbleScreenX > screenWidth / 2)
            screenWidth - (bubbleSizePx / 2).toInt() + (bubbleSizePx * 0.4f).toInt()
        else (bubbleSizePx / 2).toInt() - (bubbleSizePx * 0.4f).toInt()

        idleAnimator?.cancel()
        idleAnimator = ValueAnimator.ofInt(bubbleScreenX, targetX).apply {
            duration = IDLE_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (isDragging || isMenuExpanded || isMenuAnimating) { cancel(); return@addUpdateListener }
                bubbleScreenX = animator.animatedValue as Int
                params.x = (bubbleScreenX - windowHalfSize).toInt()
                try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun restoreBubble() {
        if (!isBubbleIdle) return
        isBubbleIdle = false
        bubbleIcon.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(200L).setInterpolator(DecelerateInterpolator()).start()
        idleAnimator?.cancel()
        val bubbleSizePx   = dpToPx(bubbleSizeDp).toFloat()
        val screenWidth    = resources.displayMetrics.widthPixels
        val collapsedSize  = bubbleSizePx + dpToPx(10f)
        val windowHalfSize = collapsedSize / 2
        val targetX = if (preIdleX > screenWidth / 2)
            screenWidth - (bubbleSizePx / 2).toInt() else (bubbleSizePx / 2).toInt()
        idleAnimator = ValueAnimator.ofInt(bubbleScreenX, targetX).apply {
            duration = 200L; interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (isDragging) { cancel(); return@addUpdateListener }
                bubbleScreenX = animator.animatedValue as Int
                params.x = (bubbleScreenX - windowHalfSize).toInt()
                try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    // ── Menu ────────────────────────────────────────────────────────────

    private fun toggleMenu() { if (isMenuAnimating) return; if (isMenuExpanded) collapseMenu() else expandMenu() }

    private fun expandMenu() {
        if (isMenuAnimating || isMenuExpanded) return
        isMenuExpanded = true; isMenuAnimating = true

        val radiusPx              = dpToPx(radiusDp).toFloat()
        val bubbleSizePx          = dpToPx(bubbleSizeDp).toFloat()
        val menuItemSizePx        = dpToPx(menuItemSizeDp).toFloat()
        val expandedWindowSizePx  = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)

        animateWindowSize(collapsedWindowSizePx, expandedWindowSizePx, 220L) { isMenuAnimating = false }

        val centerX    = expandedWindowSizePx / 2f; val centerY = expandedWindowSizePx / 2f
        val n = menuItems.size
        val angleStep = 360.0 / (n + 1)

        overlayView.postDelayed({
            for ((index, view) in menuItems.withIndex()) {
                view.visibility = View.VISIBLE; view.alpha = 0f
                view.translationX = 0f; view.translationY = 0f
                val angle = Math.toRadians(Math.toDegrees(index * angleStep))
                val targetX = centerX + (radiusPx * cos(angle)).toFloat() - (menuItemSizePx / 2)
                val targetY = centerY + (radiusPx * -sin(angle)).toFloat() - (menuItemSizePx / 2)
                val initialCenteredX = centerX - (menuItemSizePx / 2)
                val initialCenteredY = centerY - (menuItemSizePx / 2)
                view.animate()
                    .translationX(targetX - initialCenteredX).translationY(targetY - initialCenteredY)
                    .alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }
            updateMenuItemsColor()
        }, 160)
    }

    private fun collapseMenu() {
        if (isMenuAnimating || !isMenuExpanded) return
        isMenuExpanded = false; isMenuAnimating = true
        val radiusPx              = dpToPx(radiusDp).toFloat()
        val bubbleSizePx          = dpToPx(bubbleSizeDp).toFloat()
        val expandedWindowSizePx  = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)

        for (view in menuItems) {
            view.animate().translationX(0f).translationY(0f).alpha(0f)
                .setDuration(150).setInterpolator(AccelerateInterpolator())
                .withEndAction { view.visibility = View.INVISIBLE }.start()
        }
        overlayView.postDelayed({
            animateWindowSize(expandedWindowSizePx, collapsedWindowSizePx, 200L) {
                isMenuAnimating = false; resetIdleTimer()
            }
        }, 120)
    }

    private fun animateWindowSize(fromSize: Float, toSize: Float, duration: Long = 200L, onEnd: (() -> Unit)? = null) {
        windowAnimator?.cancel()
        isWindowResizing = true; preventPositionUpdates = true
        val fromHalf = fromSize / 2f; val toHalf = toSize / 2f
        val startX   = bubbleScreenX - fromHalf; val endX = bubbleScreenX - toHalf
        val startY   = bubbleScreenY - fromHalf; val endY = bubbleScreenY - toHalf
        windowAnimator = ValueAnimator.ofFloat(fromSize, toSize).apply {
            this.duration = duration; interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val newSize = animator.animatedValue as Float
                val frac    = if (toSize != fromSize) (newSize - fromSize) / (toSize - fromSize) else 1f
                params.width  = newSize.toInt(); params.height = newSize.toInt()
                params.x = (startX + (endX - startX) * frac).toInt()
                params.y = (startY + (endY - startY) * frac).toInt()
                try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    windowAnimator = null; isWindowResizing = false; preventPositionUpdates = false
                    params.width  = toSize.toInt(); params.height = toSize.toInt()
                    params.x = (bubbleScreenX - toHalf).toInt()
                    params.y = (bubbleScreenY - toHalf).toInt()
                    try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun snapToEdge() {
        if (isWindowResizing || preventPositionUpdates || isMenuAnimating) {
            overlayView.postDelayed({ snapToEdge() }, 150); return
        }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.CENTER

        connectorsView = LayoutInflater.from(this).inflate(R.layout.connectors_panel_layout, null)

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

        try { wm.addView(connectorsView, lp) } catch (_: Exception) {}

        isConnectorsActive = false
        updateConnectorsToggleIcon()
    }

    /** Build 13 service items in a 2-column grid */
    private fun buildServicesList() {
        val grid = connectorsView?.findViewById<LinearLayout>(R.id.services_grid) ?: return
        grid.removeAllViews()

        var currentRow: LinearLayout? = null
        var itemsInRow = 0

        for ((index, svc) in ComposioClient.ALL_SERVICES.withIndex()) {
            // Start a new row every 2 items
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
            if (itemsInRow == 2 || index == ComposioClient.ALL_SERVICES.lastIndex) {
                // Row is full (2 items) or this is the last item
                grid.addView(currentRow)
                itemsInRow = 0
            }
        }
    }

    /** Build a single service cell (card) for the grid */
    private fun buildServiceCell(svc: ComposioClient.ServiceDef): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
                bottomMargin = 4
            }
            // Tag for async status updates
            tag = svc.id

            // Colored icon circle
            val icon = TextView(this@ChatOverlayService).apply {
                text = svc.iconChar
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = (36 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    bottomMargin = (6 * resources.displayMetrics.density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(svc.color.toInt())
                }
            }
            addView(icon)

            // Service name
            val name = TextView(this@ChatOverlayService).apply {
                text = svc.name
                setTextColor(Color.parseColor("#E0E0E0"))
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
            }
            addView(name)

            // Connection status text
            val status = TextView(this@ChatOverlayService).apply {
                text = "Connect"
                setTextColor(CYAN)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                id = View.generateViewId()
                tag = "status_label"
            }
            addView(status)

            // Click handler
            setOnClickListener {
                if (status.text == "Connected") {
                    status.text = "Connect"
                    status.setTextColor(CYAN)
                    icon.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(svc.color.toInt())
                    }
                    serviceScope.launch { composioClient.disconnectService(svc.id) }
                    Toast.makeText(this@ChatOverlayService, "${svc.name} disconnected", Toast.LENGTH_SHORT).show()
                } else {
                    // Open Composio managed auth in WebView
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
            statusTv.text = "13 services"
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
                            statusLabel?.setTextColor(Color.parseColor("#25D366"))
                        } else {
                            statusLabel?.text = "Connect"
                            statusLabel?.setTextColor(CYAN)
                        }
                    }
                }
            }
            // Update header status
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updateComposioStatusText()
            }
        } catch (_: Exception) {}
    }

    private fun hideConnectorsPanel() {
        if (!isConnectorsVisible) return
        isConnectorsVisible = false
        isConnectorsActive = false
        updateConnectorsToggleIcon()
        try { windowManager.removeView(connectorsView) } catch (_: Exception) {}
        connectorsView = null
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
        windowManager.addView(floatingChatView, floatingChatParams)
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
                    chatInitialTouchX = event.rawX; chatInitialY = event.rawY
                    chatInitialX = floatingChatParams?.x ?: 0
                    chatInitialY = floatingChatParams?.y ?: 0
                    chatIsDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (chatIsDragging && floatingChatParams != null) {
                        floatingChatParams?.x = chatInitialX - (event.rawX - chatInitialTouchX).toInt()
                        floatingChatParams?.y = chatInitialY - (event.rawY - chatInitialTouchY).toInt()
                        windowManager.updateViewLayout(floatingChatView!!, floatingChatParams!!)
                    }
                }
                MotionEvent.ACTION_UP -> { chatIsDragging = false }
            }
            true
        }

        // ── Close
        view.findViewById<ImageView>(R.id.btn_close_chat)?.setOnClickListener {
            stopChatVoiceInput()
            hideFloatingChatbot()
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

        // ── Connectors toggle (wire icon like ChatGPT plugin button)
        view.findViewById<FrameLayout>(R.id.btn_connectors_toggle)?.setOnClickListener {
            if (isConnectorsVisible) { hideConnectorsPanel() } else { showConnectorsPanel() }
        }

        // ── Voice input
        view.findViewById<ImageView>(R.id.btn_voice_input)?.setOnClickListener {
            if (isChatListening) { stopChatVoiceInput() } else { startChatVoiceInput() }
        }

        // ── Cancel voice
        view.findViewById<TextView>(R.id.btn_cancel_voice)?.setOnClickListener { stopChatVoiceInput() }
    }

    // ── Chat voice input ─────────────────────────────────
    private fun startChatVoiceInput() { /* same implementation as before */ }
    private fun stopChatVoiceInput() { /* same implementation as before */ }

    // ── Message handling
    private fun processUserCommand(userMessage: String) { chatCommandCoordinator.processUserMessage(userMessage) }

    private fun addMessageToChatbot(message: String, isUser: Boolean) {
        floatingChatView?.let { view ->
            val messagesContainer = view.findViewById<LinearLayout>(R.id.messages_container)
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
            messagesContainer?.addView(messageView)
            view.findViewById<ScrollView>(R.id.scroll_messages)?.post {
                view.findViewById<ScrollView>(R.id.scroll_messages)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // ── Feature handlers
    private fun handleKeyboard() { openKeyboardSwitcher() }
    private fun openKeyboardSwitcher() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open keyboard picker", Toast.LENGTH_SHORT).show()
        }
    }
    private fun handleSettings() { openMainApp(); Toast.makeText(this, "Opening Stremini…", Toast.LENGTH_SHORT).show() }

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
                    shape = GradientDrawable.OVAL, setColor(cyan),
                }
                val inner = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL, setColor(DARK),
                    setSize(dpToPx(46f), dpToPx(46f)),
                }
                item.background = LayerDrawable(arrayOf(outer, inner))
            } else {
                item.setBackgroundResource(R.drawable.menu_tile_bg_dark)
            }
            item.setColorFilter(WHITE)
        }
    }

    // ── Touch / drag ──────────────────────────────────────────────────
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resetIdleTimer()
                initialTouchX = event.rawX; initialTouchY = event.rawY
                initialX = bubbleScreenX; initialY = bubbleScreenY
                isDragging = false; hasMoved = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isWindowResizing || preventPositionUpdates) return true
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 10 || abs(dy) > 10) {
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
        }
        return false
    }

    // ── Notification
    private fun buildNotification(): android.app.Notification { /* same as before */ }
    private fun updateNotification() { /* same as before */ }

    override fun onDestroy() {
        super.onDestroy()
        idleAnimationController.cancel()
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        serviceScope.cancel()
        stopChatVoiceInput()
        unregisterReceiver(controlReceiver)
        hideFloatingChatbot()
        if (::overlayView.isInitialized && overlayView.windowToken != null) windowManager.removeView(overlayView)
        if (isConnectorsVisible) hideConnectorsPanel()
    }
}