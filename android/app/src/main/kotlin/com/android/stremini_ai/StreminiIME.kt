package com.android.stremini_ai

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.PopupMenu
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ClipDescription
import android.net.Uri
import android.provider.MediaStore
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.SoundPool
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.media.AudioManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

class StreminiIME : InputMethodService() {

    companion object {
        private const val TAG = "StreminiIME"
        private const val PREFS_NAME = "keyboard_prefs"
        private const val CLIPBOARD_HISTORY_KEY = "clipboard_history"
        private const val CLIPBOARD_HISTORY_ENABLED_KEY = "clipboard_history_enabled"
        // Maximum number of clipboard entries to keep. Acts as a fixed-size ring buffer:
        // newest item goes to index 0, oldest item is dropped when size exceeds this limit.
        private const val CLIPBOARD_HISTORY_LIMIT = 5
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var encryptedPrefs: EncryptedPrefs.EncryptedSharedPreferencesWrapper

    private lateinit var imeBackendClient: IMEBackendClient

    private val handler = Handler(Looper.getMainLooper())

    // State
    private var isShiftOn = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var isMoreSymbolsMode = false
    private var lastShiftTapTime = 0L
    private val letterKeyViews = mutableListOf<TextView>()
    private var shiftKeyView: TextView? = null
    private var symbolsKeyView: TextView? = null
    private var enterKeyView: TextView? = null
    private var keyboardRootView: View? = null
    private val keyTextViewCache = HashMap<Int, TextView>()
    private var currentAppContext = "general"
    private var selectedTone = "professional"
    private var isAiFeatureMode = false  // AI features hidden by default
    private var aiActionJob: Job? = null
    private var lastAiActionTs = 0L
    private var isSettingsMenuMode = false
    private var isOneHandedMode = false
    private var isExpandedHeight = false
    
    // Clipboard icon state
    private var clipboardIconOn: ImageView? = null
    private var clipboardIconOff: ImageView? = null

    // Voice typing state
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var speechErrorCount = 0
    private val MAX_SPEECH_ERRORS = 3
    private val recognitionResults = StringBuilder()
    private var soundPool: SoundPool? = null
    private var micClickSoundId: Int = 0
    private var selectedLanguage = "en-US"
    private val supportedLanguages = listOf(
        "hi-IN" to "Hindi (India)",
        "en-IN" to "English (India)",
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "bn-IN" to "Bengali (India)",
        "ta-IN" to "Tamil (India)",
        "te-IN" to "Telugu (India)",
        "mr-IN" to "Marathi (India)",
        "gu-IN" to "Gujarati (India)",
        "kn-IN" to "Kannada (India)",
        "ml-IN" to "Malayalam (India)",
        "pa-IN" to "Punjabi (India)",
        "ur-IN" to "Urdu (India)"
    )

    private val defaultMajorLanguages = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "hi" to "Hindi",
        "pt" to "Portuguese",
        "ar" to "Arabic",
        "ja" to "Japanese"
    )

    private var translationLanguages = emptyList<Pair<String, String>>()

    private val alphaNumericKeyMap = mapOf(
        R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r", R.id.key_t to "t",
        R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i", R.id.key_o to "o", R.id.key_p to "p",
        R.id.key_a to "a", R.id.key_s to "s", R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g",
        R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l",
        R.id.key_z to "z", R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
        R.id.key_n to "n", R.id.key_m to "m",
        R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3", R.id.key_4 to "4", R.id.key_5 to "5",
        R.id.key_6 to "6", R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9", R.id.key_0 to "0"
        // key_dot_semicolon is handled by its own touch listener (tap='.', long-press=',')
    )

    private val specialCharacterKeyMap = mapOf(
        "key_at" to "@",
        "key_hash" to "#",
        "key_amp" to "&",
        "key_question" to "?",
        "key_exclaim" to "!",
        "key_underscore" to "_",
        "key_dash" to "-",
        "key_colon" to ":"
    )

    // Symbols Layer 1 (Gboard-like: ?123)
    private val symbolsKeyMap = mapOf(
        R.id.key_q to "@", R.id.key_w to "#", R.id.key_e to "$", R.id.key_r to "_", R.id.key_t to "&",
        R.id.key_y to "-", R.id.key_u to "+", R.id.key_i to "(", R.id.key_o to ")", R.id.key_p to "/",
        R.id.key_a to "*", R.id.key_s to "\"", R.id.key_d to "'", R.id.key_f to ":", R.id.key_g to ";",
        R.id.key_h to "!", R.id.key_j to "?", R.id.key_k to "<", R.id.key_l to ">",
        R.id.key_z to ",", R.id.key_x to ".", R.id.key_c to "~", R.id.key_v to "\\", R.id.key_b to "|",
        R.id.key_n to "=", R.id.key_m to "%"
    )

    // Symbols Layer 2 (Gboard-like: =\<)
    private val moreSymbolsKeyMap = mapOf(
        R.id.key_q to "~", R.id.key_w to "`", R.id.key_e to "|", R.id.key_r to "•", R.id.key_t to "√",
        R.id.key_y to "π", R.id.key_u to "÷", R.id.key_i to "×", R.id.key_o to "¶", R.id.key_p to "∆",
        R.id.key_a to "£", R.id.key_s to "¢", R.id.key_d to "€", R.id.key_f to "¥", R.id.key_g to "^",
        R.id.key_h to "°", R.id.key_j to "≤", R.id.key_k to "≥", R.id.key_l to "≈",
        R.id.key_z to "±", R.id.key_x to "§", R.id.key_c to "©", R.id.key_v to "®", R.id.key_b to "™",
        R.id.key_n to "{", R.id.key_m to "}"
    )

