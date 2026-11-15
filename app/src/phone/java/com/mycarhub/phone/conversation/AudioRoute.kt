package com.mycarhub.phone.conversation

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 지금 음성 안내가 나가는 곳과 블루투스 마이크 유무 (확인만 하고 경로를 바꾸지 않는다).
 *
 * - 출력: 음성 안내(USAGE_ASSISTANT)는 시스템이 경로를 정한다. 차량과 블루투스 미디어(A2DP)로 연결되어 있으면
 *   차량 스피커로 나가고, 아니면 휴대폰 스피커로 나간다.
 * - 입력: 음성 인식은 휴대폰 마이크를 쓴다. 차량 마이크는 통화용 블루투스(SCO) 연결을 따로 열어야 해서
 *   아직 지원하지 않는다 (차량에 블루투스 마이크가 보이는지만 알려준다).
 */
object AudioRoute {

    data class Status(
        /** 연결된 블루투스 오디오 출력 이름 (없으면 null) */
        val bluetoothOutput: String?,
        /** 블루투스 통화(SCO) 마이크를 가진 기기가 연결되어 있는지 */
        val bluetoothMicAvailable: Boolean,
    )

    fun status(context: Context): Status {
        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val output = outputs.firstOrNull { it.type in BLUETOOTH_OUTPUTS }
        return Status(
            bluetoothOutput = output?.productName?.toString()?.takeIf { it.isNotBlank() } ?: output?.let { "" },
            bluetoothMicAvailable = inputs.any { it.type in BLUETOOTH_INPUTS },
        )
    }

    private val BLUETOOTH_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
    private val BLUETOOTH_INPUTS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
}
