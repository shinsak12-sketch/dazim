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
            o.optString("needles", "").takeIf { it.isNotEmpty() }?.let {
                log.append("    제목 발견 횟수: $it\n")
            }
            o.optString("around", "").takeIf { it.isNotEmpty() }?.let {
                log.append("    주변: ${it.replace("\n", " ").take(280)}\n")
            }
            out.add(Outcome(id, ep, path, error))
        }
        return out
    }

    /**
     * 목록 페이지들을 가져와 이 작품의 가장 큰 회차 번호를 찾는다.
     *
     * href 만 뒤지면 안 된다. 이 사이트는 회차를 링크 태그로 걸지 않아서
     * 페이지 전체에 .html 링크가 거의 없다. 그래서 문서 전체에서 제목 뒤에
     * 오는 숫자를 찾는다.
     *
     * 제목이 나타나는 형태가 셋이라 모두 본다. 주소에 인코딩된 형태,
     * 주소에 한글 그대로인 형태, 그리고 화면에 보이는 글자(밑줄 대신 띄어쓰기).
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

          // 링크가 href 가 아니라 onclick 이나 data- 속성에 들어 있을 수 있다.
          // 앞뒤로 가장 가까운 따옴표를 찾아 그 안의 값을 주소로 본다.
          function quotedAround(html, at) {
            var from = Math.max(0, at - 400);
            var s = -1;
            for (var i = at; i >= from; i--) {
              var c = html.charAt(i);
              if (c === '"' || c === "'") { s = i; break; }
              if (c === "<" || c === ">") break;
            }
            if (s < 0) return null;
            var q = html.charAt(s);
            var e = html.indexOf(q, s + 1);
            if (e < 0 || e - s > 500) return null;
            var token = html.substring(s + 1, e);
            if (token.indexOf(".htm") < 0 && token.charAt(0) !== "/") return null;
            return token;
          }

          function scan(html, lower, needles) {
            var best = null;
            for (var i = 0; i < needles.length; i++) {
              var needle = needles[i].n;
              if (!needle) continue;
              var hay = needles[i].lower ? lower : html;
              var at = 0;
              while ((at = hay.indexOf(needle, at)) !== -1) {
                var v = numberAfter(hay, at, needle.length);
                if (v !== null && (best === null || v > best.ep)) {
                  best = { ep: v, path: quotedAround(html, at) };
                }
                at += needle.length;
              }
            }
            return best;
          }

          // 못 찾았을 때 무엇을 봤는지 남긴다. 짐작으로 고치면 또 빗나간다.
          function diagnose(html, lower, needles, entry) {
            var counts = [];
            var firstAt = -1;
            for (var i = 0; i < needles.length; i++) {
              var needle = needles[i].n;
              if (!needle) continue;
              var hay = needles[i].lower ? lower : html;
              var n = 0, at = 0;
              while ((at = hay.indexOf(needle, at)) !== -1) { n++; at += needle.length; if (n > 50) break; }
              counts.push(needles[i].label + "=" + n);
              if (n > 0 && firstAt < 0) firstAt = hay.indexOf(needle);
            }
            entry.needles = counts.join(", ");
            if (firstAt >= 0) {
              entry.around = html.substring(Math.max(0, firstAt - 120), firstAt + 160);
            }
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
                var lower = html.toLowerCase();
                var needles = [
                  { n: t.enc.toLowerCase(), lower: true, label: "인코딩" },
                  { n: t.dec, lower: false, label: "한글밑줄" },
                  { n: t.dec.replace(/_/g, " "), lower: false, label: "화면글자" }
                ];
                var best = scan(html, lower, needles);
                var e = { id: t.id, title: t.title, status: status, size: html.length };
                if (best) { e.ep = best.ep; if (best.path) e.path = best.path; }
                else { e.error = "회차 번호를 찾지 못함"; diagnose(html, lower, needles, e); }
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
