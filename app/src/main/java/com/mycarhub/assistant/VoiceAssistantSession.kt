package com.mycarhub.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mycarhub.apps.FavoriteAppsManager
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchResult
import com.mycarhub.apps.system.AppLauncher
import com.mycarhub.assistant.core.CommandParser
import com.mycarhub.assistant.core.Confirmation
import com.mycarhub.assistant.core.IncomingMessage
import com.mycarhub.assistant.core.MessageSelection
import com.mycarhub.assistant.core.MessageSelector
import com.mycarhub.assistant.core.VoiceCommand
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.speech.Speaker
import com.mycarhub.assistant.stt.SpeechToText
import com.mycarhub.drive.DriveAssistant
import com.mycarhub.wakeword.WakeWordManager
import java.util.concurrent.Executors

/** 무엇을 듣는 중인지 (화면 안내와 결과 해석이 달라진다). */
enum class ListenPurpose {
    /** 첫 명령 */
    COMMAND,

    /** 메시지를 읽은 뒤: 답장·다시 읽기·닫기 */
    AFTER_READ,

    /** "어떤 내용으로 답장할까요?" */
    REPLY_BODY,

    /** "…라고 보낼까요?" */
    CONFIRM,
}

/** 무엇을 읽어 주는 중인지. */
enum class SpeakPurpose { READ_MESSAGE, ASK_REPLY_BODY, CONFIRM_REPLY }

/** 음성비서 화면 상태. 화면은 이 상태만 그린다. */
sealed interface AssistantState {
    data object Preparing : AssistantState

    /** [level]은 0~100 입력 크기, [partial]은 지금까지 알아들은 말. [message]·[draft]는 메시지 흐름일 때만. */
    data class Listening(
        val partial: String?,
        val level: Int,
        val speaking: Boolean,
        val purpose: ListenPurpose = ListenPurpose.COMMAND,
        val message: IncomingMessage? = null,
        val draft: String? = null,
    ) : AssistantState

    data class Processing(val heard: String) : AssistantState
    data class Done(val heard: String, val app: FavoriteApp) : AssistantState

    /** 이름이 같은 앱이 여러 개 → 사용자가 고른다 */
    data class Choose(val heard: String, val apps: List<FavoriteApp>) : AssistantState

    /** TTS로 읽는 중. [voice]가 false면 오프라인 음성이 없어 화면으로만 보여주는 중. */
    data class Speaking(
        val purpose: SpeakPurpose,
        val message: IncomingMessage,
        val draft: String?,
        val voice: Boolean,
    ) : AssistantState

    data class Sent(val message: IncomingMessage, val body: String) : AssistantState

    /** Drive To-Do 대답 ("마지막 주차 위치는 …", "할 일에 추가했어요 …"): 말하고 화면에 보여준다 */
    data class Answer(val heard: String, val text: String) : AssistantState
    data class Failed(val reason: Failure, val heard: String? = null, val subject: String? = null) : AssistantState

    /** "취소"라고 말함 */
    data object Cancelled : AssistantState

    /** 답장을 보내지 않기로 함 (거절·대답 없음) */
    data object ReplyCancelled : AssistantState

    /** 메시지를 읽은 뒤 더 말하지 않음 → 조용히 닫는다 */
    data object Finished : AssistantState
}

enum class Failure {
    NO_PERMISSION,

    /** 기기 내 인식기가 없어 시스템 인식기 사용 동의가 필요 */
    STT_NEEDS_CONSENT,
    STT_UNAVAILABLE,
    STT_LANGUAGE_UNAVAILABLE,
    STT_NETWORK,
    STT_ERROR,
    MIC_BUSY,
    NO_SPEECH,
    NOT_UNDERSTOOD,
    UNKNOWN_COMMAND,
    UNSUPPORTED,
    APP_NOT_FOUND,
    NOT_INSTALLED,
    DISABLED,
    BLOCKED_WHILE_DRIVING,
    LAUNCH_FAILED,

    /** 이 플랫폼(차량용)에서는 메시지 읽기·답장을 지원하지 않음 */
    MESSAGES_UNSUPPORTED,

