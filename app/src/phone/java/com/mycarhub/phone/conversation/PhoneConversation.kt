package com.mycarhub.phone.conversation

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mycarhub.BuildConfig
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchResult
import com.mycarhub.assistant.AppCommandResolver
import com.mycarhub.assistant.core.AppCategory
import com.mycarhub.assistant.core.AppEntry
import com.mycarhub.assistant.core.AppMatch
import com.mycarhub.assistant.core.AppMatcher
import com.mycarhub.assistant.core.CommandParser
import com.mycarhub.assistant.core.Confirmation
import com.mycarhub.assistant.core.IncomingMessage
import com.mycarhub.assistant.core.MessageSelection
import com.mycarhub.assistant.core.MessageSelector
import com.mycarhub.assistant.core.VoiceCommand
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.phone.AppHit
import com.mycarhub.assistant.phone.AppNameMatcher
import com.mycarhub.assistant.phone.ClarifyAnswer
import com.mycarhub.assistant.phone.KoreanNameMatcher
import com.mycarhub.assistant.phone.NameMatch
import com.mycarhub.assistant.phone.PersonCommand
import com.mycarhub.assistant.phone.PersonCommandParser
import com.mycarhub.assistant.phone.ReadTarget
import com.mycarhub.assistant.phone.TargetClassifier
import com.mycarhub.assistant.stt.SpeechToText
import com.mycarhub.drive.DriveAssistant
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.platform.Platform
import com.mycarhub.phone.ContactNames
import com.mycarhub.phone.KnownNames
import com.mycarhub.phone.NotificationMessageAccess
import com.mycarhub.phone.PermissionGate
import com.mycarhub.assistant.core.ReminderDraft
import com.mycarhub.assistant.core.ReminderParser
import com.mycarhub.assistant.core.ReminderRequest
import com.mycarhub.phone.reminder.Reminders
import java.time.LocalDate
import java.time.LocalDateTime
import com.mycarhub.phone.control.AppSearch
import com.mycarhub.phone.control.AppSearchParser
import com.mycarhub.phone.control.NavigateParser
import com.mycarhub.phone.control.MediaCommand
import com.mycarhub.phone.control.MediaCommandParser
import com.mycarhub.phone.control.MediaControl
import com.mycarhub.phone.control.ScreenActions
import com.mycarhub.phone.control.ScreenCommand
import com.mycarhub.phone.control.ScreenCommandParser
import com.mycarhub.phone.control.ScreenControlService
import com.mycarhub.phone.control.ScreenResult
import com.mycarhub.wakeword.audio.MicrophonePcmSource
import java.util.concurrent.Executors

/**
 * 폰 빌드의 양방향 음성 대화: 인사 → 듣기 → 실행 → 결과를 음성으로 안내 → (필요하면) 다시 듣기.
 *
 * 화면을 보지 않아도 되도록 모든 단계를 TTS로 말한다. 한 번에 한 가지만 한다: 말하는 동안에는 인식기를 열지 않고,
 * 말이 끝나고 잠깐 쉰 뒤에 듣는다 (비서 목소리가 다시 인식되지 않게). 대답이 두 번 없으면 대화를 끝내고
 * 화면이 닫히면 호출어 대기로 돌아간다.
 *
 * 메시지 답장은 내용 확인 질문에 '응/보내'라고 분명히 대답했을 때만 보낸다. 대답이 없거나 애매하면 보내지 않는다.
 * 필요한 권한(마이크·알림 접근·연락처)이 없으면 말로 안내만 하지 않고 허용 화면을 바로 띄운 뒤, 허용하면 하던 명령을 이어간다.
 * 실제로 실패한 일은 실패했다고 말한다. 읽은 메시지·답장 초안·인식 문장은 메모리에만 두고 로그에 남기지 않는다.
 * (개발 빌드에서만 이름 찾기 진단을 위해 인식 문장·찾은 이름·후보 이름을 기기 logcat에 남긴다.)
 *
 * 차량용 빌드의 VoiceAssistantSession은 바꾸지 않고, 같은 인식기·명령 해석·앱 실행·메시지 기능을 그대로 쓴다.
 * 메인 스레드에서 호출한다.
 */
