package com.sjbz.aimp.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

class VerticalSeekBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle) : AppCompatSeekBar(context, attrs, defStyleAttr) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(h, w, oldh, oldw) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { super.onMeasure(heightMeasureSpec, widthMeasureSpec); setMeasuredDimension(measuredHeight, measuredWidth) }
    override fun onDraw(c: Canvas) { c.rotate(-90f); c.translate(-height.toFloat(), 0f); super.onDraw(c) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                progress = (max - max * event.y / height).toInt().coerceIn(0, max)
                onSizeChanged(width, height, 0, 0)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
