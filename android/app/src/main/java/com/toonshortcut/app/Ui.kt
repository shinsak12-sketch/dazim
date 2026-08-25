package com.toonshortcut.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * 화면 전체가 같은 규칙을 쓰도록 색과 모양을 한곳에 모았다.
 * 뷰를 코드로 만들기 때문에 여기서 통일하지 않으면 화면마다 조금씩 어긋난다.
 */
object Ui {

    // 색 — 어두운 배경에 하늘색 강조. 상태색은 초록/빨강/노랑.
    val BG = Color.parseColor("#0B1120")
    val SURFACE = Color.parseColor("#151E2E")
    val SURFACE_HI = Color.parseColor("#1F2A3D")
    val BORDER = Color.parseColor("#26344B")
    val TEXT = Color.parseColor("#E8EEF7")
    val TEXT_DIM = Color.parseColor("#8A9AB4")
    val TEXT_FAINT = Color.parseColor("#5A6B85")
    val ACCENT = Color.parseColor("#38BDF8")
    val ACCENT_DEEP = Color.parseColor("#0EA5E9")
    val ON_ACCENT = Color.parseColor("#06121F")
    val GREEN = Color.parseColor("#34D399")
    val RED = Color.parseColor("#F87171")
    val AMBER = Color.parseColor("#FBBF24")

    private const val RIPPLE = 0x33FFFFFF

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 모서리가 둥근 단색 배경. 테두리를 넣으면 카드처럼 보인다. */
    fun rounded(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    /** 알약 모양. 배지에 쓴다. */
    fun pill(color: Int, ctx: Context): GradientDrawable =
        rounded(color, 999, ctx)

    /** 눌렀을 때 반응이 보이도록 물결 효과를 씌운다. */
    fun tappable(view: View, background: Drawable) {
        view.background = RippleDrawable(ColorStateList.valueOf(RIPPLE), background, null)
        view.isClickable = true
        view.isFocusable = true
    }

    /** 색에 투명도를 준다. 배지 배경처럼 은은한 색을 만들 때 쓴다. */
    fun alpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
