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
 * 주소의 회차 숫자만 하나 올려서 그 페이지가 있는지 본다. 200이면 나온 것이고
 * 404면 아직 없는 것이다. 본문을 읽지 않으므로 "074화" 처럼 0을 채워 쓰든 말든
 * 상관이 없다. 주소를 만들 때 0은 그대로 유지되기 때문이다.
 *
 * 다만 "천마는_..._209화_:_부제.html" 처럼 회차마다 부제가 바뀌는 작품은
 * 숫자만 올리면 없는 주소가 된다. 그런 작품만 현재 페이지의 링크를 훑어
 * 가장 큰 회차를 찾는다.
 */
object EpisodeCheck {

    data class Result(val comicId: String, val status: NextStatus, val note: String?)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    /** 링크를 훑어야 하는 경우에만 본문을 받는다. 다음 화 링크는 문서 아래쪽에 있다. */
    private const val MAX_BYTES_FOR_LINKS = 512 * 1024
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

        return if (SiteUrl.hasSimpleTail(ref)) {
            checkByNextUrl(domain, comic, ref)
        } else {
            checkByLinks(domain, comic, ref)
        }
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
     */
    private fun checkByLinks(domain: SiteUrl.Domain, comic: Comic, ref: SiteUrl.Episode): Result {
        val url = SiteUrl.buildUrl(domain, comic.path)
        val page = fetch(url, maxBytes = MAX_BYTES_FOR_LINKS)
            ?: return Result(comic.id, NextStatus.FAILED, "접속하지 못했습니다 (시간 초과 또는 연결 끊김)")
        if (page.status != 200) {
            return Result(comic.id, NextStatus.FAILED, "사이트가 ${page.status} 로 응답했습니다")
        }

        val max = page.decodings().mapNotNull { SiteUrl.maxLinkedEpisode(it, ref) }.maxOrNull()
            ?: return Result(comic.id, NextStatus.FAILED, "페이지에서 회차 링크를 찾지 못했습니다")

        return Result(comic.id, if (max > ref.ep) NextStatus.YES else NextStatus.NO, null)
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

    private fun fetch(url: String, maxBytes: Int): Page? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
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
