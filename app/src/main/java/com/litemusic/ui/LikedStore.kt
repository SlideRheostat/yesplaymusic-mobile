package com.litemusic.ui

import com.litemusic.Api

/** 红心歌曲缓存（登录后有效） */
object LikedStore {
    private val ids = HashSet<Long>()

    fun refresh(cb: (() -> Unit)? = null) {
        if (!com.litemusic.Prefs.loggedIn) { cb?.invoke(); return }
        Api.likelist { r ->
            r.getOrNull()?.let {
                ids.clear()
                ids.addAll(it)
            }
            cb?.invoke()
        }
    }

    fun contains(id: Long): Boolean = ids.contains(id)

    fun setLiked(id: Long, liked: Boolean) {
        if (liked) ids.add(id) else ids.remove(id)
    }

    /** 批量替换红心 id（横屏音乐库一次性拉取完整列表后使用） */
    fun replace(all: Collection<Long>) {
        ids.clear()
        ids.addAll(all)
    }

    fun size(): Int = ids.size

    fun clear() {
        ids.clear()
    }
}
