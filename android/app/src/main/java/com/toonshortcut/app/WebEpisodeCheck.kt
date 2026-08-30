package com.toonshortcut.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 새 회차 확인을 WebView 안에서 한다.
 *
 * 앱에서 직접 HTTP 로 요청하면 전부 "Connection reset" 으로 끊긴다. 통신사가
 * TLS 접속 시작 부분에 평문으로 노출되는 주소를 보고 연결을 끊기 때문이다.
 * 같은 폰 같은 회선인데도 WebView 로는 열린다. 최신 브라우저는 그 부분을
 * 감추는 기능을 쓰기 때문이다.
 *
 * 그래서 사이트 페이지를 하나 띄운 뒤, 그 안에서 자바스크립트로 목록 페이지들을
 * 가져온다. 같은 사이트라 제약이 없고 WebView 의 네트워크를 그대로 탄다.
 *
 * 페이지 쪽 자바스크립트와 같은 공간에서 도는 점은 감안했다. 주고받는 것은
 * 회차 번호와 주소뿐이고, 값이 이상하면 무시한다.
 */
class WebEpisodeCheck(
    private val web: WebView,
    private val domain: SiteUrl.Domain,
) {

    data class Outcome(
        val comicId: String,
        val ep: Int?,
        val path: String?,
        val error: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private var finished = false

    private companion object {
        /** 목록 페이지 하나를 가져오는 데 걸어줄 최대 시간 */
        const val TOTAL_TIMEOUT_MS = 120_000L
        const val POLL_MS = 400L
        const val CONCURRENCY = 3
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(
        comics: List<Comic>,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (List<Outcome>, log: String) -> Unit,
    ) {
        val log = StringBuilder()
        val tasks = JSONArray()
        for (c in comics) {
            val ref = SiteUrl.parseEpisode(c.path)
            if (ref == null) {
                log.append("[${c.title}] 경로에 회차 번호 없음\n")
                continue
            }
            val listPath = c.listPath?.takeIf { it.isNotBlank() } ?: SiteUrl.guessListPath(ref)
            if (listPath.isNullOrBlank()) {
                log.append("[${c.title}] 목록 주소를 만들지 못함\n")
                continue
            }
            tasks.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("url", listPath)
                    .put("enc", SiteUrl.encodeUri(ref.before).substringAfterLast('/'))
                    .put("dec", ref.before.substringAfterLast('/'))
                    .put("ep", ref.ep),
            )
        }

        if (tasks.length() == 0) {
            onDone(emptyList(), log.toString())
            return
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        val root = "https://" + SiteUrl.buildHost(domain) + "/"
        log.append("기준 페이지: $root\n")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (finished) return
                log.append("기준 페이지 열림: $url\n")
                web.evaluateJavascript(buildScript(tasks), null)
                poll(tasks.length(), log, onProgress, onDone, System.currentTimeMillis())
            }
        }
        web.loadUrl(root)

        // 기준 페이지조차 못 열면 폴링이 시작되지 않으므로 여기서도 시간을 잰다.
        main.postDelayed({
            if (!finished) {
                finished = true
                log.append("기준 페이지를 열지 못했습니다. 주소 번호가 맞는지 확인해 주세요.\n")
                onDone(emptyList(), log.toString())
            }
        }, TOTAL_TIMEOUT_MS)
    }

    private fun poll(
        total: Int,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit,
        onDone: (List<Outcome>, String) -> Unit,
        startedAt: Long,
    ) {
        if (finished) return
        web.evaluateJavascript("JSON.stringify(window.__toonState || null)") { raw ->
            if (finished) return@evaluateJavascript
            val state = parseState(raw)
            if (state != null) {
                onProgress(state.optInt("done", 0), total)
                val results = state.optJSONArray("results")
                if (state.optBoolean("finished", false) && results != null) {
                    finished = true
                    onDone(toOutcomes(results, log), log.toString())
                    return@evaluateJavascript
                }
            }
            if (System.currentTimeMillis() - startedAt > TOTAL_TIMEOUT_MS) {
                finished = true
                log.append("시간이 너무 오래 걸려 중단했습니다.\n")
                onDone(emptyList(), log.toString())
                return@evaluateJavascript
            }
            main.postDelayed({ poll(total, log, onProgress, onDone, startedAt) }, POLL_MS)
        }
    }

    /** evaluateJavascript 는 결과를 JSON 문자열로 한 번 더 감싸서 준다. */
    private fun parseState(raw: String?): JSONObject? {
        if (raw == null || raw == "null" || raw == "\"null\"") return null
        return try {
            val inner = if (raw.startsWith("\"")) JSONArray("[$raw]").getString(0) else raw
            if (inner == "null") null else JSONObject(inner)
        } catch (e: Exception) {
            null
        }
    }

    private fun toOutcomes(results: JSONArray, log: StringBuilder): List<Outcome> {
        val out = mutableListOf<Outcome>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val title = o.optString("title", "?")
            val error = o.optString("error", "").ifEmpty { null }
            val ep = if (o.has("ep") && !o.isNull("ep")) o.optInt("ep") else null
            val path = o.optString("path", "").ifEmpty { null }

            log.append("[$title] ")
            when {
                error != null -> log.append("실패: $error")
                ep != null -> log.append("최신 ${ep}화, 주소 ${SiteUrl.decodeUri(path ?: "")}")
                else -> log.append("회차 링크를 찾지 못함")
            }
            log.append("  (상태 ${o.optInt("status", 0)}, ${o.optInt("size", 0)}바이트)\n")
            o.optJSONArray("samples")?.let { s ->
                for (k in 0 until s.length()) log.append("    표본: ${s.optString(k)}\n")
            }
            out.add(Outcome(id, ep, path, error))
        }
        return out
    }

    /**
     * 목록 페이지들을 가져와 이 작품의 회차 링크 중 가장 큰 번호를 찾는다.
     * 제목과 숫자 사이에 "EP." 같은 표시가 끼는 작품이 있어 짧은 글자는 건너뛴다.
     */
    private fun buildScript(tasks: JSONArray): String = """
        (function () {
          var tasks = $tasks;
          var results = [];
          var idx = 0;
          window.__toonState = { done: 0, finished: false, results: [] };

          var LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._-";

          function numberAfter(hay, at, needleLen) {
            var j = at + needleLen, skipped = 0, digits = "";
            while (j < hay.length && skipped < 6 &&
                   !(hay.charAt(j) >= "0" && hay.charAt(j) <= "9") &&
                   LETTERS.indexOf(hay.charAt(j)) !== -1) { j++; skipped++; }
            while (j < hay.length && hay.charAt(j) >= "0" && hay.charAt(j) <= "9") {
              digits += hay.charAt(j); j++;
            }
            return digits.length ? parseInt(digits, 10) : null;
          }

          function scan(html, enc, dec) {
            var best = null;
            var re = /href\s*=\s*["']([^"'>]+)["']/gi;
            var m;
            while ((m = re.exec(html)) !== null) {
              var href = m[1];
              var pairs = [[enc.toLowerCase(), href.toLowerCase()], [dec, href]];
              for (var i = 0; i < pairs.length; i++) {
                var needle = pairs[i][0], hay = pairs[i][1];
                if (!needle) continue;
                var at = hay.indexOf(needle);
                if (at < 0) continue;
                var v = numberAfter(hay, at, needle.length);
                if (v !== null && (best === null || v > best.ep)) best = { ep: v, path: href };
                break;
              }
            }
            return best;
          }

          function samples(html) {
            var out = [];
            var re = /href\s*=\s*["']([^"'>]+)["']/gi;
            var m;
            while ((m = re.exec(html)) !== null && out.length < 5) {
              if (m[1].indexOf(".htm") >= 0) out.push(m[1]);
            }
            return out;
          }

          function record(entry) {
            results.push(entry);
            window.__toonState = {
              done: results.length,
              finished: results.length >= tasks.length,
              results: results
            };
            next();
          }

          function next() {
            if (idx >= tasks.length) return;
            var t = tasks[idx++];
            var status = 0;
            fetch(t.url, { credentials: "omit" })
              .then(function (r) { status = r.status; return r.text(); })
              .then(function (html) {
                var best = scan(html, t.enc, t.dec);
                var e = { id: t.id, title: t.title, status: status, size: html.length };
                if (best) { e.ep = best.ep; e.path = best.path; }
                else { e.error = "회차 링크 없음"; e.samples = samples(html); }
                record(e);
              })
              .catch(function (err) {
                record({ id: t.id, title: t.title, status: status, size: 0,
                         error: String((err && err.message) || err) });
              });
          }

          for (var k = 0; k < $CONCURRENCY && k < tasks.length; k++) next();
        })()
    """.trimIndent()
}
