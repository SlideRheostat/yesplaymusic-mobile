package com.litemusic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.litemusic.Prefs
import com.litemusic.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private companion object {
        /** 拖拽/点按切换时指示器的放大比例 */
        const val NAV_SCALE = 0.18f
    }

    private lateinit var mini: MiniPlayerController
    private lateinit var pager: ViewPager2
    private lateinit var nav: NavDragLayout
    private lateinit var indicator: View
    /** 点按反馈用的更淡指示器，位于正式指示器下层 */
    private lateinit var indicatorHint: View

    /** 三个页签（顺序即导航栏顺序）：首页、搜索、我的 */
    private val tabIds = listOf(R.id.tab_home, R.id.tab_search, R.id.tab_mine)
    private lateinit var tabViews: List<View>

    private var selectedTab = 0

    /** 每个页签图标中心相对导航容器的坐标（竖屏为 X、横屏为 Y） */
    private var tabCenters = FloatArray(0)

    /** 竖屏底栏横向、横屏导航栏纵向 */
    private var landscape = false
    private var fakeDragging = false
    private var dragProgress = 0f

    /** 点按切换时，指示器在动画前半程放大、后半程恢复 */
    private var tapAnimating = false
    private var tapFrom = 0f
    private var tapTo = 0f
    private var hintVisible = false

    private var colorActive = 0
    private var colorOnSurface = 0
    private var colorInactive = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // M3 导航栏配色：选中图标 onSecondaryContainer、选中标签 onSurface、未选中 onSurfaceVariant
        colorActive = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF2C1512.toInt())
        colorOnSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0xFF201A19.toInt())
        colorInactive = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF534340.toInt())

        mini = MiniPlayerController(this, findViewById(android.R.id.content))

        nav = findViewById(R.id.bottom_nav)
        indicator = findViewById(R.id.tab_indicator)
        indicatorHint = findViewById(R.id.tab_indicator_hint)
        tabViews = tabIds.map { findViewById(it) }

        pager = findViewById(R.id.fragment_container)
        // 横屏页面上下切换，与纵向导航栏一致
        pager.orientation = if (landscape) {
            ViewPager2.ORIENTATION_VERTICAL
        } else {
            ViewPager2.ORIENTATION_HORIZONTAL
        }
        // 页面本身不响应滑动：只允许拖动底栏深色指示器来切换
        pager.isUserInputEnabled = false
        pager.adapter = TabPagerAdapter(this)
        // 三个页面常驻，切换时保留各自的滚动位置与已加载数据
        pager.offscreenPageLimit = 2

        for ((index, v) in tabViews.withIndex()) {
            v.setOnClickListener { onTabClicked(index) }
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int,
            ) {
                dragProgress = position + positionOffset
                // 指示器跟随手指在页签之间平移
                moveIndicator(position, positionOffset)
                // 点按切换：前半程放大、后半程恢复，缩放速率对称
                if (tapAnimating && !fakeDragging) {
                    val span = tapTo - tapFrom
                    if (span != 0f) {
                        val progress = ((position + positionOffset) - tapFrom) / span
                        val p = progress.coerceIn(0f, 1f)
                        val scale = 1f + NAV_SCALE * sin(PI * p).toFloat()
                        indicator.scaleX = scale
                        indicator.scaleY = scale
                    }
                }
            }

            override fun onPageSelected(position: Int) {
                selectedTab = tabForPosition(position)
                applyTabSelection(selectedTab)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    if (tapAnimating) {
                        tapAnimating = false
                        hideTapHint()
                    }
                    // 平滑归位：避免拖拽松手后的恢复动画尚未结束就被直接跳到 1 倍
                    indicator.animate().cancel()
                    indicator.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(150)
                        .start()
                }
            }
        })

        setupNavDrag()

        val initial = (savedInstanceState?.getInt("selectedTab") ?: 0)
            .coerceIn(0, tabIds.size - 1)
        selectedTab = initial
        pager.setCurrentItem(positionForTab(initial), false)
        applyTabSelection(initial)
        nav.post {
            updateTabCenters()
            moveIndicator(pager.currentItem, 0f)
        }
        nav.addOnLayoutChangeListener { _, left, top, right, bottom, ol, ot, or, ob ->
            if (right - left != or - ol || bottom - top != ob - ot) {
                updateTabCenters()
                moveIndicator(pager.currentItem, 0f)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Prefs.loggedIn) {
            // 先拉账号信息（自愈老版本遗留的 userId 缺失），完成后再刷新红心列表
            com.litemusic.Api.account { LikedStore.refresh() }
        }
    }

    /**
     * 底栏拖拽：手指拖动深色指示器时通过 fakeDrag 驱动页面同步移动，
     * 松手后吸附到最近的一页。
     */
    private fun setupNavDrag() {
        nav.onDragStart = {
            fakeDragging = pager.beginFakeDrag()
            if (fakeDragging) {
                dragProgress = pager.currentItem.toFloat()
                tapAnimating = false
                hideTapHint()
                // 拖拽期间指示器轻微放大
                indicator.animate().cancel()
                indicator.animate()
                    .scaleX(1f + NAV_SCALE).scaleY(1f + NAV_SCALE)
                    .setDuration(120)
                    .start()
            }
            fakeDragging
        }
        nav.onDrag = { delta ->
            if (fakeDragging) {
                // 让指示器精确跟手：页面位移按「页宽 / 页签间距」放大。
                // 页序与导航栏一致，手指向页签增大方向移动时页面向前切换。
                val spacing = tabSpacing()
                val pagerSize = if (landscape) pager.height else pager.width
                val scale = if (spacing > 0f) pagerSize / spacing else 1f
                pager.fakeDragBy(-delta * scale)
            }
        }
        nav.onDragEnd = {
            if (fakeDragging) {
                fakeDragging = false
                // 松手后指示器恢复原大小
                indicator.animate().cancel()
                indicator.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .start()
                val nearest = dragProgress.roundToInt().coerceIn(0, tabIds.size - 1)
                // 松手后先保持约 120ms 零位移，使 FakeDrag 记录的滑动速度归零，
                // 这样 endFakeDrag 只做吸附而不会按速度甩到更远的页面
                val start = android.os.SystemClock.uptimeMillis()
                val freeze = object : Runnable {
                    override fun run() {
                        if (android.os.SystemClock.uptimeMillis() - start < 120) {
                            pager.fakeDragBy(0f)
                            pager.postDelayed(this, 20)
                        } else {
                            pager.endFakeDrag()
                            pager.post {
                                if (pager.currentItem != nearest) {
                                    pager.setCurrentItem(nearest, true)
                                }
                            }
                        }
                    }
                }
                freeze.run()
            }
        }
    }

    /** 点按页签：目标位置先出现更淡的背景作为反馈，指示器随后平移并在动画中放大再恢复 */
    private fun onTabClicked(index: Int) {
        // 松手后的零速度吸附阶段仍处于 fakeDrag，此时点击交给吸附逻辑处理，避免冲突
        if (pager.isFakeDragging) return
        val target = positionForTab(index)
        if (target == pager.currentItem) return
        indicator.animate().cancel()
        // 以当前实际滚动位置为起点，连续点按或页面尚未吸附完成时缩放曲线不会跳变
        tapFrom = dragProgress
        tapTo = target.toFloat()
        tapAnimating = true
        showTapHint(index)
        pager.setCurrentItem(target, true)
    }

    /** 在目标页签下方显示更淡的指示器底色（位于正式指示器下层） */
    private fun showTapHint(tabIndex: Int) {
        if (tabCenters.size != tabIds.size) return
        val vertical = nav.height > nav.width
        val center = tabCenters[tabIndex]
        indicatorHint.animate().cancel()
        if (vertical) {
            indicatorHint.translationX = 0f
            indicatorHint.translationY = center - indicatorHint.height / 2f
        } else {
            indicatorHint.translationX = center - indicatorHint.width / 2f
            indicatorHint.translationY = 0f
        }
        indicatorHint.visibility = View.VISIBLE
        indicatorHint.alpha = 0f
        indicatorHint.animate().alpha(0.4f).setDuration(90).start()
        hintVisible = true
    }

    private fun hideTapHint() {
        if (!hintVisible) return
        hintVisible = false
        indicatorHint.animate().cancel()
        indicatorHint.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { indicatorHint.visibility = View.INVISIBLE }
            .start()
    }

    private fun tabSpacing(): Float {
        if (tabCenters.size < 2) return 0f
        return abs(tabCenters[1] - tabCenters[0])
    }

    /** 记录每个页签图标的中心坐标：指示器与图标对齐，而不是与整条页签（含文字）对齐 */
    private fun updateTabCenters() {
        if (nav.width == 0 || nav.height == 0) return
        val navLoc = IntArray(2)
        nav.getLocationOnScreen(navLoc)
        val vertical = nav.height > nav.width
        tabCenters = FloatArray(tabIds.size) { i ->
            val icon = findViewById<ImageView>(iconId(tabIds[i]))
            val loc = IntArray(2)
            icon.getLocationOnScreen(loc)
            if (vertical) {
                loc[1] - navLoc[1] + icon.height / 2f
            } else {
                loc[0] - navLoc[0] + icon.width / 2f
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selectedTab", selectedTab)
    }

    /** 页签索引 → ViewPager2 页号：顺序一致（首页、搜索、我的） */
    private fun positionForTab(tabIndex: Int): Int = tabIndex

    private fun tabForPosition(position: Int): Int = position

    /** M3 页签状态：选中图标着色 + 实心图标（首页），标签使用 onSurface */
    private fun applyTabSelection(selectedIndex: Int) {
        for ((index, tabId) in tabIds.withIndex()) {
            val selected = index == selectedIndex
            val icon = findViewById<ImageView>(iconId(tabId))
            // 竖屏紧凑底栏无标签；横屏 Navigation Rail 仍有标签
            val label: TextView? = findViewById(labelId(tabId))

            val newIcon = when {
                selected && tabId == R.id.tab_home -> R.drawable.ic_home
                tabId == R.id.tab_home -> R.drawable.ic_home_outlined
                tabId == R.id.tab_search -> R.drawable.ic_search_symbol
                else -> R.drawable.ic_account_circle
            }
            if (icon.tag != newIcon) {
                icon.tag = newIcon
                icon.setImageResource(newIcon)
            }
            icon.imageTintList = ColorStateList.valueOf(
                if (selected) colorActive else colorInactive
            )
            label?.setTextColor(if (selected) colorOnSurface else colorInactive)
        }
    }

    /**
     * 把指示器平移到 [position] 与 [position + 1] 之间的插值位置。
     * 竖屏底栏按 X 平移，横屏导航栏按 Y 平移；拖拽时 positionOffset 实时变化，形成跟手效果。
     */
    private fun moveIndicator(position: Int, positionOffset: Float) {
        if (tabCenters.size != tabIds.size) return
        val from = tabForPosition(position)
        val nextPos = (position + 1).coerceAtMost(tabIds.lastIndex)
        val to = tabForPosition(nextPos)
        val center = tabCenters[from] + (tabCenters[to] - tabCenters[from]) * positionOffset

        if (nav.height > nav.width) {
            indicator.translationY = center - indicator.height / 2f
        } else {
            indicator.translationX = center - indicator.width / 2f
        }
    }

    private fun iconId(tabId: Int): Int = when (tabId) {
        R.id.tab_home -> R.id.tab_home_icon
        R.id.tab_search -> R.id.tab_search_icon
        else -> R.id.tab_mine_icon
    }

    private fun labelId(tabId: Int): Int = when (tabId) {
        R.id.tab_home -> R.id.tab_home_label
        R.id.tab_search -> R.id.tab_search_label
        else -> R.id.tab_mine_label
    }

    /** 三个页签的页面，由 ViewPager2 托管；页序与导航栏一致 */
    private class TabPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> SearchFragment()
                else -> MineFragment()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mini.onStart()
    }

    override fun onStop() {
        mini.onStop()
        super.onStop()
    }
}
