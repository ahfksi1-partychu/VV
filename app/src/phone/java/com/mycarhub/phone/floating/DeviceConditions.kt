package com.mycarhub.phone.floating

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * 백그라운드 감지를 쉬어야 하는 기기 상태: 화면 꺼짐·잠금, 통화(전화·인터넷 통화).
 *
 * 통화 감지는 READ_PHONE_STATE 권한 없이 오디오 모드(통화·통신 모드)로 판단한다.
 * 이 모드는 일반 전화와 카카오톡 보이스톡 같은 인터넷 통화에 모두 쓰인다.
 */
class DeviceConditions(private val context: Context, private val onChanged: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var modeListener: Any? = null

    /** 화면이 꺼졌거나 잠금 화면 */
    val isScreenOffOrLocked: Boolean get() = !power.isInteractive || keyguard.isKeyguardLocked

    /** 전화 수신·통화 중이거나 인터넷 통화 중 */
    val isInCall: Boolean
        get() = audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onChanged()
    }

    private val modePoll = object : Runnable {
        override fun run() {
            onChanged()
            main.postDelayed(this, MODE_POLL_MS)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // 시스템 방송만 받으므로 다른 앱에는 열지 않는다 (Android 14+ targetSdk 34는 명시 필요)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { onChanged() }
            audio.addOnModeChangedListener(context.mainExecutor, listener)
            modeListener = listener
        } else {
            main.postDelayed(modePoll, MODE_POLL_MS)
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // 등록 전
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let(audio::removeOnModeChangedListener)
        }
        modeListener = null
        main.removeCallbacks(modePoll)
    }

    private companion object {
        const val MODE_POLL_MS = 2_000L
    }
}
