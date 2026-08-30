package com.toonshortcut.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var listContainer: LinearLayout
    private lateinit var domainView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var checkButton: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private var checking = false

    /**
     * 새 회차가 있는 것부터 보여줄지 여부.
     *
     * 저장된 순서를 실제로 바꾸지 않고 보여줄 때만 다시 늘어놓는다.
     * 직접 맞춰둔 순서가 새로고침 한 번에 사라지면 안 되기 때문이다.
     * 그래서 앱을 다시 켜면 원래 순서로 돌아온다.
     */
    private var sortByNew = false

    /** 마지막 확인에서 무슨 일이 있었는지. 판정이 어긋날 때 파일로 내보낸다. */
    private var lastDiagnostics: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = buildLayout()
        setContentView(root)
        root.padForSystemBars()
        render()
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

    // ------------------------------------------------------------------ 화면 뼈대

    private fun buildLayout(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
        }
        swipe = SwipeRefreshLayout(this).apply {
            setBackgroundColor(Ui.BG)
            setColorSchemeColors(Ui.ACCENT)
            setProgressBackgroundColorSchemeColor(Ui.SURFACE)
            // 맨 위에서 아래로 당기면 확인하고, 새 회차가 있는 것부터 보여준다.
            setOnRefreshListener { checkNewEpisodes(sortAfter = true) }
        }
        val root = column().apply { setPadding(pad(20), pad(20), pad(20), pad(40)) }

        val titleRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "만화 바로가기"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT)
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(0, wrap, 1f))

        // 목록을 바꾸는 버튼. 배경도 글자도 물결 효과도 주지 않아 눈에 띄지 않는다.
        // 어떤 목록을 보고 있는지는 목록 내용으로만 알 수 있다.
        titleRow.addView(
            View(this).apply { setOnClickListener { Store.toggleList(); render() } },
            LinearLayout.LayoutParams(pad(56), pad(44)),
        )
        root.addView(titleRow)
        subtitleView = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, pad(2), 0, 0)
        }
        root.addView(subtitleView)

        root.addView(buildDomainCard(), marginTop(18))

        val actionRow = row()
        checkButton = softButton("새 회차 확인") { checkNewEpisodes() }
        actionRow.addView(checkButton, weightWithRightGap())
        actionRow.addView(accentButton("만화 추가") { showComicDialog() }, weight())
        root.addView(actionRow, marginTop(10))

        listContainer = column()
        root.addView(listContainer, marginTop(18))

        val backupRow = row()
        backupRow.addView(softButton("내보내기") { exportList() }, weightWithRightGap())
        backupRow.addView(softButton("가져오기") { showImportDialog() }, weight())
        root.addView(backupRow, marginTop(14))

        root.addView(softButton("진단 파일 저장") { shareDiagnostics() }, marginTop(8))

        root.addView(TextView(this).apply {
            text = "다음 화로 넘어가면 회차가 자동으로 저장됩니다.\n" +
                "주소가 막히면 뷰어에서 주소 +1 을 눌러주세요."
            textSize = 11.5f
            setLineSpacing(pad(3).toFloat(), 1f)
            setTextColor(Ui.TEXT_FAINT)
        }, marginTop(24))

        scroll.addView(root)
        swipe.addView(scroll)
        return swipe
    }

    private fun buildDomainCard(): View {
        val card = column().apply {
            background = Ui.rounded(Ui.SURFACE, 18, context, Ui.BORDER)
            setPadding(pad(16), pad(14), pad(16), pad(16))
        }

        card.addView(TextView(this).apply {
            text = "현재 주소"
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT_DIM)
        })

        val r = row().apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(stepper("−") { store.bumpDomain(-1); render() })

        domainView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Ui.TEXT)
            setPadding(pad(8), pad(12), pad(8), pad(12))
            Ui.tappable(this, Ui.rounded(Ui.BG, 14, context, Ui.BORDER))
            setOnClickListener { showDomainDialog() }
        }
        r.addView(domainView, LinearLayout.LayoutParams(0, wrap, 1f).apply {
            leftMargin = pad(10); rightMargin = pad(10)
        })

        r.addView(stepper("+", accent = true) { store.bumpDomain(1); render() })
        card.addView(r, marginTop(12))

        card.addView(TextView(this).apply {
            text = "주소를 눌러 직접 고칠 수 있습니다"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT_FAINT)
        }, marginTop(10))

        return card
    }

    // ------------------------------------------------------------------ 목록 그리기

    private fun render() {
        domainView.text = SiteUrl.buildHost(store.domain)
        listContainer.removeAllViews()

        val stored = store.comics
        val comics = if (sortByNew) stored.sortedBy { rankFor(it.next) } else stored
        subtitleView.text = if (comics.isEmpty()) "저장한 만화 없음" else "만화 ${comics.size}편"

        if (comics.isEmpty()) {
            listContainer.addView(emptyState())
            return
        }
        for (c in comics) listContainer.addView(buildComicCard(c), marginTop(10))
    }

    /** 새 회차가 있는 것이 위로, 이미 최신인 것이 아래로 간다. */
    private fun rankFor(status: NextStatus): Int = when (status) {
        NextStatus.YES -> 0
        NextStatus.FAILED -> 1
        NextStatus.NO_EPISODE -> 2
        NextStatus.UNKNOWN -> 3
        NextStatus.NO -> 4
    }

    private fun emptyState(): View = column().apply {
        background = Ui.rounded(Ui.SURFACE, 18, context, Ui.BORDER)
        setPadding(pad(20), pad(36), pad(20), pad(36))
        gravity = Gravity.CENTER
        addView(TextView(this@MainActivity).apply {
            text = "저장한 만화가 없습니다"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT_DIM)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = "보던 주소를 복사한 뒤\n아래 [만화 추가]를 누르세요"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setLineSpacing(pad(3).toFloat(), 1f)
            setTextColor(Ui.TEXT_FAINT)
            setPadding(0, pad(8), 0, 0)
        })
    }

    private fun buildComicCard(c: Comic): View {
        val card = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(16), pad(14), pad(14), pad(14))
            Ui.tappable(this, Ui.rounded(Ui.SURFACE, 18, context, Ui.BORDER))
            setOnClickListener { openReader(c) }
            setOnLongClickListener { showComicMenu(c); true }
        }

        val texts = column()
        texts.addView(TextView(this).apply {
            text = c.title
            textSize = 16f
            maxLines = 1
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT)
        })

        val meta = row().apply { gravity = Gravity.CENTER_VERTICAL }
        meta.addView(TextView(this).apply {
            text = SiteUrl.episodeLabel(c.path) ?: SiteUrl.decodeUri(c.path)
            textSize = 12.5f
            maxLines = 1
            setTextColor(Ui.TEXT_DIM)
        })
        statusBadge(c)?.let { meta.addView(it, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = pad(8) }) }
        texts.addView(meta, marginTop(4))

        card.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        card.addView(openButton(c), LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = pad(10) })
        return card
    }

    /**
     * 확인 결과를 배지로 보여준다.
     *
     * 실패와 "회차 번호 없는 주소"를 나눈다. 전자는 다시 시도하거나 열어보면 되고,
     * 후자는 주소를 고쳐야 해서 사용자가 할 일이 다르다.
     */
    private fun statusBadge(c: Comic): View? {
        val ep = SiteUrl.parseEpisode(c.path)?.ep
        return when (c.next) {
            NextStatus.YES -> {
                val latest = c.latestEp
                val gap = if (latest != null && ep != null) latest - ep else null
                val shown = latest ?: ep?.plus(1)
                val text = when {
                    shown == null -> "새 회차"
                    gap != null && gap > 1 -> "+$gap · ${shown}화"
                    else -> "새 회차 · ${shown}화"
                }
                badge(text, Ui.GREEN) {
                    // 사이트가 준 주소가 있으면 그걸 쓴다. 표기가 바뀌는 작품은
                    // 번호만 갈아끼워서는 주소를 만들 수 없다.
                    val exact = c.latestPath
                    when {
                        !exact.isNullOrBlank() -> openReaderAt(c, exact)
                        ep != null -> openReader(c, ep + 1)
                        else -> openReader(c)
                    }
                }
            }

            NextStatus.NO -> badge("최신", Ui.TEXT_FAINT, null)

            NextStatus.FAILED -> badge("확인 실패", Ui.RED) { showFailureDialog(c) }

            NextStatus.NO_EPISODE -> badge("회차 없음", Ui.AMBER) { showNoEpisodeDialog(c) }

            NextStatus.UNKNOWN -> null
        }
    }

    /** 왜 실패했는지 그대로 보여준다. 원인을 짐작하게 만들지 않는다. */
    private fun showFailureDialog(c: Comic) {
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(
                (c.nextNote ?: "원인을 알 수 없습니다") +
                    "\n\n직접 열어보면 그 페이지를 보고 결과가 갱신됩니다.",
            )
            .setPositiveButton("열어보기") { _, _ -> openReader(c) }
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
            .setNeutralButton("열어보기") { _, _ -> openReader(c) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun badge(text: String, color: Int, onClick: (() -> Unit)?): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(pad(9), pad(4), pad(9), pad(4))
            val bg = Ui.pill(Ui.alpha(color, 38), context)
            if (onClick != null) {
                Ui.tappable(this, bg)
                setOnClickListener { onClick() }
            } else {
                background = bg
            }
        }

    private fun openButton(c: Comic): TextView = TextView(this).apply {
        text = "열기"
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Ui.ON_ACCENT)
        setPadding(pad(20), pad(11), pad(20), pad(11))
        Ui.tappable(this, Ui.rounded(Ui.ACCENT, 14, context))
        setOnClickListener { openReader(c) }
    }

    // ------------------------------------------------------------------ 동작

    private fun openReader(c: Comic, episode: Int? = null) {
        val intent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_COMIC_ID, c.id)
        if (episode != null) intent.putExtra(ReaderActivity.EXTRA_EPISODE, episode)
        startActivity(intent)
    }

    /** 사이트가 알려준 주소로 그대로 연다. */
    private fun openReaderAt(c: Comic, path: String) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_COMIC_ID, c.id)
                .putExtra(ReaderActivity.EXTRA_PATH, path),
        )
    }

    /** 저장된 만화들에 다음 회차가 나왔는지 한 번에 확인한다. 결과는 끝나는 대로 하나씩 반영된다. */
    private fun checkNewEpisodes(sortAfter: Boolean = false) {
        if (checking) {
            swipe.isRefreshing = false
            return
        }
        val comics = store.comics
        if (comics.isEmpty()) {
            swipe.isRefreshing = false
            toast("저장한 만화가 없습니다.")
            return
        }
        checking = true
        var done = 0
        val logs = StringBuilder()
        checkButton.isEnabled = false
        checkButton.text = "확인 중… 0/${comics.size}"

        EpisodeCheck.checkAll(
            store.domain,
            comics,
            onEach = { r ->
                done++
                store.setNext(r.comicId, r.status, r.note, r.latestEp, r.latestPath)
                logs.append(r.log).append('\n')
                checkButton.text = "확인 중… $done/${comics.size}"
                render()
            },
            onDone = {
                checking = false
                checkButton.isEnabled = true
                checkButton.text = "새 회차 확인"
                lastDiagnostics = buildDiagnostics(logs.toString())
                swipe.isRefreshing = false
                if (sortAfter) sortByNew = true
                render()
                val list = store.comics
                val failed = list.count { it.next == NextStatus.FAILED }
                val noEp = list.count { it.next == NextStatus.NO_EPISODE }
                when {
                    failed > 0 && noEp > 0 -> toast("실패 ${failed}편 · 회차 없는 주소 ${noEp}편. 배지를 눌러 확인하세요.")
                    failed > 0 -> toast("${failed}편 실패. 배지를 누르면 이유가 나옵니다.")
                    noEp > 0 -> toast("${noEp}편은 주소에 회차 번호가 없습니다.")
                }
            },
        )
    }

    // ------------------------------------------------------------------ 대화상자

    private fun showComicMenu(c: Comic) {
        val items = arrayOf("수정", "위로", "아래로", "삭제")
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showComicDialog(existing = c)
                    1 -> { sortByNew = false; store.move(c.id, -1); render() }
                    2 -> { sortByNew = false; store.move(c.id, 1); render() }
                    3 -> confirmDelete(c)
                }
            }
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
     * 확인 과정을 파일로 내보낸다.
     * 판정이 어긋났을 때 짐작으로 고치면 또 빗나가므로 실제 응답을 봐야 한다.
     */
    private fun shareDiagnostics() {
        val text = lastDiagnostics
        if (text.isNullOrBlank()) {
            toast("먼저 [새 회차 확인]을 한 번 눌러주세요.")
            return
        }
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
                        putExtra(Intent.EXTRA_SUBJECT, "만화 바로가기 진단")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "진단 파일 보내기",
                ),
            )
        } catch (e: Exception) {
            toast("파일을 만들지 못했습니다: ${e.message}")
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

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(match, wrap)
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(match, wrap).apply { topMargin = pad(v) }
    private fun weight() = LinearLayout.LayoutParams(0, wrap, 1f)
    private fun weightWithRightGap() = LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = pad(8) }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.06f
        setTextColor(Ui.TEXT_DIM)
        setPadding(0, pad(12), 0, pad(2))
    }

    private fun accentButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Ui.ON_ACCENT)
        setPadding(pad(16), pad(15), pad(16), pad(15))
        Ui.tappable(this, Ui.rounded(Ui.ACCENT, 16, context))
        setOnClickListener { onClick() }
    }

    private fun softButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Ui.TEXT)
        setPadding(pad(14), pad(13), pad(14), pad(13))
        Ui.tappable(this, Ui.rounded(Ui.SURFACE_HI, 14, context, Ui.BORDER))
        setOnClickListener { onClick() }
    }

    private fun stepper(text: String, accent: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (accent) Ui.ON_ACCENT else Ui.TEXT)
        Ui.tappable(this, Ui.rounded(if (accent) Ui.ACCENT else Ui.SURFACE_HI, 14, context, if (accent) null else Ui.BORDER))
        layoutParams = LinearLayout.LayoutParams(pad(54), pad(50))
        setOnClickListener { onClick() }
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
