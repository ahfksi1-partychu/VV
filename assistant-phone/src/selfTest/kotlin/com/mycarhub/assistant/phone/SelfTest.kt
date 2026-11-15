package com.mycarhub.assistant.phone

import com.mycarhub.assistant.core.AppEntry
import com.mycarhub.assistant.core.AppMatch
import com.mycarhub.assistant.core.AppMatcher
import com.mycarhub.assistant.core.CommandParser
import com.mycarhub.assistant.core.VoiceCommand
import kotlin.system.exitProcess

/** 비슷한 이름 찾기·사람 이름 명령 해석 자체 검사. 실패가 하나라도 있으면 종료 코드 1. */
private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual != expected) {
        failures++
        println("FAIL  $name\n      expected: $expected\n      actual:   $actual")
    }
}

private fun matcherChecks() {
    // 실제 사례: 연락처 '동진님'을 인식기가 '동지님'으로 받아 적음
    val friends = listOf("동진님", "엄마", "김민수", "회사 팀장님")
    check("동지님 → 동진님 (확인 후)", KoreanNameMatcher.resolve("동지님", friends)::class, NameMatch.Likely::class)
    check("동지님 → 동진님 이름", (KoreanNameMatcher.resolve("동지님", friends) as? NameMatch.Likely)?.name, "동진님")
    check("동진님 정확히", KoreanNameMatcher.resolve("동진님", friends), NameMatch.Exact("동진님"))
    check("호칭 없이 동진", KoreanNameMatcher.resolve("동진", friends), NameMatch.Exact("동진님"))
    check("동진님이 (조사)", (KoreanNameMatcher.resolve("동진님이", friends) as? NameMatch.Likely)?.name, "동진님")
    check("성 빼고 민수", (KoreanNameMatcher.resolve("민수", friends) as? NameMatch.Likely)?.name, "김민수")
    check("엄마 정확히", KoreanNameMatcher.resolve("엄마", friends), NameMatch.Exact("엄마"))

    // 비슷한 이름이 여럿: 후보를 모두 물어본다
    val similar = listOf("동진", "동빈", "엄마")
    check("동인 → 동진·동빈 중 선택", KoreanNameMatcher.resolve("동인", similar), NameMatch.Ambiguous(listOf("동진", "동빈")))
    check("성만 다른 동진 둘", KoreanNameMatcher.resolve("동진", listOf("김동진", "이동진", "엄마")), NameMatch.Ambiguous(listOf("김동진", "이동진")))
    check("동지 → 동진·동지 중 동지 정확히", KoreanNameMatcher.resolve("동지", listOf("동진", "동지")), NameMatch.Exact("동지"))

    // 유사도가 낮으면 고르지 않는다
    check("전혀 다른 이름", KoreanNameMatcher.resolve("철수", friends), NameMatch.None)
    check("첫 글자만 같음 동훈", KoreanNameMatcher.resolve("동훈", listOf("동진")), NameMatch.None)
    check("빈 이름", KoreanNameMatcher.resolve("님", friends), NameMatch.None)
    check("후보 없음", KoreanNameMatcher.resolve("동진", emptyList()), NameMatch.None)

    // 발음 비교 단위
    check("ㅐ/ㅔ 거의 같음", KoreanNameMatcher.similarity("세진", "새진") > 0.9f, true)
    check("받침 ㄴ 빠짐", KoreanNameMatcher.similarity("동지", "동진") > 0.9f, true)
    check("다른 모음은 멀다", KoreanNameMatcher.similarity("동진", "동준") < KoreanNameMatcher.similarity("동지", "동진"), true)
    check("영문 이름", KoreanNameMatcher.resolve("john", listOf("John", "Jane")), NameMatch.Exact("John"))
}

