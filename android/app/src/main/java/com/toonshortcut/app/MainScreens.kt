package com.toonshortcut.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.Collator
import java.util.Locale

/** 화면 모양과 상관없이 메인 화면이 하는 일. MainActivity 가 맡는다. */
interface MainHost {
    /** 저장한 회차를 연다. */
    fun openComic(c: Comic)

    /** 새 회차가 있는 만화를 연다. 보던 회차 그대로 연다. */
    fun openNew(c: Comic)

    /** 길게 눌렀을 때 메뉴(수정, 삭제 등). */
    fun comicMenu(c: Comic)

    /** 확인 실패 / 회차 없는 주소 안내. */
    fun showStatus(c: Comic)

    /** 이 만화 하나만 다시 확인한다. */
    fun recheck(c: Comic)
    fun bumpDomain(delta: Int)
    fun editDomain()
    fun checkAll()
    fun addComic()

    /** 더보기: 화면 모양, 내보내기, 가져오기, 진단 기록. */
    fun showMore()

    /** 숨긴 목록 전환. 화면마다 제목 줄에 보이지 않게 둔다. */
    fun toggleList()
}

/**
 * 메인 화면 한 가지 모양.
 *
 * 화면 모양은 여러 개지만 하는 일은 같다. 동작은 전부 [MainHost] 로 넘기고
 * 여기서는 그리기만 한다.
 */
abstract class MainScreen(
    protected val ctx: Context,
    protected val host: MainHost,
    protected val store: Store,
) {
    abstract val view: View
    abstract val swipe: SwipeRefreshLayout

    /** 배경색. 상태바·내비게이션바도 이 색으로 맞춘다. */
    abstract val bg: Int

    /** 밝은 바탕이면 상태바 아이콘을 어둡게 해야 보인다. */
    abstract val lightBars: Boolean

    abstract fun render()

    /** 확인 중이면 진행 글자, 끝나면 null. */
    abstract fun showProgress(text: String?)

    companion object {
        val THEMES = arrayOf(
            "타임라인 (흰 바탕 · 보라)",
            "검색 · 초성 (어두운 바탕 · 호박색)",
            "알림 피드 (하늘색)",
        )

        fun create(theme: Int, ctx: Context, host: MainHost, store: Store): MainScreen = when (theme) {
            1 -> IndexScreen(ctx, host, store)
            2 -> FeedScreen(ctx, host, store)
            else -> TimelineScreen(ctx, host, store)
        }

        const val RIPPLE_ON_LIGHT = 0x1F000000
        const val RIPPLE_ON_DARK = 0x33FFFFFF
    }

    // ------------------------------------------------------------ 공통 도우미

    protected val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    protected val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    protected fun dp(v: Int): Int = Ui.dp(ctx, v)

    protected fun vcol() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    protected fun hrow() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    protected fun box(wDp: Int, hDp: Int) = LinearLayout.LayoutParams(dp(wDp), dp(hDp))
    protected fun fill(topDp: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(topDp) }
    protected fun grow(leftDp: Int = 0) = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(leftDp) }
    protected fun wrapLeft(leftDp: Int) = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(leftDp) }
    protected fun rowParams(hDp: Int, topDp: Int) = LinearLayout.LayoutParams(MATCH, dp(hDp)).apply { topMargin = dp(topDp) }

    protected fun text(s: CharSequence, sp: Float, color: Int, weight: Int = 400): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = Ui.weight(weight)
        }

    protected fun oneLine(tv: TextView): TextView = tv.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    protected fun icon(res: Int, color: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 가운데 아이콘 하나짜리 단추. */
    protected fun iconButton(
        res: Int,
        color: Int,
        iconDp: Int,
        background: Drawable?,
        ripple: Int,
        desc: String,
        onClick: () -> Unit,
    ): FrameLayout = FrameLayout(ctx).apply {
        contentDescription = desc
        addView(icon(res, color), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        Ui.tappable(this, background, ripple)
        setOnClickListener { onClick() }
    }

    protected fun circle(color: Int, stroke: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (stroke != null) setStroke(dp(strokeDp), stroke)
        }

    /** 동그라미 안에 제목 첫 글자. 표지 대신 쓴다. */
    protected fun avatar(title: String, bgColor: Int, fg: Int, sp: Float, stroke: Int? = null, strokeDp: Int = 0): TextView =
        TextView(ctx).apply {
            text = firstLetter(title)
            gravity = Gravity.CENTER
            textSize = sp
            setTextColor(fg)
            typeface = Ui.weight(700)
            includeFontPadding = false
            background = circle(bgColor, stroke, strokeDp)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun firstLetter(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return "?"
        return String(Character.toChars(t.codePointAt(0))).uppercase()
    }

    /** 앞부분만 굵게. "신마대제 다음 화가 올라왔어요" */
    protected fun boldLead(bold: String, rest: String): CharSequence =
        SpannableStringBuilder().apply {
            append(bold, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(rest)
        }

    protected fun ep(c: Comic): String? = SiteUrl.episodeLabel(c.path)

    /** "70화까지 봄" 처럼. 회차 번호가 없는 주소면 그냥 "저장한 회차". */
    protected fun seen(c: Comic, suffix: String): String = ep(c)?.let { it + suffix } ?: "저장한 회차"

    /**
     * 새 회차 카드 아랫줄. "189화까지 봄 · 다음 190화"
     * 사이트 다음 화 버튼이 가리킨 회차를 같이 보여줘서, 이미 본 회차라면
     * 저장이 뒤처진 것을 바로 알 수 있게 한다.
     */
    protected fun freshSub(c: Comic, suffix: String): String {
        val nxt = c.latestPath?.let { SiteUrl.episodeLabel(it) }
        return seen(c, suffix) + (nxt?.let { " · 다음 $it" } ?: "")
    }

    protected fun isTrouble(c: Comic) = c.next == NextStatus.FAILED || c.next == NextStatus.NO_EPISODE

    /** 누르면 열고, 길게 누르면 메뉴. 모든 화면에서 같다. */
    protected fun View.bindComic(c: Comic, background: Drawable?, ripple: Int) {
        Ui.tappable(this, background, ripple)
        setOnClickListener { host.openComic(c) }
        setOnLongClickListener { host.comicMenu(c); true }
    }

    /**
     * 숨긴 목록으로 바꾸는 단추. 배경도 글자도 물결도 없어서 보이지 않는다.
     * 어떤 목록을 보고 있는지는 목록 내용으로만 알 수 있다.
     */
    protected fun hiddenToggle(): View = View(ctx).apply {
        setOnClickListener { host.toggleList() }
    }

    protected fun scrollOf(content: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    /** 맨 위에서 아래로 당기면 새 회차를 확인한다. */
    protected fun swipeOf(scroll: View, accent: Int, surface: Int): SwipeRefreshLayout =
        SwipeRefreshLayout(ctx).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(surface)
            setOnRefreshListener { host.checkAll() }
            addView(scroll, ViewGroup.LayoutParams(MATCH, MATCH))
        }

    protected fun emptyCard(bgColor: Int, stroke: Int?, titleColor: Int, subColor: Int): View = vcol().apply {
        gravity = Gravity.CENTER
        background = Ui.rounded(bgColor, 16, ctx, stroke)
        setPadding(dp(20), dp(32), dp(20), dp(32))
        addView(text("저장한 만화가 없습니다", 15f, titleColor, 700).apply { gravity = Gravity.CENTER })
        addView(
            text("보던 주소를 복사한 뒤\n＋ 단추를 누르세요", 12.5f, subColor).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            },
        )
    }
}

// ================================================================ 1. 타임라인

/** 왼쪽에 세로줄을 긋는 목록. 줄 위에 묶음마다 동그라미가 얹힌다. */
private class RailColumn(ctx: Context, lineColor: Int) : LinearLayout(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }

    var drawLine = true
        set(v) {
            field = v
            invalidate()
        }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!drawLine || childCount == 0) return
        val d = resources.displayMetrics.density
        canvas.drawRect(9f * d, 10f * d, 11f * d, height.toFloat(), paint)
    }
}

