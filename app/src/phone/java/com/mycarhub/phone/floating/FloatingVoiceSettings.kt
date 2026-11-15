package com.mycarhub.phone.floating

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.mycarhub.MyCarHubApplication
import com.mycarhub.phone.PhoneVoice

/**
 * 상시 호출어 듣기([FloatingVoiceService])의 켜짐 여부와 필요한 권한 확인.
 *
 * 켜짐/꺼짐은 메인 화면·설정의 '호출어 듣기'와 같은 값([PhoneVoice.listenEnabled]) 하나로 관리한다.
 * - 꼭 필요: 호출어 등록, 마이크 권한
 * - 다른 앱 사용 중 호출: '다른 앱 위에 표시' (대화 창을 띄우고, 백그라운드에서 앱을 실행하려면 필요)
 * - 선택: 알림 권한 (없어도 서비스는 동작하지만 알림창의 [듣기 중지] 버튼이 보이지 않는다)
 */
class FloatingVoiceSettings(private val context: Context) {

    var enabled: Boolean
        get() = PhoneVoice.listenEnabled(context)
        set(value) = PhoneVoice.setListenEnabled(context, value)

    /** 다른 앱 위 플로팅 버튼 표시 (숨겨도 호출어 듣기·지속 알림·마이크 사용 표시는 그대로) */
    var showBubble: Boolean
        get() = PhoneVoice.showBubble(context)
        set(value) = PhoneVoice.setShowBubble(context, value)

    val hasWakeWord: Boolean get() = (context.applicationContext as MyCarHubApplication).wakeWords.model != null

    val hasMicPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Android 13+: 듣기 중지 버튼이 있는 지속 알림을 보이려면 필요 */
    val hasNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val canDrawOverlays: Boolean get() = Settings.canDrawOverlays(context)

    /** 서비스를 시작할 수 있는지 (다른 앱 위 표시는 백그라운드 호출에만 필요해 여기서 묻지 않는다) */
    val isReady: Boolean get() = hasWakeWord && hasMicPermission
}