private fun parserChecks() {
    fun read(vararg words: String) = PersonCommand.Read(words.toList())
    val cases = listOf(
        "동지님 메시지 읽어줘" to read("동지님"),
        "동진님 메시지 읽어 줘" to read("동진님"),
        "동진님한테 온 메시지 읽어줘" to read("동진님"),
        "동진님이 보낸 메시지 읽어줘" to read("동진님이"),
        "카톡에서 동진님 메시지 읽어줘" to read("카톡", "동진님"),
        "동진님 카톡 읽어줘" to read("동진님", "카톡"),
        "뿡뿡이 동진님 메시지 읽어줘" to read("동진님"),
        "최근 메시지 읽어줘" to null,
        "메시지 읽어줘" to null,
        "문자 읽어 줘" to read("문자"),
        "동진님한테 곧 간다고 답장해줘" to PersonCommand.Reply("동진님", "곧 간다"),
        "동진님한테 답장해줘" to PersonCommand.Reply("동진님", null),
        "동진님 메시지에 답장해줘" to PersonCommand.Reply("동진님", null),
        "엄마한테 카톡 보내줘" to PersonCommand.Reply("엄마", null),
        "엄마한테 카톡으로 곧 간다고 보내줘" to PersonCommand.Reply("엄마", "곧 간다"),
        "곧 간다고 답장해줘" to null,
        "답장해줘" to null,
        "답장 메시지 보내줘" to null,
        "아빠에게 전화 걸어 줘" to null,
        "카카오톡 열어줘" to null,
        // 대화방 열기: 대화방을 가리키는 말이 있어야 한다 ("카톡 열어줘"는 앱 열기)
        "동진님 카톡방 열어줘" to PersonCommand.Open(listOf("동진님")),
        "동지님 대화방 열어 줘" to PersonCommand.Open(listOf("동지님")),
        "동지님카톡방 열어줘" to PersonCommand.Open(listOf("동지님")),
        "동진님 카톡방을 열어줘" to PersonCommand.Open(listOf("동진님")),
        "동진님 대화방 들어가 줘" to PersonCommand.Open(listOf("동진님")),
        "카톡에서 동진님 대화방 열어줘" to PersonCommand.Open(listOf("카톡", "동진님")),
        "동진님이랑 대화 열어줘" to PersonCommand.Open(listOf("동진님")),
        "카톡 열어줘" to null,
        "카톡방 열어줘" to null,
        "카톡 대화방 열어줘" to null,
        "유튜브 열어줘" to null,
    )
    for ((text, expected) in cases) check("parse \"$text\"", PersonCommandParser.parse(text, wakeWord = "뿡뿡이"), expected)

    val answers = listOf(
        "동진님이요" to "동진님",
        "동진님" to "동진님",
        "동진이요" to "동진",
        "동빈님 말이야" to "동빈님",
        "동진님 메시지" to "동진님",
        "김민수입니다" to "김민수",
    )
    for ((text, expected) in answers) check("nameAnswer \"$text\"", PersonCommandParser.nameAnswer(text), expected)
    // 대답 끝을 뗀 뒤에도 원래 이름과 맞아야 한다
    check("동진이요 → 동진님", KoreanNameMatcher.resolve(PersonCommandParser.nameAnswer("동진이요"), listOf("동진님", "동빈님")), NameMatch.Exact("동진님"))
}

/**
 * 실제 STT 문장 전체 → 명령 해석 → 이름 낱말 → (앱 이름 제외) 검색어 → 후보 비교. PhoneConversation.findPerson 과 같은 순서.
 * [appWords]는 기기에서 실제 메시지 앱 이름으로 가려지는 낱말 (isAppWord 대신).
 */
private fun search(sentence: String, candidates: List<String>, appWords: Set<String> = setOf("카톡", "문자")): Pair<String, NameMatch>? =
    when (val command = PersonCommandParser.parse(sentence, wakeWord = "뿡뿡이")) {
        is PersonCommand.Read -> query(command.words, candidates, appWords)
        is PersonCommand.Open -> query(command.words, candidates, appWords)
        is PersonCommand.Reply -> command.name to KoreanNameMatcher.resolve(command.name, candidates)
        null -> null
    }

private fun query(words: List<String>, candidates: List<String>, appWords: Set<String>): Pair<String, NameMatch> {
    val app = words.firstOrNull { it in appWords }
    val query = PersonCommandParser.nameQuery(words, app)
    return query to KoreanNameMatcher.resolve(query, candidates)
}

