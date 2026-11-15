package com.mycarhub.phone.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mycarhub.R
import kotlin.math.abs

/**
 * 다른 앱 위에 떠 있는 작은 MyCarHub 버튼 (폰 테스트용 빌드 전용).
 *
 * - 누르기: 음성비서 수동 실행
 * - 길게 누르기: 화면 아래 가운데에 큰 ✕(끄기) 영역이 나타난다. 손을 떼지 않고 버튼을 ✕까지 끌어 놓으면 [onRemove]
 *   (플로팅 버튼과 호출어 듣기 종료). ✕가 아닌 곳에 놓으면 버튼은 놓은 자리에 그대로 남는다.
 * - 끌기: 위치 이동
 * - 접근성(TalkBack 등)으로 길게 누르기: 끌 수 없으므로 [onLongPressMenu] (버튼 숨기기·상시 듣기 종료 선택)
 * 색으로 상태를 보여준다: 파랑 = 호출어 듣는 중, 회색 = 쉬는 중(통화·마이크 사용 중 등).
 */
class FloatingBubble(
    context: Context,
    private val onTap: () -> Unit,
    private val onRemove: () -> Unit,
    private val onLongPressMenu: () -> Unit,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val view = ImageView(context).apply {
        setImageResource(android.R.drawable.ic_btn_speak_now)
        setBackgroundResource(R.drawable.floating_bubble_paused)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        contentDescription = context.getString(R.string.floating_bubble_description)
    }

    private val params = WindowManager.LayoutParams(
        dp(SIZE_DP),
        dp(SIZE_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(200)
    }

    // region 끄기(✕) 영역: 길게 누른 동안만 보인다. 터치를 받지 않는 창이라 손가락 움직임은 계속 버튼으로 간다.
    private val targetCircle = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    private val targetX = FrameLayout(context).apply {
        background = targetCircle
        addView(
            TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 40f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    private val targetLabel = TextView(context).apply {
        setText(R.string.floating_remove_hint)
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private val target = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(targetX, LinearLayout.LayoutParams(dp(TARGET_DP), dp(TARGET_DP)))
        addView(
            targetLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        visibility = View.INVISIBLE
    }

    private val targetParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(TARGET_BOTTOM_DP)
    }

    private var removing = false
    private var overTarget = false
    // endregion

    private var shown = false

    fun show() {
        if (shown) return
        attachTouch()
        // ✕ 영역을 먼저 붙여 버튼이 그 위에 그려지게 한다 (같은 창 종류는 나중에 붙인 창이 위)
        try {
            windows.addView(target, targetParams)
        } catch (e: RuntimeException) {
            // ✕ 영역 없이도 버튼은 동작한다 (접근성 메뉴·알림으로 끌 수 있음)
        }
        windows.addView(view, params)
        shown = true
    }

    fun hide() {
        if (!shown) return
        removeHint()
        setActive(false)
        shown = false
        endRemoveMode()
        for (v in listOf(view, target)) {
            try {
                windows.removeView(v)
            } catch (e: IllegalArgumentException) {
                // 이미 제거됨
            }
        }
    }

    /** 화면 방향·크기가 바뀌어 화면 밖으로 나간 버튼을 안쪽으로 되돌린다 (세로 폰 ↔ 가로 전체 화면 앱). */
    fun keepOnScreen() {
        val (width, height) = screenSize()
        val size = dp(SIZE_DP)
        params.x = params.x.coerceIn(0, (width - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (height - size).coerceAtLeast(0))
        if (shown) windows.updateViewLayout(view, params)
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = view.context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    fun setListening(listening: Boolean) {
        if (active) return
        view.setBackgroundResource(if (listening) R.drawable.floating_bubble_listening else R.drawable.floating_bubble_paused)
        view.alpha = if (listening) 1f else PAUSED_ALPHA
    }

    // region 명령 듣기·처리 중 표시 (큰 대화창 대신 버튼만 깜빡인다)
    private var active = false
    private val pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
        view,
        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
    ).apply {
        duration = 450
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            view.setBackgroundResource(R.drawable.floating_bubble_listening)
            view.alpha = 1f
            pulse.start()
        } else {
            pulse.cancel()
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    private var hintView: TextView? = null
    private val hideHint = Runnable { removeHint() }

    /** 버튼 옆에 짧은 상태 글자를 잠깐 보여 준다 (실패 표시용). 터치를 받지 않는다. */
    fun showHint(text: String) {
        if (!shown) return
        removeHint()
        val hint = TextView(view.context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC222222.toInt())
            }
        }
        val (width, _) = screenSize()
        val size = dp(SIZE_DP)
        val onLeft = params.x > width / 2
        val hp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (onLeft) Gravity.END else Gravity.START)
            x = if (onLeft) (width - params.x + dp(4)) else (params.x + size + dp(4))
            y = params.y + size / 4
        }
        try {
            windows.addView(hint, hp)
            hintView = hint
            hint.postDelayed(hideHint, HINT_MS)
        } catch (e: RuntimeException) {
            // 표시 못 해도 기능에는 지장 없음
        }
    }

    private fun removeHint() {
        val hint = hintView ?: return
        hintView = null
        hint.removeCallbacks(hideHint)
        try {
            windows.removeView(hint)
        } catch (e: IllegalArgumentException) {
        }
    }
    // endregion

    @SuppressLint("ClickableViewAccessibility") // 끌기와 누르기를 직접 구분한다. 접근성용 클릭도 아래에서 연결
    private fun attachTouch() {
        var touching = false
        view.setOnClickListener { onTap() }
        view.setOnLongClickListener {
            if (touching) startRemoveMode() else onLongPressMenu()
            true
        }
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        view.cancelLongPress()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        if (shown) windows.updateViewLayout(view, params)
                        if (removing) setOverTarget(isOverTarget(event.rawX, event.rawY))
                    }
                    dragging || removing
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    val drop = removing && event.actionMasked == MotionEvent.ACTION_UP && isOverTarget(event.rawX, event.rawY)
                    endRemoveMode()
                    when {
                        drop -> {
                            view.isPressed = false
                            onRemove()
                            true
                        }
                        dragging -> {
                            // ✕가 아닌 곳: 놓은 자리에 그대로 둔다 (화면 밖이면 안쪽으로)
                            view.isPressed = false
                            keepOnScreen()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    private fun startRemoveMode() {
        if (!shown || removing) return
        removing = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setOverTarget(false, force = true)
        target.visibility = View.VISIBLE
    }

    private fun endRemoveMode() {
        removing = false
        overTarget = false
        target.visibility = View.INVISIBLE
    }

    /** 손가락이 ✕ 원 근처(원보다 조금 넓게)에 있는지 */
    private fun isOverTarget(rawX: Float, rawY: Float): Boolean {
        if (target.visibility != View.VISIBLE || !targetX.isAttachedToWindow) return false
        val location = IntArray(2)
        targetX.getLocationOnScreen(location)
        val margin = dp(TARGET_MARGIN_DP)
        val rect = Rect(location[0] - margin, location[1] - margin, location[0] + targetX.width + margin, location[1] + targetX.height + margin)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun setOverTarget(value: Boolean, force: Boolean = false) {
        if (overTarget == value && !force) return
        overTarget = value
        targetCircle.setColor(if (value) COLOR_ACTIVE else COLOR_IDLE)
        targetCircle.setStroke(dp(3), Color.WHITE)
        val scale = if (value) ACTIVE_SCALE else 1f
        targetX.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        targetLabel.setText(if (value) R.string.floating_remove_release else R.string.floating_remove_hint)
        if (value && !force) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val SIZE_DP = 56
        const val PAUSED_ALPHA = 0.6f
        const val HINT_MS = 3_000L
        const val TARGET_DP = 96
        const val TARGET_BOTTOM_DP = 72
        const val TARGET_MARGIN_DP = 28
        const val ACTIVE_SCALE = 1.2f
        val COLOR_IDLE = Color.argb(0xCC, 0x30, 0x30, 0x30)
        val COLOR_ACTIVE = Color.argb(0xF0, 0xE5, 0x39, 0x35)
    }
}
