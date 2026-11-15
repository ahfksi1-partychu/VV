package com.mycarhub.phone.control

/**
 * 휴대폰 화면 조작 음성 명령 (연속 대화 중 "뒤로 가", "검색창에 고양이 영상이라고 쳐", "구독 눌러" 등).
 * 규칙 기반이며, 확실한 꼴만 화면 명령으로 본다. 나머지는 기존 명령(앱 열기·메시지 등)으로 넘긴다.
 */
sealed interface ScreenCommand {
    data object Back : ScreenCommand
    data object Home : ScreenCommand
    data object Recents : ScreenCommand
    data object ScrollDown : ScreenCommand
    data object ScrollUp : ScreenCommand

    /** 입력란에 [text]를 쓴다. [thenSearch]면 쓴 뒤 검색까지 한다 ("고양이 영상이라고 검색해 줘"). */
    data class Type(val text: String, val thenSearch: Boolean) : ScreenCommand

    /** 검색 실행 ("검색해 줘") */
    data object Search : ScreenCommand

    /** [label]이 적힌 버튼을 누른다. null이면 "이거 눌러"처럼 무엇인지 말하지 않음 → 되묻는다. */
    data class Tap(val label: String?) : ScreenCommand

    /** 연속 대화 종료 ("쁘이 그만", "대화 종료") */
    data object EndConversation : ScreenCommand
}

object ScreenCommandParser {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private val SPACES = Regex("\\s+")

    private val END = setOf(
        "그만", "그만해", "그만해줘", "그만하자", "대화종료", "대화종료해", "대화종료해줘", "대화끝", "대화끝내", "대화끝내줘", "대화그만",
        "연속대화종료", "연속대화끝", "대화모드종료", "대화모드끝", "종료", "종료해", "종료해줘", "끝", "끝내", "끝내줘", "이제됐어", "수고했어",
    )
    private val BACK = setOf("뒤로", "뒤로가", "뒤로가줘", "뒤로가기", "뒤로가자", "뒤로돌아가", "이전화면", "이전화면으로", "이전화면으로가", "이전으로", "이전으로가", "돌아가", "돌아가줘")
    private val HOME = setOf("홈", "홈으로", "홈으로가", "홈으로가줘", "홈화면", "홈화면으로", "홈화면으로가", "홈화면으로가줘", "홈화면가", "홈가", "홈가줘", "바탕화면", "바탕화면으로가")
    private val RECENTS = setOf("최근앱", "최근앱보여줘", "최근앱열어", "최근앱열어줘", "최근실행앱")
    private val SEARCH = setOf("검색", "검색해", "검색해줘", "검색하기", "검색하자", "검색실행", "검색눌러", "검색눌러줘", "검색버튼눌러", "검색버튼눌러줘", "엔터", "엔터쳐", "엔터쳐줘", "엔터눌러", "엔터눌러줘")
    private val THIS = setOf("", "이거", "저거", "그거", "이것", "저것", "그것", "여기", "저기", "거기", "이버튼", "저버튼", "그버튼", "이걸", "저걸", "그걸")

    private val TYPE_QUOTED = Regex("^(?:(?:검색창|검색란|입력창|입력란|여기)(?:에|에다|에다가)\\s*)?(.+?)\\s*라고\\s*(?:좀\\s*)?(쳐|써|적어|입력|타이핑|검색)")
    private val TYPE_FIELD = Regex("^(?:검색창|검색란|입력창|입력란)(?:에|에다|에다가)\\s*(.+?)\\s*(?:좀\\s*)?(?:쳐|써|적어|입력|타이핑)")
    private val TAP = Regex("^(.*?)\\s*(?:을|를)?\\s*(?:좀\\s*)?(?:눌러|누르|클릭|터치|탭해|선택해)\\S*$")

