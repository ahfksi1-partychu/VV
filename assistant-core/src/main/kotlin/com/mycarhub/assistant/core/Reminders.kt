package com.mycarhub.assistant.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 음성 알림·타이머 명령 해석 (규칙 기반).
 * "2분 뒤에 설거지하라고 알림 켜줘", "10분 후에 빨래 꺼내라고 알려줘", "내일 아침 8시에 약 먹으라고 알려줘",
 * "30분 타이머 맞춰줘", "방금 알림 취소해 줘".
 *
 * 시간이 모호하면 추측하지 않는다: 시간이 없으면 [ReminderRequest.NeedTime], 오전/오후를 모르면 [ReminderRequest.NeedAmPm].
 */
sealed interface ReminderRequest {
    /** 등록할 알림. [spokenWhen]은 "2분 뒤에", "내일 오전 8시에"처럼 대답에 쓰는 말. */
    data class Schedule(val at: LocalDateTime, val message: String, val spokenWhen: String, val timer: Boolean) : ReminderRequest

    /** 언제인지 모름 → 다시 묻는다. [past]: 말한 시간이 이미 지남. */
    data class NeedTime(val draft: ReminderDraft, val past: Boolean = false) : ReminderRequest

    /** "8시"처럼 오전·오후를 모름 → 다시 묻는다 */
    data class NeedAmPm(val draft: ReminderDraft, val dayOffset: Int?, val hour: Int, val minute: Int) : ReminderRequest

    /** 마지막으로 등록한 알림 취소 */
    data object CancelLast : ReminderRequest
}

/** 시간을 아직 모르는 알림 내용 */
data class ReminderDraft(val message: String, val timer: Boolean)

object ReminderParser {

    private val PUNCTUATION = Regex("""[.,!?~…·"'“”‘’]""")
    private val SPACES = Regex("""\s+""")

    // 숫자: 2, 10 / 한·두·세…열두·스무·서른 / 일·이·삼…십·삼십
    private const val NUM =
        """(\d+|(?:열|스물|스무|서른|마흔|쉰)?\s?(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉)|열|스무|스물|서른|마흔|쉰|[이삼사오육칠팔구]?십\s?[일이삼사오육칠팔구]?|[일이삼사오육칠팔구])"""

    private const val AFTER = """(?:뒤|후|있다가|이따가|지나서|지나면|있으면)(?:에는|에|쯤)?"""

    /** "2분 뒤에", "1시간 30분 후", "한 시간 반 있다가" (단위가 하나는 있어야 한다: '오후'의 '후'와 구분) */
    private val RELATIVE = Regex("""(?:$NUM\s*시간\s*(반)?)?\s*(?:$NUM\s*분\s*(반)?)?\s*(?:$NUM\s*초)?\s*$AFTER""")

    /** 기간만: "30분", "1시간 반" (타이머, 되물은 대답) */
    private val DURATION = Regex("""(?:$NUM\s*시간\s*(반)?)?\s*(?:$NUM\s*분\s*(반)?)?\s*(?:$NUM\s*초)?""")

    /** "내일 아침 8시에", "오후 2시 반", "21시 30분에" */
    private val ABSOLUTE = Regex(
        """(?:(오늘|내일|모레|글피)\s*)?(?:(아침|오전|오후|저녁|밤|낮|새벽|점심)\s*)?$NUM\s*시(?!간)\s*(?:$NUM\s*분|(반))?\s*(?:에는|에|쯤|까지)?""",
    )

    private val DAY_OR_PERIOD = Regex("""(오늘|내일|모레|글피|아침|오전|오후|저녁|밤|낮|새벽|점심)""")
    private val VAGUE = Regex("""(있다가|이따가|이따|나중에|조금\s*있다|좀\s*있다|잠시\s*후|잠깐\s*뒤|잠시\s*뒤)""")

