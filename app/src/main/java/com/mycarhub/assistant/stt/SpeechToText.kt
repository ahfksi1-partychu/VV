package com.mycarhub.assistant.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Android SpeechRecognizer 기반 음성 명령 인식(STT). 메인 스레드에서만 호출한다.
 *
 * 음성이 외부 서버로 나가지 않도록 기기 내 인식기(API 31+)를 기본으로 쓴다.
 * 기기 내 인식기가 없을 때 시스템 기본 인식기는 서버로 음성을 보낼 수 있어서,
 * 사용자가 [SttSettings.allowSystemRecognizer]로 명시적으로 허용한 경우에만 쓴다.
 */
class SpeechToText(private val context: Context, private val settings: SttSettings) {

    enum class Engine {
        /** 기기 안에서만 인식 (createOnDeviceSpeechRecognizer) */
        ON_DEVICE,

        /** 시스템 기본 인식 서비스 (구현에 따라 서버 사용 가능, 오프라인 우선 요청) */
        SYSTEM,
    }

    sealed interface Availability {
        data class Ready(val engine: Engine) : Availability

        /** 기기 내 인식기는 없고 시스템 인식기는 있음 → 사용자 동의가 필요 */
        data object NeedsSystemConsent : Availability

        /** 쓸 수 있는 음성 인식 서비스가 없음 */
        data object Unavailable : Availability
    }

    enum class Error {
        /** 말소리를 알아듣지 못함 */
        NO_MATCH,

        /** 말이 들리지 않음 */
        NO_SPEECH,

        /** 마이크를 열 수 없음 (다른 앱·시스템 음성비서가 사용 중일 수 있음) */
        AUDIO,

        /** 인식 서비스가 다른 요청을 처리 중 */
        BUSY,
        PERMISSION,

        /** 시스템 인식기가 네트워크를 요구함 */
        NETWORK,

        /** 한국어 인식 모델이 기기에 없음 (언어 팩 다운로드 필요) */
        LANGUAGE_UNAVAILABLE,

        /** 인식 서비스 오류·연결 끊김·응답 없음 */
        SERVICE,
    }

    interface Listener {
        fun onReady() {}
        fun onSpeechStart() {}
        fun onLevel(rmsDb: Float) {}
        fun onPartial(text: String) {}

        /** 인식 결과 후보 (신뢰도 높은 순). */
        fun onResults(hypotheses: List<String>)
        fun onError(error: Error)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active: Listener? = null
    private val watchdog = Runnable { finishWithError(Error.SERVICE, "no response from recognition service") }

    /**
     * true면 빠른 종료: 부분 인식 결과가 [silenceMillis] 동안 바뀌지 않으면 말이 끝난 것으로 보고 인식을 마친다.
     * 인식기의 무음 길이 설정(EXTRA_SPEECH_INPUT_*)은 기기·엔진에 따라 무시되므로 이 앱에서 직접 판단한다.
     * 기본은 끔 (차량용은 기존 동작 그대로).
     */
    var fastEndpoint: Boolean = false
    var silenceMillis: Long = 2_000L
    private var lastPartial: String? = null
    private val endpoint = Runnable {
        if (recognizer == null) return@Runnable
        Log.i(TAG, "endpoint: silence ${silenceMillis}ms after speech")
        recognizer?.stopListening()
        main.removeCallbacks(watchdog)
        main.postDelayed(partialFallback, FINAL_WAIT_MS)
    }

    /** stopListening 뒤에도 최종 결과가 늦으면 마지막 부분 결과로 마친다. */
    private val partialFallback = Runnable {
        val listener = active ?: return@Runnable
        val text = lastPartial?.takeIf { it.isNotBlank() } ?: return@Runnable finishWithError(Error.NO_MATCH, "no final result")
        Log.i(TAG, "endpoint: final result late, using partial")
        cancel()
        listener.onResults(listOf(text))
    }

    var engine: Engine? = null
        private set

    fun availability(): Availability {
        if (isOnDeviceAvailable()) return Availability.Ready(Engine.ON_DEVICE)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return Availability.Unavailable
        return if (settings.allowSystemRecognizer) Availability.Ready(Engine.SYSTEM) else Availability.NeedsSystemConsent
    }

