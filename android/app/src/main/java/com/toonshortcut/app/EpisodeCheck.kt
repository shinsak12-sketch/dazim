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
 * 보고 있던 페이지를 받아서 "다음 화로 가는 링크"가 있는지만 본다.
 * 뷰어 하단의 오른쪽 화살표가 곧 그 링크다.
 *
 * "(총93화)" 같은 개수 표기는 쓰지 않는다. 0화가 있는 작품은 개수와 회차
 * 번호가 어긋나서(92화가 마지막인데 총93화) 잘못된 답이 나온다.
 *
 * 링크를 못 찾으면 다음 회차 주소를 직접 열어보는 것으로 한 번 더 확인한다.
 */
object EpisodeCheck {

    data class Result(val comicId: String, val status: NextStatus, val error: String?)

    private const val TIMEOUT_MS = 12000
    private const val MAX_BYTES = 512 * 1024
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val pool = Executors.newFixedThreadPool(3)
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
                    Result(c.id, NextStatus.FAILED, e.message ?: "확인 실패")
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
            ?: return Result(comic.id, NextStatus.FAILED, "회차 번호를 못 찾음")
        val next = ref.ep + 1

        // 1순위: 보던 페이지에 다음 화 링크가 있는지 (요청 한 번)
        val page = fetch(SiteUrl.buildUrl(domain, comic.path))
            ?: return Result(comic.id, NextStatus.FAILED, "사이트에 접속하지 못함")
        if (page.status == 200 && page.decodings().any { SiteUrl.linksToEpisode(it, ref, next) }) {
            return Result(comic.id, NextStatus.YES, null)
        }

        // 2순위: 링크 형태가 다를 수 있으니 다음 화 주소를 직접 열어본다.
        val nextPage = fetch(SiteUrl.buildUrl(domain, SiteUrl.buildEpisodePath(ref, next)))
        val exists = nextPage != null &&
            nextPage.status == 200 &&
            // 없는 회차에 404 대신 200을 주는 사이트가 있어 내용까지 확인한다.
            nextPage.decodings().any { SiteUrl.looksLikeEpisode(it, next) }

        return Result(comic.id, if (exists) NextStatus.YES else NextStatus.NO, null)
    }

    private class Page(val status: Int, val body: ByteArray, val contentType: String?) {
        /**
         * 한국 사이트는 UTF-8 과 EUC-KR 이 섞여 있고 헤더가 틀린 경우도 있다.
         * 후보를 여러 개 만들어 그중 하나에서라도 원하는 문구가 잡히면 쓴다.
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
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
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
