package com.Android.stremini_ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.*

/**
 * Clean, Gboard-style panels for Emoji, Language, and Tools grid.
 * Uses the keyboard's existing dark theme (#000000 bg, #1A1A1A cards etc.).
 */
object KeyboardPanels {

    // ─── Emoji Panel ───────────────────────────────────────────

    private val emojiCategories = linkedMapOf(
        "😊" to "Recent",
        "😀" to "Smileys",
        "👋" to "People",
        "🐱" to "Animals",
        "🍕" to "Food",
        "⚽" to "Activity",
        "🚗" to "Travel",
        "💡" to "Objects",
        "❤️" to "Symbols"
    )

    private val emojiData = mapOf(
        "Recent" to listOf("😀","😂","🥰","😎","🤔","😭","👍","🙏","❤️","✨","🔥","🎉","😊","🥺","🙌","💀","😤","🤣","🥲","🫡"),
        "Smileys" to listOf("😀","😃","😄","😁","😆","🥹","😅","😂","🤣","🥲","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😚","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🫢","🫣","🤫","🤔","🫡","🤐","🤨","😐","😑","😶","🫥","😏","😒","🙄","😬","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🥵","🥶","🥴","😵","🤯","🤠","🥳","🥸","😎","🤓","🧐","😕","🫤","😟","🙁","☹️","😮","😯","😲","😳","🥺","🥹","😦","😧","😨","😰","😥","😢","😭","😱","😖","😣","😞","😓","😩","😫","🥱","🫠","🫨","🫦","🫧"),
        "People" to listOf("👋","🤚","🖐️","✋","🖖","🫱","🫲","🫳","🫴","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝️","🫵","👍","👎","✊","👊","🤛","🤜","👏","🙌","🫶","👐","🤲","🤝","🙏","✍️","💅","🤳","💪","🦾","🦿","🦵","🦶","👂","🦻","👃","🧠","🫀","🫁","🦷","🦴","👀","👁️","👅","👄"),
        "Animals" to listOf("🐱","🐶","🐭","🐹","🐰","🦊","🐻","🐼","🐻‍❄️","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧","🐦","🐤","🐣","🐥","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝","🪱","🐛","🦋","🐌","🐞","🐜","🪰","🪲","🪳","🦂","🐢","🐍","🦎","🦖","🦕"),
        "Food" to listOf("🍕","🍔","🍟","🌭","🥪","🌮","🌯","🫔","🥙","🧆","🥚","🍳","🥘","🍲","🫕","🥣","🥗","🍿","🧈","🧂","🥫","🍱","🍘","🍙","🍚","🍛","🍜","🍝","🍠","🍢","🍣","🍤","🍥","🥮","🍡","🥟","🥠","🥡","🦀","🦞","🦐","🦑","🦪","🍦","🍧","🍨","🍩","🍪","🎂","🍰","🧁","🥧","🍫","🍬","🍭","🍮","🍯","🍼","🥛","☕","🍵","🍶","🍾","🍷","🍸","🍹","🍺","🍻","🥂","🥃","🥤","🧋","🧃","🧉","🧊","🥢","🍽️","🍴","🥄"),
        "Activity" to listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍","🏏","🪃","🥅","⛳","🪁","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌","🎿","⛷️","🏂","🪂","🏋️","🤸","⛹️","🤺","🏅","🎖️","🏵️","🎫","🎫","🎭","🎨","🎬","🎤","🎧","🎼","🎹","🥁","🎷","🎺","🎸"),
        "Travel" to listOf("🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🏍️","🛵","🚲","🛴","🛺","🚔","🚍","🚘","🚖","🛞","🚡","🚠","🚟","🚃","🚋","🚞","🚝","🚄","🚅","🚈","🚂","🚆","🚇","🚊","🚉","✈️","🛫","🛬","🛩️","💺","🚀","🛸","🌌","🗿","🗽","🗼","🏰","🏟️","🏔️","🌋","🏕️","🏖️","🏜️","🏝️","🏡"),
        "Objects" to listOf("💡","🔦","🏮","🪔","📱","💻","⌨️","🖥️","🖨️","🖱️","🖲️","🕹️","🗜️","💿","💾","📀","📼","📷","📸","📹","🎥","📽️","🎞️","📞","☎️","📟","📠","📺"," radio","🎙️","🎚️","🎛️","🧭","⏱️","⏲️","⏰","🕰️","⌛","⏳","📡"," battery"," plug","💰","🪙","💴","💵","💶","💷","💸","💳","🧾","💹","✉️","📧","📩","📤","📦","📫","📅","🗑️","📉","📈","📊","📕","📗","📘","📙","📚","📖","🔖","🧷","🔗","📎","📏","📌","📍","✂️","🖊️","🖋️","✒️","🖌️","🖍️","📝","🔍","🔎","🔏","🔐","🔒","🔓","🔨","🪓","⛏️","⚒️","🛠️","🗡️","⚔️"),
        "Symbols" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❤️‍🔥","❤️‍🩹","💕","💞","💓","💗","💖","💝","💘","💟","☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","☦️","🛐","⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑","♒","♓","🆔","⚛️","✴️","🔱","📛","🔰","⭕","✅","☑️","✔️","❌","❎","➕","➖","➗","✖️","♾️","‼️","⁉️","❓","❔","❕","❗","〰️","💱","💲","⚕️","♻️","⚜️","🔅","🔆","🔱","🧿","🪬","🆘","🅰️","🅱️","🆕","🆖","🆗","🆙","🆒","🆓"),
    )

