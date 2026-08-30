package com.toonshortcut.app

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 저장된 만화에 다음 회차가 나왔는지 확인한다.
 *
 * 작품마다 목록 페이지가 있고 주소가 고정이다(/몽둥이기사-단). 거기 회차 링크를
 * 보면 최신 회차를 알 수 있다. 번호만 세지 않고 링크 주소를 그대로 가져오는데,
 * 도중에 표기가 바뀌는 작품이 있어("074화" -> "EP.075_부제") 번호만으로는
 * 주소를 만들 수 없기 때문이다.
 *
 * 판정이 어긋날 때 짐작으로 고치면 또 빗나간다. 그래서 과정을 전부 기록해
 * 파일로 내보낼 수 있게 했다. 상태 코드, 받은 크기, 실제 링크 표본까지 남긴다.
 */
object EpisodeCheck {

    data class Result(
        val comicId: String,
        val status: NextStatus,
        val note: String?,
        /** 목록에서 읽어낸 최신 회차. 못 읽었으면 null. */
        val latestEp: Int? = null,
        /** 최신 회차의 실제 주소. 표기가 바뀌는 작품은 지어낼 수 없어 그대로 담는다. */
        val latestPath: String? = null,
        /** 무슨 일이 있었는지 그대로 남긴 기록. 진단 파일에 들어간다. */
        val log: String = "",
    )

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    /** 목록 페이지는 최신 회차가 위에 있으므로 앞부분만 받으면 된다. */
    private const val LIST_BYTES = 192 * 1024
    /** 보던 페이지에서 링크를 훑을 때. 이전/다음 링크는 문서 아래쪽에 있다. */
    private const val PAGE_BYTES = 512 * 1024
    private const val CONCURRENCY = 2
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val pool = Executors.newFixedThreadPool(CONCURRENCY)
    private val main = Handler(Looper.getMainLooper())

    /** 확인 과정을 사람이 읽을 수 있게 모아둔다. */
    private class Log {
        private val sb = StringBuilder()
        fun line(text: String) { sb.append(text).append('\n') }
        override fun toString() = sb.toString()
    }

    fun checkAll(
        domain: SiteUrl.Domain,
        comics: List<Comic>,
        onEach: (Result) -> Unit,
        onDone: () -> Unit,
    ) {
        if (comics.isEmpty()) {
            onDone()
            return
        }
        val remaining = AtomicInteger(comics.size)
        for (c in comics) {
            pool.execute {
                val log = Log()
                val result = try {
                    check(domain, c, log)
                } catch (e: Exception) {
                    log.line("  예외: ${e.javaClass.simpleName}: ${e.message}")
                    Result(c.id, NextStatus.FAILED, e.javaClass.simpleName, log = log.toString())
                }
                main.post {
                    onEach(result.copy(log = log.toString()))
                    if (remaining.decrementAndGet() == 0) onDone()
                }
            }
        }
    }

    private fun check(domain: SiteUrl.Domain, comic: Comic, log: Log): Result {
        log.line("[${comic.title}]")
        log.line("  저장 경로: ${comic.path}")
        log.line("  (디코딩) ${SiteUrl.decodeUri(comic.path)}")

        val ref = SiteUrl.parseEpisode(comic.path)
        if (ref == null) {
            log.line("  → 경로에서 회차 번호를 못 찾음")
            return Result(comic.id, NextStatus.NO_EPISODE, "주소에 회차 번호가 없습니다")
        }
        log.line("  회차 ${ref.ep}, 자릿수 ${ref.pad}, 뒤 \"${ref.after}\", 단순형 ${SiteUrl.hasSimpleTail(ref)}")

        checkByListPage(domain, comic, ref, log)?.let { return it }

        log.line("  목록 페이지로 판정 못함 → 예비 방법으로")
        return if (SiteUrl.hasSimpleTail(ref)) {
            checkByNextUrl(domain, comic, ref, log)
        } else {
            checkByLinks(domain, comic, ref, log)
        }
    }

    private fun checkByListPage(
        domain: SiteUrl.Domain,
        comic: Comic,
        ref: SiteUrl.Episode,
        log: Log,
    ): Result? {
        val listPath = comic.listPath?.takeIf { it.isNotBlank() } ?: SiteUrl.guessListPath(ref)
        if (listPath.isNullOrBlank()) {
            log.line("  목록 주소를 만들지 못함")
            return null
        }
        val source = if (comic.listPath.isNullOrBlank()) "추측" else "직접 지정"
        log.line("  목록 주소($source): ${SiteUrl.decodeUri(listPath)}")

        val url = SiteUrl.buildUrl(domain, listPath)
        log.line("  요청: $url")
        val page = fetch(url, LIST_BYTES, log = log) ?: return null
        if (page.status !in 200..299) {
            log.line("  → 목록 페이지 상태 ${page.status}")
            return null
        }

        describeLinks(page, ref, log)
        val latest = findLatest(page, ref)
        if (latest == null) {
            log.line("  → 목록에서 이 작품의 회차 링크를 못 찾음")
            return null
        }
        log.line("  → 최신 ${latest.ep}화, 주소 ${SiteUrl.decodeUri(latest.path)}")
        log.line("  → 결과: ${if (latest.ep > ref.ep) "새 회차 있음" else "최신"}")
        return Result(
            comic.id,
            if (latest.ep > ref.ep) NextStatus.YES else NextStatus.NO,
            null,
            latestEp = latest.ep,
            latestPath = latest.path,
        )
    }

