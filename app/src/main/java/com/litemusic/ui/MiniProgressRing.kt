package com.litemusic.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors

/**
 * 迷你播放器播放键外圈：M3 确定型环形进度指示器。
 * 细描边（2dp）、圆角端帽、轨道与进度弧两端留缺口；
 * 颜色取主题 colorPrimary / colorSecondaryContainer，深浅色自动适配。
 */
class MiniProgressRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val stroke = 2f * resources.displayMetrics.density

    /** 进度弧与轨道之间的缺口（度） */
    private val gap = 14f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(
            this@MiniProgressRing,
            com.google.android.material.R.attr.colorSecondaryContainer,
            0,
        )
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(
            this@MiniProgressRing,
            com.google.android.material.R.attr.colorPrimary,
            0,
        )
    }

    private val bounds = RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** [value] 取 0..1；[animated] 为 true 时在 500ms 回调间隔内线性补间，视觉上连续前进 */
    fun setProgress(value: Float, animated: Boolean = true) {
        val target = value.coerceIn(0f, 1f)
        animator?.cancel()
        animator = null
        if (!animated) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 450
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= stroke) return
        val radius = (size - stroke) / 2f
        val cx = width / 2f
        val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val sweep = progress * 360f
        // 轨道：进度弧终点后留 gap，绕行到进度弧起点前再留 gap
        val trackSweep = 360f - sweep - gap * 2f
        if (trackSweep > 0f) {
            canvas.drawArc(bounds, -90f + sweep + gap, trackSweep, false, trackPaint)
        }
        if (sweep > 0f) {
            canvas.drawArc(bounds, -90f, sweep, false, indicatorPaint)
        }
    }
}
