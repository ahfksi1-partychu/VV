package com.mycarhub.assistant.phone

import com.mycarhub.assistant.core.CommandParser
import com.mycarhub.assistant.core.VoiceCommand

/** 사람 이름이 들어간 메시지 명령. */
sealed interface PersonCommand {
    /**
     * "동진님 메시지 읽어줘", "카톡에서 동진님이 보낸 메시지 읽어줘".
     * [words]는 사람 이름 또는 앱 이름일 수 있는 낱말들 (어느 쪽인지는 실제 메시지 앱 목록으로 가린다).
     */
    data class Read(val words: List<String>) : PersonCommand

    /** "동진님한테 곧 간다고 답장해줘" → name "동진님", body "곧 간다". 내용 없이 "동진님한테 답장해줘"면 body null. */
    data class Reply(val name: String, val body: String?) : PersonCommand

    /** "동진님 카톡방 열어줘", "카톡에서 동진님 대화방 열어줘": 그 사람과의 대화방 열기. [words]는 [Read]와 같다. */
    data class Open(val words: List<String>) : PersonCommand
}

/**
 * 공유 해석기([CommandParser])가 앱 이름으로만 보는 메시지 명령에서 사람 이름을 찾는다. 폰 빌드 전용.
 *
 * 차량용과 공유하는 해석 규칙은 바꾸지 않고, 그 결과(메시지 읽기·답장)에 사람 이름이 들어 있을 때만 여기서 다시 본다.
 */
object PersonCommandParser {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private val SPACES = Regex("\\s+")

    /** 받는 사람 조사: "동진님한테", "동진님에게", "선생님께" */
    private val RECIPIENT_PARTICLES = listOf("한테다", "에게다", "한테", "에게", "께")

    /** 보낸 사람·앱을 꾸미는 조사 (긴 것부터). 이·가는 이름 끝 글자일 수 있어 떼지 않고 이름 비교에서 함께 본다. */
    private val PARTICLES = listOf(
        "한테서", "에게서", "으로부터", "로부터", "께서", "한테", "에게", "에서", "이랑", "하고", "의", "께", "에",
    )

    /** "동진님 메시지에 답장해줘": 이름 다음에 오는 '메시지에' */
    private val MESSAGE_TARGETS = setOf("메시지에", "메세지에", "문자에", "카톡에", "톡에", "메시지", "메세지")

    /** 답장 내용 앞에 붙는 메시지 종류: "카톡으로 곧 간다고 보내줘" */
    private val MEDIUM_WORDS = setOf("카톡", "톡", "문자", "메시지", "메세지", "카카오톡", "답장", "답")

    private val REPLY_WORD = Regex("(답장|회신|답해|답\\s*해|보내|전송)")

    /** 이름도 앱도 아닌 말 */
    private val STOP_WORDS = setOf(
        "메시지", "메세지", "알림", "dm", "내용", "것", "거", "뭐", "뭐라고", "뭐래", "뭐라는지",
        "가장", "제일", "최근", "최근에", "마지막", "마지막으로", "새", "새로", "새로운", "온", "들어온", "받은", "보낸", "보내온",
        "방금", "지금", "내", "나한테", "나에게", "좀", "최신", "도착한", "안", "읽은", "안읽은", "저기", "음", "어", "그", "혹시", "빨리", "얼른",
        "줘", "줘요", "주세요", "줄래", "봐", "해", "해줘", "해봐",
        "대화", "대화방", "채팅", "채팅방", "톡방", "카톡방", "대화내용", "대화창", "채팅창",
        // "당근 앱 알림": 앞 낱말이 앱이라는 단서 ([TargetClassifier.hint]가 문장에서 본다)
        "앱", "어플", "애플리케이션", "앱의", "앱에서", "어플에서",
    )
    private val VERB_PREFIXES = listOf("읽", "알려", "들려", "확인", "왔", "보여", "말해")

