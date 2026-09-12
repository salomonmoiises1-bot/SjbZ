package com.sjbz.aimp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

class VerticalSeekBar @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    init {
        rotation = 270f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        // Esto hace que solo se mueva el fader, no toda la pantalla
        val newProgress = max - (max * event.y / height).toInt()
        progress = newProgress.coerceIn(0, max)
        return true
    }
}
