package com.mycarhub.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mycarhub.phone.drive.TodoCandidateCollector

/**
 * 알림 접근 연결점 (폰 테스트용 빌드 전용).
 *
 * 알림이 올 때마다 받아서 저장하지 않는다 (Drive To-Do 할 일 후보를 켠 경우에만 후보 제목·출처를 저장). 사용자가 "메시지 읽어줘"라고 할 때만
 * [NotificationMessageAccess]가 이 서비스로 '지금 떠 있는' 알림을 조회한다.
 * 그래서 앱이 종료·재시작되어도 메시지 기록이 남지 않는다.
 */
class MessageListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
    }

    /** Drive To-Do: 사용자가 켠 경우에만 새 문자·카카오톡 알림에서 할 일 후보를 만든다 (본문은 저장하지 않음). */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        TodoCandidateCollector.onPosted(this, sbn)
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var connected: MessageListenerService? = null
            private set
    }
}
