package com.mycarhub.phone.floating

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.mycarhub.MainActivity
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.phone.conversation.PhoneAssistantActivity
import com.mycarhub.phone.conversation.PhoneConversation
import com.mycarhub.wakeword.WakeWordManager
import com.mycarhub.wakeword.core.WakeWordModel

/**
 * 상시 호출어 듣기 포그라운드 서비스 (폰 테스트용 빌드 전용). 폰 빌드의 호출어 감지는 이 서비스 하나가 맡는다.
 *
 * 흐름: 호출어 대기 → 감지(감지 스레드가 마이크를 닫음) → 음성 대화(STT → 명령 실행 → TTS 대답) → 대화 끝 → 다시 대기.
 * - MyCarHub 메인 화면이 앞에 있으면: 음성 대화 화면([PhoneAssistantActivity])을 연다.
 * - 다른 앱·홈 화면을 쓰는 중이면: 그 앱 위에 음성비서 창([OverlayAssistantPanel])을 띄운다. 앱을 실행해도
 *   MyCarHub로 돌아오지 않고, 실행한 앱 위에서 계속 호출을 기다린다.
 * 대화하는 동안(비서가 말하는 동안 포함)은 감지를 멈춰 비서 목소리를 호출어로 듣지 않는다.
 *
 * Android 정책
 * - 마이크 포그라운드 서비스(type=microphone)는 MyCarHub 화면이 보이는 동안에만 시작한다 (Android 11+ while-in-use,
 *   Android 14+ 타입·권한 필수). 시스템이 서비스를 종료하면 백그라운드에서 다시 시작하지 않는다(START_NOT_STICKY):
 *   다시 시작해도 마이크가 무음만 주기 때문이다. MyCarHub를 다시 열면 다시 켜진다.
 * - 다른 앱 위의 창과 백그라운드에서의 앱 실행에는 '다른 앱 위에 표시' 허용이 필요하다. 없으면 MyCarHub 화면에서만 듣는다.
 * - 지속 알림에 [듣기 중지]를 둔다. 플로팅 버튼을 길게 눌러 화면 아래 ✕에 끌어 놓으면 버튼과 듣기를 함께 끈다
 *   (TalkBack 등 접근성 길게 누르기는 [버튼 숨기기](듣기는 계속)·[상시 듣기 종료] 선택).
 *   버튼을 숨겨도 지속 알림과 마이크 사용 표시는 그대로다 (Android 요구사항).
 *
 * 감지를 쉬는 경우: 메인 화면이 아닌 MyCarHub 화면(대화·호출어 등록·설정)이 열림, 음성비서 창이 열림, 화면 꺼짐·잠금,
 * 통화 중, 호출어 미등록, 마이크를 열 수 없음(잠시 뒤 다시 시도), 다른 앱 위 표시 권한 없이 백그라운드.
 * 오디오와 인식 내용은 저장하지 않는다.
 */
class FloatingVoiceService : Service() {

    /** 메인 화면에 보여줄 듣기 상태 */
    enum class Status { STOPPED, STARTING, LISTENING, SILENCED, ASSISTANT, SCREEN_OFF, IN_CALL, MIC_RETRY, NO_MODEL, NO_OVERLAY }

    private enum class Pause { OTHER_SCREEN, ASSISTANT, SCREEN_OFF, IN_CALL, NO_MODEL, MIC_RETRY, NO_OVERLAY }

    private val main = Handler(Looper.getMainLooper())
    private val pauses = mutableSetOf<Pause>()
    private lateinit var app: MyCarHubApplication
    private lateinit var settings: FloatingVoiceSettings
    private lateinit var conditions: DeviceConditions
    private lateinit var bubble: FloatingBubble
    private var listener: BackgroundWakeWordListener? = null
    private var listenerModel: WakeWordModel? = null
    private var panel: OverlayAssistantPanel? = null
    private var bubbleMenu: AlertDialog? = null
    private var listening = false
    private var silenced = false
    private var started = false

    private val retryMic = Runnable {
        pauses -= Pause.MIC_RETRY
        evaluate()
    }
    private val resume = Runnable { evaluate() }

    // 전체 화면 음성비서에서 연속 대화를 넘겨받기로 한 기한 (그 안에 화면이 닫히면 다른 앱 위 창으로 이어간다)
    private var continueUntil = 0L