    /** 이름 뒤에 띄어 쓰지 않고 붙어 들어오는 말: "동지님대화", "동진님메시지" (긴 것부터) */
    private val NAME_TAILS = listOf("카톡방", "대화방", "채팅방", "대화창", "채팅창", "메시지", "메세지", "톡방", "대화", "채팅")

    fun parse(text: String, wakeWord: String? = null): PersonCommand? {
        val sentence = withoutWakeWord(clean(text), wakeWord)
        if (sentence.isEmpty()) return null
        openRoom(sentence)?.let { return it }
        return when (CommandParser.parse(sentence)) {
            is VoiceCommand.ReadMessage -> readWords(sentence)?.let(PersonCommand::Read)
            // "엄마한테 보내줘"는 공유 해석기에서 '새 메시지 보내기(미지원)'로 본다: 받는 사람이 있으면 여기서 답장으로 다시 본다
            is VoiceCommand.Reply, is VoiceCommand.Unsupported -> reply(sentence)
            else -> nameWithMessageNoun(sentence)
        }
    }

    /**
     * 동사 없이 "동지님 대화", "동진님 메시지"라고만 말한 경우 (공유 해석기는 읽기 동사가 있어야 읽기로 본다).
     * 짧은 문장이 메시지 명사로 끝나고 앱 종류가 아닌 이름이 남을 때만 읽기로 본다: "카톡 대화"는 여기서 잡지 않는다.
     */
    private fun nameWithMessageNoun(sentence: String): PersonCommand.Read? {
        val tokens = sentence.split(' ')
        val last = tokens.last()
        if (tokens.size > 3 || MESSAGE_NOUNS.none { last == it || (last.length > it.length && last.endsWith(it)) }) return null
        val words = readWords(sentence) ?: return null
        return if (words.any { it !in MEDIUM_WORDS }) PersonCommand.Read(words) else null
    }

    private val MESSAGE_NOUNS = listOf("대화", "대화방", "채팅", "채팅방", "메시지", "메세지")

    private val OPEN_VERB = Regex("^(열어|열기|들어가|띄워)")

    /** 대화방을 가리키는 말 (긴 것부터). "카톡 열어줘"처럼 이것이 없으면 앱 열기로 둔다. */
    private val ROOM_NOUNS = listOf("카톡방", "대화방", "채팅방", "대화창", "채팅창", "톡방", "대화", "채팅")

    /**
     * "동진님 카톡방 열어줘", "동진님 대화방 들어가 줘", "카톡에서 동진님 대화방 열어줘".
     * 여는 동사 앞에 대화방을 가리키는 말과 이름이 함께 있을 때만 대화방 열기로 본다.
     */
    private fun openRoom(sentence: String): PersonCommand.Open? {
        val tokens = sentence.split(' ')
        val verb = tokens.indexOfFirst { OPEN_VERB.containsMatchIn(it) }
        if (verb <= 0) return null
        val before = tokens.take(verb).map { token ->
            // "카톡방을", "대화방을": 목적격 조사를 뗀다
            val stem = token.removeSuffix("을").removeSuffix("를")
            if (stem != token && ROOM_NOUNS.any { stem.endsWith(it) }) stem else token
        }
        if (before.none { token -> ROOM_NOUNS.any { token == it || token.endsWith(it) } }) return null
        val words = readWords(before.joinToString(" "))?.filter { it !in ROOM_NOUNS } ?: return null
        return if (words.any { it !in MEDIUM_WORDS }) PersonCommand.Open(words) else null
    }

    /** 조사 단서를 볼 문장: 기호·호출어를 뺀 인식 문장 ([TargetClassifier.hint]용). */
    fun sentence(text: String, wakeWord: String? = null): String = withoutWakeWord(clean(text), wakeWord)

    /** "누구인가요?"에 대한 대답에서 이름만: "동진님이요" → "동진님", "동진님 메시지" → "동진님". */
    fun nameAnswer(text: String, wakeWord: String? = null): String {
        val words = withoutWakeWord(clean(text), wakeWord).split(' ')
            .filter { it.isNotEmpty() && it !in STOP_WORDS && it !in ANSWER_WORDS }
            .toMutableList()
        if (words.isEmpty()) return ""
        val last = words.last()
        words[words.lastIndex] = ANSWER_ENDINGS.firstOrNull { last.length > it.length && last.endsWith(it) }
            ?.let { last.dropLast(it.length) } ?: last
        return withoutTail(stripParticle(withoutTail(words.joinToString(""))))
    }