/**
 * 흰 바탕. "오늘 볼 만화" 아래로 새 회차 → 확인 실패 → 최신 → 확인 전 순서로
 * 세로줄을 따라 묶는다. 새 회차는 카드, 나머지는 낮은 줄.
 * 오른쪽 아래에 추가와 새 회차 확인 단추가 떠 있다.
 */
private class TimelineScreen(ctx: Context, host: MainHost, store: Store) : MainScreen(ctx, host, store) {

    private companion object {
        val WHITE = Color.WHITE
        val INK = Color.parseColor("#16131F")
        val DIM = Color.parseColor("#6B6680")
        val ACCENT = Color.parseColor("#6E3BFF")
        val ACCENT_SOFT = Color.parseColor("#F2EEFF")
        val ACCENT_RING = Color.parseColor("#E9E2FF")
        val LINE = Color.parseColor("#ECE9F5")
        val SOFT = Color.parseColor("#F4F2FA")
        val ROW = Color.parseColor("#FAF9FD")
        val GRAY = Color.parseColor("#C9C5D6")
        val GRAY_RING = Color.parseColor("#F1EFF6")
        val RED = Color.parseColor("#D92D20")
        val RED_DEEP = Color.parseColor("#B42318")
        val RED_SOFT = Color.parseColor("#FEF3F2")
        val RED_RING = Color.parseColor("#FDE5E3")
    }

    override val bg = WHITE
    override val lightBars = true
    override val view: View
    override val swipe: SwipeRefreshLayout

    private val domainText: TextView
    private val rail: RailColumn
    private val checkLabel: TextView

