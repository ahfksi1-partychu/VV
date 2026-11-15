package com.mycarhub.assistant.phone

/** 말한 이름을 후보 이름들과 비교한 결과. */
sealed interface NameMatch {
    /** 호칭(님·씨)·띄어쓰기만 다르고 같은 이름: 확인 없이 쓴다. */
    data class Exact(val name: String) : NameMatch

    /** 하나만 충분히 비슷함: "동진님 말씀하시는 거죠?"라고 확인한 뒤 쓴다. */
    data class Likely(val name: String, val similarity: Float) : NameMatch

    /** 비슷한 이름이 여럿: 후보를 읽어 주고 누구인지 묻는다. */
    data class Ambiguous(val names: List<String>) : NameMatch

    /** 충분히 비슷한 이름이 없음: 임의로 고르지 않고 다시 묻는다. */
    data object None : NameMatch
}

/**
 * 음성 인식이 이름을 한두 글자 잘못 받아 적어도 실제 이름을 찾는다 ("동지님" → "동진님").
 *
 * 한글을 초성·중성·종성으로 나눠 발음이 비슷한 소리끼리는 작은 비용으로 비교한다.
 * - 초성: 같은 조음 위치(ㄱ/ㄲ/ㅋ, ㄷ/ㄸ/ㅌ, ㅂ/ㅃ/ㅍ, ㅈ/ㅉ/ㅊ, ㅅ/ㅆ, ㅇ/ㅎ)는 가깝게
 * - 중성: ㅐ/ㅔ처럼 소리가 거의 같은 모음, ㅓ/ㅕ·ㅗ/ㅛ처럼 비슷한 모음은 가깝게
 * - 종성: 받침을 7개 대표음으로 바꿔 비교하고, 인식기가 자주 빠뜨리는 비음 받침(ㄴ·ㅇ·ㅁ)의 유무는 가깝게
 *
 * 사람 이름 목록은 호출하는 쪽이 넘기고 여기서는 저장하지 않는다.
 */
object KoreanNameMatcher {

    /** 이보다 덜 비슷하면 후보로 보지 않는다 */
    const val ACCEPT = 0.75f

    /** 1등과 이만큼 이내인 후보는 함께 물어본다 */
    const val MARGIN = 0.1f

    const val MAX_CHOICES = 3

    private val TITLES = listOf("님", "씨")
    private val BRACKETS = Regex("\\(.*?\\)|\\[.*?\\]")

    fun resolve(query: String, names: List<String>): NameMatch {
        if (normalize(query).isEmpty()) return NameMatch.None
        val scored = names.distinctBy(::normalize)
            .filter { normalize(it).isNotEmpty() }
            .map { it to similarity(query, it) }
            .sortedByDescending { it.second }
        val exact = scored.filter { it.second >= 1f }
        if (exact.size == 1) return NameMatch.Exact(exact.single().first)
        if (exact.size > 1) return NameMatch.Ambiguous(exact.take(MAX_CHOICES).map { it.first })
        val best = scored.firstOrNull()?.takeIf { it.second >= ACCEPT } ?: return NameMatch.None
        val close = scored.filter { it.second >= ACCEPT && it.second >= best.second - MARGIN }.take(MAX_CHOICES)
        return if (close.size == 1) NameMatch.Likely(best.first, best.second) else NameMatch.Ambiguous(close.map { it.first })
    }

    /** 0~1. 1이면 호칭·띄어쓰기를 빼고 같은 이름. */
    fun similarity(query: String, name: String): Float {
        var best = 0f
        for ((q, qPenalty) in variants(query, isQuery = true)) {
            for ((n, nPenalty) in variants(name, isQuery = false)) {
                val length = maxOf(q.length, n.length)
                if (length == 0) continue
                val score = 1f - distance(q, n) / length - qPenalty - nPenalty
                if (score > best) best = score
            }
        }
        return best.coerceIn(0f, 1f)
    }

