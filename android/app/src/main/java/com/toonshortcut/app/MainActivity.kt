package com.toonshortcut.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var listContainer: LinearLayout
    private lateinit var domainView: TextView

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
        // 뷰어에서 회차가 바뀌었을 수 있으니 돌아올 때마다 다시 그린다.
        render()
    }

    /** 크롬에서 "공유 → 만화 바로가기" 로 들어온 주소를 추가 창에 채워준다. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return
        intent.removeExtra(Intent.EXTRA_TEXT)
        if (text.isNotEmpty()) showComicDialog(prefillUrl = text)
    }

    // ------------------------------------------------------------------ 화면

    private fun buildLayout(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#020617")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "만화 바로가기"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
        })

        root.addView(buildDomainCard())

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }
        root.addView(listContainer)

        root.addView(primaryButton("+ 만화 추가") { showComicDialog() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        })

        val backupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        backupRow.addView(subtleButton("목록 내보내기") { exportList() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        backupRow.addView(subtleButton("목록 가져오기") { showImportDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) }
        })
        root.addView(backupRow)

        root.addView(TextView(this).apply {
            text = "만화를 보다가 다음 화로 넘어가면 앱이 알아서 회차를 저장합니다.\n" +
                "주소가 막히면 번호를 올려 자동으로 다시 시도합니다."
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
        })

        scroll.addView(root)
        return scroll
    }

    private fun buildDomainCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }

        card.addView(TextView(this).apply {
            text = "현재 주소"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

        row.addView(bigButton("−") { store.bumpDomain(-1); render() })

        domainView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#F1F5F9"))
            isClickable = true
            setOnClickListener { showDomainDialog() }
        }
        row.addView(domainView)

        row.addView(bigButton("+") { store.bumpDomain(1); render() })
        card.addView(row)
        return card
    }

    private fun render() {
        domainView.text = SiteUrl.buildHost(store.domain)
        listContainer.removeAllViews()

        val comics = store.comics
        if (comics.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "저장한 만화가 없습니다.\n보던 주소를 복사한 뒤 아래 [만화 추가]를 누르세요."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#64748B"))
                setPadding(dp(16), dp(32), dp(16), dp(32))
            })
            return
        }

        for (c in comics) listContainer.addView(buildComicCard(c))
    }

    private fun buildComicCard(c: Comic): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            setOnClickListener { openReader(c) }
            setOnLongClickListener { showComicMenu(c); true }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = c.title
            textSize = 15f
            maxLines = 1
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
        })
        texts.addView(TextView(this).apply {
            text = SiteUrl.episodeLabel(c.path) ?: SiteUrl.decodeUri(c.path)
            textSize = 12f
            maxLines = 1
            setTextColor(Color.parseColor("#38BDF8"))
        })
        card.addView(texts)

        card.addView(TextView(this).apply {
            text = "열기"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#020617"))
            setBackgroundColor(Color.parseColor("#0EA5E9"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { openReader(c) }
        })
        return card
    }

    private fun openReader(c: Comic) {
        startActivity(
            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_COMIC_ID, c.id),
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
                    1 -> { store.move(c.id, -1); render() }
                    2 -> { store.move(c.id, 1); render() }
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }

        val urlInput = EditText(this).apply {
            hint = "https://tkor146.com/..."
            setSingleLine(false)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(
                prefillUrl
                    ?: existing?.let { SiteUrl.decodeUri(it.path) }
                    ?: clipboardUrl()
                    ?: "",
            )
        }
        val titleInput = EditText(this).apply {
            hint = "제목 (비우면 주소에서 자동)"
            setSingleLine(true)
            setText(existing?.title ?: "")
        }
        box.addView(label("주소"))
        box.addView(urlInput)
        box.addView(label("제목"))
        box.addView(titleInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "만화 추가" else "수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                saveComic(existing, urlInput.text.toString(), titleInput.text.toString())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveComic(existing: Comic?, rawUrl: String, rawTitle: String) {
        val parsed = SiteUrl.parseInput(rawUrl.trim())
        if (parsed == null || parsed.path.isBlank()) {
            toast("주소를 알아볼 수 없습니다.")
            return
        }
        val title = rawTitle.trim().ifEmpty { SiteUrl.guessTitle(parsed.path) }

        if (existing == null) {
            store.addComic(title, parsed.path)
        } else {
            store.updateComic(existing.id, title = title, path = parsed.path)
        }

        // 붙여넣은 주소의 도메인 번호가 다르면 갱신할지 물어본다.
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
        val d = store.domain
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(SiteUrl.buildHost(d))
        }
        box.addView(label("주소 (번호 포함)"))
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("현재 주소")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val parsed = SiteUrl.parseHost(input.text.toString().trim())
                if (parsed == null) {
                    toast("숫자가 들어간 주소여야 합니다. 예: tkor146.com")
                } else {
                    store.domain = parsed
                    render()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ------------------------------------------------------------------ 백업

    private fun exportList() {
        val json = store.exportJson()
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, json)
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
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

    // ------------------------------------------------------------------ 도우미

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun clipboardUrl(): String? =
        clipboardText()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#64748B"))
        setPadding(0, dp(10), 0, 0)
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#020617"))
        setBackgroundColor(Color.parseColor("#0EA5E9"))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

    private fun subtleButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#CBD5E1"))
        setBackgroundColor(Color.parseColor("#1E293B"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun bigButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#F1F5F9"))
        setBackgroundColor(Color.parseColor("#1E293B"))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(52))
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