    init {
        val content = vcol().apply { setPadding(dp(20), dp(22), dp(20), dp(120)) }

        val titleRow = hrow()
        titleRow.addView(
            oneLine(text("오늘 볼 만화", 30f, INK, 900)).apply { letterSpacing = -0.03f },
            grow(),
        )
        titleRow.addView(hiddenToggle(), box(56, 44))
        content.addView(titleRow, fill())

        val domainRow = hrow()
        domainRow.addView(text("주소", 13f, DIM))
        domainText = oneLine(text("", 14f, INK, 700)).apply {
            setPadding(dp(6), dp(12), dp(6), dp(12))
            Ui.tappable(this, null, RIPPLE_ON_LIGHT)
            setOnClickListener { host.editDomain() }
        }
        domainRow.addView(domainText, LinearLayout.LayoutParams(WRAP, WRAP))
        domainRow.addView(
            iconButton(R.drawable.ic_minus, INK, 13, Ui.rounded(SOFT, 10, ctx), RIPPLE_ON_LIGHT, "주소 번호 내리기") {
                host.bumpDomain(-1)
            },
            box(40, 44),
        )
        domainRow.addView(
            iconButton(R.drawable.ic_plus, ACCENT, 13, Ui.rounded(SOFT, 10, ctx), RIPPLE_ON_LIGHT, "주소 번호 올리기") {
                host.bumpDomain(1)
            },
            box(40, 44).apply { leftMargin = dp(6) },
        )
        domainRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        domainRow.addView(
            iconButton(R.drawable.ic_more, DIM, 20, null, RIPPLE_ON_LIGHT, "더보기") { host.showMore() },
            box(44, 44),
        )
        content.addView(domainRow, fill(4))

        rail = RailColumn(ctx, LINE)
        content.addView(rail, fill(18))

        swipe = swipeOf(scrollOf(content), ACCENT, WHITE)

        val frame = FrameLayout(ctx).apply { setBackgroundColor(WHITE) }
        frame.addView(swipe, FrameLayout.LayoutParams(MATCH, MATCH))

        // 한 손으로 누르기 쉽게 오른쪽 아래에 띄운다. 그림자가 잘리지 않게 여백을 둔다.
        val fabs = hrow().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        fabs.addView(
            iconButton(R.drawable.ic_plus, INK, 20, Ui.rounded(WHITE, 999, ctx, LINE), RIPPLE_ON_LIGHT, "만화 추가") {
                host.addComic()
            }.apply { elevation = dp(4).toFloat() },
            box(52, 52),
        )
        val check = hrow().apply {
            setPadding(dp(22), 0, dp(22), 0)
            Ui.tappable(this, Ui.rounded(ACCENT, 999, ctx), RIPPLE_ON_DARK)
            elevation = dp(6).toFloat()
            setOnClickListener { host.checkAll() }
        }
        check.addView(icon(R.drawable.ic_refresh, WHITE), box(18, 18))
        checkLabel = text("새 회차 확인", 15f, WHITE, 900)
        check.addView(checkLabel, wrapLeft(8))
        fabs.addView(check, LinearLayout.LayoutParams(WRAP, dp(58)).apply { leftMargin = dp(10) })
        frame.addView(
            fabs,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(12)
                bottomMargin = dp(14)
            },
        )
        view = frame
    }

    override fun showProgress(text: String?) {
        checkLabel.text = text ?: "새 회차 확인"
    }

    override fun render() {
        domainText.text = SiteUrl.buildHost(store.domain)
        rail.removeAllViews()

        val all = store.comics
        if (all.isEmpty()) {
            rail.drawLine = false
            rail.addView(emptyCard(ROW, LINE, INK, DIM), fill())
            return
        }
        rail.drawLine = true

        var first = true
        fun group(label: String, node: Int, ring: Int, labelColor: Int, items: List<Comic>, hDp: Int, make: (Comic) -> View) {
            if (items.isEmpty()) return
            rail.addView(groupHeader(label, node, ring, labelColor), fill(if (first) 0 else 16))
            first = false
            items.forEachIndexed { i, c ->
                rail.addView(
                    make(c),
                    LinearLayout.LayoutParams(MATCH, dp(hDp)).apply {
                        leftMargin = dp(32)
                        topMargin = dp(if (i == 0) 8 else 6)
                    },
                )
            }
        }

        val fresh = all.filter { it.next == NextStatus.YES }
        val trouble = all.filter { isTrouble(it) }
        val latest = all.filter { it.next == NextStatus.NO }
        val unknown = all.filter { it.next == NextStatus.UNKNOWN }
        group("새 회차 ${fresh.size}", ACCENT, ACCENT_RING, ACCENT, fresh, 56) { freshCard(it) }
        group("확인 실패 ${trouble.size}", RED, RED_RING, RED_DEEP, trouble, 44) { troubleRow(it) }
        group("최신까지 봄 ${latest.size}", GRAY, GRAY_RING, DIM, latest, 44) { plainRow(it) }
        group("아직 확인 전 ${unknown.size}", GRAY, GRAY_RING, DIM, unknown, 44) { plainRow(it) }
    }

    private fun groupHeader(label: String, node: Int, ring: Int, color: Int): View = hrow().apply {
        addView(View(ctx).apply { background = circle(node, ring, 4) }, box(20, 20))
        addView(text(label, 14f, color, 800), wrapLeft(12))
    }

    private fun freshCard(c: Comic): View = hrow().apply {
        setPadding(dp(14), 0, dp(6), 0)
        bindComic(c, Ui.rounded(WHITE, 12, ctx, LINE), RIPPLE_ON_LIGHT)
        val texts = vcol()
        texts.addView(oneLine(text(c.title, 15f, INK, 800)))
        texts.addView(oneLine(text(freshSub(c, "까지 봄"), 12f, DIM)))
        addView(texts, grow())
        val next = hrow().apply {
            setPadding(dp(12), 0, dp(12), 0)
            Ui.tappable(this, Ui.rounded(ACCENT_SOFT, 10, ctx), RIPPLE_ON_LIGHT)
            setOnClickListener { host.openNew(c) }
        }
        next.addView(text("이어 보기", 14f, ACCENT, 800))
        next.addView(icon(R.drawable.ic_arrow, ACCENT), box(13, 13).apply { leftMargin = dp(4) })
        addView(next, LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(8) })
    }

    private fun plainRow(c: Comic): View = hrow().apply {
        setPadding(dp(14), 0, dp(14), 0)
        bindComic(c, Ui.rounded(ROW, 10, ctx), RIPPLE_ON_LIGHT)
        addView(oneLine(text(c.title, 14f, INK, 700)), grow())
        ep(c)?.let { addView(text(it, 12f, DIM), wrapLeft(8)) }
    }

    private fun troubleRow(c: Comic): View = hrow().apply {
        setPadding(dp(14), 0, dp(4), 0)
        bindComic(c, Ui.rounded(RED_SOFT, 10, ctx), RIPPLE_ON_LIGHT)
        addView(oneLine(text(c.title, 14f, INK, 700)), grow())
        val why = text(if (c.next == NextStatus.NO_EPISODE) "주소 고치기 ›" else "이유 보기 ›", 12f, RED_DEEP, 700).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            Ui.tappable(this, null, RIPPLE_ON_LIGHT)
            setOnClickListener { host.showStatus(c) }
        }
        addView(why, LinearLayout.LayoutParams(WRAP, MATCH).apply { leftMargin = dp(4) })
    }
}

