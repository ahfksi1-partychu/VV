package com.mycarhub.phone.conversation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/** '음성 및 말하기' 설정 (폰 빌드). 앱을 다시 실행해도 유지된다. */
class VoiceOutputSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 고른 TTS 음성 이름. null이면 엔진 기본 한국어 음성 */
    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    /** 말하기 속도 0.5~2.0배 (0.1 단위) */
    var rate: Float
        get() = normalizeRate(prefs.getFloat(KEY_RATE, DEFAULT_RATE))
        set(value) = prefs.edit().putFloat(KEY_RATE, normalizeRate(value)).apply()

    companion object {
        private const val PREFS = "phone_voice_output"
        private const val KEY_VOICE = "voice_name"
        private const val KEY_RATE = "speech_rate"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val DEFAULT_RATE = 1.0f

        fun normalizeRate(rate: Float): Float = ((rate * 10).roundToInt() / 10f).coerceIn(MIN_RATE, MAX_RATE)
    }
}

/**
 * 폰 빌드의 음성 응답(TTS). 메인 스레드에서 호출한다.
 *
 * - 메시지 내용이 외부 서버로 나가지 않도록 기기에 설치된(네트워크가 필요 없는) 한국어 음성만 쓴다.
 * - 말할 때마다 설정의 음성·속도를 적용하고, 말하는 동안 음악 등은 잠시 줄인다(오디오 포커스).
 * - 출력 경로는 시스템이 정한다: 차량 블루투스(미디어)가 연결되어 있으면 그쪽으로 나간다.
 */
class PhoneSpeaker(context: Context, private val settings: VoiceOutputSettings = VoiceOutputSettings(context)) {

    enum class State { INITIALIZING, READY, NO_OFFLINE_VOICE, UNAVAILABLE }

    enum class Gender { FEMALE, MALE }

    /** 이 기기 엔진에서 실제로 쓸 수 있는 한국어 음성. [gender]는 엔진의 음성 이름으로 알 수 있을 때만. */
    data class VoiceOption(val name: String, val gender: Gender?, val quality: Int)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(AudioManager::class.java)
    // 음량 확인([VoiceVolume])과 같은 속성이어야 같은 출력 경로·음량을 본다
    private val attributes: AudioAttributes = VoiceVolume.ATTRIBUTES
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private class Request(val text: String, val voiceName: String?, val rate: Float, val onDone: (Boolean) -> Unit)

    private var pending: Request? = null
    private var current: Utterance? = null
    private var nextId = 0
    private var voices: List<Voice> = emptyList()
    private var readyListeners = mutableListOf<() -> Unit>()

    var state = State.INITIALIZING
        private set

    /** 엔진 이름 (진단·설정 화면 표시용) */
    var engine: String? = null
        private set

