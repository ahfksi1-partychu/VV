package com.mycarhub.phone.conversation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.phone.PermissionGate
import com.mycarhub.phone.floating.FloatingVoiceService
import com.mycarhub.phone.conversation.PhoneConversation.Action
import com.mycarhub.phone.conversation.PhoneConversation.End
import com.mycarhub.phone.conversation.PhoneConversation.Phase

/**
 * 폰 빌드의 음성비서 화면: 열리자마자 "네, 말씀하세요!" 라고 대답하고 대화를 이어간다 ([PhoneConversation]).
 *
 * 호출어 감지와 메인 화면의 [직접 음성비서 실행]으로 열린다. 대화가 끝나면 잠시 뒤 스스로 닫혀 호출어 대기로 돌아가고,
 * 앱을 실행했으면 바로 닫힌다. 다른 앱으로 바뀌어도 대화는 끝날 때까지 이어간다 (음성 안내 유지).
 * 메시지 내용이 보이는 동안에는 화면 캡처·최근 앱 미리보기를 막는다.
 */
class PhoneAssistantActivity : Activity() {

    private lateinit var conversation: PhoneConversation
    private lateinit var view: ConversationView
    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }
    private var secure = false

    // 마이크 권한을 이번 화면에서 이미 요청했는지 (다시 묻지 않음 상태 구분)
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_assistant)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        conversation = PhoneConversation(this, intent.getStringExtra(EXTRA_WAKE_WORD), ::render, handOffOnLeave = true)
        view = ConversationView(findViewById(R.id.conversationRoot), { conversation }, onAction = ::onAction, onStop = ::onStopPressed)
        render(conversation.state)
        view.start()
        conversation.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 이미 열려 있는데 다시 불림: 처음부터 다시 대답한다
        main.removeCallbacks(autoClose)
        view.start()
        conversation.start()
    }

    override fun onDestroy() {
        main.removeCallbacks(autoClose)
        view.release()
        conversation.release()
        super.onDestroy()
    }

    private fun render(state: PhoneConversation.Snapshot) {
        view.render(state)
        setSecure(state.showsMessage)
        main.removeCallbacks(autoClose)
        if (state.phase != Phase.ENDED) return
        when {
            state.end == End.LAUNCHED -> {
                // 연속 대화 중 앱을 열었거나 홈으로 감: 다른 앱 위 창이 이어서 듣는다
                if (state.continuous) FloatingVoiceService.continueConversation(this)
                finish()
            }
            // 사용자가 화면에서 해야 할 일이 있으면 닫지 않는다
            state.action == Action.NONE -> main.postDelayed(autoClose, AUTO_CLOSE_MS)
        }
    }

    private fun onStopPressed() {
        if (conversation.state.phase == Phase.ENDED) finish() else conversation.stop()
    }

    private fun onAction(action: Action) {
        val app = application as MyCarHubApplication
        when (action) {
            Action.MIC_PERMISSION -> {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) && asked) {
                    // 다시 묻지 않음 상태: 앱 정보 화면에서 허용
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } else {
                    asked = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                }
            }
            // 사용자가 직접 누름: 허용 화면을 띄우고, 허용하면 다시 명령을 듣는다
            Action.NOTIFICATION_ACCESS -> PermissionGate.request(this, PermissionGate.Kind.NOTIFICATION_ACCESS, force = true) { granted ->
                if (granted && !isFinishing) conversation.restart()
            }
            Action.STT_CONSENT -> AlertDialog.Builder(this)
                .setMessage(R.string.conv_system_stt_message)
                .setPositiveButton(R.string.phone_allow) { _, _ ->
                    app.sttSettings.allowSystemRecognizer = true
                    conversation.restart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            Action.NONE -> finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) conversation.restart()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 설정 화면이 없는 기기: 버튼만 남긴다
        }
    }

    private fun setSecure(value: Boolean) {
        if (value == secure) return
        secure = value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        private const val EXTRA_WAKE_WORD = "com.mycarhub.phone.extra.WAKE_WORD"
        private const val REQUEST_MIC = 41
        private const val AUTO_CLOSE_MS = 3_000L

        fun intent(context: Context, wakeWordName: String?): Intent =
            Intent(context, PhoneAssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_WAKE_WORD, wakeWordName)
    }
}