    private val REMIND_VERB = Regex("""(알려|알림|알람|기억|리마인드|깨워|상기)""")
    private val MARKER = Regex("""(으라고|라고|다고|알림|알람|알려|기억|리마인드|깨워|상기)""")
    private val TURN_ON = Regex("""(알림|알람)\s*(?:좀\s*)?(?:켜|설정|맞춰|등록|해|예약|걸어)""")
    private val CANCEL_WORDS = Regex("""(취소|지워|삭제|없애|꺼\s*줘|꺼\s*주|끄)""")
    private val CANCEL_TARGET = Regex("""(알림|알람|타이머|리마인더|예약)""")
    private val NOT_REMINDER = Regex("""(메시지|메세지|카톡|문자|답장)""")

    fun parse(raw: String, now: LocalDateTime, wakeWord: String? = null): ReminderRequest? {
        val s = clean(raw, wakeWord)
        if (s.isEmpty()) return null
        if (CANCEL_TARGET.containsMatchIn(s) && CANCEL_WORDS.containsMatchIn(s) && !NOT_REMINDER.containsMatchIn(s)) {
            return ReminderRequest.CancelLast
        }
        if (s.contains("타이머")) return parseTimer(s, now)
        if (!REMIND_VERB.containsMatchIn(s)) return null

        val relative = findRelative(s)
        val absolute = if (relative == null) findAbsolute(s) else null
        val timeRange = relative?.range ?: absolute?.range
        val rest = timeRange?.let { s.removeRange(it) }?.replace(SPACES, " ")?.trim() ?: s
        val hasMarker = Regex("""(으라고|라고|다고)""").containsMatchIn(rest) || TURN_ON.containsMatchIn(rest)
        if (timeRange == null && !hasMarker && !VAGUE.containsMatchIn(s)) return null
        // 답장 등 다른 명령 ("30분 뒤에 도착한다고 답장해 줘")
        if (NOT_REMINDER.containsMatchIn(rest) && !TURN_ON.containsMatchIn(rest)) return null

        val draft = ReminderDraft(message(rest), timer = false)
        return when {
            relative != null -> relative.minutes?.let { schedule(now.plusSeconds(it), draft, relative.spoken) }
                ?: ReminderRequest.NeedTime(draft)
            absolute != null -> absolute.toRequest(now, draft)
            else -> ReminderRequest.NeedTime(draft)
        }
    }

    /** 되물은 "언제 알려드릴까요?"에 대한 대답 ("10분 뒤", "10분", "내일 아침 8시"). 시간이 없으면 null. */
    fun answer(raw: String, now: LocalDateTime, draft: ReminderDraft, wakeWord: String? = null): ReminderRequest? {
        val s = clean(raw, wakeWord)
        findRelative(s)?.let { r -> return r.minutes?.let { schedule(now.plusSeconds(it), draft, r.spoken) } }
        findAbsolute(s)?.let { return it.toRequest(now, draft) }
        findDuration(s)?.let { (seconds, spoken) ->
            val message = if (draft.timer) "$spoken 타이머가 끝났어요" else draft.message
            return schedule(now.plusSeconds(seconds), draft.copy(message = message), if (draft.timer) spoken else "$spoken 뒤에")
        }
        return null
    }

    /** 되물은 "오전이요, 오후요?"에 대한 결정 */
    fun withAmPm(request: ReminderRequest.NeedAmPm, pm: Boolean, now: LocalDateTime): ReminderRequest {
        val hour = when {
            pm && request.hour < 12 -> request.hour + 12
            !pm && request.hour == 12 -> 0
            else -> request.hour
        }
        return at(now, request.dayOffset, hour, request.minute, request.draft)
    }

    // region 타이머

    private fun parseTimer(s: String, now: LocalDateTime): ReminderRequest {
        val found = findDuration(s)
        val draft = ReminderDraft("타이머가 끝났어요", timer = true)
        val (seconds, spoken) = found ?: return ReminderRequest.NeedTime(draft)
        return ReminderRequest.Schedule(now.plusSeconds(seconds), "$spoken 타이머가 끝났어요", spoken, timer = true)
    }

    // endregion

    // region 시간 찾기