    fun parse(raw: String, wakeWord: String?): ScreenCommand? {
        val s = withoutWakeWord(raw.replace(PUNCTUATION, " ").replace(SPACES, " ").trim(), wakeWord)
        val c = s.replace(" ", "").lowercase()
        if (c.isEmpty()) return null
        when {
            c in END -> return ScreenCommand.EndConversation
            c in BACK -> return ScreenCommand.Back
            c in HOME -> return ScreenCommand.Home
            c in RECENTS -> return ScreenCommand.Recents
            c in SEARCH -> return ScreenCommand.Search
        }
        if (c.length <= 10 && listOf("다운", "볼륨", "소리", "음량", "밝기", "온도").none { c.contains(it) }) {
            if (c.contains("내려") || c.contains("내리") || c == "스크롤다운") return ScreenCommand.ScrollDown
            if (c.contains("올려") || c.contains("올리") || c == "스크롤업") return ScreenCommand.ScrollUp
        }
        TYPE_QUOTED.find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) return ScreenCommand.Type(withoutParticle(text), thenSearch = m.groupValues[2] == "검색")
        }
        TYPE_FIELD.find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) return ScreenCommand.Type(text, thenSearch = false)
        }
        TAP.find(s)?.let { m ->
            val label = m.groupValues[1].trim().removeSuffix("버튼").trim()
            return ScreenCommand.Tap(label.takeUnless { it.replace(" ", "") in THIS })
        }
        return null
    }

    /** "이거 눌러"에 되물은 뒤의 대답 ("구독", "구독 버튼") → 누를 이름 */
    fun tapAnswer(raw: String, wakeWord: String?): String? {
        (parse(raw, wakeWord) as? ScreenCommand.Tap)?.label?.let { return it }
        val s = withoutWakeWord(raw.replace(PUNCTUATION, " ").replace(SPACES, " ").trim(), wakeWord).removeSuffix("버튼").trim()
        return s.takeUnless { it.replace(" ", "") in THIS }
    }

    /** 이 말들은 끝의 '이'가 낱말의 일부다 ("고양이라고" → 고양이) */
    private val ENDS_WITH_I = setOf("고양이", "어린이", "아이", "오이", "나이", "놀이", "높이", "길이", "넓이", "거북이", "원숭이", "다람쥐이", "까치", "멸치", "김치", "이")

    /**
     * "영상이라고"의 '이'처럼 받침 있는 말 뒤에 붙은 조사 '이'를 뗀다. 받침 없는 말("날씨라고")은 그대로.
     * 낱말 자체가 '이'로 끝나는 흔한 말([ENDS_WITH_I])은 떼지 않는다.
     */
    private fun withoutParticle(text: String): String {
        if (text.length < 2 || !text.endsWith("이")) return text
        val lastWord = text.substringAfterLast(' ')
        if (lastWord in ENDS_WITH_I) return text
        val before = text[text.length - 2]
        val hasFinal = before in '가'..'힣' && (before - '가') % 28 != 0
        return if (hasFinal) text.dropLast(1).trim() else text
    }

    private fun withoutWakeWord(s: String, wakeWord: String?): String {
        val name = wakeWord?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: return s
        val m = Regex("^" + Regex.escape(name) + "(?:야|아)?\\s*", RegexOption.IGNORE_CASE).find(s) ?: return s
        return s.substring(m.range.last + 1).trim()
    }
}

/** "유튜브에서 판다 검색해 줘": [app]을 열고(이미 열려 있으면 그대로) [query]를 검색한다. */
data class AppSearch(val app: String, val query: String, /** "틀어줘·재생해 줘·보여줘": 검색 후 재생까지 요청 */ val play: Boolean = false)

object AppSearchParser {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private val SPACES = Regex("""\s+""")

    // 검색해 / 검색해 줘 / 검색해 봐 / 찾아줘 / 찾아 봐 ...
    private const val TAIL = """(?:검색|찾아|찾기|서치)(?:\s*(?:해주세요|해줘|해봐|해라|하기|하자|해|줘|봐|좀|줄래|주세요))*"""

    /** "유튜브에서 판다 검색해 줘", "유튜브에 고양이 검색", "유튜브로 판다 찾아줘" */
    private val IN_APP = Regex("""^(.+?)\s*(?:에서|에|으로|로)\s+(.+?)\s*(?:을|를)?\s*""" + TAIL + "$")

    /** "유튜브 열고 판다 검색해 줘", "유튜브 켜서 강아지 찾아줘" */
    private val OPEN_THEN = Regex("""^(.+?)\s*(?:열고|켜고|실행하고|열어서|켜서|들어가서|실행해서)\s+(.+?)\s*(?:을|를)?\s*""" + TAIL + "$")

    /** "유튜브로 판다 영상 틀어줘", "유튜브에서 판다 영상 재생해 줘": 앱 이름 + 조사 + 검색어 + 재생 동사 → 검색으로 처리 (자동 재생은 하지 않음) */
    private val YOUTUBE_GLUED = Regex("""^(유튜브|유투브|youtube)\s*(에서|으로|로|에)(?=\S)""", RegexOption.IGNORE_CASE)