    /** 비교용 이름: 소문자, 괄호 속 설명·띄어쓰기·기호 제거, 끝의 호칭(님·씨) 제거. */
    fun normalize(name: String): String {
        var n = name.lowercase().replace(BRACKETS, "").filter { it.isLetterOrDigit() }
        for (title in TITLES) {
            if (n.length > title.length && n.endsWith(title)) n = n.dropLast(title.length)
        }
        return n
    }

    /** 비교해 볼 형태와 그 형태로 맞았을 때 깎는 점수. */
    private fun variants(name: String, isQuery: Boolean): List<Pair<String, Float>> {
        val n = normalize(name)
        val list = mutableListOf(n to 0f)
        // 세 글자 한글 이름은 성을 빼고 부르기도 한다 ("동진" ↔ "김동진")
        if (n.length == 3 && n.all(::isHangul)) list += n.drop(1) to GIVEN_NAME_PENALTY
        // "동진이 메시지", "동진님이 보낸": 이름 뒤 '이'·'가' (이름 끝 글자일 수도 있어 둘 다 비교)
        val raw = name.trim()
        if (isQuery && raw.length >= 3 && (raw.endsWith('이') || raw.endsWith('가'))) {
            normalize(raw.dropLast(1)).takeIf { it.isNotEmpty() }?.let { list += it to SUFFIX_PENALTY }
        }
        return list
    }

    /** 글자 단위 편집 거리. 한글 두 글자는 초성·중성·종성의 발음 차이만큼만 비용을 낸다. */
    internal fun distance(a: String, b: String): Float {
        var previous = FloatArray(b.length + 1) { it.toFloat() }
        for (i in 1..a.length) {
            val row = FloatArray(b.length + 1)
            row[0] = i.toFloat()
            for (j in 1..b.length) {
                row[j] = minOf(
                    previous[j] + 1f,
                    row[j - 1] + 1f,
                    previous[j - 1] + syllableCost(a[i - 1], b[j - 1]),
                )
            }
            previous = row
        }
        return previous[b.length]
    }

