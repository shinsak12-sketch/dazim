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
 * 작품마다 목록 페이지가 있고 주소가 고정이다(/몽둥이기사-단). 거기에 모든 회차
 * 링크가 최신순으로 있으므로, 그 페이지 하나만 보면 최신 회차를 바로 알 수 있다.
 * 0을 채워 쓰든("074화") 회차마다 부제가 바뀌든 상관이 없다.
 *
 * 목록 주소는 회차 주소에서 추측한다. 밑줄을 붙임표로 바꾸고 회차 부분을 떼면 된다.
 * 추측이 빗나가는 작품은 사용자가 직접 목록 주소를 넣을 수 있고, 그마저 없으면
 * 예전 방식(다음 회차 주소 열어보기 / 보던 페이지 링크 훑기)으로 물러선다.
 */
object EpisodeCheck {

    data class Result(
        val comicId: String,
        val status: NextStatus,
        val note: String?,
        /** 목록 페이지에서 읽어낸 최신 회차. 못 읽었으면 null. */
        val latestEp: Int? = null,
        /** 최신 회차의 실제 주소. 표기가 바뀌는 작품은 지어낼 수 없어 그대로 담는다. */
        val latestPath: String? = null,
    )

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    /** 목록 페이지는 최신 회차가 위에 있으므로 앞부분만 받으면 된다. */
    private const val LIST_BYTES = 192 * 1024
    /** 링크를 훑어야 할 때 뒤쪽만 받아보는 크기. 이전/다음 링크는 문서 아래쪽에 있다. */
    private const val TAIL_BYTES = 96 * 1024
    /** 뒤쪽만으로 못 찾았을 때 통째로 받는 한도. */
    private const val FULL_BYTES = 512 * 1024
    /** 동시에 너무 많이 물면 사이트가 끊는다. */
    private const val CONCURRENCY = 2
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val pool = Executors.newFixedThreadPool(CONCURRENCY)
    private val main = Handler(Looper.getMainLooper())

