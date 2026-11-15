package com.mycarhub.assistant.phone

import com.mycarhub.assistant.core.AppCategory
import com.mycarhub.assistant.core.AppEntry
import com.mycarhub.assistant.core.AppMatcher

/** 말한 이름을 설치된 앱 이름과 비교한 결과 (음성 인식이 한두 글자 잘못 받아 적은 경우 포함). */
sealed interface AppHit {
    /** 앱 이름(또는 별칭)과 같음 */
    data class Exact(val app: AppEntry) : AppHit

    /** 하나만 충분히 비슷함 ("당군" → 당근): 확인한 뒤 쓴다 */
    data class Likely(val app: AppEntry, val similarity: Float) : AppHit

    /** 비슷한 앱이 여럿 */
    data class Ambiguous(val apps: List<AppEntry>) : AppHit

    data object None : AppHit
}

/**
 * 설치된 앱 이름 중 말한 이름과 소리가 비슷한 앱을 찾는다. 공유 해석기([AppMatcher])는 글자가 같거나 포함될 때만 찾으므로
 * 폰 빌드에서 그 다음 단계로 쓴다. 발음 비교는 [KoreanNameMatcher]와 같다.
 */
object AppNameMatcher {

    /** 앱 이름이 말한 이름으로 시작함 ("당근" → "당근마켓"): 같은 앱일 가능성이 높지만 확인한다 */
    private const val PREFIX_SCORE = 0.97f
    private const val PREFIX_FUZZY_PENALTY = 0.05f

    fun find(spoken: String, apps: List<AppEntry>): AppHit {
        val key = AppMatcher.normalize(spoken)
        // 한 글자는 우연히 비슷한 앱이 많다
        if (key.length < 2) return AppHit.None
        val scored = apps.distinctBy { it.packageName }
            .map { it to score(key, it) }
            .filter { it.second >= KoreanNameMatcher.ACCEPT }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return AppHit.None
        val exact = scored.filter { it.second >= 1f }
        if (exact.size == 1) return AppHit.Exact(exact.single().first)
        // 같은 이름이 여럿이면 등록 앱 우선 (공유 해석기와 같은 규칙)
        if (exact.size > 1) {
            val preferred = exact.filter { it.first.registered }.ifEmpty { exact }
            return if (preferred.size == 1) AppHit.Exact(preferred.single().first) else AppHit.Ambiguous(preferred.map { it.first })
        }
        val best = scored.first()
        val close = scored.filter { it.second >= best.second - KoreanNameMatcher.MARGIN }
        return if (close.size == 1) AppHit.Likely(best.first, best.second) else AppHit.Ambiguous(close.take(KoreanNameMatcher.MAX_CHOICES).map { it.first })
    }

    private fun score(key: String, app: AppEntry): Float {
        var best = 0f
        for (name in listOfNotNull(app.label, app.alias).map(AppMatcher::normalize).filter { it.isNotEmpty() }) {
            val value = when {
                name == key -> 1f
                name.length > key.length && name.startsWith(key) -> PREFIX_SCORE
                else -> maxOf(
                    KoreanNameMatcher.similarity(key, name).coerceAtMost(0.99f),
                    // "당군" ↔ "당근마켓"의 앞부분
                    if (name.length > key.length) KoreanNameMatcher.similarity(key, name.take(key.length)) - PREFIX_FUZZY_PENALTY else 0f,
                )
            }
            if (value > best) best = value
        }
        return best
    }
}

/** 문장 속 낱말이 앱을 가리키는지 사람을 가리키는지 알려 주는 조사·호칭 단서. */
enum class TargetHint { APP, PERSON, NONE }

/** 메시지 읽기 명령에서 읽을 대상. */
sealed interface ReadTarget {
    /** "당근에서 온 메시지 읽어줘": 그 앱의 알림. [sure]가 false면 비슷한 이름이라 확인한 뒤 읽는다. */
    data class App(val app: AppEntry, val sure: Boolean) : ReadTarget