    // 음성 대화 화면을 열었는데 열리지 않았으면(시작 실패) 다시 듣는다. 열렸으면 그 화면이 닫힐 때까지 OTHER_SCREEN으로 쉰다.
    private val screenOpenTimeout = Runnable {
        pauses -= Pause.ASSISTANT
        evaluate()
    }

    private val appScreenListener = AppForegroundTracker.Listener { onAppScreenChanged() }

    private val modelListener = object : WakeWordManager.Listener {
        override fun onModelChanged(model: WakeWordModel?) {
            setPause(Pause.NO_MODEL, model == null)
            // 재등록되면 새 모델로 다시 시작
            if (model != null && model != listenerModel) {
                listener?.stop()
                listener = null
                evaluate()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as MyCarHubApplication
        settings = FloatingVoiceSettings(this)
        conditions = DeviceConditions(this) { updateDeviceConditions() }
        bubble = FloatingBubble(
            this,
            onTap = { openAssistant(wakeWordName = null) },
            onRemove = ::removeByUser,
            onLongPressMenu = ::showBubbleMenu,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopByUser()
                return START_NOT_STICKY
            }
            ACTION_ASSIST -> {
                if (started) openAssistant(wakeWordName = null) else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONTINUE -> {
                if (!started) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 전체 화면 음성비서가 닫히면(OTHER_SCREEN이 풀리면) 다른 앱 위 창으로 연속 대화를 이어간다
                continueUntil = SystemClock.elapsedRealtime() + CONTINUE_WINDOW_MS
                if (Pause.OTHER_SCREEN !in pauses) scheduleContinue()
                return START_NOT_STICKY
            }
        }
        if (!started && !begin()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun begin(): Boolean {
        if (!settings.isReady) {
            Log.w(TAG, "not ready: model=${settings.hasWakeWord} mic=${settings.hasMicPermission}")
            return false
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: RuntimeException) {
            // Android 12+: 백그라운드에서 시작 불가 / Android 14+: 마이크 서비스 조건 미충족
            Log.w(TAG, "startForeground refused", e)
            return false
        }
        started = true
        isRunning = true
        conditions.start()
        AppForegroundTracker.addListener(appScreenListener)
        app.wakeWords.addListener(modelListener)
        pauses.clear()
        if (AppForegroundTracker.isOtherScreenOpen) pauses += Pause.OTHER_SCREEN
        if (!AppForegroundTracker.isAppScreenOpen && !settings.canDrawOverlays) pauses += Pause.NO_OVERLAY
        if (app.wakeWords.model == null) pauses += Pause.NO_MODEL
        updateBubble()
        updateDeviceConditions()
        evaluate()
        Log.i(TAG, "listening service started: overlay=${settings.canDrawOverlays} notify=${settings.hasNotificationPermission}")
        return true
    }

    // 앞에 있는 앱이 가로 전체 화면으로 바뀌는 등 화면 방향이 달라지면 버튼을 화면 안에 다시 둔다
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubble.isInitialized) main.post { bubble.keepOnScreen() }
    }

    override fun onDestroy() {
        main.removeCallbacks(retryMic)
        main.removeCallbacks(resume)
        main.removeCallbacks(screenOpenTimeout)
        main.removeCallbacks(continueConversation)
        panel?.close()
        panel = null
        bubbleMenu?.dismiss()
        bubbleMenu = null
        listener?.stop()
        listener = null
        bubble.hide()
        if (started) {
            conditions.stop()
            AppForegroundTracker.removeListener(appScreenListener)
            app.wakeWords.removeListener(modelListener)
        }
        started = false
        isRunning = false
        publish(Status.STOPPED)
        Log.i(TAG, "listening service stopped")
        super.onDestroy()
    }

    // region 감지 시작·중지

    private fun setPause(reason: Pause, active: Boolean) {
        val changed = if (active) pauses.add(reason) else pauses.remove(reason)
        if (changed) evaluate()
    }

    private fun onAppScreenChanged() {
        val appOpen = AppForegroundTracker.isAppScreenOpen
        // MyCarHub 화면이 열리면 그 화면이 대화를 맡으므로 떠 있는 음성비서 창은 닫는다
        if (appOpen) panel?.close()
        updateBubble()
        if (AppForegroundTracker.isOtherScreenOpen) {
            setPause(Pause.OTHER_SCREEN, true)
        } else if (pauses.remove(Pause.OTHER_SCREEN)) {
            if (SystemClock.elapsedRealtime() < continueUntil) {
                scheduleContinue()
            } else {
                // 음성 대화·등록 화면이 마이크를 놓을 시간을 준 뒤 다시 듣는다
                main.removeCallbacks(resume)
                main.postDelayed(resume, RESUME_DELAY_MS)
            }
        }
        setPause(Pause.NO_OVERLAY, !appOpen && !settings.canDrawOverlays)
        render()
    }

    /** 다른 앱을 쓰는 중에만 플로팅 버튼을 보인다 (MyCarHub 화면에서는 그 화면이 버튼 역할). 사용자가 숨겼으면 보이지 않는다. */
    private fun updateBubble() {
        val show = started && settings.showBubble && !AppForegroundTracker.isAppScreenOpen && settings.canDrawOverlays
        if (show) bubble.show() else bubble.hide()
    }

    /** 접근성(TalkBack 등) 길게 누르기: [버튼 숨기기] / [상시 듣기 종료] / 취소. 다른 앱 위에 뜨는 선택 창이다. */
    private fun showBubbleMenu() {
        bubbleMenu?.dismiss()
        val items = arrayOf(getString(R.string.floating_menu_hide), getString(R.string.floating_menu_stop))
        bubbleMenu = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.floating_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> hideBubbleByUser()
                    1 -> stopByUser()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                setOnDismissListener { if (bubbleMenu === this) bubbleMenu = null }
            }
        try {
            bubbleMenu?.show()
        } catch (e: RuntimeException) {
            // '다른 앱 위에 표시'가 막 꺼진 경우 등: 메뉴 없이 그대로 둔다
            Log.w(TAG, "bubble menu unavailable", e)
            bubbleMenu = null
        }
    }

    /** 버튼만 숨기고 호출어 듣기는 계속한다. 설정에 저장해 MyCarHub를 다시 열어도 숨긴 채로 둔다. */
    private fun hideBubbleByUser() {
        settings.showBubble = false
        updateBubble()
        Toast.makeText(this, R.string.floating_bubble_hidden, Toast.LENGTH_LONG).show()
    }

    private fun updateDeviceConditions() {
        val off = conditions.isScreenOffOrLocked
        val call = conditions.isInCall
        // 화면이 꺼지거나 통화가 시작되면 열려 있는 음성비서 창도 닫는다 (잠금 화면 위 실행·통화 중 마이크 점유 방지)
        if ((off || call) && panel != null) panel?.close()
        setPause(Pause.SCREEN_OFF, off)
        setPause(Pause.IN_CALL, call)
    }

    /** 쉬어야 할 이유가 없으면 감지를 켜고, 있으면 끈다. */
    private fun evaluate() {
        if (!started) return
        val model = app.wakeWords.model
        if (pauses.isEmpty() && listener == null && model != null) {
            listenerModel = model
            listener = BackgroundWakeWordListener(this, model, detectorCallbacks).also { it.start() }
        } else if (pauses.isNotEmpty() && listener != null) {
            listener?.stop()
            listener = null
            listening = false
            silenced = false
        }
        render()
    }

    private val detectorCallbacks = object : BackgroundWakeWordListener.Callbacks {
        override fun onListening() {
            listening = true
            render()
        }

        override fun onDetected() {
            // 감지 스레드는 이미 마이크를 닫고 끝났다
            listener = null
            listening = false
            // 알림음 대신 음성비서가 "네, 말씀하세요!" 라고 대답한다
            openAssistant(wakeWordName = app.wakeWords.model?.name)
        }

        override fun onSilenced(silenced: Boolean) {
            this@FloatingVoiceService.silenced = silenced
            render()
        }

        override fun onMicUnavailable() {
            listener = null
            listening = false
            Log.w(TAG, "microphone unavailable; retry in ${MIC_RETRY_MS}ms")
            pauses += Pause.MIC_RETRY
            main.postDelayed(retryMic, MIC_RETRY_MS)
            render()
        }
    }

    // endregion

    // region 음성 대화

    /**
     * 호출어 감지·플로팅 버튼·알림의 [음성비서] → 감지가 마이크를 놓은 뒤 대화를 시작한다.
     * 메인 화면이 앞에 있으면 음성 대화 화면, 다른 앱을 쓰는 중이면 그 위의 음성비서 창.
     */
    /** 음성 대화 화면이 마이크를 놓을 시간을 준 뒤 연속 대화를 다른 앱 위 창으로 이어간다. */
    private fun scheduleContinue() {
        continueUntil = 0L
        main.removeCallbacks(resume)
        main.removeCallbacks(continueConversation)
        main.postDelayed(continueConversation, RESUME_DELAY_MS)
    }

    private val continueConversation = Runnable {
        Log.i(TAG, "continuing conversation in overlay")
        openAssistant(app.wakeWords.model?.name, resumeContinuous = true)
    }

    private fun openAssistant(wakeWordName: String?, resumeContinuous: Boolean = false) {
        val blocked = panel != null || pauses.any { it == Pause.OTHER_SCREEN || it == Pause.SCREEN_OFF || it == Pause.IN_CALL }
        val mainScreen = AppForegroundTracker.resumedMain
        if (blocked || (mainScreen == null && !settings.canDrawOverlays)) {
            // 대화를 열 수 없으면 (감지 직후라면) 다시 듣는다
            evaluate()
            return
        }
        main.removeCallbacks(resume)
        main.removeCallbacks(screenOpenTimeout)
        pauses += Pause.ASSISTANT
        val running = listener
        listener = null
        listening = false
        render()
        val show = show@{
            if (!started || Pause.ASSISTANT !in pauses || panel != null) return@show
            val screen = AppForegroundTracker.resumedMain
            if (resumeContinuous && (screen != null || !settings.canDrawOverlays)) {
                // 이어받을 다른 앱 화면이 아님(쁘이 메인 화면으로 돌아옴) 또는 다른 앱 위 표시 불가: 평소처럼 호출어 대기
                pauses -= Pause.ASSISTANT
                evaluate()
            } else if (screen != null && !screen.isFinishing) {
                // 메인 화면에서는 기존처럼 대화 화면을 연다. 그 화면이 열리면 OTHER_SCREEN으로 쉰다.
                screen.startActivity(PhoneAssistantActivity.intent(screen, wakeWordName))
                main.postDelayed(screenOpenTimeout, SCREEN_OPEN_TIMEOUT_MS)
            } else if (settings.canDrawOverlays) {
                panel = OverlayAssistantPanel(this, wakeWordName, onClosed = ::onAssistantClosed, onState = ::onAssistantState).also { it.show(resumeContinuous) }
            } else {
                pauses -= Pause.ASSISTANT
                evaluate()
            }
        }
        if (running == null) show() else running.stop(show)
    }

    /** 음성비서 상태를 플로팅 버튼으로만 보여 준다: 진행 중이면 깜빡임, 실패로 끝나면 버튼 옆에 짧게 표시. */
    private fun onAssistantState(state: PhoneConversation.Snapshot) {
        val ended = state.phase == PhoneConversation.Phase.ENDED
        bubble.setActive(!ended)
        if (ended && state.end == PhoneConversation.End.ERROR) {
            state.assistant?.let { text -> bubble.showHint(text.substringBefore(". ").removeSuffix(".").take(HINT_MAX_CHARS)) }
        }
    }

    private fun onAssistantClosed() {
        panel = null
        bubble.setActive(false)
        pauses -= Pause.ASSISTANT
        // 음성 인식기가 마이크를 놓을 시간을 준 뒤 다시 호출어를 기다린다
        main.postDelayed(resume, RESUME_DELAY_MS)
        render()
    }

    // endregion

    private fun stopByUser() {
        settings.enabled = false
        Toast.makeText(this, R.string.floating_stopped, Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    /**
     * 플로팅 버튼을 ✕에 끌어 놓음: 버튼과 함께 백그라운드 호출어 듣기도 끈다. 호출어 녹음·모델·다른 설정은 그대로다.
     * MyCarHub 메인 화면의 [호출어 듣기 켜기]를 누르면 듣기와 버튼이 함께 다시 켜진다.
     */
    private fun removeByUser() {
        bubble.hide()
        settings.enabled = false
        Toast.makeText(this, R.string.floating_removed, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    // region 상태 표시 (알림·플로팅 버튼·메인 화면)

    private fun status(): Status = when {
        Pause.NO_MODEL in pauses -> Status.NO_MODEL
        Pause.ASSISTANT in pauses || Pause.OTHER_SCREEN in pauses -> Status.ASSISTANT
        Pause.IN_CALL in pauses -> Status.IN_CALL
        Pause.SCREEN_OFF in pauses -> Status.SCREEN_OFF
        Pause.MIC_RETRY in pauses -> Status.MIC_RETRY
        Pause.NO_OVERLAY in pauses -> Status.NO_OVERLAY
        silenced -> Status.SILENCED
        listening -> Status.LISTENING
        else -> Status.STARTING
    }

    private fun render() {
        if (!started) return
        val current = status()
        bubble.setListening(current == Status.LISTENING)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        publish(current)
    }

    private fun statusText(): String = when (status()) {
        Status.NO_MODEL -> getString(R.string.floating_status_no_model)
        Status.ASSISTANT -> getString(R.string.floating_status_assistant)
        Status.IN_CALL -> getString(R.string.floating_status_in_call)
        Status.SCREEN_OFF -> getString(R.string.floating_status_screen_off)
        Status.MIC_RETRY -> getString(R.string.floating_status_mic_unavailable)
        Status.NO_OVERLAY -> getString(R.string.floating_status_no_overlay)
        Status.SILENCED -> getString(R.string.floating_status_silenced)
        Status.LISTENING -> getString(R.string.floating_status_listening, app.wakeWords.model?.name.orEmpty())
        Status.STARTING, Status.STOPPED -> getString(R.string.floating_status_starting)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.floating_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.floating_channel_description)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatingVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val assist = PendingIntent.getService(
            this, 2, Intent(this, FloatingVoiceService::class.java).setAction(ACTION_ASSIST), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(statusText())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.floating_action_stop), stop).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.floating_action_assist), assist).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    // endregion

    companion object {
        /** 서비스가 실행 중인지 (설정 화면 표시용) */
        @Volatile
        var isRunning = false
            private set

        /** 지금 듣기 상태 (메인 스레드에서 읽는다) */
        var status = Status.STOPPED
            private set

        private val statusListeners = mutableSetOf<(Status) -> Unit>()

        fun addStatusListener(listener: (Status) -> Unit) {
            statusListeners += listener
        }

        fun removeStatusListener(listener: (Status) -> Unit) {
            statusListeners -= listener
        }

        private fun publish(next: Status) {
            if (status == next) return
            status = next
            statusListeners.toList().forEach { it(next) }
        }

        private const val TAG = "FloatingVoice"
        private const val CHANNEL_ID = "floating_voice"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.mycarhub.phone.action.STOP_LISTENING"
        private const val ACTION_ASSIST = "com.mycarhub.phone.action.ASSIST"
        private const val MIC_RETRY_MS = 10_000L
        private const val RESUME_DELAY_MS = 500L
        private const val HINT_MAX_CHARS = 30
        private const val SCREEN_OPEN_TIMEOUT_MS = 3_000L
        private const val ACTION_CONTINUE = "com.mycarhub.phone.action.CONTINUE_CONVERSATION"
        private const val CONTINUE_WINDOW_MS = 5_000L

        /**
         * 전체 화면 음성비서가 연속 대화 중 앱을 열고 닫힐 때 부른다: 화면이 닫히면 다른 앱 위 창이 대화를 이어받는다.
         * 상시 듣기 서비스가 꺼져 있거나 '다른 앱 위에 표시'가 없으면 이어받지 않는다 (기존처럼 대화가 끝남).
         */
        fun continueConversation(context: Context): Boolean {
            if (!isRunning || !Settings.canDrawOverlays(context)) return false
            return try {
                context.startService(Intent(context, FloatingVoiceService::class.java).setAction(ACTION_CONTINUE))
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot hand off conversation", e)
                false
            }
        }

        /**
         * MyCarHub 화면이 보이는 동안에만 호출한다 (마이크 포그라운드 서비스 시작 조건).
         * 호출어와 마이크 권한이 있을 때만 시작한다: startForegroundService 뒤 startForeground를 못 부르면
         * 시스템이 앱을 종료하기 때문이다.
         */
        fun start(context: Context): Boolean {
            if (!FloatingVoiceSettings(context).isReady) return false
            return try {
                context.startForegroundService(Intent(context, FloatingVoiceService::class.java))
                true
            } catch (e: RuntimeException) {
                // Android 12+: 백그라운드에서 포그라운드 서비스 시작 불가
                Log.w(TAG, "cannot start listening service", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingVoiceService::class.java))
        }
    }
}