    /** 결과는 확인이 끝나는 대로 하나씩 onEach 로 돌려준다. 전부 끝나면 onDone. */
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
                val result = try {
                    check(domain, c)
                } catch (e: Exception) {
                    Result(c.id, NextStatus.FAILED, e.javaClass.simpleName)
                }
                main.post {
                    onEach(result)
                    if (remaining.decrementAndGet() == 0) onDone()
                }
            }
        }
    }

    private fun check(domain: SiteUrl.Domain, comic: Comic): Result {
        val ref = SiteUrl.parseEpisode(comic.path)
            ?: return Result(comic.id, NextStatus.NO_EPISODE, "주소에 회차 번호가 없습니다")

        // 1순위: 작품 목록 페이지. 최신 회차가 그대로 적혀 있어 가장 확실하다.
        checkByListPage(domain, comic, ref)?.let { return it }

        // 목록 주소를 못 맞혔을 때만 예전 방식으로 물러선다.
        return if (SiteUrl.hasSimpleTail(ref)) {
            checkByNextUrl(domain, comic, ref)
        } else {
            checkByLinks(domain, comic, ref)
        }
    }

    /**
     * 작품 목록 페이지에서 최신 회차를 읽는다.
     * 목록을 못 열었거나 회차 링크가 없으면 null 을 돌려 다른 방법에 넘긴다.
     */
    private fun checkByListPage(
        domain: SiteUrl.Domain,
        comic: Comic,
        ref: SiteUrl.Episode,
    ): Result? {
        val listPath = comic.listPath?.takeIf { it.isNotBlank() } ?: SiteUrl.guessListPath(ref)
        if (listPath.isNullOrBlank()) return null

        val page = fetch(SiteUrl.buildUrl(domain, listPath), maxBytes = LIST_BYTES) ?: return null
        if (page.status !in 200..299) return null

        val latest = findLatest(page, ref) ?: return null
        return Result(
            comic.id,
            if (latest.ep > ref.ep) NextStatus.YES else NextStatus.NO,
            null,
            latestEp = latest.ep,
            latestPath = latest.path,
        )
    }

    /** 회차 숫자만 하나 올린 주소가 열리는지 본다. 본문은 받지 않는다. */
    private fun checkByNextUrl(domain: SiteUrl.Domain, comic: Comic, ref: SiteUrl.Episode): Result {
        val nextPath = SiteUrl.buildEpisodePath(ref, ref.ep + 1)
        val url = SiteUrl.buildUrl(domain, nextPath)

        // 모바일 회선은 첫 연결이 종종 끊긴다. 한 번은 다시 시도한다.
        val page = fetch(url, maxBytes = 0) ?: fetch(url, maxBytes = 0)
            ?: return Result(comic.id, NextStatus.FAILED, "접속하지 못했습니다 (시간 초과 또는 연결 끊김)")

        return when {
            page.status == 404 || page.status == 410 -> Result(comic.id, NextStatus.NO, null)

            page.status == 200 -> {
                // 없는 주소를 홈이나 목록으로 돌려보내는 사이트가 있다.
                // 그런 경우 최종 주소가 요청한 파일이 아니게 된다.
                val wanted = nextPath.substringAfterLast('/')
                val landed = page.finalUrl?.substringAfterLast('/') ?: wanted
                if (landed.equals(wanted, ignoreCase = true)) {
                    Result(comic.id, NextStatus.YES, null)
                } else {
                    Result(comic.id, NextStatus.NO, null)
                }
            }

            else -> Result(comic.id, NextStatus.FAILED, "사이트가 ${page.status} 로 응답했습니다")
        }
    }

    /**
     * 부제가 붙는 작품용. 주소를 지어낼 수 없으므로 보던 페이지의 링크를 훑는다.
     * 회차 번호 앞부분까지만 맞춰보고 뒤의 숫자를 읽으므로 부제가 무엇이든 걸린다.
     *
     * 이 사이트는 광고가 많아 페이지가 무겁다. 이전/다음 링크는 문서 아래쪽에 있으니
     * 먼저 끝부분만 요청해 본다. 서버가 구간 요청을 받아주지 않거나 거기서 링크를
     * 못 찾으면 그때만 통째로 받는다.
     */
    private fun checkByLinks(domain: SiteUrl.Domain, comic: Comic, ref: SiteUrl.Episode): Result {
        val url = SiteUrl.buildUrl(domain, comic.path)

        val tail = fetch(url, maxBytes = TAIL_BYTES, tailOnly = true)
        findLatest(tail, ref)?.let {
            return Result(
                comic.id,
                if (it.ep > ref.ep) NextStatus.YES else NextStatus.NO,
                null,
                latestEp = it.ep,
                latestPath = it.path,
            )
        }

        val whole = fetch(url, maxBytes = FULL_BYTES)
            ?: return Result(comic.id, NextStatus.FAILED, "접속하지 못했습니다 (시간 초과 또는 연결 끊김)")
        if (whole.status !in 200..299) {
            return Result(comic.id, NextStatus.FAILED, "사이트가 ${whole.status} 로 응답했습니다")
        }

        val latest = findLatest(whole, ref)
            ?: return Result(comic.id, NextStatus.FAILED, "페이지에서 회차 링크를 찾지 못했습니다")
        return Result(
            comic.id,
            if (latest.ep > ref.ep) NextStatus.YES else NextStatus.NO,
            null,
            latestEp = latest.ep,
            latestPath = latest.path,
        )
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
        /**
         * 문서에 적힌 인코딩이 실제와 다른 한국 사이트가 많다.
         * 후보를 여러 개 만들어 그중 하나에서라도 회차가 잡히면 쓴다.
         */
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

    private fun fetch(url: String, maxBytes: Int, tailOnly: Boolean = false): Page? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                // 뒤쪽만 달라고 요청한다. 서버가 안 받아주면 그냥 전체를 보내온다.
                if (tailOnly) setRequestProperty("Range", "bytes=-$maxBytes")
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = if (maxBytes > 0) stream?.use { read(it, maxBytes) } ?: ByteArray(0) else ByteArray(0)
            if (maxBytes == 0) runCatching { stream?.close() }
            Page(status, body, conn.contentType, conn.url?.toString())
        } catch (e: Exception) {
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