    private var recentEmojis = mutableListOf("😀","😂","🥰","😎","🤔","😭","👍","🙏","❤️","✨","🔥","🎉","😊","🥺","🙌","💀","😤","🤣","🥲","🫡","🫠","🫨","🫶","🫰","🧿")

    fun addRecentEmoji(emoji: String) {
        recentEmojis.remove(emoji) // remove if exists
        recentEmojis.add(0, emoji) // add to front
        if (recentEmojis.size > 30) {
            recentEmojis.removeAt(recentEmojis.size - 1)
        }
    }

    fun showEmojiPanel(context: Context, parent: FrameLayout, ic: InputConnection?, closeCallback: (() -> Unit)? = null) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        // Root container
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(222)
            )
        }

        // Scroll area for emojis
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val emojiGrid = GridLayout(context).apply {
            columnCount = 8
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(emojiGrid)

        // Category tabs at bottom
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll.addView(tabRow)

        fun loadCategory(catKey: String) {
            emojiGrid.removeAllViews()
            val items = if (catKey == "Recent") recentEmojis else emojiData[catKey] ?: return
            items.forEach { emoji ->
                val tv = TextView(context).apply {
                    text = emoji
                    textSize = 28f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(46)
                        height = dp(50)
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    }
                    setOnClickListener { 
                        ic?.commitText(emoji, 1)
                        if (catKey != "Recent") {
                            addRecentEmoji(emoji)
                        }
                    }
                }
                emojiGrid.addView(tv)
            }
        }

        // Build category tabs
        emojiCategories.forEach { (icon, name) ->
            val tab = TextView(context).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#222222"))
                    cornerRadius = dp(12).toFloat()
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(dp(3), 0, dp(3), 0)
                layoutParams = lp
                setOnClickListener {
                    // Highlight selected
                    for (i in 0 until tabRow.childCount) {
                        val child = tabRow.getChildAt(i)
                        (child.background as? GradientDrawable)?.setColor(Color.parseColor("#222222"))
                    }
                    (background as? GradientDrawable)?.setColor(Color.parseColor("#444444"))
                    loadCategory(name)
                }
            }
            tabRow.addView(tab)
        }

        // Add close button (⌨️)
        val closeBtn = TextView(context).apply {
            text = "⌨️"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = dp(12).toFloat()
            }
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp
            setOnClickListener {
                if (closeCallback != null) {
                    closeCallback()
                } else {
                    parent.removeAllViews()
                    parent.visibility = View.GONE
                }
            }
        }
        tabRow.addView(closeBtn, 0)

        // Add Kaomoji tab switch next to close button
        val kaomojiTab = TextView(context).apply {
            text = "(^_^)" // Kaomoji symbol
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = dp(12).toFloat()
            }
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp
            setOnClickListener {
                showKaomojiPanel(context, parent, ic, closeCallback)
            }
        }
        tabRow.addView(kaomojiTab, 1)

        root.addView(scrollView)
        root.addView(tabScroll)
        parent.addView(root)

        // Load "Recent" by default
        loadCategory("Recent")
        // Highlight first real tab (index 2 since 0 is close btn, 1 is kaomoji btn)
        if (tabRow.childCount > 2) {
            (tabRow.getChildAt(2).background as? GradientDrawable)?.setColor(Color.parseColor("#444444"))
        }
    }

    // ─── Kaomoji Panel (Emoji and more like Win11) ─────────────

    private val kaomojiData = mapOf(
        "Happy & Smile" to listOf("(^▽^)", "(´∀｀)", "(*^ω^*)", "(≧▽≦)", "٩(ˊᗜˋ*)و", "(๑˃ᴗ˂)", "^_^", "(>ᴗ<)"),
        "Sad & Crying" to listOf("(T_T)", "(;_;)", "(TωT)", "｡ﾟ(ﾟ´Д｀ﾟ)ﾟ｡", "(╥﹏╥)", "(ಥ﹏ಥ)", "(ノД`)", "q(′_′q)"),
        "Surprise & Shock" to listOf("(ﾟoﾟ;)", "(ﾟДﾟ;)", "Σ(°△°|||)", "ﾉｼ", "＼(ºoº)＼", "(⊙_◎)", "(ﾟロﾟ;)", "!? o(>< )o"),
        "Angry & Upset" to listOf("(>_<)", "(¬_¬)", "(｀ヘ´)", "ヽ(｀Д´)ﾉ", "(＃`Д´)", "(╬ Ò﹏Ó)", "Σ(▼□▼メ)"),
        "Love & Romance" to listOf("(♡‿♡)", "(灬º‿º灬)♡", "♥(ˆ⌣ˆ)ღ", "(*≧ω≦)", "(✿♥‿♥)", "૮(˃̵ڡ˂̵)ა", "♡(｡- ω -)"),
        "Laugh & Joke" to listOf("(≧▽≦)ノ", "(｀∀´)Ψ", "(￣▽￣)ノ"),
        "Shy & Embarrassed" to listOf("(〃▽〃)", "(*´ω｀*)", "(*/ω＼*)", "(*ﾉωﾉ)", "(//▽//)", "＼(￣▽￣;)／", "(*´∪｀*)"),
        "Confused & Thinking" to listOf("(?_?)", "(・_・;)", "(￣▽￣;)??", "(⊙﹏⊙✿)", "ヽ(゜Q゜*。)ノ"),
        "Animals" to listOf("(≧ω≦)", "ฅ(≧◡≦)ฅ", "(=^・ｪ・^=)", "(￣(ｴ)￣)"),
        "Food & Eating" to listOf("(灬º‿º灬)🍜", "(*^_^*)🍰", "(灬ºωº灬)🍩", "~=(￣▽￣)~", "🍔 (*´▽`)ﾉ🍕"),
        "Hand Gestures" to listOf("✌(◕‿◕✌)", "(✿◕‿◕)ノ", "(´∀｀)", "(*^▽^*)", "٩(ˊ〇ˋ*)و", "☆*:.｡. o(≧▽≦)o"),
        "Sleep & Tired" to listOf("(￣ω￣)｡oO", "(￣ー￣)ﾉ ｡", "￥(￣ー￣;", "( -_-)Zzz", "(´。＿⊃｀。)"),
        "Dance & Party" to listOf("\\(^o^)/", "(ノ≧∀≦)ノ", "(☆^O^☆)", "\\(^_^)/", "~♪(≧▽≦)♪~", "ヽ(★ω★)ノ"),
        "Cute & Baby" to listOf("(◕‿◕) ♡", "૮ ˶ᵔ ᵕ ᵔ˶ ა", "(˶˃ ᵕ ˂˶)", "(˵ •̀ ᴗ - ˵ )", "૮₍ ˶•͈ ᵕ •͈˶ ₎ა"),
        "Victorious & Strong" to listOf("( •̀ ω •́ )✧", "(ง •̀_•́)ง", "(ง’̀-‘́)ง", "＼(＾▽＾)／", "(￣▽￣) |", "ﾉ(｡◕‿◕)ﾉ")
    )

    fun showKaomojiPanel(context: Context, parent: FrameLayout, ic: InputConnection?, closeCallback: (() -> Unit)? = null) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(222)
            )
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        val title = TextView(context).apply {
            text = "Classic Ascii Emoticons"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                if (closeCallback != null) {
                    closeCallback()
                } else {
                    parent.removeAllViews()
                    parent.visibility = View.GONE
                }
            }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Scrollable content
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        kaomojiData.forEach { (catName, kaomojis) ->
            // Category Title
            val catTitle = TextView(context).apply {
                text = catName
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(4), dp(12), dp(4), dp(8))
            }
            list.addView(catTitle)

            // Grid for Kaomojis
            val grid = GridLayout(context).apply {
                columnCount = 3
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            kaomojis.forEach { kaomoji ->
                val btn = TextView(context).apply {
                    text = kaomoji
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E1E1E"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#333333"))
                    }
                    background = bg
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    
                    setOnClickListener {
                        ic?.commitText(kaomoji, 1)
                    }
                }
                grid.addView(btn)
            }
            list.addView(grid)
        }

        scrollView.addView(list)
        root.addView(scrollView)
        parent.addView(root)
    }

    // ─── Language Panel ────────────────────────────────────────

    fun showLanguagePanel(context: Context, parent: FrameLayout, onSelect: (String) -> Unit) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val languages = listOf(
            "English" to "EN", "Hindi" to "HI", "Spanish" to "ES",
            "French" to "FR", "German" to "DE", "Portuguese" to "PT",
            "Arabic" to "AR", "Japanese" to "JA"
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(222)
            )
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val title = TextView(context).apply {
            text = "Languages"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                parent.removeAllViews()
                parent.visibility = View.GONE
            }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Language list
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        languages.forEach { (name, code) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = dp(14).toFloat()
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(3), 0, dp(3))
                layoutParams = lp
                setOnClickListener {
                    onSelect(code)
                    parent.removeAllViews()
                    parent.visibility = View.GONE
                }
            }
            val label = TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badge = TextView(context).apply {
                text = code
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
            }
            row.addView(label)
            row.addView(badge)
            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)
        parent.addView(root)
    }

    // ─── Tools Grid Panel ──────────────────────────────────────

    data class ToolItem(val iconResId: Int, val label: String, val action: () -> Unit)

    fun showToolsPanel(context: Context, parent: FrameLayout, tools: List<ToolItem>) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(222)
            )
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val title = TextView(context).apply {
            text = "Tools"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        root.addView(header)

        // Grid
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val grid = GridLayout(context).apply {
            columnCount = 5
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }

        tools.forEach { tool ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1A1A"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#333333"))
                }
                background = bg
                setPadding(dp(4), dp(12), dp(4), dp(12))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(72)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener {
                    tool.action()
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(tool.iconResId)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                setColorFilter(Color.parseColor("#DDDDDD"))
            }
            val label = TextView(context).apply {
                text = tool.label
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            cell.addView(icon)
            cell.addView(label)
            grid.addView(cell)
        }

        scroll.addView(grid)
        root.addView(scroll)
        parent.addView(root)
    }

    // ─── Close Panel Helper ────────────────────────────────────

    fun closePanel(panelContainer: FrameLayout?, keyboardBody: View?) {
        panelContainer?.removeAllViews()
        panelContainer?.visibility = View.GONE
        keyboardBody?.visibility = View.VISIBLE
    }

    // ─── Tone Changer Panel (Professional Dark Themed) ─────────

    data class ToneOption(
        val key: String,
        val label: String,
        val icon: String,
        val description: String,
        val accentColor: String
    )

    fun showTonePanel(
        context: Context,
        parent: FrameLayout,
        currentTone: String,
        onSelect: (String) -> Unit
    ) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val tones = listOf(
            ToneOption("formal", "Formal", "👔", "Professional & polished", "#6C63FF"),
            ToneOption("friendly", "Friendly", "😊", "Warm & approachable", "#FF6B6B"),
            ToneOption("concise", "Concise", "🎯", "Direct & to the point", "#4ECDC4"),
            ToneOption("persuasive", "Persuasive", "💡", "Convincing marketing copy", "#FFD700"),
            ToneOption("funny", "Funny", "😂", "Witty with a sense of humor", "#FF8C00"),
            ToneOption("empathetic", "Empathetic", "🤝", "Supportive & polite", "#FF69B4"),
            ToneOption("confident", "Confident", "🔥", "Bold & assertive tone", "#FF4500"),
            ToneOption("social", "Social Media", "📱", "Trendy for social posts", "#1DA1F2")
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(222)
            )
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        val title = TextView(context).apply {
            text = "🎨 Tone Changer"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "⌨️"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                parent.removeAllViews()
                parent.visibility = View.GONE
            }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Tones list (Scrollable)
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        tones.forEach { tone ->
            val isSelected = tone.key == currentTone

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                val bg = GradientDrawable().apply {
                    setColor(if (isSelected) Color.parseColor("#2A2A2A") else Color.parseColor("#1E1E1E"))
                    cornerRadius = dp(14).toFloat()
                    if (isSelected) {
                        setStroke(dp(2), Color.parseColor(tone.accentColor))
                    } else {
                        setStroke(dp(1), Color.parseColor("#333333"))
                    }
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(3), 0, dp(3))
                layoutParams = lp
                setOnClickListener {
                    onSelect(tone.key)
                    parent.removeAllViews()
                    parent.visibility = View.GONE
                }
            }

            val iconView = TextView(context).apply {
                text = tone.icon
                textSize = 22f
                setPadding(0, 0, dp(14), 0)
            }

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val label = TextView(context).apply {
                text = tone.label
                setTextColor(if (isSelected) Color.parseColor(tone.accentColor) else Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }
            val desc = TextView(context).apply {
                text = tone.description
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            }
            textContainer.addView(label)
            textContainer.addView(desc)

            row.addView(iconView)
            row.addView(textContainer)

            if (isSelected) {
                val badge = TextView(context).apply {
                    text = "✓"
                    setTextColor(Color.parseColor(tone.accentColor))
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(8), 0, 0, 0)
                }
                row.addView(badge)
            }

            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)
        parent.addView(root)
    }

    // ─── Translator Panel (Professional Dark Themed) ───────────

    fun showTranslatePanel(
        context: Context,
        parent: FrameLayout,
        languages: List<Pair<String, String>>,
        onSelect: (code: String, name: String) -> Unit
    ) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(222)
            )
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        val title = TextView(context).apply {
            text = "🌍 Translate to..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "⌨️"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                parent.removeAllViews()
                parent.visibility = View.GONE
            }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Language rows
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Flag map for better visual
        val flags = mapOf(
            "en" to "🇺🇸", "es" to "🇪🇸", "fr" to "🇫🇷", "de" to "🇩🇪",
            "hi" to "🇮🇳", "pt" to "🇧🇷", "ar" to "🇸🇦", "ja" to "🇯🇵"
        )

        languages.forEach { (code, name) ->
            val flag = flags[code.lowercase()] ?: "🌐"
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = dp(14).toFloat()
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(3), 0, dp(3))
                layoutParams = lp
                setOnClickListener {
                    onSelect(code, name)
                    parent.removeAllViews()
                    parent.visibility = View.GONE
                }
            }
            val flagView = TextView(context).apply {
                text = flag
                textSize = 20f
                setPadding(0, 0, dp(12), 0)
            }
            val label = TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badge = TextView(context).apply {
                text = code.uppercase()
                setTextColor(Color.parseColor("#666666"))
                textSize = 12f
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2A2A"))
                    cornerRadius = dp(8).toFloat()
                }
                background = bg
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            row.addView(flagView)
            row.addView(label)
            row.addView(badge)
            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)
        parent.addView(root)
    }
    // ─── Language Panel ─────────────────────────────────────────

    data class LanguageItem(val name: String, val code: String, val flag: String)

    private val languageList = listOf(
        LanguageItem("English (US)", "en-US", "🇺🇸"),
        LanguageItem("Hindi (हिन्दी)", "hi-IN", "🇮🇳"),
        LanguageItem("Japanese (日本語)", "ja-JP", "🇯🇵"),
        LanguageItem("Urdu (اردो)", "ur-PK", "🇵🇰"),
        LanguageItem("Persian (فारसी)", "fa-IR", "🇮🇷"),
        LanguageItem("Spanish (Español)", "es-ES", "🇪🇸"),
        LanguageItem("French (Français)", "fr-FR", "🇫🇷"),
        LanguageItem("Arabic (العربية)", "ar-SA", "🇸🇦")
    )

    fun showLanguagePanel(context: Context, parent: FrameLayout, currentCode: String, onSelect: (String) -> Unit, closeCallback: (() -> Unit)? = null) {
        parent.removeAllViews()
        parent.visibility = View.VISIBLE
        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(222))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        val title = TextView(context).apply {
            text = "Voice Language"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { closeCallback?.invoke() ?: run { parent.visibility = View.GONE } }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // List Scroll
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        languageList.forEach { lang ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                val bg = GradientDrawable().apply {
                    if (lang.code == currentCode) setColor(Color.parseColor("#333333"))
                    cornerRadius = dp(8).toFloat()
                }
                background = bg
                setOnClickListener {
                    onSelect(lang.code)
                    closeCallback?.invoke()
                }
            }
            val flagTv = TextView(context).apply {
                text = lang.flag
                textSize = 20f
                setPadding(0, 0, dp(12), 0)
            }
            val nameTv = TextView(context).apply {
                text = lang.name
                setTextColor(Color.WHITE)
                textSize = 16f
            }
            row.addView(flagTv)
            row.addView(nameTv)
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll)
        parent.addView(root)
    }
}
