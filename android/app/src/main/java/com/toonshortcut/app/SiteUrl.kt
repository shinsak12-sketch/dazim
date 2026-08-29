package com.toonshortcut.app

/**
 * 도메인 번호와 회차 번호를 다루는 순수 로직.
 *
 * android.* 를 쓰지 않는다. 안드로이드 없이 JVM에서 그대로 테스트하기 위해서다.
 * 인코딩은 자바스크립트 encodeURI 와 바이트 단위로 같은 결과를 내야 한다.
 * 원본 주소와 조금이라도 어긋나면 링크가 통째로 깨진다.
 */
object SiteUrl {

    // encodeURI 가 그대로 두는 문자들. (영숫자 + 아래 기호)
    private const val UNRESERVED = "-_.!~*'()"
    private const val RESERVED_KEPT = ";,/?:@&=+\$#"

    private fun isSafe(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            UNRESERVED.indexOf(c) >= 0 || RESERVED_KEPT.indexOf(c) >= 0

    /** 자바스크립트 encodeURI 와 동일하게 인코딩한다. 16진수는 대문자. */
    fun encodeUri(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 0x80 && isSafe(c)) sb.append(c)
            else sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * 퍼센트 디코딩. URLDecoder 는 '+' 를 공백으로 바꿔버려서 경로에는 쓸 수 없다.
     * 잘못된 인코딩이 섞여 있어도 예외 없이 최대한 살려서 돌려준다.
     */
    fun decodeUri(s: String): String {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- 도메인

    data class Domain(
        /** 번호 라벨 앞부분. 예: "www." (없으면 "") */
        val head: String,
        /** 번호 라벨의 문자 부분. 예: "tkor" */
        val prefix: String,
        /** 번호 라벨 뒷부분. 예: "com", "co.kr" */
        val suffix: String,
        /** 번호 자릿수 (앞 0 채움용) */
        val pad: Int,
        val num: Int,
    )

    const val MIN_NUM = 0
    const val MAX_NUM = 99999

    fun clampNum(n: Int): Int = n.coerceIn(MIN_NUM, MAX_NUM)

    fun buildHost(d: Domain, num: Int = d.num): String =
        d.head + d.prefix + clampNum(num).toString().padStart(d.pad, '0') + "." + d.suffix

    fun buildUrl(d: Domain, path: String, num: Int = d.num): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return "https://" + buildHost(d, num) + p
    }

    /** 호스트명에서 "글자+숫자" 라벨을 찾아 분해한다. 못 찾으면 null. */
    fun parseHost(hostname: String): Domain? {
        val host = hostname.trim().lowercase()
        if (host.isEmpty()) return null
        val labels = host.split(".")
        // 마지막 라벨(TLD)은 번호 자리로 보지 않는다.
        for (i in 0 until labels.size - 1) {
            if (labels[i] == "www") continue
            val m = Regex("^(.*?)(\\d+)$").find(labels[i]) ?: continue
            val digits = m.groupValues[2]
            return Domain(
                head = if (i > 0) labels.subList(0, i).joinToString(".") + "." else "",
                prefix = m.groupValues[1],
                suffix = labels.subList(i + 1, labels.size).joinToString("."),
                pad = digits.length,
                num = clampNum(digits.toIntOrNull() ?: return null),
            )
        }
        return null
    }

    /** 번호를 뺀 도메인 모양이 같은지 (같으면 번호만 다른 미러) */
    fun sameShape(a: Domain, b: Domain): Boolean =
        a.head == b.head && a.prefix == b.prefix && a.suffix == b.suffix

    data class Parsed(val domain: Domain?, val path: String)

    /** 붙여넣은 문자열(전체 주소 또는 경로만)을 도메인과 경로로 나눈다. */
    fun parseInput(input: String): Parsed? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("/")) return Parsed(null, s)

        val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "https://$s"
        val uri = try {
            java.net.URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host ?: return null
        // getRawPath 로 원본 인코딩을 보존한다. getPath 를 쓰면 디코딩돼 버린다.
        val raw = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val frag = uri.rawFragment?.let { "#$it" } ?: ""
        val path = (if (raw.isEmpty()) "/" else raw) + query + frag
        return Parsed(parseHost(host), path)
    }

    // ---------------------------------------------------------------- 회차

    data class Episode(val before: String, val ep: Int, val pad: Int, val after: String)