    private val IN_APP_PLAY = Regex(
        """^(.+?)\s*(?:에서|으로|로|에)\s+(.+?)\s*(?:을|를)?\s*(?:틀어|재생|보여)(?:\s*(?:해주세요|해줘|해봐|해|줘|봐|좀|줄래|주세요|줄래요|줘요))*$""",
    )

    /** 앱 이름이 아닌 말 ("검색창에 판다 검색해" 등은 화면 조작으로 둔다) */
    private val NOT_APPS = setOf("검색창", "검색란", "입력창", "입력란", "여기", "거기", "화면", "이화면", "지금화면")

    fun parse(raw: String, wakeWord: String?): AppSearch? {
        var s = raw.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
        wakeWord?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { name ->
            Regex("^" + Regex.escape(name) + """(?:야|아)?\s*""", RegexOption.IGNORE_CASE).find(s)?.let { s = s.substring(it.range.last + 1).trim() }
        }
        // "유튜브로최신 음악"처럼 조사 뒤 띄어쓰기가 빠진 인식 결과도 앱 이름을 분리한다
        s = YOUTUBE_GLUED.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + " " }
        val playMatch = IN_APP_PLAY.find(s)
        val m = IN_APP.find(s) ?: OPEN_THEN.find(s) ?: playMatch ?: return null
        val app = m.groupValues[1].trim()
        val query = m.groupValues[2].trim()
        if (app.isEmpty() || query.isEmpty() || app.length > MAX_APP_LENGTH || app.replace(" ", "") in NOT_APPS) return null
        return AppSearch(app, query, play = m === playMatch)
    }

    private const val MAX_APP_LENGTH = 15
}

/**
 * "강남역 길 안내해 줘", "서울역까지 길 찾아줘", "강남역 가는 길 알려줘", "강남역으로 안내해 줘" → 목적지.
 * 일반 앱 실행("내비 켜줘", "길안내 열어줘")은 건드리지 않는다.
 */
object NavigateParser {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private val SPACES = Regex("""\s+""")
    private const val TAIL = """(?:\s*(?:해주세요|해줘|해봐|해|좀|줘|주세요|줄래|줄래요|줘요|시작|부탁해))*"""

    private val ROAD = Regex("""^(.+?)\s*(?:까지|으로|로|에)?\s*(?:가는\s*)?길\s*(?:안내|찾기|찾아|알려)""" + TAIL + "$")
    private val TO = Regex("""^(.+?)\s*(?:까지|으로|로)\s*(?:안내|내비|네비)""" + TAIL + "$")

    private val NOT_PLACES = setOf("길", "지도", "내비", "네비", "음성", "안내", "좀", "여기", "거기", "이")

    fun parse(raw: String, wakeWord: String?): String? {
        var s = raw.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
        wakeWord?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { name ->
            Regex("^" + Regex.escape(name) + """(?:야|아)?\s*""", RegexOption.IGNORE_CASE).find(s)?.let { s = s.substring(it.range.last + 1).trim() }
        }
        // "네이버지도로 서울역 안내해 줘": 지도 앱 이름을 먼저 떼고, 앱을 말했으면 "안내"만으로도 길 안내로 본다
        var app: String? = null
        APP_PREFIX.find(s)?.let { p ->
            app = p.groupValues[1].replace(" ", "")
            s = s.substring(p.range.last + 1).trim()
        }
        val m = ROAD.find(s) ?: TO.find(s) ?: (if (app != null) BARE.find(s) else null) ?: return null
        // "네이버지도에서 서울역 길 안내" → 목적지는 "서울역"
        val dest = m.groupValues[1].trim().substringAfterLast("에서 ").trim().removePrefix("좀 ").trim()
        if (dest.replace(" ", "") in NOT_PLACES || dest.length > MAX_LENGTH) return null
        if (dest.isEmpty()) return null
        lastApp = app
        return dest
    }

    /** 마지막 [parse]에서 말한 지도 앱 (예: "네이버지도"). 말하지 않았으면 null. */
    var lastApp: String? = null
        private set

    private val APP_PREFIX = Regex("""^(네이버\s*지도|네이버\s*맵|카카오\s*맵|카카오\s*지도|구글\s*지도|구글\s*맵)\s*(?:으로|로|에서)\s+""")
    private val BARE = Regex("""^(.+?)\s*(?:까지|으로|로)?\s*(?:안내|길안내)""" + TAIL + "$")

    private const val MAX_LENGTH = 30
}
