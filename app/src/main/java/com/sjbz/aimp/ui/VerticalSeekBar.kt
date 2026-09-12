package com.sjbz.aimp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    init {
        // Versión sin glitch - usa rotación del sistema
        rotation = 270f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        
        // Evita que el HorizontalScrollView te robe el dedo
        parent?.requestDisallowInterceptTouchEvent(true)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                // Mapea Y -> progress invertido (abajo = 0, arriba = max)
                var progress = (max - max * event.y / height).toInt()
                progress = progress.coerceIn(0, max)
                setProgress(progress)
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
