package com.litemusic

/** 罗马音 → 平假名（日语歌曲注音显示用） */
object Kana {

    private val MAP = linkedMapOf(
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "tsu" to "つ", "shi" to "し", "chi" to "ち", "fu" to "ふ",
        "ji" to "じ", "di" to "ぢ", "du" to "づ",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を", "nn" to "ん",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "n" to "ん",
    )

    /** 转换罗马音串为平假名；无法识别的字符原样保留 */
    fun romajiToHiragana(src: String?): String? {
        if (src.isNullOrBlank()) return null
        val s = src.lowercase()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // 促音：重叠辅音（kk/ss/tt/pp/cc 等）
            if (c.isLetter() && c != 'n' && i + 1 < s.length && s[i + 1] == c) {
                sb.append('っ')
                i++
                continue
            }
            if (c == '-' || c == 'ー') {
                sb.append('ー')
                i++
                continue
            }
            var matched = false
            for (len in 3 downTo 1) {
                if (i + len > s.length) continue
                val key = s.substring(i, i + len)
                val kana = MAP[key]
                if (kana != null) {
                    sb.append(kana)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                // 空格/标点/未识别字符原样保留
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