    /** 알림 접근이 허용되지 않음 */
    NOTIFICATION_ACCESS,
    NOTIFICATION_NOT_CONNECTED,
    NO_MESSAGES,
    NO_MESSAGES_FOR_APP,

    /** 읽은 메시지 없이 답장을 요청함 */
    REPLY_WITHOUT_MESSAGE,

    /** 이 메시지 알림에는 답장 동작이 없음 */
    REPLY_NOT_SUPPORTED,
    MESSAGE_GONE,
    SEND_FAILED,
}

/** TTS로 읽을 문장 (화면 문자열과 같은 리소스를 쓰도록 화면이 제공한다). */
interface AssistantPhrases {
    fun readMessage(message: IncomingMessage): String
    fun askReplyBody(message: IncomingMessage): String
    fun confirmReply(message: IncomingMessage, body: String): String
    fun confirmAgain(body: String): String
    fun replySent(message: IncomingMessage): String
}

/**
 * 음성비서 흐름: 마이크 확보 → 음성 인식(STT) → 명령 해석 → 실행.
 *
 * - 앱 실행: 앱 찾기 → 실행 (즐겨찾기와 같은 AppLauncher)
 * - 메시지: 현재 알림에서 최신 메시지 → TTS로 읽기 → 이어서 "…라고 답장해 줘" → 내용 확인 질문 →
 *   **명확히 '응/보내'라고 답하거나 [보내기]를 눌렀을 때만** 전송. 대답이 없거나 애매하면 보내지 않는다.
 *
 * 화면(AssistantActivity)과 분리되어 있으며 메인 스레드에서 호출한다. 읽은 메시지와 답장 초안은
 * 이 객체 안에만 있고 [release]에서 지운다 (파일·설정에 저장하지 않음).
 */
