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

/**
 * 만화 사이트를 앱 안에서 직접 연다.
 *
 * 저장은 전부 사용자가 누를 때만 일어난다. 주소 번호도 회차도 앱이 알아서
 * 바꾸지 않는다. 잘못 눌렀을 때 되돌리기 쉬워야 하고, 무엇이 저장됐는지
 * 항상 눈에 보여야 하기 때문이다.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COMIC_ID = "comic_id"
    }

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var titleView: TextView
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#020617"))
        }
        root.addView(buildToolbar())
        root.addView(buildErrorBar())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            visibility = View.GONE
        }
        root.addView(progress)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(web)

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

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        bar.addView(iconButton("‹") { if (web.canGoBack()) web.goBack() else finish() })

        titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            maxLines = 2
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(titleView)

        // 지금 보고 있는 주소를 이 만화의 진행 상황으로 저장한다. 저장은 이 버튼으로만 일어난다.
        bar.addView(filledButton("저장", "#0EA5E9") { saveCurrent() })
        bar.addView(outlineButton("주소+1") { bumpDomain(1) })
        bar.addView(iconButton("⋮") { showMenu() })
        return bar
    }

    private fun buildErrorBar(): View {
        errorBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#422006"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FDE68A"))
        }
        errorBar.addView(errorText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        row.addView(outlineButton("주소 +1") { bumpDomain(1) })
        row.addView(outlineButton("번호 입력") { askDomainNumber() })
        row.addView(outlineButton("다시 시도") { load() })
        row.addView(outlineButton("닫기") { errorBar.visibility = View.GONE })
        errorBar.addView(row)
        return errorBar
    }

    private fun iconButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun filledButton(label: String, color: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#020617"))
            setBackgroundColor(Color.parseColor(color))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun outlineButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun showMenu() {
        val d = store.domain
        val items = arrayOf(
            "새로고침",
            "주소 번호 내리기 (${d.num} → ${d.num - 1})",
            "주소 번호 직접 입력",
            "브라우저로 열기",
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> load()
                    1 -> bumpDomain(-1)
                    2 -> askDomainNumber()
                    3 -> openInBrowser()
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
            setPadding(dp(20), dp(12), dp(20), 0)
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

    /**
     * 지금 보고 있는 주소를 이 만화에 반영한다. 주소 번호와 회차를 한꺼번에 저장한다.
     * 다른 작품으로 보이면 실수일 수 있으므로 먼저 확인한다.
     */
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
        toast(if (messages.isEmpty()) "이미 저장된 주소입니다." else "저장: ${messages.joinToString(" · ")}")
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

            /** 사이트 안에서 이동하면 제목만 갱신한다. 저장은 하지 않는다. */
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                refreshTitle()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                refreshTitle()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 이미지 같은 부수 요청 실패는 무시한다.
                if (request?.isForMainFrame != true) return
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

    /**
     * 제목에는 저장된 회차를, 아래 줄에는 지금 보고 있는 주소를 보여준다.
     * 저장된 것과 보고 있는 것이 다르면 눈에 보이도록 표시한다.
     */
    private fun refreshTitle() {
        val c = comic
        val saved = c?.let { SiteUrl.episodeLabel(it.path) }
        val name = c?.title ?: "만화"

        val viewing = web.url?.let { SiteUrl.parseInput(it) }
        val viewingLabel = viewing?.let { SiteUrl.episodeLabel(it.path) }
        val host = viewing?.domain?.let { SiteUrl.buildHost(it) } ?: SiteUrl.buildHost(store.domain)

        val unsaved = viewingLabel != null && saved != null && viewingLabel != saved
        val line2 = if (unsaved) "$host · 보는 중 $viewingLabel (저장 안 됨)" else host

        titleView.text = if (saved != null) "$name · $saved\n$line2" else "$name\n$line2"
        titleView.setTextColor(
            if (unsaved) Color.parseColor("#FDE68A") else Color.parseColor("#E2E8F0"),
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
