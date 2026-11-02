package com.toonshortcut.app

/**
 * 한글 초성으로 묶고 찾는 도구. 안드로이드에 기대지 않아 따로 시험할 수 있다.
 */
object Chosung {

    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /** 오른쪽 색인 줄에 늘 보이는 글자. 영문(A)과 기타(#)는 있을 때만 붙인다. */
    const val BASIC = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"
    const val LATIN = "A"
    const val OTHER = "#"

    private fun isSyllable(ch: Char) = ch in '가'..'힣'
    private fun isJamoConsonant(ch: Char) = ch in 'ㄱ'..'ㅎ'

    /** 글자 하나의 초성. 한글 음절이 아니면 null. */
    fun initialOf(ch: Char): Char? =
        if (isSyllable(ch)) CHO[(ch.code - 0xAC00) / 588] else null

    /** 된소리는 예사소리 칸에 넣는다. ㄲ → ㄱ */
    private fun plain(ch: Char): Char = when (ch) {
        'ㄲ' -> 'ㄱ'
        'ㄸ' -> 'ㄷ'
        'ㅃ' -> 'ㅂ'
        'ㅆ' -> 'ㅅ'
        'ㅉ' -> 'ㅈ'
        else -> ch
    }

    /** 제목이 들어갈 색인 칸. 첫 글자 기준: 한글이면 초성, 영문이면 A, 나머지는 #. */
    fun groupOf(title: String): String {
        val ch = title.trim().firstOrNull() ?: return OTHER
        initialOf(ch)?.let { return plain(it).toString() }
        if (isJamoConsonant(ch)) return plain(ch).toString()
        if (ch in 'a'..'z' || ch in 'A'..'Z') return LATIN
        return OTHER
    }

    /** 색인 줄의 순서. */
    fun order(group: String): Int {
        val i = BASIC.indexOf(group)
        return when {
            group.length == 1 && i >= 0 -> i
            group == LATIN -> BASIC.length
            else -> BASIC.length + 1
        }
    }

    /** 제목의 초성만 뽑는다. 한글이 아닌 글자는 소문자로 그대로 둔다. 공백은 뺀다. */
    fun initials(title: String): String = buildString {
        for (ch in title) {
            if (ch.isWhitespace()) continue
            append(initialOf(ch) ?: ch.lowercaseChar())
        }
    }

    /**
     * 검색어가 제목에 맞는지.
     * "절대" 처럼 그냥 쳐도 되고, "ㅈㄷㄱㄱ" 처럼 초성만 쳐도 된다.
     * 띄어쓰기와 대소문자는 따지지 않는다.
     */
    fun matches(title: String, query: String): Boolean {
        val q = query.filterNot { it.isWhitespace() }.lowercase()
        if (q.isEmpty()) return true
        val t = title.filterNot { it.isWhitespace() }.lowercase()
        if (t.contains(q)) return true
        if (q.any { isJamoConsonant(it) }) {
            // 초성과 완성 글자가 섞여 있어도 되게 글자마다 비교한다. "절ㄷ" → 절대
            val ti = initials(title)
            if (ti.length != t.length) return false
            for (start in 0..(t.length - q.length)) {
                var ok = true
                for (k in q.indices) {
                    val qc = q[k]
                    val tc = t[start + k]
                    val hit = if (isJamoConsonant(qc)) ti[start + k] == qc else tc == qc
                    if (!hit) { ok = false; break }
                }
                if (ok) return true
            }
        }
        return false
    }
}