class PhoneConversation(
    context: Context,
    private val wakeWordName: String?,
    private val onUpdate: (Snapshot) -> Unit,
    /** 개발 빌드 ADB 명령 테스트용 ([ConversationTestHooks]). 실제 사용에서는 null. */
    private val testHooks: ConversationTestHooks? = null,
    /**
     * 전체 화면 음성비서처럼 다른 앱으로 넘어가면 사라지는 화면이면 true: 연속 대화 중 앱을 열거나 홈·뒤로 가면
     * [End.LAUNCHED] + [Snapshot.continuous]로 끝내 화면이 다른 앱 위 창으로 대화를 넘기게 한다.
     */
    private val handOffOnLeave: Boolean = false,
) {
    // 릴리스 빌드에서는 항상 null (BuildConfig.DEBUG가 false라 테스트 경로가 실행되지 않는다)
    private val hooks: ConversationTestHooks? get() = if (BuildConfig.DEBUG) testHooks else null

    enum class Phase { SPEAKING, LISTENING, WORKING, PERMISSION, ENDED }

    /** 무엇을 기다리는 중인지 */
    enum class Step { COMMAND, AFTER_READ, REPLY_BODY, CONFIRM, CHOOSE, PERSON_CONFIRM, PERSON_CHOOSE, PERSON_NAME, CLARIFY }

    /** 화면에서 사용자가 해야 하는 일 (말로는 해결할 수 없는 것) */
    enum class Action { NONE, MIC_PERMISSION, NOTIFICATION_ACCESS, STT_CONSENT }

    enum class End { DONE, LAUNCHED, NO_RESPONSE, CANCELLED, ERROR }

    data class Snapshot(
        val phase: Phase,
        /** 비서가 마지막으로 한 말 (화면 자막) */
        val assistant: String?,
        /** 사용자가 한 말 (인식 중이면 중간 결과) */
        val heard: String?,
        val level: Int,
        val step: Step,
        val action: Action,
        /** 메시지 내용이 화면에 있는지 (화면 캡처 방지) */
        val showsMessage: Boolean,
        /** TTS로 실제 소리를 내는지 (false면 글자로만 보여줌) */
        val voice: Boolean,
        val end: End?,
        /** 연속 대화 모드 (호출어 없이 다음 명령을 계속 듣는 중) */
        val continuous: Boolean = false,
    )

    private val context = context.applicationContext
    private val app = this.context as MyCarHubApplication
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // 명령을 말한 뒤 약 2초 무음이면 바로 처리한다 (휴대폰만)
    private val stt = SpeechToText(this.context, app.sttSettings).apply { fastEndpoint = true }
    private val speaker = PhoneSpeaker(this.context)
    private val resolver = AppCommandResolver(app.catalog)
    private val messages = app.messages
    private val drive = DriveAssistant(this.context, Platform.voiceTodoSource)

    // 다시 시작·취소하면 이전 흐름의 늦은 결과를 버린다
    private var generation = 0
    private var snapshot = Snapshot(Phase.WORKING, null, null, 0, Step.COMMAND, Action.NONE, false, true, null)

    private var step = Step.COMMAND
    private var lastPrompt: String? = null
    private var misses = 0
    private var micRetried = false
    private var confirmRetried = false

    // 대화 내용 (메모리에만)
    private var message: IncomingMessage? = null
    private var draft: String? = null
    private var choices: List<FavoriteApp> = emptyList()

    // 사람 이름으로 찾는 중인 요청 (메모리에만)
    private var person: PersonRequest? = null
    private var personName: String? = null
    private var personChoices: List<String> = emptyList()
    private var nameRetried = false
    private var choiceRetried = false

    // 이번 대화에서 이미 허용 화면을 띄운 권한 (다시 띄우지 않는다)
    private val permissionAsked = mutableSetOf<PermissionGate.Kind>()

    // 되묻는 중인 질문 ("당근 앱을 열까요, 알림을 읽을까요?") (메모리에만)
    private var clarify: Clarify? = null
    private var clarifyRetried = false

    // 앱을 지정해 읽은 알림인지 (메시지가 아닌 알림이면 알림이라고 읽는다)
    private var readingApp = false

    // 연속 대화: 한 번 부르면 명령을 마친 뒤에도 호출어 없이 다음 명령을 듣는다. [idleUntil]까지 말이 없으면 끝낸다.
    private var continuous = false
    private var idleUntil = 0L

    // "이거 눌러"에 무엇을 누를지 되묻고 대답을 기다리는 중
    private var pendingTap = false

    // 이번 대화에서 접근성 설정 화면을 이미 띄웠는지 (다시 띄우지 않는다)
    private var accessibilityAsked = false

    // 시간을 몰라 "언제 알려드릴까요?"라고 되물은 알림 (메모리에만)
    private var pendingReminder: ReminderDraft? = null

    /** 되묻는 질문. [options]는 선택지를 가리키는 말과 고르면 할 일, [onYes]는 "응"이라고만 대답했을 때 할 일. */
    private class Clarify(val prompt: String, val options: List<Pair<List<String>, () -> Unit>>, val onYes: (() -> Unit)? = null)

    /** 이름을 확인한 뒤 할 일. [app]은 "카톡에서 동진님 메시지"처럼 함께 말한 앱. */
    private sealed interface PersonRequest {
        val app: String? get() = null

        data class Read(override val app: String?) : PersonRequest

        data class Reply(val body: String?) : PersonRequest

        /** 그 사람과의 대화방 열기 (알림의 링크로) */
        data class Open(override val app: String?) : PersonRequest
    }

    val state: Snapshot get() = snapshot

    /** 호출어 감지·[직접 음성비서 실행]: "네, 말씀하세요!" 라고 대답하고 명령을 듣는다. */
    fun start() {
        clear()
        // 호출어로 부르면 연속 대화를 시작한다 (ADB 테스트는 기존처럼 한 번씩)
        continuous = hooks == null
        accessibilityAsked = false
        idleUntil = SystemClock.elapsedRealtime() + CONTINUOUS_IDLE_MS
        ask(str(R.string.conv_greeting), Step.COMMAND)
    }

    /**
     * 플로팅(다른 앱 위)에서 호출: 인사 음성 없이 바로 명령을 듣는다.
     * 인사("네, 말씀하세요")를 하는 동안 듣지 못해 "쁘이, 유튜브로 …"처럼 이어 말한 앞부분이 잘리던 문제를 막는다.
     */
    fun startListeningNow() {
        clear()
        continuous = hooks == null
        accessibilityAsked = false
        idleUntil = SystemClock.elapsedRealtime() + CONTINUOUS_IDLE_MS
        listen(Step.COMMAND)
    }

    /** 전체 화면 음성비서가 앱을 연 뒤 다른 앱 위 창이 이어받음: 인사 없이 바로 다음 명령을 듣는다. */
    fun resumeContinuous() {
        clear()
        continuous = true
        accessibilityAsked = false
        awaitNext()
    }

    /** 개발 빌드 ADB 테스트: 인사 없이 바로 명령을 기다린다 (마이크는 열지 않는다). */
    fun startForTest() {
        if (hooks == null) return
        clear()
        listen(Step.COMMAND)
    }

    /**
     * 개발 빌드 ADB 테스트: [text]를 음성 인식 결과로 받은 것처럼 지금 단계의 처리로 넘긴다 (인식 이후 경로는 실제와 같다).
     * 듣는 중이 아니면 false.
     */
    fun hearForTest(text: String): Boolean {
        if (hooks == null || snapshot.phase != Phase.LISTENING) return false
        generation++
        onHeard(listOf(text), step)
        return true
    }

    /** 화면의 [다시 말하기]: 대화가 끝난 뒤 다시 명령을 듣는다. */
    fun restart() {
        misses = 0
        ask(str(R.string.conv_listening_again), Step.COMMAND)
    }

    /** 확인 질문 중 화면의 [보내기] (음성 '응'과 같다). */
    fun confirmSend() {
        if (step == Step.CONFIRM && draft != null && message != null) send()
    }

    /** 확인 질문 중 화면의 [보내지 않기]. */
    fun cancelReply() {
        if (draft == null) return
        draft = null
        finish(str(R.string.conv_reply_cancelled), End.CANCELLED)
    }

    /** 화면의 [그만하기]: 조용히 멈춘다. */
    fun stop() {
        generation++
        continuous = false
        stt.cancel()
        speaker.stop()
        update(phase = Phase.ENDED, end = End.CANCELLED, level = 0)
    }

    /** 화면을 떠날 때. 말하던 문장은 끊고 내용을 지운다. */
    fun release() {
        generation++
        stt.cancel()
        speaker.shutdown()
        worker.shutdownNow()
        clear()
    }

    // region 말하기·듣기

    /** [text]를 말한 뒤 [next] 단계의 대답을 듣는다. */
    private fun ask(text: String, next: Step) {
        lastPrompt = text
        sayThenListen(text, next)
    }

    /**
     * 명령을 기다리는 안내("네, 말씀하세요!")는 말하는 동안에도 인식기를 미리 열어 둔다 (끼어들기).
     * 안내가 끝나기 전에 말을 시작해도 첫마디부터 인식되고, 사용자 말이 들리면 안내를 바로 멈춘다.
     * 그 밖의 질문(확인·선택 등)은 대답이 안내 문장과 겹칠 수 있어 지금처럼 다 말한 뒤 듣는다.
     */
    private fun sayThenListen(text: String, next: Step) {
        if (next == Step.COMMAND && text in bargeInPrompts() && canListenWhileSpeaking()) speakWhileListening(text) else say(text) { listen(next) }
    }

    /**
     * 말하는 동안 들어도 되는 짧은 안내만 ("네, 말씀하세요!" 등). 예시 명령이 든 안내("예를 들어 카카오톡 열어줘…")를
     * 말하는 동안 들으면 비서 자신의 목소리를 명령으로 알아들을 수 있어 다 말한 뒤 듣는다 (실기기 로그로 확인).
     */
    private fun bargeInPrompts(): Set<String> = setOf(
        str(R.string.conv_greeting),
        str(R.string.conv_listening_again),
        str(R.string.conv_no_speech),
        str(R.string.conv_not_understood),
    )

    private fun canListenWhileSpeaking(): Boolean =
        hooks == null && snapshot.voice && speaker.state == PhoneSpeaker.State.READY &&
            MicrophonePcmSource.hasPermission(context) && stt.availability() is SpeechToText.Availability.Ready

    private fun speakWhileListening(text: String) {
        val run = ++generation
        step = Step.COMMAND
        stt.cancel()
        val echo = PromptEcho(text)
        update(phase = Phase.SPEAKING, assistant = text, level = 0, end = null, action = Action.NONE, step = Step.COMMAND)
        speaker.speak(text) { spoken ->
            if (run != generation || !echo.speaking) return@speak
            echo.speaking = false
            if (!spoken && speaker.state != PhoneSpeaker.State.READY && snapshot.voice) update(voice = false)
            if (snapshot.phase == Phase.SPEAKING) update(phase = Phase.LISTENING, level = 0)
        }
        // 호출어 감지가 마이크를 놓는 즉시 인식을 시작한다 (안내가 끝나기를 기다리지 않음)
        app.wakeWords.whenMicReleased {
            if (run != generation) return@whenMicReleased
            if (!stt.start(Recognition(run, Step.COMMAND, echo))) finish(str(R.string.conv_stt_error), End.ERROR, fatal = true)
        }
    }

    /** 안내 중 사용자 말이 들림: 안내를 멈추고 계속 듣는다. */
    private fun bargeIn(echo: PromptEcho) {
        if (!echo.speaking) return
        echo.speaking = false
        Log.i(TAG, "barge-in: prompt stopped")
        speaker.stop()
        update(phase = Phase.LISTENING, level = 0)
    }

    private fun say(text: String, then: () -> Unit) {
        val run = ++generation
        stt.cancel()
        // 대답하는 동안 방금 알아들은 문장(heard)을 화면에 그대로 둔다 (다음 듣기에서 지움)
        update(phase = Phase.SPEAKING, assistant = text, level = 0, end = null, action = Action.NONE)
        hooks?.let { test ->
            // 테스트: 소리를 내지 않고 할 말만 기록한다
            test.onSay(text)
            main.post { if (run == generation) then() }
            return
        }
        speaker.speak(text) { spoken ->
            if (run != generation) return@speak
            if (!spoken && speaker.state != PhoneSpeaker.State.READY && snapshot.voice) update(voice = false)
            // 소리가 안 나면 글자를 읽을 시간을 준다. 소리가 났으면 끝부분이 인식기에 들어가지 않게 잠깐 쉰다.
            val pause = if (spoken) AFTER_TTS_PAUSE_MS else TEXT_ONLY_MS
            main.postDelayed({ if (run == generation) then() }, pause)
        }
    }

    /** 실행형 명령: 안내 음성 없이 바로 실행한다 (실패·권한 안내만 말한다). */
    private fun act(then: () -> Unit) {
        ++generation
        stt.cancel()
        update(phase = Phase.WORKING, level = 0, end = null, action = Action.NONE)
        then()
    }

    /** 성공한 실행형 명령을 말없이 마친다. 연속 대화면 다음 명령을 듣는다. */
    private fun doneQuietly() {
        if (continuous) awaitNext() else finishQuietly(End.DONE)
    }

    private fun listen(next: Step) {
        val run = ++generation
        step = next
        update(phase = Phase.WORKING, heard = null, level = 0, step = next)
        hooks?.let { test ->
            // 테스트: 마이크·인식기를 열지 않고 ADB로 들어올 문장을 기다린다 ([hearForTest])
            update(phase = Phase.LISTENING)
            test.onListen(next)
            return
        }
        if (!MicrophonePcmSource.hasPermission(context)) return askMicrophone(next)
        when (stt.availability()) {
            SpeechToText.Availability.NeedsSystemConsent ->
                return finish(str(R.string.conv_need_stt_consent), End.ERROR, Action.STT_CONSENT)
            SpeechToText.Availability.Unavailable -> return finish(str(R.string.conv_stt_unavailable), End.ERROR, fatal = true)
            is SpeechToText.Availability.Ready -> Unit
        }
        // 호출어 감지가 마이크를 놓은 뒤 인식을 시작한다
        app.wakeWords.whenMicReleased {
            if (run != generation) return@whenMicReleased
            if (!stt.start(Recognition(run, next))) finish(str(R.string.conv_stt_error), End.ERROR, fatal = true)
        }
    }

    /** [echo]: 안내를 말하는 동안 연 인식이면 그 안내 문장 (마이크로 들어온 안내 소리를 결과에서 뗀다). */
    private inner class Recognition(
        private val run: Int,
        private val purpose: Step,
        private val echo: PromptEcho? = null,
    ) : SpeechToText.Listener {
        private var partial: String? = null

        override fun onReady() {
            if (run == generation && echo?.speaking != true) update(phase = Phase.LISTENING, level = 0)
        }

        override fun onLevel(rmsDb: Float) {
            if (run != generation || echo?.speaking == true) return
            update(phase = Phase.LISTENING, level = ((rmsDb - RMS_MIN_DB) / (RMS_MAX_DB - RMS_MIN_DB) * 100).toInt().coerceIn(0, 100))
        }

        override fun onPartial(text: String) {
            if (run != generation) return
            val said = echo?.strip(text) ?: text
            if (said.isEmpty()) return
            echo?.let(::bargeIn)
            partial = said
            update(heard = said)
        }

        override fun onResults(hypotheses: List<String>) {
            if (run != generation) return
            val said = if (echo == null) hypotheses else hypotheses.map(echo::strip).filter { it.isNotEmpty() }
            if (said.isEmpty()) return onEchoOnly(SpeechToText.Error.NO_MATCH)
            echo?.let(::bargeIn)
            onHeard(said, purpose)
        }

        /** 안내 소리만 들림 (사용자는 아직 말하지 않음): 같은 대화에서 인식을 다시 열어 계속 듣는다. */
        private fun onEchoOnly(error: SpeechToText.Error) {
            val e = echo
            if (e != null && (e.speaking || e.heardEcho) && e.restarts < MAX_ECHO_RESTARTS) {
                e.restarts++
                e.heardEcho = false
                Log.i(TAG, "only prompt echo heard, listening again")
                if (!stt.start(Recognition(run, purpose, e))) finish(str(R.string.conv_stt_error), End.ERROR, fatal = true)
                return
            }
            if (e?.speaking == true) bargeIn(e)
            onSilence(purpose, heardSomething = partial != null)
        }

        override fun onError(error: SpeechToText.Error) {
            if (run != generation) return
            Log.i(TAG, "stt error=$error step=$purpose")
            when (error) {
                SpeechToText.Error.NO_SPEECH, SpeechToText.Error.NO_MATCH ->
                    if (echo != null) onEchoOnly(error) else onSilence(purpose, heardSomething = partial != null)
                // 호출어 감지가 마이크를 막 놓는 중일 수 있다: 한 번은 조용히 다시 시도
                SpeechToText.Error.AUDIO, SpeechToText.Error.BUSY ->
                    if (!micRetried) {
                        micRetried = true
                        main.postDelayed({ if (run == generation) listen(purpose) }, MIC_RETRY_MS)
                    } else {
                        finish(str(R.string.conv_mic_busy), End.ERROR, fatal = true)
                    }
                SpeechToText.Error.PERMISSION -> askMicrophone(purpose)
                SpeechToText.Error.NETWORK -> finish(str(R.string.conv_stt_network), End.ERROR, fatal = true)
                SpeechToText.Error.LANGUAGE_UNAVAILABLE -> finish(str(R.string.conv_stt_language), End.ERROR, fatal = true)
                SpeechToText.Error.SERVICE -> finish(str(R.string.conv_stt_error), End.ERROR, fatal = true)
            }
        }
    }

    /** 인식 결과 → 지금 단계의 처리. 실제 인식기와 ADB 테스트([hearForTest])가 같이 쓴다. */
    private fun onHeard(hypotheses: List<String>, purpose: Step) {
        misses = 0
        micRetried = false
        update(phase = Phase.WORKING, heard = hypotheses.first(), level = 0)
        // 인식 문장은 개인 정보일 수 있어 단계만 남긴다
        Log.i(TAG, "heard: step=$purpose candidates=${hypotheses.size}")
        when (purpose) {
            Step.COMMAND, Step.AFTER_READ -> onCommand(hypotheses, purpose)
            Step.REPLY_BODY -> onReplyBody(hypotheses.first())
            Step.CONFIRM -> onConfirmation(hypotheses)
            Step.CHOOSE -> onChoice(hypotheses)
            Step.PERSON_CONFIRM -> onPersonConfirm(hypotheses)
            Step.PERSON_CHOOSE -> onPersonChoice(hypotheses)
            Step.PERSON_NAME -> onPersonName(hypotheses)
            Step.CLARIFY -> onClarify(hypotheses)
        }
    }

    /** 마이크 권한이 없음: 권한 창을 띄우고, 허용하면 하던 질문의 대답을 이어서 듣는다. */
    private fun askMicrophone(next: Step) {
        withPermission(PermissionGate.Kind.MICROPHONE, R.string.conv_permission_mic, onGranted = { listen(next) }) {
            finish(str(R.string.conv_need_mic), End.ERROR, Action.MIC_PERMISSION)
        }
    }

    /**
     * 기능에 필요한 권한이 없을 때: 무엇을 허용해야 하는지 말한 뒤 허용 화면(권한 창·시스템 설정)을 바로 띄우고,
     * 허용하면 [onGranted]로 원래 하던 일을 이어간다. 이번 대화에서 이미 띄웠거나 전에 거부한 권한은
     * 다시 띄우지 않고 [onDenied] (반복 요청하지 않는다. 화면의 버튼이나 설정에서는 다시 허용할 수 있다).
     */
    private fun withPermission(kind: PermissionGate.Kind, intro: Int, onGranted: () -> Unit, onDenied: () -> Unit) {
        if (PermissionGate.isGranted(context, kind)) return onGranted()
        if (kind in permissionAsked || PermissionGate.wasDeclined(context, kind)) return onDenied()
        // 테스트: 권한 화면을 띄우지 않는다
        hooks?.let { test ->
            test.onAction("permission_missing", kind.name)
            return onDenied()
        }
        permissionAsked += kind
        say(str(intro)) {
            val run = ++generation
            update(phase = Phase.PERMISSION, level = 0)
            PermissionGate.request(context, kind, force = false) { granted ->
                if (run != generation) return@request
                Log.i(TAG, "permission $kind granted=$granted")
                // 권한 화면이 닫히고 원래 화면으로 돌아올 시간을 준 뒤 이어간다
                if (granted) main.postDelayed({ if (run == generation) onGranted() }, AFTER_PERMISSION_MS) else onDenied()
            }
        }
    }

    /** 대답이 없거나 알아듣지 못함: 한 번은 다시 묻고, 두 번째면 대화를 끝낸다. */
    private fun onSilence(purpose: Step, heardSomething: Boolean) {
        // 연속 대화에서 명령을 기다리는 중: 잠깐 조용해도 끝내지 않고 정해진 시간까지 계속 듣는다
        if (continuous && purpose == Step.COMMAND) {
            if (SystemClock.elapsedRealtime() >= idleUntil) return endContinuous(timeout = true)
            return if (heardSomething) sayThenListen(str(R.string.conv_not_understood), Step.COMMAND) else listen(Step.COMMAND)
        }
        when {
            purpose == Step.CONFIRM -> askConfirmationAgain()
            // 메시지를 다 들은 뒤 아무 말 없으면 그대로 끝낸다
            purpose == Step.AFTER_READ && !heardSomething -> finish(str(R.string.conv_bye, wakeName()), End.NO_RESPONSE)
            misses == 0 -> {
                misses++
                sayThenListen(str(if (heardSomething) R.string.conv_not_understood else R.string.conv_no_speech), purpose)
            }
            else -> {
                if (purpose == Step.REPLY_BODY) draft = null
                finish(str(R.string.conv_bye, wakeName()), End.NO_RESPONSE)
            }
        }
    }

    // endregion

    // region 명령

    private fun onCommand(hypotheses: List<String>, purpose: Step) {
        val heard = hypotheses.first()
        if (purpose == Step.COMMAND) {
            // "이거 눌러"에 되물은 대답: 누를 버튼 이름
            if (pendingTap) {
                pendingTap = false
                val label = ScreenCommandParser.tapAnswer(heard, wakeWordName)
                if (label != null && CommandParser.parse(heard) != VoiceCommand.Cancel) return onScreenCommand(ScreenCommand.Tap(label))
            }
            // "언제 알려드릴까요?"에 대한 대답 ("10분 뒤", "내일 아침 8시")
            pendingReminder?.let { draft ->
                pendingReminder = null
                if (CommandParser.parse(heard) == VoiceCommand.Cancel) return finish(str(R.string.conv_ok), End.CANCELLED)
                val answer = ReminderParser.answer(heard, LocalDateTime.now(), draft, wakeWordName)
                    ?: return finish(str(R.string.reminder_time_unknown), End.DONE)
                return onReminder(answer)
            }
            // 미디어 제어 ("다음 곡", "이전 곡", "음악 멈춰", "다시 재생해"): 앱 실행보다 먼저 ("다음" ≠ Daum 앱)
            MediaCommandParser.parse(heard, wakeWordName)?.let { return onMediaCommand(it) }
            // 알림·타이머 ("2분 뒤에 설거지하라고 알림 켜줘", "30분 타이머 맞춰줘", "방금 알림 취소해 줘")
            ReminderParser.parse(heard, LocalDateTime.now(), wakeWordName)?.let { return onReminder(it) }
            // 화면 조작("뒤로 가", "검색창에 …라고 쳐", "… 눌러")과 연속 대화 종료("쁘이 그만", "대화 종료")
            ScreenCommandParser.parse(heard, wakeWordName)?.let { return onScreenCommand(it) }
        }
        val commands = hypotheses.map { CommandParser.parse(it, wakeWordName) }
        val first = commands.first()
        // 문장 전체로 의도(앱 열기·메시지 읽기·답장·대화방 열기)를 먼저 정하고, 이름이 앱인지 사람인지는 그다음에 가린다
        val personCommand = PersonCommandParser.parse(heard, wakeWordName)
        Log.i(TAG, "command=${first::class.simpleName} person=${personCommand?.let { it::class.simpleName }} step=$purpose")
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stt debug hypotheses=$hypotheses command=$first personCommand=$personCommand")
        }
        // Drive To-Do: "차 어디 세웠지?", "여기 주차했어", "할 일 뭐 있어?", "집에 가서 택배 확인해야 해"
        if (purpose == Step.COMMAND) {
            when (val answer = drive.answer(heard, wakeWordName, save = hooks?.saveDriveData ?: true)) {
                is DriveAssistant.Answer.Say -> return driveSay(answer)
                DriveAssistant.Answer.RecordParkingHere -> return parkHere()
                null -> Unit
            }
        }
        // 메시지를 읽은 뒤 "응" → 답장 내용 묻기, "아니" → 끝
        if (purpose == Step.AFTER_READ && message != null) {
            when (CommandParser.parseConfirmation(heard)) {
                Confirmation.YES -> if (first !is VoiceCommand.Reply && first !is VoiceCommand.ReadMessage) return startReply(null)
                Confirmation.NO -> return finish(str(R.string.conv_ok), End.DONE)
                Confirmation.UNKNOWN -> Unit
            }
        }
        // "동진님 메시지 읽어줘", "당근에서 온 알림 읽어줘", "동진님한테 답장해줘": 이름이 앱인지 사람인지 가린 뒤 찾는다
        when (val command = personCommand) {
            is PersonCommand.Read -> return findPerson(command.words, PersonRequest.Read(null), heard, classify = true)
            is PersonCommand.Reply -> return findPerson(listOf(command.name), PersonRequest.Reply(command.body), heard)
            is PersonCommand.Open -> return findPerson(command.words, PersonRequest.Open(null), heard)
            null -> Unit
        }
        // "유튜브에서 판다 검색해 줘", "유튜브 열고 판다 검색해 줘": 앱을 찾아 그 앱에서 검색한다
        // "강남역 길 안내해 줘": 지도 앱에서 목적지 검색 (일반 앱 실행보다 먼저)
        if (purpose == Step.COMMAND) NavigateParser.parse(heard, wakeWordName)?.let { return startNavigate(it) }
        // 인식 후보 전체에서 찾는다 (첫 후보에서 앱 이름이 빠져도 다른 후보에 있으면 그 앱으로)
        if (purpose == Step.COMMAND) hypotheses.firstNotNullOfOrNull { AppSearchParser.parse(it, wakeWordName) }?.let { return startAppSearch(it, heard, commands) }
        when (first) {
            VoiceCommand.Cancel -> return if (continuous) endContinuous() else finish(str(R.string.conv_ok), End.CANCELLED)
            VoiceCommand.Repeat -> return repeat(purpose)
            is VoiceCommand.Unsupported -> return ask(str(R.string.conv_unsupported), Step.COMMAND)
            is VoiceCommand.ReadMessage -> return readMessage(first.app)
            is VoiceCommand.Reply -> return startReply(first.body)
            else -> Unit
        }
        val registered = app.favoriteApps.favorites
        val run = ++generation
        val messagesReady = messages.status == MessageAccess.Status.READY
        worker.execute {
            val outcome = resolveApp(heard, commands, registered, messagesReady)
            main.post { if (run == generation) outcome() }
        }
    }

    // region Drive To-Do

    /** 주차 위치·할 일 대답. 주차 위치가 있으면 걸어서 길찾기를 열지 묻는다. */
    private fun driveSay(answer: DriveAssistant.Answer.Say) {
        val spot = answer.spot ?: return finish(answer.text, End.DONE)
        val navigate = { openDirections(spot) }
        askClarify(
            answer.text + " " + str(R.string.conv_drive_offer_directions),
            listOf(DIRECTION_WORDS to navigate),
            onYes = navigate,
        )
    }

    private fun openDirections(spot: ParkingSpot) {
        act {
            hooks?.let { test ->
                // 테스트: 지도 앱을 열지 않는다
                test.onAction("directions", "parking")
                return@act finishQuietly(End.LAUNCHED)
            }
            try {
                val directions = DriveAssistant.directionsIntent(spot) ?: return@act finish(str(R.string.drive_no_map_app), End.ERROR)
                context.startActivity(directions.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finishQuietly(End.LAUNCHED)
            } catch (e: ActivityNotFoundException) {
                finish(str(R.string.drive_no_map_app), End.ERROR)
            }
        }
    }

    /** "여기 주차했어": 위치 권한을 확인(없으면 허용 창)한 뒤 지금 위치를 저장한다. 위치를 못 얻으면 저장하지 않는다. */
    private fun parkHere() {
        withPermission(PermissionGate.Kind.LOCATION, R.string.conv_permission_location, onGranted = {
            hooks?.let { test ->
                // 테스트: 저장된 주차 위치를 바꾸지 않는다
                test.onAction("record_parking", "manual")
                return@withPermission finish(str(R.string.drive_parking_saved), End.DONE)
            }
            update(phase = Phase.WORKING)
            val run = ++generation
            drive.recordParkingHere { text -> if (run == generation) finish(text, End.DONE) }
        }) {
            finish(str(R.string.drive_parking_need_location), End.ERROR)
        }
    }

    // endregion

    /** "다시 말해줘": 메시지를 읽은 뒤면 메시지를, 아니면 마지막 질문을 다시 말한다. */
    private fun repeat(purpose: Step) {
        val current = message
        if (purpose == Step.AFTER_READ && current != null) return readAloud(current, intro = null)
        ask(lastPrompt ?: str(R.string.conv_greeting), purpose)
    }

    /** 백그라운드 스레드 (앱 목록 조회). 메인 스레드에서 실행할 동작을 돌려준다. */
    private fun resolveApp(heard: String, commands: List<VoiceCommand>, registered: List<FavoriteApp>, messagesReady: Boolean): () -> Unit {
        val opens = commands.filterIsInstance<VoiceCommand.OpenApp>()
        var missing: String? = null
        for (command in opens) {
            val found = resolver.resolve(command.target, registered)
            if (BuildConfig.DEBUG) Log.d(TAG, "app debug target=\"${command.target}\" hasVerb=${command.hasVerb} resolved=$found")
            when (found) {
                is AppCommandResolver.Resolution.Found -> {
                    // 동사 없이 "당근"이라고만 말했고 그 앱의 알림이 와 있으면: 열지 읽을지 묻는다
                    if (!command.hasVerb && messagesReady && hasAppNotifications(found.app.packageName)) {
                        return { askOpenOrRead(found.app) }
                    }
                    return { openApp(found.app) }
                }
                is AppCommandResolver.Resolution.Choices -> return { askChoice(found.apps) }
                is AppCommandResolver.Resolution.NotFound -> if (missing == null && command.hasVerb) missing = command.target
            }
        }
        // 인식은 됐지만 무슨 명령인지 모름: 알아들은 문장을 함께 말한다 (인식 실패는 conv_no_speech·conv_not_understood)
        val target = missing ?: return { retryCommand(str(R.string.conv_unknown_command, heard)) }
        // 글자가 같은 앱이 없음: 설치된 앱 이름 중 발음이 비슷한 앱 ("당군 열어줘" → 당근)
        val installed = installedApps(registered)
        val hit = AppNameMatcher.find(target, installed.map { it.first })
        Log.i(TAG, "app fuzzy hit=${hit::class.simpleName} installed=${installed.size}")
        if (BuildConfig.DEBUG) Log.d(TAG, "app debug fuzzy target=\"$target\" hit=$hit")
        fun favorite(entry: AppEntry) = installed.first { it.first == entry }.second
        when (hit) {
            is AppHit.Exact -> return { openApp(favorite(hit.app)) }
            is AppHit.Likely -> return { confirmOpen(favorite(hit.app)) }
            is AppHit.Ambiguous -> return { askChoice(hit.apps.map(::favorite)) }
            AppHit.None -> Unit
        }
        // 앱이 아니고 알림이 와 있는 사람 이름이면 ("동진님 열어줘"): 그 사람의 대화방 열기로 본다
        if (messagesReady) {
            val names = candidateNames(allMessages(), null).let { it.fromMessages + it.registered }
            val person = KoreanNameMatcher.resolve(target, names)
            if (BuildConfig.DEBUG) Log.d(TAG, "app debug person target=\"$target\" match=$person")
            if (person != NameMatch.None) return { findPerson(listOf(target), PersonRequest.Open(null), heard) }
        }
        return { retryCommand(str(R.string.conv_app_not_found, target)) }
    }

    /** 백그라운드 스레드. '내 앱' 등록 앱(별칭 포함) + 설치된 런처 앱. */
    private fun installedApps(registered: List<FavoriteApp>): List<Pair<AppEntry, FavoriteApp>> {
        val packages = registered.map { it.packageName }.toSet()
        val mine = registered.map { AppEntry(it.id, it.label, it.alias, it.packageName, registered = true) to it }
        val others = app.catalog.queryLaunchableApps().filter { it.packageName !in packages }.map {
            AppEntry(it.id, it.label, null, it.packageName, registered = false) to FavoriteApp(it.packageName, it.activityName, it.label)
        }
        return mine + others
    }

    /** 백그라운드 스레드. */
    private fun hasAppNotifications(packageName: String): Boolean = appNotifications(packageName).isNotEmpty()

    /** 백그라운드 스레드. 현재 메시지 알림 (테스트면 넣어 둔 가짜 알림도). */
    private fun allMessages(): List<IncomingMessage> {
        val real = messages.currentMessages()
        val test = hooks ?: return real
        return real + test.extraMessages.filter { it.isMessage }.map { it.message }
    }

    /** 백그라운드 스레드. [packageName] 앱의 현재 알림 (메시지가 아닌 알림 포함, 테스트면 가짜 알림도). */
    private fun appNotifications(packageName: String): List<IncomingMessage> {
        val real = (messages as? NotificationMessageAccess)?.appNotifications(setOf(packageName))
            ?: messages.currentMessages().filter { it.packageName == packageName }
        val test = hooks ?: return real
        return real + test.extraMessages.map { it.message }.filter { it.packageName == packageName }
    }

    /** 비슷한 이름으로 찾은 앱: 열기 전에 확인한다. */
    private fun confirmOpen(target: FavoriteApp) {
        askClarify(
            str(R.string.conv_confirm_app_open, target.displayName),
            listOf(ClarifyAnswer.OPEN_WORDS to { openApp(target) }),
            onYes = { openApp(target) },
        )
    }

    /** "당근"이라고만 말함: 앱을 열지 알림을 읽을지 묻는다. */
    private fun askOpenOrRead(target: FavoriteApp) {
        askClarify(
            str(R.string.conv_ask_open_or_read, target.displayName),
            listOf(
                ClarifyAnswer.OPEN_WORDS to { openApp(target) },
                ClarifyAnswer.READ_WORDS to { readAppNotifications(target.displayName, target.packageName) },
            ),
        )
    }

    private fun askClarify(prompt: String, options: List<Pair<List<String>, () -> Unit>>, onYes: (() -> Unit)? = null) {
        clarify = Clarify(prompt, options, onYes)
        clarifyRetried = false
        ask(prompt, Step.CLARIFY)
    }

    /** 되물은 질문의 대답. 알아듣지 못하면 한 번 더 묻고, 그래도 모르면 임의로 하지 않고 끝낸다. */
    private fun onClarify(hypotheses: List<String>) {
        val pending = clarify ?: return finish(str(R.string.conv_ok), End.DONE)
        val heard = hypotheses.first()
        if (BuildConfig.DEBUG) Log.d(TAG, "clarify debug hypotheses=$hypotheses")
        when (CommandParser.parse(heard)) {
            VoiceCommand.Cancel -> return cancelClarify()
            VoiceCommand.Repeat -> return ask(pending.prompt, Step.CLARIFY)
            else -> Unit
        }
        // "열지 마", "읽지 말고": 하지 않는다
        if (heard.replace(" ", "").let { it.contains("지마") || it.contains("지말") }) return cancelClarify()
        for (text in hypotheses) {
            val picked = ClarifyAnswer.pick(text, pending.options.map { it.first }) ?: continue
            clarify = null
            Log.i(TAG, "clarify picked=$picked")
            return pending.options[picked].second()
        }
        when (CommandParser.parseConfirmation(heard)) {
            Confirmation.YES -> pending.onYes?.let { clarify = null; return it() }
            Confirmation.NO -> return cancelClarify()
            Confirmation.UNKNOWN -> Unit
        }
        if (clarifyRetried) {
            clarify = null
            return finish(str(R.string.conv_clarify_end), End.DONE)
        }
        clarifyRetried = true
        ask(str(R.string.conv_clarify_again, pending.prompt), Step.CLARIFY)
    }

    private fun cancelClarify() {
        clarify = null
        finish(str(R.string.conv_ok), End.CANCELLED)
    }

    /** 이해하지 못한 명령: 한 번은 다시 말해 달라고 하고, 두 번째면 끝낸다. */
    private fun retryCommand(text: String) {
        if (misses == 0) {
            misses++
            ask(text, Step.COMMAND)
        } else {
            finish(text, End.DONE)
        }
    }

    private fun askChoice(apps: List<FavoriteApp>) {
        choices = apps
        ask(str(R.string.conv_choose_app, apps.joinToString(", ") { it.displayName }), Step.CHOOSE)
    }

    private fun onChoice(hypotheses: List<String>) {
        val heard = hypotheses.first()
        if (CommandParser.parse(heard) == VoiceCommand.Cancel) return finish(str(R.string.conv_ok), End.CANCELLED)
        val picked = pickChoice(hypotheses, choices) ?: return retryCommand(str(R.string.conv_choose_again))
        openApp(picked)
    }

    /** 앱을 연다고 말한 뒤 연다. 실행이 막히면(미설치·사용 중지·주행 제한) 그 이유를 말한다. */
    private fun openApp(target: FavoriteApp) {
        act {
            hooks?.let { test ->
                // 테스트: 앱을 실제로 열지 않고, 실행할 화면이 있는지만 확인한다
                val activity = app.catalog.resolveLaunchActivity(target)
                test.onAction("launch", "${target.packageName} activity=${activity?.name}")
                return@act if (activity != null) finishQuietly(End.LAUNCHED) else finish(str(R.string.conv_app_not_installed, target.displayName), End.ERROR)
            }
            val result = app.launcher.launch(target)
            Log.i(TAG, "launch ${target.packageName}: ${result::class.simpleName}")
            when (result) {
                is LaunchResult.Launched -> finishQuietly(End.LAUNCHED)
                is LaunchResult.NotInstalled -> finish(str(R.string.conv_app_not_installed, target.displayName), End.ERROR)
                is LaunchResult.Disabled -> finish(str(R.string.conv_app_disabled, target.displayName), End.ERROR)
                is LaunchResult.BlockedWhileDriving -> finish(str(R.string.conv_app_blocked, target.displayName), End.ERROR)
                else -> finish(str(R.string.conv_app_failed, target.displayName), End.ERROR)
            }
        }
    }

    // endregion

    // region 메시지 읽기·답장

    /** 메시지 기능을 쓸 수 없으면 false. 알림 접근이 없으면 허용 화면을 띄우고, 허용하면 [retry]로 같은 명령을 다시 한다. */
    private fun messagesUsable(retry: () -> Unit): Boolean {
        when (messages.status) {
            MessageAccess.Status.UNSUPPORTED -> finish(str(R.string.conv_messages_unsupported), End.ERROR)
            MessageAccess.Status.NEEDS_ACCESS ->
                withPermission(PermissionGate.Kind.NOTIFICATION_ACCESS, R.string.conv_permission_notifications, retry) {
                    finish(str(R.string.conv_messages_need_access), End.ERROR, Action.NOTIFICATION_ACCESS)
                }
            MessageAccess.Status.NOT_CONNECTED, MessageAccess.Status.READY -> return true
        }
        return false
    }

    private fun readMessage(appName: String?) {
        if (!messagesUsable { readMessage(appName) }) return
        val run = ++generation
        update(phase = Phase.WORKING)
        worker.execute {
            val selection = MessageSelector.latest(allMessages(), appName)
            main.post {
                if (run != generation) return@post
                when (selection) {
                    is MessageSelection.Found -> {
                        message = selection.message
                        draft = null
                        readingApp = false
                        Log.i(TAG, "read message from ${selection.message.packageName} canReply=${selection.message.canReply}")
                        readAloud(selection.message, intro = str(R.string.conv_reading))
                    }
                    is MessageSelection.NoneForApp -> finish(str(R.string.conv_no_messages_for_app, selection.app), End.DONE)
                    MessageSelection.None -> finish(
                        str(if (messages.status == MessageAccess.Status.READY) R.string.conv_no_messages else R.string.conv_messages_not_connected),
                        End.DONE,
                    )
                }
            }
        }
    }

    /**
     * "당근에서 온 알림 읽어줘": 그 앱의 가장 최근 알림을 읽는다. 메시지 알림이 아니어도 읽는다 (기기 안에서만, 저장하지 않음).
     * 알림이 없으면 없다고 말한다.
     */
    private fun readAppNotifications(label: String, packageName: String) {
        if (!messagesUsable { readAppNotifications(label, packageName) }) return
        val run = ++generation
        update(phase = Phase.WORKING)
        worker.execute {
            val found = appNotifications(packageName).maxByOrNull { it.postedAt }
            main.post {
                if (run != generation) return@post
                Log.i(TAG, "app notification found=${found != null} package=$packageName")
                if (found == null) return@post finish(str(R.string.conv_no_app_notifications, label), End.DONE)
                message = found
                draft = null
                readingApp = true
                readAloud(found, intro = str(R.string.conv_reading_app, label))
            }
        }
    }

    /** 메시지를 읽는다. 보낸 사람 이름은 알림에 있는 그대로 말한다 ("동진님" → "동진님의 메시지"). */
    private fun readAloud(current: IncomingMessage, intro: String?) {
        val group = current.conversation
        // 앱을 지정해 읽은, 답장할 수 없는 알림: 메시지가 아니라 알림이라고 읽는다
        if (readingApp && !current.canReply) {
            val body = if (current.sender.isEmpty()) {
                str(R.string.conv_read_notification_untitled, current.appLabel, current.text)
            } else {
                str(R.string.conv_read_notification, current.appLabel, current.sender, current.text)
            }
            return ask(listOfNotNull(intro, body, str(R.string.conv_after_read_notification)).joinToString(" "), Step.AFTER_READ)
        }
        val body = if (group != null) {
            str(R.string.conv_read_message_group, current.appLabel, current.sender, current.text, group)
        } else {
            str(R.string.conv_read_message, current.appLabel, current.sender, current.text)
        }
        val tail = str(if (current.canReply) R.string.conv_offer_reply else R.string.conv_after_read_no_reply)
        val text = listOfNotNull(intro, body, tail).joinToString(" ")
        ask(text, Step.AFTER_READ)
    }

    /** 답장이 가는 곳: 단체방이면 방 이름, 1:1이면 보낸 사람. */
    private fun recipient(current: IncomingMessage): String =
        current.conversation?.let { str(R.string.conv_room, it) } ?: current.sender

    private fun startReply(body: String?) {
        if (messages.status == MessageAccess.Status.UNSUPPORTED) return finish(str(R.string.conv_messages_unsupported), End.ERROR)
        val current = message ?: return ask(str(R.string.conv_reply_without_message), Step.COMMAND)
        if (!current.canReply) return finish(str(R.string.conv_reply_not_supported, current.appLabel), End.ERROR)
        confirmRetried = false
        if (body == null) {
            ask(str(R.string.conv_ask_reply_body, recipient(current)), Step.REPLY_BODY)
        } else {
            draft = body
            ask(str(R.string.conv_confirm_reply, recipient(current), body), Step.CONFIRM)
        }
    }

    private fun onReplyBody(heard: String) {
        val current = message ?: return finish(str(R.string.conv_reply_without_message), End.ERROR)
        if (CommandParser.parse(heard) == VoiceCommand.Repeat) {
            return ask(lastPrompt ?: str(R.string.conv_ask_reply_body, recipient(current)), Step.REPLY_BODY)
        }
        val body = CommandParser.parseReplyBody(heard) ?: return finish(str(R.string.conv_reply_cancelled), End.CANCELLED)
        draft = body
        confirmRetried = false
        // 받는 사람과 내용을 함께 확인한다
        ask(str(R.string.conv_confirm_reply, recipient(current), body), Step.CONFIRM)
    }

    private fun onConfirmation(hypotheses: List<String>) {
        if (CommandParser.parse(hypotheses.first()) == VoiceCommand.Repeat) {
            val current = message ?: return askConfirmationAgain()
            return ask(str(R.string.conv_confirm_reply, recipient(current), draft.orEmpty()), Step.CONFIRM)
        }
        // 후보 중 하나라도 '아니'면 보내지 않는다. '응'은 첫 후보 기준.
        val answers = hypotheses.map(CommandParser::parseConfirmation)
        when {
            Confirmation.NO in answers -> cancelReply()
            answers.first() == Confirmation.YES -> send()
            else -> askConfirmationAgain()
        }
    }

    private fun askConfirmationAgain() {
        val body = draft
        val current = message
        if (confirmRetried || body == null || current == null) {
            draft = null
            return finish(str(R.string.conv_reply_not_sent), End.NO_RESPONSE)
        }
        confirmRetried = true
        ask(str(R.string.conv_confirm_again, recipient(current), body), Step.CONFIRM)
    }

    private fun send() {
        val current = message ?: return
        val body = draft ?: return
        hooks?.let { test ->
            // 테스트: 답장을 절대 보내지 않는다
            test.onAction("reply_blocked", current.packageName)
            draft = null
            return finish(str(R.string.conv_reply_cancelled), End.CANCELLED)
        }
        val run = ++generation
        stt.cancel()
        speaker.stop()
        update(phase = Phase.WORKING, level = 0)
        worker.execute {
            val result = messages.reply(current, body)
            main.post {
                if (run != generation) return@post
                draft = null
                Log.i(TAG, "reply result=$result")
                when (result) {
                    MessageAccess.ReplyResult.SENT -> finish(str(R.string.conv_reply_sent, recipient(current)), End.DONE)
                    MessageAccess.ReplyResult.MESSAGE_GONE -> finish(str(R.string.conv_reply_gone), End.ERROR)
                    MessageAccess.ReplyResult.NO_REPLY_ACTION ->
                        finish(str(R.string.conv_reply_not_supported, current.appLabel), End.ERROR)
                    MessageAccess.ReplyResult.NO_ACCESS ->
                        finish(str(R.string.conv_messages_need_access), End.ERROR, Action.NOTIFICATION_ACCESS)
                    MessageAccess.ReplyResult.FAILED -> finish(str(R.string.conv_reply_failed), End.ERROR)
                }
            }
        }
    }

    // endregion

    // region 사람 이름으로 찾기

    /**
     * 말한 이름과 비슷한 사람을 실제로 받은 메시지의 보낸 사람·단체방 이름과 (허용했으면) 연락처 이름에서 찾는다.
     * 정확히 같으면 바로, 하나만 비슷하면 확인한 뒤, 여럿이면 누구인지 묻고, 비슷한 이름이 없으면 다시 묻는다.
     */
    private fun findPerson(words: List<String>, request: PersonRequest, heard: String, classify: Boolean = false) {
        if (!messagesUsable { findPerson(words, request, heard, classify) }) return
        val run = ++generation
        update(phase = Phase.WORKING)
        val registered = app.favoriteApps.favorites
        worker.execute {
            val current = allMessages()
            // 메시지 읽기: 이름이 설치된 앱인지 사람인지 먼저 가린다 ("당근 메시지 읽어줘"의 당근은 앱)
            val target = if (classify) classifyRead(heard, words, current, registered) else null
            if (target != null && target !is ReadTarget.Person) {
                main.post { if (run == generation) onReadTarget(target, words, request, heard) }
                return@execute
            }
            val app = when {
                target is ReadTarget.Person -> target.app
                request is PersonRequest.Reply -> null
                else -> request.app ?: words.firstOrNull { word -> isAppWord(word, current) }
            }
            val query = (target as? ReadTarget.Person)?.query ?: PersonCommandParser.nameQuery(words, app)
            val candidates = if (query.isEmpty()) null else candidateNames(current, app)
            val match = candidates?.let { KoreanNameMatcher.resolve(query, it.all) }
            main.post {
                if (run != generation) return@post
                // 이름 없이 앱만 말함 ("문자 읽어줘"): 기존 읽기
                if (candidates == null || match == null) return@post readMessage(app)
                person = when (request) {
                    is PersonRequest.Read -> PersonRequest.Read(app)
                    is PersonRequest.Open -> PersonRequest.Open(app)
                    is PersonRequest.Reply -> request
                }
                // 이름은 개인 정보라 결과 종류와 후보 출처별 개수만 남긴다
                Log.i(
                    TAG,
                    "person match=${match::class.simpleName} messages=${current.size} fromMessages=${candidates.fromMessages.size} " +
                        "registered=${candidates.registered.size} contactsPermission=${candidates.contactsPermission} " +
                        "contacts=${candidates.contacts.size}",
                )
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "person debug heard=\"$heard\" words=$words app=$app query=\"$query\" candidates=${candidates.all} match=$match")
                }
                // 찾지 못했고 연락처 권한이 없으면: 권한 창을 띄우고, 허용하면 연락처까지 넣어 다시 찾는다.
                // (카카오톡 친구 이름은 연락처에 없을 수 있어 거부해도 그대로 이어간다)
                if ((candidates.all.isEmpty() || match == NameMatch.None) && !candidates.contactsPermission) {
                    return@post withPermission(
                        PermissionGate.Kind.CONTACTS,
                        R.string.conv_permission_contacts,
                        onGranted = { findPerson(words, request, heard, classify) },
                    ) { onCandidates(candidates, match, query) }
                }
                onCandidates(candidates, match, query)
            }
        }
    }

    /** 백그라운드 스레드. 읽기 명령의 이름을 설치된 앱 이름·사람 이름 후보와 함께 비교해 대상을 정한다. */
    private fun classifyRead(heard: String, words: List<String>, current: List<IncomingMessage>, registered: List<FavoriteApp>): ReadTarget {
        val apps = installedApps(registered).map { it.first }
        val people = candidateNames(current, null).all
        val sentence = PersonCommandParser.sentence(heard, wakeWordName)
        val target = TargetClassifier.classify(sentence, words, apps, people) { isAppWord(it, current) }
        Log.i(TAG, "read target=${target::class.simpleName} apps=${apps.size} people=${people.size}")
        if (BuildConfig.DEBUG) Log.d(TAG, "read debug heard=\"$heard\" words=$words target=$target")
        return target
    }

    private fun onReadTarget(target: ReadTarget, words: List<String>, request: PersonRequest, heard: String) {
        when (target) {
            is ReadTarget.App ->
                if (target.sure) {
                    readAppNotifications(target.app.label, target.app.packageName)
                } else {
                    val read = { readAppNotifications(target.app.label, target.app.packageName) }
                    askClarify(str(R.string.conv_confirm_app_read, target.app.label), listOf(ClarifyAnswer.READ_WORDS to read), onYes = read)
                }
            is ReadTarget.Either -> askClarify(
                str(R.string.conv_ask_app_or_person, target.app.label, target.person),
                listOf(
                    ClarifyAnswer.APP_WORDS to { readAppNotifications(target.app.label, target.app.packageName) },
                    ClarifyAnswer.PERSON_WORDS to { findPerson(words, request, heard) },
                ),
            )
            is ReadTarget.NoApp -> finish(str(R.string.conv_read_app_not_found, target.name), End.DONE)
            is ReadTarget.Person -> findPerson(words, request, heard)
        }
    }

    private fun onCandidates(candidates: Candidates, match: NameMatch, query: String) {
        // 후보가 하나도 없으면 비슷한 이름 문제가 아니다: 다시 묻지 않고 이유를 말한다
        if (candidates.all.isEmpty()) {
            clearPerson()
            val reason = if (candidates.contactsPermission) R.string.conv_person_no_candidates else R.string.conv_person_no_candidates_contacts_off
            return finish(str(reason, query), End.DONE)
        }
        onNameMatch(match, query)
    }

    private fun onNameMatch(match: NameMatch, query: String) {
        when (match) {
            is NameMatch.Exact -> usePerson(match.name)
            is NameMatch.Likely -> {
                personName = match.name
                ask(str(R.string.conv_person_confirm, match.name), Step.PERSON_CONFIRM)
            }
            is NameMatch.Ambiguous -> {
                personChoices = match.names
                choiceRetried = false
                ask(str(R.string.conv_person_choose, match.names.joinToString(", ")), Step.PERSON_CHOOSE)
            }
            // 임의로 고르지 않는다
            NameMatch.None -> askNameAgain(str(R.string.conv_person_not_found, query), str(R.string.conv_person_not_found_end, query))
        }
    }

    /** 이름을 한 번은 다시 묻고, 두 번째면 끝낸다. */
    private fun askNameAgain(again: String, end: String) {
        if (nameRetried) {
            clearPerson()
            return finish(end, End.DONE)
        }
        nameRetried = true
        ask(again, Step.PERSON_NAME)
    }

    /** "동진님 말씀하시는 거죠?"에 대한 대답. */
    private fun onPersonConfirm(hypotheses: List<String>) {
        val name = personName ?: return finish(str(R.string.conv_ok), End.DONE)
        if (CommandParser.parse(hypotheses.first()) == VoiceCommand.Repeat) return ask(str(R.string.conv_person_confirm, name), Step.PERSON_CONFIRM)
        val answers = hypotheses.map(CommandParser::parseConfirmation)
        when {
            Confirmation.NO in answers -> {
                personName = null
                askNameAgain(str(R.string.conv_person_ask), str(R.string.conv_ok))
            }
            answers.first() == Confirmation.YES -> usePerson(name)
            // '응/아니' 대신 이름을 다시 말한 경우
            else -> onPersonName(hypotheses)
        }
    }

    /** "누구인가요?"에 대한 대답: 후보 이름 또는 "첫 번째". */
    private fun onPersonChoice(hypotheses: List<String>) {
        if (CommandParser.parse(hypotheses.first()) == VoiceCommand.Cancel) return cancelPerson()
        pickPerson(hypotheses, personChoices)?.let { return usePerson(it) }
        if (choiceRetried) {
            clearPerson()
            return finish(str(R.string.conv_ok), End.DONE)
        }
        choiceRetried = true
        ask(str(R.string.conv_person_choose_again), Step.PERSON_CHOOSE)
    }

    /** 이름을 다시 말해 달라고 한 뒤의 대답. */
    private fun onPersonName(hypotheses: List<String>) {
        val heard = hypotheses.first()
        if (CommandParser.parse(heard) == VoiceCommand.Cancel) return cancelPerson()
        val request = person ?: return finish(str(R.string.conv_ok), End.DONE)
        val query = PersonCommandParser.nameAnswer(heard, wakeWordName)
        if (query.isEmpty()) return askNameAgain(str(R.string.conv_person_ask), str(R.string.conv_ok))
        // 확인 질문에 이름으로 대답하는 것도 한 번까지만 (같은 질문이 반복되지 않게)
        if (step == Step.PERSON_CONFIRM && nameRetried) return cancelPerson()
        nameRetried = true
        findPerson(listOf(query), request, heard)
    }

    private fun cancelPerson() {
        clearPerson()
        finish(str(R.string.conv_ok), End.CANCELLED)
    }

    /**
     * 그 사람의 메시지 알림에 메시지 앱이 넣어 둔 링크로 대화방을 연다 (알림을 누른 것과 같음).
     * 메시지 앱 화면을 자동으로 조작하지 않는다. 열리면 다른 앱을 연 것처럼 대화를 끝내고 호출어 대기로 돌아간다.
     */
    private fun openConversation(target: IncomingMessage, name: String) {
        val opener = messages as? NotificationMessageAccess ?: return finish(str(R.string.conv_messages_unsupported), End.ERROR)
        act {
            hooks?.let { test ->
                // 테스트: 대화방을 실제로 열지 않는다
                test.onAction("open_room", target.packageName)
                return@act finishQuietly(End.LAUNCHED)
            }
            val run = ++generation
            update(phase = Phase.WORKING)
            worker.execute {
                val opened = opener.openConversation(target)
                main.post {
                    if (run != generation) return@post
                    if (opened) finishQuietly(End.LAUNCHED) else finish(str(R.string.conv_open_failed, name), End.ERROR)
                }
            }
        }
    }

    /** 확인한 사람의 실제 메시지 알림으로 읽기·답장·대화방 열기를 이어간다. 알림이 없으면 없다고 말한다 (만들어 내지 않는다). */
    private fun usePerson(name: String) {
        val request = person ?: return finish(str(R.string.conv_ok), End.DONE)
        val run = ++generation
        update(phase = Phase.WORKING)
        worker.execute {
            val current = allMessages()
            val app = request.app
            val pool = if (app == null) current else current.filter { it.packageName in appPackages(app, current) }
            val latest = pool.filter { isFrom(it, name) }.maxByOrNull { it.postedAt }
            main.post {
                if (run != generation) return@post
                clearPerson()
                Log.i(TAG, "person message found=${latest != null} request=${request::class.simpleName}")
                when {
                    latest == null && request is PersonRequest.Reply -> finish(str(R.string.conv_reply_needs_message, name), End.DONE)
                    latest == null && request is PersonRequest.Open -> finish(str(R.string.conv_open_needs_message, name), End.DONE)
                    latest == null -> finish(str(R.string.conv_no_messages_from, name), End.DONE)
                    request is PersonRequest.Open -> openConversation(latest, name)
                    request is PersonRequest.Reply -> {
                        message = latest
                        draft = null
                        readingApp = false
                        startReply(request.body)
                    }
                    else -> {
                        message = latest
                        draft = null
                        readingApp = false
                        readAloud(latest, intro = str(R.string.conv_reading_from, name))
                    }
                }
            }
        }
    }

    /** 검색 후보와 출처: 후보가 없을 때 알림이 없는 건지 연락처 권한이 없는 건지 가리기 위해 나눠 둔다. */
    private class Candidates(
        val fromMessages: List<String>,
        val registered: List<String>,
        val contactsPermission: Boolean,
        val contacts: List<String>,
    ) {
        val all = (fromMessages + registered + contacts).distinct()
    }

    /**
     * 백그라운드 스레드. 보낸 사람·단체방 이름 + 직접 등록한 이름(카카오톡 친구 등) + (허용했으면) 연락처 이름.
     * 카카오톡 친구 목록은 다른 앱이 읽을 수 없어 후보에 없다 ([KnownNames] 참고).
     */
    private fun candidateNames(current: List<IncomingMessage>, app: String?): Candidates {
        val pool = if (app == null) current else current.filter { it.packageName in appPackages(app, current) }
        val fromMessages = pool.sortedByDescending { it.postedAt }.flatMap { listOfNotNull(it.sender, it.conversation) }.distinct()
        return Candidates(fromMessages, KnownNames.all(context), ContactNames.hasPermission(context), ContactNames.load(context))
    }

    private fun clearPerson() {
        person = null
        personName = null
        personChoices = emptyList()
        nameRetried = false
        choiceRetried = false
    }

    // endregion

    /**
     * 마지막 말을 하고 대화를 끝낸다. 연속 대화 중이면 끝내지 않고 그 말을 한 뒤 다음 명령을 듣는다.
     * [fatal]: 음성 인식·마이크를 쓸 수 없는 오류 — 연속 대화 중이어도 끝낸다 (계속 들으려 하면 같은 오류가 반복됨).
     */
    private fun finish(text: String, end: End, action: Action = Action.NONE, fatal: Boolean = false) {
        if (continuous && !fatal && action == Action.NONE) {
            if (end == End.NO_RESPONSE) return awaitNext()
            say(text) { awaitNext() }
            return
        }
        continuous = false
        say(text) { update(phase = Phase.ENDED, end = end, level = 0) }
        update(action = action)
    }

    private fun finishQuietly(end: End) {
        val leaving = continuous && end == End.LAUNCHED
        // 다른 앱 위 창: 앱을 연 뒤에도 그 앱 위에서 다음 명령을 듣는다
        if (leaving && !handOffOnLeave) return awaitNext()
        generation++
        // 전체 화면 음성비서: continuous=true로 끝내 화면이 다른 앱 위 창에 대화를 넘긴다
        if (!leaving) continuous = false
        update(phase = Phase.ENDED, end = end, level = 0)
    }

    // region 연속 대화

    /** 한 명령을 마침: 그 명령의 내용은 지우고 호출어 없이 다음 명령을 듣는다. */
    private fun awaitNext() {
        message = null
        draft = null
        choices = emptyList()
        clearPerson()
        clarify = null
        clarifyRetried = false
        misses = 0
        readingApp = false
        pendingTap = false
        pendingReminder = null
        idleUntil = SystemClock.elapsedRealtime() + CONTINUOUS_IDLE_MS
        Log.i(TAG, "continuous: waiting for next command")
        listen(Step.COMMAND)
    }

    /** "쁘이 그만"·"대화 종료" 또는 한동안 말이 없음: 연속 대화를 끝내고 호출어 대기로 돌아간다. */
    private fun endContinuous(timeout: Boolean = false) {
        continuous = false
        Log.i(TAG, "continuous: ended timeout=$timeout")
        finish(str(if (timeout) R.string.conv_continuous_timeout else R.string.conv_continuous_end, wakeName()), End.DONE)
    }

    // endregion

    // region 화면 조작 (접근성 서비스)

    private fun onScreenCommand(command: ScreenCommand) {
        if (command == ScreenCommand.EndConversation) {
            return if (continuous) endContinuous() else finish(str(R.string.conv_ok), End.CANCELLED)
        }
        Log.i(TAG, "screen command=${command::class.simpleName}")
        hooks?.let { test ->
            test.onAction("screen", command::class.simpleName.orEmpty())
            return finish(str(R.string.conv_ok), End.DONE)
        }
        val service = ScreenControlService.instance ?: return needScreenControl()
        val actions = ScreenActions(service)
        // 전체 화면 음성비서에서는 지금 화면이 쁘이 자신이다: 홈·뒤로·최근 앱만 하고 대화는 다른 앱 위 창으로 넘긴다
        if (handOffOnLeave) {
            val result = when (command) {
                ScreenCommand.Back -> actions.back()
                ScreenCommand.Home -> actions.home()
                ScreenCommand.Recents -> actions.recents()
                else -> return finish(str(R.string.control_own_screen), End.DONE)
            }
            return if (result == ScreenResult.Done) finishQuietly(End.LAUNCHED) else finish(str(R.string.control_failed), End.ERROR)
        }
        val failed = str(R.string.control_failed)
        when (command) {
            ScreenCommand.Back -> onScreenResult(actions, actions.back(), str(R.string.control_back), failed)
            ScreenCommand.Home -> onScreenResult(actions, actions.home(), str(R.string.control_home), failed)
            ScreenCommand.Recents -> onScreenResult(actions, actions.recents(), str(R.string.control_recents), failed)
            ScreenCommand.ScrollDown -> onScreenResult(actions, actions.scroll(down = true), str(R.string.control_scrolled_down), failed)
            ScreenCommand.ScrollUp -> onScreenResult(actions, actions.scroll(down = false), str(R.string.control_scrolled_up), failed)
            ScreenCommand.Search -> onScreenResult(actions, actions.search(), str(R.string.control_searched), str(R.string.control_no_search))
            is ScreenCommand.Type -> {
                val run = ++generation
                update(phase = Phase.WORKING, heard = command.text, level = 0)
                actions.type(command.text) { result ->
                    if (run != generation) return@type
                    if (result == ScreenResult.Done && command.thenSearch) {
                        onScreenResult(actions, actions.search(), str(R.string.control_searched), str(R.string.control_no_search))
                    } else {
                        onScreenResult(actions, result, str(R.string.control_typed, command.text), str(R.string.control_no_input))
                    }
                }
            }
            is ScreenCommand.Tap -> {
                val label = command.label
                if (label == null) {
                    // 무엇을 누를지 모름: 임의로 누르지 않고 묻는다
                    pendingTap = true
                    return ask(str(R.string.control_ask_target), Step.COMMAND)
                }
                onScreenResult(actions, actions.tap(label), str(R.string.control_tapped), str(R.string.control_not_found, label))
            }
            ScreenCommand.EndConversation -> Unit
        }
    }

    // region 알림·타이머

    private fun onReminder(request: ReminderRequest) {
        Log.i(TAG, "reminder request=${request::class.simpleName}")
        when (request) {
            is ReminderRequest.Schedule -> scheduleReminder(request)
            is ReminderRequest.NeedTime -> {
                // 시간을 추측하지 않고 묻는다
                pendingReminder = request.draft
                val prompt = when {
                    request.past -> R.string.reminder_past
                    request.draft.timer -> R.string.reminder_ask_timer
                    else -> R.string.reminder_ask_time
                }
                ask(str(prompt), Step.COMMAND)
            }
            is ReminderRequest.NeedAmPm -> askClarify(
                str(R.string.reminder_ask_ampm, request.hour),
                listOf(
                    listOf("오전", "아침", "새벽", "am") to { onReminder(ReminderParser.withAmPm(request, pm = false, LocalDateTime.now())) },
                    listOf("오후", "저녁", "밤", "낮", "pm") to { onReminder(ReminderParser.withAmPm(request, pm = true, LocalDateTime.now())) },
                ),
            )
            ReminderRequest.CancelLast -> {
                val cancelled = Reminders.cancelLast(context) ?: return finish(str(R.string.reminder_none), End.DONE)
                if (cancelled.timer) return finish(str(R.string.reminder_timer_cancelled), End.DONE)
                val at = java.time.Instant.ofEpochMilli(cancelled.at).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                finish(str(R.string.reminder_cancelled, ReminderParser.spokenAt(LocalDate.now(), at)), End.DONE)
            }
        }
    }

    /** 알림 권한을 확인한 뒤 등록한다. 실제로 등록됐을 때만 "2분 뒤에 알려드릴게요"라고 대답한다. */
    private fun scheduleReminder(request: ReminderRequest.Schedule) {
        withPermission(
            PermissionGate.Kind.NOTIFICATIONS,
            R.string.reminder_need_notifications,
            onGranted = { registerReminder(request) },
            onDenied = { finish(str(R.string.reminder_notifications_off), End.ERROR) },
        )
    }

    private fun registerReminder(request: ReminderRequest.Schedule) {
        // 말하는 사이 시간이 흐른 만큼은 그대로 둔다 (말한 시점 기준)
        val result = Reminders.schedule(context, request.at, request.message, request.timer)
        hooks?.onAction("reminder", result.name)
        when (result) {
            Reminders.Result.SCHEDULED -> finish(
                str(if (request.timer) R.string.reminder_timer_set else R.string.reminder_set, request.spokenWhen),
                End.DONE,
            )
            Reminders.Result.NEED_EXACT_ALARM -> {
                continuous = false
                say(str(R.string.reminder_need_exact)) {
                    try {
                        context.startActivity(Reminders.exactAlarmSettings(context))
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "exact alarm settings unavailable")
                    }
                    update(phase = Phase.ENDED, end = End.ERROR, level = 0)
                }
            }
            Reminders.Result.NOTIFICATIONS_BLOCKED -> finish(str(R.string.reminder_notifications_blocked), End.ERROR)
            Reminders.Result.FAILED -> finish(str(R.string.reminder_failed), End.ERROR)
        }
    }

    // endregion

    /** "유튜브에서 판다 검색해 줘": 앱 이름을 찾은 뒤(백그라운드) 그 앱에서 검색. 앱을 못 찾으면 기존 앱 열기 처리로. */
    private fun startAppSearch(search: AppSearch, heard: String, commands: List<VoiceCommand>) {
        Log.i(TAG, "app search")
        if (BuildConfig.DEBUG) Log.d(TAG, "app search debug app=\"${search.app}\" query=\"${search.query}\"")
        val registered = app.favoriteApps.favorites
        val run = ++generation
        update(phase = Phase.WORKING, level = 0)
        worker.execute {
            val found = (resolver.resolve(search.app, registered) as? AppCommandResolver.Resolution.Found)?.app
                ?: installedApps(registered).let { installed ->
                    (AppNameMatcher.find(search.app, installed.map { it.first }) as? AppHit.Exact)?.let { hit -> installed.first { it.first == hit.app }.second }
                }
            if (BuildConfig.DEBUG) Log.d(TAG, "app search debug resolved=${found?.packageName}")
            main.post {
                if (run != generation) return@post
                // "유튜브에서 아이유 노래 틀어줘": 검색 후 실제 영상 재생 (검색 명령은 아래 기존 처리)
                if (search.play && (found?.packageName == YOUTUBE_PACKAGE || isYouTubeName(search.app))) {
                    return@post playOnYouTube(search.query, found?.packageName)
                }
                if (found == null) {
                    // 앱이 없으면 웹 검색으로 대신 연다 (유튜브·네이버지도 등)
                    val web = webSearchUri(search.app, search.query)
                        ?: return@post finish(str(R.string.conv_app_not_found, search.app), End.ERROR)
                    return@post act {
                        if (startView(web, null)) finishQuietly(End.LAUNCHED) else finish(str(R.string.conv_app_not_found, search.app), End.ERROR)
                    }
                }
                searchInApp(found, search.query)
            }
        }
    }

    /**
     * 재생 중인 미디어 앱에 명령을 보낸다. 성공하면 안내 없이 바로 끝낸다(음악을 방해하지 않도록).
     * 앱이 지원하지 않거나 실제로 바뀌지 않았으면(다음 곡 없음 등) 그렇다고 말한다.
     */
    private fun onMediaCommand(command: MediaCommand) {
        Log.i(TAG, "media command=$command")
        hooks?.let { test ->
            test.onAction("media", command.name)
            return finishQuietly(End.DONE)
        }
        when (val result = MediaControl(context).send(command)) {
            MediaControl.Result.NoAccess -> finish(str(R.string.media_no_access), End.ERROR)
            MediaControl.Result.NoSession -> finish(str(R.string.media_no_session), End.ERROR)
            is MediaControl.Result.Unsupported -> finish(str(R.string.media_unsupported, result.app), End.ERROR)
            is MediaControl.Result.Sent -> {
                val run = ++generation
                update(phase = Phase.WORKING, level = 0)
                main.postDelayed({
                    if (run != generation) return@postDelayed
                    val applied = runCatching { result.verify() }.getOrDefault(false)
                    Log.i(TAG, "media command=$command applied=$applied")
                    if (applied) {
                        finishQuietly(End.DONE)
                    } else {
                        val what = str(
                            when (command) {
                                MediaCommand.NEXT -> R.string.media_next
                                MediaCommand.PREVIOUS -> R.string.media_previous
                                MediaCommand.PAUSE -> R.string.media_pause
                                MediaCommand.PLAY -> R.string.media_play
                            },
                        )
                        finish(str(R.string.media_not_applied, result.app, what), End.ERROR)
                    }
                }, MediaControl.VERIFY_DELAY_MS)
            }
        }
    }

    private fun isYouTubeName(name: String): Boolean =
        name.replace(" ", "").lowercase().removeSuffix("앱") in setOf("유튜브", "유투브", "youtube")

    /**
     * 유튜브 재생: YouTube Data API(search.list)로 검색어에 맞는 첫 영상 ID를 받아 그 영상을 유튜브에서 연다(재생).
     * 일반 Intent에는 "검색 후 첫 결과 재생"이 없어서 공식 API로 영상을 고른다.
     * API 키가 없거나 실패하면 재생했다고 말하지 않고, 검색 결과만 열고 그렇다고 알린다.
     */
    private fun playOnYouTube(query: String, packageName: String?) {
        Log.i(TAG, "youtube play: key=${BuildConfig.YOUTUBE_API_KEY.isNotEmpty()}")
        val run = ++generation
        update(phase = Phase.WORKING, level = 0)
        worker.execute {
            val video = if (BuildConfig.YOUTUBE_API_KEY.isEmpty()) null else youtubeFirstVideo(query)
            main.post {
                if (run != generation) return@post
                val q = Uri.encode(query)
                if (video != null) {
                    act {
                        val uri = Uri.parse("https://www.youtube.com/watch?v=$video")
                        if (startView(uri, packageName ?: YOUTUBE_PACKAGE) || startView(uri, null)) { watchYouTubeAds(packageName ?: YOUTUBE_PACKAGE); endAfterPlayback() }
                        else finish(str(R.string.conv_app_failed, "YouTube"), End.ERROR)
                    }
                } else if (ScreenControlService.instance != null) {
                    // API 키 없음: 검색 결과를 연 뒤 화면 조작(접근성)으로 첫 번째 영상을 누른다
                    act {
                        val uri = Uri.parse("https://www.youtube.com/results?search_query=$q")
                        val pkg = packageName ?: YOUTUBE_PACKAGE
                        if (!startView(uri, pkg)) return@act finish(str(R.string.conv_app_failed, "YouTube"), End.ERROR)
                        val detached = handOffOnLeave
                        val tapRun = ++generation
                        if (detached) endAfterPlayback() else update(phase = Phase.WORKING, level = 0)
                        autoPlayFirstVideo(pkg) { playing ->
                            Log.i(TAG, "youtube first video: playing=$playing")
                            if (detached || tapRun != generation) return@autoPlayFirstVideo
                            // 성공(영상 화면으로 넘어감)은 말없이, 실패는 음성 없이 버튼 옆 짧은 표시만
                            if (playing != false) endAfterPlayback() else failQuietly(str(R.string.youtube_first_failed))
                        }
                    }
                } else {
                    say(str(R.string.youtube_play_search_only, query)) {
                        val uri = Uri.parse("https://www.youtube.com/results?search_query=$q")
                        if (startView(uri, packageName ?: YOUTUBE_PACKAGE) || startView(uri, null)) finishQuietly(End.LAUNCHED)
                        else finish(str(R.string.conv_app_failed, "YouTube"), End.ERROR)
                    }
                }
            }
        }
    }

    /**
     * 유튜브 검색 결과가 뜰 때까지 기다렸다가 첫 번째 영상을 누르고, 재생 여부를 미디어 세션으로 확인한다.
     * [onDone]: true = 재생 확인, false = 영상을 못 찾았거나 재생되지 않음, null = 눌렀지만 확인 불가(알림 접근 없음).
     * 대화 상태를 건드리지 않아 화면이 넘어가도 동작한다.
     */
    private fun autoPlayFirstVideo(pkg: String, onDone: (Boolean?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        val tap = object : Runnable {
            override fun run() {
                val service = ScreenControlService.instance ?: return onDone(false)
                val tapped = ScreenActions(service).tapFirstVideo(pkg)
                if (tapped != null) {
                    Log.i(TAG, "youtube first video: tapped")
                    watchYouTubeAds(pkg)
                    verifyPlayback(pkg, onDone)
                } else if (++tries < YOUTUBE_TAP_TRIES) {
                    handler.postDelayed(this, YOUTUBE_TAP_POLL_MS)
                } else {
                    onDone(false)
                }
            }
        }
        handler.postDelayed(tap, YOUTUBE_TAP_POLL_MS * 2)
    }

    /**
     * 영상 화면에 들어간 것만으로 성공으로 보지 않고 실제 재생 상태(미디어 세션)를 확인한다.
     * 일시정지 상태면 유튜브 세션에 재생(play)을 한 번만 보낸다. 광고·버퍼링을 고려해 몇 번 더 확인한 뒤 끝낸다(무한 반복 없음).
     */
    private fun verifyPlayback(pkg: String, onDone: (Boolean?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val media = MediaControl(context)
        var checks = 0
        var pressedPlay = false
        val check = object : Runnable {
            override fun run() {
                when (media.isPlaying(pkg)) {
                    true -> return onDone(true)
                    null -> return onDone(null) // 알림 접근 권한이 없어 확인 불가
                    false -> Unit
                }
                if (!pressedPlay && media.playIfPaused(pkg)) {
                    pressedPlay = true
                    Log.i(TAG, "youtube: paused after open, sent play once")
                }
                if (++checks < YOUTUBE_VERIFY_TRIES) handler.postDelayed(this, YOUTUBE_PLAY_CHECK_MS) else onDone(false)
            }
        }
        handler.postDelayed(check, YOUTUBE_PLAY_CHECK_MS)
    }

    /** 재생을 연 뒤 대화를 완전히 끝낸다: 연속 대화(음성 인식 반복)로 마이크를 붙잡지 않고, 호출어 감지로 돌아간다. */
    private fun endAfterPlayback() {
        continuous = false
        finishQuietly(End.LAUNCHED)
    }

    /**
     * 영상을 연 뒤 일정 시간 유튜브 화면을 지켜보다가, 유튜브가 공식 '광고 건너뛰기' 버튼을 보여 주면 누른다.
     * 버튼이 나오기 전(카운트다운)이나 건너뛸 수 없는 광고는 기다리기만 한다. 유튜브를 떠나면 멈춘다.
     */
    private fun watchYouTubeAds(pkg: String) {
        val handler = Handler(Looper.getMainLooper())
        val until = SystemClock.elapsedRealtime() + AD_WATCH_MS
        var away = 0
        val check = object : Runnable {
            override fun run() {
                val service = ScreenControlService.instance ?: return
                val foreground = service.rootInActiveWindow?.packageName?.toString()
                away = if (foreground == pkg) 0 else away + 1
                if (away >= AD_AWAY_LIMIT || SystemClock.elapsedRealtime() > until) return
                if (foreground == pkg && ScreenActions(service).tapSkipAd(pkg)) Log.i(TAG, "youtube: skipped ad")
                handler.postDelayed(this, AD_POLL_MS)
            }
        }
        handler.postDelayed(check, AD_POLL_MS)
    }

    /** 실패를 음성 없이 마친다. 플로팅 버튼 옆에 짧게 표시된다 ([Snapshot.assistant]). */
    private fun failQuietly(text: String) {
        generation++
        continuous = false
        update(phase = Phase.ENDED, assistant = text, end = End.ERROR, level = 0)
    }

    /** 백그라운드 스레드. YouTube Data API v3 search.list로 첫 영상 ID. 실패하면 null. */
    private fun youtubeFirstVideo(query: String): String? = try {
        val url = java.net.URL(
            "https://www.googleapis.com/youtube/v3/search?part=id&type=video&maxResults=1&regionCode=KR&relevanceLanguage=ko&safeSearch=moderate" +
                "&q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&key=" + BuildConfig.YOUTUBE_API_KEY,
        )
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        // API 키를 Android 앱으로 제한한 경우 필요한 헤더
        conn.setRequestProperty("X-Android-Package", context.packageName)
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "youtube api http ${conn.responseCode}")
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Regex("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{6,20})\"").find(body)?.groupValues?.get(1)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "youtube api failed: ${e.javaClass.simpleName}")
        null
    }

    /** "강남역 길 안내해 줘": 기본 지도 앱에서 목적지를 검색한다. 길 안내 시작은 지도 앱에서 사용자가 누른다. */
    private fun startNavigate(destination: String) {
        Log.i(TAG, "navigate")
        act {
            hooks?.let { test ->
                test.onAction("navigate", destination)
                return@act finishQuietly(End.LAUNCHED)
            }
            val q = Uri.encode(destination)
            val spokenApp = NavigateParser.lastApp
            // 네이버지도: 목적지 이름 → 좌표(기기 Geocoder) → nmap://navigation 으로 현재 위치에서 자동차 길 안내 바로 시작
            if (spokenApp == "네이버지도" || spokenApp == "네이버맵") {
                val run = ++generation
                worker.execute {
                    val place = geocode(destination)
                    main.post {
                        if (run != generation) return@post
                        startNaverNavigation(destination, place)
                    }
                }
                return@act
            }
            // 말한 지도 앱: 구글 지도는 바로 길 안내 시작, 카카오맵은 이름만으로는 경로를 못 열어 목적지 검색까지
            val named = when (spokenApp) {
                "네이버지도", "네이버맵" -> startView(Uri.parse("nmap://search?query=$q&appname=${context.packageName}"), "com.nhn.android.nmap")
                "카카오맵", "카카오지도" -> startView(Uri.parse("kakaomap://search?q=$q"), "net.daum.android.map")
                "구글지도", "구글맵" -> startView(Uri.parse("google.navigation:q=$q"), "com.google.android.apps.maps")
                else -> false
            }
            val ok = named || startView(Uri.parse("geo:0,0?q=$q"), null) ||
                startView(Uri.parse("https://map.naver.com/p/search/$q"), null)
            if (ok) finishQuietly(End.LAUNCHED) else finish(str(R.string.drive_no_map_app), End.ERROR)
        }
    }

    private sealed interface Place {
        data class Found(val lat: Double, val lng: Double) : Place
        data object Ambiguous : Place
        data object NotFound : Place
    }

    /** 백그라운드 스레드. 기기 Geocoder로 장소 이름 → 좌표. 결과가 서로 멀리 떨어진 여러 곳이면 모호함. */
    private fun geocode(name: String): Place = try {
        if (!android.location.Geocoder.isPresent()) {
            Place.NotFound
        } else {
            @Suppress("DEPRECATION")
            val results = android.location.Geocoder(context, java.util.Locale.KOREA).getFromLocationName(name, 5).orEmpty()
                .filter { it.hasLatitude() && it.hasLongitude() }
            val first = results.firstOrNull()
            when {
                first == null -> Place.NotFound
                results.drop(1).any { other ->
                    val d = FloatArray(1)
                    android.location.Location.distanceBetween(first.latitude, first.longitude, other.latitude, other.longitude, d)
                    d[0] > AMBIGUOUS_DISTANCE_M
                } -> Place.Ambiguous
                else -> Place.Found(first.latitude, first.longitude)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "geocode failed: ${e.javaClass.simpleName}")
        Place.NotFound
    }

    /**
     * 네이버지도 공식 URL Scheme(nmap://navigation)으로 자동차 길 안내를 바로 시작한다. 출발지는 네이버지도가 현재 위치로 잡는다.
     * 성공하면 말없이 끝낸다. 안내 시작 버튼이 있는 화면이 뜨면 화면 조작(켜져 있을 때)으로 그 버튼만 누른다.
     */
    private fun startNaverNavigation(destination: String, place: Place) {
        val q = Uri.encode(destination)
        when (place) {
            Place.Ambiguous -> return finish(str(R.string.navigate_ambiguous, destination), End.ERROR)
            Place.NotFound -> {
                // 좌표를 못 구함: 길 안내를 시작했다고 하지 않고 검색 화면만 연다
                val opened = startView(Uri.parse("nmap://search?query=$q&appname=${context.packageName}"), NAVER_MAP_PACKAGE)
                return finish(str(if (opened) R.string.navigate_no_coords else R.string.drive_no_map_app, destination), End.ERROR)
            }
            is Place.Found -> {
                val uri = Uri.parse(
                    "nmap://navigation?dlat=${place.lat}&dlng=${place.lng}&dname=$q&appname=${context.packageName}",
                )
                if (!startView(uri, NAVER_MAP_PACKAGE)) return finish(str(R.string.navigate_no_naver), End.ERROR)
                Log.i(TAG, "naver navigation started")
                finishQuietly(End.LAUNCHED)
                pressNaverStartIfShown()
            }
        }
    }

    /** 네이버지도가 경로 확인 화면을 띄운 경우에만 "안내 시작" 버튼을 누른다 (화면 조작이 꺼져 있으면 아무것도 안 함). */
    private fun pressNaverStartIfShown() {
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                val service = ScreenControlService.instance ?: return
                val root = service.rootInActiveWindow
                if (root?.packageName?.toString() == NAVER_MAP_PACKAGE) {
                    val actions = ScreenActions(service)
                    for (label in NAVER_START_LABELS) {
                        if (actions.tap(label) == ScreenResult.Done) {
                            Log.i(TAG, "naver navigation: pressed start")
                            return
                        }
                    }
                }
                if (++tries < NAVER_START_TRIES) handler.postDelayed(this, NAVER_START_POLL_MS)
            }
        }
        handler.postDelayed(check, NAVER_START_POLL_MS)
    }

    /** ACTION_VIEW 실행. 처리할 앱이 없거나 막히면 false. */
    private fun startView(uri: Uri, packageName: String?): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) intent.setPackage(packageName)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** 지도 앱 검색 딥링크. 지도 앱이 아니면 null. */
    private fun mapSearchUri(packageName: String, query: String): Uri? {
        val q = Uri.encode(query)
        return when (packageName) {
            "com.nhn.android.nmap" -> Uri.parse("nmap://search?query=$q&appname=${context.packageName}")
            "net.daum.android.map" -> Uri.parse("kakaomap://search?q=$q")
            "com.google.android.apps.maps" -> Uri.parse("geo:0,0?q=$q")
            else -> null
        }
    }

    /** 앱이 설치돼 있지 않을 때 쓸 웹 검색 주소. */
    private fun webSearchUri(appName: String, query: String): Uri? {
        val name = appName.replace(" ", "").lowercase().removeSuffix("앱")
        val q = Uri.encode(query)
        return when (name) {
            "유튜브", "유투브", "youtube" -> Uri.parse("https://www.youtube.com/results?search_query=$q")
            "네이버지도", "네이버맵", "지도" -> Uri.parse("https://map.naver.com/p/search/$q")
            "카카오맵", "카카오지도" -> Uri.parse("https://map.kakao.com/?q=$q")
            "구글지도", "구글맵" -> Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")
            else -> null
        }
    }

    /**
     * [target] 앱에서 [query] 검색.
     * 1) 그 앱이 이미 앞에 열려 있고 화면 조작이 켜져 있으면: 다시 실행하지 않고 지금 화면의 검색창에 써서 검색.
     * 2) 앱이 검색 요청(ACTION_SEARCH)을 받으면(유튜브 등): 검색 결과 화면으로 바로 연다.
     * 3) 아니면 앱을 열고, 화면 조작으로 검색창을 찾아 검색한다.
     */
    private fun searchInApp(target: FavoriteApp, query: String) {
        val service = ScreenControlService.instance
        val foreground = service?.rootInActiveWindow?.packageName?.toString()
        Log.i(TAG, "app search: alreadyOpen=${foreground == target.packageName} control=${service != null}")
        if (service != null && foreground == target.packageName && !handOffOnLeave) {
            return onScreenCommand(ScreenCommand.Type(query, thenSearch = true))
        }
        act {
            // 지도 앱: 검색 딥링크
            mapSearchUri(target.packageName, query)?.let { uri ->
                if (startView(uri, target.packageName)) return@act finishQuietly(End.LAUNCHED)
            }
            val intent = Intent(Intent.ACTION_SEARCH)
                .setPackage(target.packageName)
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Log.i(TAG, "app search: search intent started")
                return@act finishQuietly(End.LAUNCHED)
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "app search: no search intent, open app and type")
            } catch (e: SecurityException) {
                Log.i(TAG, "app search: search intent refused, open app and type")
            }
            // 유튜브 등: 앱 안 검색 결과 링크
            webSearchUri(target.displayName, query)?.let { uri ->
                if (startView(uri, target.packageName)) return@act finishQuietly(End.LAUNCHED)
            }
            val result = app.launcher.launch(target)
            if (result !is LaunchResult.Launched) return@act finish(str(R.string.conv_app_failed, target.displayName), End.ERROR)
            val control = ScreenControlService.instance
            if (control == null || handOffOnLeave) return@act if (control == null) needScreenControl() else finishQuietly(End.LAUNCHED)
            // 앱 화면이 뜰 때까지 잠깐 기다린 뒤 검색창에 쓴다
            val run = ++generation
            update(phase = Phase.WORKING, level = 0)
            main.postDelayed({ if (run == generation) onScreenCommand(ScreenCommand.Type(query, thenSearch = true)) }, APP_START_WAIT_MS)
        }
    }

    /** 화면 동작 결과를 말한다. 여러 개면 고르게 하고, 중요한 동작이면 확인받은 뒤에만 실행한다. */
    private fun onScreenResult(actions: ScreenActions, result: ScreenResult, done: String, notFound: String) {
        when (result) {
            ScreenResult.Done -> doneQuietly()
            ScreenResult.NoScreen -> finish(str(R.string.control_no_screen), End.ERROR)
            ScreenResult.NotFound -> finish(notFound, End.ERROR)
            ScreenResult.ManyInputs -> finish(str(R.string.control_many_inputs), End.ERROR)
            is ScreenResult.Choose -> {
                val labels = result.targets.map { it.label }
                val distinct = labels.map(ScreenActions::normalize).distinct().size == labels.size
                if (distinct) {
                    askClarify(
                        str(R.string.control_choose, labels.joinToString(", ")),
                        result.targets.map { t -> listOf(ScreenActions.normalize(t.label)) to { onScreenResult(actions, actions.press(t), done, notFound) } },
                    )
                } else {
                    // 같은 이름이 여러 개: "첫 번째/두 번째"로 고르게 한다
                    val two = result.targets.take(2)
                    askClarify(
                        str(R.string.control_choose_same, labels.first()),
                        two.map { t -> emptyList<String>() to { onScreenResult(actions, actions.press(t), done, notFound) } },
                    )
                }
            }
            is ScreenResult.Confirm -> {
                val target = result.target
                val prompt = if (result.submit) str(R.string.control_confirm_submit) else str(R.string.control_confirm_tap, target.label)
                askClarify(prompt, emptyList(), onYes = {
                    val confirmed = if (result.submit) actions.submit(target) else actions.press(target, confirmed = true)
                    onScreenResult(actions, confirmed, done, notFound)
                })
            }
        }
    }

    /** 접근성 서비스가 꺼져 있음: 무엇을 켜야 하는지 말하고 설정 화면을 연다 (사용자가 직접 켠다). 한 대화에 한 번만. */
    private fun needScreenControl() {
        if (accessibilityAsked || ScreenControlService.isEnabled(context)) {
            return finish(str(R.string.control_need_access_again), End.ERROR)
        }
        accessibilityAsked = true
        continuous = false
        say(str(R.string.control_need_access)) {
            try {
                context.startActivity(ScreenControlService.settingsIntent())
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "accessibility settings unavailable")
            }
            update(phase = Phase.ENDED, end = End.DONE, level = 0)
        }
    }

    // endregion

    private fun clear() {
        message = null
        draft = null
        choices = emptyList()
        clearPerson()
        misses = 0
        micRetried = false
        confirmRetried = false
        lastPrompt = null
        permissionAsked.clear()
        clarify = null
        clarifyRetried = false
        readingApp = false
        pendingTap = false
        pendingReminder = null
    }

    private fun wakeName(): String = wakeWordName ?: app.wakeWords.model?.name ?: str(R.string.conv_default_wake_name)

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun update(
        phase: Phase = snapshot.phase,
        assistant: String? = snapshot.assistant,
        heard: String? = snapshot.heard,
        level: Int = snapshot.level,
        step: Step = this.step,
        action: Action = snapshot.action,
        voice: Boolean = snapshot.voice,
        end: End? = snapshot.end,
    ) {
        val next = Snapshot(phase, assistant, heard, level, step, action, message != null, voice, end, continuous)
        if (next == snapshot) return
        val ended = next.phase == Phase.ENDED && snapshot.phase != Phase.ENDED
        snapshot = next
        onUpdate(next)
        if (ended) hooks?.onEnd(next.end)
    }

    companion object {
        private const val TAG = "PhoneConversation"

        // onRmsChanged 값의 일반적인 범위 (구현마다 다름)
        private const val RMS_MIN_DB = -2f
        private const val RMS_MAX_DB = 10f
        private const val AFTER_TTS_PAUSE_MS = 350L
        private const val TEXT_ONLY_MS = 3_000L
        private const val MIC_RETRY_MS = 600L
        private const val AFTER_PERMISSION_MS = 800L

        /** 연속 대화: 마지막 명령 뒤 이 시간 동안 말이 없으면 끝낸다 */
        private const val CONTINUOUS_IDLE_MS = 40_000L

        /** 검색 요청을 받지 않는 앱: 앱을 연 뒤 화면이 뜰 때까지 기다리는 시간 */
        private const val APP_START_WAIT_MS = 1_800L
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val NAVER_MAP_PACKAGE = "com.nhn.android.nmap"
        private val NAVER_START_LABELS = listOf("안내 시작", "안내시작", "길안내 시작")
        private const val NAVER_START_POLL_MS = 700L
        private const val NAVER_START_TRIES = 8
        private const val AMBIGUOUS_DISTANCE_M = 30_000f
        private const val YOUTUBE_TAP_POLL_MS = 600L
        private const val YOUTUBE_TAP_TRIES = 12
        private const val YOUTUBE_PLAY_CHECK_MS = 2_500L
        private const val AD_WATCH_MS = 60_000L
        private const val AD_POLL_MS = 1_500L
        private const val YOUTUBE_VERIFY_TRIES = 6
        private const val AD_AWAY_LIMIT = 5

        /** 안내 소리만 듣고 끝난 인식을 다시 여는 최대 횟수 (그 뒤에도 말이 없으면 평소처럼 다시 묻는다) */
        private const val MAX_ECHO_RESTARTS = 3

        /** "길찾기 열까요?"에 대한 대답 중 여는 말 */
        private val DIRECTION_WORDS = listOf("열", "길찾기", "안내", "보여", "켜", "가자")

        private val ORDINALS = listOf(listOf("첫", "일번", "1번", "하나"), listOf("두", "둘", "이번", "2번"), listOf("세", "셋", "삼번", "3번"))

        /** "카톡에서 동진님 메시지"의 '카톡'처럼 메시지 앱을 가리키는 말 (사람 이름으로 비교하지 않는다). */
        private val MESSAGE_APP_WORDS = setOf("카톡", "카카오톡", "톡", "카카오", "문자", "메시지", "메세지", "sms", "라인", "텔레그램", "인스타", "인스타그램", "dm", "왓츠앱", "페이스북", "메신저")

        /** 부분 일치는 이름을 앱으로 오인할 수 있어 정확히 같은 앱 이름·종류만 앱으로 본다. */
        private fun isAppWord(word: String, current: List<IncomingMessage>): Boolean {
            val key = AppMatcher.normalize(word)
            return key in MESSAGE_APP_WORDS || AppCategory.forName(word) != null ||
                current.any { AppMatcher.normalize(it.appLabel) == key }
        }

        private fun appPackages(app: String, current: List<IncomingMessage>): Set<String> {
            val apps = current.distinctBy { it.packageName }
                .map { AppEntry(it.packageName, it.appLabel, null, it.packageName, registered = false) }
            val match = AppMatcher.match(app, apps).takeIf { it != AppMatch.None }
                ?: AppCategory.forName(app)?.let { AppMatcher.matchByHints(it, apps) }
            return when (match) {
                is AppMatch.Single -> setOf(match.app.packageName)
                is AppMatch.Multiple -> match.apps.map { it.packageName }.toSet()
                else -> emptySet()
            }
        }

        /** 보낸 사람이나 단체방 이름이 [name]인 메시지 (호칭·띄어쓰기 차이는 무시). */
        private fun isFrom(message: IncomingMessage, name: String): Boolean {
            val key = KoreanNameMatcher.normalize(name)
            return KoreanNameMatcher.normalize(message.sender) == key ||
                message.conversation?.let { KoreanNameMatcher.normalize(it) == key } == true
        }

        /** 후보 이름 중 고른 사람: 이름이 같거나, "첫 번째"처럼 순서로, 또는 하나만 비슷한 이름. */
        internal fun pickPerson(hypotheses: List<String>, names: List<String>): String? {
            for (heard in hypotheses) {
                val answer = PersonCommandParser.nameAnswer(heard)
                val match = KoreanNameMatcher.resolve(answer, names)
                if (match is NameMatch.Exact) return match.name
                // '세진'의 '세'처럼 이름 첫 글자와 헷갈리지 않게 "번째"·"째"가 있을 때만 순서로 본다
                val compact = heard.replace(" ", "")
                if (compact.contains("째")) {
                    ORDINALS.forEachIndexed { i, words -> if (i < names.size && words.any { compact.startsWith(it) }) return names[i] }
                }
                if (match is NameMatch.Likely) return match.name
            }
            return null
        }

        /** "두 번째", "유튜브 뮤직" 처럼 순서나 이름으로 고른 앱. */
        internal fun pickChoice(hypotheses: List<String>, apps: List<FavoriteApp>): FavoriteApp? {
            for (heard in hypotheses) {
                val compact = heard.replace(" ", "").lowercase()
                apps.firstOrNull { compact.contains(it.displayName.replace(" ", "").lowercase()) }?.let { return it }
                ORDINALS.forEachIndexed { i, words -> if (i < apps.size && words.any { compact.startsWith(it) }) return apps[i] }
            }
            return null
        }
    }
}