private fun sentenceChecks() {
    // 기기 실제 사례: 화면에 "동지님대화, 와 비슷한 이름을 찾지 못했어요" (대화가 이름에 붙음)
    val friends = listOf("동진님", "엄마", "회사 팀장님", "가족 단톡방")
    val sentences = listOf(
        "동지님 대화",
        "동지님대화",
        "동지님 대화 읽어줘",
        "동지님 메시지 읽어줘",
        "동지님메시지 읽어줘",
        "동지님한테 온 메시지 읽어줘",
        "동지님에게 온 메시지 읽어 줘",
        "동지님의 대화 읽어줘",
        "동지님이 보낸 메시지 읽어줘",
        "뿡뿡이 동지님 대화 읽어줘",
        "카톡에서 동지님 대화 읽어줘",
        "동지님 카톡 읽어줘",
        "동지님 대화방 메시지 읽어줘",
    )
    for (sentence in sentences) {
        val result = search(sentence, friends)
        check("문장 \"$sentence\" → 동진님 확인", (result?.second as? NameMatch.Likely)?.name, "동진님")
        // 조사 "이"는 이름 끝 글자일 수 있어 검색어에 남지만 (동지님이) 같은 사람으로 찾는다
        if (!sentence.contains("동지님이")) check("문장 \"$sentence\" 검색어", result?.first, "동지님")
    }
    check("답장 문장", (search("동지님한테 곧 간다고 답장해줘", friends)?.second as? NameMatch.Likely)?.name, "동진님")
    check("카톡 보내기 문장", (search("동지님한테 카톡 보내줘", friends)?.second as? NameMatch.Likely)?.name, "동진님")
    check("대화방 열기 문장", (search("동지님 카톡방 열어줘", friends)?.second as? NameMatch.Likely)?.name, "동진님")
    check("앱 지정 대화방 열기", search("카톡에서 동지님 대화방 열어줘", friends)?.first, "동지님")
    check("정확한 이름 문장", search("동진님 대화 읽어줘", friends)?.second, NameMatch.Exact("동진님"))

    // 비슷한 이름이 여럿: 고르지 않고 묻는다
    val similar = listOf("동진님", "동빈님", "엄마")
    check("문장 \"동인님 메시지 읽어줘\" → 선택", search("동인님 메시지 읽어줘", similar)?.second, NameMatch.Ambiguous(listOf("동진님", "동빈님")))
    check("문장 \"동인님 대화\" → 선택", search("동인님 대화", similar)?.second, NameMatch.Ambiguous(listOf("동진님", "동빈님")))
    // 비슷한 이름이 없으면 고르지 않는다
    check("문장 \"철수 메시지 읽어줘\" → 없음", search("철수 메시지 읽어줘", friends)?.second, NameMatch.None)
    // 후보가 비면 (알림 없음·연락처 권한 없음) None: 앱이 출처별 후보 수로 이유를 따로 말한다
    check("후보 없음", search("동지님 대화 읽어줘", emptyList())?.second, NameMatch.None)
    // 이름 없는 명령은 사람 찾기로 가지 않는다
    check("문장 \"메시지 읽어줘\"", search("메시지 읽어줘", friends), null)
    check("문장 \"최근 대화 읽어줘\"", search("최근 대화 읽어줘", friends), null)
    check("문장 \"카톡 대화\" (앱만)", search("카톡 대화", friends), null)
    check("문장 \"새 메시지\"", search("새 메시지", friends), null)
    check("문장 \"카카오톡 열어줘\"", search("카카오톡 열어줘", friends), null)

    // 다시 물었을 때 대답
    check("nameAnswer \"동지님 대화\"", PersonCommandParser.nameAnswer("동지님 대화"), "동지님")
    check("nameAnswer \"동지님대화요\"", PersonCommandParser.nameAnswer("동지님대화요"), "동지님")
}

/**
 * 실제 STT 문장 전체 → 의도 → 앱/사람 판단. PhoneConversation.onCommand 와 같은 순서:
 * 사람·메시지 명령 해석 → (읽기면) 앱인지 사람인지 가리기 → (아니면) 공유 해석기 → 앱 찾기 → 비슷한 앱 이름.
 */
