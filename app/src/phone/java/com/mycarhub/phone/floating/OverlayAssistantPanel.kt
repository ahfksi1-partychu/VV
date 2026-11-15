package com.mycarhub.phone.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.mycarhub.R
import com.mycarhub.phone.PermissionGate
import com.mycarhub.phone.conversation.ConversationView
import com.mycarhub.phone.conversation.PhoneAssistantActivity
import com.mycarhub.phone.conversation.PhoneConversation
import com.mycarhub.phone.conversation.PhoneConversation.Action
import com.mycarhub.phone.conversation.PhoneConversation.End
import com.mycarhub.phone.conversation.PhoneConversation.Phase

/**
 * 다른 앱 위에 뜨는 음성비서 창 (폰 테스트용 빌드 전용).
 *
 * 앱 화면과 같은 음성 대화([PhoneConversation])를 쓰고, 이 클래스는 창 표시만 맡는다. 다른 앱을 쓰는 중에도
 * 비서가 음성으로 대답하고 결과를 말한다. 권한이 필요하면 권한 창·시스템 설정을 바로 띄우고 그동안 창을 숨긴다
 * ([PermissionGate]). 메시지 내용이 보이는 동안에는 창을 화면 캡처·녹화에서 제외한다(FLAG_SECURE).
 * 닫으면 대화와 함께 내용을 지운다.
 */
class OverlayAssistantPanel(
    context: Context,
    private val wakeWordName: String?,
    private val onClosed: () -> Unit,
    /** 대화 상태 (플로팅 버튼 표시용). 큰 창은 띄우지 않고 버튼으로만 상태를 보여 준다. */
    private val onState: (PhoneConversation.Snapshot) -> Unit = {},
) {
    private val context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { close() }

    private val root: View = LayoutInflater.from(this.context).inflate(R.layout.view_conversation, null).apply {
        setBackgroundResource(R.drawable.floating_panel_background)
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 글자 입력이 없으므로 포커스를 가져가지 않는다: 창 밖 터치는 아래 앱으로 간다
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        y = dp(24)
    }

    private var shown = false
    private var secure = false

    private val conversation = PhoneConversation(context, wakeWordName, ::render)
    private val view = ConversationView(root, { conversation }, onAction = ::onAction, onStop = ::onStopPressed)

    val isShowing: Boolean get() = shown

    /** 창을 띄우고 "네, 말씀하세요!" 라고 대답한 뒤 명령을 듣는다. 호출 전에 호출어 감지가 마이크를 놓아야 한다. */
    fun show(resumeContinuous: Boolean = false) {
        if (shown) return
        // 큰 대화창은 숨긴 채로 둔다 (유튜브·지도 화면을 가리지 않음). 권한 버튼이 필요할 때만 보인다.
        root.visibility = View.GONE
        windows.addView(root, params)
        shown = true
        view.start()
        // 전체 화면 음성비서가 앱을 연 뒤 이어받음: 인사 없이 연속 대화를 이어간다
        if (resumeContinuous) conversation.resumeContinuous() else conversation.startListeningNow()
    }

    /** 닫기: 인식·말하기를 멈추고, 읽은 메시지·답장 초안을 지운 뒤 [onClosed]. */
    fun close() {
        if (!shown) return
        shown = false
        main.removeCallbacks(autoClose)
        view.release()
        conversation.release()
        try {
            windows.removeView(root)
        } catch (e: IllegalArgumentException) {
            // 이미 제거됨
        }
        onClosed()
    }

    private fun onStopPressed() {
        if (conversation.state.phase == Phase.ENDED) close() else conversation.stop()
    }

    /** 화면의 권한 버튼: 사용자가 직접 눌렀으므로 전에 거부했어도 허용 화면을 다시 띄우고, 허용하면 다시 듣는다. */
    private fun onAction(action: Action) {
        val kind = when (action) {
            Action.MIC_PERMISSION -> PermissionGate.Kind.MICROPHONE
            Action.NOTIFICATION_ACCESS -> PermissionGate.Kind.NOTIFICATION_ACCESS
            // 시스템 음성 인식 사용 동의는 대화상자가 필요해 앱의 음성비서 화면에서 묻는다
            Action.STT_CONSENT -> return openAssistantScreen()
            Action.NONE -> return close()
        }
        root.visibility = View.GONE
        PermissionGate.request(context, kind, force = true) { granted ->
            if (!shown) return@request
            root.visibility = View.VISIBLE
            if (granted) conversation.restart()
        }
    }

    private fun openAssistantScreen() {
        context.startActivity(PhoneAssistantActivity.intent(context, wakeWordName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        close()
    }

    private fun render(state: PhoneConversation.Snapshot) {
        if (!shown) return
        // 큰 창은 권한 허용 버튼이 필요할 때만 보인다. 나머지 상태는 플로팅 버튼으로만 표시한다.
        root.visibility = if (state.phase != Phase.PERMISSION && state.action != Action.NONE) View.VISIBLE else View.GONE
        onState(state)
        view.render(state)
        setSecure(state.showsMessage)
        main.removeCallbacks(autoClose)
        if (state.phase != Phase.ENDED) return
        when {
            state.end == End.LAUNCHED -> close()
            state.action == Action.NONE -> close()
        }
    }

    /** 메시지 내용이 보일 때만 화면 캡처·녹화에서 제외한다. */
    private fun setSecure(value: Boolean) {
        if (secure == value || !shown) return
        secure = value
        params.flags = if (value) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        windows.updateViewLayout(root, params)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTO_CLOSE_MS = 3_000L
    }
}
