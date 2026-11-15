package com.mycarhub.assistant.core

import java.time.LocalDateTime

/** 음성 알림·타이머 명령 해석 검사 */
internal fun reminderChecks(check: (String, Any?, Any?) -> Unit) {
    val now = LocalDateTime.of(2026, 11, 15, 22, 0, 0)
    fun schedule(text: String) = ReminderParser.parse(text, now, "쁘이") as? ReminderRequest.Schedule

    schedule("2분 뒤에 설거지하라고 알림 켜줘").let {
        check("알림 2분 뒤 시각", it?.at, now.plusMinutes(2))
        check("알림 2분 뒤 내용", it?.message, "설거지할 시간이에요")
        check("알림 2분 뒤 대답", it?.spokenWhen, "2분 뒤에")
    }
    schedule("10분 후에 빨래 꺼내라고 알려줘").let {
        check("알림 10분 후 시각", it?.at, now.plusMinutes(10))
        check("알림 10분 후 내용", it?.message, "빨래 꺼낼 시간이에요")
    }
    schedule("내일 아침 8시에 약 먹으라고 알려줘").let {
        check("알림 내일 아침 8시 시각", it?.at, LocalDateTime.of(2026, 11, 16, 8, 0))
        check("알림 내일 아침 8시 내용", it?.message, "약 먹을 시간이에요")
        check("알림 내일 아침 8시 대답", it?.spokenWhen, "내일 오전 8시에")
    }
    schedule("30분 타이머 맞춰줘").let {
        check("타이머 30분 시각", it?.at, now.plusMinutes(30))
        check("타이머 30분 표시", it?.timer, true)
        check("타이머 30분 내용", it?.message, "30분 타이머가 끝났어요")
    }
    check("방금 알림 취소", ReminderParser.parse("방금 알림 취소해 줘", now), ReminderRequest.CancelLast)
    check("타이머 취소", ReminderParser.parse("타이머 취소해줘", now), ReminderRequest.CancelLast)

    // 다양한 표현
    check("한 시간 반 있다가", schedule("한 시간 반 있다가 알려줘")?.at, now.plusMinutes(90))
    check("1시간 30분 뒤", schedule("1시간 30분 뒤에 회의 들어가라고 알려줘")?.at, now.plusMinutes(90))
    check("5분 뒤 기억해 줘", schedule("5분 뒤에 전화하라고 기억해 줘")?.message, "전화할 시간이에요")
    check("호출어 붙음", schedule("쁘이 3분 후에 알려줘")?.at, now.plusMinutes(3))
    check("내용 없는 알림", schedule("3분 후에 알려줘")?.message, ReminderParser.DEFAULT_MESSAGE)
    check("오후 2시 반", schedule("오후 2시 반에 장 보라고 알려줘")?.at, LocalDateTime.of(2026, 11, 16, 14, 30))
    check("21시 30분", schedule("21시 30분에 알림 켜줘")?.at, LocalDateTime.of(2026, 11, 16, 21, 30))
    check("타이머 숫자 말", schedule("타이머 삼십 분 맞춰 줘")?.at, now.plusMinutes(30))
    check("세 시간 뒤", schedule("세 시간 뒤에 알려줘")?.at, now.plusHours(3))
    check("다고 기억", schedule("10분 뒤에 회의 있다고 기억해 줘")?.message, "회의 있다는 거 잊지 마세요")

    // 모호하면 다시 묻는다
    check("오전/오후 모름", ReminderParser.parse("8시에 약 먹으라고 알려줘", now) is ReminderRequest.NeedAmPm, true)
    check("시간 없음", ReminderParser.parse("설거지하라고 알려줘", now) is ReminderRequest.NeedTime, true)
    check("있다가만", ReminderParser.parse("있다가 알려줘", now) is ReminderRequest.NeedTime, true)
    check("시간 없는 타이머", ReminderParser.parse("타이머 맞춰줘", now) is ReminderRequest.NeedTime, true)
    check("오늘 지난 시각", ReminderParser.parse("오늘 오전 9시에 알려줘", now), ReminderRequest.NeedTime(ReminderDraft(ReminderParser.DEFAULT_MESSAGE, false), past = true))
    (ReminderParser.parse("8시에 약 먹으라고 알려줘", now) as? ReminderRequest.NeedAmPm)?.let {
        check("오전 선택", (ReminderParser.withAmPm(it, pm = false, now) as? ReminderRequest.Schedule)?.at, LocalDateTime.of(2026, 11, 16, 8, 0))
        check("오후 선택 (지남 → 내일)", (ReminderParser.withAmPm(it, pm = true, now) as? ReminderRequest.Schedule)?.at, LocalDateTime.of(2026, 11, 16, 20, 0))
    }
    val draft = ReminderDraft("설거지할 시간이에요", timer = false)
    check("되물은 대답 10분", (ReminderParser.answer("10분", now, draft) as? ReminderRequest.Schedule)?.at, now.plusMinutes(10))
    check("되물은 대답 10분 뒤", (ReminderParser.answer("10분 뒤에", now, draft) as? ReminderRequest.Schedule)?.spokenWhen, "10분 뒤에")
    check("되물은 대답 없음", ReminderParser.answer("몰라", now, draft), null)

    // 다른 명령은 가져가지 않는다
    check("답장은 알림 아님", ReminderParser.parse("30분 뒤에 도착한다고 답장해 줘", now), null)
    check("할 일 목록은 알림 아님", ReminderParser.parse("할 일 알려줘", now), null)
    check("주차 위치는 알림 아님", ReminderParser.parse("차 어디 세웠는지 알려줘", now), null)
    check("메시지 읽기는 알림 아님", ReminderParser.parse("카톡 알림 읽어줘", now), null)
    check("앱 열기는 알림 아님", ReminderParser.parse("유튜브 열어줘", now), null)
    check("오후의 후는 상대 시간 아님", ReminderParser.parse("오후 3시에 알려줘", now) is ReminderRequest.Schedule, true)
}
