package com.mycarhub.assistant.core

/** 음성 인식 결과 한 문장을 해석한 결과. */
sealed interface VoiceCommand {
    /** "카카오톡 열어줘" → target "카카오톡". [hasVerb]가 false면 앱 이름만 말한 경우 (일치하는 앱이 있을 때만 실행). */
    data class OpenApp(val target: String, val hasVerb: Boolean) : VoiceCommand

    /** "카카오톡에 가장 최근에 온 메시지 읽어줘" → app "카카오톡". 앱을 말하지 않으면 null (모든 메시지 앱 중 최신). */
    data class ReadMessage(val app: String?) : VoiceCommand

    /** 방금 읽은 메시지에 답장. "30분 뒤에 도착한다고 답장해 줘" → body "30분 뒤에 도착한다". 내용 없이 "답장해 줘"면 null. */
    data class Reply(val body: String?) : VoiceCommand

    /** "다시 읽어줘", "한 번 더" */
    data object Repeat : VoiceCommand

    /** "취소", "됐어" 등 */
    data object Cancel : VoiceCommand

    /** 전화 걸기, 특정 사람에게 새 메시지 보내기처럼 지원하지 않는 동작. */
    data class Unsupported(val text: String) : VoiceCommand

    data class Unknown(val text: String) : VoiceCommand
}

/** 답장 전송 확인 질문("보낼까요?")에 대한 대답. */
enum class Confirmation { YES, NO, UNKNOWN }

/**
 * 한국어 음성 명령 해석. 규칙 기반이라 기기 안에서만 동작한다.
 *
 * - 앱 실행: "카카오톡 열어줘", "내비게이션 실행해 줘", "카톡 좀 켜 줄래", "음악 틀어 주세요"
 * - 메시지: "카카오톡에 가장 최근에 온 메시지 읽어줘", "새 문자 확인해 줘"
 * - 답장: "30분 뒤에 도착한다고 답장해 줘", "알았어라고 보내 줘"
 */
object CommandParser {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private val SPACES = Regex("\\s+")

    private val CANCEL_WORDS = setOf(
        "취소", "취소해", "취소해줘", "그만", "그만해", "됐어", "됐어요", "아니야", "아니", "아냐",
        "닫아", "닫아줘", "닫기", "없어", "없어요", "괜찮아", "괜찮아요", "종료",
    )

    /** 전화 걸기, 특정 사람에게 새 메시지 보내기 (답장이 아닌 전송). */
    private val UNSUPPORTED = Regex(
        "(전화\\s*(걸|해|좀\\s*걸|좀\\s*해)|에게\\s*전화|한테\\s*전화|(에게|한테|께)\\s*\\S*\\s*(보내|전송))",
    )

    private val REPEAT = Regex("(다시\\s*(읽|말|들려)|한\\s*번\\s*더|한번\\s*더)")

    /** 답장 요청 단어. 이 단어 앞부분이 답장 내용이다. */
    private val REPLY_WORD = Regex("(답장|회신|답해|답\\s*해|보내|전송)")

    /** "답장 보내줘: 곧 갈게"처럼 내용이 뒤에 오는 경우의 앞부분 */
    private val REPLY_HEAD = Regex("^(답장|회신)\\s*(을|으로)?\\s*(좀\\s*)?(보내|해)?\\s*(줘|줘요|주세요|줄래)?\\s*")

    private val MESSAGE_WORDS = listOf("메시지", "메세지", "문자", "카톡", "톡", "알림", "dm")
    private val READ_VERB = Regex("(읽어|읽|알려|들려|확인|뭐야|뭐\\s*왔|왔어|왔는지|있어|있나)")

    /** 실행 동사 + 존댓말·요청 어미. 문장 끝 쪽에서 찾는다. */
    private val OPEN_VERB = Regex(
        "\\s*(열어|열|켜|실행\\s*(시켜|해)?|틀어|띄워|시작\\s*해?|보여|들어가|켜\\s*봐)" +
            "(\\s*(줘|줘요|주세요|줄래|줄래요|봐|봐줘|요|라|줄\\s*수\\s*있어|주라))*\\s*$",
    )

    private val FILLERS = setOf("좀", "저기", "음", "어", "그", "있잖아", "혹시", "지금", "빨리", "얼른")
    private val APP_WORDS = listOf("애플리케이션", "어플리케이션", "프로그램", "어플", "앱")

