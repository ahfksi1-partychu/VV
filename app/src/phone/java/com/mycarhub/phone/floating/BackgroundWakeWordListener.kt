package com.mycarhub.phone.floating

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import com.mycarhub.phone.conversation.PhoneSpeaker
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mycarhub.wakeword.audio.MicrophonePcmSource
import com.mycarhub.wakeword.phone.AdaptiveWakeWordDetector
import com.mycarhub.wakeword.core.WakeWordModel
import com.mycarhub.wakeword.core.WavIO
import com.mycarhub.wakeword.phone.LiveCalibration
import java.io.File

/**
 * 폰 빌드의 호출어 감지 스레드 (백그라운드 플로팅과 MyCarHub 메인 화면이 함께 쓴다).
 * 마이크 입력은 기존과 같고, 감지기는 배경 소음 추정을 보완한 [AdaptiveWakeWordDetector]를 쓴다.
 *
 * 호출어를 감지하면 **스스로 마이크를 닫고 끝난 뒤** [Callbacks.onDetected]를 부른다.
 * 그래서 이어지는 음성 인식(STT)과 마이크를 동시에 점유하지 않는다.
 * 오디오는 메모리에서만 처리하고 저장하지 않는다. 콜백은 메인 스레드에서 호출된다.
 */
class BackgroundWakeWordListener(
    private val context: Context,
    private val model: WakeWordModel,
    private val callbacks: Callbacks,
    /** 로그 태그: 백그라운드(플로팅)와 MyCarHub 화면의 감지를 구분한다 */
    private val tag: String = TAG,
) {
    interface Callbacks {
        fun onListening()
        fun onDetected()

        /** 다른 앱·통화가 마이크 우선권을 가져가 무음을 받는 중 */
        fun onSilenced(silenced: Boolean)
        fun onMicUnavailable()
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: Loop? = null

    val isRunning: Boolean get() = thread != null

    fun start() {
        if (thread != null) return
        thread = Loop().also { it.start() }
    }

    /** 멈추고, 스레드가 마이크를 완전히 닫은 뒤 메인 스레드에서 [onStopped]. */
    fun stop(onStopped: (() -> Unit)? = null) {
        val loop = thread
        thread = null
        if (loop == null) {
            if (onStopped != null) main.post { onStopped() }
            return
        }
        loop.running = false
        loop.interrupt()
        if (onStopped == null) return
        Thread({
            loop.join(STOP_TIMEOUT_MS)
            main.post(onStopped)
        }, "WakeWordBgStop").start()
    }

    private inner class Loop : Thread("WakeWordBackground") {
        @Volatile
        var running = true

        override fun run() {
            val source = MicrophonePcmSource(context) { silenced ->
                if (running) main.post { if (thread === this) callbacks.onSilenced(silenced) }
            }.apply { echoCancel = true }
            if (!source.start()) {
                main.post {
                    if (thread === this) {
                        thread = null
                        callbacks.onMicUnavailable()
                    }
                }
                return
            }
            main.post { if (thread === this) callbacks.onListening() }
            logCalibration()
            val detector = AdaptiveWakeWordDetector(model)
            // 검증: 등록과 같은 방식의 점수(matched)도 함께 기록한다. 판정은 기존 점수만 쓴다.
            detector.compareScores = true
            detector.segmentListener = AdaptiveWakeWordDetector.SegmentListener { score, matched, threshold, millis ->
                Log.i(tag, "segment ${millis}ms score=%.3f matched=%.3f threshold=%.3f".format(score, matched, threshold))
            }
            // 진단: 입력 레벨과 소음 기준 (오디오 내용은 남기지 않음)
            detector.levelListener = AdaptiveWakeWordDetector.LevelListener { avg, min, noise, speech ->
                Log.d(tag, "level avg=%.1f min=%.1f noise=%.1f speech=%s".format(avg, min, noise, speech))
            }
            val buffer = ShortArray(BUFFER_SAMPLES)
            var detected = false
            try {
                while (running) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    // 앱 자신의 TTS가 말하는 동안(과 끝난 직후)은 마이크 입력을 감지기에 넣지 않는다
                    if (selfSpeechNearby(SELF_SPEECH_MUTE_MS)) continue
                    val detection = detector.process(buffer, n) ?: continue
                    if (!acceptDetection(detection.score, detection.threshold)) continue
                    Log.i(tag, "wake word detected: score=%.3f threshold=%.3f".format(detection.score, detection.threshold))
                    detected = true
                    break
                }
            } finally {
                source.stop()
            }
            main.post {
                if (thread !== this) return@post
                thread = null
                // 감지했으면 마이크를 이미 닫은 상태로 알린다. 그 밖의 종료(입력 오류)는 마이크 문제로 본다.
                if (detected) callbacks.onDetected() else if (running) callbacks.onMicUnavailable()
            }
        }
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var playbackCheckedAt = 0L
    private var playbackActive = false

    private fun selfSpeechNearby(windowMs: Long): Boolean =
        PhoneSpeaker.selfSpeaking || SystemClock.elapsedRealtime() - PhoneSpeaker.selfSpokeEndMs < windowMs

    /** 다른 앱(영상·음악·내비 안내 등)이 소리를 내는 중인지. 0.5초마다 다시 확인한다. */
    private fun otherPlaybackActive(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - playbackCheckedAt < PLAYBACK_CHECK_MS) return playbackActive
        playbackCheckedAt = now
        playbackActive = runCatching {
            audioManager.isMusicActive || audioManager.activePlaybackConfigurations.any { it.audioAttributes.usage in LOUD_USAGES }
        }.getOrDefault(false)
        return playbackActive
    }

    /**
     * 스피커 소리에 의한 오인식 줄이기. 마이크만으로 사람 목소리와 스피커 소리를 완전히 가를 수는 없으므로:
     * - 앱 TTS가 끝난 직후에는 감지를 받지 않는다 (자기 음성 재호출 방지).
     * - 다른 앱이 소리를 내는 중이면 더 엄격한 기준(점수가 기준의 [PLAYBACK_STRICTNESS]배 이하)만 받는다.
     *   직접 또렷하게 말한 호출어는 보통 이 기준도 넘으므로 음악·내비 중에도 부를 수 있다.
     */
    private fun acceptDetection(score: Float, threshold: Float): Boolean {
        if (selfSpeechNearby(SELF_SPEECH_GUARD_MS)) {
            Log.i(tag, "wake word ignored: own tts just spoke (score=%.3f)".format(score))
            return false
        }
        if (otherPlaybackActive() && score > threshold * PLAYBACK_STRICTNESS) {
            Log.i(tag, "wake word ignored during playback: score=%.3f strict=%.3f".format(score, threshold * PLAYBACK_STRICTNESS))
            return false
        }
        return true
    }

    /**
     * 진단: 기기에 저장된 등록 녹음 5개를 실시간 감지와 같은 방식으로 다시 채점해 로그로 남긴다 (모델마다 한 번).
     * 녹음은 기기 안에서 읽기만 하고 복사·전송하지 않는다. 감지 기준은 아직 바꾸지 않는다.
     */
    private fun logCalibration() {
        if (calibratedModel == model.createdAtMillis) return
        calibratedModel = model.createdAtMillis
        val dir = File(context.filesDir, "wakeword/samples")
        val takes = (1..model.templates.size).map { File(dir, "take_$it.wav") }.filter { it.isFile }.map { WavIO.read(it).samples }
        val result = LiveCalibration.calibrate(model, takes)
        if (result == null) {
            Log.i(tag, "calibration skipped: takes=${takes.size} templates=${model.templates.size}")
            return
        }
        Log.i(
            tag,
            "calibration: model threshold=%.3f live LOO=%s matched LOO=%s -> calibrated=%.3f%s (not applied)".format(
                result.modelThreshold,
                result.liveScores.map { "%.3f".format(it) },
                result.matchedScores.map { "%.3f".format(it) },
                result.threshold,
                if (result.capped) " capped" else "",
            ),
        )
    }

    private companion object {
        const val TAG = "WakeWordBackground"

        @Volatile
        var calibratedModel = 0L
        const val BUFFER_SAMPLES = 1_600 // 100 ms
        const val STOP_TIMEOUT_MS = 1_500L
        const val SELF_SPEECH_MUTE_MS = 600L
        const val SELF_SPEECH_GUARD_MS = 1_500L
        const val PLAYBACK_CHECK_MS = 500L
        const val PLAYBACK_STRICTNESS = 0.8f
        val LOUD_USAGES = setOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANT,
        )
    }
}
