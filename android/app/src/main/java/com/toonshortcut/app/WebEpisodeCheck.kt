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
 * 그래서 사이트 페이지를 하나 띄운 뒤, 그 안에서 자바스크립트로 페이지들을
 * 가져온다. 같은 사이트라 제약이 없고 WebView 의 네트워크를 그대로 탄다.
 *
 * 판단은 사이트 화면 그대로 한다. 저장된 회차 페이지를 열어서 사이트의
 * "다음 화" 버튼이 살아 있으면 새 회차가 있는 것이고, 꺼져 있으면 최신이다.
 * 몇 화가 남았는지는 세지 않는다.
 *
 * 예전에는 주소에서 회차 번호를 뽑고 목록 페이지에서 가장 큰 번호를 찾아
 * 견줬다. 부제, 해시, 시즌, 제/EP, 전각 마침표처럼 사이트가 이름 짓는 방식이
 * 바뀔 때마다 빗나갔다. 이제 주소는 열기만 하고 해석하지 않는다.
 *
 * 버튼 자체를 못 찾으면 "최신" 이라고 하지 않고 실패로 둔다. 사이트 화면이
 * 바뀐 것이라 판단할 근거가 없기 때문이다.
 */
class WebEpisodeCheck(
    private val web: WebView,
    private val domain: SiteUrl.Domain,
) {

    data class Outcome(
        val comicId: String,
        /** 다음 화가 있으면 true, 마지막 화면 false. 판단하지 못했으면 null. */
        val hasNext: Boolean?,
        /** 사이트의 다음 화 버튼이 가리키는 주소 */
        val nextPath: String?,
        val error: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private var finished = false
    /** 기준 페이지가 한 번이라도 열렸는지. 못 열었을 때만 그쪽 시간 제한을 적용한다. */
    private var baseLoaded = false
    /** 지금까지 끝난 결과. 시간이 다 돼도 여기까지는 살린다. */
    private var lastResults: JSONArray? = null

    private companion object {
        /** 확인 전체에 걸어줄 최대 시간 */
        const val TOTAL_TIMEOUT_MS = 180_000L
        /** 기준 페이지가 열리기를 기다리는 최대 시간 */
        const val BASE_TIMEOUT_MS = 60_000L
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
            if (c.path.isBlank()) {
                log.append("[${c.title}] 저장된 회차 주소가 없음\n")
                continue
            }
            tasks.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("url", c.path),
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
                baseLoaded = true
                log.append("기준 페이지 열림: $url\n")
                web.evaluateJavascript(buildScript(tasks), null)
                poll(tasks.length(), log, onProgress, onDone, System.currentTimeMillis())
            }
        }
        web.loadUrl(root)

        // 기준 페이지조차 못 열면 폴링이 시작되지 않으므로 여기서도 시간을 잰다.
        // 열렸으면 손대지 않는다.
        main.postDelayed({
            if (!finished && !baseLoaded) {
                finished = true
                log.append("기준 페이지를 열지 못했습니다. 주소 번호가 맞는지 확인해 주세요.\n")
                onDone(emptyList(), log.toString())
            }
        }, BASE_TIMEOUT_MS)
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
                if (results != null) lastResults = results
                if (state.optBoolean("finished", false) && results != null) {
                    finished = true
                    onDone(toOutcomes(results, log), log.toString())
                    return@evaluateJavascript
                }
            }
            if (System.currentTimeMillis() - startedAt > TOTAL_TIMEOUT_MS) {
                finished = true
                // 끝난 것은 살리고 나머지만 "확인하지 못했습니다" 로 남긴다.
                val partial = lastResults ?: JSONArray()
                log.append("시간이 너무 오래 걸려 ${partial.length()}/$total 편까지만 확인하고 멈췄습니다.\n")
                onDone(toOutcomes(partial, log), log.toString())
                return@evaluateJavascript
            }
            main.postDelayed({ poll(total, log, onProgress, onDone, startedAt) }, POLL_MS)
        }
    }

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
            val status = o.optInt("status", 0)
            val size = o.optInt("size", 0)
            val fetchError = o.optString("error", "").ifEmpty { null }
            val nextPath = o.optString("next", "").takeIf { it.startsWith("/") }
            val hint = o.optString("hint", "")

            val outcome = when {
                fetchError != null -> Outcome(id, null, null, "$fetchError (상태 $status)")
                o.optString("state") == "next" -> Outcome(id, true, nextPath, null)
                o.optString("state") == "last" -> Outcome(id, false, null, null)
                else -> Outcome(id, null, null, "다음 화 버튼을 찾지 못했습니다")
            }

            log.append("[$title] ")
            when {
                outcome.error != null -> log.append("실패: ${outcome.error}")
                outcome.hasNext == true -> log.append("새 회차 있음 → ${nextPath?.let { SiteUrl.decodeUri(it) } ?: "(주소 없음)"}")
                else -> log.append("최신 (다음 화 버튼 꺼짐${if (hint.isNotEmpty()) ": $hint" else ""})")
            }
            log.append("  (상태 $status, ${size}바이트)\n")
            // 실패했을 때는 무엇을 열었고 거기서 무엇을 봤는지가 가장 중요한 단서다.
            if (outcome.error != null) {
                o.optString("url", "").takeIf { it.isNotEmpty() }?.let {
                    log.append("    저장된 회차: ${SiteUrl.decodeUri(it)}\n")
                }
                if (hint.isNotEmpty()) log.append("    아래쪽 바: ${hint.take(300)}\n")
            }
            out.add(outcome)
        }
        return out
    }

    /**
     * 저장된 회차 페이지들을 가져와 사이트의 다음 화 버튼을 본다.
     *
     * 버튼이 꺼진 표시는 셋 중 하나다: class 에 dis, 주소가 javascript:, 주소가
     * 비었거나 #. 마지막 화에서 실제로 앞의 둘이 함께 나온다.
     */
    private fun buildScript(tasks: JSONArray): String = """
        (function () {
          var tasks = $tasks;
          var NEXT_SEL = ${JSONObject.quote(SiteUrl.NEXT_LINK_SELECTOR)};
          var results = [];
          var idx = 0;
          window.__toonState = { done: 0, finished: false, results: [] };

          function judge(html, pagePath) {
            var doc = new DOMParser().parseFromString(html, "text/html");
            var a = doc.querySelector(NEXT_SEL);
            if (!a) {
              // 판단할 수 없다. 무엇이 있었는지만 남긴다.
              var nav = doc.querySelector(".toon-nav, .bottom-navbar");
              return { state: "nobutton", hint: nav ? nav.outerHTML.replace(/\s+/g, " ").slice(0, 300) : "" };
            }
            var href = (a.getAttribute("href") || "").trim();
            var off = a.classList.contains("dis") || a.classList.contains("disabled") ||
                      href === "" || href === "#" || /^javascript:/i.test(href);
            var title = a.getAttribute("title") || "";
            if (off) return { state: "last", hint: title };
            var u = new URL(href, new URL(pagePath, location.origin));
            var next = u.origin === location.origin ? u.pathname + u.search : "";
            return { state: "next", next: next, hint: title };
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
                var e = { id: t.id, title: t.title, url: t.url, status: status, size: html.length };
                if (status !== 200) {
                  e.error = "저장된 회차 페이지가 열리지 않음";
                } else {
                  var j = judge(html, t.url);
                  e.state = j.state;
                  e.next = j.next || "";
                  e.hint = j.hint || "";
                }
                record(e);
              })
              .catch(function (err) {
                record({ id: t.id, title: t.title, url: t.url, status: status, size: 0,
                         error: String((err && err.message) || err) });
              });
          }

          for (var k = 0; k < $CONCURRENCY && k < tasks.length; k++) next();
        })()
    """.trimIndent()
}