// ================================================================ 4. 검색 + 초성

/**
 * 어두운 바탕에 호박색. 위에 검색칸, 새 회차는 "고정" 칸에 따로 올리고
 * 전체 목록은 가나다순으로 초성마다 묶는다. 오른쪽 초성 줄을 누르면 그 칸으로 간다.
 * 맨 아래에 새 회차 확인 단추.
 */
private class IndexScreen(ctx: Context, host: MainHost, store: Store) : MainScreen(ctx, host, store) {

    private companion object {
        val BG = Color.parseColor("#15130F")
        val FIELD = Color.parseColor("#1F1C17")
        val PIN_BG = Color.parseColor("#2A2214")
        val PIN_LINE = Color.parseColor("#4A3A1C")
        val ROW = Color.parseColor("#1D1A15")
        val TEXT = Color.parseColor("#F5F1E8")
        val DIM = Color.parseColor("#A69F90")
        val FAINT = Color.parseColor("#5E584C")
        val ACCENT = Color.parseColor("#FFB224")
        val ON_ACCENT = Color.parseColor("#2A1A00")
        val FAIL_BG = Color.parseColor("#2A1816")
        val FAIL_TEXT = Color.parseColor("#FF9A8A")
        val BAR_LINE = Color.parseColor("#2A261E")
    }

    override val bg = BG
    override val lightBars = false
    override val view: View
    override val swipe: SwipeRefreshLayout

    private val search: EditText
    private val clear: View
    private val domainText: TextView
    private val list: LinearLayout
    private val scroll: ScrollView
    private val railBox: LinearLayout
    private val checkLabel: TextView

    /** 초성 칸마다 첫 줄. 오른쪽 초성 줄을 누르면 여기로 옮긴다. */
    private val anchors = LinkedHashMap<String, View>()
    private val collator = Collator.getInstance(Locale.KOREAN)

