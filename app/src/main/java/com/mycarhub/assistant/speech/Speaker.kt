package com.mycarhub.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Android TextToSpeech로 음성비서 응답을 읽는다. 메인 스레드에서 호출한다.
 *
 * 메시지 내용이 외부 서버로 나가지 않도록 기기에 설치된(네트워크가 필요 없는) 한국어 음성만 쓴다.
 * 그런 음성이 없으면 읽지 않고 [speak]가 false로 끝나며, 화면에 글자로만 보여준다.
 */
class Speaker(context: Context) {

    enum class State { INITIALIZING, READY, NO_OFFLINE_VOICE, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())
    private var pending: Pair<String, (Boolean) -> Unit>? = null
    private var current: Pending? = null
    private var nextId = 0

    var state = State.INITIALIZING
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    private class Pending(val id: String, val onDone: (Boolean) -> Unit, val timeout: Runnable)

    /** [text]를 읽고 끝나면 [onDone] (읽었으면 true). 이전에 읽던 것은 중단한다. */
    fun speak(text: String, onDone: (spoken: Boolean) -> Unit) {
        stop()
        when (state) {
            State.INITIALIZING -> pending = text to onDone
            State.READY -> start(text, onDone)
            State.NO_OFFLINE_VOICE, State.UNAVAILABLE -> main.post { onDone(false) }
        }
    }

    /** 읽기 중단. 대기 중인 콜백은 false로 끝낸다. */
    fun stop() {
        pending?.let { (_, onDone) -> main.post { onDone(false) } }
        pending = null
        current?.let { finish(it.id, spoken = false) }
        if (state == State.READY) tts.stop()
    }

    fun shutdown() {
        pending = null
        current?.let { main.removeCallbacks(it.timeout) }
        current = null
        tts.shutdown()
    }

    private fun onInit(status: Int) {
        state = if (status != TextToSpeech.SUCCESS) State.UNAVAILABLE else selectOfflineKoreanVoice()
        Log.i(TAG, "tts state=$state engine=${tts.defaultEngine}")
        if (state == State.READY) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}

                override fun onDone(utteranceId: String) {
                    main.post { finish(utteranceId, spoken = true) }
                }

                @Suppress("OVERRIDE_DEPRECATION") // 추상 메서드라 구현은 필요 (API 21 이후 onError(id, code) 사용)
                override fun onError(utteranceId: String) {
                    main.post { finish(utteranceId, spoken = false) }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    main.post { finish(utteranceId, spoken = false) }
                }
            })
        }
        pending?.let { (text, onDone) ->
            pending = null
            speak(text, onDone)
        }
    }

    private fun selectOfflineKoreanVoice(): State {
        val voices: Set<Voice> = try {
            tts.voices.orEmpty()
        } catch (e: RuntimeException) {
            emptySet()
        }
        val offline = voices
            .filter { it.locale.language == Locale.KOREAN.language }
            .filter { !it.isNetworkConnectionRequired }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxByOrNull { it.quality }
            ?: return State.NO_OFFLINE_VOICE
        return if (tts.setVoice(offline) == TextToSpeech.SUCCESS) State.READY else State.NO_OFFLINE_VOICE
    }

    private fun start(text: String, onDone: (Boolean) -> Unit) {
        val id = "mycarhub-${nextId++}"
        val timeout = Runnable { finish(id, spoken = false) }
        current = Pending(id, onDone, timeout)
        // 응답이 오지 않는 엔진에 대비: 글자 수에 비례한 시간 뒤 끝난 것으로 본다
        main.postDelayed(timeout, BASE_TIMEOUT_MS + text.length * PER_CHAR_TIMEOUT_MS)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, spoken = false)
    }

    private fun finish(id: String, spoken: Boolean) {
        val done = current?.takeIf { it.id == id } ?: return
        current = null
        main.removeCallbacks(done.timeout)
        done.onDone(spoken)
    }

    private companion object {
        const val TAG = "Speaker"
        const val BASE_TIMEOUT_MS = 5_000L
        const val PER_CHAR_TIMEOUT_MS = 250L
    }
}
