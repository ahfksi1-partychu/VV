package com.mycarhub.phone.drive

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mycarhub.assistant.core.TodoSuggester
import com.mycarhub.drive.TodoCandidate
import com.mycarhub.drive.TodoItem
import com.mycarhub.drive.TodoStore
import com.mycarhub.phone.NotificationMessageAccess

/**
 * 새 문자·카카오톡 알림에서 할 일 후보를 만든다 (사용자가 Drive To-Do 설정에서 동의해 켰을 때만, 기기 안에서만).
 *
 * 규칙에 분명히 맞는 알림만 후보로 만들고(택배 배송 완료, 일정 변경, 1:1 대화의 질문·요청), 저장하는 것은
 * 만든 제목·출처 앱·알림 키뿐이다. 메시지 본문과 보낸 사람 전화번호는 저장하지 않는다.
 */
object TodoCandidateCollector {

    private const val TAG = "TodoCandidates"

    private val SOURCES = mapOf(
        "com.kakao.talk" to TodoItem.Source.KAKAO,
        "com.samsung.android.messaging" to TodoItem.Source.SMS,
        "com.google.android.apps.messaging" to TodoItem.Source.SMS,
        "com.android.mms" to TodoItem.Source.SMS,
    )

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        val source = SOURCES[sbn.packageName] ?: return
        if (!PhoneDriveSettings(context).analyzeNotifications) return
        val message = NotificationMessageAccess(context).messageOf(sbn) ?: return
        // 번호로만 온 문자(택배사·인증번호 등)는 사람과의 대화로 보지 않는다 (답장 후보를 만들지 않음)
        val person = message.sender.none { it.isDigit() }
        val suggestion = TodoSuggester.suggest(message.sender, message.text, isGroup = message.conversation != null, isPersonChat = person)
            ?: return
        val added = TodoStore(context).addCandidate(
            TodoCandidate(
                id = TodoItem.newId(),
                title = suggestion.title,
                kind = suggestion.kind.name,
                source = source,
                appPackage = sbn.packageName,
                notificationKey = sbn.key,
                createdAt = System.currentTimeMillis(),
            ),
        )
        // 제목에 이름이 들어갈 수 있어 종류만 남긴다
        Log.i(TAG, "candidate kind=${suggestion.kind} source=$source added=$added")
    }
}