    /**
     * 읽기 명령의 낱말에서 검색할 이름: 앱 이름으로 쓰인 낱말([app])을 빼고 나머지를 붙인다.
     * "카톡에서 동지님 대화 읽어줘" → words [카톡, 동지님], app 카톡 → "동지님".
     */
    fun nameQuery(words: List<String>, app: String?): String = words.filter { it != app }.joinToString("")

    /** "동지님대화" → "동지님". 붙은 말을 떼고 남는 게 없으면 원래 낱말. */
    private fun withoutTail(word: String): String {
        var name = word
        while (true) {
            val tail = NAME_TAILS.firstOrNull { name.length > it.length && name.endsWith(it) } ?: return name
            name = name.dropLast(tail.length)
        }
    }

    private val ANSWER_WORDS = setOf("말이야", "말이에요", "말이요", "말하는", "거야", "거예요", "거요", "요")
    private val ANSWER_ENDINGS = listOf("이에요", "입니다", "이요", "예요", "에요", "이야", "요")

    private fun readWords(sentence: String): List<String>? {
        val words = sentence.split(' ').mapNotNull { token ->
            val word = stripParticle(token)
            when {
                word.isEmpty() || word in STOP_WORDS || token in STOP_WORDS -> null
                VERB_PREFIXES.any { word.startsWith(it) } -> null
                // 붙은 말을 뗀 뒤 남은 조사도 뗀다: "동지님의대화" → "동지님"
                else -> stripParticle(withoutTail(word)).takeIf { it !in STOP_WORDS }
            }
        }
        return words.ifEmpty { null }
    }

    private fun reply(sentence: String): PersonCommand.Reply? {
        val tokens = sentence.split(' ')
        // 이름은 문장 앞쪽(두 번째 낱말까지)에서만 찾는다: 답장 내용 속의 "엄마한테"를 받는 사람으로 보지 않게
        for (i in 0 until minOf(2, tokens.size)) {
            val token = tokens[i]
            val particle = RECIPIENT_PARTICLES.firstOrNull { token.length > it.length && token.endsWith(it) }
            val (name, restFrom) = when {
                particle != null -> token.dropLast(particle.length) to i + 1
                tokens.getOrNull(i + 1) in MESSAGE_TARGETS -> token to i + 2
                else -> continue
            }
            val rest = tokens.drop(restFrom).dropWhile { stripMedium(it) }.joinToString(" ")
            if (!REPLY_WORD.containsMatchIn(rest)) return null
            if (name in STOP_WORDS || name in MEDIUM_WORDS) return null
            return PersonCommand.Reply(name, CommandParser.parseReplyBody(rest))
        }
        return null
    }

    /** "카톡으로", "문자로" 처럼 내용 앞의 메시지 종류 */
    private fun stripMedium(token: String): Boolean {
        val word = token.removeSuffix("으로").removeSuffix("로").removeSuffix("을").removeSuffix("를")
        return word in MEDIUM_WORDS && !REPLY_WORD.matches(token)
    }

    private fun stripParticle(token: String): String {
        for (particle in PARTICLES) {
            // 한 글자 조사는 두 글자 이상 남을 때만 뗀다
            val keep = if (particle.length == 1) 2 else 1
            if (token.length >= particle.length + keep && token.endsWith(particle)) return token.dropLast(particle.length)
        }
        return token
    }

    private fun clean(text: String) = text.replace(PUNCTUATION, " ").replace(SPACES, " ").trim().lowercase()

    private fun withoutWakeWord(sentence: String, wakeWord: String?): String {
        val name = wakeWord?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return sentence
        return if (sentence.startsWith(name)) sentence.substring(name.length).trim() else sentence
    }
}
