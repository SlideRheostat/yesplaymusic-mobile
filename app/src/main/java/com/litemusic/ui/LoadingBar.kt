package com.litemusic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 风格的线性不确定加载指示器。
 *
 * 动画由 postOnAnimation 逐帧驱动，不依赖系统动画时长设置；
 * 活动指示段在轨道上移动并周期性地伸长、收缩。
 */
class LoadingBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@LoadingBar,
            com.google.android.material.R.attr.colorSurfaceVariant,
        )
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@LoadingBar,
            com.google.android.material.R.attr.colorPrimary,
        )
    }

    private var startTime = 0L

    private val frame = object : Runnable {
        override fun run() {
            if (visibility != VISIBLE) return
            invalidate()
            postOnAnimation(this)
        }
    }

    fun show() {
        startTime = SystemClock.uptimeMillis()
        visibility = VISIBLE
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    fun hide() {
        removeCallbacks(frame)
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            if (startTime == 0L) startTime = SystemClock.uptimeMillis()
            removeCallbacks(frame)
            postOnAnimation(frame)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f

        // 轨道
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, trackPaint)

        val cycle = 1800f
        val t = ((SystemClock.uptimeMillis() - startTime).toFloat() / cycle) % 1f
        val position = easeInOut(t)
        val segmentWidth = (0.15f + 0.35f * sin(PI.toFloat() * t)) * w
        val start = (-0.2f + 1.2f * position) * w

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.drawRoundRect(
            start,
            0f,
            start + segmentWidth,
            h,
            radius,
            radius,
            indicatorPaint,
        )
        canvas.restore()
    }

    private fun easeInOut(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
}