class VoiceAssistantSession(
    private val stt: SpeechToText,
    private val speaker: Speaker,
    private val messages: MessageAccess,
    private val phrases: AssistantPhrases,
    private val resolver: AppCommandResolver,
    private val favorites: FavoriteAppsManager,
    private val launcher: AppLauncher,
    private val wakeWords: WakeWordManager,
    private val hasMicPermission: () -> Boolean,
    /** 사용자가 호출어를 다시 말하면 명령 앞에서 떼어낸다 */
    private val wakeWordName: String?,
    private val onState: (AssistantState) -> Unit,
    /** Drive To-Do: 주차 위치 묻기·할 일 등록 (기존 명령보다 먼저 본다) */
    private val drive: DriveAssistant? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    // 다시 시작·취소하면 이전 흐름의 늦은 결과를 버린다
    private var generation = 0

    // 메시지 흐름 (메모리에만)
    private var message: IncomingMessage? = null
    private var draft: String? = null
    private var confirmRetried = false
    private var lastPurpose = ListenPurpose.COMMAND

    var state: AssistantState = AssistantState.Preparing
        private set

    /** 처음부터 명령을 듣는다. */
    fun start() {
        clearMessage()
        listen(ListenPurpose.COMMAND)
    }

    /** 실패 화면의 [다시 말하기]: 마지막으로 듣던 단계를 다시 듣는다. */
    fun retry() {
        if (lastPurpose != ListenPurpose.COMMAND && message == null) lastPurpose = ListenPurpose.COMMAND
        listen(lastPurpose)
    }

    /** "다 말했어요": 지금까지 들은 말로 인식을 끝낸다. */
    fun finishSpeaking() {
        if (state is AssistantState.Listening) stt.stopListening()
    }

    /** 읽기 중 [건너뛰기]: 읽기를 멈추고 다음 단계로. */
    fun skipSpeaking() {
        val speaking = state as? AssistantState.Speaking ?: return
        val run = ++generation
        speaker.stop()
        afterSpeaking(run, speaking.purpose)
    }

    /** 여러 후보 중 사용자가 고른 앱 실행. */
    fun choose(app: FavoriteApp) {
        val heard = (state as? AssistantState.Choose)?.heard ?: return
        launch(heard, app)
    }

    /** 확인 화면의 [보내기] 버튼 (음성 '응'과 같은 효과). */
    fun confirmSend() {
        if (draft != null && message != null) send()
    }

    /** 확인 화면의 [보내지 않기] 버튼. */
    fun cancelReply() {
        cancel()
        draft = null
        setState(AssistantState.ReplyCancelled)
    }

    fun cancel() {
        generation++
        stt.cancel()
        speaker.stop()
    }

    fun release() {
        cancel()
        clearMessage()
        speaker.shutdown()
        worker.shutdownNow()
    }

    // region 듣기

    private fun listen(purpose: ListenPurpose) {
        val run = ++generation
        lastPurpose = purpose
        stt.cancel()
        speaker.stop()
        setState(AssistantState.Preparing)
        if (!hasMicPermission()) return setState(AssistantState.Failed(Failure.NO_PERMISSION))
        when (stt.availability()) {
            SpeechToText.Availability.NeedsSystemConsent -> return setState(AssistantState.Failed(Failure.STT_NEEDS_CONSENT))
            SpeechToText.Availability.Unavailable -> return setState(AssistantState.Failed(Failure.STT_UNAVAILABLE))
            is SpeechToText.Availability.Ready -> Unit
        }
        // 호출어 감지가 마이크를 놓은 뒤 인식을 시작해야 시작 직후 오디오 오류가 나지 않는다
        wakeWords.whenMicReleased {
            if (run != generation) return@whenMicReleased
            if (!stt.start(SttCallback(run, purpose))) setState(AssistantState.Failed(Failure.STT_ERROR))
        }
    }

    private inner class SttCallback(private val run: Int, private val purpose: ListenPurpose) : SpeechToText.Listener {
        private var partial: String? = null
        private var speaking = false

        override fun onReady() = listening(0)

        override fun onSpeechStart() {
            speaking = true
        }

        override fun onLevel(rmsDb: Float) =
            listening(((rmsDb - RMS_MIN_DB) / (RMS_MAX_DB - RMS_MIN_DB) * 100).toInt().coerceIn(0, 100))

        override fun onPartial(text: String) {
            partial = text
            listening((state as? AssistantState.Listening)?.level ?: 0)
        }

        override fun onResults(hypotheses: List<String>) {
            if (run != generation) return
            when (purpose) {
                ListenPurpose.COMMAND, ListenPurpose.AFTER_READ -> interpret(run, hypotheses, purpose)
                ListenPurpose.REPLY_BODY -> onReplyBody(hypotheses.first())
                ListenPurpose.CONFIRM -> onConfirmation(hypotheses)
            }
        }

        override fun onError(error: SpeechToText.Error) {
            if (run != generation) return
            val quiet = error == SpeechToText.Error.NO_SPEECH || error == SpeechToText.Error.NO_MATCH
            when {
                // 메시지를 읽은 뒤 아무 말 없으면 그대로 닫는다
                quiet && purpose == ListenPurpose.AFTER_READ -> return setState(AssistantState.Finished)
                // 확인 질문에 대답이 없으면 한 번 더 묻고, 그래도 없으면 보내지 않는다
                quiet && purpose == ListenPurpose.CONFIRM -> return askConfirmationAgain()
            }
            val reason = when (error) {
                SpeechToText.Error.NO_MATCH -> Failure.NOT_UNDERSTOOD
                SpeechToText.Error.NO_SPEECH -> Failure.NO_SPEECH
                SpeechToText.Error.AUDIO, SpeechToText.Error.BUSY -> Failure.MIC_BUSY
                SpeechToText.Error.PERMISSION -> Failure.NO_PERMISSION
                SpeechToText.Error.NETWORK -> Failure.STT_NETWORK
                SpeechToText.Error.LANGUAGE_UNAVAILABLE -> Failure.STT_LANGUAGE_UNAVAILABLE
                SpeechToText.Error.SERVICE -> Failure.STT_ERROR
            }
            setState(AssistantState.Failed(reason, heard = partial.takeIf { purpose == ListenPurpose.COMMAND }))
        }

        private fun listening(level: Int) {
            if (run == generation) {
                setState(AssistantState.Listening(partial, level, speaking, purpose, message, draft))
            }
        }
    }

    // endregion

    // region 명령 해석

    /** 인식 후보를 신뢰도 순으로 해석한다. 첫 후보가 취소·지원하지 않는 요청·메시지 명령이면 그대로 따른다. */
    private fun interpret(run: Int, hypotheses: List<String>, purpose: ListenPurpose) {
        val heard = hypotheses.first()
        setState(AssistantState.Processing(heard))
        val assistant = drive
        if (purpose == ListenPurpose.COMMAND && assistant != null) {
            when (val answer = assistant.answer(heard, wakeWordName)) {
                is DriveAssistant.Answer.Say -> return answer(run, heard, answer.text)
                DriveAssistant.Answer.RecordParkingHere -> return assistant.recordParkingHere { text -> answer(run, heard, text) }
                null -> Unit
            }
        }
        val commands = hypotheses.map { CommandParser.parse(it, wakeWordName) }
        // 인식 문장은 개인 정보일 수 있어 명령 종류만 남긴다
        Log.i(TAG, "purpose=$purpose command=${commands.first()::class.simpleName}")
        when (val first = commands.first()) {
            VoiceCommand.Cancel -> return setState(
                if (purpose == ListenPurpose.AFTER_READ) AssistantState.Finished else AssistantState.Cancelled,
            )
            is VoiceCommand.Unsupported -> return setState(AssistantState.Failed(Failure.UNSUPPORTED, heard))
            is VoiceCommand.ReadMessage -> return readMessage(run, first.app)
            is VoiceCommand.Reply -> return startReply(first.body)
            VoiceCommand.Repeat -> {
                val current = message ?: return setState(AssistantState.Failed(Failure.REPLY_WITHOUT_MESSAGE, heard))
                return speak(SpeakPurpose.READ_MESSAGE, current)
            }
            else -> Unit
        }
        val registered = favorites.favorites
        worker.execute {
            val outcome = decideApp(hypotheses, commands, registered)
            main.post {
                if (run != generation) return@post
                when (outcome) {
                    is Outcome.Launch -> launch(outcome.heard, outcome.app)
                    is Outcome.Show -> setState(outcome.state)
                }
            }
        }
    }

    /** Drive To-Do 대답을 보여주고 말한다 (말한 뒤 더 듣지 않는다). */
    private fun answer(run: Int, heard: String, text: String) {
        if (run != generation) return
        Log.i(TAG, "drive answer")
        setState(AssistantState.Answer(heard, text))
        if (speakerUsable()) speaker.speak(text) {}
    }

    private sealed interface Outcome {
        data class Launch(val heard: String, val app: FavoriteApp) : Outcome
        data class Show(val state: AssistantState) : Outcome
    }

    /** 백그라운드 스레드 (앱 목록 조회). */
    private fun decideApp(hypotheses: List<String>, commands: List<VoiceCommand>, registered: List<FavoriteApp>): Outcome {
        val heard = hypotheses.first()
        var missing: String? = null
        for ((index, command) in commands.withIndex()) {
            if (command !is VoiceCommand.OpenApp) continue
            when (val found = resolver.resolve(command.target, registered)) {
                is AppCommandResolver.Resolution.Found -> return Outcome.Launch(hypotheses[index], found.app)
                is AppCommandResolver.Resolution.Choices -> return Outcome.Show(AssistantState.Choose(hypotheses[index], found.apps))
                is AppCommandResolver.Resolution.NotFound -> if (missing == null && command.hasVerb) missing = command.target
            }
        }
        return Outcome.Show(
            if (missing != null) {
                AssistantState.Failed(Failure.APP_NOT_FOUND, heard, subject = missing)
            } else {
                AssistantState.Failed(Failure.UNKNOWN_COMMAND, heard)
            },
        )
    }

    /** 실행 전 설치·활성·주행 제한 확인은 즐겨찾기 실행과 같은 AppLauncher가 한다. */
    private fun launch(heard: String, app: FavoriteApp) {
        val result = launcher.launch(app)
        Log.i(TAG, "launch ${app.packageName}: ${result::class.simpleName}")
        setState(
            when (result) {
                is LaunchResult.Launched -> AssistantState.Done(heard, app)
                is LaunchResult.NotInstalled -> AssistantState.Failed(Failure.NOT_INSTALLED, heard, app.displayName)
                is LaunchResult.Disabled -> AssistantState.Failed(Failure.DISABLED, heard, app.displayName)
                is LaunchResult.BlockedWhileDriving -> AssistantState.Failed(Failure.BLOCKED_WHILE_DRIVING, heard, app.displayName)
                is LaunchResult.NoLaunchableActivity,
                is LaunchResult.Failed,
                is LaunchResult.NoMatch,
                is LaunchResult.Ambiguous,
                -> AssistantState.Failed(Failure.LAUNCH_FAILED, heard, app.displayName)
            },
        )
    }

    // endregion

    // region 메시지 읽기·답장

    private fun readMessage(run: Int, app: String?) {
        accessFailure()?.let { return setState(AssistantState.Failed(it)) }
        worker.execute {
            val current = messages.currentMessages()
            val selection = MessageSelector.latest(current, app)
            main.post {
                if (run != generation) return@post
                when (selection) {
                    is MessageSelection.Found -> {
                        message = selection.message
                        draft = null
                        Log.i(TAG, "read message from ${selection.message.packageName} canReply=${selection.message.canReply}")
                        speak(SpeakPurpose.READ_MESSAGE, selection.message)
                    }
                    is MessageSelection.NoneForApp ->
                        setState(AssistantState.Failed(Failure.NO_MESSAGES_FOR_APP, subject = selection.app))
                    MessageSelection.None -> setState(
                        AssistantState.Failed(
                            if (messages.status == MessageAccess.Status.READY) Failure.NO_MESSAGES else Failure.NOTIFICATION_NOT_CONNECTED,
                        ),
                    )
                }
            }
        }
    }

    private fun accessFailure(): Failure? = when (messages.status) {
        MessageAccess.Status.UNSUPPORTED -> Failure.MESSAGES_UNSUPPORTED
        MessageAccess.Status.NEEDS_ACCESS -> Failure.NOTIFICATION_ACCESS
        // 허용 직후 연결 전이면 currentMessages가 재연결을 기다린다
        MessageAccess.Status.NOT_CONNECTED, MessageAccess.Status.READY -> null
    }

    private fun startReply(body: String?) {
        if (messages.status == MessageAccess.Status.UNSUPPORTED) {
            return setState(AssistantState.Failed(Failure.MESSAGES_UNSUPPORTED))
        }
        val current = message ?: return setState(AssistantState.Failed(Failure.REPLY_WITHOUT_MESSAGE))
        if (!current.canReply) return setState(AssistantState.Failed(Failure.REPLY_NOT_SUPPORTED, subject = current.appLabel))
        confirmRetried = false
        if (body == null) {
            speak(SpeakPurpose.ASK_REPLY_BODY, current)
        } else {
            draft = body
            speak(SpeakPurpose.CONFIRM_REPLY, current)
        }
    }

    private fun onReplyBody(heard: String) {
        val body = CommandParser.parseReplyBody(heard) ?: return setState(AssistantState.ReplyCancelled)
        val current = message ?: return setState(AssistantState.Failed(Failure.REPLY_WITHOUT_MESSAGE))
        draft = body
        speak(SpeakPurpose.CONFIRM_REPLY, current)
    }

    private fun onConfirmation(hypotheses: List<String>) {
        // 후보 중 하나라도 '아니'면 보내지 않는다. '응'은 첫 후보 기준.
        val answers = hypotheses.map(CommandParser::parseConfirmation)
        when {
            Confirmation.NO in answers -> cancelReply()
            answers.first() == Confirmation.YES -> send()
            else -> askConfirmationAgain()
        }
    }

    private fun askConfirmationAgain() {
        val current = message
        val body = draft
        if (confirmRetried || current == null || body == null) return cancelReply()
        confirmRetried = true
        val run = ++generation
        setState(AssistantState.Speaking(SpeakPurpose.CONFIRM_REPLY, current, body, voice = speakerUsable()))
        speakText(run, phrases.confirmAgain(body), SpeakPurpose.CONFIRM_REPLY)
    }

    private fun send() {
        val current = message ?: return
        val body = draft ?: return
        val run = ++generation
        stt.cancel()
        speaker.stop()
        setState(AssistantState.Processing(body))
        worker.execute {
            val result = messages.reply(current, body)
            main.post {
                if (run != generation) return@post
                draft = null
                when (result) {
                    MessageAccess.ReplyResult.SENT -> {
                        setState(AssistantState.Sent(current, body))
                        speaker.speak(phrases.replySent(current)) {}
                    }
                    MessageAccess.ReplyResult.MESSAGE_GONE -> setState(AssistantState.Failed(Failure.MESSAGE_GONE))
                    MessageAccess.ReplyResult.NO_REPLY_ACTION ->
                        setState(AssistantState.Failed(Failure.REPLY_NOT_SUPPORTED, subject = current.appLabel))
                    MessageAccess.ReplyResult.NO_ACCESS -> setState(AssistantState.Failed(Failure.NOTIFICATION_ACCESS))
                    MessageAccess.ReplyResult.FAILED -> setState(AssistantState.Failed(Failure.SEND_FAILED))
                }
            }
        }
    }

    // endregion

    // region 읽어 주기

    private fun speak(purpose: SpeakPurpose, current: IncomingMessage) {
        val run = ++generation
        stt.cancel()
        setState(AssistantState.Speaking(purpose, current, draft, voice = speakerUsable()))
        val text = when (purpose) {
            SpeakPurpose.READ_MESSAGE -> phrases.readMessage(current)
            SpeakPurpose.ASK_REPLY_BODY -> phrases.askReplyBody(current)
            SpeakPurpose.CONFIRM_REPLY -> phrases.confirmReply(current, draft.orEmpty())
        }
        speakText(run, text, purpose)
    }

    private fun speakText(run: Int, text: String, purpose: SpeakPurpose) {
        if (!speakerUsable()) {
            // 오프라인 음성이 없으면 화면에 글자로 보여주고 읽을 시간을 준 뒤 다음 단계로
            main.postDelayed({ if (run == generation) afterSpeaking(run, purpose) }, TEXT_ONLY_MS)
            return
        }
        speaker.speak(text) { spoken ->
            if (run != generation) return@speak
            if (spoken || speakerUsable()) return@speak afterSpeaking(run, purpose)
            // 초기화해 보니 오프라인 음성이 없음 → 글자로만 보여주는 상태로 바꾸고 읽을 시간을 준다
            (state as? AssistantState.Speaking)?.let { setState(it.copy(voice = false)) }
            main.postDelayed({ if (run == generation) afterSpeaking(run, purpose) }, TEXT_ONLY_MS)
        }
    }

    private fun afterSpeaking(run: Int, purpose: SpeakPurpose) {
        if (run != generation) return
        // TTS 끝부분이 인식기에 들어가지 않도록 잠깐 쉬었다가 듣는다
        main.postDelayed({
            if (run != generation) return@postDelayed
            listen(
                when (purpose) {
                    SpeakPurpose.READ_MESSAGE -> ListenPurpose.AFTER_READ
                    SpeakPurpose.ASK_REPLY_BODY -> ListenPurpose.REPLY_BODY
                    SpeakPurpose.CONFIRM_REPLY -> ListenPurpose.CONFIRM
                },
            )
        }, AFTER_TTS_PAUSE_MS)
    }

    private fun speakerUsable(): Boolean =
        speaker.state == Speaker.State.READY || speaker.state == Speaker.State.INITIALIZING

    // endregion

    private fun clearMessage() {
        message = null
        draft = null
        confirmRetried = false
    }

    private fun setState(newState: AssistantState) {
        if (state == newState) return
        state = newState
        onState(newState)
    }

    private companion object {
        const val TAG = "VoiceAssistant"

        // onRmsChanged 값의 일반적인 범위 (구현마다 다름)
        const val RMS_MIN_DB = -2f
        const val RMS_MAX_DB = 10f

        const val AFTER_TTS_PAUSE_MS = 300L
        const val TEXT_ONLY_MS = 4_000L
    }
}
