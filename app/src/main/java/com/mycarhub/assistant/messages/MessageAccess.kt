package com.mycarhub.assistant.messages

import android.content.Intent
import com.mycarhub.assistant.core.IncomingMessage

/**
 * 메시지 앱 알림 읽기·답장. 플랫폼마다 구현이 다르다 (src/car, src/phone 의 Platform).
 *
 * - 폰: NotificationListenerService로 '현재 떠 있는' 알림만 요청 시점에 읽는다. 저장·캐시하지 않는다.
 * - 차량(Android Automotive): 메시지 알림 접근과 답장은 차량 기본 음성비서 전용이라 지원하지 않는다.
 */
interface MessageAccess {

    enum class Status {
        READY,

        /** 사용자가 알림 접근을 허용하지 않음 */
        NEEDS_ACCESS,

        /** 허용했지만 시스템이 아직 알림 서비스를 연결하지 않음 (허용 직후·앱 업데이트 직후) */
        NOT_CONNECTED,

        /** 이 플랫폼에서는 공식적으로 지원되지 않음 */
        UNSUPPORTED,
    }

    enum class ReplyResult {
        SENT,

        /** 알림이 사라져(읽음 처리·삭제) 더 이상 답장할 수 없음 */
        MESSAGE_GONE,
        NO_REPLY_ACTION,
        NO_ACCESS,
        FAILED,
    }

    val status: Status

    /** 알림 접근 설정 화면 (지원하지 않는 플랫폼이면 null). */
    fun accessSettingsIntent(): Intent?

    /** 현재 알림에 있는 수신 메시지. 백그라운드 스레드에서 호출한다. */
    fun currentMessages(): List<IncomingMessage>

    /** [message] 알림의 RemoteInput 답장 동작으로 [text]를 보낸다. 사용자 확인 뒤에만 호출한다. */
    fun reply(message: IncomingMessage, text: String): ReplyResult
}

/** 메시지 기능을 지원하지 않는 플랫폼 (차량용). 아무것도 읽거나 보내지 않는다. */
object UnsupportedMessageAccess : MessageAccess {
    override val status = MessageAccess.Status.UNSUPPORTED
    override fun accessSettingsIntent(): Intent? = null
    override fun currentMessages(): List<IncomingMessage> = emptyList()
    override fun reply(message: IncomingMessage, text: String) = MessageAccess.ReplyResult.NO_ACCESS
}
