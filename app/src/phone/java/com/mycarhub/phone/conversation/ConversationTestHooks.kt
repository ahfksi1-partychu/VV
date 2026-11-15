package com.mycarhub.phone.conversation

import com.mycarhub.assistant.core.IncomingMessage

/**
 * 개발 빌드의 ADB 명령 테스트용 연결점. 만드는 곳은 phoneDebug 소스의 DebugCommandReceiver뿐이라 릴리스 빌드에는 구현이 없고,
 * [PhoneConversation]도 BuildConfig.DEBUG일 때만 쓴다.
 *
 * 음성 인식 이후의 명령 처리(의도 판단·앱/사람 찾기·되묻기)는 실제와 같은 코드로 돌리고,
 * 바깥으로 나가는 동작(소리·마이크·권한 화면·앱 실행·대화방 열기·답장 전송)만 실행하지 않고 기록한다.
 */
interface ConversationTestHooks {

    /** 테스트로 넣은 가짜 알림. [isMessage]면 메시지 알림, 아니면 일반 앱 알림처럼 다룬다. */
    data class TestNotification(val message: IncomingMessage, val isMessage: Boolean)

    /** 실제 알림 목록 뒤에 더할 가짜 알림 */
    val extraMessages: List<TestNotification>

    /** Drive To-Do(할 일·차에 둔 물건) 명령을 실제로 저장할지 (기본: 저장하지 않고 대답만) */
    val saveDriveData: Boolean get() = false

    /** 소리 대신: 비서가 할 말 */
    fun onSay(text: String)

    /** 마이크 대신: 이 단계의 대답을 기다림 */
    fun onListen(step: PhoneConversation.Step)

    /** 실행하지 않은 바깥 동작 (launch, open_room, reply_blocked, permission_missing) */
    fun onAction(action: String, detail: String)

    fun onEnd(end: PhoneConversation.End?)
}