    /** "동진님 메시지 읽어줘", "카톡에서 동진님 메시지": 보낸 사람 이름으로 찾는다. [query]가 비면 [app]의 최신 메시지. */
    data class Person(val app: String?, val query: String) : ReadTarget

    /** 앱 이름과 사람 이름이 둘 다 맞고 문맥으로도 가릴 수 없음: 어느 쪽인지 묻는다. */
    data class Either(val app: AppEntry, val person: String) : ReadTarget

    /** "당군에서 온 알림"처럼 앱을 말했는데 비슷한 앱이 없음 */
    data class NoApp(val name: String) : ReadTarget
}

/**
 * 메시지 읽기 명령의 이름이 앱인지 사람인지 가린다 (폰 빌드 전용, 기기 안에서만).
 *
 * 1. 앱 이름·사람 이름을 모두 후보와 비교한다 (설치된 앱 이름, 메시지 알림의 보낸 사람·연락처).
 * 2. 한쪽만 맞으면 그쪽. 둘 다 맞으면 조사·호칭으로 가린다: "당근에서", "당근 앱" → 앱 / "동진님", "동진한테서" → 사람.
 * 3. 그래도 가릴 수 없으면 임의로 고르지 않고 [ReadTarget.Either]로 묻게 한다.
 */
object TargetClassifier {

    private val APP_TAILS = listOf("에서", "앱", "어플", "애플리케이션")
    private val APP_NEXT = listOf("앱", "어플", "애플리케이션")
    private val PERSON_TAILS = listOf("한테서", "에게서", "께서", "한테", "에게", "께", "이랑", "하고", "님", "씨")
    private val PERSON_NEXT = listOf("보낸", "보내온", "님")

    /**
     * [sentence]는 인식 문장 원문, [words]는 [PersonCommand.Read]의 낱말들, [apps]는 설치된 앱, [people]은 사람 이름 후보.
     * [isMessageAppWord]는 "카톡", "문자"처럼 메시지 앱을 가리키는 말인지 (기기의 실제 메시지 알림으로 판단).
     */
    fun classify(
        sentence: String,
        words: List<String>,
        apps: List<AppEntry>,
        people: List<String>,
        isMessageAppWord: (String) -> Boolean,
    ): ReadTarget {
        // "카톡에서 동진님 메시지", "당근에서 동진님 메시지": 앱 + 사람이 함께 있으면 앱 이름은 정확히 같을 때만.
        // 낱말이 하나뿐이면 "카톡", "문자" 같은 메시지 앱 종류만 바로 앱으로 보고, 나머지("당근")는 사람 이름과 함께 비교한다.
        val appWord = words.firstOrNull { word ->
            if (words.size > 1) {
                isMessageAppWord(word) || AppNameMatcher.find(word, apps) is AppHit.Exact
            } else {
                isGenericMessageApp(word)
            }
        }
        val rest = words.filter { it != appWord }
        if (appWord != null) return ReadTarget.Person(appWord, PersonCommandParser.nameQuery(words, appWord))
        val query = rest.joinToString("")
        val hint = hint(sentence, rest.first())
        val app = AppNameMatcher.find(query, apps)
        val person = KoreanNameMatcher.resolve(query, people)
        return decide(query, hint, app, person)
    }

    /** 메시지 앱 이름·종류 ("카톡에서 온 메시지", "문자 읽어줘"). 사람 이름으로 비교하지 않는다. */
    private val GENERIC_MESSAGE_APPS = setOf(
        "카톡", "카카오톡", "톡", "카카오", "문자", "메시지", "메세지", "sms", "라인", "텔레그램", "인스타", "인스타그램", "dm", "왓츠앱", "페이스북", "메신저",
    )

    fun isGenericMessageApp(word: String): Boolean {
        val key = AppMatcher.normalize(word)
        return key in GENERIC_MESSAGE_APPS || AppCategory.forName(word) != null
    }

