package com.Android.stremini_ai

class BubbleController(
    private val onHide: () -> Unit,
    private val onShow: () -> Unit
) {
    private var isVisible = true

    fun setVisible(visible: Boolean) {
        isVisible = visible
    }

    fun toggle() {
        if (isVisible) {
            onHide()
            isVisible = false
        } else {
            onShow()
            isVisible = true
        }
    }
}
