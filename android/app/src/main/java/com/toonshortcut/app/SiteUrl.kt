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

    data class EpisodeLink(val ep: Int, val path: String)

    /**
     * 페이지에서 이 작품의 회차 링크를 찾아 가장 최신 것을 돌려준다.
     *
     * 번호만 세지 않고 링크 주소를 통째로 가져온다. 도중에 표기 방식이 바뀌는
     * 작품이 있기 때문이다. 예를 들어 74화까지는 "..._074화.html" 이다가
     * 75화부터 "..._EP.075_새로운_시작.html" 로 바뀐다. 이런 주소는 번호만
     * 갈아끼워서는 만들어낼 수 없으므로 사이트가 준 주소를 그대로 써야 한다.
     *
     * 제목 뒤에 "EP." 같은 표시가 끼어들 수 있어 숫자 앞의 짧은 글자는 건너뛴다.
     * href 가 절대경로든 상대경로든, 한글이 인코딩돼 있든 아니든 걸리게 한다.
     */
    fun findLatestEpisodeLink(html: String, ref: Episode): EpisodeLink? {
        val decodedName = ref.before.substringAfterLast('/')
        val encodedName = encodeUri(ref.before).substringAfterLast('/')
        val needles = listOf(encodedName, decodedName).distinct().filter { it.isNotEmpty() }
        if (needles.isEmpty()) return null

        val hrefRe = Regex("""href\s*=\s*["']([^"'>]+)["']""", RegexOption.IGNORE_CASE)
        // 제목과 숫자 사이에 끼어들 수 있는 표시. "EP." 처럼 짧은 것만 허용한다.
        val numRe = Regex("""^[A-Za-z._\-]{0,6}(\d{1,5})""")

        var best: EpisodeLink? = null
        for (m in hrefRe.findAll(html)) {
            val href = m.groupValues[1].trim()
            for (needle in needles) {
                val at = href.indexOf(needle, ignoreCase = true)
                if (at < 0) continue
                val tail = href.substring(at + needle.length)
                val ep = numRe.find(tail)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val path = toPath(href) ?: continue
                val current = best
                if (current == null || ep > current.ep) best = EpisodeLink(ep, path)
                break
            }
        }
        return best
    }

    /** href 를 우리가 저장하는 형태(인코딩된 절대 경로)로 맞춘다. */
    private fun toPath(href: String): String? {
        val raw = when {
            href.startsWith("http://", true) || href.startsWith("https://", true) ->
                runCatching { java.net.URI(encodeUri(decodeUri(href))).rawPath }.getOrNull() ?: return null
            href.startsWith("/") -> href
            else -> "/$href"
        }
        if (raw.isEmpty() || raw == "/") return null
        // 한글이 그대로 들어 있으면 인코딩해 둔다. 이미 인코딩돼 있으면 그대로 유지된다.
        return if (raw.any { it.code > 127 }) encodeUri(raw) else raw
    }

    /**
     * 회차 번호 뒤에 확장자만 남는지 본다.
     *
     * "배드_본_블러드_92화.html" 은 숫자만 93으로 바꾸면 다음 화 주소가 된다.
     * 하지만 "천마는_..._209화_:_부제.html" 은 회차마다 부제가 달라서
     * 숫자만 바꾸면 존재하지 않는 주소가 나온다. 앞의 경우에만 주소를 지어낼 수 있다.
     */
    fun hasSimpleTail(ref: Episode): Boolean =
        Regex("^\\s*화\\s*\\.(html?|php|aspx?|jsp)$", RegexOption.IGNORE_CASE).matches(ref.after)

    /**
     * 회차 주소에서 그 작품의 목록 페이지 주소를 추측한다.
     *
     * 이 사이트는 회차 주소에 밑줄을, 목록 주소에 붙임표를 쓴다.
     *   /몽둥이기사_단_45화.html  ->  /몽둥이기사-단
     *
     * 목록 페이지에는 모든 회차 링크가 있어서 최신 회차를 바로 알 수 있다.
     * 0을 채워 쓰든 부제가 붙든 상관이 없고, 작품마다 주소가 고정이라
     * 한 번 맞히면 계속 쓸 수 있다.
     */
    fun guessListPath(ref: Episode): String? {
        val name = ref.before.substringAfterLast('/').trimEnd('_', '-', ' ')
        if (name.isEmpty()) return null
        return "/" + encodeUri(name.replace('_', '-'))
    }
}