    init {
        val root = vcol().apply {
            setBackgroundColor(BG)
            // 처음부터 검색칸에 커서가 가서 키보드가 뜨지 않게 바탕이 먼저 초점을 받는다.
            isFocusableInTouchMode = true
        }

        val top = hrow().apply { setPadding(dp(16), dp(16), dp(16), 0) }
        val field = hrow().apply {
            background = Ui.rounded(FIELD, 14, ctx)
            setPadding(dp(14), 0, 0, 0)
        }
        field.addView(icon(R.drawable.ic_search, DIM), box(17, 17))
        search = EditText(ctx).apply {
            hint = "만화 이름 검색 (초성도 돼요)"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            textSize = 15f
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), 0, dp(4), 0)
        }
        field.addView(search, LinearLayout.LayoutParams(0, MATCH, 1f))
        clear = iconButton(R.drawable.ic_plus, DIM, 16, null, RIPPLE_ON_DARK, "검색어 지우기") {
            search.setText("")
        }.apply {
            getChildAt(0).rotation = 45f
            visibility = View.GONE
        }
        field.addView(clear, box(44, 48))
        top.addView(field, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(
            iconButton(R.drawable.ic_plus, ON_ACCENT, 20, Ui.rounded(ACCENT, 14, ctx), RIPPLE_ON_LIGHT, "만화 추가") {
                host.addComic()
            },
            box(48, 48).apply { leftMargin = dp(8) },
        )
        root.addView(top, fill())

        val dom = hrow().apply { setPadding(dp(16), dp(2), dp(8), 0) }
        domainText = oneLine(text("", 13f, DIM)).apply {
            setPadding(0, dp(12), dp(6), dp(12))
            Ui.tappable(this, null, RIPPLE_ON_DARK)
            setOnClickListener { host.editDomain() }
        }
        dom.addView(domainText, LinearLayout.LayoutParams(WRAP, WRAP))
        dom.addView(
            iconButton(R.drawable.ic_minus, DIM, 13, null, RIPPLE_ON_DARK, "주소 번호 내리기") { host.bumpDomain(-1) },
            box(40, 44),
        )
        dom.addView(
            iconButton(R.drawable.ic_plus, TEXT, 13, null, RIPPLE_ON_DARK, "주소 번호 올리기") { host.bumpDomain(1) },
            box(40, 44),
        )
        dom.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        dom.addView(hiddenToggle(), box(56, 44))
        dom.addView(
            iconButton(R.drawable.ic_more, DIM, 18, null, RIPPLE_ON_DARK, "더보기") { host.showMore() },
            box(44, 44),
        )
        root.addView(dom, fill())

        list = vcol().apply { setPadding(dp(16), dp(4), dp(34), dp(16)) }
        scroll = scrollOf(list)
        swipe = swipeOf(scroll, ACCENT, FIELD)
        railBox = vcol().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val middle = FrameLayout(ctx)
        middle.addView(swipe, FrameLayout.LayoutParams(MATCH, MATCH))
        middle.addView(
            railBox,
            FrameLayout.LayoutParams(dp(28), WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(3) },
        )
        root.addView(middle, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(View(ctx).apply { setBackgroundColor(BAR_LINE) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        val bar = vcol().apply { setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val check = hrow().apply {
            gravity = Gravity.CENTER
            val shape = Ui.rounded(FIELD, 14, ctx).apply {
                setStroke((1.5f * ctx.resources.displayMetrics.density).toInt(), ACCENT)
            }
            Ui.tappable(this, shape, RIPPLE_ON_DARK)
            setOnClickListener { host.checkAll() }
        }
        check.addView(icon(R.drawable.ic_refresh, ACCENT), box(17, 17))
        checkLabel = text("새 회차 확인", 15f, ACCENT, 700)
        check.addView(checkLabel, wrapLeft(8))
        bar.addView(check, LinearLayout.LayoutParams(MATCH, dp(52)))
        root.addView(bar, fill())

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                renderList()
            }
        })
        search.setOnEditorActionListener { v, _, _ ->
            hideKeyboard(v)
            true
        }
        view = root
    }

    private fun hideKeyboard(v: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    override fun showProgress(text: String?) {
        checkLabel.text = text ?: "새 회차 확인"
    }

    override fun render() {
        domainText.text = SiteUrl.buildHost(store.domain)
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        anchors.clear()

        val all = store.comics
        if (all.isEmpty()) {
            list.addView(emptyCard(FIELD, null, TEXT, DIM), fill(8))
            buildRail()
            return
        }

        val sorted = all.sortedWith(Comparator { a, b ->
            val ga = Chosung.order(Chosung.groupOf(a.title))
            val gb = Chosung.order(Chosung.groupOf(b.title))
            if (ga != gb) ga - gb else collator.compare(a.title.trim(), b.title.trim())
        })
        val q = search.text?.toString().orEmpty().trim()

        if (q.isEmpty()) {
            val fresh = all.filter { it.next == NextStatus.YES }
            if (fresh.isNotEmpty()) {
                val head = hrow()
                head.addView(icon(R.drawable.ic_pin, ACCENT), box(14, 14))
                head.addView(text("고정 · 새 회차 ${fresh.size}", 13f, ACCENT, 700), wrapLeft(6))
                list.addView(head, fill(8))
                fresh.forEachIndexed { i, c -> list.addView(pinCard(c), rowParams(56, if (i == 0) 8 else 6)) }
            }
            list.addView(text("전체 ${all.size}", 13f, DIM, 700), fill(if (fresh.isEmpty()) 8 else 18))
            addRows(sorted)
        } else {
            val hits = sorted.filter { Chosung.matches(it.title, q) }
            list.addView(text("검색 결과 ${hits.size}", 13f, DIM, 700), fill(8))
            if (hits.isEmpty()) {
                list.addView(text("맞는 만화가 없습니다", 13f, FAINT), fill(14))
            }
            addRows(hits)
        }
        buildRail()
    }

    private fun addRows(items: List<Comic>) {
        var last: String? = null
        items.forEachIndexed { i, c ->
            val g = Chosung.groupOf(c.title)
            val r = hrow()
            r.addView(
                text(if (g != last) g else "", 13f, ACCENT, 700).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(18), WRAP),
            )
            r.addView(rowCard(c), LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
            list.addView(r, fill(if (i == 0) 8 else 6))
            if (g != last) {
                anchors[g] = r
                last = g
            }
        }
    }

    private fun buildRail() {
        railBox.removeAllViews()
        if (anchors.isEmpty()) {
            railBox.visibility = View.GONE
            return
        }
        railBox.visibility = View.VISIBLE
        val letters = Chosung.BASIC.map { it.toString() } +
            listOf(Chosung.LATIN, Chosung.OTHER).filter { anchors.containsKey(it) }
        for (g in letters) {
            val on = anchors.containsKey(g)
            val tv = text(g, 11f, if (on) ACCENT else FAINT, if (on) 700 else 500).apply {
                gravity = Gravity.CENTER
                if (on) {
                    Ui.tappable(this, null, RIPPLE_ON_DARK)
                    setOnClickListener { jump(g) }
                }
            }
            railBox.addView(tv, LinearLayout.LayoutParams(dp(28), dp(22)))
        }
    }

    private fun jump(g: String) {
        val target = anchors[g] ?: return
        scroll.smoothScrollTo(0, (target.top - dp(8)).coerceAtLeast(0))
    }

    private fun pinCard(c: Comic): View = hrow().apply {
        setPadding(dp(10), 0, dp(6), 0)
        bindComic(c, Ui.rounded(PIN_BG, 14, ctx, PIN_LINE), RIPPLE_ON_DARK)
        addView(avatar(c.title, ACCENT, ON_ACCENT, 15f), box(36, 36))
        val texts = vcol()
        texts.addView(oneLine(text(c.title, 15f, TEXT, 700)))
        texts.addView(oneLine(text(freshSub(c, "까지 봄"), 12f, DIM)))
        addView(texts, grow(12))
        val next = text("이어 보기", 14f, ON_ACCENT, 700).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            Ui.tappable(this, Ui.rounded(ACCENT, 999, ctx), RIPPLE_ON_LIGHT)
            setOnClickListener { host.openNew(c) }
        }
        addView(next, LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(8) })
    }

    private fun rowCard(c: Comic): View = hrow().apply {
        val trouble = isTrouble(c)
        val fresh = c.next == NextStatus.YES
        setPadding(dp(12), 0, dp(if (trouble || fresh) 2 else 12), 0)
        bindComic(c, Ui.rounded(if (trouble) FAIL_BG else ROW, 10, ctx), RIPPLE_ON_DARK)
        addView(oneLine(text(c.title, 14f, TEXT, 600)), grow())
        when {
            fresh -> addView(
                text("새 회차 ›", 12f, ACCENT, 700).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    Ui.tappable(this, null, RIPPLE_ON_DARK)
                    setOnClickListener { host.openNew(c) }
                },
                LinearLayout.LayoutParams(WRAP, MATCH).apply { leftMargin = dp(4) },
            )

            trouble -> addView(
                text(if (c.next == NextStatus.NO_EPISODE) "회차 없음 ›" else "실패 ›", 12f, FAIL_TEXT, 700).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    Ui.tappable(this, null, RIPPLE_ON_DARK)
                    setOnClickListener { host.showStatus(c) }
                },
                LinearLayout.LayoutParams(WRAP, MATCH).apply { leftMargin = dp(4) },
            )

            else -> ep(c)?.let { addView(text(it, 12f, DIM), wrapLeft(8)) }
        }
    }
}

