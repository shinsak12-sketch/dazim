package com.toonshortcut.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35(안드로이드 15)부터는 앱 화면이 상태바·내비게이션바 아래까지
 * 강제로 그려진다(edge-to-edge). 그대로 두면 상단 버튼이 시계·배터리 아이콘에
 * 가리고, 하단도 내비게이션 바에 겹친다.
 *
 * 시스템 바와 카메라 홀(디스플레이 컷아웃)만큼 바깥 여백을 준다.
 */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        // 자식 뷰가 같은 여백을 또 적용하지 않도록 여기서 소비한다.
        WindowInsetsCompat.CONSUMED
    }
    // 이미 화면에 붙은 뒤에 호출돼도 반영되도록 한 번 요청한다.
    ViewCompat.requestApplyInsets(this)
}