    private val tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }

    private class Utterance(val id: String, val onDone: (Boolean) -> Unit, val timeout: Runnable)

    /** 초기화가 끝나면(이미 끝났으면 바로) [block]. */
    fun whenReady(block: () -> Unit) {
        if (state == State.INITIALIZING) readyListeners += block else block()
    }

    /** 설정 화면용: 쓸 수 있는 한국어 음성 목록 (품질 높은 순). */
    fun voiceOptions(): List<VoiceOption> = voices.map { VoiceOption(it.name, genderOf(it), it.quality) }

    /** [text]를 설정한 음성·속도로 읽고 끝나면 [onDone] (실제로 읽었으면 true). 이전에 읽던 것은 중단한다. */
    fun speak(text: String, onDone: (spoken: Boolean) -> Unit) = speak(text, settings.voiceName, settings.rate, onDone)

    /** 미리 듣기: 저장하지 않은 음성·속도로 읽는다. */
    fun speak(text: String, voiceName: String?, rate: Float, onDone: (spoken: Boolean) -> Unit) {
        stop()
        when (state) {
            State.INITIALIZING -> pending = Request(text, voiceName, rate, onDone)
            State.READY -> start(text, voiceName, rate, onDone)
            State.NO_OFFLINE_VOICE, State.UNAVAILABLE -> main.post { onDone(false) }
        }
    }

    /** 읽기 중단. 대기 중인 콜백은 false로 끝낸다. */
    fun stop() {
        pending?.let { request -> main.post { request.onDone(false) } }
        pending = null
        current?.let { finish(it.id, spoken = false) }
        if (state == State.READY) tts.stop()
    }

    fun shutdown() {
        pending = null
        if (current != null) {
            selfSpeaking = false
            selfSpokeEndMs = SystemClock.elapsedRealtime()
        }
        current?.let { main.removeCallbacks(it.timeout) }
        current = null
        readyListeners.clear()
        audio.abandonAudioFocusRequest(focus)
        tts.shutdown()
    }

    private fun onInit(status: Int) {
        engine = runCatching { tts.defaultEngine }.getOrNull()
        voices = if (status == TextToSpeech.SUCCESS) offlineKoreanVoices() else emptyList()
        state = when {
            status != TextToSpeech.SUCCESS -> State.UNAVAILABLE
            voices.isEmpty() -> State.NO_OFFLINE_VOICE
            else -> State.READY
        }
        Log.i(TAG, "tts state=$state engine=$engine voices=${voices.map { "${it.name}:${genderOf(it) ?: "?"}" }}")
        if (state == State.READY) {
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}

                override fun onDone(utteranceId: String) {
                    main.post { finish(utteranceId, spoken = true) }
                }

                @Suppress("OVERRIDE_DEPRECATION") // 추상 메서드라 구현은 필요
                override fun onError(utteranceId: String) {
                    main.post { finish(utteranceId, spoken = false) }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    Log.w(TAG, "tts error $errorCode")
                    main.post { finish(utteranceId, spoken = false) }
                }
            })
        }
        val waiting = readyListeners.toList()
        readyListeners.clear()
        waiting.forEach { it() }
        pending?.let { request ->
            pending = null
            speak(request.text, request.voiceName, request.rate, request.onDone)
        }
    }

    private fun offlineKoreanVoices(): List<Voice> {
        val all: Set<Voice> = try {
            tts.voices.orEmpty()
        } catch (e: RuntimeException) {
            Log.w(TAG, "voices failed: ${e.javaClass.simpleName}")
            emptySet()
        }
        // 진단: 엔진이 준 한국어 음성과 필터 조건 (음성 이름·속성만, 사용자 데이터 없음)
        Log.i(TAG, "engine voices total=${all.size} default=${runCatching { tts.defaultVoice?.name }.getOrNull()} koAvailable=${tts.isLanguageAvailable(Locale.KOREAN)}")
        all.filter(::isKorean)
            .forEach { Log.i(TAG, "voice ${it.name} locale=${it.locale} lang=${it.locale.language} network=${it.isNetworkConnectionRequired} features=${it.features}") }
        return all
            .filter(::isKorean)
            .filter { !it.isNetworkConnectionRequired }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
    }

    /** 삼성 SMT는 언어를 ISO 639-2 코드("kor")로 준다: 2글자("ko")만 비교하면 한국어 음성이 모두 빠진다. */
    private fun isKorean(voice: Voice): Boolean =
        voice.locale.language == Locale.KOREAN.language ||
            runCatching { voice.locale.isO3Language }.getOrNull() == Locale.KOREAN.isO3Language

    private fun start(text: String, voiceName: String?, rate: Float, onDone: (Boolean) -> Unit) {
        // 고른 음성이 지워졌거나 없으면 가장 품질 좋은 음성
        val voice = voices.firstOrNull { it.name == voiceName } ?: voices.first()
        if (tts.voice?.name != voice.name) tts.setVoice(voice)
        tts.setSpeechRate(VoiceOutputSettings.normalizeRate(rate))
        val id = "mycarhub-phone-${nextId++}"
        val timeout = Runnable { finish(id, spoken = false) }
        current = Utterance(id, onDone, timeout)
        selfSpeaking = true
        // 응답이 오지 않는 엔진에 대비: 글자 수와 속도에 비례한 시간 뒤 끝난 것으로 본다
        main.postDelayed(timeout, BASE_TIMEOUT_MS + (text.length * PER_CHAR_TIMEOUT_MS / rate.coerceAtLeast(0.5f)).toLong())
        audio.requestAudioFocus(focus)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finish(id, spoken = false)
    }

    private fun finish(id: String, spoken: Boolean) {
        val done = current?.takeIf { it.id == id } ?: return
        current = null
        selfSpeaking = false
        selfSpokeEndMs = SystemClock.elapsedRealtime()
        main.removeCallbacks(done.timeout)
        audio.abandonAudioFocusRequest(focus)
        done.onDone(spoken)
    }

    companion object {
        private const val TAG = "PhoneSpeaker"

        /** 이 앱의 TTS가 지금 말하는 중인지. 호출어 감지가 자기 음성에 반응하지 않도록 쓴다. */
        @Volatile
        var selfSpeaking = false
            private set

        /** 마지막 TTS가 끝난 시각 (SystemClock.elapsedRealtime). */
        @Volatile
        var selfSpokeEndMs = 0L
            private set
        private const val BASE_TIMEOUT_MS = 5_000L
        private const val PER_CHAR_TIMEOUT_MS = 250L

        /**
         * 음성 이름으로 알 수 있는 성별. Android Voice API에는 성별 정보가 없어서 엔진의 이름 규칙으로만 판단하고,
         * 알 수 없으면 null (설정 화면에서 '목소리 1, 2…'로 보여주고 미리 듣기로 고르게 한다).
         */
        fun genderOf(voice: Voice): Gender? = genderOf(voice.name, voice.features.orEmpty())

        internal fun genderOf(name: String, features: Set<String>): Gender? {
            val lower = name.lowercase(Locale.ROOT)
            val tags = features.map { it.lowercase(Locale.ROOT) }
            return when {
                "female" in lower || tags.any { "female" in it } -> Gender.FEMALE
                "male" in lower || tags.any { it.contains("male") } -> Gender.MALE
                // 삼성 TTS: ko-KR-SMTf00 (여성), ko-KR-SMTm00 (남성)
                Regex("smt([fm])\\d").find(lower)?.groupValues?.get(1) == "f" -> Gender.FEMALE
                Regex("smt([fm])\\d").find(lower)?.groupValues?.get(1) == "m" -> Gender.MALE
                else -> null
            }
        }
    }
}
