package com.mycarhub.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.apps.system.CarRestrictionMonitor
import com.mycarhub.assistant.AppCommandResolver
import com.mycarhub.assistant.AssistantPhrases
import com.mycarhub.assistant.AssistantState
import com.mycarhub.assistant.Failure
import com.mycarhub.assistant.ListenPurpose
import com.mycarhub.assistant.SpeakPurpose
import com.mycarhub.assistant.VoiceAssistantSession
import com.mycarhub.assistant.core.IncomingMessage
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.speech.Speaker
import com.mycarhub.assistant.stt.SpeechToText
import com.mycarhub.assistant.stt.SttSettings
import com.mycarhub.drive.DriveAssistant
import com.mycarhub.platform.Platform
import com.mycarhub.wakeword.audio.MicrophonePcmSource

/**
 * MyCarHub 음성비서 화면: 듣기 → 처리 → 완료/실패, 메시지 읽기 → 답장 확인 → 전송.
 *
 * 호출어 감지나 메인 화면의 '음성 명령' 버튼으로 열린다. 주행 중에도 열리는 화면이라(distractionOptimized)
 * 글자 입력이 없고, 권한 요청·설정 이동·시스템 음성 인식 동의처럼 읽고 판단할 것이 많은 동작은 정차 중에만 허용한다.
 *
 * 메시지 내용이 보이는 화면이라 최근 앱 목록 미리보기에 남지 않게 한다. 화면을 닫으면 읽은 메시지도 지운다.
 */
class AssistantActivity : Activity() {

    private lateinit var session: VoiceAssistantSession
    private lateinit var restrictions: CarRestrictionMonitor
    private lateinit var sttSettings: SttSettings
    private lateinit var messages: MessageAccess