    internal fun syllableCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        if (!isHangul(a) || !isHangul(b)) return 1f
        val x = a - HANGUL_FIRST
        val y = b - HANGUL_FIRST
        return INITIAL_WEIGHT * initialCost(x / 588, y / 588) +
            MEDIAL_WEIGHT * medialCost(x % 588 / 28, y % 588 / 28) +
            FINAL_WEIGHT * finalCost(FINAL_SOUND[x % 28], FINAL_SOUND[y % 28])
    }

    private fun isHangul(c: Char) = c in HANGUL_FIRST..HANGUL_LAST

    // region 소리 비교표

    private const val HANGUL_FIRST = '가'
    private const val HANGUL_LAST = '힣'
    private const val INITIAL_WEIGHT = 0.4f
    private const val MEDIAL_WEIGHT = 0.35f
    private const val FINAL_WEIGHT = 0.25f
    private const val GIVEN_NAME_PENALTY = 0.03f
    private const val SUFFIX_PENALTY = 0.02f

    // 초성 순서: ㄱ ㄲ ㄴ ㄷ ㄸ ㄹ ㅁ ㅂ ㅃ ㅅ ㅆ ㅇ ㅈ ㅉ ㅊ ㅋ ㅌ ㅍ ㅎ
    // 조음 위치: 0 연구개(ㄱㄲㅋ) 1 비음(ㄴㅁ) 2 치조 파열(ㄷㄸㅌ) 3 유음(ㄹ) 4 양순(ㅂㅃㅍ) 5 마찰(ㅅㅆ) 6 성문(ㅇㅎ) 7 파찰(ㅈㅉㅊ)
    private val INITIAL_GROUP = intArrayOf(0, 0, 1, 2, 2, 3, 1, 4, 4, 5, 5, 6, 7, 7, 7, 0, 2, 4, 6)
    private const val NASAL = 1

    private fun initialCost(a: Int, b: Int): Float {
        if (a == b) return 0f
        val ga = INITIAL_GROUP[a]
        val gb = INITIAL_GROUP[b]
        if (ga == gb) return if (ga == NASAL) 0.5f else 0.3f
        return when (setOf(ga, gb)) {
            setOf(5, 7) -> 0.6f // ㅅ ↔ ㅈ
            setOf(1, 3) -> 0.5f // ㄴ ↔ ㄹ
            setOf(2, 7) -> 0.7f // ㄷ ↔ ㅈ
            else -> 1f
        }
    }

    // 중성 순서: ㅏ ㅐ ㅑ ㅒ ㅓ ㅔ ㅕ ㅖ ㅗ ㅘ ㅙ ㅚ ㅛ ㅜ ㅝ ㅞ ㅟ ㅠ ㅡ ㅢ ㅣ
    private val MEDIAL_NEAR: Map<Int, Float> = listOf(
        Triple("ㅐ", "ㅔ", 0.15f), Triple("ㅒ", "ㅖ", 0.15f), Triple("ㅙ", "ㅞ", 0.15f),
        Triple("ㅚ", "ㅙ", 0.2f), Triple("ㅚ", "ㅞ", 0.2f), Triple("ㅐ", "ㅒ", 0.3f), Triple("ㅔ", "ㅖ", 0.3f),
        Triple("ㅏ", "ㅑ", 0.4f), Triple("ㅓ", "ㅕ", 0.4f), Triple("ㅗ", "ㅛ", 0.4f), Triple("ㅜ", "ㅠ", 0.4f),
        Triple("ㅢ", "ㅣ", 0.4f), Triple("ㅢ", "ㅡ", 0.4f), Triple("ㅚ", "ㅔ", 0.4f), Triple("ㅞ", "ㅔ", 0.4f), Triple("ㅙ", "ㅐ", 0.4f),
        Triple("ㅓ", "ㅗ", 0.5f), Triple("ㅡ", "ㅜ", 0.5f), Triple("ㅘ", "ㅏ", 0.5f), Triple("ㅝ", "ㅓ", 0.5f), Triple("ㅟ", "ㅣ", 0.5f),
        Triple("ㅡ", "ㅓ", 0.6f), Triple("ㅏ", "ㅓ", 0.7f), Triple("ㅣ", "ㅡ", 0.7f),
    ).associate { (a, b, cost) -> pairKey(medialIndex(a), medialIndex(b)) to cost }

    private fun medialIndex(jamo: String) = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ".indexOf(jamo)

    private fun pairKey(a: Int, b: Int) = minOf(a, b) * 32 + maxOf(a, b)

    private fun medialCost(a: Int, b: Int): Float = if (a == b) 0f else MEDIAL_NEAR[pairKey(a, b)] ?: 1f

    // 종성 → 대표음: 0 없음 1 ㄱ 2 ㄴ 3 ㄷ 4 ㄹ 5 ㅁ 6 ㅂ 7 ㅇ
    // 종성 순서: (없음) ㄱ ㄲ ㄳ ㄴ ㄵ ㄶ ㄷ ㄹ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅁ ㅂ ㅄ ㅅ ㅆ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ
    private val FINAL_SOUND = intArrayOf(0, 1, 1, 1, 2, 2, 2, 3, 4, 1, 5, 4, 4, 4, 6, 4, 5, 6, 6, 3, 3, 7, 3, 3, 1, 3, 6, 3)
    private val NASAL_FINALS = setOf(2, 5, 7)
    private val STOP_FINALS = setOf(1, 3, 6)

    private fun finalCost(a: Int, b: Int): Float = when {
        a == b -> 0f
        // 받침 하나가 빠지거나 더해짐: 비음 받침은 인식기가 자주 놓친다
        a == 0 || b == 0 -> if (maxOf(a, b) in NASAL_FINALS) 0.5f else 0.6f
        a in NASAL_FINALS && b in NASAL_FINALS -> if (setOf(a, b) == setOf(2, 7)) 0.3f else 0.4f
        a in STOP_FINALS && b in STOP_FINALS -> 0.4f
        else -> 1f
    }

    // endregion
}
