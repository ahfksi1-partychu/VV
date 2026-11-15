package com.mycarhub.phone

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 폰 빌드의 호출어 듣기 설정.
 *
 * 폰 빌드는 상시 듣기 서비스(FloatingVoiceService)가 보완된 감지기(BackgroundWakeWordListener)로 듣는다.
 * [listenEnabled]가 그 서비스의 켜짐/꺼짐이다 (MyCarHub 화면과 다른 앱 사용 중 모두).
 * main 코드의 WakeWordManager가 같은 마이크를 따로 열지 않도록, main의 듣기 설정(wake_word.listen_enabled)은 항상 끄고
 * 사용자가 고른 켜기·끄기는 이 설정에 따로 저장한다. 호출어 모델과 녹음은 건드리지 않는다.
 */
object PhoneVoice {
    private const val PREFS = "phone_voice"
    private const val KEY_LISTEN = "listen_enabled"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_SHOW_BUBBLE = "show_bubble"
    private const val KEY_SETUP_POSTPONED = "setup_postponed"
    private const val KEY_AUTO_GUIDED = "auto_guided"
    private const val MAIN_PREFS = "wake_word"
    private const val MAIN_KEY_LISTEN = "listen_enabled"
    private const val TAG = "PhoneTest"

    fun listenEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LISTEN, true)

    fun setListenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LISTEN, enabled).apply()
    }

    /** 다른 앱 위 플로팅 버튼을 보일지 (숨겨도 호출어 듣기는 계속된다). 사용자가 숨기면 다시 켤 때까지 숨긴 채로 둔다. */
    fun showBubble(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_BUBBLE, true)

    fun setShowBubble(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_BUBBLE, show).apply()
    }

    /** 상시 듣기 초기 설정을 마쳤는지 (마쳤으면 앱을 열 때마다 설정 안내를 다시 띄우지 않는다) */
    fun setupDone(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /** 설정 안내에서 [나중에]를 눌렀는지 (자동으로 다시 띄우지 않는다. 메인 화면의 버튼으로는 열 수 있다) */
    fun setupPostponed(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_POSTPONED, false)

    fun setSetupPostponed(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_POSTPONED, true).apply()
    }

    /** 처음 설치 후 설정 안내(권한 요청)를 자동으로 띄운 적이 있는지 (자동 안내는 한 번만, 그 뒤에는 메인 화면 버튼으로) */
    fun autoGuided(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_GUIDED, false)

    fun setAutoGuided(context: Context) {
        prefs(context).edit().putBoolean(KEY_AUTO_GUIDED, true).apply()
    }

    /** 앱 시작 시 한 번: main 화면의 자체 듣기를 끈다 (사용자 설정은 [listenEnabled]에 유지). */
    fun takeOverListening(context: Context) {
        val main = context.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
        if (main.getBoolean(MAIN_KEY_LISTEN, true)) main.edit().putBoolean(MAIN_KEY_LISTEN, false).apply()
        Log.i(TAG, "wake word listening handled by phone listener (enabled=${listenEnabled(context)})")
    }

    /** 호출어 감지 알림음 (짧은 '띵'). 이어지는 음성 인식에 섞이지 않도록 짧게 낸다. */
    fun playDetectedTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MILLIS)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, TONE_MILLIS + 100L)
        } catch (e: RuntimeException) {
            Log.w(TAG, "detected tone unavailable", e)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TONE_VOLUME = 80
    private const val TONE_MILLIS = 150
}
