package com.litemusic

import org.json.JSONArray
import org.json.JSONObject

/** 业务接口定义（路径与参数对照 NeteaseCloudMusicApi 各 module） */
object Api {

    /** 个性化推荐歌单：实测该接口不支持任何分页（cursor/offset/page 均被忽略，
     *  响应也无 cursor 字段），重复调用固定同一批——仅用于首屏加载 */
    fun personalized(limit: Int, cb: (Result<List<Playlist>>) -> Unit) {
        val d = JSONObject()
        d.put("limit", limit)
        d.put("total", true)
        d.put("n", 1000)
        ApiClient.get("personalized/playlist", d) { r ->
            cb(r.map { it.optJSONArray("result")?.let { a -> Parser.playlists(a) } ?: emptyList() })
        }
    }

    /** 歌单广场（官方 top_playlist，eapi 真分页）：下拉刷新靠它按 offset 取"下一页"新内容。
     *  回传 (本页列表, 是否还有更多) */
    fun playlistSquare(limit: Int, offset: Int, cb: (Result<Pair<List<Playlist>, Boolean>>) -> Unit) {
        val d = JSONObject()
        d.put("cat", "全部")
        d.put("order", "hot")
        d.put("limit", limit)
        d.put("offset", offset)
        d.put("total", true)
        ApiClient.get("playlist/list", d) { r ->
            cb(r.map { json ->
                val list = json.optJSONArray("playlists")?.let { Parser.playlists(it) } ?: emptyList()
                list to json.optBoolean("more", false)
            })
        }
    }

    fun recommendSongs(cb: (Result<List<Song>>) -> Unit) {
        // eapi 通道（weapi 在部分网络环境被风控返回空响应）
        ApiClient.get("v3/discovery/recommend/songs", JSONObject()) { r ->
            cb(r.map { Parser.songs(it.optJSONObject("data")?.optJSONArray("dailySongs")) })
        }
    }

    fun search(keywords: String, limit: Int, offset: Int, cb: (Result<List<Song>>) -> Unit) {
        searchRaw(keywords, 1, limit, offset) { r ->
            cb(r.map { Parser.songs(it.optJSONObject("result")?.optJSONArray("songs")) })
        }
    }

    fun searchPlaylists(keywords: String, limit: Int, offset: Int, cb: (Result<List<Playlist>>) -> Unit) {
        searchRaw(keywords, 1000, limit, offset) { r ->
            cb(r.map { Parser.playlists(it.optJSONObject("result")?.optJSONArray("playlists")) })
        }
    }