    private lateinit var stateText: TextView
    private lateinit var heardText: TextView
    private lateinit var detailText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var busy: ProgressBar
    private lateinit var choices: LinearLayout
    private lateinit var doneSpeakingButton: Button
    private lateinit var skipButton: Button
    private lateinit var sendButton: Button
    private lateinit var dontSendButton: Button
    private lateinit var notificationAccessButton: Button
    private lateinit var retryButton: Button
    private lateinit var grantButton: Button
    private lateinit var settingsButton: Button
    private lateinit var allowSystemSttButton: Button

    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }
    private var wakeWordName: String? = null
    private var dialog: AlertDialog? = null

    /** 권한 요청을 한 번 거절한 뒤 다시 묻지 않음 상태인지 (설정 화면 안내로 바꾼다) */
    private var permissionBlocked = false

    private val restrictionListener = CarRestrictionMonitor.Listener {
        if (it) dialog?.takeIf { d -> d.isShowing }?.dismiss()
        render(session.state)
    }

    private val phrases = object : AssistantPhrases {
        override fun readMessage(message: IncomingMessage): String =
            if (message.conversation != null) {
                getString(R.string.tts_read_message_group, message.appLabel, message.sender, message.text, message.conversation)
            } else {
                getString(R.string.tts_read_message, message.appLabel, message.sender, message.text)
            }

        override fun askReplyBody(message: IncomingMessage) = getString(R.string.tts_ask_reply_body, message.sender)
        override fun confirmReply(message: IncomingMessage, body: String) =
            getString(R.string.tts_confirm_reply, message.sender, body)

        override fun confirmAgain(body: String) = getString(R.string.tts_confirm_again, body)
        override fun replySent(message: IncomingMessage) = getString(R.string.tts_reply_sent, message.sender)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)
        protectFromRecents()

        val app = application as MyCarHubApplication
        restrictions = app.restrictions
        sttSettings = app.sttSettings
        messages = app.messages
        wakeWordName = intent.getStringExtra(EXTRA_WAKE_WORD)

        stateText = findViewById(R.id.assistantState)
        heardText = findViewById(R.id.assistantHeard)
        detailText = findViewById(R.id.assistantDetail)
        levelBar = findViewById(R.id.assistantLevel)
        busy = findViewById(R.id.assistantBusy)
        choices = findViewById(R.id.assistantChoices)
        doneSpeakingButton = findViewById(R.id.assistantDoneSpeaking)
        skipButton = findViewById(R.id.assistantSkip)
        sendButton = findViewById(R.id.assistantSend)
        dontSendButton = findViewById(R.id.assistantDontSend)
        notificationAccessButton = findViewById(R.id.assistantNotificationAccess)
        retryButton = findViewById(R.id.assistantRetry)
        grantButton = findViewById(R.id.assistantGrant)
        settingsButton = findViewById(R.id.assistantSettings)
        allowSystemSttButton = findViewById(R.id.assistantAllowSystemStt)

        session = VoiceAssistantSession(
            stt = SpeechToText(this, sttSettings),
            speaker = Speaker(this),
            messages = messages,
            phrases = phrases,
            resolver = AppCommandResolver(app.catalog),
            favorites = app.favoriteApps,
            launcher = app.launcher,
            wakeWords = app.wakeWords,
            hasMicPermission = { MicrophonePcmSource.hasPermission(this) },
            wakeWordName = wakeWordName,
            onState = ::render,
            drive = DriveAssistant(this, Platform.voiceTodoSource),
        )

        doneSpeakingButton.setOnClickListener { session.finishSpeaking() }
        skipButton.setOnClickListener { session.skipSpeaking() }
        sendButton.setOnClickListener { session.confirmSend() }
        dontSendButton.setOnClickListener { session.cancelReply() }
        notificationAccessButton.setOnClickListener { confirmNotificationAccess() }
        retryButton.setOnClickListener { session.retry() }
        grantButton.setOnClickListener { requestMicPermission() }
        settingsButton.setOnClickListener { openAppSettings() }
        allowSystemSttButton.setOnClickListener { confirmSystemRecognizer() }
        findViewById<Button>(R.id.assistantClose).setOnClickListener { finish() }

        restrictions.addListener(restrictionListener)
        session.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 이미 열려 있는데 다시 호출됨 → 처음부터 다시 듣는다
        setIntent(intent)
        session.start()
    }

    override fun onStop() {
        super.onStop()
        // 다른 앱이 앞으로 오면(실행한 앱·설정 화면) 마이크를 놓고 닫는다. 권한 대화상자는 onStop을 부르지 않는다.
        if (!isChangingConfigurations) {
            session.cancel()
            finish()
        }
    }

    override fun onDestroy() {
        restrictions.removeListener(restrictionListener)
        main.removeCallbacks(autoClose)
        dialog?.dismiss()
        // 읽은 메시지·답장 초안을 메모리에서 지운다
        session.release()
        super.onDestroy()
    }

    /** 메시지 내용이 최근 앱 미리보기(디스크에 저장될 수 있음)에 남지 않게 한다. */
    private fun protectFromRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // region 상태 표시

    private fun render(state: AssistantState) {
        main.removeCallbacks(autoClose)
        choices.removeAllViews()
        levelBar.visibility = View.GONE
        busy.visibility = View.GONE
        heardText.text = ""
        detailText.text = ""
        val buttons = mutableSetOf<Button>()

        when (state) {
            AssistantState.Preparing -> {
                stateText.setText(R.string.assistant_state_preparing)
                busy.visibility = View.VISIBLE
                detailText.text = wakeWordName?.let { getString(R.string.assistant_wake_heard, it) }
            }
            is AssistantState.Listening -> renderListening(state, buttons)
            is AssistantState.Processing -> {
                stateText.setText(R.string.assistant_state_processing)
                busy.visibility = View.VISIBLE
                heardText.text = getString(R.string.assistant_heard, state.heard)
            }
            is AssistantState.Speaking -> {
                stateText.setText(
                    when (state.purpose) {
                        SpeakPurpose.READ_MESSAGE -> R.string.assistant_state_reading
                        SpeakPurpose.ASK_REPLY_BODY -> R.string.assistant_state_ask_body
                        SpeakPurpose.CONFIRM_REPLY -> R.string.assistant_state_confirm
                    },
                )
                showMessage(state.message, state.draft)
                if (!state.voice) detailText.append("\n" + getString(R.string.assistant_text_only))
                buttons += skipButton
                if (state.purpose == SpeakPurpose.CONFIRM_REPLY) buttons += setOf(sendButton, dontSendButton)
            }
            is AssistantState.Done -> {
                stateText.text = getString(R.string.assistant_state_done, state.app.displayName)
                heardText.text = getString(R.string.assistant_heard, state.heard)
                main.postDelayed(autoClose, DONE_CLOSE_MS)
            }
            is AssistantState.Answer -> {
                stateText.text = state.text
                heardText.text = getString(R.string.assistant_heard, state.heard)
                main.postDelayed(autoClose, ANSWER_CLOSE_MS)
            }
            is AssistantState.Sent -> {
                stateText.setText(R.string.assistant_state_sent)
                heardText.text = senderLine(state.message)
                detailText.text = getString(R.string.assistant_draft, state.body)
                main.postDelayed(autoClose, SENT_CLOSE_MS)
            }
            is AssistantState.Choose -> {
                stateText.setText(R.string.assistant_state_choose)
                heardText.text = getString(R.string.assistant_heard, state.heard)
                detailText.setText(R.string.assistant_choose_hint)
                for (app in state.apps) {
                    choices.addView(choiceButton(app.displayName) { session.choose(app) })
                }
                buttons += retryButton
                main.postDelayed(autoClose, IDLE_CLOSE_MS)
            }
            is AssistantState.Failed -> {
                stateText.setText(R.string.assistant_state_failed)
                heardText.text = state.heard?.let { getString(R.string.assistant_heard, it) }
                detailText.text = failureMessage(state)
                buttons += failureActions(state.reason)
                main.postDelayed(autoClose, IDLE_CLOSE_MS)
            }
            AssistantState.Cancelled -> return closeWith(R.string.assistant_cancelled)
            AssistantState.ReplyCancelled -> return closeWith(R.string.assistant_reply_cancelled)
            AssistantState.Finished -> {
                finish()
                return
            }
        }
        for (button in listOf(
            doneSpeakingButton, skipButton, sendButton, dontSendButton, notificationAccessButton,
            retryButton, grantButton, settingsButton, allowSystemSttButton,
        )) {
            button.visibility = if (button in buttons) View.VISIBLE else View.GONE
        }
    }

    private fun renderListening(state: AssistantState.Listening, buttons: MutableSet<Button>) {
        levelBar.visibility = View.VISIBLE
        levelBar.progress = state.level
        if (state.speaking) buttons += doneSpeakingButton
        when (state.purpose) {
            ListenPurpose.COMMAND -> {
                stateText.setText(if (state.speaking) R.string.assistant_state_hearing else R.string.assistant_state_listening)
                heardText.text = state.partial?.let { getString(R.string.assistant_heard, it) }
                if (state.partial == null) {
                    val example = if (messages.status == MessageAccess.Status.UNSUPPORTED) {
                        R.string.assistant_example_apps
                    } else {
                        R.string.assistant_example
                    }
                    detailText.setText(example)
                }
            }
            ListenPurpose.AFTER_READ -> {
                stateText.setText(R.string.assistant_state_after_read)
                state.message?.let { showMessage(it, null) }
                detailText.append(
                    "\n" + getString(
                        if (state.message?.canReply == true) R.string.assistant_after_read_hint else R.string.assistant_after_read_no_reply,
                    ),
                )
            }
            ListenPurpose.REPLY_BODY -> {
                stateText.setText(R.string.assistant_state_ask_body)
                heardText.text = state.message?.let(::senderLine)
                detailText.text = state.partial?.let { getString(R.string.assistant_heard, it) }
            }
            ListenPurpose.CONFIRM -> {
                stateText.setText(R.string.assistant_state_confirm)
                heardText.text = state.message?.let(::senderLine)
                detailText.text = getString(R.string.assistant_draft, state.draft.orEmpty()) +
                    "\n" + getString(R.string.assistant_confirm_hint)
                buttons += setOf(sendButton, dontSendButton)
            }
        }
    }

    /** 보낸 사람 줄 + 내용 (답장 초안이 있으면 초안). */
    private fun showMessage(message: IncomingMessage, draft: String?) {
        heardText.text = senderLine(message)
        detailText.text = if (draft != null) getString(R.string.assistant_draft, draft) else message.text
    }

    private fun senderLine(message: IncomingMessage): String =
        if (message.conversation != null) {
            getString(R.string.assistant_message_from_group, message.appLabel, message.sender, message.conversation)
        } else {
            getString(R.string.assistant_message_from, message.appLabel, message.sender)
        }

    private fun closeWith(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun failureMessage(state: AssistantState.Failed): String = when (state.reason) {
        Failure.NO_PERMISSION -> getString(if (permissionBlocked) R.string.fail_permission_blocked else R.string.fail_no_permission)
        Failure.STT_NEEDS_CONSENT -> getString(R.string.fail_stt_needs_consent)
        Failure.STT_UNAVAILABLE -> getString(R.string.fail_stt_unavailable)
        Failure.STT_LANGUAGE_UNAVAILABLE -> getString(R.string.fail_stt_language)
        Failure.STT_NETWORK -> getString(R.string.fail_stt_network)
        Failure.STT_ERROR -> getString(R.string.fail_stt_error)
        Failure.MIC_BUSY -> getString(R.string.fail_mic_busy)
        Failure.NO_SPEECH -> getString(R.string.fail_no_speech)
        Failure.NOT_UNDERSTOOD -> getString(R.string.fail_not_understood)
        Failure.UNKNOWN_COMMAND -> getString(R.string.fail_unknown_command)
        Failure.UNSUPPORTED -> getString(R.string.fail_unsupported)
        Failure.APP_NOT_FOUND -> getString(R.string.fail_app_not_found, state.subject)
        Failure.NOT_INSTALLED -> getString(R.string.launch_not_installed, state.subject)
        Failure.DISABLED -> getString(R.string.launch_disabled, state.subject)
        Failure.BLOCKED_WHILE_DRIVING -> getString(R.string.launch_blocked_driving, state.subject)
        Failure.LAUNCH_FAILED -> getString(R.string.fail_launch, state.subject)
        Failure.MESSAGES_UNSUPPORTED -> getString(R.string.fail_messages_unsupported)
        Failure.NOTIFICATION_ACCESS -> getString(R.string.fail_notification_access)
        Failure.NOTIFICATION_NOT_CONNECTED -> getString(R.string.fail_notification_not_connected)
        Failure.NO_MESSAGES -> getString(R.string.fail_no_messages)
        Failure.NO_MESSAGES_FOR_APP -> getString(R.string.fail_no_messages_for_app, state.subject)
        Failure.REPLY_WITHOUT_MESSAGE -> getString(R.string.fail_reply_without_message)
        Failure.REPLY_NOT_SUPPORTED -> getString(R.string.fail_reply_not_supported, state.subject)
        Failure.MESSAGE_GONE -> getString(R.string.fail_message_gone)
        Failure.SEND_FAILED -> getString(R.string.fail_send_failed)
    }

    /** 실패 이유별로 보여줄 버튼. 정차 중에만 가능한 동작은 주행 중이면 숨기고 안내 문구를 덧붙인다. */
    private fun failureActions(reason: Failure): Set<Button> {
        val driving = restrictions.isDrivingRestricted
        return when (reason) {
            Failure.NO_PERMISSION -> when {
                driving -> setOf<Button>().also { appendDrivingNotice() }
                permissionBlocked -> setOf(settingsButton)
                else -> setOf(grantButton)
            }
            Failure.STT_NEEDS_CONSENT ->
                if (driving) setOf<Button>().also { appendDrivingNotice() } else setOf(allowSystemSttButton)
            Failure.NOTIFICATION_ACCESS ->
                if (driving) setOf<Button>().also { appendDrivingNotice() } else setOf(notificationAccessButton)
            Failure.STT_UNAVAILABLE, Failure.STT_LANGUAGE_UNAVAILABLE, Failure.MESSAGES_UNSUPPORTED,
            Failure.MESSAGE_GONE, Failure.REPLY_NOT_SUPPORTED,
            -> emptySet()
            else -> setOf(retryButton)
        }
    }

    private fun appendDrivingNotice() {
        detailText.append("\n" + getString(R.string.assistant_blocked_driving))
    }

    private fun choiceButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = CHOICE_TEXT_SP
        minWidth = resources.getDimensionPixelSize(R.dimen.touch_target) * 3
        minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
        setOnClickListener { onClick() }
    }

    // endregion

    // region 권한·설정 (정차 중에만)

    private fun requestMicPermission() {
        if (blockIfDriving()) return
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionBlocked = false
            session.start()
        } else {
            // 거절 후 '다시 묻지 않음'이면 시스템이 더 이상 대화상자를 띄우지 않으므로 설정 화면으로 안내
            permissionBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            render(session.state)
        }
    }

    private fun openAppSettings() {
        if (blockIfDriving()) return
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun confirmSystemRecognizer() {
        if (blockIfDriving()) return
        main.removeCallbacks(autoClose)
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.assistant_system_stt_title)
            .setMessage(R.string.assistant_system_stt_message)
            .setPositiveButton(R.string.assistant_system_stt_allow) { _, _ ->
                sttSettings.allowSystemRecognizer = true
                session.start()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> render(session.state) }
            .show()
    }

    /** 알림 접근을 허용하기 전에 무엇을 읽고 어떻게 다루는지 안내한 뒤 설정 화면을 연다. */
    private fun confirmNotificationAccess() {
        if (blockIfDriving()) return
        val settings = messages.accessSettingsIntent() ?: return
        main.removeCallbacks(autoClose)
        dialog = NotificationAccessDialog.show(this, settings) { render(session.state) }
    }

    private fun blockIfDriving(): Boolean {
        if (!restrictions.isDrivingRestricted) return false
        Toast.makeText(this, R.string.assistant_blocked_driving, Toast.LENGTH_SHORT).show()
        render(session.state)
        return true
    }

    // endregion

    companion object {
        /** 호출어로 열렸을 때 등록된 호출어 이름 (없으면 버튼으로 연 경우) */
        const val EXTRA_WAKE_WORD = "com.mycarhub.extra.WAKE_WORD"

        private const val REQUEST_MIC = 30
        private const val DONE_CLOSE_MS = 1_500L
        private const val SENT_CLOSE_MS = 3_000L

        /** Drive To-Do 대답 (주차 위치 등)을 읽을 시간 */
        private const val ANSWER_CLOSE_MS = 10_000L

        // 실패·선택 화면이 주행 중 계속 떠 있지 않도록 자동으로 닫는다
        private const val IDLE_CLOSE_MS = 15_000L
        private const val CHOICE_TEXT_SP = 26f

        fun intent(context: Context, wakeWordName: String?): Intent =
            Intent(context, AssistantActivity::class.java)
                .putExtra(EXTRA_WAKE_WORD, wakeWordName)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** 알림 접근 안내 대화상자 (음성비서 화면과 메인 화면에서 같이 쓴다). */
object NotificationAccessDialog {
    fun show(activity: Activity, settings: Intent, onCancel: () -> Unit): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle(R.string.notification_access_title)
            .setMessage(activity.getString(R.string.notification_access_message, activity.getString(R.string.app_name)))
            .setPositiveButton(R.string.notification_access_open) { _, _ ->
                try {
                    activity.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    activity.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .show()
}