    // Backspace Repeater
    private var isBackspacePressed = false
    private val backspaceRunnable = object : Runnable {
        override fun run() {
            if (isBackspacePressed) {
                handleBackspace()
                handler.postDelayed(this, 40) // 40ms = 25 chars/sec deletion speed
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        imeBackendClient = IMEBackendClient(this)
        translationLanguages = imeBackendClient.fetchTranslationLanguages().getOrDefault(emptyList())
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        encryptedPrefs = EncryptedPrefs.getEncrypted(this, PREFS_NAME)
        selectedLanguage = sharedPrefs?.getString("selected_language", "en-US") ?: "en-US"
        loadSoundEffects()
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                isListening = true
                speechErrorCount = 0
                showListeningIndicator(true, Color.parseColor("#00BFFF"))
                playClickSound()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Restart listening for continuous recognition
                if (isListening) {
                    handler.postDelayed({
                        if (isListening) {
                            restartSpeechRecognition()
                        }
                    }, 100)
                }
            }
            override fun onError(error: Int) {
                val isFatalError = error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_AUDIO

                if (isFatalError || speechErrorCount >= MAX_SPEECH_ERRORS) {
                    isListening = false
                    speechErrorCount = 0
                    showListeningIndicator(false)
                    if (error == SpeechRecognizer.ERROR_NETWORK) {
                        Toast.makeText(this@StreminiIME, "No internet for voice. Try again.", Toast.LENGTH_SHORT).show()
                    }
                    handleError(error)
                    return
                }

                if (isListening) {
                    speechErrorCount++
                    handler.postDelayed({
                        if (isListening) restartSpeechRecognition()
                    }, 300)
                } else {
                    isListening = false
                    showListeningIndicator(false)
                    handleError(error)
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    currentInputConnection?.commitText(recognizedText, 1)
                }
                // Continuous restart handled by onEndOfSpeech — do not restart here
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                // Show partial results for real-time feedback
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    // Could show partial text in a preview if needed
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
    }

    private fun restartSpeechRecognition() {
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
            handler.postDelayed({
                if (isListening) {
                    startSpeechRecognitionInternal()
                }
            }, 50)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildRecognitionIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
    }

    private fun startSpeechRecognitionInternal() {
        if (!isListening) return
        try {
            speechRecognizer?.startListening(buildRecognitionIntent())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSpeechRecognition() {
        if (isListening) {
            stopSpeechRecognition()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }
        
        isListening = true
        showListeningIndicator(true, Color.parseColor("#00BFFF")) // DeepSkyBlue aura

        try {
            speechRecognizer?.startListening(buildRecognitionIntent())
        } catch (e: Exception) {
            e.printStackTrace()
            showListeningIndicator(true, Color.RED)
            handler.postDelayed({ showListeningIndicator(false) }, 1000)
            isListening = false
        }
    }

    private fun stopSpeechRecognition() {
        if (!isListening) return
        speechRecognizer?.stopListening()
        isListening = false
        showListeningIndicator(false)
    }

    private fun showListeningIndicator(show: Boolean, color: Int = Color.TRANSPARENT) {
        val micImageView = keyboardRootView?.findViewById<ImageView>(R.id.key_voice)
        val micFrame = micImageView?.parent as? View
        
        if (show && micFrame != null) {
            val aura = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            micFrame.background = aura
            
            // Continuous Pulse Animation
            fun startPulse() {
                if (!isListening && color != Color.RED) return
                micFrame.animate().scaleX(1.15f).scaleY(1.15f).alpha(0.7f).setDuration(600).withEndAction {
                    micFrame.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(600).withEndAction {
                        if (isListening || color == Color.RED) startPulse()
                    }.start()
                }.start()
            }
            startPulse()
            
            if (color == Color.RED) {
                micImageView.setColorFilter(Color.RED)
            } else {
                micImageView.setColorFilter(Color.parseColor("#00BFFF"))
            }

            // Auto hide error after 1 second
            if (color == Color.RED) {
                handler.postDelayed({ showListeningIndicator(false) }, 1000)
            }
        } else {
            micFrame?.background = null
            micFrame?.animate()?.cancel()
            micFrame?.scaleX = 1.0f
            micFrame?.scaleY = 1.0f
            micFrame?.alpha = 1.0f
            micImageView?.clearColorFilter()
        }
    }

    private fun loadSoundEffects() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()
        // Sound file not available, will use ToneGenerator fallback
        micClickSoundId = 0
    }

    private fun playClickSound() {
        if (micClickSoundId != 0) {
            soundPool?.play(micClickSoundId, 1f, 1f, 1, 0, 1f)
        } else {
            try {
                val toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 50)
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                toneGenerator.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseSoundEffects() {
        soundPool?.release()
        soundPool = null
        micClickSoundId = 0
    }

    private fun handleError(errorCode: Int) {
        val message = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            else -> "Unknown error"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showLanguageSelection() {
        val languageNames = supportedLanguages.map { it.second }.toTypedArray()
        val currentLanguageIndex = supportedLanguages.indexOfFirst { it.first == selectedLanguage }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Select Voice Language")
            .setSingleChoiceItems(languageNames, currentLanguageIndex) { dialog, which ->
                selectedLanguage = supportedLanguages[which].first
                Toast.makeText(this, "Language: ${supportedLanguages[which].second}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardRootView = view
        buildKeyCache(view)
        setupKeyboardInteractions(view)
        return view
    }

    private fun buildKeyCache(view: View) {
        keyTextViewCache.clear()
        alphaNumericKeyMap.keys.forEach { id ->
            (view.findViewById<View>(id) as? TextView)?.let { keyTextViewCache[id] = it }
        }
        listOf(R.id.key_symbols, R.id.key_enter).forEach { id ->
            (view.findViewById<View>(id) as? TextView)?.let { keyTextViewCache[id] = it }
        }
    }

    private fun setupKeyboardInteractions(view: View) {
        letterKeyViews.clear()
        shiftKeyView = view.findViewById(R.id.key_shift)
        symbolsKeyView = view.findViewById(R.id.key_symbols)
        enterKeyView = view.findViewById(R.id.key_enter)

        disableSoundEffects(view)

        // 1. Attach key listeners
        alphaNumericKeyMap.forEach { (id, char) ->
            val keyView = keyTextViewCache[id] ?: view.findViewById<View>(id)
            if (keyView is TextView && char.length == 1 && char[0].isLetter()) {
                letterKeyViews.add(keyView)
            }
            keyView?.setOnTouchListener(createKeyTouchListener(id))
        }

        specialCharacterKeyMap.forEach { (idName, value) ->
            val keyId = resources.getIdentifier(idName, "id", packageName)
            if (keyId != 0) {
                view.findViewById<View>(keyId)?.setOnTouchListener(createTextTouchListener(value))
            }
        }

        // Space (with Cursor Sliding & Double-tap for Period)
        var lastSpaceTapTime = 0L
        var spaceDownX = 0f
        var lastCursorMoveX = 0f
        var isSpaceSlide = false
        val SLIDE_START_THRESHOLD = 40f
        val CURSOR_MOVE_THRESHOLD = 30f

        view.findViewById<View>(R.id.key_space)?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                    spaceDownX = event.rawX
                    lastCursorMoveX = spaceDownX
                    isSpaceSlide = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - spaceDownX
                    if (!isSpaceSlide && Math.abs(deltaX) > SLIDE_START_THRESHOLD) {
                        isSpaceSlide = true
                        lastCursorMoveX = event.rawX
                    }
                    if (isSpaceSlide) {
                        val moveDelta = event.rawX - lastCursorMoveX
                        if (Math.abs(moveDelta) > CURSOR_MOVE_THRESHOLD) {
                            val ic = currentInputConnection
                            if (ic != null) {
                                val before = ic.getTextBeforeCursor(100, 0)?.length ?: 0
                                val newSel = if (moveDelta > 0) before + 1 else before - 1
                                if (newSel >= 0) {
                                    ic.setSelection(newSel, newSel)
                                }
                            }
                            lastCursorMoveX = event.rawX
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    animateKey(v, false)
                    if (!isSpaceSlide) {
                        val now = System.currentTimeMillis()
                        // If tapped twice within 400ms
                        if (now - lastSpaceTapTime < 400) {
                            val ic = currentInputConnection
                            if (ic != null) {
                                val before = ic.getTextBeforeCursor(2, 0)
                                if (before?.endsWith(" ") == true) {
                                    ic.deleteSurroundingText(1, 0)
                                    ic.commitText(". ", 1)
                                } else {
                                    commitText(" ")
                                }
                            }
                            lastSpaceTapTime = 0L
                        } else {
                            commitText(" ")
                            lastSpaceTapTime = now
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    animateKey(v, false)
                }
            }
            true
        }

        // key_dot_semicolon: visual ';', tap = '.', long-press = ','
        var isDotSemicolonLongPressed = false
        val dotSemicolonRunnable = Runnable {
            isDotSemicolonLongPressed = true
            view.findViewById<View>(R.id.key_dot_semicolon)?.let { feedback(it) }
            commitText(",")   // long-press produces a comma
        }

        view.findViewById<View>(R.id.key_dot_semicolon)?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                    isDotSemicolonLongPressed = false
                    handler.postDelayed(dotSemicolonRunnable, 400) // 400 ms threshold
                }
                MotionEvent.ACTION_UP -> {
                    animateKey(v, false)
                    handler.removeCallbacks(dotSemicolonRunnable)
                    if (!isDotSemicolonLongPressed) {
                        commitText(".")   // tap produces a full stop
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    animateKey(v, false)
                    handler.removeCallbacks(dotSemicolonRunnable)
                }
            }
            true
        }

        // Symbol/Alphabet toggle
        symbolsKeyView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                }
                MotionEvent.ACTION_UP -> {
                    animateKey(v, false)
                    isSymbolsMode = !isSymbolsMode
                    isMoreSymbolsMode = false
                    // When entering symbols mode, shift should not carry over
                    if (isSymbolsMode) {
                        isShiftOn = false
                        isCapsLock = false
                    }
                    updateKeyboardLabels()
                }
                MotionEvent.ACTION_CANCEL -> animateKey(v, false)
            }
            true
        }

        // Backspace (Hold to delete)
        view.findViewById<View>(R.id.key_backspace)?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                    isBackspacePressed = true
                    handleBackspace()
                    handler.postDelayed(backspaceRunnable, 500) // Wait before repeating
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animateKey(v, false)
                    isBackspacePressed = false
                    handler.removeCallbacks(backspaceRunnable)
                }
            }
            true
        }

        // Enter Key
        view.findViewById<View>(R.id.key_enter)?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                feedback(v)
                animateKey(v, true)
            } else if (event.action == MotionEvent.ACTION_UP) {
                animateKey(v, false)
                handleEnterKey()
            }
            true
        }

