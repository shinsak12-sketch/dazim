package com.toonshortcut.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * 웹 iframe 과 달리 WebView 는 최상위 탐색이라 사이트의 삽입 차단이 적용되지 않고,
 * 사용자가 어디로 이동했는지도 그대로 읽힌다. 이 두 가지 덕분에
 *  - 회차가 바뀌면 자동으로 저장되고
 *  - 도메인이 막히면 오류를 감지해 번호를 올려 다시 시도할 수 있다.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COMIC_ID = "comic_id"
        /** 오류가 이어질 때 도메인 번호를 최대 몇 번까지 자동으로 올릴지 */
        private const val MAX_AUTO_BUMPS = 4
    }

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var progress: ProgressBar

    private var comicId: String = ""
    private var autoBumps = 0
    /** 자동으로 올리기 직전의 번호. 되돌리기에 쓴다. */
    private var numBeforeAutoBump: Int? = null

    private val comic: Comic?
        get() = store.comics.firstOrNull { it.id == comicId }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        comicId = intent.getStringExtra(EXTRA_COMIC_ID) ?: ""

        val c = comic
        if (c == null) {
            Toast.makeText(this, "만화를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#020617"))
        }
        root.addView(buildToolbar())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            visibility = View.GONE
        }
        root.addView(progress)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
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
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        bar.addView(iconButton("‹") { if (web.canGoBack()) web.goBack() else finish() })

        titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(titleView)

        bar.addView(iconButton("↻") { load() })
        bar.addView(textButton("주소+1") { bumpDomain(1, auto = false) })
        bar.addView(iconButton("⋮") { showMenu() })
        return bar
    }

    private fun iconButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0EA5E9"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun showMenu() {
        val d = store.domain
        val items = arrayOf(
            "주소 번호 내리기 (${d.num} → ${d.num - 1})",
            "주소 번호 직접 입력",
            "브라우저로 열기",
            "이 화를 목록에 저장",
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> bumpDomain(-1, auto = false)
                    1 -> askDomainNumber()
                    2 -> openInBrowser()
                    3 -> saveCurrentAsProgress()
                }
            }
            .show()
    }

    private fun askDomainNumber() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(store.domain.num.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("주소 번호")
            .setView(input)
            .setPositiveButton("적용") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                store.setDomainNum(n)
                autoBumps = 0
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

    /** 지금 보고 있는 주소를 이 만화의 진행 상황으로 강제 저장한다. */
    private fun saveCurrentAsProgress() {
        val url = web.url ?: return
        val parsed = SiteUrl.parseInput(url) ?: return
        store.updateComic(comicId, path = parsed.path)
        parsed.domain?.let { if (SiteUrl.sameShape(it, store.domain)) store.setDomainNum(it.num) }
        refreshTitle()
        toast("저장했습니다.")
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

            /**
             * 사용자가 사이트 안에서 이동할 때마다 불린다.
             * 여기서 회차와 도메인 번호를 따라잡는다.
             */
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) trackUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 정상적으로 떴으면 자동 증가 카운터를 초기화한다.
                autoBumps = 0
                if (url != null) trackUrl(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 이미지 같은 부수 요청 실패는 무시한다.
                if (request?.isForMainFrame != true) return
                handleLoadFailure(error?.errorCode)
            }
        }
    }

    private fun currentUrl(): String {
        val c = comic ?: return "https://" + SiteUrl.buildHost(store.domain) + "/"
        return SiteUrl.buildUrl(store.domain, c.path)
    }

    private fun load() {
        web.loadUrl(currentUrl())
    }

    /**
     * 주소가 바뀌면 진행 상황을 따라잡는다.
     *
     * 다른 작품으로 넘어갔을 때 이 만화의 북마크를 덮어쓰면 안 되므로,
     * 회차 숫자 앞뒤 문자열이 똑같을 때만 같은 작품으로 본다.
     */
    private fun trackUrl(url: String) {
        val parsed = SiteUrl.parseInput(url) ?: return

        // 1) 사이트가 새 미러로 넘겨줬다면 번호를 따라간다.
        parsed.domain?.let { d ->
            val cur = store.domain
            if (SiteUrl.sameShape(d, cur) && d.num != cur.num) {
                store.setDomainNum(d.num)
                autoBumps = 0
                numBeforeAutoBump = null
                toast("주소가 ${SiteUrl.buildHost(store.domain)} 로 바뀌었습니다.")
            }
        }

        // 2) 같은 작품의 다른 회차면 저장한다.
        val c = comic ?: return
        val old = SiteUrl.parseEpisode(c.path) ?: return
        val new = SiteUrl.parseEpisode(parsed.path) ?: return
        if (old.before != new.before || old.after != new.after) return // 다른 작품
        if (old.ep == new.ep) return

        store.updateComic(comicId, path = parsed.path)
        refreshTitle()
        toast("${new.ep}화로 저장했습니다.")
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 페이지를 못 띄웠을 때. 도메인이 막힌 경우가 대부분이라 번호를 올려 다시 시도한다.
     * 다만 아예 인터넷이 끊긴 상황에서 번호만 계속 올리면 안 되므로 먼저 연결을 확인한다.
     */
    private fun handleLoadFailure(errorCode: Int?) {
        if (!hasNetwork()) {
            toast("인터넷 연결을 확인해 주세요.")
            return
        }
        if (autoBumps >= MAX_AUTO_BUMPS) {
            AlertDialog.Builder(this)
                .setTitle("계속 안 열립니다")
                .setMessage("번호를 ${MAX_AUTO_BUMPS}번 올려봤지만 열리지 않았습니다.\n번호를 직접 입력하거나 잠시 뒤 다시 시도해 주세요.")
                .setPositiveButton("번호 직접 입력") { _, _ -> askDomainNumber() }
                .setNegativeButton("닫기", null)
                .show()
            return
        }
        if (numBeforeAutoBump == null) numBeforeAutoBump = store.domain.num
        autoBumps++
        bumpDomain(1, auto = true)
    }

    private fun bumpDomain(delta: Int, auto: Boolean) {
        val before = store.domain.num
        val d = store.bumpDomain(delta)
        refreshTitle()
        load()
        if (auto) {
            toast("$before 이 안 열려 ${d.num} 로 넘겼습니다. (${autoBumps}/$MAX_AUTO_BUMPS)")
        } else {
            autoBumps = 0
            numBeforeAutoBump = null
        }
    }

    private fun refreshTitle() {
        val c = comic
        val label = c?.let { SiteUrl.episodeLabel(it.path) }
        val name = c?.title ?: "만화"
        titleView.text = if (label != null) {
            "$name · $label\n${SiteUrl.buildHost(store.domain)}"
        } else {
            "$name\n${SiteUrl.buildHost(store.domain)}"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