    /** 메시지 읽기 문장에서 앱 이름이 아닌 말 */
    private val READ_STOP_WORDS = setOf(
        "가장", "제일", "최근", "최근에", "마지막", "마지막으로", "새", "새로", "새로운", "온", "들어온", "받은",
        "방금", "지금", "내", "나한테", "나에게", "좀", "최신", "도착한", "안", "읽은", "안읽은",
    )
    private val TARGET_PARTICLES = listOf("으로부터", "로부터", "에서", "에게", "한테", "에", "의", "으로", "로")

    private val YES_EXACT = setOf("응", "어", "네", "예", "엉", "웅", "ㅇㅇ", "yes", "ok", "오케이")
    private val YES_PREFIX = listOf("응", "네", "예", "그래", "좋아", "보내", "전송", "맞아", "오케이", "확인", "그렇게")
    private val NO_WORDS = listOf("아니", "아냐", "안보내", "보내지마", "보내지말", "하지마", "취소", "그만", "됐어", "싫어", "잠깐", "아직")

    fun parse(text: String, wakeWord: String? = null): VoiceCommand {
        val original = withoutWakeWord(punctuationless(text), wakeWord)
        val sentence = original.lowercase()
        if (sentence.isEmpty()) return VoiceCommand.Unknown(text)

        val compact = sentence.replace(" ", "")
        if (compact in CANCEL_WORDS || sentence.startsWith("취소")) return VoiceCommand.Cancel
        if (UNSUPPORTED.containsMatchIn(sentence)) return VoiceCommand.Unsupported(text)
        if (REPEAT.containsMatchIn(sentence)) return VoiceCommand.Repeat
        if (REPLY_WORD.containsMatchIn(sentence)) return VoiceCommand.Reply(extractReplyBody(original))
        if (isReadRequest(sentence)) return VoiceCommand.ReadMessage(readTarget(sentence))
        return parseOpenApp(sentence, text)
    }

    /** "보낼까요?"에 대한 대답. 부정을 먼저 본다 ("보내지 마"). 애매하면 UNKNOWN → 보내지 않는다. */
    fun parseConfirmation(text: String): Confirmation {
        val compact = punctuationless(text).lowercase().replace(" ", "")
        if (compact.isEmpty()) return Confirmation.UNKNOWN
        if (NO_WORDS.any { compact.contains(it) }) return Confirmation.NO
        if (compact in YES_EXACT || YES_PREFIX.any { compact.startsWith(it) }) return Confirmation.YES
        return Confirmation.UNKNOWN
    }

    /**
     * "어떤 내용으로 답장할까요?"에 대한 대답에서 답장 내용만 뽑는다.
     * "곧 간다고 보내줘" → "곧 간다", "알았어" → "알았어". 취소하는 말이면 null.
     */
    fun parseReplyBody(text: String): String? {
        val original = punctuationless(text)
        val compact = original.lowercase().replace(" ", "")
        if (compact.isEmpty() || compact in CANCEL_WORDS) return null
        if (REPLY_WORD.containsMatchIn(original.lowercase())) return extractReplyBody(original)
        return stripQuotation(original).ifEmpty { null }
    }

    // region 앱 실행

    private fun parseOpenApp(sentence: String, text: String): VoiceCommand {
        val verb = OPEN_VERB.find(sentence)
        // "열어줘"처럼 앱 이름 없이 동사만 말한 경우
        if (verb != null && verb.range.first == 0) return VoiceCommand.Unknown(text)
        val hasVerb = verb != null
        val rawTarget = if (verb != null) sentence.substring(0, verb.range.first) else sentence
        val target = stripTarget(rawTarget)
        if (target.isEmpty()) return VoiceCommand.Unknown(text)
        // 동사 없이 긴 문장은 일상 대화일 가능성이 높아 명령으로 보지 않는다
        if (!hasVerb && target.split(' ').size > MAX_BARE_WORDS) return VoiceCommand.Unknown(text)
        return VoiceCommand.OpenApp(target, hasVerb)
    }

    private fun stripTarget(raw: String): String {
        val words = raw.trim().split(' ').filter { it.isNotEmpty() && it !in FILLERS }.toMutableList()
        while (words.isNotEmpty()) {
            val last = words.last()
            val trimmed = stripSuffix(last)
            when {
                trimmed.isEmpty() -> words.removeAt(words.lastIndex)
                trimmed != last -> words[words.lastIndex] = trimmed
                else -> break
            }
        }
        return words.joinToString(" ")
    }

