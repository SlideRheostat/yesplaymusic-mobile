package com.litemusic.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.litemusic.Prefs
import com.litemusic.R

class MainActivity : AppCompatActivity() {

    private lateinit var mini: MiniPlayerController
    private lateinit var tabs: List<Pair<Int, View>>
    private var selectedTab = 0

    private val tintAnims = HashMap<Int, ValueAnimator>()
    private val argb = ArgbEvaluator()

    private val decelerate: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val accelerate: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    private val standard: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var colorActive = 0
    private var colorOnSurface = 0
    private var colorInactive = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // M3 导航栏配色：选中图标 onSecondaryContainer、选中标签 onSurface、未选中 onSurfaceVariant
        colorActive = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF2C1512.toInt())
        colorOnSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0xFF201A19.toInt())
        colorInactive = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF534340.toInt())

        mini = MiniPlayerController(this, findViewById(android.R.id.content))

        tabs = listOf(
            R.id.tab_home to findViewById(R.id.tab_home),
            R.id.tab_search to findViewById(R.id.tab_search),
            R.id.tab_mine to findViewById(R.id.tab_mine),
        )
        for ((id, v) in tabs) {
            v.setOnClickListener { selectTab(id) }
        }
        selectedTab = -1
        selectTab(savedInstanceState?.getInt("selectedTab") ?: R.id.tab_home)

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selectedTab", selectedTab)
    }

    private fun selectTab(id: Int) {
        if (selectedTab == id && supportFragmentManager.findFragmentByTag("f$id") != null) return
        selectedTab = id
        for ((tid, _) in tabs) {
            animateTab(tid, tid == id)
        }
        showFragment(id)
    }

    /** M3 Navigation Bar 动效：active indicator 缩放淡入、图标实心/描边切换、图标颜色渐变 */
    private fun animateTab(tabId: Int, selected: Boolean) {
        val icon = findViewById<ImageView>(iconId(tabId))
        // 竖屏紧凑底栏无标签；横屏 Navigation Rail 仍有标签
        val label: TextView? = findViewById(labelId(tabId))
        val pill = findViewById<View>(pillId(tabId))

        val newIcon = when {
            selected && tabId == R.id.tab_home -> R.drawable.ic_home
            selected && tabId == R.id.tab_mine -> R.drawable.ic_person
            selected -> R.drawable.ic_search
            tabId == R.id.tab_home -> R.drawable.ic_home_outlined
            tabId == R.id.tab_mine -> R.drawable.ic_person_outlined
            else -> R.drawable.ic_search
        }
        val iconFrom = icon.imageTintList?.defaultColor ?: colorInactive
        if (icon.tag != newIcon) {
            icon.tag = newIcon
            icon.setImageResource(newIcon)
        }

        // active indicator：选中 300ms 减速放大，取消 150ms 加速缩回
        pill.animate().cancel()
        pill.animate()
            .alpha(if (selected) 1f else 0f)
            .scaleX(if (selected) 1f else 0.64f)
            .scaleY(if (selected) 1f else 0.64f)
            .setDuration(if (selected) 300 else 150)
            .setInterpolator(if (selected) decelerate else accelerate)
            .start()

        val iconTo = if (selected) colorActive else colorInactive
        val labelTo = if (selected) colorOnSurface else colorInactive
        val labelFrom = label?.currentTextColor ?: colorInactive
        tintAnims[tabId]?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = standard
            addUpdateListener {
                val f = it.animatedFraction
                icon.imageTintList = ColorStateList.valueOf(
                    argb.evaluate(f, iconFrom, iconTo) as Int
                )
                label?.setTextColor(argb.evaluate(f, labelFrom, labelTo) as Int)
            }
        }
        tintAnims[tabId] = anim
        anim.start()
    }

    /** 页签对应的 active indicator（胶囊）视图 id，作为涟漪圆心参照 */
    private fun pillId(tabId: Int): Int = when (tabId) {
        R.id.tab_home -> R.id.tab_home_pill
        R.id.tab_search -> R.id.tab_search_pill
        else -> R.id.tab_mine_pill
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

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.tab_home -> HomeFragment()
        R.id.tab_search -> SearchFragment()
        else -> MineFragment()
    }

    /** 切换页签：M3 Fade through（旧页立即隐藏，新页 250ms 淡入） */
    private fun showFragment(id: Int) {
        val fm = supportFragmentManager
        val tag = "f$id"
        val existing = fm.findFragmentByTag(tag)
        val target = existing ?: createFragment(id)
        if (existing == null) {
            fm.beginTransaction().add(R.id.fragment_container, target, tag).commitNow()
        }
        fm.beginTransaction().apply {
            for (frag in fm.fragments) {
                if (frag !== target && frag.isVisible) hide(frag)
            }
            show(target)
        }.commitNow()
        target.view?.let { v ->
            v.animate().cancel()
            v.alpha = 0f
            v.animate()
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(decelerate)
                .start()
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

    override fun onDestroy() {
        for (a in tintAnims.values) a.cancel()
        tintAnims.clear()
        super.onDestroy()
    }
}
