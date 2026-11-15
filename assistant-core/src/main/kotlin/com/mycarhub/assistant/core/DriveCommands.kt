package com.mycarhub.assistant.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** 할 일의 장소 조건: "집에 가서", "마트에 가면" → 그 장소에 도착했을 때. */
data class PlaceCondition(val kind: Kind, val name: String) {
    enum class Kind { HOME, WORK, OTHER }
}

/** 음성에서 뽑은 할 일. [due]는 기한(없으면 null), [hasTime]이 false면 날짜만 정해진 것. */
data class TodoDraft(
    val title: String,
    val note: String,
    val due: LocalDateTime?,
    val hasTime: Boolean,
    val place: PlaceCondition?,
)

/**
 * Drive To-Do 음성 명령 (규칙 기반, 기기 안에서만): 주차 위치 묻기, 지금 위치를 주차 위치로 저장, 할 일 등록.
 *
 * 차량용·폰 음성비서가 기존 명령([CommandParser])보다 먼저 본다. 할 일은 '해야 해', '할 일에 추가해', '나중에 알려줘'처럼
 * 분명한 표현이 있을 때만 잡아서 "카카오톡 열어줘" 같은 기존 명령을 가로채지 않는다.
 */
object DriveCommands {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private val SPACES = Regex("\\s+")

    // region 주차 위치

    private val PARKING_QUERY = listOf(
        Regex("차\\s*(를|은|는)?\\s*어디(에|다)?\\s*(세웠|주차|댔|뒀|두었|놨|놓았|있)"),
        Regex("어디(에|다)?\\s*(주차|차\\s*세웠|세웠|댔)"),
        Regex("주차\\s*(한|했던)?\\s*(위치|곳|자리|장소)"),
        Regex("(마지막|최근)\\s*주차"),
        Regex("내\\s*차\\s*(어디|위치)"),
        Regex("차\\s*위치\\s*(알려|어디|찾아)"),
    )
    private val PARK_HERE = listOf(
        Regex("(여기|이\\s*곳|지금\\s*위치)(에|를)?\\s*(주차|차\\s*세웠|세웠|댔)"),
        Regex("주차\\s*위치\\s*(저장|기억)"),
    )

    /** "차 어디 세웠지?", "주차 위치 알려줘" */
    fun isParkingQuery(text: String, wakeWord: String? = null): Boolean {
        val sentence = sentence(text, wakeWord)
        if (isParkHere(text, wakeWord)) return false
        return PARKING_QUERY.any { it.containsMatchIn(sentence) }
    }

    private val TODO_LIST = Regex("(할\\s*일|투두)\\s*(이|은|목록)?\\s*(뭐|뭐야|뭐\\s*있|알려|읽어|있어|있나|확인)")

    /** "할 일 뭐 있어?", "할 일 알려줘" (할 일 등록 문장은 아님) */
    fun isTodoListQuery(text: String, wakeWord: String? = null): Boolean {
        val s = sentence(text, wakeWord)
        return TODO_LIST.containsMatchIn(s) && !s.contains("추가") && !s.contains("나중에")
    }

    /** "여기 주차했어", "주차 위치 저장해 줘": 지금 위치를 주차 위치로 직접 저장 */
    fun isParkHere(text: String, wakeWord: String? = null): Boolean {
        val sentence = sentence(text, wakeWord)
        return PARK_HERE.any { it.containsMatchIn(sentence) } && !sentence.contains("어디")
    }

    // endregion

    // region 할 일

    /** 할 일 등록을 요청하는 끝말 (긴 것부터). 이 말을 떼고 남은 앞부분이 할 일이다. */
    private val REQUEST_TAILS = listOf(
        "할 일에 추가해 줘", "할 일에 추가해줘", "할 일에 추가해", "할 일에 넣어 줘", "할 일에 넣어줘", "할 일에 넣어", "할 일 추가해 줘",
        "할 일 추가해줘", "할 일 추가해", "할 일 추가", "할일에 추가해 줘", "할일에 추가해줘", "할일에 추가해", "할일 추가해줘", "할일 추가",
        "투두에 추가해 줘", "투두에 추가해줘", "투두에 추가해", "투두 추가해줘",
        "나중에 알려 줘", "나중에 알려줘", "나중에 말해 줘", "나중에 말해줘", "잊지 않게 알려 줘", "잊지 않게 알려줘", "잊지 않게 해 줘",
        "잊지 않게 해줘", "메모해 줘", "메모해줘", "기억해 줘", "기억해줘", "리마인드해 줘", "리마인드해줘",
    )

