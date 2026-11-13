package com.litemusic.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 底部导航容器：支持拖拽整条导航来切换页面（页面本身不响应滑动）。
 *
 * - 手指移动超过 touchSlop 才开始拖拽，未超过时子项照常响应点击；
 * - 竖屏沿水平轴、横屏 Navigation Rail 沿垂直轴计算位移；
 * - 回调只上报沿轴方向的手指位移，具体如何滚动页面由宿主决定。
 */
class NavDragLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 开始拖拽，返回 true 表示接受本次拖拽 */
    var onDragStart: (() -> Boolean)? = null

    /** 拖拽中的手指位移（正负均可能），单位 px */
    var onDrag: ((Float) -> Unit)? = null

    /** 手指离开屏幕（或事件被取消），由宿主完成吸附 */
    var onDragEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var lastAxis = 0f
    private var dragging = false
    private var active = false

    private val verticalAxis: Boolean
        get() = height > width

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastAxis = if (verticalAxis) ev.y else ev.x
                dragging = false
                active = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val moved = if (verticalAxis) {
                        abs(ev.y - downY)
                    } else {
                        abs(ev.x - downX)
                    }
                    if (moved > touchSlop) {
                        active = onDragStart?.invoke() ?: true
                        dragging = active
                        if (active) {
                            // 从越过 touchSlop 的当前位置开始计算位移，避免起手跳一截
                            lastAxis = if (verticalAxis) ev.y else ev.x
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
            }
        }
        return dragging && active
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (active) {
                    val cur = if (verticalAxis) ev.y else ev.x
                    val delta = cur - lastAxis
                    if (delta != 0f) {
                        lastAxis = cur
                        onDrag?.invoke(delta)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active) onDragEnd?.invoke()
                dragging = false
                active = false
                return true
            }
        }
        return active
    }
}