// ================================================================ 5. 알림 피드

/**
 * 하늘색 바탕에 흰 카드. 새 회차를 "새 소식" 알림처럼 위에 쌓고,
 * 나머지는 "조용한 만화" 한 칸에 접어 둔다. 아래 가운데에 단추 셋.
 */
private class FeedScreen(ctx: Context, host: MainHost, store: Store) : MainScreen(ctx, host, store) {

    private companion object {
        val BG = Color.parseColor("#EAF3FF")
        val WHITE = Color.WHITE
        val INK = Color.parseColor("#0B1B33")
        val DIM = Color.parseColor("#56667F")
        val ACCENT = Color.parseColor("#1463D8")
        val DOT = Color.parseColor("#E5484D")
        val FAIL_BG = Color.parseColor("#FFF1F0")
        val FAIL_LINE = Color.parseColor("#FFD5D1")
        val FAIL = Color.parseColor("#D92D20")
        val FAIL_DEEP = Color.parseColor("#B42318")
        val QUIET_HEAD = Color.parseColor("#F5F9FF")
        val DIVIDER = Color.parseColor("#EEF2F8")

        /** 표지 대신 쓰는 동그라미 색. 제목마다 늘 같은 색이 나온다. */
        val AVATARS = intArrayOf(
            Color.parseColor("#2B1B5A"),
            Color.parseColor("#7A1E1E"),
            Color.parseColor("#0F3D3E"),
            Color.parseColor("#4A3512"),
            Color.parseColor("#14143A"),
            Color.parseColor("#3A3A44"),
            Color.parseColor("#23324A"),
            Color.parseColor("#5A0F1F"),
            Color.parseColor("#1F4A2C"),
        )
    }

    override val bg = BG
    override val lightBars = true
    override val view: View
    override val swipe: SwipeRefreshLayout

    private val domainText: TextView
    private val subText: TextView
    private val dot: View
    private val news: LinearLayout
    private val quietLabel: TextView
    private val quietCard: LinearLayout
    private val checkLabel: TextView