    private class Relative(val range: IntRange, val minutes: Long?, val spoken: String)

    private fun findRelative(s: String): Relative? {
        for (m in RELATIVE.findAll(s)) {
            val seconds = durationSeconds(m.groupValues, 1) ?: continue
            if (seconds <= 0) continue
            return Relative(m.range, seconds, spokenDuration(seconds) + " 뒤에")
        }
        return null
    }

    private fun findDuration(s: String): Pair<Long, String>? {
        for (m in DURATION.findAll(s)) {
            if (m.value.isBlank()) continue
            val seconds = durationSeconds(m.groupValues, 1) ?: continue
            if (seconds > 0) return seconds to spokenDuration(seconds)
        }
        return null
    }

    /** groups[start..]: 시간, 시간반, 분, 분반, 초. 단위가 하나도 없으면 null. */
    private fun durationSeconds(g: List<String>, start: Int): Long? {
        val hours = g[start].takeIf { it.isNotBlank() }?.let(::number)
        val halfHour = g[start + 1].isNotBlank()
        val minutes = g[start + 2].takeIf { it.isNotBlank() }?.let(::number)
        val halfMinute = g[start + 3].isNotBlank()
        val seconds = g[start + 4].takeIf { it.isNotBlank() }?.let(::number)
        if (hours == null && minutes == null && seconds == null) return null
        return (hours ?: 0) * 3600L + (if (halfHour) 1800 else 0) + (minutes ?: 0) * 60L + (if (halfMinute) 30 else 0) + (seconds ?: 0)
    }

    private class Absolute(val range: IntRange, val dayOffset: Int?, val period: String?, val hour: Int, val minute: Int) {
        fun toRequest(now: LocalDateTime, draft: ReminderDraft): ReminderRequest {
            if (hour > 24 || minute > 59) return ReminderRequest.NeedTime(draft)
            val h = when {
                hour == 24 -> 0
                hour == 0 || hour > 12 -> hour
                period == null -> return ReminderRequest.NeedAmPm(draft, dayOffset, hour, minute)
                period in AM -> if (hour == 12) 0 else hour
                period == "밤" && hour <= 4 -> hour
                period == "밤" && hour == 12 -> 0
                period == "낮" || period == "점심" -> if (hour <= 5 || hour == 12) (if (hour == 12) 12 else hour + 12) else hour
                else -> if (hour == 12) 12 else hour + 12
            }
            return at(now, dayOffset, h, minute, draft)
        }
    }

    private val AM = setOf("아침", "오전", "새벽")

    private fun findAbsolute(s: String): Absolute? {
        val m = ABSOLUTE.find(s) ?: return null
        val day = when (m.groupValues[1]) {
            "오늘" -> 0
            "내일" -> 1
            "모레" -> 2
            "글피" -> 3
            else -> null
        }
        val period = m.groupValues[2].takeIf { it.isNotBlank() }
        val hour = number(m.groupValues[3]) ?: return null
        val minute = when {
            m.groupValues[5].isNotBlank() -> 30
            m.groupValues[4].isNotBlank() -> number(m.groupValues[4]) ?: return null
            else -> 0
        }
        return Absolute(m.range, day, period, hour, minute)
    }

    private fun at(now: LocalDateTime, dayOffset: Int?, hour: Int, minute: Int, draft: ReminderDraft): ReminderRequest {
        val today = now.toLocalDate()
        var target = (dayOffset?.let { today.plusDays(it.toLong()) } ?: today).atTime(hour, minute)
        if (!target.isAfter(now)) {
            // 날을 말하지 않았으면 다음에 오는 그 시각, 말했는데 지났으면 다시 묻는다
            if (dayOffset != null) return ReminderRequest.NeedTime(draft, past = true)
            target = target.plusDays(1)
        }
        return ReminderRequest.Schedule(target, draft.message, spokenAt(now.toLocalDate(), target) + "에", timer = false)
    }

    private fun schedule(at: LocalDateTime, draft: ReminderDraft, spoken: String) =
        ReminderRequest.Schedule(at, draft.message, spoken, draft.timer)

