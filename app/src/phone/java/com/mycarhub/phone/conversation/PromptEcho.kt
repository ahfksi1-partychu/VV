package com.mycarhub.phone.conversation

/**
 * 안내("네, 말씀하세요!")를 말하는 동안 연 음성 인식에서, 스피커 소리가 마이크로 들어와 인식된 안내 문장을 떼어낸다.
 *
 * Android SpeechRecognizer는 마이크 입력을 앱이 고를 수 없어 에코 제거(AEC)를 직접 걸 수 없다.
 * 대신 앱이 무슨 말을 했는지 알고 있으므로, 인식 문장의 앞부분이 안내 문장과 같은 순서로 겹치면 그 단어들을 뗀다.
 * "네 말씀하세요 카카오톡 열어줘" → "카카오톡 열어줘", "네 말씀" → "" (아직 사용자 말 없음).
 */
internal class PromptEcho(prompt: String) {
    /** 안내를 아직 말하는 중인지 */
    var speaking = true

    /** 안내 소리만 인식된 적이 있는지 (그 뒤 인식이 끝나면 다시 듣는다) */
    var heardEcho = false

    /** 안내 소리만 듣고 끝나 인식을 다시 연 횟수 */
    var restarts = 0

    private val prompt = normalize(prompt)

    /** [heard]에서 앞부분의 안내 문장을 뗀 나머지 (사용자가 한 말). 전부 안내 소리였으면 빈 문자열. */
    fun strip(heard: String): String {
        val words = heard.trim().split(SPACES).filter { it.isNotEmpty() }
        var pos = 0
        var used = 0
        for (word in words) {
            val w = normalize(word)
            if (w.isEmpty()) {
                used++
                continue
            }
            // 인식기가 늦게 열려 안내 앞부분을 놓쳤을 수 있다: 첫 단어(두 글자 이상)는 안내 어디에서 시작해도 된다
            val at = (if (used == 0 && w.length >= 2) find(w, 0, prompt.length) else find(w, pos)) ?: break
            pos = at + w.length
            used++
        }
        val rest = words.drop(used).joinToString(" ")
        if (used > 0 && rest.isEmpty()) heardEcho = true
        return rest
    }

    /** 안내 문장에서 [from] 위치 가까이(인식기가 놓친 글자 몇 개는 건너뜀)에 [w]가 이어지는 곳 */
    private fun find(w: String, from: Int, skip: Int = MAX_SKIP): Int? {
        for (start in from..minOf(from + skip, prompt.length - w.length)) {
            var miss = 0
            for (i in w.indices) if (!sameSound(w[i], prompt[start + i])) miss++
            if (miss <= if (w.length >= 3) 1 else 0) return start
        }
        return null
    }

    companion object {
        private val SPACES = Regex("\\s+")
        private const val MAX_SKIP = 2

        private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

        /** 같은 글자이거나, 인식기가 자주 헷갈리는 'ㅐ/ㅔ', 'ㅒ/ㅖ'만 다른 한글 글자 ("내"↔"네") */
        private fun sameSound(a: Char, b: Char): Boolean {
            if (a == b) return true
            if (a !in '가'..'힣' || b !in '가'..'힣') return false
            val x = a - '가'
            val y = b - '가'
            if (x / 588 != y / 588 || x % 28 != y % 28) return false
            return vowelGroup((x / 28) % 21) == vowelGroup((y / 28) % 21)
        }

        // ㅐ(1)=ㅔ(5), ㅒ(3)=ㅖ(7)
        private fun vowelGroup(v: Int): Int = when (v) {
            5 -> 1
            7 -> 3
            else -> v
        }
    }
}