    internal fun decide(query: String, hint: TargetHint, app: AppHit, person: NameMatch): ReadTarget {
        val appEntry = when (app) {
            is AppHit.Exact -> app.app
            is AppHit.Likely -> app.app
            else -> null
        }
        val personName = when (person) {
            is NameMatch.Exact -> person.name
            is NameMatch.Likely -> person.name
            else -> null
        }
        val appExact = app is AppHit.Exact
        val personFound = person != NameMatch.None
        val asPerson = ReadTarget.Person(null, query)
        return when {
            appEntry == null && hint == TargetHint.APP && !personFound -> ReadTarget.NoApp(query)
            appEntry == null -> asPerson
            !personFound -> ReadTarget.App(appEntry, sure = appExact || hint == TargetHint.APP)
            hint == TargetHint.APP -> ReadTarget.App(appEntry, sure = true)
            hint == TargetHint.PERSON -> asPerson
            appExact && person !is NameMatch.Exact -> ReadTarget.App(appEntry, sure = true)
            !appExact && person is NameMatch.Exact -> asPerson
            // 둘 다 같거나 둘 다 비슷함: 묻는다 (비슷한 사람이 여럿이면 사람 쪽은 '이름'으로 묻는다)
            else -> ReadTarget.Either(appEntry, personName ?: query)
        }
    }

    /** [word]가 문장에서 어떤 조사·호칭과 함께 쓰였는지: "당근에서" → APP, "동진님", "동진한테서" → PERSON. */
    fun hint(sentence: String, word: String): TargetHint {
        val tokens = sentence.lowercase().replace(Regex("[.,!?~…·\"'“”‘’]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val key = word.lowercase()
        val index = tokens.indexOfFirst { it.startsWith(key) }
        if (index < 0) return TargetHint.NONE
        val tail = tokens[index].removePrefix(key)
        val next = tokens.getOrNull(index + 1).orEmpty()
        return when {
            APP_TAILS.any { tail.startsWith(it) } || APP_NEXT.any { next.startsWith(it) } -> TargetHint.APP
            PERSON_TAILS.any { key.endsWith(it) || tail.startsWith(it) } || PERSON_NEXT.any { next.startsWith(it) } -> TargetHint.PERSON
            else -> TargetHint.NONE
        }
    }
}

/**
 * "당근 앱을 열까요, 알림을 읽을까요?"처럼 둘 중 하나를 묻는 질문의 대답.
 * "첫 번째/두 번째" 또는 각 선택지를 가리키는 말로 고른다. 두 쪽 말이 다 들어 있거나 아무것도 없으면 null (임의로 고르지 않는다).
 */
object ClarifyAnswer {

    private val ORDINALS = listOf(listOf("첫", "일번", "1번", "하나", "앞에"), listOf("두", "둘", "이번", "2번", "뒤에", "뒤엣", "나중"))

    /** 앱 열기를 가리키는 말 */
    val OPEN_WORDS = listOf("열", "실행", "켜", "들어가", "띄워", "앱")

    /** 알림·메시지 읽기를 가리키는 말 */
    val READ_WORDS = listOf("읽", "메시지", "메세지", "알림", "들려", "확인")

    /** 사람을 가리키는 말 (앱 알림인지 사람 메시지인지 물을 때) */
    val PERSON_WORDS = listOf("님", "씨", "사람", "친구", "연락처", "보낸")

    /** 앱을 가리키는 말 (앱 알림인지 사람 메시지인지 물을 때) */
    val APP_WORDS = listOf("앱", "어플", "알림")

    fun pick(text: String, options: List<List<String>>): Int? {
        val compact = text.lowercase().replace(Regex("[\\s.,!?~]"), "")
        if (compact.isEmpty()) return null
        if (compact.contains("째")) {
            ORDINALS.forEachIndexed { i, words -> if (i < options.size && words.any { compact.startsWith(it) }) return i }
        }
        val matched = options.indices.filter { i -> options[i].any { compact.contains(it) } }
        return matched.singleOrNull()
    }
}