    /** 회차 숫자만 하나 올린 주소가 열리는지 본다. 본문은 받지 않는다. */
    private fun checkByNextUrl(
        domain: SiteUrl.Domain,
        comic: Comic,
        ref: SiteUrl.Episode,
        log: Log,
    ): Result {
        val nextPath = SiteUrl.buildEpisodePath(ref, ref.ep + 1)
        val url = SiteUrl.buildUrl(domain, nextPath)
        log.line("  다음 회차 주소 요청: $url")

        val page = fetch(url, 0, log = log) ?: fetch(url, 0, log = log)
        if (page == null) {
            log.line("  → 두 번 다 접속 실패")
            return Result(comic.id, NextStatus.FAILED, "접속하지 못했습니다 (시간 초과 또는 연결 끊김)")
        }

        return when {
            page.status == 404 || page.status == 410 -> {
                log.line("  → 없음. 결과: 최신")
                Result(comic.id, NextStatus.NO, null)
            }
            page.status == 200 -> {
                val wanted = nextPath.substringAfterLast('/')
                val landed = page.finalUrl?.substringAfterLast('/') ?: wanted
                if (landed.equals(wanted, ignoreCase = true)) {
                    log.line("  → 있음. 결과: 새 회차 있음")
                    Result(comic.id, NextStatus.YES, null, latestEp = ref.ep + 1, latestPath = nextPath)
                } else {
                    log.line("  → 다른 곳으로 넘어감($landed). 결과: 최신")
                    Result(comic.id, NextStatus.NO, null)
                }
            }
            else -> {
                log.line("  → 예상 밖 상태 ${page.status}")
                Result(comic.id, NextStatus.FAILED, "사이트가 ${page.status} 로 응답했습니다")
            }
        }
    }

    /** 주소를 지어낼 수 없는 작품용. 보던 페이지의 링크를 훑는다. */
    private fun checkByLinks(
        domain: SiteUrl.Domain,
        comic: Comic,
        ref: SiteUrl.Episode,
        log: Log,
    ): Result {
        val url = SiteUrl.buildUrl(domain, comic.path)
        log.line("  보던 페이지 요청: $url")
        val page = fetch(url, PAGE_BYTES, log = log)
            ?: return Result(comic.id, NextStatus.FAILED, "접속하지 못했습니다 (시간 초과 또는 연결 끊김)")
        if (page.status !in 200..299) {
            return Result(comic.id, NextStatus.FAILED, "사이트가 ${page.status} 로 응답했습니다")
        }

        describeLinks(page, ref, log)
        val latest = findLatest(page, ref)
            ?: return Result(comic.id, NextStatus.FAILED, "페이지에서 회차 링크를 찾지 못했습니다")
        log.line("  → 최신 ${latest.ep}화")
        return Result(
            comic.id,
            if (latest.ep > ref.ep) NextStatus.YES else NextStatus.NO,
            null,
            latestEp = latest.ep,
            latestPath = latest.path,
        )
    }

    /** 링크를 못 찾았을 때 원인을 알 수 있도록 실제 모습을 남긴다. */
    private fun describeLinks(page: Page, ref: SiteUrl.Episode, log: Log) {
        val text = page.decodings().firstOrNull() ?: return
        log.line("  href 개수: ${SiteUrl.countHrefs(text)}")
        log.line("  찾는 접두사(디코딩): ${ref.before.substringAfterLast('/')}")
        log.line("  찾는 접두사(인코딩): ${SiteUrl.encodeUri(ref.before).substringAfterLast('/')}")
        val samples = SiteUrl.sampleEpisodeHrefs(text)
        if (samples.isEmpty()) {
            log.line("  회차 링크 표본: 없음")
        } else {
            log.line("  회차 링크 표본:")
            for (h in samples) log.line("    $h")
        }
    }

    private fun findLatest(page: Page?, ref: SiteUrl.Episode): SiteUrl.EpisodeLink? {
        if (page == null || page.status !in 200..299) return null
        return page.decodings()
            .mapNotNull { SiteUrl.findLatestEpisodeLink(it, ref) }
            .maxByOrNull { it.ep }
    }

    private class Page(
        val status: Int,
        val body: ByteArray,
        val contentType: String?,
        val finalUrl: String?,
    ) {
        fun decodings(): List<String> {
            val list = mutableListOf<String>()
            charsetOf(contentType)?.let { runCatching { list.add(String(body, it)) } }
            runCatching { list.add(String(body, Charsets.UTF_8)) }
            runCatching { list.add(String(body, charset("EUC-KR"))) }
            return list.distinct()
        }
    }

    private fun charsetOf(contentType: String?): java.nio.charset.Charset? {
        val name = contentType?.substringAfter("charset=", "")?.trim()?.trim('"')
        if (name.isNullOrEmpty()) return null
        return runCatching { charset(name) }.getOrNull()
    }

    private fun fetch(url: String, maxBytes: Int, tailOnly: Boolean = false, log: Log): Page? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                if (tailOnly) setRequestProperty("Range", "bytes=-$maxBytes")
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = if (maxBytes > 0) stream?.use { read(it, maxBytes) } ?: ByteArray(0) else ByteArray(0)
            if (maxBytes == 0) runCatching { stream?.close() }
            val finalUrl = conn.url?.toString()
            log.line("  ← 상태 $status, ${body.size}바이트, 형식 ${conn.contentType ?: "?"}")
            if (finalUrl != null && finalUrl != url) log.line("  ← 최종 주소 $finalUrl")
            Page(status, body, conn.contentType, finalUrl)
        } catch (e: Exception) {
            log.line("  ← 실패: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun read(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