private fun route(sentence: String, apps: List<AppEntry>, people: List<String>): String {
    val messageAppWords = setOf("카톡", "카카오톡", "문자")
    return when (val command = PersonCommandParser.parse(sentence, wakeWord = "뿡뿡이")) {
        is PersonCommand.Read -> {
            val target = TargetClassifier.classify(PersonCommandParser.sentence(sentence, "뿡뿡이"), command.words, apps, people) { it in messageAppWords }
            when (target) {
                is ReadTarget.App -> (if (target.sure) "알림읽기:" else "알림읽기확인:") + target.app.label
                is ReadTarget.Person -> if (target.query.isEmpty()) {
                    "최신메시지:${target.app}"
                } else {
                    "사람:" + listOfNotNull(target.app, KoreanNameMatcher.resolve(target.query, people).let { match ->
                        when (match) {
                            is NameMatch.Exact -> match.name
                            is NameMatch.Likely -> match.name + "?"
                            else -> match.toString()
                        }
                    }).joinToString("/")
                }
                is ReadTarget.Either -> "묻기:${target.app.label}|${target.person}"
                is ReadTarget.NoApp -> "앱없음:${target.name}"
            }
        }
        is PersonCommand.Reply -> "답장:${command.name}"
        is PersonCommand.Open -> "대화방:${command.words}"
        null -> when (val shared = CommandParser.parse(sentence, "뿡뿡이")) {
            is VoiceCommand.OpenApp -> {
                val prefix = if (shared.hasVerb) "열기" else "이름만"
                when (val exact = AppMatcher.match(shared.target, apps)) {
                    is AppMatch.Single -> "$prefix:${exact.app.label}"
                    else -> when (val hit = AppNameMatcher.find(shared.target, apps)) {
                        is AppHit.Exact -> "$prefix:${hit.app.label}"
                        is AppHit.Likely -> "${prefix}확인:${hit.app.label}"
                        is AppHit.Ambiguous -> "${prefix}선택:${hit.apps.map { it.label }}"
                        AppHit.None -> "앱못찾음:${shared.target}"
                    }
                }
            }
            is VoiceCommand.ReadMessage -> "최신메시지:${shared.app}"
            else -> shared::class.simpleName.orEmpty()
        }
    }
}