        // Main Toolbar Logic
        val mainToolbar = view.findViewById<View>(R.id.main_toolbar_container)

        // Panel container reference
        val panelContainer = view.findViewById<FrameLayout>(R.id.panel_container)
        val keyboardBody = view.findViewById<View>(R.id.main_keyboard_body)

        // Central close function — always restores the keyboard
        val restoreKeyboard = fun() {
            panelContainer?.removeAllViews()
            panelContainer?.visibility = View.GONE
            keyboardBody?.visibility = View.VISIBLE
        }

        fun showPanel(showFn: (FrameLayout, () -> Unit) -> Unit) {
            keyboardBody?.visibility = View.GONE
            panelContainer?.let { showFn(it, restoreKeyboard) }
        }


        // Settings ⚙️ → If panel is open, close it. Otherwise, open tools panel.
        view.findViewById<View>(R.id.action_settings)?.setOnClickListener {
            if (panelContainer?.visibility == View.VISIBLE && panelContainer.childCount > 0) {
                restoreKeyboard()
                return@setOnClickListener
            }
            showPanel { panel, close ->
                val tools = mutableListOf<KeyboardPanels.ToolItem>()

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_clipboard, "Clipboard") {
                    addToHistoryIfMissing()
                    showClipboardPanel(panel, close)
                })

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_ai_tool, "AI Tools") {
                    isAiFeatureMode = !isAiFeatureMode
                    updateKeyboardModeUi()
                    close()
                })

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_emoji, "Emoji") {
                    panelContainer?.let { p ->
                        KeyboardPanels.showEmojiPanel(this@StreminiIME, p, currentInputConnection, restoreKeyboard)
                        setCloseCallback(p, restoreKeyboard)
                    }
                })

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_emoji, "Kaomoji") {
                    panelContainer?.let { p ->
                        KeyboardPanels.showKaomojiPanel(this@StreminiIME, p, currentInputConnection, restoreKeyboard)
                        setCloseCallback(p, restoreKeyboard)
                    }
                })

                tools.add(KeyboardPanels.ToolItem(android.R.drawable.ic_menu_sort_by_size, "Language") {
                    panelContainer?.let { p ->
                        KeyboardPanels.showLanguagePanel(this@StreminiIME, p, selectedLanguage, { newLang ->
                            selectedLanguage = newLang
                            sharedPrefs?.edit()?.putString("selected_language", newLang)?.apply()
                            Toast.makeText(this@StreminiIME, "Voice language: $newLang", Toast.LENGTH_SHORT).show()
                        }, restoreKeyboard)
                        setCloseCallback(p, restoreKeyboard)
                    }
                })

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_gif, "GIF") {
                    Toast.makeText(this, "GIF search coming soon", Toast.LENGTH_SHORT).show()
                    close()
                })

                val oneHandIconRes = if (isOneHandedMode) R.drawable.ic_two_hands else R.drawable.ic_one_hand
                val oneHandLabel = if (isOneHandedMode) "Normal hand" else "One-hand"
                tools.add(KeyboardPanels.ToolItem(oneHandIconRes, oneHandLabel) {
                    isOneHandedMode = !isOneHandedMode
                    val rootBody = keyboardRootView?.findViewById<LinearLayout>(R.id.main_keyboard_body)
                    if (rootBody != null) {
                        rootBody.layoutParams?.width = if (isOneHandedMode) (resources.displayMetrics.widthPixels * 0.8).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
                        val lp = rootBody.layoutParams
                        if (lp is LinearLayout.LayoutParams) {
                            lp.gravity = if (isOneHandedMode) android.view.Gravity.END else android.view.Gravity.CENTER_HORIZONTAL
                            rootBody.layoutParams = lp
                        }
                        rootBody.requestLayout()
                    }
                    close()
                })

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_resize, "Resize") {
                    isExpandedHeight = !isExpandedHeight
                    updateKeyboardHeight()
                    close()
                })

                tools.add(KeyboardPanels.ToolItem(R.drawable.ic_undo, "Undo") {
                    handleUndo()
                    close()
                })

                KeyboardPanels.showToolsPanel(this@StreminiIME, panel, tools)
                setCloseCallback(panel, close)
            }
        }

        // 🔄 AI Toggle button on toolbar
        view.findViewById<View>(R.id.action_ai_toggle)?.setOnClickListener {
            feedback(it)
            isAiFeatureMode = !isAiFeatureMode
            updateKeyboardModeUi()
        }

        // 📋 Clipboard icon on toolbar (with empty state toggle)
        clipboardIconOn = view.findViewById(R.id.action_toolbar_clipboard_on)
        clipboardIconOff = view.findViewById(R.id.action_toolbar_clipboard_off)
        updateClipboardIcon()
        
        view.findViewById<View>(R.id.action_toolbar_clipboard_on)?.setOnClickListener {
            feedback(it)
            addToHistoryIfMissing()
            showPanel { panel, close ->
                showClipboardPanel(panel, close)
                setCloseCallback(panel, close)
            }
        }
        
        view.findViewById<View>(R.id.action_toolbar_clipboard_off)?.setOnClickListener {
            feedback(it)
            addToHistoryIfMissing()
            showPanel { panel, close ->
                showClipboardPanel(panel, close)
                setCloseCallback(panel, close)
            }
        }

        // 😊 Emoji icon on toolbar
        view.findViewById<View>(R.id.action_toolbar_emoji)?.setOnClickListener {
            feedback(it)
            showPanel { panel, close ->
                KeyboardPanels.showEmojiPanel(this@StreminiIME, panel, currentInputConnection, close)
                setCloseCallback(panel, close)
            }
        }

        // Voice typing toggle with long press for language selection
        view.findViewById<View>(R.id.key_voice)?.setOnClickListener {
            if (isListening) {
                stopSpeechRecognition()
            } else {
                startSpeechRecognition()
            }
        }
        
        view.findViewById<View>(R.id.key_voice)?.setOnLongClickListener {
            showLanguageSelection()
            true
        }

        // Shift Key — Gboard behavior
        shiftKeyView?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                feedback(v)
                if (isSymbolsMode) {
                    // In symbols mode: toggle between symbols layer 1 and layer 2
                    isMoreSymbolsMode = !isMoreSymbolsMode
                    updateKeyboardLabels()
                } else {
                    // In letters mode: handle shift / caps lock
                    val now = System.currentTimeMillis()
                    if (isShiftOn && !isCapsLock && now - lastShiftTapTime < 400) {
                        // Double-tap → caps lock
                        isCapsLock = true
                        isShiftOn = true
                    } else if (isCapsLock) {
                        // Tap while caps lock → turn off
                        isCapsLock = false
                        isShiftOn = false
                    } else {
                        // Single tap → one-shot shift
                        isShiftOn = !isShiftOn
                    }
                    lastShiftTapTime = now
                    updateKeyboardLabels()
                }
            }
            true
        }

        // AI Actions
        setupAiAction(view, R.id.action_improve, "correct")
        setupAiAction(view, R.id.action_complete, "complete")

        // Tone Changer → new panel
        view.findViewById<View>(R.id.action_tone)?.setOnClickListener {
            feedback(it)
            showPanel { panel, close ->
                KeyboardPanels.showTonePanel(this@StreminiIME, panel, selectedTone) { tone ->
                    selectedTone = tone
                    handleAiAction("tone")
                    close()
                }
                setCloseCallback(panel, close)
            }
        }

        // Translator → new panel
        view.findViewById<View>(R.id.action_translate)?.setOnClickListener {
            feedback(it)
            showPanel { panel, close ->
                val langs = if (translationLanguages.isNotEmpty()) translationLanguages else defaultMajorLanguages
                KeyboardPanels.showTranslatePanel(this@StreminiIME, panel, langs) { code, name ->
                    translateCurrentText(code, name)
                    close()
                }
                setCloseCallback(panel, close)
            }
        }

        // Clipboard Listener setup
        setupClipboardListener()

        updateKeyboardLabels()
        updateKeyboardModeUi()
    }

    private var inlinePasteVisibleValue = ""
    private var inlinePasteUri: Uri? = null
    private var inlinePasteMimeType: String? = null

    private fun setupClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val description = clip.description

                // Check for image/screenshots
                if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) || 
                    description.hasMimeType("image/*")) {
                    val uri = item.uri
                    if (uri != null) {
                        showInlinePasteSuggestion(null, uri, description.getMimeType(0))
                        // Also store in history as a URI string
                        saveClipboardEntry(uri.toString())
                        return@addPrimaryClipChangedListener
                    }
                }

                // Check for text
                val text = item.coerceToText(this).toString().trim()
                if (text.isNotBlank() && text != inlinePasteVisibleValue) {
                    saveClipboardEntry(text)
                    showInlinePasteSuggestion(text, null, ClipDescription.MIMETYPE_TEXT_PLAIN)
                }
            }
        }
    }

    private fun showInlinePasteSuggestion(text: String?, uri: Uri?, mimeType: String?) {
        val root = keyboardRootView ?: return
        val suggestionContainer = root.findViewById<View>(R.id.inline_paste_suggestion)
        val suggestionText = root.findViewById<TextView>(R.id.inline_paste_text)
        val suggestionThumbnail = root.findViewById<ImageView>(R.id.inline_paste_thumbnail)

        if (suggestionContainer != null && suggestionText != null && suggestionThumbnail != null) {
            inlinePasteVisibleValue = text ?: ""
            inlinePasteUri = uri
            inlinePasteMimeType = mimeType

            if (uri != null) {
                // UI for Image/Screenshot
                suggestionText.text = "Tap to paste"
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.setTargetSize(100, 100)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                    suggestionThumbnail.setImageBitmap(bitmap)
                    suggestionThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                } catch (e: Exception) {
                    suggestionThumbnail.setImageResource(R.drawable.ic_clipboard)
                }
            } else {
                // UI for Text
                suggestionText.text = if (text!!.length > 20) text.substring(0, 17) + "..." else text
                suggestionThumbnail.setImageResource(R.drawable.ic_clipboard)
                suggestionThumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            suggestionContainer.visibility = View.VISIBLE

            suggestionContainer.setOnClickListener {
                feedback(it)
                if (uri != null) {
                    pasteImage(uri, mimeType ?: "image/png")
                } else {
                    currentInputConnection?.commitText(text, 1)
                }
                // Do NOT add to history if pasted via suggestion
                hideInlinePasteSuggestion()
            }
        }
    }

    private fun pasteImage(uri: Uri, mimeType: String) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo ?: return
        
        val contentInfo = InputContentInfoCompat(uri, ClipDescription("Copied Image", arrayOf(mimeType)), null)
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        }
        
        InputConnectionCompat.commitContent(ic, editorInfo, contentInfo, flags, null)
    }

    private fun hideInlinePasteSuggestion() {
        val suggestionContainer = keyboardRootView?.findViewById<View>(R.id.inline_paste_suggestion)
        suggestionContainer?.visibility = View.GONE
        inlinePasteVisibleValue = ""
        inlinePasteUri = null
        inlinePasteMimeType = null
    }

    // This should be called when user explicitly opens the panel or suggestion expires
    // to keep history clean as per "sirf recent, un-pasted items rakho"
    private fun addToHistoryIfMissing() {
        if (inlinePasteVisibleValue.isNotBlank() && !getClipboardHistory().contains(inlinePasteVisibleValue)) {
            saveClipboardEntry(inlinePasteVisibleValue)
        }
    }

    /**
     * Finds and overrides the ⌨️ close button inside any panel to use the provided close callback.
     * This ensures keyboard body is always restored when user taps close.
     */
    private fun setCloseCallback(panel: FrameLayout, close: () -> Unit) {
        // The close button is the ⌨️ or ✕ text view in the panel header or tab bar.
        // Walk the view hierarchy and find any view with text "⌨️" or "✕" and set its click listener.
        fun findAndOverrideClose(view: View) {
            if (view is TextView && (view.text == "⌨️" || view.text == "✕")) {
                view.setOnClickListener { close() }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    findAndOverrideClose(view.getChildAt(i))
                }
            }
        }
        findAndOverrideClose(panel)
    }

    private fun disableSoundEffects(root: View) {
        root.isSoundEffectsEnabled = false
        root.isHapticFeedbackEnabled = false
        (root as? android.view.ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                disableSoundEffects(group.getChildAt(i))
            }
        }
    }

    // --- Performance Touch Listener ---
    private fun createKeyTouchListener(keyId: Int): View.OnTouchListener {
        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                }
                MotionEvent.ACTION_UP -> {
                    animateKey(v, false)
                    commitText(resolveKeyOutput(keyId))
                }
                MotionEvent.ACTION_CANCEL -> animateKey(v, false)
            }
            true
        }
    }



    private fun createTextTouchListener(text: String): View.OnTouchListener {
        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                }
                MotionEvent.ACTION_UP -> {
                    animateKey(v, false)
                    commitText(text)
                }
                MotionEvent.ACTION_CANCEL -> animateKey(v, false)
            }
            true
        }
    }

    // --- Core Logic ---

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        val output = if (!isSymbolsMode && isShiftOn && text.length == 1 && text[0].isLetter()) {
            text.uppercase()
        } else {
            text
        }
        
        ic.commitText(output, 1)
        // Cancel only the active AI action job, not all coroutines
        aiActionJob?.cancel(CancellationException("Cancelled by user input"))
        aiActionJob = null

        // Auto-turn off shift after one char (unless caps lock)
        if (!isSymbolsMode && isShiftOn && !isCapsLock) {
            isShiftOn = false
            // Labels will be updated via refreshAutoCap after committing
        }
        
        // Basic rule: Handle auto-spacing for punctuation if needed (optional rule)
        // But the user just asked for capitalization rules mostly.
        
        refreshAutoCap()
    }

    private fun resolveKeyOutput(keyId: Int): String {
        return if (isSymbolsMode) {
            val map = if (isMoreSymbolsMode) moreSymbolsKeyMap else symbolsKeyMap
            map[keyId] ?: alphaNumericKeyMap[keyId] ?: ""
        } else {
            alphaNumericKeyMap[keyId] ?: ""
        }
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        ic.beginBatchEdit()
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            // Fetch a chunk of text before the cursor to find the start of the previous character/emoji
            val textBefore = ic.getTextBeforeCursor(20, 0)
            if (!textBefore.isNullOrEmpty()) {
                val iter = java.text.BreakIterator.getCharacterInstance()
                iter.setText(textBefore.toString())
                val lastBoundary = iter.last()
                val prevBoundary = iter.previous()
                
                if (prevBoundary != java.text.BreakIterator.DONE) {
                    val charsToDelete = lastBoundary - prevBoundary
                    ic.deleteSurroundingText(charsToDelete, 0)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
        ic.endBatchEdit()
    }

    private fun handleEnterKey() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return

        // 1. Check for Multi-Line (Standard Enter)
        val isMultiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (isMultiLine) {
            ic.commitText("\n", 1)
            return
        }

        // 2. Perform Action (Go, Search, Send)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action)
        } else {
            // Fallback
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    // --- AI Feature Logic ---

    private fun handleAiAction(actionType: String) {
        if (isSensitiveInputField()) {
            Toast.makeText(this, "AI disabled for sensitive fields", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAiActionTs < 250L) return
        lastAiActionTs = now

        val originalText = getCurrentText()
        if (originalText.isBlank()) return
        if (originalText.length > 8000) {
            Toast.makeText(this, "Text is too long for AI action. Select a portion first.", Toast.LENGTH_LONG).show()
            return
        }

        aiActionJob?.cancel(CancellationException("Replaced by a newer AI action"))

        // Lightweight feedback
        Toast.makeText(this, "Thinking...", Toast.LENGTH_SHORT).show()

        aiActionJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val resultText = imeBackendClient.requestKeyboardAction(
                    originalText = originalText,
                    appContext = currentAppContext,
                    actionType = actionType,
                    selectedTone = selectedTone,
                ).getOrNull().orEmpty()

                if (resultText.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (actionType == "complete") {
                            smartAppend(originalText, resultText)
                        } else {
                            replaceFullText(resultText)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StreminiIME, "AI action failed. Check your connection.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Smartly appends text, preventing duplication.
     * Example: Input="Hello wor", AI="Hello world" -> Appends "ld"
     * Example: Input="Hello", AI=" world" -> Appends " world"
     */
    private fun smartAppend(currentText: String, aiCompletion: String) {
        val ic = currentInputConnection ?: return
        
        // 1. If AI returned the exact full sentence including input
        if (aiCompletion.startsWith(currentText)) {
            val newPart = aiCompletion.substring(currentText.length)
            ic.commitText(newPart, 1)
            return
        }

        // 2. Overlap detection (Suffix of current matching Prefix of AI)
        // Check overlap of up to 20 characters
        val checkLen = min(currentText.length, 20)
        val suffix = currentText.takeLast(checkLen)
        
        // Find if the AI text starts with any part of the suffix
        // e.g. Suffix="lo world", AI="world is big" -> Overlap "world"
        var overlapIndex = -1
        for (i in 0 until checkLen) {
            val sub = suffix.substring(i)
            if (aiCompletion.startsWith(sub)) {
                overlapIndex = sub.length
                break // Found largest overlap
            }
        }

        if (overlapIndex > 0) {
            val newPart = aiCompletion.substring(overlapIndex)
            ic.commitText(newPart, 1)
        } else {
            // No obvious overlap, just append (add space if needed)
            val textToInsert = if (!currentText.endsWith(" ") && !aiCompletion.startsWith(" ")) {
                " $aiCompletion"
            } else {
                aiCompletion
            }
            ic.commitText(textToInsert, 1)
        }
    }

    private fun replaceFullText(newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
            val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
            ic.deleteSurroundingText(before.length, after.length)
            ic.commitText(newText, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun getCurrentText(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
        return "$before$after"
    }

    // --- UX Feedback ---

    private fun feedback(view: View) {
        // Haptic feedback removed as per user request
    }

    private fun handleClipboardPaste() {
        val ic = currentInputConnection ?: return
        if (!isClipboardHistoryEnabled()) {
            Toast.makeText(this, "Clipboard history is disabled", Toast.LENGTH_SHORT).show()
            return
        }
        val clip = clipboardManager.primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotBlank()) {
            saveClipboardEntry(text)
            ic.commitText(text, 1)
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleClipboardCopy() {
        val ic = currentInputConnection ?: return
        if (!isClipboardHistoryEnabled()) {
            Toast.makeText(this, "Clipboard history is disabled", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val textToCopy = if (selected.isNotBlank()) selected else getCurrentText()

        if (textToCopy.isBlank()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val clip = ClipData.newPlainText("Stremini", textToCopy)
        clipboardManager.setPrimaryClip(clip)
        saveClipboardEntry(textToCopy)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showClipboardPanel(panelContainer: FrameLayout, closeFn: () -> Unit) {
        panelContainer.removeAllViews() // clear any previous panel content
        val view = layoutInflater.inflate(R.layout.clipboard_panel, null)
        val container = view.findViewById<LinearLayout>(R.id.clipboard_items_container)
        val closeBtn = view.findViewById<View>(R.id.btn_close_panel)

        closeBtn.setOnClickListener { 
            updateClipboardIcon() // Update icon state when panel closes
            closeFn() 
        }

        val history = getClipboardHistory()
        if (history.isEmpty()) {
            // Empty state with icon and text
            val emptyLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            
            val emptyIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_content_paste_off)
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(48),
                    dpToPx(48)
                ).apply {
                    setMargins(0, 0, 0, dpToPx(12))
                }
                alpha = 0.5f
            }
            
            val emptyText = TextView(this).apply {
                text = "No items yet\nCopy text to see it here"
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                lineHeight = dpToPx(20)
            }
            
            emptyLayout.addView(emptyIcon)
            emptyLayout.addView(emptyText)
            container.addView(emptyLayout)
        } else {
            history.forEachIndexed { index, itemText ->
                val itemView = layoutInflater.inflate(R.layout.clipboard_item_view, null)
                val tv = itemView.findViewById<TextView>(R.id.clipboard_item_text)
                val iv = itemView.findViewById<ImageView>(R.id.clipboard_item_image)
                val icon = itemView.findViewById<ImageView>(R.id.clipboard_item_icon)

                // Check if item is a URI (image/screenshot)
                if (itemText.startsWith("content://") || itemText.startsWith("file://")) {
                    try {
                        val uri = Uri.parse(itemText)
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                decoder.setTargetSize(120, 120)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        }
                        iv.setImageBitmap(bitmap)
                        iv.visibility = View.VISIBLE
                        tv.visibility = View.GONE
                        icon.visibility = View.GONE
                    } catch (e: Exception) {
                        tv.text = "[Image — tap to paste]"
                        iv.visibility = View.GONE
                        icon.visibility = View.GONE
                    }
                } else {
                    tv.text = itemText
                    iv.visibility = View.GONE
                }

                // Tap → paste & close
                itemView.setOnClickListener {
                    if (itemText.startsWith("content://") || itemText.startsWith("file://")) {
                        pasteImage(Uri.parse(itemText), "image/png")
                    } else {
                        currentInputConnection?.commitText(itemText, 1)
                    }
                    closeFn()
                }

                container.addView(itemView)
            }
        }

        panelContainer.addView(view)
        panelContainer.visibility = View.VISIBLE
    }

    /**
     * Saves a new clipboard entry as the most-recent item.
     *
     * Ring-buffer behaviour:
     *   1. If the exact same text is already in the list, remove the old copy (no duplicates).
     *   2. Insert the new item at position 0 (front = most recent).
     *   3. If the list now exceeds CLIPBOARD_HISTORY_LIMIT (5), drop items from the end
     *      (oldest) until the list is exactly 5 items long.
     *   4. Persist the updated list to SharedPreferences as a JSON array.
     */
    private fun saveClipboardEntry(value: String) {
        val sanitized = value.trim()
        if (sanitized.isBlank()) return

        val updated = getClipboardHistory().toMutableList().apply {
            removeAll { it == sanitized }   // de-duplicate: remove any existing copy
            add(0, sanitized)               // insert at front (most recent)
            // Trim to the hard cap — oldest entries (tail) are dropped
            if (size > CLIPBOARD_HISTORY_LIMIT) {
                subList(CLIPBOARD_HISTORY_LIMIT, size).clear()
            }
        }

        encryptedPrefs.putString(CLIPBOARD_HISTORY_KEY, JSONArray(updated).toString())
    }

    private fun getClipboardHistory(): List<String> {
        val raw = encryptedPrefs.getString(CLIPBOARD_HISTORY_KEY) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i)
                    if (item.isNotBlank()) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isClipboardHistoryEnabled(): Boolean =
        sharedPrefs.getBoolean(CLIPBOARD_HISTORY_ENABLED_KEY, true) // default ON

    /**
     * Updates the clipboard icon visibility based on history state.
     * Shows ic_content_paste_off (grayed out) when empty,
     * Shows ic_clipboard (white) when there are items.
     */
    private fun updateClipboardIcon() {
        val history = getClipboardHistory()
        val hasItems = history.isNotEmpty()
        
        clipboardIconOn?.visibility = if (hasItems) View.VISIBLE else View.GONE
        clipboardIconOff?.visibility = if (hasItems) View.GONE else View.VISIBLE
    }
    
    /**
     * Helper function to convert dp to pixels
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun isSensitiveInputField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun animateKey(view: View, isPressed: Boolean) {
        val scale = if (isPressed) 0.92f else 1.0f
        val duration = if (isPressed) 50L else 80L

        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .start()
    }

    private fun showKeyboardSwitcher() {
        val switched = switchToNextInputMethod(false)
        if (!switched) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    private fun updateShiftState() {
        if (isSymbolsMode) {
            // In symbols mode, shift visual shows =\< toggle state
            shiftKeyView?.alpha = if (isMoreSymbolsMode) 1.0f else 0.6f
            return
        }
        // Letters mode: update letter case and shift icon
        if (isCapsLock) {
            shiftKeyView?.alpha = 1.0f
            shiftKeyView?.text = "⇧"
            shiftKeyView?.setBackgroundColor(Color.LTGRAY) // Visual cue for caps lock
        } else if (isShiftOn) {
            shiftKeyView?.alpha = 1.0f
            shiftKeyView?.text = "⇧"
            shiftKeyView?.setBackgroundColor(Color.TRANSPARENT)
        } else {
            shiftKeyView?.alpha = 0.5f
            shiftKeyView?.text = "⇧"
            shiftKeyView?.setBackgroundColor(Color.TRANSPARENT)
        }
        letterKeyViews.forEach { tv ->
            val keyId = tv.id
            val base = alphaNumericKeyMap[keyId] ?: return@forEach
            if (base.length == 1 && base[0].isLetter()) {
                tv.text = if (isShiftOn) base.uppercase() else base.lowercase()
            }
        }
    }

    private fun refreshAutoCap() {
        if (isSymbolsMode || isCapsLock) {
            updateKeyboardLabels() // just labels
            return
        }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        
        // Use standard Android caps mode detection
        val caps = if (ic != null) {
            ic.getCursorCapsMode(info.inputType)
        } else 0
        
        val shouldShift = caps != 0
        if (shouldShift != isShiftOn) {
            isShiftOn = shouldShift
            updateKeyboardLabels()
        } else {
            updateKeyboardLabels()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Refresh auto-cap state when cursor moves
        refreshAutoCap()
    }

    private fun updateKeyboardLabels() {
        // Number keys (1-0) always show digits regardless of mode
        val numberKeyIds = listOf(R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
            R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0)

        alphaNumericKeyMap.keys.forEach { id ->
            val view = keyTextViewCache[id] ?: keyboardRootView?.findViewById<TextView>(id)
            
            // Always keep number row as numbers
            if (id in numberKeyIds) {
                val base = alphaNumericKeyMap[id]
                if (view != null && base != null) view.text = base
                return@forEach
            }

            val finalText: String? = if (isSymbolsMode) {
                val map = if (isMoreSymbolsMode) moreSymbolsKeyMap else symbolsKeyMap
                map[id] ?: alphaNumericKeyMap[id]
            } else {
                val base = alphaNumericKeyMap[id]
                if (base != null && isShiftOn && base.length == 1 && base[0].isLetter()) {
                    base.uppercase()
                } else {
                    base
                }
            }

            if (view != null && !finalText.isNullOrEmpty()) {
                view.text = finalText
            }
        }

        // Bottom-left key label
        symbolsKeyView?.text = if (isSymbolsMode) "ABC" else "?123"

        // Shift key label
        if (isSymbolsMode) {
            shiftKeyView?.text = if (isMoreSymbolsMode) "?123" else "=\\<"
        } else {
            shiftKeyView?.text = "⇧"
        }

        shiftKeyView?.isEnabled = true
        updateShiftState()
        updateEnterKeyLabel(currentInputEditorInfo)
    }

    private fun updateEnterKeyLabel(info: EditorInfo?) {
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val label = when (action) {
            EditorInfo.IME_ACTION_GO -> "⏎"
            EditorInfo.IME_ACTION_SEARCH -> "⏎"
            EditorInfo.IME_ACTION_SEND -> "⏎"
            EditorInfo.IME_ACTION_NEXT -> "⏎"
            EditorInfo.IME_ACTION_DONE -> "⏎"
            else -> "⏎"
        }
        enterKeyView?.text = label
    }

    private fun handleUndo() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.undo)
    }


    private fun updateKeyboardModeUi() {
        val root = keyboardRootView ?: return
        val toolsRow = root.findViewById<LinearLayout>(R.id.universal_tools_container)
        val quickActions = root.findViewById<LinearLayout>(R.id.quick_actions_container)
        val aiChipsScrollView = quickActions?.parent as? HorizontalScrollView
        
        val settings = root.findViewById<View>(R.id.action_settings)
        val mic = root.findViewById<View>(R.id.key_voice)
        val aiToggle = root.findViewById<View>(R.id.action_ai_toggle)
        val clipboard = root.findViewById<View>(R.id.clipboard_icon_container)
        val emoji = root.findViewById<View>(R.id.action_toolbar_emoji)
        
        // Root container of emoji's FrameLayout
        val emojiContainer = emoji?.parent as? View

        if (isAiFeatureMode) {
            // AI Mode: Cluster persistent tools on left, show AI chips on right
            toolsRow?.layoutParams?.width = LinearLayout.LayoutParams.WRAP_CONTENT
            (toolsRow?.layoutParams as? LinearLayout.LayoutParams)?.weight = 0f
            toolsRow?.weightSum = 0f
            
            // Hide non-AI tools to make space
            clipboard?.visibility = View.GONE
            emojiContainer?.visibility = View.GONE
            mic?.visibility = View.GONE // Keep it simple: Settings + AI toggle + Chips
            
            // Show AI chips scrollable
            aiChipsScrollView?.visibility = View.VISIBLE
            aiChipsScrollView?.layoutParams?.width = 0
            (aiChipsScrollView?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
            
            // Adjust individual icons within the clustered toolsRow
            for (i in 0 until (toolsRow?.childCount ?: 0)) {
                val child = toolsRow?.getChildAt(i)
                val clp = child?.layoutParams as? LinearLayout.LayoutParams
                if (clp != null) {
                    clp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                    clp.weight = 0f
                    val padding = (8 * resources.displayMetrics.density).toInt()
                    clp.setMargins(0, 0, padding, 0)
                    child.layoutParams = clp
                }
            }
        } else {
            // Standard Mode: Spread 5 tools evenly across the FULL width
            toolsRow?.layoutParams?.width = 0
            (toolsRow?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
            toolsRow?.weightSum = 5f
            
            // Show all tools
            settings?.visibility = View.VISIBLE
            mic?.visibility = View.VISIBLE
            aiToggle?.visibility = View.VISIBLE
            clipboard?.visibility = View.VISIBLE
            emojiContainer?.visibility = View.VISIBLE
            
            // Hide AI chips
            aiChipsScrollView?.visibility = View.GONE
            
            // Set children to equally distribute
            for (i in 0 until (toolsRow?.childCount ?: 0)) {
                val child = toolsRow?.getChildAt(i)
                val clp = child?.layoutParams as? LinearLayout.LayoutParams
                if (clp != null) {
                    clp.width = 0
                    clp.weight = 1f
                    clp.setMargins(0, 0, 0, 0)
                    child.layoutParams = clp
                }
            }
        }
        
        // Show/Hide the chips content
        root.findViewById<View>(R.id.action_improve)?.visibility = if (isAiFeatureMode) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.action_complete)?.visibility = if (isAiFeatureMode) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.action_tone)?.visibility = if (isAiFeatureMode) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.action_translate)?.visibility = if (isAiFeatureMode) View.VISIBLE else View.GONE

        // Make sure everything is requested to layout
        toolsRow?.requestLayout()
        aiChipsScrollView?.requestLayout()
    }

    private fun updateKeyboardHeight() {
        val newHeightPx = if (isExpandedHeight) (56 * resources.displayMetrics.density).toInt() else (48 * resources.displayMetrics.density).toInt()
        keyTextViewCache.values.forEach { view ->
            view.layoutParams.height = newHeightPx
            view.requestLayout()
        }
    }

    private fun setupAiAction(root: View, id: Int, action: String) {
        root.findViewById<View>(id)?.setOnClickListener { 
            feedback(it)
            handleAiAction(action) 
        }
    }

    private fun translateCurrentText(targetLanguageCode: String, targetLanguageName: String) {
        val originalText = getCurrentText()
        if (originalText.isBlank()) {
            Toast.makeText(this, "Type something to translate", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Translating to $targetLanguageName...", Toast.LENGTH_SHORT).show()
        serviceScope.launch(Dispatchers.IO) {
            val translated = imeBackendClient.translateText(
                text = originalText,
                targetLanguage = targetLanguageCode
            ).getOrNull().orEmpty()

            withContext(Dispatchers.Main) {
                if (translated.isBlank()) {
                    Toast.makeText(this@StreminiIME, "Translation failed", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                replaceFullText(translated)
                Toast.makeText(this@StreminiIME, "Translated to $targetLanguageName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Detect App Context
        currentAppContext = when (info?.packageName) {
            "com.whatsapp", "com.facebook.orca" -> "messaging"
            "com.google.android.gm" -> "email"
            else -> "general"
        }
        updateEnterKeyLabel(info)
        refreshAutoCap()
        updateKeyboardModeUi()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Clean up any open panel so keyboard opens fresh next time
        val panelContainer = keyboardRootView?.findViewById<android.widget.FrameLayout>(R.id.panel_container)
        if (panelContainer != null) {
            panelContainer.removeAllViews()
            panelContainer.visibility = android.view.View.GONE
        }
        // Stop voice input if active
        if (isListening) {
            stopSpeechRecognition()
        }
        // Cancel any in-flight AI job
        aiActionJob?.cancel()
        aiActionJob = null
    }

    override fun onDestroy() {
        stopSpeechRecognition()
        releaseSoundEffects()
        // Cancel ALL pending handler callbacks (backspace repeat, speech retry,
        // dotSemicolon long-press, etc.) to prevent post-destroy crashes.
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
