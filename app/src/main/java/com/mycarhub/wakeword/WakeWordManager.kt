package com.mycarhub.wakeword

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mycarhub.wakeword.audio.AudioSourceProvider
import com.mycarhub.wakeword.audio.MicrophonePcmSource
import com.mycarhub.wakeword.core.Detection
import com.mycarhub.wakeword.core.StreamingWakeWordDetector
import com.mycarhub.wakeword.core.TrainingResult
import com.mycarhub.wakeword.core.WakeWordModel
import com.mycarhub.wakeword.core.WakeWordTrainer
import com.mycarhub.wakeword.data.WakeWordRepository

/**
 * 개인 호출어 등록·감지 기능의 진입점. 화면과 분리되어 있어 이후 음성비서·서비스에서도 같은 객체를 쓴다.
 *
 * 1단계 범위: 앱 화면이 열려 있는 동안만 듣는다 (화면이 [startListening]/[stopListening] 호출).
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
class WakeWordManager(
    private val context: Context,
    private val repository: WakeWordRepository,
    private val audio: AudioSourceProvider,
    private val trainer: WakeWordTrainer = WakeWordTrainer(),
) {
    enum class ListenState {
        /** 듣지 않음 */
        OFF,

        /** 호출어를 듣는 중 */
        LISTENING,

        /** 다른 앱(예: 시스템 음성비서 화면)이 마이크를 사용 중이라 무음을 받고 있음 */
        SILENCED,

        /** 마이크를 열 수 없음 (권한 없음·사용 중·장치 없음) */
        MIC_UNAVAILABLE,
    }

    interface Listener {
        fun onWakeWordDetected(model: WakeWordModel, detection: Detection) {}
        fun onListenStateChanged(state: ListenState) {}
        fun onModelChanged(model: WakeWordModel?) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private var loop: ListenLoop? = null

    // 멈춘 뒤에도 마이크를 닫는 중일 수 있는 마지막 감지 스레드
    private var stoppingLoop: ListenLoop? = null

    /**
     * 이전 호출어(예: '붕붕아')로 등록돼 있던 경우 그 이름. 앱 시작 시 그 모델과 녹음은 보관 폴더로 옮겨 감지·학습에 쓰지 않는다
     * ([WakeWordRepository.retireIfNot]). '쁘이'를 새로 등록하면 null.
     */
    @Volatile
    var retiredName: String? = run {
        repository.retireIfNot(WakeWordName::isCurrent)
        repository.retiredName()
    }
        private set

    /** 지금 호출어('쁘이')로 등록된 모델만 쓴다 */
    @Volatile
    var model: WakeWordModel? = repository.load()?.takeIf { WakeWordName.isCurrent(it.name) }
        private set

    var listenState: ListenState = ListenState.OFF
        private set

    val usesTestAudio: Boolean get() = audio.usesTestAudio

    fun hasMicrophonePermission(): Boolean = MicrophonePcmSource.hasPermission(context)

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    // region 등록

    /** 호출어는 '쁘이' 하나다 ([WakeWordName.CURRENT]) */
    fun newEnrollment() = EnrollmentSession(WakeWordName.CURRENT, trainer)

    /** 녹음 1회 (블로킹, 백그라운드 스레드에서 호출). 듣는 중이면 마이크를 넘겨받기 위해 먼저 멈춘다. */
    fun recordTake(index: Int, progress: TakeRecorder.Progress, isCancelled: () -> Boolean): TakeRecorder.Result {
        main.post { stopListening() }
        return TakeRecorder().record(audio.forTake(index), progress, isCancelled)
    }

    /** 학습에 성공한 모델을 저장하고 기존 등록을 교체한다. */
    fun save(session: EnrollmentSession, result: TrainingResult.Success) {
        repository.save(result.model, session.recordings())
        model = result.model
        retiredName = null
        notify { it.onModelChanged(result.model) }
    }

    /** 등록된 모델과 녹음 원본을 삭제한다. */
    fun delete(): Boolean {
        stopListening()
        val deleted = repository.delete()
        model = null
        notify { it.onModelChanged(null) }
        return deleted
    }

    // endregion

    // region 감지

    fun startListening(): Boolean {
        val current = model ?: return false
        if (loop != null) return true
        if (!audio.usesTestAudio && !hasMicrophonePermission()) {
            setState(ListenState.MIC_UNAVAILABLE)
            return false
        }
        loop = ListenLoop(current).also { it.start() }
        return true
    }

    fun stopListening() {
        loop?.let {
            it.running = false
            it.interrupt()
            stoppingLoop = it
        }
        loop = null
        setState(ListenState.OFF)
    }

    /**
     * 감지 스레드가 마이크를 완전히 놓은 뒤 메인 스레드에서 [action]을 실행한다.
     * 음성 명령 인식(STT)처럼 같은 마이크를 쓰는 기능을 시작하기 전에 호출한다.
     */
    fun whenMicReleased(action: () -> Unit) {
        stopListening()
        val closing = stoppingLoop?.takeIf { it.isAlive }
        stoppingLoop = null
        if (closing == null) {
            main.post(action)
            return
        }
        Thread({
            closing.join(MIC_RELEASE_TIMEOUT_MS)
            main.post(action)
        }, "WakeWordMicRelease").start()
    }

    private inner class ListenLoop(private val model: WakeWordModel) : Thread("WakeWordListener") {
        @Volatile
        var running = true

        override fun run() {
            val source = audio.forListening { silenced ->
                if (running) setState(if (silenced) ListenState.SILENCED else ListenState.LISTENING)
            }
            if (!source.start()) {
                main.post {
                    if (loop === this) {
                        loop = null
                        setState(ListenState.MIC_UNAVAILABLE)
                    }
                }
                return
            }
            main.post { if (loop === this && listenState == ListenState.OFF) setState(ListenState.LISTENING) }
            val detector = StreamingWakeWordDetector(model)
            detector.segmentListener = StreamingWakeWordDetector.SegmentListener { score, threshold, millis ->
                Log.i(TAG, "segment ${millis}ms score=%.3f threshold=%.3f".format(score, threshold))
            }
            val buffer = ShortArray(BUFFER_SAMPLES)
            try {
                while (running) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    val detection = detector.process(buffer, n) ?: continue
                    Log.i(TAG, "wake word detected: score=%.3f".format(detection.score))
                    main.post { if (loop === this) notify { it.onWakeWordDetected(model, detection) } }
                }
            } finally {
                source.stop()
            }
            main.post {
                if (loop === this) {
                    // 테스트 파일 재생이 끝난 경우 등
                    loop = null
                    setState(ListenState.OFF)
                }
            }
        }
    }

    // endregion

    private fun setState(state: ListenState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setState(state) }
            return
        }
        if (listenState == state) return
        listenState = state
        notify { it.onListenStateChanged(state) }
    }

    /** 리스너는 항상 메인 스레드에서 호출한다 (저장은 백그라운드 스레드에서 일어날 수 있음). */
    private fun notify(block: (Listener) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { notify(block) }
            return
        }
        listeners.toList().forEach(block)
    }

    private companion object {
        const val TAG = "WakeWordManager"
        const val BUFFER_SAMPLES = 1_600 // 100 ms
        const val MIC_RELEASE_TIMEOUT_MS = 1_500L
    }
}