    init {
        val content = vcol().apply { setPadding(dp(16), dp(18), dp(16), dp(110)) }

        val head = hrow()
        val bell = FrameLayout(ctx).apply { background = circle(WHITE) }
        bell.addView(icon(R.drawable.ic_bell, ACCENT), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        dot = View(ctx).apply { background = circle(DOT, WHITE, 2) }
        bell.addView(
            dot,
            FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                rightMargin = dp(9)
            },
        )
        head.addView(bell, box(44, 44))

        // 제목 칸 오른쪽 끝에 숨긴 목록 단추를 겹쳐 둔다.
        val titleBox = FrameLayout(ctx)
        val titles = vcol()
        titles.addView(oneLine(text("만화 바로가기", 19f, INK, 800)))
        subText = oneLine(text("", 12f, DIM))
        titles.addView(subText)
        titleBox.addView(titles, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        titleBox.addView(hiddenToggle(), FrameLayout.LayoutParams(dp(56), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        head.addView(titleBox, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10) })

        val pill = hrow().apply { background = Ui.rounded(WHITE, 999, ctx) }
        pill.addView(
            iconButton(R.drawable.ic_minus, DIM, 12, null, RIPPLE_ON_LIGHT, "주소 번호 내리기") { host.bumpDomain(-1) },
            box(36, 44),
        )
        domainText = oneLine(text("", 12f, INK, 700)).apply {
            setPadding(dp(2), dp(12), dp(2), dp(12))
            Ui.tappable(this, null, RIPPLE_ON_LIGHT)
            setOnClickListener { host.editDomain() }
        }
        pill.addView(domainText, LinearLayout.LayoutParams(WRAP, WRAP))
        pill.addView(
            iconButton(R.drawable.ic_plus, ACCENT, 12, null, RIPPLE_ON_LIGHT, "주소 번호 올리기") { host.bumpDomain(1) },
            box(36, 44),
        )
        head.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        content.addView(head, fill())

        content.addView(text("새 소식", 13f, DIM, 700), fill(18))
        news = vcol().apply { setPadding(0, 0, 0, dp(2)) }
        content.addView(news, fill(8))

        quietLabel = text("조용한 만화", 13f, DIM, 700)
        content.addView(quietLabel, fill(18))
        quietCard = vcol().apply {
            background = Ui.rounded(WHITE, 16, ctx)
            clipToOutline = true
        }
        content.addView(quietCard, fill(8))

        swipe = swipeOf(scrollOf(content), ACCENT, WHITE)
        val frame = FrameLayout(ctx).apply { setBackgroundColor(BG) }
        frame.addView(swipe, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = hrow().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        bar.addView(
            iconButton(R.drawable.ic_plus, INK, 20, circle(WHITE), RIPPLE_ON_LIGHT, "만화 추가") { host.addComic() }
                .apply { elevation = dp(4).toFloat() },
            box(52, 52),
        )
        val check = hrow().apply {
            setPadding(dp(26), 0, dp(26), 0)
            Ui.tappable(this, Ui.rounded(ACCENT, 999, ctx), RIPPLE_ON_DARK)
            elevation = dp(6).toFloat()
            setOnClickListener { host.checkAll() }
        }
        check.addView(icon(R.drawable.ic_refresh, WHITE), box(17, 17))
        checkLabel = text("새 소식 확인", 15f, WHITE, 800)
        check.addView(checkLabel, wrapLeft(8))
        bar.addView(check, LinearLayout.LayoutParams(WRAP, dp(56)).apply { leftMargin = dp(10) })
        bar.addView(
            iconButton(R.drawable.ic_more, INK, 20, circle(WHITE), RIPPLE_ON_LIGHT, "더보기") { host.showMore() }
                .apply { elevation = dp(4).toFloat() },
            box(52, 52).apply { leftMargin = dp(10) },
        )
        frame.addView(
            bar,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(12)
            },
        )
        view = frame
    }

    override fun showProgress(text: String?) {
        checkLabel.text = text ?: "새 소식 확인"
    }

    private fun avatarColor(title: String): Int = AVATARS[(title.hashCode() and 0x7fffffff) % AVATARS.size]

    override fun render() {
        // 좁은 알약에 들어가도록 뒤쪽 .org 같은 건 뺀다.
        domainText.text = SiteUrl.buildHost(store.domain).substringBeforeLast('.')

        val all = store.comics
        val fresh = all.filter { it.next == NextStatus.YES }
        val trouble = all.filter { isTrouble(it) }
        val quiet = all.filter { it.next == NextStatus.NO || it.next == NextStatus.UNKNOWN }

        dot.visibility = if (fresh.isNotEmpty()) View.VISIBLE else View.GONE
        subText.text = when {
            all.isEmpty() -> "저장한 만화 없음"
            fresh.isNotEmpty() -> "새 소식 ${fresh.size}개"
            all.all { it.next == NextStatus.UNKNOWN } -> "아직 확인 전"
            else -> "새 소식 없음"
        }

        news.removeAllViews()
        quietCard.removeAllViews()
        if (all.isEmpty()) {
            news.addView(emptyCard(WHITE, null, INK, DIM), fill())
            quietLabel.visibility = View.GONE
            quietCard.visibility = View.GONE
            return
        }

        var n = 0
        for (c in fresh) news.addView(newsCard(c), rowParams(60, if (n++ == 0) 0 else 8))
        for (c in trouble) news.addView(troubleCard(c), rowParams(52, if (n++ == 0) 0 else 8))
        if (n == 0) {
            val msg = if (all.all { it.next == NextStatus.UNKNOWN }) {
                "아래 [새 소식 확인]을 누르면 확인해요"
            } else {
                "새로 올라온 회차가 없어요"
            }
            news.addView(
                text(msg, 13f, DIM).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    background = Ui.rounded(WHITE, 16, ctx)
                },
                rowParams(52, 0),
            )
        }

        if (quiet.isEmpty()) {
            quietLabel.visibility = View.GONE
            quietCard.visibility = View.GONE
            return
        }
        quietLabel.visibility = View.VISIBLE
        quietCard.visibility = View.VISIBLE

        val open = store.feedQuietOpen
        val qh = hrow().apply {
            setPadding(dp(12), 0, dp(14), 0)
            Ui.tappable(this, ColorDrawable(QUIET_HEAD), RIPPLE_ON_LIGHT)
            setOnClickListener {
                store.feedQuietOpen = !open
                render()
            }
        }
        val stack = hrow()
        quiet.take(5).forEachIndexed { i, c ->
            stack.addView(
                avatar(c.title, avatarColor(c.title), WHITE, 10f, QUIET_HEAD, 2),
                box(26, 26).apply { if (i > 0) leftMargin = -dp(9) },
            )
        }
        qh.addView(stack)
        val unknown = quiet.count { it.next == NextStatus.UNKNOWN }
        val label = when {
            unknown == 0 -> "${quiet.size}편 모두 최신까지 봤어요"
            unknown == quiet.size -> "${quiet.size}편 · 아직 확인 전"
            else -> "${quiet.size}편 · 확인 전 ${unknown}편"
        }
        qh.addView(oneLine(text(label, 13f, INK, 700)), grow(12))
        qh.addView(icon(R.drawable.ic_chevron, DIM).apply { rotation = if (open) 0f else 180f }, box(16, 16))
        quietCard.addView(qh, LinearLayout.LayoutParams(MATCH, dp(54)))

        if (open) {
            for (c in quiet) {
                quietCard.addView(View(ctx).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(MATCH, dp(1)))
                quietCard.addView(quietRow(c), LinearLayout.LayoutParams(MATCH, dp(44)))
            }
        }
    }

