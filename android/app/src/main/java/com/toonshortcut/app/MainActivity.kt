package com.toonshortcut.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), MainHost {

    private lateinit var store: Store

    /** 바깥 틀. 화면 모양을 바꾸면 안쪽 [screen] 만 갈아 끼운다. */
    private lateinit var frame: FrameLayout
    private lateinit var screen: MainScreen

    /**
     * 새 회차 확인에 쓰는 보이지 않는 WebView.
     * 앱에서 직접 HTTP 로 요청하면 통신사가 연결을 끊어서, 확인도 브라우저
     * 안에서 해야 한다.
     */
    private lateinit var hiddenWeb: WebView
    private var checking = false

    /** 확인 중 진행 글자. 도중에 화면 모양을 바꿔도 이어서 보여준다. */
    private var progressText: String? = null

    /** 마지막 확인에서 무슨 일이 있었는지. 판정이 어긋날 때 파일로 내보낸다. */
    private var lastDiagnostics: String? = null

    /**
     * 파일 저장 창.
     * 공유(보내기)에는 "파일로 저장"이 없어서 이걸 따로 둬야 한다.
     */
    private val saveDiagnosticsFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val text = lastDiagnostics
            if (uri == null || text.isNullOrBlank()) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                toast("저장했습니다.")
            } catch (e: Exception) {
                toast("저장하지 못했습니다: ${e.message}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        frame = FrameLayout(this)
        // 확인용 WebView 를 화면에 두되 눈에 띄지 않게 한다.
        // 완전히 떼어두면 자바스크립트가 제대로 돌지 않는 기기가 있다.
        hiddenWeb = WebView(this)
        frame.addView(hiddenWeb, FrameLayout.LayoutParams(1, 1))
        setContentView(frame)
        frame.padForSystemBars()

        applyTheme()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // 뷰어에서 회차나 확인 결과가 바뀌었을 수 있으니 돌아올 때마다 다시 그린다.
        render()
    }

    /** 크롬에서 "공유 → 만화 바로가기" 로 들어온 주소를 추가 창에 채워준다. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return
        intent.removeExtra(Intent.EXTRA_TEXT)
        if (text.isNotEmpty()) showComicDialog(prefillUrl = text)
    }

    // ------------------------------------------------------------------ 화면 모양

    /** 저장된 화면 모양으로 안쪽을 새로 만든다. 상태바 색도 같이 맞춘다. */
    @Suppress("DEPRECATION")
    private fun applyTheme() {
        if (::screen.isInitialized) frame.removeView(screen.view)
        screen = MainScreen.create(store.mainTheme, this, this, store)
        // WebView 보다 아래(먼저)에 둔다. WebView 는 1×1 이라 가려도 티가 안 난다.
        frame.addView(
            screen.view,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val bg = screen.bg
        frame.setBackgroundColor(bg)
        window.setBackgroundDrawable(ColorDrawable(bg))
        // 안드로이드 14 이하는 상태바를 직접 칠해야 한다. 15 부터는 뒤의 frame 색이 비친다.
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, frame).apply {
            isAppearanceLightStatusBars = screen.lightBars
            isAppearanceLightNavigationBars = screen.lightBars
        }

        screen.showProgress(if (checking) progressText else null)
        screen.render()
    }

    private fun render() {
        if (::screen.isInitialized) screen.render()
    }

    private fun showThemeDialog() {
        val current = store.mainTheme.coerceIn(0, MainScreen.THEMES.size - 1)
        AlertDialog.Builder(this)
            .setTitle("화면 모양")
            .setSingleChoiceItems(MainScreen.THEMES, current) { dialog, which ->
                dialog.dismiss()
                if (which != store.mainTheme) {
                    store.mainTheme = which
                    applyTheme()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------------ MainHost

    override fun openComic(c: Comic) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_COMIC_ID, c.id),
        )
    }

    /** 사이트의 다음 화 버튼이 가리키던 주소로 바로 연다. 없으면 저장한 회차. */
    override fun openNew(c: Comic) {
        val exact = c.latestPath
        if (exact.isNullOrBlank()) {
            openComic(c)
            return
        }
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_COMIC_ID, c.id)
                .putExtra(ReaderActivity.EXTRA_PATH, exact),
        )
    }

    override fun showStatus(c: Comic) {
        when (c.next) {
            NextStatus.NO_EPISODE -> showNoEpisodeDialog(c)
            NextStatus.FAILED -> showFailureDialog(c)
            NextStatus.YES -> openNew(c)
            else -> openComic(c)
        }
    }

    override fun recheck(c: Comic) = checkNewEpisodes(listOf(c))

    override fun bumpDomain(delta: Int) {
        store.bumpDomain(delta)
        render()
    }

    override fun editDomain() = showDomainDialog()

    override fun checkAll() = checkNewEpisodes(null)

    override fun addComic() = showComicDialog()

    override fun toggleList() {
        Store.toggleList()
        render()
    }

    override fun showMore() {
        val items = arrayOf("화면 모양 바꾸기", "목록 내보내기", "목록 가져오기", "진단 기록 꺼내기", "도움말")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemeDialog()
                    1 -> exportList()
                    2 -> showImportDialog()
                    3 -> shareDiagnostics()
                    4 -> AlertDialog.Builder(this)
                        .setMessage(
                            "• 만화를 누르면 저장한 회차가 열립니다.\n" +
                                "• 길게 누르면 수정·삭제 메뉴가 나옵니다.\n" +
                                "• 다음 화로 넘어가면 회차가 자동으로 저장됩니다.\n" +
                                "• 주소가 막히면 + 로 주소 번호를 올려주세요.\n" +
                                "• 맨 위에서 아래로 당겨도 새 회차를 확인합니다.",
                        )
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ 상태 안내

    /** 왜 실패했는지 그대로 보여준다. 원인을 짐작하게 만들지 않는다. */
    private fun showFailureDialog(c: Comic) {
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(
                (c.nextNote ?: "원인을 알 수 없습니다") +
                    "\n\n직접 열어보면 그 페이지를 보고 결과가 갱신됩니다.",
            )
            .setPositiveButton("열어보기") { _, _ -> openComic(c) }
            .setNeutralButton("다시 확인") { _, _ -> recheck(c) }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 회차 번호가 없는 주소는 다시 확인해도 소용없다. 주소를 고쳐야 한다. */
    private fun showNoEpisodeDialog(c: Comic) {
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(
                "저장된 주소에 회차 번호가 없습니다.\n작품 목록 페이지를 저장하신 것 같습니다.\n\n" +
                    SiteUrl.decodeUri(c.path) +
                    "\n\n회차 페이지 주소로 바꾸면 확인할 수 있습니다.",
            )
            .setPositiveButton("주소 수정") { _, _ -> showComicDialog(existing = c) }
            .setNeutralButton("열어보기") { _, _ -> openComic(c) }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------------ 새 회차 확인

    private fun setProgress(text: String?) {
        progressText = text
        screen.showProgress(text)
    }

    /**
     * 저장된 만화들에 다음 회차가 나왔는지 확인한다. [only] 를 주면 그것만.
     * 브라우저 안에서 돌린다. 앱에서 직접 요청하면 통신사가 연결을 끊는다.
     */
    private fun checkNewEpisodes(only: List<Comic>?) {
        if (checking) {
            screen.swipe.isRefreshing = false
            toast("이미 확인하는 중입니다.")
            return
        }
        val comics = only ?: store.comics
        if (comics.isEmpty()) {
            screen.swipe.isRefreshing = false
            toast("저장한 만화가 없습니다.")
            return
        }
        checking = true
        setProgress("확인 중… 0/${comics.size}")

        WebEpisodeCheck(hiddenWeb, store.domain).start(
            comics,
            onProgress = { done, total -> setProgress("확인 중… $done/$total") },
            onDone = { outcomes, log ->
                applyOutcomes(comics, outcomes)
                checking = false
                setProgress(null)
                screen.swipe.isRefreshing = false
                lastDiagnostics = buildDiagnostics(log)
                render()

                if (only != null) {
                    val ids = only.map { it.id }.toSet()
                    val after = store.comics.filter { it.id in ids }
                    val one = after.singleOrNull()
                    when {
                        one == null -> Unit
                        one.next == NextStatus.YES -> toast("새 회차가 있습니다.")
                        one.next == NextStatus.NO -> toast("최신까지 봤습니다.")
                        else -> toast("이번에도 실패: ${one.nextNote ?: "원인 모름"}")
                    }
                } else {
                    reportAll()
                }
            },
        )
    }

    /** 전체 확인이 끝나면 결과를 한 줄로 알려준다. */
    private fun reportAll() {
        val list = store.comics
        val failed = list.count { it.next == NextStatus.FAILED }
        val noEp = list.count { it.next == NextStatus.NO_EPISODE }
        val fresh = list.count { it.next == NextStatus.YES }
        when {
            failed > 0 && noEp > 0 -> toast("실패 ${failed}편 · 회차 없는 주소 ${noEp}편")
            failed > 0 -> toast("${failed}편 실패. 눌러보면 이유가 나옵니다.")
            noEp > 0 -> toast("${noEp}편은 주소에 회차 번호가 없습니다.")
            fresh > 0 -> toast("새 회차 ${fresh}편")
            else -> toast("모두 최신입니다.")
        }
    }

    /**
     * 확인 결과를 반영한다. 저장된 회차 페이지의 다음 화 버튼이 살아 있으면
     * 새 회차, 꺼져 있으면 최신. 버튼을 못 봤으면 실패로 둔다.
     */
    private fun applyOutcomes(comics: List<Comic>, outcomes: List<WebEpisodeCheck.Outcome>) {
        val byId = outcomes.associateBy { it.comicId }
        for (c in comics) {
            val o = byId[c.id]
            when {
                o == null ->
                    store.setNext(c.id, NextStatus.FAILED, "확인하지 못했습니다")
                o.error != null ->
                    store.setNext(c.id, NextStatus.FAILED, o.error)
                o.hasNext == true ->
                    store.setNext(c.id, NextStatus.YES, null, null, o.nextPath)
                o.hasNext == false ->
                    store.setNext(c.id, NextStatus.NO)
                else ->
                    store.setNext(c.id, NextStatus.FAILED, "다음 화 버튼을 찾지 못했습니다")
            }
        }
    }

    // ------------------------------------------------------------------ 대화상자

    override fun comicMenu(c: Comic) {
        val items = arrayOf("수정", "제목 새로 뽑기", "위로", "아래로", "삭제")
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showComicDialog(existing = c)
                    1 -> retitle(c)
                    2 -> { store.move(c.id, -1); render() }
                    3 -> { store.move(c.id, 1); render() }
                    4 -> confirmDelete(c)
                }
            }
            .show()
    }

    /** 지금 주소를 기준으로 제목을 다시 뽑는다. 예전에 잘못 저장된 제목을 되살릴 때 쓴다. */
    private fun retitle(c: Comic) {
        val fresh = SiteUrl.guessTitle(c.path)
        if (fresh == c.title) {
            toast("이미 주소와 같은 제목입니다.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("제목 새로 뽑기")
            .setMessage("${c.title}\n  ↓\n$fresh")
            .setPositiveButton("바꾸기") { _, _ ->
                store.updateComic(c.id, title = fresh)
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(c: Comic) {
        AlertDialog.Builder(this)
            .setMessage("\"${c.title}\" 을(를) 목록에서 지울까요?")
            .setPositiveButton("삭제") { _, _ -> store.deleteComic(c.id); render() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 추가와 수정을 같은 창으로 처리한다. */
    private fun showComicDialog(existing: Comic? = null, prefillUrl: String? = null) {
        val box = column().apply { setPadding(pad(24), pad(12), pad(24), 0) }

        val urlInput = EditText(this).apply {
            hint = "https://tkor146.com/..."
            setSingleLine(false)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefillUrl ?: existing?.let { SiteUrl.decodeUri(it.path) } ?: clipboardUrl() ?: "")
        }
        val titleInput = EditText(this).apply {
            hint = "비우면 주소에서 자동"
            setSingleLine(true)
            setText(existing?.title ?: "")
        }
        val guessed = existing?.path?.let { SiteUrl.parseEpisode(it) }?.let { SiteUrl.guessListPath(it) }
        val listInput = EditText(this).apply {
            hint = guessed?.let { "비우면 자동: " + SiteUrl.decodeUri(it) } ?: "예: /몽둥이기사-단"
            setSingleLine(true)
            setText(existing?.listPath?.let { SiteUrl.decodeUri(it) } ?: "")
        }

        box.addView(label("주소"))
        box.addView(urlInput)
        box.addView(label("제목"))
        box.addView(titleInput)
        box.addView(label("목록 주소 (자동 추측이 틀릴 때만)"))
        box.addView(listInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "만화 추가" else "수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                saveComic(
                    existing,
                    urlInput.text.toString(),
                    titleInput.text.toString(),
                    listInput.text.toString(),
                )
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveComic(existing: Comic?, rawUrl: String, rawTitle: String, rawList: String) {
        val parsed = SiteUrl.parseInput(rawUrl.trim())
        if (parsed == null || parsed.path.isBlank()) {
            toast("주소를 알아볼 수 없습니다.")
            return
        }
        val title = rawTitle.trim().ifEmpty { SiteUrl.guessTitle(parsed.path) }

        // 목록 주소는 전체 주소로 넣어도 되고 경로만 넣어도 되게 한다.
        val listPath = rawList.trim().let { raw ->
            if (raw.isEmpty()) "" else SiteUrl.parseInput(raw)?.path ?: ""
        }

        if (existing == null) {
            val added = store.addComic(title, parsed.path)
            if (listPath.isNotEmpty()) store.updateComic(added.id, listPath = listPath)
        } else {
            store.updateComic(existing.id, title = title, path = parsed.path, listPath = listPath)
        }

        // 붙여넣은 주소의 도메인이 다르면 갱신할지 물어본다.
        val d = parsed.domain
        val cur = store.domain
        if (d != null && SiteUrl.sameShape(d, cur) && d.num != cur.num) {
            AlertDialog.Builder(this)
                .setMessage("붙여넣은 주소는 ${SiteUrl.buildHost(d)} 입니다.\n현재 주소를 이걸로 바꿀까요?")
                .setPositiveButton("바꾸기") { _, _ -> store.setDomainNum(d.num); render() }
                .setNegativeButton("그대로", null)
                .show()
        } else if (d != null && !SiteUrl.sameShape(d, cur)) {
            AlertDialog.Builder(this)
                .setMessage("붙여넣은 주소의 도메인이 다릅니다.\n${SiteUrl.buildHost(d)} 로 바꿀까요?")
                .setPositiveButton("바꾸기") { _, _ -> store.domain = d; render() }
                .setNegativeButton("그대로", null)
                .show()
        }
        render()
    }

    private fun showDomainDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(SiteUrl.buildHost(store.domain))
        }
        val box = column().apply {
            setPadding(pad(24), pad(12), pad(24), 0)
            addView(label("주소 (번호 포함)"))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("현재 주소")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val parsed = SiteUrl.parseHost(input.text.toString().trim())
                if (parsed == null) toast("숫자가 들어간 주소여야 합니다. 예: tkor146.com")
                else { store.domain = parsed; render() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ------------------------------------------------------------------ 백업

    private fun exportList() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, store.exportJson())
                },
                "목록 보내기",
            ),
        )
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "내보낸 JSON 붙여넣기"
            setSingleLine(false)
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(clipboardText()?.takeIf { it.trimStart().startsWith("{") } ?: "")
        }
        val box = column().apply {
            setPadding(pad(24), pad(12), pad(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("목록 가져오기")
            .setView(box)
            .setPositiveButton("가져오기") { _, _ ->
                try {
                    val n = store.importJson(input.text.toString())
                    toast("${n}개 추가했습니다.")
                    render()
                } catch (e: Exception) {
                    toast("형식이 올바르지 않습니다.")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ------------------------------------------------------------------ 진단

    private fun buildDiagnostics(body: String): String {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        return buildString {
            appendLine("만화 바로가기 진단 기록")
            appendLine("앱 버전: $version")
            appendLine("시각: $now")
            appendLine("안드로이드: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("현재 주소: ${SiteUrl.buildHost(store.domain)}")
            appendLine("목록: ${Store.activeList}")
            appendLine("만화 수: ${store.comics.size}")
            appendLine("=".repeat(50))
            appendLine()
            append(body)
        }
    }

    /**
     * 확인 과정을 꺼내는 방법을 고르게 한다.
     *
     * 보내기(ACTION_SEND)만 두면 안드로이드가 앱 목록만 보여주고 "파일로 저장"이
     * 나오지 않는다. 저장은 문서 만들기 창을 따로 열어야 한다.
     * 내용을 그대로 붙여넣는 게 목적이면 복사가 가장 빠르다.
     */
    private fun shareDiagnostics() {
        val text = lastDiagnostics
        if (text.isNullOrBlank()) {
            toast("먼저 [새 회차 확인]을 한 번 눌러주세요.")
            return
        }
        val items = arrayOf(
            "클립보드에 복사 (${text.length}자)",
            "파일로 저장",
            "다른 앱으로 보내기",
        )
        AlertDialog.Builder(this)
            .setTitle("진단 기록")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copyDiagnostics(text)
                    1 -> saveDiagnosticsFile.launch("manhwa-diagnostics.txt")
                    2 -> sendDiagnostics(text)
                }
            }
            .show()
    }

    private fun copyDiagnostics(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast("클립보드를 쓸 수 없습니다.")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("만화 바로가기 진단", text))
        toast("복사했습니다. 붙여넣기 하세요.")
    }

    private fun sendDiagnostics(text: String) {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "manhwa-diagnostics.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        // 파일을 못 여는 앱을 위해 본문에도 같이 담는다.
                        putExtra(Intent.EXTRA_TEXT, text.take(100_000))
                        putExtra(Intent.EXTRA_SUBJECT, "만화 바로가기 진단")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "진단 기록 보내기",
                ),
            )
        } catch (e: Exception) {
            toast("보내지 못했습니다: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ 뷰 도우미

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun pad(v: Int) = Ui.dp(this, v)

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(match, wrap)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.06f
        setTextColor(Ui.TEXT_DIM)
        setPadding(0, pad(12), 0, pad(2))
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun clipboardUrl(): String? =
        clipboardText()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