private fun intentChecks() {
    fun app(label: String, pkg: String) = AppEntry("$pkg/.Main", label, null, pkg, registered = false)
    val apps = listOf(
        app("당근", "com.towneers.www"),
        app("카카오톡", "com.kakao.talk"),
        app("YouTube", "com.google.android.youtube"),
        app("토스", "viva.republica.toss"),
        app("메시지", "com.samsung.android.messaging"),
    )
    val people = listOf("동진님", "엄마", "가족 단톡방")
    val cases = listOf(
        // 실행 명령: 설치된 앱에서 먼저 찾는다
        "당근 열어줘" to "열기:당근",
        "당근 실행해 줘" to "열기:당근",
        "당근 켜줘" to "열기:당근",
        "뿡뿡이 당근 열어 줘" to "열기:당근",
        "당근 앱 열어줘" to "열기:당근",
        "카카오톡 열어줘" to "열기:카카오톡",
        "카톡 열어줘" to "열기:카카오톡",
        "유튜브 열어줘" to "열기:YouTube",
        // 인식기가 잘못 받아 적은 앱 이름: 비슷한 앱을 찾아 확인한 뒤 연다
        "당군 열어줘" to "열기확인:당근",
        "당군 실행해줘" to "열기확인:당근",
        "토수 켜줘" to "열기확인:토스",
        "없는앱 열어줘" to "앱못찾음:없는",
        // 앱을 지정한 읽기: 보낸 사람이 아니라 그 앱의 알림
        "당근 메시지 읽어줘" to "알림읽기:당근",
        "당근에서 온 메시지 읽어줘" to "알림읽기:당근",
        "당근 알림 읽어줘" to "알림읽기:당근",
        "당근 앱 알림 읽어줘" to "알림읽기:당근",
        "당군 메시지 읽어줘" to "알림읽기확인:당근",
        "당군에서 온 알림 읽어줘" to "알림읽기:당근",
        // 사람을 지정한 읽기: 보낸 사람 이름
        "동진님 메시지 읽어줘" to "사람:동진님",
        "동지님 메시지 읽어줘" to "사람:동진님?",
        "동진님한테서 온 메시지 읽어줘" to "사람:동진님",
        "엄마 메시지 읽어줘" to "사람:엄마",
        "카톡에서 동진님 메시지 읽어줘" to "사람:카톡/동진님",
        "당근에서 동진님 메시지 읽어줘" to "사람:당근/동진님",
        // 이름 없는 읽기
        "메시지 읽어줘" to "최신메시지:null",
        "카톡 읽어줘" to "최신메시지:카톡",
        "문자 읽어줘" to "최신메시지:문자",
        // 그 밖의 명령은 그대로
        "동진님 카톡방 열어줘" to "대화방:[동진님]",
        "동진님한테 곧 간다고 답장해줘" to "답장:동진님",
        "당근" to "이름만:당근",
    )
    for ((text, expected) in cases) check("route \"$text\"", route(text, apps, people), expected)

    // '당근'이라는 사람(연락처·알림)도 있을 때: 조사·호칭으로 가리고, 가릴 수 없으면 묻는다
    val withCarrot = people + "당근"
    val ambiguous = listOf(
        "당근 메시지 읽어줘" to "묻기:당근|당근",
        "당근에서 온 메시지 읽어줘" to "알림읽기:당근",
        "당근 앱 알림 읽어줘" to "알림읽기:당근",
        "당근님 메시지 읽어줘" to "사람:당근",
        "당근한테서 온 메시지 읽어줘" to "사람:당근",
        "당근 열어줘" to "열기:당근",
    )
    for ((text, expected) in ambiguous) check("route (사람 당근 있음) \"$text\"", route(text, apps, withCarrot), expected)
    // 당근 앱의 메시지 알림이 와 있어(메시지 앱 이름으로 보임) 낱말이 하나여도 사람 이름과 함께 비교한다
    check(
        "당근 메시지 알림이 와 있어도 묻기",
        TargetClassifier.classify("당근 메시지 읽어줘", listOf("당근"), apps, withCarrot) { it == "당근" || it == "카톡" },
        ReadTarget.Either(apps.first(), "당근"),
    )

    // 판단 단서
    check("hint 당근에서", TargetClassifier.hint("당근에서 온 메시지 읽어줘", "당근"), TargetHint.APP)
    check("hint 당근 앱", TargetClassifier.hint("당근 앱 알림 읽어줘", "당근"), TargetHint.APP)
    check("hint 동진님", TargetClassifier.hint("동진님 메시지 읽어줘", "동진님"), TargetHint.PERSON)
    check("hint 동진한테서", TargetClassifier.hint("동진한테서 온 메시지", "동진"), TargetHint.PERSON)
    check("hint 없음", TargetClassifier.hint("당근 메시지 읽어줘", "당근"), TargetHint.NONE)

    // 되물은 질문의 대답: "당근 앱을 열까요, 알림을 읽을까요?"
    val openOrRead = listOf(ClarifyAnswer.OPEN_WORDS, ClarifyAnswer.READ_WORDS)
    val answers = listOf(
        "열어줘" to 0, "앱 열어" to 0, "실행해 줘" to 0, "첫 번째" to 0,
        "알림 읽어줘" to 1, "읽어 줘" to 1, "메시지 들려줘" to 1, "두 번째요" to 1,
        "응" to null, "앱 알림" to null, "글쎄" to null,
    )
    for ((text, expected) in answers) check("clarify \"$text\"", ClarifyAnswer.pick(text, openOrRead), expected)
    val appOrPerson = listOf(ClarifyAnswer.APP_WORDS, ClarifyAnswer.PERSON_WORDS)
    check("clarify 앱 알림", ClarifyAnswer.pick("앱 알림", appOrPerson), 0)
    check("clarify 사람", ClarifyAnswer.pick("사람이 보낸 거", appOrPerson), 1)
    check("clarify 당근님", ClarifyAnswer.pick("당근님", appOrPerson), 1)

    // 비슷한 앱 이름
    check("당군 → 당근", (AppNameMatcher.find("당군", apps) as? AppHit.Likely)?.app?.label, "당근")
    check("당근마켓 라벨 접두", (AppNameMatcher.find("당근", listOf(app("당근마켓", "x.y"))) as? AppHit.Likely)?.app?.label, "당근마켓")
    check("사람 이름은 앱 아님", AppNameMatcher.find("동진님", apps), AppHit.None)
    check("한 글자는 비교 안 함", AppNameMatcher.find("당", apps), AppHit.None)
}

fun main() {
    matcherChecks()
    parserChecks()
    sentenceChecks()
    intentChecks()
    println("assistant-phone selfTest: ${checks - failures}/$checks passed")
    if (failures > 0) exitProcess(1)
}
