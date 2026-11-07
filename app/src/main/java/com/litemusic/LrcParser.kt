package com.litemusic

/** LRC 歌词解析，支持一行多时间标签 */
object LrcParser {
    private val TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || !line.startsWith("[")) continue
            val matches = TAG.findAll(line).toList()
            if (matches.isEmpty()) continue
            val last = matches.last()
            val text = line.substring(last.range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10
                    else -> (fracStr.take(3).toLongOrNull() ?: 0L)
                }
                val time = min * 60_000 + sec * 1000 + frac
                if (text.isNotEmpty()) out.add(LyricLine(time, text))
            }
        }
        out.sortBy { it.time }
        return out
    }
}