    /** '해야 해' 류: "택배 확인해야 해" → "택배 확인하기" */
    private val MUST = Regex("(아|어|여|해|하)?야\\s*(해|돼|된다|한다|해요|돼요|됩니다|합니다|함|겠다|겠어|지|하는데|되는데|할\\s*것\\s*같아)$")

    /** 할 일 앞의 장소 조건 (집에 가서/가면/도착하면/들어가면). 집·회사는 미리 지정한 장소로 본다. */
    private val PLACE = Regex(
        "^(.+?)(에|에서)\\s*(가서|가면|도착하면|도착해서|들어가면|들어가서|들르면|들러서|가게\\s*되면|있을\\s*때|오면|와서)\\s+",
    )
    private val HOME_WORDS = setOf("집", "우리집", "우리 집", "본가")
    private val WORK_WORDS = setOf("회사", "사무실", "직장", "일터")

    private val WEEKDAYS = mapOf(
        "월요일" to DayOfWeek.MONDAY, "화요일" to DayOfWeek.TUESDAY, "수요일" to DayOfWeek.WEDNESDAY, "목요일" to DayOfWeek.THURSDAY,
        "금요일" to DayOfWeek.FRIDAY, "토요일" to DayOfWeek.SATURDAY, "일요일" to DayOfWeek.SUNDAY,
    )
    private val DAY_WORDS = listOf("오늘" to 0L, "내일" to 1L, "낼" to 1L, "모레" to 2L, "글피" to 3L)
    private val TIME = Regex("(오전|오후|아침|낮|저녁|밤|새벽)?\\s*(\\d{1,2}|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|열한|열두)\\s*시\\s*(반|(\\d{1,2})\\s*분)?(에|까지|쯤|전에)?")
    private val KOREAN_HOURS = mapOf(
        "한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12,
    )