    /** 专辑搜索（cloudsearch type=10），映射为 Playlist（subtitle 为歌手名） */
    fun searchAlbums(keywords: String, limit: Int, offset: Int, cb: (Result<List<Playlist>>) -> Unit) {
        searchRaw(keywords, 10, limit, offset) { r ->
            cb(r.map { json ->
                val arr = json.optJSONObject("result")?.optJSONArray("albums") ?: JSONArray()
                val out = ArrayList<Playlist>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = Playlist(
                        id = o.optLong("id"),
                        name = o.optString("name"),
                        cover = o.optString("picUrl").takeIf { it.isNotEmpty() },
                        playCount = 0,
                        trackCount = o.optInt("size"),
                        subtitle = o.optJSONObject("artist")?.optString("name") ?: "",
                    )
                    if (p.id > 0 && p.name.isNotEmpty()) out.add(p)
                }
                out
            })
        }
    }

    /** 歌手搜索（cloudsearch type=100），映射为 Playlist（albumCount 为专辑数） */
    fun searchArtists(keywords: String, limit: Int, offset: Int, cb: (Result<List<Playlist>>) -> Unit) {
        searchRaw(keywords, 100, limit, offset) { r ->
            cb(r.map { json ->
                val arr = json.optJSONObject("result")?.optJSONArray("artists") ?: JSONArray()
                val out = ArrayList<Playlist>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = Playlist(
                        id = o.optLong("id"),
                        name = o.optString("name"),
                        cover = o.optString("picUrl").takeIf { it.isNotEmpty() }
                            ?: o.optString("img1v1Url").takeIf { it.isNotEmpty() },
                        playCount = 0,
                        trackCount = o.optInt("musicSize"),
                        albumCount = o.optInt("albumSize"),
                    )
                    if (p.id > 0 && p.name.isNotEmpty()) out.add(p)
                }
                out
            })
        }
    }

    private fun searchRaw(keywords: String, type: Int, limit: Int, offset: Int, cb: (Result<JSONObject>) -> Unit) {
        val d = JSONObject()
        d.put("s", keywords)
        d.put("type", type)
        d.put("limit", limit)
        d.put("offset", offset)
        d.put("total", true)
        ApiClient.get("cloudsearch/pc", d) { cb(it) }
    }

    /** 歌单详情：tracks 不全时用 trackIds 补全（上限 300 首，照顾低配设备） */
    fun playlistDetail(id: Long, cb: (Result<Pair<Playlist, List<Song>>>) -> Unit) {
        val d = JSONObject()
        d.put("id", id)
        d.put("n", 1000)
        d.put("s", 8)
        ApiClient.get("v6/playlist/detail", d) { r ->
            if (r.isFailure) { cb(Result.failure(r.exceptionOrNull() ?: ApiException(-1, "网络异常"))); return@get }
            val json = r.getOrThrow()
            val pl = json.optJSONObject("playlist")
            if (pl == null) { cb(Result.failure(ApiException(-5, "歌单不存在"))); return@get }
            val playlist = Parser.playlist(pl)
            val tracks = Parser.songs(pl.optJSONArray("tracks"))
            val trackIds = pl.optJSONArray("trackIds")
            if (tracks.size >= tracksNeeded(trackIds)) {
                cb(Result.success(playlist to tracks))
            } else {
                val ids = ArrayList<Long>()
                if (trackIds != null) {
                    val max = minOf(trackIds.length(), 300)
                    for (i in tracks.size until max) {
                        ids.add(trackIds.optJSONObject(i)?.optLong("id") ?: continue)
                    }
                }
                if (ids.isEmpty()) {
                    cb(Result.success(playlist to tracks))
                } else {
                    songDetail(ids) { r2 ->
                        val more = r2.getOrNull() ?: emptyList()
                        cb(Result.success(playlist to (tracks + more)))
                    }
                }
            }
        }
    }

    private fun tracksNeeded(trackIds: JSONArray?): Int = trackIds?.length() ?: 0

    fun songDetail(ids: List<Long>, cb: (Result<List<Song>>) -> Unit) {
        if (ids.isEmpty()) {
            cb(Result.success(emptyList()))
            return
        }
        // ids 过多时服务端会截断/超时，按 100 首分批串行拉取后按原顺序合并
        val out = ArrayList<Song>(ids.size)
        fun fetch(from: Int) {
            val end = minOf(from + SONG_DETAIL_BATCH, ids.size)
            val c = JSONArray()
            for (i in from until end) c.put(JSONObject().put("id", ids[i]))
            val d = JSONObject()
            d.put("c", c.toString())
            d.put("ids", c.toString())
            ApiClient.get("v3/song/detail", d) { r ->
                r.onSuccess { json ->
                    out.addAll(Parser.songs(json.optJSONArray("songs")))
                    if (end < ids.size) fetch(end) else cb(Result.success(out))
                }.onFailure { cb(Result.failure(it)) }
            }
        }
        fetch(0)
    }

    private const val SONG_DETAIL_BATCH = 100

    fun songUrl(id: Long, cb: (Result<String?>) -> Unit) {
        val d = JSONObject()
        d.put("ids", "[$id]")
        d.put("level", Prefs.level)
        d.put("encodeType", if (Prefs.level == "lossless") "flac" else "mp3")
        ApiClient.get("song/enhance/player/url/v1", d) { r ->
            cb(r.map { json ->
                json.optJSONArray("data")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotEmpty() }
            })
        }
    }

    fun lyric(id: Long, cb: (Result<List<LyricLine>>) -> Unit) {
        val d = JSONObject()
        d.put("id", id)
        d.put("tv", -1)
        d.put("lv", -1)
        d.put("rv", -1)
        d.put("kv", -1)
        ApiClient.get("song/lyric", d) { r ->
            cb(r.map { json ->
                val main = LrcParser.parse(json.optJSONObject("lrc")?.optString("lyric"))
                val trans = LrcParser.parse(json.optJSONObject("tlyric")?.optString("lyric"))
                // 罗马音（日语歌曲注音来源），转为平假名后按时间对齐
                val roma = LrcParser.parse(json.optJSONObject("romalrc")?.optString("lyric"))
                mergeTranslation(main, trans, roma)
            })
        }
    }

    /**
     * 按时间合并翻译行与注音行。
     * 先做时间戳精确匹配（网易 tlyric/romalrc 通常与原文逐行同时间），
     * 剩余行再做"单调就近"配对：只允许向后消费且每行只用一次。
     * 这样某一行缺少译文时，后续译文不会被整体前移，避免整段歌词与翻译错位。
     */
    private fun mergeTranslation(main: List<LyricLine>, trans: List<LyricLine>, roma: List<LyricLine>): List<LyricLine> {
        val transMap = alignAux(main, trans)
        val romaMap = alignAux(main, roma)
        return main.mapIndexed { i, m ->
            LyricLine(m.time, m.text, transMap[i], Kana.romajiToHiragana(romaMap[i]))
        }
    }

    /** 制作信息/元数据行（作词、作曲、编曲、制作人…）：不作为译文对齐目标，
     *  避免这些与首句歌词共用 [00:00.00] 时间戳的行"抢走"本应属于首句的译文。 */
    private val CREDIT_RE = Regex(
        "^\\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|监制|監製|出品|策划|企划|统筹|統籌|" +
            "混音|录音|錄音|母带|母帶|和声|和音|吉他|贝斯|鼓手|弦乐|管乐|演唱|原唱|翻唱|" +
            "词曲|词|曲|视觉|美术|导演|原作|改编|OP|SP|PV)\\s*[:：/|｜]"
    )

    private fun isCreditLine(text: String): Boolean = CREDIT_RE.containsMatchIn(text)

    private fun alignAux(main: List<LyricLine>, aux: List<LyricLine>): Map<Int, String> {
        if (main.isEmpty() || aux.isEmpty()) return emptyMap()
        val out = HashMap<Int, String>()
        val used = BooleanArray(aux.size)
        // 制作信息行不参与译文匹配（其本身无需翻译）
        val credit = BooleanArray(main.size) { isCreditLine(main[it].text) }

        // 1) 时间戳精确匹配（跳过制作信息行；同一时间出现多行时按顺序依次消费）
        val byTime = HashMap<Long, MutableList<Int>>()
        aux.forEachIndexed { idx, line -> byTime.getOrPut(line.time) { ArrayList() }.add(idx) }
        for (i in main.indices) {
            if (credit[i]) continue
            val queue = byTime[main[i].time] ?: continue
            if (queue.isNotEmpty()) {
                val idx = queue.removeAt(0)
                out[i] = aux[idx].text
                used[idx] = true
            }
        }

        // 2) 剩余未匹配行：译文(tlyric/romalrc)通常与原文逐行平行（同序同量）。
        //    若剩余行数量一致，按序（rank）对齐——即便时间戳整体系统性漂移也不致整段错位；
        //    数量不一致（译文缺失/多出若干行）时退化为单调就近配对（容差 1 秒）。
        val remainMain = ArrayList<Int>(main.size)
        for (i in main.indices) if (!credit[i] && !out.containsKey(i)) remainMain.add(i)
        val remainAux = ArrayList<Int>(aux.size)
        for (j in aux.indices) if (!used[j]) remainAux.add(j)
        if (remainMain.size == remainAux.size) {
            for (k in remainMain.indices) out[remainMain[k]] = aux[remainAux[k]].text
        } else {
            var j = 0
            for (i in remainMain) {
                val t = main[i].time
                while (j < remainAux.size && aux[remainAux[j]].time < t - MATCH_TOLERANCE_MS) j++
                if (j >= remainAux.size) break
                if (Math.abs(aux[remainAux[j]].time - t) <= MATCH_TOLERANCE_MS) {
                    out[i] = aux[remainAux[j]].text
                    j++
                }
            }
        }
        return out
    }

    private const val MATCH_TOLERANCE_MS = 1000L

    fun like(id: Long, like: Boolean, cb: (Result<Boolean>) -> Unit) {
        val d = JSONObject()
        d.put("alg", "itembased")
        d.put("trackId", id)
        d.put("like", like)
        d.put("time", "3")
        // eapi 通道：官方同款接口 /api/radio/like。部分网络环境下 weapi（music.163.com）
        // 被风控返回空响应（即"收藏失败：空响应"），eapi 登录态由 header MUSIC_U 携带。
        ApiClient.get("radio/like", d) { r ->
            if (r.isSuccess) {
                cb(Result.success(true))
            } else {
                val err = r.exceptionOrNull() ?: ApiException(-3, "未知错误")
                cb(Result.failure(ApiException((err as? ApiException)?.code ?: -1, "收藏失败：${err.message}")))
            }
        }
    }

    fun likelist(cb: (Result<List<Long>>) -> Unit) {
        val d = JSONObject()
        // 官方 likelist 模块必传 uid
        d.put("uid", Prefs.userId)
        ApiClient.get("song/like/get", d) { r ->
            cb(r.map { json ->
                val arr = json.optJSONArray("ids") ?: JSONArray()
                val out = ArrayList<Long>(arr.length())
                for (i in 0 until arr.length()) out.add(arr.optLong(i))
                out
            })
        }
    }

    /** 返回 (我喜欢的音乐歌单id, 其余歌单列表)——specialType==5 的喜欢歌单被单独摘出 */
    fun userPlaylist(uid: Long, cb: (Result<Pair<Long, List<Playlist>>>) -> Unit) {
        val d = JSONObject()
        d.put("uid", uid)
        d.put("limit", 100)
        d.put("offset", 0)
        d.put("includeVideo", true)
        // eapi 通道（与官方 user_playlist 相同接口；weapi 在部分网络环境被风控返回空响应）
        ApiClient.get("user/playlist", d) { r ->
            cb(r.map { json ->
                val arr = json.optJSONArray("playlist") ?: JSONArray()
                var likedId = 0L
                val out = ArrayList<Playlist>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = Parser.playlist(o)
                    if (p.id <= 0 || p.name.isEmpty()) continue
                    if (o.optInt("specialType") == 5) likedId = p.id else out.add(p)
                }
                likedId to out
            })
        }
    }

    /** 收藏的专辑：先 eapi，失败回退 weapi（官方通道），适应不同网络风控情况 */
    fun albumSublist(cb: (Result<List<Playlist>>) -> Unit) {
        val d = JSONObject()
        d.put("limit", 60)
        d.put("offset", 0)
        d.put("total", true)
        d.put("uid", Prefs.userId)
        // 返回 null 表示响应里完全没有专辑数组（结构异常）；空数组是合法的「未收藏任何专辑」
        val parse: (JSONObject) -> List<Playlist>? = parse@{ json ->
            // eapi 与 weapi 响应结构可能有差异：兼容多种嵌套与字段命名
            val arr = json.optJSONArray("albums")
                ?: json.optJSONObject("data")?.optJSONArray("albums")
                ?: json.optJSONArray("data")
            if (arr == null) return@parse null
            val out = ArrayList<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val artist = o.optJSONObject("artist")?.optString("name")
                    ?: o.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
                    ?: ""
                val p = Playlist(
                    id = o.optLong("id", o.optLong("albumId")),
                    name = o.optString("name", o.optString("albumName")),
                    cover = o.optString("picUrl").takeIf { it.isNotEmpty() }
                        ?: o.optString("blurPicUrl").takeIf { it.isNotEmpty() }
                        ?: o.optString("albumPicUrl").takeIf { it.isNotEmpty() },
                    playCount = 0,
                    trackCount = o.optInt("size", o.optInt("trackCount")),
                    subtitle = artist,
                    subscribed = true,
                )
                if (p.id > 0 && p.name.isNotEmpty()) out.add(p)
            }
            out
        }
        val finish: (Result<JSONObject>) -> Unit = { r ->
            r.onSuccess { json ->
                val list = parse(json)
                if (list == null) {
                    // 完全没有专辑数组：透出原始响应便于定位结构差异
                    cb(Result.failure(ApiException(-6, "响应结构异常：${json.toString().take(200)}")))
                } else {
                    cb(Result.success(list))
                }
            }.onFailure { cb(Result.failure(it)) }
        }
        ApiClient.get("album/sublist", d) { r ->
            if (r.isSuccess) {
                finish(r)
            } else {
                ApiClient.getWeapi("album/sublist", JSONObject(d.toString())) { r2 -> finish(r2) }
            }
        }
    }

    /** 专辑详情（eapi /api/v1/album/{id}，weapi 在部分网络环境被风控） */
    fun albumDetail(id: Long, cb: (Result<Pair<Playlist, List<Song>>>) -> Unit) {
        ApiClient.get("v1/album/$id", JSONObject()) { r ->
            if (r.isFailure) { cb(Result.failure(r.exceptionOrNull() ?: ApiException(-1, "网络异常"))); return@get }
            val json = r.getOrThrow()
            val songs = Parser.songs(json.optJSONArray("songs"))
            val info = json.optJSONObject("album") ?: JSONObject()
            val album = Playlist(
                id = id,
                name = info.optString("name"),
                cover = info.optString("picUrl").takeIf { it.isNotEmpty() },
                playCount = 0,
                trackCount = songs.size,
                subtitle = info.optJSONObject("artist")?.optString("name") ?: "",
                subscribed = info.optBoolean("sub", false),
            )
            cb(Result.success(album to songs))
        }
    }

    /** 歌手热门歌曲（官方 artist_top_song） */
    fun artistTopSongs(id: Long, cb: (Result<List<Song>>) -> Unit) {
        val d = JSONObject()
        d.put("id", id)
        ApiClient.get("artist/top/song", d) { r ->
            cb(r.map { Parser.songs(it.optJSONArray("songs")) })
        }
    }

    /** 艺人详情关注态（官方 artists 模块：/api/v1/artist/{id}，artist.followed 登录态下为真实值） */
    fun artistFollowed(id: Long, cb: (Result<Boolean>) -> Unit) {
        ApiClient.get("v1/artist/$id", JSONObject()) { r ->
            cb(r.map { it.optJSONObject("artist")?.optBoolean("followed", false) ?: false })
        }
    }

    /** 歌手全部专辑（官方 artist_album），分页拉取全部专辑并附带歌手信息（含专辑总数） */
    fun artistAlbums(id: Long, cb: (Result<Pair<Playlist, List<Playlist>>>) -> Unit) {
        val all = ArrayList<Playlist>()
        val pageSize = 50
        var artist: Playlist? = null

        fun fetch(offset: Int) {
            val d = JSONObject()
            d.put("limit", pageSize)
            d.put("offset", offset)
            d.put("total", true)
            ApiClient.get("artist/albums/$id", d) { r ->
                r.onSuccess { json ->
                    val a = json.optJSONObject("artist") ?: JSONObject()
                    if (artist == null) {
                        artist = Playlist(
                            id = id,
                            name = a.optString("name"),
                            cover = a.optString("picUrl").takeIf { it.isNotEmpty() }
                                ?: a.optString("img1v1Url").takeIf { it.isNotEmpty() },
                            playCount = 0,
                            trackCount = a.optInt("musicSize"),
                            albumCount = a.optInt("albumSize"),
                        )
                    }
                    val arr = json.optJSONArray("hotAlbums") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = Playlist(
                            id = o.optLong("id"),
                            name = o.optString("name"),
                            cover = o.optString("picUrl").takeIf { it.isNotEmpty() },
                            playCount = 0,
                            trackCount = o.optInt("size"),
                            subtitle = yearOf(o.optLong("publishTime")),
                        )
                        if (p.id > 0) all.add(p)
                    }
                    // more 表示还有下一页，封顶 2000 张避免服务端异常时死循环
                    if (json.optBoolean("more", false) && offset + pageSize < 2000) {
                        fetch(offset + pageSize)
                    } else {
                        // 极端情况下首个响应未带 artist 对象，给一个空壳占位而不是崩溃
                        cb(Result.success((artist ?: Playlist(id, "", null, 0, 0)) to all))
                    }
                }.onFailure { cb(Result.failure(it)) }
            }
        }
        fetch(0)
    }

    /** 关注的艺人（官方 artist_sublist：/api/artist/sublist，响应在 data 数组） */
    fun followedArtists(limit: Int, offset: Int, cb: (Result<List<Playlist>>) -> Unit) {
        val d = JSONObject()
        d.put("limit", limit)
        d.put("offset", offset)
        d.put("total", true)
        val parse = { json: JSONObject ->
            val arr = json.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = Playlist(
                    id = o.optLong("id", o.optLong("artistId")),
                    name = o.optString("name"),
                    cover = o.optString("picUrl").takeIf { it.isNotEmpty() }
                        ?: o.optString("img1v1Url").takeIf { it.isNotEmpty() },
                    playCount = 0,
                    trackCount = o.optInt("musicSize"),
                    albumCount = o.optInt("albumSize"),
                )
                if (p.id > 0 && p.name.isNotEmpty()) out.add(p)
            }
            out
        }
        val finish: (Result<JSONObject>) -> Unit = { r ->
            r.onSuccess { cb(Result.success(parse(it))) }.onFailure { cb(Result.failure(it)) }
        }
        ApiClient.get("artist/sublist", d) { r ->
            if (r.isSuccess) {
                finish(r)
            } else {
                ApiClient.getWeapi("artist/sublist", JSONObject(d.toString())) { r2 -> finish(r2) }
            }
        }
    }

    /**
     * 听歌排行（官方 user_record）：type=1 最近一周 weekData，type=0 所有时间 allData。
     * 优先 eapi，失败回退 weapi（与专辑/关注列表相同的容错策略）。
     */
    fun playRecord(type: Int, cb: (Result<List<PlayRecordItem>>) -> Unit) {
        val d = JSONObject()
        d.put("uid", Prefs.userId)
        d.put("type", if (type == 1) 1 else 0)
        val parse: (JSONObject) -> List<PlayRecordItem> = { json ->
            val arr = json.optJSONArray(if (type == 1) "weekData" else "allData")
                ?: json.optJSONArray("weekData")
                ?: json.optJSONArray("allData")
                ?: JSONArray()
            val out = ArrayList<PlayRecordItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optJSONObject("song") ?: continue
                val song = Parser.song(s)
                if (song.id > 0) out.add(PlayRecordItem(song, o.optInt("playCount", 0)))
            }
            out
        }
        ApiClient.get("v1/play/record", d) { r ->
            if (r.isSuccess) {
                cb(Result.success(parse(r.getOrThrow())))
            } else {
                ApiClient.getWeapi("v1/play/record", JSONObject(d.toString())) { r2 ->
                    r2.onSuccess { cb(Result.success(parse(it))) }
                        .onFailure { cb(Result.failure(it)) }
                }
            }
        }
    }

    /** 关注/取消关注艺人（官方 artist_sub：artistId + artistIds='[id]'）。
     *  关注/取关均为敏感写操作：统一走 linuxapi（官方 Linux 桌面端通道，风控最宽松）。
     *  eapi 的 artist/sub 路由无官方支持（关注会被风控提示"网络环境存在风险"，
     *  取关返回 200 但不改变状态）；weapi 在该网络下 POST 返回 200 空响应。 */
    fun artistSub(id: Long, sub: Boolean, cb: (Result<Boolean>) -> Unit) {
        val d = JSONObject()
        d.put("artistId", id)
        d.put("artistIds", "[$id]")
        ApiClient.getLinuxApi(if (sub) "artist/sub" else "artist/unsub", d) { r ->
            cb(r.map { true })
        }
    }

    private fun yearOf(ms: Long): String? {
        if (ms <= 0) return null
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return cal.get(java.util.Calendar.YEAR).toString()
    }

    /** 收藏/取消收藏歌单（官方 playlist_subscribe，收藏时强制携带反作弊 token） */
    fun playlistSubscribe(id: Long, sub: Boolean, cb: (Result<Boolean>) -> Unit) {
        val d = JSONObject()
        d.put("id", id)
        val path = if (sub) "playlist/subscribe" else "playlist/unsubscribe"
        ApiClient.get(path, d, antiCheat = sub) { r -> cb(r.map { true }) }
    }

    /** 收藏/取消收藏专辑（官方 album_sub / album_unsub） */
    fun albumSub(id: Long, sub: Boolean, cb: (Result<Boolean>) -> Unit) {
        val d = JSONObject()
        d.put("id", id)
        ApiClient.get(if (sub) "album/sub" else "album/unsub", d) { r ->
            cb(r.map { true })
        }
    }

    fun qrKey(cb: (Result<String>) -> Unit) {
        val d = JSONObject().put("type", 3)
        // 官方 login_qr_key 走 eapi（weapi 匿名请求会被风控返回空响应，导致二维码刷不出）
        ApiClient.get("login/qrcode/unikey", d) { r ->
            cb(r.map { it.optString("unikey") })
        }
    }

    /** code: 800过期 801待扫码 802待确认 803成功(已自动保存cookie) */
    fun qrCheck(key: String, cb: (Result<Int>) -> Unit) {
        val d = JSONObject()
        d.put("key", key)
        d.put("type", 3)
        // 官方 login_qr_check 走 eapi；803 时响应 Set-Cookie 下发 MUSIC_U
        ApiClient.get("login/qrcode/client/login", d) { r ->
            val code = r.getOrNull()?.optInt("code", 200)
                ?: (r.exceptionOrNull() as? ApiException)?.code
                ?: -1
            if (code == 803) {
                account { ar ->
                    ar.getOrNull()?.let { saveAccount(it) }
                    cb(Result.success(803))
                }
            } else {
                cb(Result.success(code))
            }
        }
    }

    fun account(cb: (Result<JSONObject>) -> Unit) {
        // eapi 通道（weapi 在部分网络环境被风控）；成功后自愈 userId/昵称等资料
        ApiClient.get("w/nuser/account/get", JSONObject()) { r ->
            r.getOrNull()?.let { saveAccount(it) }
            cb(r)
        }
    }

    fun logout(cb: (Result<Boolean>) -> Unit) {
        ApiClient.get("logout", JSONObject()) { r ->
            Prefs.clearLogin()
            cb(r.map { true })
        }
    }

    /**
     * 听歌打卡：startplay（最近播放）+ play（听歌排行）。
     * [timeSeconds] 为本次实际播放秒数，自然播完时传歌曲时长；不足 1 秒不上报。
     * 整个流程失败静默，不影响播放。
     */
    fun scrobble(songId: Long, timeSeconds: Int, sourceId: Long = 0) {
        if (!Prefs.loggedIn || songId <= 0 || timeSeconds < 1) return
        val base = JSONObject()
            .put("id", songId.toString())
            .put("type", "song")
            .put("mainsite", "1")
            .put("mainsiteWeb", "1")
            .put("content", "id=$sourceId")
        val play = JSONObject(base.toString())
            .put("download", 0)
            .put("end", "playend")
            .put("sourceId", sourceId.toString())
            .put("time", timeSeconds)
            .put("wifi", 0)
            .put("source", "list")
        ApiClient.sendWeblog("startplay", base)
        ApiClient.sendWeblog("play", play)
    }

    private fun saveAccount(json: JSONObject) {
        val profile = json.optJSONObject("profile") ?: json.optJSONObject("account")
        val account = json.optJSONObject("account")
        if (profile != null) {
            Prefs.userId = profile.optLong("userId", profile.optLong("id"))
            Prefs.nickname = profile.optString("nickname", profile.optString("userName"))
            Prefs.avatar = profile.optString("avatarUrl")
        } else if (account != null) {
            Prefs.userId = account.optLong("id")
            Prefs.nickname = account.optString("userName")
        }
        json.optJSONObject("profile")?.optInt("vipType")?.let { Prefs.vipType = it }
    }
}
