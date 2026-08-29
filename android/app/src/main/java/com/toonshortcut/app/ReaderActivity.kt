package com.toonshortcut.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject

/**
 * 만화 사이트를 앱 안에서 직접 연다.
 *
 * 주소 번호는 앱이 스스로 바꾸지 않는다. 막혔을 때 다음 번호가 맞는지는 추측이고,
 * 틀리면 되돌리기가 번거롭기 때문이다. 회차는 실제로 그 페이지를 열었다는
 * 사실이라 추측이 아니므로 자동으로 저장한다.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COMIC_ID = "comic_id"
        /** 목록에서 "새 회차 보기"로 들어올 때 건너뛸 회차 */
        const val EXTRA_EPISODE = "episode"
    }

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var titleView: TextView
    private lateinit var hostView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorBar: LinearLayout
    private lateinit var errorText: TextView

    private var comicId: String = ""

    private val comic: Comic?
        get() = store.comics.firstOrNull { it.id == comicId }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        comicId = intent.getStringExtra(EXTRA_COMIC_ID) ?: ""

        if (comic == null) {
            Toast.makeText(this, "만화를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 목록에서 새 회차로 바로 들어온 경우 그 회차부터 연다.
        val jumpTo = intent.getIntExtra(EXTRA_EPISODE, -1)
        if (jumpTo > 0) {
            val ref = comic?.let { SiteUrl.parseEpisode(it.path) }
            if (ref != null && ref.ep != jumpTo) {
                store.updateComic(comicId, path = SiteUrl.buildEpisodePath(ref, jumpTo))
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // 조작은 전부 아래에 둔다. 한 손으로 들었을 때 엄지가 닿는 곳이다.
        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.WHITE)
        }
        swipe = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setColorSchemeColors(Ui.ACCENT)
            setProgressBackgroundColorSchemeColor(Ui.SURFACE)
            // 맨 위에서 아래로 당기면 새로고침. 브라우저와 같은 동작이다.
            setOnRefreshListener { load() }
            addView(web)
        }
        root.addView(swipe)

        root.addView(buildErrorBar())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            visibility = View.GONE
        }
        root.addView(progress)

        root.addView(buildBottomBar())

        setContentView(root)
        root.padForSystemBars()

        configureWebView()
        refreshTitle()
        load()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    // ------------------------------------------------------------------ 상단 바

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        bar.addView(softButton("☰") { showMenu() })

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(8)
            }
        }
        titleView = TextView(this).apply {
            textSize = 13f
            maxLines = 1
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT)
        }
        hostView = TextView(this).apply {
            textSize = 10.5f
            maxLines = 1
            setTextColor(Ui.TEXT_FAINT)
        }
        texts.addView(titleView)
        texts.addView(hostView)
        bar.addView(texts)

        bar.addView(softButton("+1") { bumpDomain(1) })
        bar.addWithGap(accentButton("저장") { saveCurrent() }, dp(6))
        bar.addWithGap(softButton("목록") { finish() }, dp(6))
        return bar
    }

    private fun buildErrorBar(): View {
        errorBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.alpha(Ui.AMBER, 30), 0, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Ui.AMBER)
        }
        errorBar.addView(errorText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        row.addView(softButton("주소 +1") { bumpDomain(1) })
        row.addWithGap(softButton("번호 입력") { askDomainNumber() }, dp(6))
        row.addWithGap(softButton("다시 시도") { load() }, dp(6))
        row.addWithGap(softButton("닫기") { errorBar.visibility = View.GONE }, dp(6))
        errorBar.addView(row)
        return errorBar
    }

    private fun softButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            Ui.tappable(this, Ui.rounded(Ui.SURFACE_HI, 12, context, Ui.BORDER))
            setOnClickListener { onClick() }
        }

    private fun accentButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.ON_ACCENT)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            Ui.tappable(this, Ui.rounded(Ui.ACCENT, 12, context))
            setOnClickListener { onClick() }
        }

    /**
     * 왼쪽 여백을 주며 붙인다.
     * addView(View, Int) 로 만들면 ViewGroup 의 "삽입 위치" 오버로드와 겹쳐
     * 멤버 함수가 우선 선택되므로 이름을 따로 둔다.
     */
    private fun LinearLayout.addWithGap(view: View, leftGap: Int) {
        addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = leftGap })
    }

    private fun showMenu() {
        val d = store.domain
        val auto = store.autoSaveEpisode
        val items = arrayOf(
            "새로고침",
            "주소 번호 내리기 (${d.num} → ${d.num - 1})",
            "주소 번호 직접 입력",
            if (auto) "회차 자동 저장 끄기 (지금 켜짐)" else "회차 자동 저장 켜기 (지금 꺼짐)",
            "브라우저로 열기",
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> load()
                    1 -> bumpDomain(-1)
                    2 -> askDomainNumber()
                    3 -> {
                        store.autoSaveEpisode = !auto
                        toast(if (!auto) "회차를 자동으로 저장합니다." else "회차 자동 저장을 껐습니다.")
                        refreshTitle()
                    }
                    4 -> openInBrowser()
                }
            }
            .show()
    }

    private fun askDomainNumber() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.domain.num.toString())
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("주소 번호 직접 입력")
            .setView(box)
            .setPositiveButton("적용") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                store.setDomainNum(n)
                errorBar.visibility = View.GONE
                refreshTitle()
                load()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openInBrowser() {
        val url = web.url ?: currentUrl()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("열 수 있는 브라우저가 없습니다.")
        }
    }

    // ------------------------------------------------------------------ 저장

    /** 지금 보고 있는 주소를 이 만화에 반영한다. 주소 번호와 회차를 한꺼번에 저장한다. */
    private fun saveCurrent() {
        val url = web.url
        if (url == null) {
            toast("아직 페이지가 열리지 않았습니다.")
            return
        }
        val parsed = SiteUrl.parseInput(url)
        if (parsed == null) {
            toast("주소를 알아볼 수 없습니다.")
            return
        }

        val c = comic ?: return
        val old = SiteUrl.parseEpisode(c.path)
        val new = SiteUrl.parseEpisode(parsed.path)
        val differentSeries =
            old != null && new != null && (old.before != new.before || old.after != new.after)

        if (differentSeries) {
            AlertDialog.Builder(this)
                .setTitle("다른 작품 같습니다")
                .setMessage("\"${c.title}\" 을(를) 지금 보고 있는 주소로 바꿀까요?\n\n${SiteUrl.decodeUri(parsed.path)}")
                .setPositiveButton("바꾸기") { _, _ -> commitSave(parsed) }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        commitSave(parsed)
    }

    private fun commitSave(parsed: SiteUrl.Parsed) {
        val messages = mutableListOf<String>()

        parsed.domain?.let { d ->
            val cur = store.domain
            if (SiteUrl.sameShape(d, cur)) {
                if (d.num != cur.num) {
                    store.setDomainNum(d.num)
                    messages.add(SiteUrl.buildHost(store.domain))
                }
            } else {
                store.domain = d
                messages.add(SiteUrl.buildHost(d))
            }
        }

        val c = comic
        if (c != null && c.path != parsed.path) {
            store.updateComic(comicId, path = parsed.path)
            messages.add(SiteUrl.episodeLabel(parsed.path) ?: "주소")
        }

        errorBar.visibility = View.GONE
        refreshTitle()
        refreshNextFromPage()
        toast(if (messages.isEmpty()) "이미 저장된 주소입니다." else "저장: ${messages.joinToString(" · ")}")
    }

    /**
     * 같은 작품의 다른 회차로 넘어가면 알아서 저장한다.
     *
     * 회차 숫자 앞뒤 문자열이 똑같을 때만 같은 작품으로 본다.
     * 사이트 안에서 다른 작품으로 넘어가도 이 만화의 북마크를 덮어쓰지 않는다.
     */
    private fun autoSaveEpisode(url: String) {
        if (!store.autoSaveEpisode) return
        val c = comic ?: return
        val parsed = SiteUrl.parseInput(url) ?: return
        val old = SiteUrl.parseEpisode(c.path) ?: return
        val new = SiteUrl.parseEpisode(parsed.path) ?: return
        if (old.before != new.before || old.after != new.after) return // 다른 작품
        if (old.ep == new.ep) return

        store.updateComic(comicId, path = parsed.path)
        toast("${new.ep}화 저장")
    }

    /**
     * 지금 열린 페이지의 회차 링크를 훑어 목록의 표시를 갱신한다.
     *
     * 회차 번호 앞부분까지만 맞춰보고 뒤의 숫자를 읽는다. 부제가 붙는 작품도,
     * 0을 채워 쓰는 작품도 이 방식이면 같이 걸린다.
     *
     * 목록의 일괄 확인이 실패한 만화도 한 번 열어보면 여기서 결과가 채워진다.
     * 보고 있는 회차가 저장된 회차와 다를 때는 판단하지 않는다.
     * 그 결과는 다른 회차에 대한 것이라 저장해두면 틀린 정보가 된다.
     */
    private fun refreshNextFromPage() {
        val c = comic ?: return
        val ref = SiteUrl.parseEpisode(c.path) ?: return
        val viewing = web.url?.let { SiteUrl.parseInput(it) }?.let { SiteUrl.parseEpisode(it.path) }
        if (viewing == null || viewing.ep != ref.ep) return

        val encoded = JSONObject.quote(SiteUrl.encodeUri(ref.before).substringAfterLast('/').lowercase())
        val decoded = JSONObject.quote(ref.before.substringAfterLast('/'))

        // 정규식 이스케이프를 피하려고 문자열 검색으로 훑는다.
        // 제목에 . ( ) 같은 글자가 섞여도 안전하다.
        val js = """
            (function () {
              try {
                var html = document.documentElement.outerHTML;
                var lower = html.toLowerCase();
                var max = -1;
                var pairs = [[$encoded, lower], [$decoded, html]];
                for (var p = 0; p < pairs.length; p++) {
                  var needle = pairs[p][0];
                  var hay = pairs[p][1];
                  if (!needle) continue;
                  var at = 0;
                  while ((at = hay.indexOf(needle, at)) !== -1) {
                    var j = at + needle.length;
                    var digits = "";
                    while (j < hay.length && hay.charAt(j) >= "0" && hay.charAt(j) <= "9") {
                      digits += hay.charAt(j);
                      j++;
                    }
                    if (digits.length > 0) {
                      var v = parseInt(digits, 10);
                      if (v > max) max = v;
                    }
                    at += needle.length;
                  }
                }
                return String(max);
              } catch (e) {
                return "?";
              }
            })()
        """.trimIndent()

        web.evaluateJavascript(js) { raw ->
            val max = raw?.trim()?.trim('"')?.toIntOrNull()
            if (max != null && max >= 0) {
                store.setNext(comicId, if (max > ref.ep) NextStatus.YES else NextStatus.NO)
            }
            // 판단할 수 없으면 기존 값을 건드리지 않는다.
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        web.webViewClient = object : WebViewClient() {

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) autoSaveEpisode(url)
                refreshTitle()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipe.isRefreshing = false
                if (url != null) autoSaveEpisode(url)
                refreshTitle()
                refreshNextFromPage()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 이미지 같은 부수 요청 실패는 무시한다.
                if (request?.isForMainFrame != true) return
                swipe.isRefreshing = false
                showError()
            }
        }
    }

    /** 번호를 대신 바꾸지 않는다. 무엇을 할지는 사용자가 고른다. */
    private fun showError() {
        errorText.text =
            "${SiteUrl.buildHost(store.domain)} 를 못 불러왔습니다.\n주소가 막혔다면 번호를 올려 보세요."
        errorBar.visibility = View.VISIBLE
    }

    private fun currentUrl(): String {
        val c = comic ?: return "https://" + SiteUrl.buildHost(store.domain) + "/"
        return SiteUrl.buildUrl(store.domain, c.path)
    }

    private fun load() {
        errorBar.visibility = View.GONE
        web.loadUrl(currentUrl())
    }

    private fun bumpDomain(delta: Int) {
        val d = store.bumpDomain(delta)
        refreshTitle()
        toast("${SiteUrl.buildHost(d)} 로 이동합니다.")
        load()
    }

    /** 제목에는 저장된 회차를, 아래 줄에는 지금 보고 있는 주소를 보여준다. */
    private fun refreshTitle() {
        val c = comic
        val saved = c?.let { SiteUrl.episodeLabel(it.path) }
        val name = c?.title ?: "만화"

        val viewing = web.url?.let { SiteUrl.parseInput(it) }
        val viewingLabel = viewing?.let { SiteUrl.episodeLabel(it.path) }
        val host = viewing?.domain?.let { SiteUrl.buildHost(it) } ?: SiteUrl.buildHost(store.domain)

        val unsaved = viewingLabel != null && saved != null && viewingLabel != saved

        titleView.text = if (saved != null) "$name · $saved" else name
        titleView.setTextColor(if (unsaved) Ui.AMBER else Ui.TEXT)
        hostView.text = if (unsaved) "$host · 보는 중 $viewingLabel (저장 안 됨)" else host
        hostView.setTextColor(if (unsaved) Ui.AMBER else Ui.TEXT_FAINT)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