    // endregion

    // region 말 만들기

    /** "2분", "1시간 30분", "45초" */
    fun spokenDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}시간" }, m.takeIf { it > 0 }?.let { "${it}분" }, s.takeIf { it > 0 }?.let { "${it}초" })
            .joinToString(" ")
    }

    /** "오늘 오후 3시", "내일 오전 8시 30분", "11월 20일 오전 9시" */
    fun spokenAt(today: LocalDate, at: LocalDateTime): String {
        val day = when (ChronoUnit.DAYS.between(today, at.toLocalDate())) {
            0L -> "오늘"
            1L -> "내일"
            2L -> "모레"
            else -> "${at.monthValue}월 ${at.dayOfMonth}일"
        }
        val ampm = if (at.hour < 12) "오전" else "오후"
        val h12 = if (at.hour % 12 == 0) 12 else at.hour % 12
        val minute = if (at.minute > 0) " ${at.minute}분" else ""
        return "$day $ampm ${h12}시$minute"
    }

    /**
     * 알림 내용. "설거지하라고" → "설거지할 시간이에요", "약 먹으라고" → "약 먹을 시간이에요",
     * "회의 있다고" → "회의 있다는 거 잊지 마세요", "설거지 알림" → "설거지 시간이에요", 내용 없음 → 기본 문구.
     */
    internal fun message(rest: String): String {
        val marker = MARKER.find(rest)
        val task = (if (marker != null) rest.substring(0, marker.range.first) else rest)
            .trim().removePrefix("에는").removePrefix("에").trim()
            .removeSuffix("을").removeSuffix("를").trim()
        if (rest.contains("깨워") && task.isEmpty()) return "일어날 시간이에요"
        if (task.isEmpty()) return DEFAULT_MESSAGE
        return when (marker?.value) {
            "으라고", "라고" -> futureForm(task) + " 시간이에요"
            "다고" -> task + "다는 거 잊지 마세요"
            else -> "$task 시간이에요"
        }
    }

    /** 동사 줄기 + ㄹ/을: "설거지하" → "설거지할", "꺼내" → "꺼낼", "먹" → "먹을" */
    private fun futureForm(stem: String): String {
        val last = stem.last()
        if (last !in '가'..'힣') return stem
        val code = last - '가'
        return if (code % 28 == 0) stem.dropLast(1) + ('가' + code + 8) else stem + "을"
    }

    const val DEFAULT_MESSAGE = "요청하신 알림이에요"

    // endregion

    private fun clean(raw: String, wakeWord: String?): String {
        var s = raw.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
        wakeWord?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { name ->
            Regex("^" + Regex.escape(name) + """(?:야|아)?\s*""", RegexOption.IGNORE_CASE).find(s)?.let { s = s.substring(it.range.last + 1).trim() }
        }
        return s
    }

    /** "2", "열두", "삼십", "한" → 숫자 */
    internal fun number(text: String): Int? {
        val t = text.replace(" ", "")
        t.toIntOrNull()?.let { return it }
        NATIVE_TENS.entries.firstOrNull { t.startsWith(it.key) }?.let { (word, tens) ->
            val rest = t.removePrefix(word)
            if (rest.isEmpty()) return tens
            return NATIVE_ONES[rest]?.let { tens + it }
        }
        NATIVE_ONES[t]?.let { return it }
        if (t.contains('십')) {
            val tens = t.substringBefore('십').let { if (it.isEmpty()) 1 else SINO[it] ?: return null }
            val ones = t.substringAfter('십').let { if (it.isEmpty()) 0 else SINO[it] ?: return null }
            return tens * 10 + ones
        }
        return SINO[t]
    }

    private val NATIVE_ONES = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9)
    private val NATIVE_TENS = linkedMapOf("스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "쉰" to 50, "열" to 10)
    private val SINO = mapOf("일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5, "육" to 6, "칠" to 7, "팔" to 8, "구" to 9)
}
