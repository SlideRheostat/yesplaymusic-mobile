package com.litemusic.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.litemusic.Api
import com.litemusic.ImageLoader
import com.litemusic.PlayRecordItem
import com.litemusic.Playlist
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.player.PlayerService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 我的页。
 *
 * 竖屏：登录卡 + 我喜欢的音乐 + 可折叠的歌单/专辑/艺人分区（保持原样）。
 * 横屏：音乐库布局——顶部账号栏与我喜欢的音乐卡片整体可折叠，
 * 下方分类切换条（歌单/专辑/艺人/听歌排行）吸顶，点击分类自动收起大卡片。
 */
class MineFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private var playlists: List<Playlist> = emptyList()
    private var albums: List<Playlist> = emptyList()
    private var artists: List<Playlist> = emptyList()
    private var likedSongs: List<Song> = emptyList()
    private var likedId = 0L
    private var likedLoading = false
    private var playlistsExpanded = true
    private var albumsExpanded = true
    private var artistsExpanded = true
    private var albumsFailed = false
    private var albumsError: String? = null
    private var playlistsFailed = false
    private var artistsFailed = false

    // ---- 横屏音乐库状态 ----
    private var currentTab = TAB_PLAYLISTS
    private var rankMode = RANK_WEEK
    private var weekRanks: List<PlayRecordItem> = emptyList()
    private var allRanks: List<PlayRecordItem> = emptyList()
    private var rankLoading = false
    private var rankLoaded = false
    private var rankError: String? = null

    private var appBar: AppBarLayout? = null
    private var likedCard: View? = null
    private var likedCover: ImageView? = null
    private var likedCount: TextView? = null
    private var likedPlay: ImageButton? = null
    private var heroSongs: RecyclerView? = null
    private var tabItems: LinearLayout? = null
    private var tabIndicator: View? = null
    private var tabCenters = FloatArray(0)
    private var tabActiveColor = 0
    private var tabInactiveColor = 0

    // 横屏：四个分类页各自一个 RecyclerView，由 ViewPager2 承载
    private var pager: ViewPager2? = null
    private var playlistPage: LibPageAdapter? = null
    private var albumPage: LibPageAdapter? = null
    private var artistPage: LibPageAdapter? = null
    private var rankPage: LibPageAdapter? = null
    private var fakeDragging = false
    private var dragProgress = 0f
    private var heroSongsShown: List<Song> = emptyList()

    /** 点击分类时的程序化滚动：期间由独立动画驱动指示条，忽略页面回调，避免闪现 */
    private var programmaticScroll = false
    private var indicatorAnimator: ValueAnimator? = null

    /** 横屏卡片网格列数：与首页使用同一套按宽度计算的规则，不再写死 */
    private var landColumns = 2

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        return inflater.inflate(R.layout.fragment_mine, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (isLandscape) {
            // 与首页推荐歌单相同的列数算法：按屏幕宽度约 180dp 一张卡片
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            landColumns = (widthDp / 180).toInt().coerceAtLeast(2)
            setupLibrary(view)
        } else {
            recycler = view.findViewById(R.id.recycler)
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = MineAdapter()
        }
    }

    // ---------- 横屏音乐库骨架 ----------

    private fun setupLibrary(view: View) {
        appBar = view.findViewById(R.id.app_bar)
        likedCard = view.findViewById(R.id.liked_card)
        likedCover = view.findViewById(R.id.liked_cover)
        likedCount = view.findViewById(R.id.liked_count)
        likedPlay = view.findViewById(R.id.liked_play)
        heroSongs = view.findViewById<RecyclerView>(R.id.hero_songs)?.apply {
            layoutManager = GridLayoutManager(requireContext(), HERO_COLUMNS)
            isNestedScrollingEnabled = false
            itemAnimator = null
        }

        likedCard?.setOnClickListener {
            if (Prefs.loggedIn) openLiked()
            else startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        likedPlay?.setOnClickListener {
            when {
                !Prefs.loggedIn -> startActivity(Intent(requireContext(), LoginActivity::class.java))
                likedSongs.isNotEmpty() -> PlayerService.play(requireContext(), likedSongs, 0)
            }
        }

        // 四个分类页：各自持有独立数据，由 ViewPager2 承载并跟手滚动
        playlistPage = LibPageAdapter(SECTION_PLAYLIST)
        albumPage = LibPageAdapter(SECTION_ALBUM)
        artistPage = LibPageAdapter(SECTION_ARTIST)
        rankPage = LibPageAdapter(SECTION_RANKING)

        pager = view.findViewById<ViewPager2>(R.id.library_pager)?.apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            isUserInputEnabled = false
            offscreenPageLimit = TAB_COUNT - 1
            adapter = LibraryPagerAdapter()
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int,
                ) {
                    dragProgress = position + positionOffset
                    // 点击切页时指示条由独立动画驱动，忽略页面回调，避免先闪到终点再回跳
                    if (!programmaticScroll) moveIndicatorByPage(position, positionOffset)
                }

                override fun onPageSelected(position: Int) {
                    if (currentTab != position) {
                        currentTab = position
                        updateTabColors()
                        if (position == TAB_RANKING && !rankLoaded) loadRanking()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_IDLE && programmaticScroll) {
                        programmaticScroll = false
                        indicatorAnimator?.cancel()
                        indicatorAnimator = null
                        updateTabColors()
                        moveIndicatorToCenter(currentTab)
                    }
                }
            })
        }

        val bar = view.findViewById<View>(R.id.library_tabs)
        tabItems = view.findViewById(R.id.library_tab_items)
        tabIndicator = view.findViewById(R.id.library_tab_indicator)
        tabActiveColor = MaterialColors.getColor(
            bar, com.google.android.material.R.attr.colorPrimary, 0
        )
        tabInactiveColor = MaterialColors.getColor(
            bar, com.google.android.material.R.attr.colorOnSurfaceVariant, 0
        )
        for (i in TAB_IDS.indices) {
            val item = view.findViewById<TextView>(TAB_IDS[i])
            item.setOnClickListener { selectLibraryTab(i, animate = true) }
            item.setOnTouchListener(tabTouchListener(bar))
        }
        updateTabColors()
        bar.post {
            refreshTabCenters()
            moveIndicatorByPage(pager?.currentItem ?: 0, 0f)
        }
        bar.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            // 仅在宽度变化（旋转/尺寸调整）时重新定位
            if (right - left != oldRight - oldLeft) {
                refreshTabCenters()
                moveIndicatorByPage(pager?.currentItem ?: 0, 0f)
            }
        }
    }

    /** 点击分类：指示条从当前位置平滑移动到目标，页面同步切页并收起上方大卡片 */
    private fun selectLibraryTab(index: Int, animate: Boolean) {
        if (!isAdded) return
        if (index == TAB_RANKING && !rankLoaded) loadRanking()
        val p = pager
        val currentPage = p?.currentItem ?: currentTab
        currentTab = index
        updateTabColors()
        if (p != null && currentPage != index) {
            if (animate) {
                programmaticScroll = true
                animateIndicatorTo(index)
            } else {
                programmaticScroll = false
                indicatorAnimator?.cancel()
                indicatorAnimator = null
                moveIndicatorToCenter(index)
            }
            p.setCurrentItem(index, animate)
        } else {
            programmaticScroll = false
            indicatorAnimator?.cancel()
            indicatorAnimator = null
            moveIndicatorToCenter(index)
        }
        // 点击分类：自动上滑略过我喜欢的音乐，让切换条吸顶
        appBar?.setExpanded(false, true)
    }

    private fun updateTabColors() {
        applyTabColors(currentTab)
    }

    private fun applyTabColors(selected: Int) {
        val v = view ?: return
        for (i in TAB_IDS.indices) {
            v.findViewById<TextView>(TAB_IDS[i]).setTextColor(
                if (i == selected) tabActiveColor else tabInactiveColor
            )
        }
    }

    private fun refreshTabCenters() {
        val items = tabItems ?: return
        if (items.childCount < TAB_COUNT) return
        val centers = FloatArray(TAB_COUNT)
        for (i in 0 until TAB_COUNT) {
            val child = items.getChildAt(i)
            centers[i] = child.left + child.width / 2f
        }
        tabCenters = centers
    }

    /** 根据 ViewPager2 的滚动进度移动指示条，点击切页时由独立动画接管 */
    private fun moveIndicatorByPage(position: Int, offset: Float) {
        if (programmaticScroll) return
        val indicator = tabIndicator ?: return
        if (tabCenters.size < TAB_COUNT || indicator.width == 0) return
        val from = position.coerceIn(0, TAB_COUNT - 1)
        val to = (from + 1).coerceAtMost(TAB_COUNT - 1)
        val center = tabCenters[from] + (tabCenters[to] - tabCenters[from]) * offset
        indicator.translationX = center - indicator.width / 2f
    }

    /** 指示条直接对齐某个分类（无动画） */
    private fun moveIndicatorToCenter(index: Int) {
        val indicator = tabIndicator ?: return
        if (tabCenters.size < TAB_COUNT || indicator.width == 0) return
        indicator.animate().cancel()
        indicator.translationX = tabCenters[index] - indicator.width / 2f
        indicator.scaleX = 1f
        indicator.scaleY = 1f
    }

    /** 点击切页时让指示条从当前位置平滑移动到目标，避免页面回调造成的闪现与回跳 */
    private fun animateIndicatorTo(index: Int) {
        val indicator = tabIndicator ?: return
        if (tabCenters.size < TAB_COUNT || indicator.width == 0) {
            moveIndicatorToCenter(index)
            return
        }
        val target = tabCenters[index] - indicator.width / 2f
        indicatorAnimator?.cancel()
        indicator.animate().cancel()
        indicatorAnimator = ValueAnimator.ofFloat(indicator.translationX, target).apply {
            duration = 260
            interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { indicator.translationX = it.animatedValue as Float }
            start()
        }
    }

    /**
     * 分类条拖拽：通过 ViewPager2 的 fakeDrag 让整个内容页跟手滚动，
     * 指示条由 onPageScrolled 同步移动；未超过 touchSlop 时交给 TextView 处理点击。
     */
    private fun tabTouchListener(bar: View): View.OnTouchListener {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var dragging = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lastX = e.rawX
                    dragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                        val p = pager
                        if (p != null && p.beginFakeDrag()) {
                            dragging = true
                            fakeDragging = true
                            // 拖拽接管时立即停止点击动画，指示条从当前实际位置继续
                            programmaticScroll = false
                            indicatorAnimator?.cancel()
                            indicatorAnimator = null
                            dragProgress = p.currentItem.toFloat()
                            lastX = e.rawX
                            tabIndicator?.animate()?.cancel()
                            tabIndicator?.scaleX = 1.15f
                            tabIndicator?.scaleY = 1.15f
                            // 避免 AppBarLayout 在横向拖拽时抢走触摸事件
                            bar.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    if (dragging) {
                        val delta = e.rawX - lastX
                        lastX = e.rawX
                        if (delta != 0f) {
                            val spacing = if (tabCenters.size > 1) {
                                tabCenters[1] - tabCenters[0]
                            } else 1f
                            val scale = if (spacing > 0f) bar.width / spacing else 1f
                            pager?.fakeDragBy(-delta * scale)
                        }
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        bar.parent?.requestDisallowInterceptTouchEvent(false)
                        endTabDrag(snap = true)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        bar.parent?.requestDisallowInterceptTouchEvent(false)
                        endTabDrag(snap = false)
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    /** 结束分类条拖拽：归零速度后吸附到最近页，取消时回到当前页 */
    private fun endTabDrag(snap: Boolean) {
        if (!fakeDragging) return
        fakeDragging = false
        tabIndicator?.animate()?.cancel()
        tabIndicator?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        val p = pager ?: return
        if (!p.isAttachedToWindow) return
        if (!snap) {
            p.endFakeDrag()
            p.setCurrentItem(currentTab, true)
            return
        }
        val nearest = dragProgress.roundToInt().coerceIn(0, TAB_COUNT - 1)
        // 先保持约 120ms 零位移，使 FakeDrag 记录的滑动速度归零，避免松手后甩到更远页
        val start = SystemClock.uptimeMillis()
        val freeze = object : Runnable {
            override fun run() {
                if (!isAdded || pager !== p || !p.isAttachedToWindow) return
                if (SystemClock.uptimeMillis() - start < 120) {
                    p.fakeDragBy(0f)
                    p.postDelayed(this, 20)
                } else {
                    p.endFakeDrag()
                    p.post {
                        if (p.currentItem != nearest) p.setCurrentItem(nearest, true)
                    }
                }
            }
        }
        freeze.run()
    }

    private fun pageAdapterFor(tab: Int): LibPageAdapter? = when (tab) {
        TAB_PLAYLISTS -> playlistPage
        TAB_ALBUMS -> albumPage
        TAB_ARTISTS -> artistPage
        else -> rankPage
    }

    private fun bindLandscapeHeader() {
        val v = view ?: return
        val loggedIn = Prefs.loggedIn
        val avatar = v.findViewById<ImageView>(R.id.avatar)
        val nickname = v.findViewById<TextView>(R.id.nickname)
        val sub = v.findViewById<TextView>(R.id.sub)
        val settings = v.findViewById<ImageButton>(R.id.btn_settings)
        if (loggedIn) {
            nickname.text = Prefs.nickname.ifEmpty { getString(R.string.user_default) }
            sub.text = if (Prefs.vipType > 0) getString(R.string.vip_member) else getString(R.string.my_library)
            ImageLoader.load(avatar, Prefs.avatar, 160)
        } else {
            nickname.text = getString(R.string.login)
            sub.text = getString(R.string.login_tip)
            avatar.setImageResource(R.drawable.ic_person)
        }
        v.findViewById<View>(R.id.header_root).setOnClickListener {
            startActivity(
                Intent(
                    requireContext(),
                    if (loggedIn) SettingsActivity::class.java else LoginActivity::class.java
                )
            )
        }
        settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    private fun bindLandscapeHero() {
        val songs = likedSongs.take(HERO_SONG_COUNT)
        likedCount?.text = getString(
            R.string.track_count,
            if (Prefs.loggedIn) likedSongs.size else 0
        )
        // 数据未变化时复用现有适配器，避免每次刷新都重建英雄区列表
        if (songs != heroSongsShown) {
            heroSongsShown = songs
            likedCover?.let { ImageLoader.load(it, likedSongs.firstOrNull()?.cover, 600) }
            heroSongs?.visibility = if (songs.isEmpty()) View.INVISIBLE else View.VISIBLE
            heroSongs?.adapter = HeroSongAdapter(songs)
        }
        val canPlay = Prefs.loggedIn && likedSongs.isNotEmpty()
        likedPlay?.isEnabled = canPlay
        likedPlay?.alpha = if (canPlay) 1f else 0.45f
    }

    // ---------- 数据刷新 ----------

    override fun onDestroyView() {
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        programmaticScroll = false
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (isLandscape) rankLoaded = false
        rebuild()
        if (Prefs.loggedIn) {
            val load = {
                if (isLandscape) loadLikedLibrary() else LikedStore.refresh { rebuild() }
                Api.userPlaylist(Prefs.userId) { r ->
                    if (!isAdded) return@userPlaylist
                    r.onSuccess { (id, list) ->
                        val changed = id != likedId
                        likedId = id
                        playlists = list
                        playlistsFailed = false
                        rebuild()
                        // 喜欢歌曲详情与完整列表的排序来源保持一致（同一个歌单详情）
                        if (isLandscape && id > 0 && (changed || likedSongs.isEmpty())) loadLikedLibrary()
                    }.onFailure {
                        playlistsFailed = true
                        rebuild()
                        if (isLandscape && likedSongs.isEmpty()) loadLikedFallback()
                    }
                }
                Api.albumSublist { r ->
                    if (!isAdded) return@albumSublist
                    r.onSuccess {
                        albums = it
                        albumsFailed = false
                        albumsError = null
                        rebuild()
                    }.onFailure {
                        albumsFailed = true
                        albumsError = it.message
                        rebuild()
                    }
                }
                Api.followedArtists(100, 0) { r ->
                    if (!isAdded) return@followedArtists
                    r.onSuccess {
                        artists = it
                        artistsFailed = false
                        rebuild()
                    }.onFailure {
                        artistsFailed = true
                        rebuild()
                    }
                }
                if (isLandscape && currentTab == TAB_RANKING) loadRanking()
            }
            // userId 缺失（老版本遗留）时先拉账号信息自愈，否则歌单/红心接口拿不到数据
            if (Prefs.userId == 0L) Api.account { load() } else load()
        } else {
            LikedStore.clear()
            playlists = emptyList()
            albums = emptyList()
            artists = emptyList()
            likedSongs = emptyList()
            likedId = 0
            weekRanks = emptyList()
            allRanks = emptyList()
            rankLoaded = false
            rankError = null
            albumsFailed = false
            playlistsFailed = false
            artistsFailed = false
        }
    }

    /**
     * 横屏喜欢歌曲：直接取「我喜欢的音乐」歌单详情，保证与点进去后的列表顺序完全一致。
     * 歌单 id 未知时等 userPlaylist 返回后再加载；详情失败则退化为 likelist + songDetail。
     */
    private fun loadLikedLibrary() {
        if (!isAdded) return
        if (!isLandscape) {
            LikedStore.refresh { rebuild() }
            return
        }
        if (likedId <= 0) return
        if (likedLoading) return
        likedLoading = true
        Api.playlistDetail(likedId) { r ->
            likedLoading = false
            if (!isAdded) return@playlistDetail
            val songs = r.getOrNull()?.second
            if (songs == null) {
                loadLikedFallback()
            } else {
                likedSongs = songs
                LikedStore.replace(songs.map { it.id })
                rebuild()
            }
        }
    }

    private fun loadLikedFallback() {
        if (likedLoading) return
        likedLoading = true
        Api.likelist { r ->
            if (!isAdded) {
                likedLoading = false
                return@likelist
            }
            val ids = r.getOrNull()
            if (ids == null) {
                likedLoading = false
                rebuild()
                return@likelist
            }
            LikedStore.replace(ids)
            if (ids.isEmpty()) {
                likedLoading = false
                likedSongs = emptyList()
                rebuild()
                return@likelist
            }
            Api.songDetail(ids.take(300)) { r2 ->
                likedLoading = false
                if (!isAdded) return@songDetail
                likedSongs = r2.getOrNull().orEmpty()
                rebuild()
            }
        }
    }

    /** 听歌排行：分别拉最近一周与所有时间，切换时不再请求 */
    private fun loadRanking() {
        if (!isAdded || rankLoading || !Prefs.loggedIn) return
        rankLoading = true
        rankError = null
        rebuild()
        Api.playRecord(1) week@{ week ->
            if (!isAdded) return@week
            weekRanks = week.getOrNull().orEmpty()
            if (week.isFailure) rankError = week.exceptionOrNull()?.message
            Api.playRecord(0) all@{ all ->
                if (!isAdded) return@all
                allRanks = all.getOrNull().orEmpty()
                if (all.isFailure && rankError == null) rankError = all.exceptionOrNull()?.message
                rankLoading = false
                rankLoaded = true
                rebuild()
            }
        }
    }

    private fun rebuild() {
        // 异步回调可能在 Fragment 已 detach 后到达，此时 getString 会抛 IllegalStateException
        if (!isAdded) return
        if (isLandscape) {
            bindLandscapeHeader()
            bindLandscapeHero()
            playlistPage?.submit(buildPlaylistRows())
            albumPage?.submit(buildAlbumRows())
            artistPage?.submit(buildArtistRows())
            rankPage?.submit(buildRankRows())
        } else {
            rows = buildRows()
            recycler.adapter?.notifyDataSetChanged()
        }
    }

    // 行模型：type 0=头部 1=分区标题 2=条目 3=提示 4=排行筛选 5=排行行
    private var rows: List<Row> = emptyList()

    /** 竖屏：原有分区式列表 */
    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows.add(Row(TYPE_HEADER))
        if (playlists.isNotEmpty() || Prefs.loggedIn) {
            rows.add(Row(TYPE_SECTION, getString(R.string.my_playlists), section = SECTION_PLAYLIST))
            if (playlistsExpanded) {
                for (p in playlists) rows.add(Row(TYPE_ITEM, item = p))
                if (playlists.isEmpty()) {
                    rows.add(Row(TYPE_HINT, title = if (playlistsFailed) getString(R.string.load_failed) else getString(R.string.no_playlists)))
                }
            }
        }
        if (albums.isNotEmpty() || Prefs.loggedIn) {
            rows.add(Row(TYPE_SECTION, getString(R.string.my_albums), section = SECTION_ALBUM))
            if (albumsExpanded) {
                for (a in albums) rows.add(Row(TYPE_ITEM, item = a))
                if (albums.isEmpty()) {
                    rows.add(Row(TYPE_HINT, title = if (albumsFailed) (albumsError ?: getString(R.string.load_failed)) else getString(R.string.no_albums)))
                }
            }
        }
        if (artists.isNotEmpty() || Prefs.loggedIn) {
            rows.add(Row(TYPE_SECTION, getString(R.string.followed_artists), section = SECTION_ARTIST))
            if (artistsExpanded) {
                for (a in artists) rows.add(Row(TYPE_ITEM, item = a, section = SECTION_ARTIST))
                if (artists.isEmpty()) {
                    rows.add(Row(TYPE_HINT, title = if (artistsFailed) getString(R.string.load_failed) else getString(R.string.no_followed_artists)))
                }
            }
        }
        return rows
    }

    private fun buildPlaylistRows(): List<Row> {
        val rows = ArrayList<Row>(playlists.size + 1)
        for (p in playlists) rows.add(Row(TYPE_ITEM, item = p, section = SECTION_PLAYLIST))
        if (rows.isEmpty()) {
            rows.add(Row(TYPE_HINT, title = if (playlistsFailed) getString(R.string.load_failed) else getString(R.string.no_playlists)))
        }
        return rows
    }

    private fun buildAlbumRows(): List<Row> {
        val rows = ArrayList<Row>(albums.size + 1)
        for (a in albums) rows.add(Row(TYPE_ITEM, item = a, section = SECTION_ALBUM))
        if (rows.isEmpty()) {
            rows.add(Row(TYPE_HINT, title = if (albumsFailed) (albumsError ?: getString(R.string.load_failed)) else getString(R.string.no_albums)))
        }
        return rows
    }

    private fun buildArtistRows(): List<Row> {
        val rows = ArrayList<Row>(artists.size + 1)
        for (a in artists) rows.add(Row(TYPE_ITEM, item = a, section = SECTION_ARTIST))
        if (rows.isEmpty()) {
            rows.add(Row(TYPE_HINT, title = if (artistsFailed) getString(R.string.load_failed) else getString(R.string.no_followed_artists)))
        }
        return rows
    }

    private fun buildRankRows(): List<Row> {
        val rows = ArrayList<Row>()
        if (!Prefs.loggedIn) {
            rows.add(Row(TYPE_HINT, title = getString(R.string.not_logged_in)))
            return rows
        }
        rows.add(Row(TYPE_RANK_FILTER))
        when {
            rankLoading -> rows.add(Row(TYPE_HINT, title = getString(R.string.loading)))
            else -> {
                val list = rankList()
                if (list.isEmpty()) {
                    rows.add(Row(TYPE_HINT, title = rankError ?: getString(R.string.no_play_record)))
                } else {
                    for ((i, item) in list.withIndex()) {
                        rows.add(Row(TYPE_RANK, rankItem = item, rankIndex = i))
                    }
                }
            }
        }
        return rows
    }

    private fun rankList(): List<PlayRecordItem> =
        if (rankMode == RANK_WEEK) weekRanks else allRanks

    // ---------- 适配器 ----------

    /** ViewPager2 的 4 个页面，每页一个独立 RecyclerView */
    private inner class LibraryPagerAdapter :
        RecyclerView.Adapter<LibraryPagerAdapter.PageVH>() {

        inner class PageVH(val recycler: RecyclerView) : RecyclerView.ViewHolder(recycler)

        override fun getItemCount(): Int = TAB_COUNT
        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val rv = RecyclerView(parent.context)
            rv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            rv.clipToPadding = false
            val density = parent.resources.displayMetrics.density
            rv.setPadding(0, (8 * density).toInt(), 0, (96 * density).toInt())
            val page = pageAdapterFor(viewType)
            if (viewType == TAB_RANKING) {
                rv.layoutManager = LinearLayoutManager(parent.context)
            } else {
                rv.layoutManager = GridLayoutManager(parent.context, landColumns).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int =
                            if (page?.rows?.getOrNull(position)?.type == TYPE_ITEM) 1 else landColumns
                    }
                }
            }
            rv.adapter = page
            return PageVH(rv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) = Unit
    }

    /** 单个分类页的列表适配器：卡片网格与排行列表共用 */
    private inner class LibPageAdapter(private val section: Int) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var rows: List<Row> = emptyList()
            private set

        fun submit(list: List<Row>) {
            if (rows == list) return
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HINT -> HintVH(inf.inflate(R.layout.item_hint, parent, false) as TextView)
                TYPE_RANK_FILTER -> RankFilterVH(inf.inflate(R.layout.item_rank_filter, parent, false))
                TYPE_RANK -> RankVH(inf.inflate(R.layout.item_rank, parent, false))
                else -> CollectionCardVH(inf.inflate(R.layout.item_playlist, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val row = rows[pos]
            when (h) {
                is HintVH -> {
                    h.text.text = row.title
                    h.text.setOnClickListener {
                        if (section == SECTION_RANKING) {
                            rankLoaded = false
                            loadRanking()
                        } else {
                            refresh()
                        }
                    }
                }
                is RankFilterVH -> bindRankFilter(h)
                is RankVH -> bindRankRow(h, row)
                is CollectionCardVH -> bindCollectionCard(h, row)
            }
        }
    }

    private inner class MineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_mine_header, parent, false))
                TYPE_SECTION -> SectionVH(inf.inflate(R.layout.item_section_header, parent, false))
                TYPE_HINT -> HintVH(inf.inflate(R.layout.item_hint, parent, false) as TextView)
                else -> RowVH(inf.inflate(R.layout.item_song, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val row = rows[pos]
            when (h) {
                is HeaderVH -> bindHeader(h)
                is SectionVH -> bindSection(h, row)
                is HintVH -> {
                    h.text.text = row.title
                    h.text.setOnClickListener { refresh() }
                }
                is RowVH -> bindItem(h, row)
            }
        }
    }

    private class HintVH(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)

    // ---------- 竖屏头部 ----------

    private fun bindHeader(h: HeaderVH) {
        val loggedIn = Prefs.loggedIn
        // 登录后账号信息与退出登录已移至设置页顶部，“我的”页不再重复展示
        h.cardUser.visibility = if (loggedIn) View.GONE else View.VISIBLE
        if (!loggedIn) {
            h.nickname.text = getString(R.string.login)
            h.sub.text = getString(R.string.login_tip)
            h.action.text = getString(R.string.login)
            h.action.setOnClickListener { startActivity(Intent(requireContext(), LoginActivity::class.java)) }
            h.avatar.setImageResource(R.drawable.ic_person)
        }
        h.favoritesRow.visibility = if (loggedIn) View.VISIBLE else View.GONE
        h.favCount.text = if (loggedIn) "${LikedStore.size()}" else ""
        h.favoritesRow.setOnClickListener {
            if (loggedIn) openLiked() else startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        h.settingsRow.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
    }

    private fun openLiked() {
        if (likedId > 0) {
            startActivity(
                PlaylistActivity.launch(
                    requireContext(), likedId, getString(R.string.my_favorites), hideSub = true
                )
            )
        } else {
            startActivity(PlaylistActivity.liked(requireContext()))
        }
    }

    // ---------- 分区 ----------

    private fun bindSection(h: SectionVH, row: Row) {
        h.title.text = row.title
        val expanded = when (row.section) {
            SECTION_PLAYLIST -> playlistsExpanded
            SECTION_ALBUM -> albumsExpanded
            else -> artistsExpanded
        }
        val count = when (row.section) {
            SECTION_PLAYLIST -> playlists.size
            SECTION_ALBUM -> albums.size
            else -> artists.size
        }
        h.count.text = if (count > 0) "$count" else ""
        h.chevron.animate().cancel()
        h.chevron.animate()
            .rotation(if (expanded) 0f else -90f)
            .setDuration(250)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .start()
        h.root.setOnClickListener {
            when (row.section) {
                SECTION_PLAYLIST -> playlistsExpanded = !playlistsExpanded
                SECTION_ALBUM -> albumsExpanded = !albumsExpanded
                else -> artistsExpanded = !artistsExpanded
            }
            rebuild()
        }
    }

    // ---------- 竖屏条目 ----------

    private fun bindItem(h: RowVH, row: Row) {
        val p = row.item!!
        h.name.text = p.name
        if (row.section == SECTION_ARTIST) {
            h.artist.text = if (p.albumCount > 0) getString(R.string.album_count, p.albumCount)
            else getString(R.string.artist)
        } else if (p.subtitle != null) {
            h.artist.text = "${p.subtitle} · ${getString(R.string.track_count, p.trackCount)}"
        } else {
            h.artist.text = getString(R.string.playlist_meta, p.trackCount, HomeFragment.formatCount(p.playCount))
        }
        ImageLoader.load(h.cover, p.cover, 120)
        h.like.visibility = View.GONE
        h.itemView.setOnClickListener {
            val ctx = requireContext()
            when (row.section) {
                SECTION_ARTIST -> startActivity(ArtistActivity.launch(ctx, p.id, p.name))
                else -> startActivity(if (p.subtitle != null) PlaylistActivity.album(ctx, p.id, p.name)
                else PlaylistActivity.launch(ctx, p.id, p.name))
            }
        }
    }

    // ---------- 横屏卡片网格 ----------

    private fun bindCollectionCard(h: CollectionCardVH, row: Row) {
        val p = row.item ?: return
        h.name.text = p.name
        val badge = when (row.section) {
            SECTION_ARTIST -> if (p.albumCount > 0) getString(R.string.album_count, p.albumCount) else ""
            SECTION_ALBUM -> if (p.trackCount > 0) getString(R.string.track_count, p.trackCount) else ""
            else -> if (p.playCount > 0) HomeFragment.formatCount(p.playCount) else ""
        }
        h.count.text = badge
        h.count.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        ImageLoader.load(h.cover, p.cover, 300)
        h.itemView.setOnClickListener {
            val ctx = requireContext()
            when (row.section) {
                SECTION_ARTIST -> startActivity(ArtistActivity.launch(ctx, p.id, p.name))
                SECTION_ALBUM -> startActivity(PlaylistActivity.album(ctx, p.id, p.name))
                else -> startActivity(PlaylistActivity.launch(ctx, p.id, p.name))
            }
        }
    }

    // ---------- 听歌排行 ----------

    private fun bindRankFilter(h: RankFilterVH) {
        h.group.check(if (rankMode == RANK_WEEK) R.id.btn_week else R.id.btn_all)
        h.week.setOnClickListener {
            if (rankMode != RANK_WEEK) {
                rankMode = RANK_WEEK
                rebuild()
            }
        }
        h.all.setOnClickListener {
            if (rankMode != RANK_ALL) {
                rankMode = RANK_ALL
                rebuild()
            }
        }
    }

    private fun bindRankRow(h: RankVH, row: Row) {
        val item = row.rankItem ?: return
        h.rank.text = "${row.rankIndex + 1}"
        h.name.text = item.song.name
        h.artist.text = item.song.artists
        h.count.text = getString(R.string.play_count_times, item.playCount)
        ImageLoader.load(h.cover, item.song.cover, 120)
        h.itemView.setOnClickListener {
            val list = rankList().map { it.song }
            if (row.rankIndex in list.indices) {
                PlayerService.play(it.context, list, row.rankIndex)
            }
        }
    }

    // ---------- ViewHolder ----------

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val cardUser: View = v.findViewById(R.id.card_user)
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val nickname: TextView = v.findViewById(R.id.nickname)
        val sub: TextView = v.findViewById(R.id.sub)
        val action: MaterialButton = v.findViewById(R.id.btn_action)
        val favoritesRow: LinearLayout = v.findViewById(R.id.row_favorites)
        val favCount: TextView = v.findViewById(R.id.fav_count)
        val settingsRow: View = v.findViewById(R.id.row_settings)
    }

    private class CollectionCardVH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    private class RankFilterVH(v: View) : RecyclerView.ViewHolder(v) {
        val group: MaterialButtonToggleGroup = v.findViewById(R.id.rank_filter)
        val week: MaterialButton = v.findViewById(R.id.btn_week)
        val all: MaterialButton = v.findViewById(R.id.btn_all)
    }

    private class RankVH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.rank)
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val artist: TextView = v.findViewById(R.id.artist)
        val count: TextView = v.findViewById(R.id.play_count)
    }

    private class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.section_root)
        val title: TextView = v.findViewById(R.id.section_title)
        val count: TextView = v.findViewById(R.id.section_count)
        val chevron: ImageView = v.findViewById(R.id.section_chevron)
    }

    private class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val artist: TextView = v.findViewById(R.id.artist)
        val like: ImageView = v.findViewById(R.id.btn_like)
    }

    private class HeroSongVH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val artist: TextView = v.findViewById(R.id.artist)
    }

    private inner class HeroSongAdapter(private val items: List<Song>) :
        RecyclerView.Adapter<HeroSongVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroSongVH =
            HeroSongVH(LayoutInflater.from(parent.context).inflate(R.layout.item_song_compact, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: HeroSongVH, position: Int) {
            val song = items[position]
            h.name.text = song.name
            h.artist.text = song.artists
            ImageLoader.load(h.cover, song.cover, 120)
            h.itemView.setOnClickListener {
                val idx = likedSongs.indexOfFirst { it.id == song.id }
                if (idx >= 0) PlayerService.play(it.context, likedSongs, idx)
            }
        }
    }

    private companion object {
        /** 分类条四个文字项，顺序与内容页签一致 */
        val TAB_IDS = intArrayOf(
            R.id.tab_item_playlists,
            R.id.tab_item_albums,
            R.id.tab_item_artists,
            R.id.tab_item_ranking,
        )
        const val TAB_COUNT = 4

        /** 英雄区右侧歌曲网格列数 */
        const val HERO_COLUMNS = 2

        /** 英雄区最多展示的歌曲数（4 行 × 2 列） */
        const val HERO_SONG_COUNT = 8

        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_ITEM = 2
        const val TYPE_HINT = 3
        const val TYPE_RANK_FILTER = 4
        const val TYPE_RANK = 5

        const val SECTION_PLAYLIST = 1
        const val SECTION_ALBUM = 2
        const val SECTION_ARTIST = 3
        const val SECTION_RANKING = 4

        const val TAB_PLAYLISTS = 0
        const val TAB_ALBUMS = 1
        const val TAB_ARTISTS = 2
        const val TAB_RANKING = 3

        const val RANK_WEEK = 0
        const val RANK_ALL = 1
    }
}

/** 页面行模型：type 0=头部 1=分区标题 2=条目 3=提示 4=排行筛选 5=排行行 */
private data class Row(
    val type: Int,
    val title: String = "",
    val item: Playlist? = null,
    val section: Int = 0,
    val rankItem: PlayRecordItem? = null,
    val rankIndex: Int = 0,
)