    /**
     * 경로에서 회차 숫자를 찾는다.
     *
     * 주의: 퍼센트 인코딩된 한글에는 숫자가 섞여 있다(%EB%82%98 의 82, %ED%99%94 의 94).
     * 반드시 디코딩한 뒤에 찾아야 한다. 인코딩된 문자열에서 "마지막 숫자"를 집으면
     * 회차를 94 로 오인한다.
     */
    fun parseEpisode(path: String): Episode? {
        val decoded = decodeUri(path)

        // 1순위: "266화" 처럼 숫자 뒤에 화/話/회 가 붙은 형태
        Regex("(\\d+)(?=[화話회])").find(decoded)?.let { m ->
            val digits = m.groupValues[1]
            val idx = m.range.first
            return Episode(
                before = decoded.substring(0, idx),
                ep = digits.toIntOrNull() ?: return null,
                pad = digits.length,
                after = decoded.substring(idx + digits.length),
            )
        }

        // 2순위: 파일명 쪽 마지막 숫자 (예: /view/1234, /episode-88.html)
        val lastSlash = decoded.lastIndexOf('/')
        val seg = decoded.substring(lastSlash + 1)
        val matches = Regex("\\d+").findAll(seg).toList()
        if (matches.isEmpty()) return null
        val last = matches.last()
        val idx = lastSlash + 1 + last.range.first
        val digits = last.value
        return Episode(
            before = decoded.substring(0, idx),
            ep = digits.toIntOrNull() ?: return null,
            pad = digits.length,
            after = decoded.substring(idx + digits.length),
        )
    }

    /** 회차를 바꾼 경로를 만든다. 원본과 같은 방식으로 다시 인코딩된다. */
    fun buildEpisodePath(ref: Episode, ep: Int): String {
        val n = ep.coerceAtLeast(0)
        return encodeUri(ref.before + n.toString().padStart(ref.pad, '0') + ref.after)
    }

    fun episodeLabel(path: String): String? = parseEpisode(path)?.let { "${it.ep}화" }

    /** 경로에서 만화 제목을 추측한다. /나_혼자_..._266화.html → "나 혼자 ..." */
    fun guessTitle(path: String): String {
        var s = decodeUri(path)
        s = s.substringBefore('?').substringBefore('#')
        s = s.substring(s.lastIndexOf('/') + 1)
        s = s.replace(Regex("\\.(html?|php|aspx?|jsp)$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("[_+]+"), " ").trim()
        s = s.replace(Regex("\\s*\\d+\\s*화\\s*$"), "").trim()
        return s.ifEmpty { "제목 없음" }
    }

    // ---------------------------------------------------------------- 다음 회차 확인

    /**
     * 페이지에서 이 작품의 회차 링크들을 찾아 가장 큰 번호를 돌려준다.
     *
     * 회차 번호 "앞부분"까지만 맞춰보고 그 뒤의 숫자를 읽는다. 뒷부분은 보지 않는다.
     * 이 사이트에는 "제목_209화_:_부제.html" 처럼 회차마다 부제가 바뀌는 작품이 있어서,
     * 숫자만 바꿔 주소를 지어내면 존재하지 않는 주소가 된다. 앞부분만 맞추면
     * 부제가 무엇이든 걸린다.
     *
     * href 가 절대경로든 상대경로든 걸리도록 파일 이름 쪽만 본다.
     * 퍼센트 인코딩은 대소문자가 섞여 나오므로 대소문자를 무시한다.
     */
    fun maxLinkedEpisode(html: String, ref: Episode): Int? {
        val decodedName = ref.before.substringAfterLast('/')
        val encodedName = encodeUri(ref.before).substringAfterLast('/')
        if (decodedName.isEmpty() && encodedName.isEmpty()) return null

        var max: Int? = null
        for (needle in listOf(encodedName, decodedName).distinct().filter { it.isNotEmpty() }) {
            val re = Regex(Regex.escape(needle) + "(\\d{1,5})", RegexOption.IGNORE_CASE)
            for (m in re.findAll(html)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                if (max == null || n > max!!) max = n
            }
        }
        return max
    }

    /**
     * 받아온 페이지가 정말 그 회차의 것인지 확인한다.
     *
     * 없는 회차에 404 대신 200과 안내 페이지를 주는 사이트가 있어서
     * 상태 코드만으로는 판단할 수 없다.
     *
     * 이 사이트는 회차를 "074화" 처럼 0을 채워 적는다. 그래서 앞의 0을 허용해야 한다.
     * 허용하지 않으면 75화를 찾을 때 "075화" 가 걸리지 않아 없는 회차로 오해한다.
     */
    fun looksLikeEpisode(html: String, ep: Int): Boolean =
        Regex("(?<!\\d)0*$ep\\s*화").containsMatchIn(html)
}