    private fun newsCard(c: Comic): View = hrow().apply {
        setPadding(dp(12), 0, dp(6), 0)
        bindComic(c, Ui.rounded(WHITE, 16, ctx), RIPPLE_ON_LIGHT)
        elevation = dp(1).toFloat()
        val av = FrameLayout(ctx)
        av.addView(
            avatar(c.title, avatarColor(c.title), WHITE, 15f),
            FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.START),
        )
        av.addView(
            View(ctx).apply { background = circle(ACCENT, WHITE, 2) },
            FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END),
        )
        addView(av, box(40, 40))
        val texts = vcol()
        texts.addView(oneLine(text(boldLead(c.title, " 다음 화가 올라왔어요"), 14f, INK)))
        texts.addView(oneLine(text(freshSub(c, "까지 봤어요"), 12f, DIM)))
        addView(texts, grow(10))
        val go = text("보기", 14f, WHITE, 800).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            Ui.tappable(this, Ui.rounded(ACCENT, 12, ctx), RIPPLE_ON_DARK)
            setOnClickListener { host.openNew(c) }
        }
        addView(go, LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(8) })
    }

    private fun troubleCard(c: Comic): View = hrow().apply {
        val noEp = c.next == NextStatus.NO_EPISODE
        setPadding(dp(12), 0, dp(4), 0)
        bindComic(c, Ui.rounded(FAIL_BG, 16, ctx, FAIL_LINE), RIPPLE_ON_LIGHT)
        val mark = text("!", 15f, WHITE, 800).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = circle(FAIL)
            contentDescription = "실패 이유"
            setOnClickListener { host.showStatus(c) }
        }
        addView(mark, box(28, 28))
        val msg = if (noEp) " 주소에 회차 번호가 없어요" else " 확인에 실패했어요"
        addView(oneLine(text(boldLead(c.title, msg), 13f, INK)), grow(12))
        val act = text(if (noEp) "고치기" else "다시", 13f, FAIL_DEEP, 800).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            Ui.tappable(this, null, RIPPLE_ON_LIGHT)
            setOnClickListener { if (noEp) host.showStatus(c) else host.recheck(c) }
        }
        addView(act, LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(4) })
    }

    private fun quietRow(c: Comic): View = hrow().apply {
        setPadding(dp(14), 0, dp(14), 0)
        bindComic(c, null, RIPPLE_ON_LIGHT)
        addView(oneLine(text(c.title, 14f, INK, 500)), grow())
        ep(c)?.let { addView(text(it, 12f, DIM), wrapLeft(8)) }
    }
}
