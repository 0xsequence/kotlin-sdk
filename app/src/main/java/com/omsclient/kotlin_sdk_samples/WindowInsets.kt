package com.omsclient.kotlin_sdk_samples

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun View.applySafeDrawingInsets() {
    val initialPaddingLeft = paddingLeft
    val initialPaddingTop = paddingTop
    val initialPaddingRight = paddingRight
    val initialPaddingBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(
            initialPaddingLeft + insets.left,
            initialPaddingTop + insets.top,
            initialPaddingRight + insets.right,
            initialPaddingBottom + insets.bottom,
        )
        windowInsets
    }

    requestApplyInsetsWhenAttached()
}

private fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        requestApplyInsets()
        return
    }

    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.removeOnAttachStateChangeListener(this)
            view.requestApplyInsets()
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    })
}
