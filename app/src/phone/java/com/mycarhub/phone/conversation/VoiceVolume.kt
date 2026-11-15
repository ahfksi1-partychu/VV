package com.mycarhub.phone.conversation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.mycarhub.assistant.core.VolumeRule

/**
 * 음성 안내 음량 확인 (폰 빌드).
 *
 * - 음성 안내와 같은 오디오 속성([ATTRIBUTES])이 쓰는 음량 스트림을 본다. Android는 출력 장치마다 음량을 따로 기억하므로
 *   차량 블루투스(A2DP)로 나가는 중이면 그 장치의 음량이, 아니면 휴대폰 스피커 음량이 읽힌다.
 * - 음량을 앱이 마음대로 올리지 않는다. 작으면 시스템 음량 조절 화면을 띄우고(값은 그대로), [음량 높이기]를 누를 때마다
 *   한 단계씩만 올린다 (사용자가 누른 경우만). 모두 일반 앱에 허용된 AudioManager 공개 API이며 권한이 필요 없다.
 */
object VoiceVolume {

    private const val TAG = "VoiceVolume"

    /** 음성 안내(TTS)의 오디오 속성. [PhoneSpeaker]도 같은 값을 쓴다. */
    val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    data class Level(
        val stream: Int,
        val current: Int,
        val min: Int,
        val max: Int,
        val muted: Boolean,
        /** 지금 음성 안내가 나갈 출력: 블루투스 기기 이름 (휴대폰 스피커면 null) */
        val bluetoothName: String?,
        val bluetooth: Boolean,
    ) {
        val low: Boolean get() = VolumeRule.isLow(current, min, max, muted)
    }

    fun read(context: Context): Level? = try {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = publicStream(ATTRIBUTES.volumeControlStream)
        val output = currentOutput(audio)
        Level(
            stream = stream,
            current = audio.getStreamVolume(stream),
            min = audio.getStreamMinVolume(stream),
            max = audio.getStreamMaxVolume(stream),
            muted = audio.isStreamMute(stream),
            bluetoothName = output?.productName?.toString()?.takeIf { it.isNotBlank() },
            bluetooth = output != null,
        )
    } catch (e: RuntimeException) {
        Log.w(TAG, "volume read failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * 음성 안내(USAGE_ASSISTANT)는 Android 10+에서 내부용 STREAM_ASSISTANT를 돌려주는데, 공개 API(getStreamMinVolume 등)는
     * 이 값을 받지 않는다 (에뮬레이터 Android 14에서 IllegalArgumentException 확인). 휴대폰에서는 이 스트림이 미디어 음량에
     * 묶여 있으므로(dumpsys audio: "STREAM_ASSISTANT (aliased to: STREAM_MUSIC)") 공개 스트림이 아니면 미디어 음량을 본다.
     */
    private fun publicStream(stream: Int): Int = if (stream in PUBLIC_STREAMS) stream else AudioManager.STREAM_MUSIC

    private val PUBLIC_STREAMS = setOf(
        AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_DTMF, AudioManager.STREAM_ACCESSIBILITY,
    )

    /** 시스템 음량 조절 화면만 띄운다 (값은 바꾸지 않음) */
    fun showSystemPanel(context: Context, stream: Int) = adjust(context, stream, AudioManager.ADJUST_SAME)

    /** 사용자가 [음량 높이기]를 눌렀을 때: 음소거면 풀고, 아니면 한 단계 올린다 (시스템 음량 화면이 함께 보임) */
    fun raiseOneStep(context: Context, level: Level) =
        adjust(context, level.stream, if (level.muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_RAISE)

    private fun adjust(context: Context, stream: Int, direction: Int) {
        try {
            context.getSystemService(AudioManager::class.java).adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            // 방해 금지 모드 등으로 시스템이 거부한 경우: 화면 안내만 남는다
            Log.w(TAG, "volume adjust refused")
        }
    }

    /** 음성 안내가 지금 나갈 블루투스 출력 (Android 13+: 그 속성의 실제 경로, 그 전: 연결된 블루투스 미디어 출력) */
    private fun currentOutput(audio: AudioManager): AudioDeviceInfo? {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed = try {
                audio.getAudioDevicesForAttributes(ATTRIBUTES)
            } catch (e: RuntimeException) {
                null
            }
            if (routed != null) {
                // 실제 경로가 휴대폰 스피커면 블루투스가 연결되어 있어도 null
                return outputs.firstOrNull { d -> d.type in BLUETOOTH_OUTPUTS && routed.any { it.type == d.type && it.address == d.address } }
            }
        }
        return outputs.firstOrNull { it.type in BLUETOOTH_OUTPUTS }
    }

    private val BLUETOOTH_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
}