    /** 인식 시작. 이미 듣는 중이면 이전 요청을 취소한다. 쓸 수 있는 인식기가 없으면 false. */
    fun start(listener: Listener): Boolean {
        cancel()
        val selected = (availability() as? Availability.Ready)?.engine ?: return false
        val created = try {
            if (selected == Engine.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "recognizer creation failed", e)
            return false
        }
        recognizer = created
        engine = selected
        active = listener
        created.setRecognitionListener(Callback(created))
        Log.i(TAG, "start engine=$selected")
        return try {
            created.startListening(recognizerIntent())
            main.postDelayed(watchdog, RESPONSE_TIMEOUT_MS)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "startListening failed", e)
            release()
            false
        }
    }

    /** 말을 끝냈다고 보고 지금까지 들은 내용으로 인식을 마친다. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    /** 결과 없이 중단한다. 리스너는 더 호출되지 않는다. */
    fun cancel() {
        main.removeCallbacks(endpoint)
        main.removeCallbacks(partialFallback)
        lastPartial = null
        recognizer?.cancel()
        release()
    }

    private fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // 시스템 인식기도 가능하면 기기 안에서 처리하도록 요청 (보장은 아님)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .apply {
            if (fastEndpoint) {
                // 지원하는 엔진에서만 적용됨 (무시되면 아래 endpoint가 대신 끝낸다)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMillis)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMillis)
            }
        }

    private fun finishWithError(error: Error, reason: String) {
        val listener = active ?: return
        Log.w(TAG, "error=$error ($reason) engine=$engine")
        cancel()
        listener.onError(error)
    }

    private fun release() {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(endpoint)
        main.removeCallbacks(partialFallback)
        recognizer?.destroy()
        recognizer = null
        active = null
    }

    private inner class Callback(private val owner: SpeechRecognizer) : RecognitionListener {
        private val current: Listener? get() = if (recognizer === owner) active else null

        override fun onReadyForSpeech(params: Bundle?) {
            main.removeCallbacks(watchdog)
            // 말을 시작하지 않으면 인식기가 NO_SPEECH 오류를 보내지만, 응답이 없는 구현에 대비해 다시 감시한다
            main.postDelayed(watchdog, RESPONSE_TIMEOUT_MS + LISTEN_TIMEOUT_MS)
            current?.onReady()
        }

        override fun onBeginningOfSpeech() {
            current?.onSpeechStart()
        }

        override fun onRmsChanged(rmsdB: Float) {
            current?.onLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, RESPONSE_TIMEOUT_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = texts(partialResults).firstOrNull()
            text?.let { current?.onPartial(it) }
            // 말하는 동안은 부분 결과가 계속 바뀐다. 바뀔 때마다 무음 타이머를 다시 건다 (중간에 잠깐 쉬는 것은 허용)
            if (fastEndpoint && current != null && !text.isNullOrBlank() && text != lastPartial) {
                lastPartial = text
                main.removeCallbacks(endpoint)
                main.postDelayed(endpoint, silenceMillis)
            }
        }

        override fun onResults(results: Bundle?) {
            val listener = current ?: return
            val hypotheses = texts(results)
            // 인식 문장에는 답장 내용 등 개인 정보가 있을 수 있어 개수만 남긴다
            Log.i(TAG, "results=${hypotheses.size}")
            release()
            if (hypotheses.isEmpty()) listener.onError(Error.NO_MATCH) else listener.onResults(hypotheses)
        }

        override fun onError(error: Int) {
            if (current == null) return
            finishWithError(mapError(error), "code $error")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun texts(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun mapError(code: Int): Error = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> Error.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Error.NO_SPEECH
        SpeechRecognizer.ERROR_AUDIO -> Error.AUDIO
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Error.BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Error.PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Error.NETWORK
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> Error.LANGUAGE_UNAVAILABLE
        else -> Error.SERVICE
    }

    private companion object {
        const val TAG = "SpeechToText"
        const val LANGUAGE = "ko-KR"
        const val MAX_RESULTS = 5
        const val RESPONSE_TIMEOUT_MS = 5_000L
        const val FINAL_WAIT_MS = 1_500L
        const val LISTEN_TIMEOUT_MS = 10_000L

        // SpeechRecognizer.ERROR_LANGUAGE_* (API 31). minSdk 28이라 값으로 비교한다.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

/** 음성 인식 방식 설정. 기본은 기기 내 인식만 사용. */
class SttSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 기기 내 인식기가 없을 때 시스템 인식기(서버 사용 가능) 사용을 사용자가 허용했는지. */
    var allowSystemRecognizer: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SYSTEM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ALLOW_SYSTEM, value).apply()
        }

    private companion object {
        const val PREFS = "assistant"
        const val KEY_ALLOW_SYSTEM = "allow_system_recognizer"
    }
}