    /** 끝에 붙은 조사·'앱'을 떼어낸다: "카카오톡을" → "카카오톡", "유튜브앱" → "유튜브", "앱" → "". */
    private fun stripSuffix(word: String): String {
        if (word in APP_WORDS || word in FILLERS) return ""
        var w = word
        for (particle in listOf("을", "를")) {
            if (w.length > 2 && w.endsWith(particle)) w = w.dropLast(1)
        }
        for (suffix in APP_WORDS) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) w = w.dropLast(suffix.length)
        }
        return w
    }

    // endregion

    // region 메시지

    private fun isReadRequest(sentence: String): Boolean {
        val compact = sentence.replace(" ", "")
        val mentionsMessage = MESSAGE_WORDS.any { compact.contains(it) }
        return (mentionsMessage && READ_VERB.containsMatchIn(sentence)) || compact.contains("읽어")
    }

    /** "카카오톡에 가장 최근에 온 메시지 읽어줘" → "카카오톡", "최근 메시지 읽어줘" → null, "문자 읽어줘" → "문자". */
    private fun readTarget(sentence: String): String? {
        val first = sentence.split(' ').firstOrNull { it.isNotEmpty() && it !in FILLERS } ?: return null
        var word = first
        for (particle in TARGET_PARTICLES) {
            if (word.length > particle.length && word.endsWith(particle)) {
                word = word.dropLast(particle.length)
                break
            }
        }
        if (word in READ_STOP_WORDS) return null
        // "메시지 읽어줘", "알림 확인해 줘": 앱을 말하지 않음. "문자"·"카톡"·"톡"은 앱(종류)으로 본다.
        if (word in setOf("메시지", "메세지", "알림", "dm") || READ_VERB.matchesAt(word, 0)) return null
        return word.removeSuffix("메시지").removeSuffix("메세지").ifEmpty { null }
    }

    /** 답장 단어 앞부분(없으면 뒷부분)을 답장 내용으로 쓴다. 원래 대소문자를 유지한다. */
    private fun extractReplyBody(original: String): String? {
        val lower = original.lowercase()
        val match = REPLY_WORD.find(lower) ?: return null
        val before = original.substring(0, match.range.first).trim()
        val body = if (before.isNotEmpty()) {
            before
        } else {
            val head = REPLY_HEAD.find(lower)
            if (head == null) "" else original.substring(head.range.last + 1).trim()
        }
        return stripQuotation(body.removeSuffix("으로").removeSuffix("답장").trim()).ifEmpty { null }
    }

    /** 간접 인용 어미를 뗀다: "도착한다고" → "도착한다", "알았어라고" → "알았어", "회의 중이라고" → "회의 중". */
    private fun stripQuotation(body: String): String {
        val words = body.trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && (words.last() == "좀" || words.last() == "이렇게" || words.last() == "그렇게")) {
            words.removeAt(words.lastIndex)
        }
        if (words.isEmpty()) return ""
        var last = words.last()
        last = when {
            last == "라고" || last == "이라고" -> "".also { words.removeAt(words.lastIndex) }
            last.length > 3 && last.endsWith("이라고") -> last.dropLast(3)
            last.length > 2 && last.endsWith("라고") -> last.dropLast(2)
            last.length > 2 && last.last() == '고' && last[last.length - 2] in "다자냐래" -> last.dropLast(1)
            else -> last
        }
        if (last.isNotEmpty() && words.isNotEmpty()) words[words.lastIndex] = last
        return words.joinToString(" ").trim()
    }

    // endregion

    private fun punctuationless(text: String): String =
        text.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()

    private fun withoutWakeWord(sentence: String, wakeWord: String?): String {
        val name = wakeWord?.let(::punctuationless)?.lowercase()?.takeIf { it.isNotEmpty() } ?: return sentence
        // 호출어를 다시 말한 경우 앞부분에서 제거 ("붕붕아 카톡 열어줘")
        return if (sentence.lowercase().startsWith(name)) sentence.substring(name.length).replace(CALL_SUFFIX, "").trim() else sentence
    }

    private const val MAX_BARE_WORDS = 3

    /** "쁘이야 카톡 열어줘"처럼 호출어 뒤에 붙인 부르는 말 */
    internal val CALL_SUFFIX = Regex("^(야|아)(?=\\s|$)")
}