    /**
     * 할 일 등록 문장이면 할 일을 뽑는다. [now]는 기한 계산 기준 (기기의 현재 시각).
     * "집에 가서 택배 확인해야 해" → 제목 "택배 확인하기", 장소 집. "내일 엄마한테 전화해야 해" → "엄마한테 전화하기", 내일.
     */
    fun parseTodo(text: String, now: LocalDateTime, wakeWord: String? = null): TodoDraft? {
        var body = sentence(text, wakeWord, keepCase = true)
        if (body.isEmpty()) return null
        val tail = REQUEST_TAILS.firstOrNull { body.endsWith(it) }
        val mustMatch = if (tail == null) MUST.find(body) else null
        if (tail == null && mustMatch == null) return null
        body = if (tail != null) body.dropLast(tail.length).trim() else body
        val note = body

        // 기한
        var day: LocalDate? = null
        var time: LocalTime? = null
        val words = body.split(' ').toMutableList()
        val keep = mutableListOf<String>()
        val today = now.toLocalDate()
        // 다음 주 월요일 (오늘이 월요일이면 7일 뒤)
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        fun bare(word: String?) = word?.removeSuffix("에")?.removeSuffix("까지")
        var i = 0
        while (i < words.size) {
            val w = bare(words[i])!!
            val dayWord = DAY_WORDS.firstOrNull { it.first == w }
            val following = bare(words.getOrNull(i + 1))
            when {
                dayWord != null -> day = today.plusDays(dayWord.second)
                (w == "이번" || w == "다음") && (following == "주말" || following == "주") -> {
                    val weekday = WEEKDAYS[bare(words.getOrNull(i + 2))]
                    val base = if (w == "다음") nextMonday else today
                    day = when {
                        following == "주말" -> nextOrSame(base, DayOfWeek.SATURDAY)
                        weekday != null -> nextOrSame(base, weekday)
                        w == "다음" -> nextMonday
                        else -> null
                    }
                    i += if (weekday != null) 3 else 2
                    continue
                }
                w == "주말" -> day = nextOrSame(today, DayOfWeek.SATURDAY)
                WEEKDAYS[w] != null -> day = today.with(TemporalAdjusters.next(WEEKDAYS.getValue(w)))
                else -> keep += words[i]
            }
            i++
        }
        body = keep.joinToString(" ")
        TIME.find(body)?.let { m ->
            val period = m.groupValues[1]
            var hour = m.groupValues[2].toIntOrNull() ?: KOREAN_HOURS[m.groupValues[2]] ?: return@let
            val minute = when {
                m.groupValues[3] == "반" -> 30
                m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
                else -> 0
            }
            if (hour !in 0..24 || minute !in 0..59) return@let
            if (period in setOf("오후", "저녁", "밤") && hour < 12) hour += 12
            if (period.isEmpty() && hour in 1..6) hour += 12 // "3시" 는 보통 오후 3시
            if (period in setOf("오전", "아침", "새벽") && hour == 12) hour = 0
            time = LocalTime.of(hour % 24, minute)
            body = body.removeRange(m.range).replace(SPACES, " ").trim()
        }
        val dueDay = day ?: time?.let { t -> if (t.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1) }
        val due = dueDay?.atTime(time ?: LocalTime.of(DEFAULT_DUE_HOUR, 0))

        // 장소 조건
        var place: PlaceCondition? = null
        PLACE.find(body)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isEmpty() || name.split(' ').size > 2) return@let
            place = when {
                name in HOME_WORDS -> PlaceCondition(PlaceCondition.Kind.HOME, "집")
                name in WORK_WORDS -> PlaceCondition(PlaceCondition.Kind.WORK, "회사")
                else -> PlaceCondition(PlaceCondition.Kind.OTHER, name)
            }
            body = body.substring(m.range.last + 1).trim()
        }

        val title = titleOf(body, mustMatch != null) ?: return null
        return TodoDraft(title, note, due, hasTime = time != null, place = place)
    }

    /** 끝말을 '~하기'로: "택배 확인해야 해" → "택배 확인하기", "휴지 사야 해" → "휴지 사기", "라고" 인용도 뗀다. */
    private fun titleOf(body: String, must: Boolean): String? {
        var text = body.trim()
        if (must) {
            val m = MUST.find(text) ?: return null
            val stem = text.substring(0, m.range.first).trim() + (m.groupValues[1].takeIf { it == "해" || it == "하" }?.let { "하" } ?: "")
            text = if (m.groupValues[1] in setOf("아", "어", "여")) stem else toStem(stem)
            if (text.isEmpty()) return null
            return (text + "기").replace(SPACES, " ")
        }
        // "주유소 들르라고 나중에 알려줘" → "주유소 들르기" / "휴지 사기 할 일에 추가해": 앞부분이 이미 할 일
        Regex("으?라고$").find(text)?.let { m -> text = text.dropLast(m.value.length) + "기" }
        text = text.removeSuffix("을").removeSuffix("를").trim()
        return text.ifEmpty { null }
    }

    /** '해야'의 앞 줄기: 마지막 글자가 '해'면 '하', '봐'→'보', '줘'→'주', '써'→'쓰', '켜'처럼 줄어든 모음을 되돌린다. */
    private fun toStem(stem: String): String {
        if (stem.isEmpty()) return stem
        val last = stem.last()
        if (last !in '가'..'힣') return stem
        val code = last - '가'
        val initial = code / 588
        val medial = code % 588 / 28
        val final = code % 28
        if (last == '해') return stem.dropLast(1) + "하"
        if (final != 0) return stem
        // ㅘ→ㅗ, ㅝ→ㅜ, ㅕ→ㅣ, ㅓ→ㅡ (사·가·타처럼 ㅏ는 그대로)
        val restored = when (medial) {
            9 -> 8
            14 -> 13
            6 -> 20
            4 -> 18
            else -> medial
        }
        return stem.dropLast(1) + ('가' + initial * 588 + restored * 28)
    }

    private fun nextOrSame(from: LocalDate, day: DayOfWeek): LocalDate = from.with(TemporalAdjusters.nextOrSame(day))

    // endregion

    internal fun sentence(text: String, wakeWord: String?, keepCase: Boolean = false): String {
        var s = text.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
        if (!keepCase) s = s.lowercase()
        val name = wakeWord?.replace(PUNCTUATION, " ")?.replace(SPACES, " ")?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && s.lowercase().startsWith(name.lowercase())) return s.substring(name.length).replace(Regex("^(야|아)(?=\\s|$)"), "").trim()
        // "뿡뿡아 …"처럼 호출어를 부르는 꼴로 말한 경우, 또는 "붕붕아, …"처럼 쉼표로 부른 말을 뗀다
        val first = s.substringBefore(' ')
        val called = name != null && name.length >= 2 && first.length == name.length && first.dropLast(1) == name.dropLast(1)
        val vocative = Regex("^\\S{1,5}(아|야)\\s*,").containsMatchIn(text.trim())
        if ((called || vocative) && s.contains(' ')) s = s.substringAfter(' ').trim()
        return s
    }

    /** 기한만 말하고 시각이 없으면 이 시각으로 둔다 (오전 9시) */
    const val DEFAULT_DUE_HOUR = 9
}

