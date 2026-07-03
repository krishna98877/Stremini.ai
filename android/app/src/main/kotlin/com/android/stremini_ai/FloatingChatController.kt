package com.android.stremini_ai

class FloatingChatController(
    private val onShow: () -> Unit,
    private val onHide: () -> Unit
) {
    private var isVisible = false

    fun toggle() {
        if (isVisible) hide() else show()
    }

    fun show() {
        if (isVisible) return
        onShow()
        isVisible = true
    }

    fun hide() {
        if (!isVisible) return
        onHide()
        isVisible = false
    }

    fun setVisible(visible: Boolean) {
        if (visible == isVisible) return
        if (visible) onShow() else onHide()
        isVisible = visible
    }
}
