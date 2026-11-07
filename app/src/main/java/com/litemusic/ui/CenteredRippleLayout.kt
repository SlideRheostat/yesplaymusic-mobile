package com.litemusic.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 导航项容器：涟漪热区固定为本视图（整个条目）的几何中心。
 * 系统在按下时会把背景热区更新为手指位置，重写此方法可保证无论按在条目何处，
 * 涟漪都从条目中心同心扩散，与"按钮"（整条点击区域）共圆心。
 */
class CenteredRippleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun drawableHotspotChanged(x: Float, y: Float) {
        super.drawableHotspotChanged(width / 2f, height / 2f)
    }
}