/** 알림에서 만든 할 일 후보의 종류. */
enum class SuggestionKind { DELIVERY, SCHEDULE_CHANGE, REPLY }

data class TodoSuggestion(val kind: SuggestionKind, val title: String)

/**
 * 문자·카카오톡 알림에서 할 일 후보를 만든다 (규칙 기반, 분명한 경우만). 메시지 본문은 판단에만 쓰고 결과에 넣지 않는다.
 * 광고 알림, 단체방의 일반 대화는 후보로 만들지 않는다.
 */
object TodoSuggester {

    private val DELIVERY_DONE = listOf(
        "배송완료", "배송 완료", "배송이 완료", "배달완료", "배달 완료", "배달이 완료", "문앞", "문 앞", "택배함", "무인함", "보관함에 보관", "경비실에 보관",
    )
    private val DELIVERY_WORDS = listOf("배송", "배달", "택배")
    private val SCHEDULE_CHANGE = listOf(
        "일정이 변경", "일정 변경", "일정이 바뀌", "시간이 변경", "시간 변경", "예약이 변경", "예약 변경", "예약이 취소", "일정이 취소",
        "약속이 취소", "변경되었습니다", "변경됐습니다", "변경 안내", "연기되었", "미뤄졌",
    )
    private val ADS = listOf("(광고)", "[광고]", "광고)", "수신거부", "무료거부")
    private val REQUESTS = listOf("연락 줘", "연락줘", "연락 주세요", "전화 줘", "전화줘", "전화 주세요", "답장 줘", "답장줘", "알려 줘", "알려줘", "알려주세요", "확인해 줘", "확인해줘", "확인 부탁")

    /**
     * [sender]는 보낸 사람(알림 제목), [isGroup]은 단체방 여부, [isPersonChat]은 사람과의 대화 앱(카카오톡·문자)인지.
     * 후보가 아니면 null.
     */
    fun suggest(sender: String, text: String, isGroup: Boolean, isPersonChat: Boolean): TodoSuggestion? {
        val body = text.trim()
        if (body.isEmpty() || ADS.any { body.contains(it) }) return null
        if (DELIVERY_DONE.any { body.contains(it) } && DELIVERY_WORDS.any { body.contains(it) }) {
            return TodoSuggestion(SuggestionKind.DELIVERY, "택배 확인하기")
        }
        if (SCHEDULE_CHANGE.any { body.contains(it) }) return TodoSuggestion(SuggestionKind.SCHEDULE_CHANGE, "변경된 일정 확인하기")
        // 답장: 1:1 대화에서 질문(?)이나 분명한 요청이 있을 때만
        val name = sender.trim()
        if (isPersonChat && !isGroup && name.isNotEmpty() && (body.endsWith("?") || body.endsWith("?)") || REQUESTS.any { body.contains(it) })) {
            return TodoSuggestion(SuggestionKind.REPLY, "${name}에게 답장하기")
        }
        return null
    }
}
