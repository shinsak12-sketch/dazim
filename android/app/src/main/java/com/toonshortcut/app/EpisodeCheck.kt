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
 * 다음 회차 주소를 직접 열어보고 그 회차의 페이지가 맞는지 확인한다.
 * 없는 회차에 404 대신 200과 안내 페이지를 주는 사이트가 있어서
 * 상태 코드만으로는 판단하지 않는다.
 *
 * 이 사이트는 광고가 많아 페이지가 무겁다. 회차 번호는 문서 앞쪽 제목에
 * 나오므로 앞부분만 읽고 연결을 끊는다. 전부 받으면 모바일 회선에서
 * 시간 초과로 실패하기 쉽다.
 */
object EpisodeCheck {

    data class Result(val comicId: String, val status: NextStatus, val note: String?)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    /** 제목이 나오는 앞부분만 읽는다. 뒤쪽 광고까지 받을 이유가 없다. */
    private const val MAX_BYTES = 96 * 1024
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

        val next = ref.ep + 1
        val url = SiteUrl.buildUrl(domain, SiteUrl.buildEpisodePath(ref, next))

        // 한 번은 다시 시도한다. 모바일 회선은 첫 연결이 종종 끊긴다.
        var page = fetch(url)
        if (page == null) page = fetch(url)
        if (page == null) return Result(comic.id, NextStatus.FAILED, "접속하지 못했습니다 (시간 초과 또는 연결 끊김)")

        return when {
            page.status == 200 && page.decodings().any { SiteUrl.looksLikeEpisode(it, next) } ->
                Result(comic.id, NextStatus.YES, null)

            // 404 거나, 200이어도 그 회차 페이지가 아니면 아직 안 나온 것으로 본다.
            page.status == 200 || page.status == 404 || page.status == 410 ->
                Result(comic.id, NextStatus.NO, null)

            else -> Result(comic.id, NextStatus.FAILED, "사이트가 ${page.status} 로 응답했습니다")
        }
    }

    private class Page(val status: Int, val body: ByteArray, val contentType: String?) {
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

    private fun fetch(url: String): Page? {
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
            val body = stream?.use { read(it) } ?: ByteArray(0)
            Page(status, body, conn.contentType)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun read(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < MAX_BYTES) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
