package com.mycarhub.assistant.core

/** 차 안에서 물건을 둔 자리 (사용자가 말한 것. 말하지 않았으면 null = 그냥 '차') */
enum class CarSpot(val label: String) {
    TRUNK("트렁크"),
    BACK_SEAT("뒷좌석"),
    FRONT_SEAT("앞좌석"),
    GLOVE_BOX("글로브박스"),
    CONSOLE("콘솔"),
}

/** 차량에 남긴 물건 음성 명령 */
sealed interface CarItemCommand {
    /** "차에 택배 상자 두고 내렸어" ([pickup] false: 기록만), "차에서 노트북 챙겨야 해" ([pickup] true: 하차 후 할 일) */
    data class Leave(val name: String, val spot: CarSpot?, val pickup: Boolean) : CarItemCommand

    /** "차에 뭐 뒀지?" */
    data object ListAll : CarItemCommand

    /** "차에서 챙길 거 있어?" */
    data object ListPickup : CarItemCommand

    /** "내 우산 어디 있어?" */
    data class Find(val name: String) : CarItemCommand

    /** "노트북 가져왔어" ([saidCar]: 문장에 차·트렁크 등이 있었는지. 없으면 기록과 맞을 때만 처리) */
    data class Retrieved(val name: String, val saidCar: Boolean) : CarItemCommand
}

/**
 * 차량에 남긴 물건 (규칙 기반, 기기 안에서만). 차량 센서로 확인하는 것이 아니라 사용자가 말한 것을 기록할 뿐이다.
 *
 * [DriveCommands]의 주차 위치 질문 다음, 할 일 등록보다 먼저 본다 ("차에서 노트북 챙겨야 해"가 일반 할 일이 되지 않게).
 * 차·트렁크·뒷좌석 같은 말이 있는 문장만 등록으로 잡아 "집에 가서 택배 확인해야 해" 같은 할 일을 가로채지 않는다.
 */
object CarItems {

    /** 차 안의 자리를 가리키는 말 (앞에 '차', '내 차' 등이 붙을 수 있다) */
    private const val PLACE =
        "(?:(?:내|우리|제)\\s*)?(?:(?:차|차량|자동차)\\s*(?:안|속)?\\s*)?" +
            "(?:트렁크|뒷\\s*좌석|뒷\\s*자리|뒷\\s*칸|조수석|앞\\s*좌석|앞\\s*자리|운전석|글로브\\s*박스|글러브\\s*박스|콘솔|컵\\s*홀더|차\\s*안|차량|자동차|차)"
    private const val AT = "(?:에다가|에다|안에|속에|에)"
    private const val LEAVE_VERB =
        "(?:두고\\s*(?:내렸|왔|나왔|갔|내림)|놔\\s*두고\\s*(?:내렸|왔|나왔)|놓고\\s*(?:내렸|왔|나왔)|놔\\s*뒀|놔\\s*두었|놓아\\s*뒀|놓아\\s*두었|" +
            "넣어\\s*(?:뒀|두었|놨|놓았)|실어\\s*(?:뒀|두었|놨|놓았)|넣었|실었|뒀|두었|놨|놓았)"
    private const val ENDING = "(?:어요|어|다|음|는데|거든|지|습니다)?"
    private const val PICKUP_VERB =
        "(?:(?:챙겨|가져와|가지고\\s*와|가지고\\s*나와|가져\\s*와|꺼내|빼)\\s*야\\s*(?:해요|돼요|해|돼|하는데|되는데|함|겠다|겠어|지|된다|한다)?|" +
            "(?:챙기|가져오|꺼내|빼)(?:라고|도록|게)?\\s*(?:알려\\s*줘|말해\\s*줘|해\\s*줘|기억해\\s*줘)(?:요)?)"

    private val LEAVE_PLACE_FIRST = Regex("^($PLACE)$AT?\\s+(.+?)\\s*(?:을|를)?\\s*$LEAVE_VERB$ENDING(.*)$")
    private val LEAVE_ITEM_FIRST = Regex("^(.+?)\\s*(?:을|를)?\\s+($PLACE)$AT\\s*$LEAVE_VERB$ENDING(.*)$")
    private val PICKUP_PLACE_FIRST = Regex("^($PLACE)(?:에서|$AT\\s*(?:있는|둔|놔둔|놓고\\s*온|두고\\s*온))\\s+(.+?)\\s*(?:을|를|도)?\\s*$PICKUP_VERB$")
    private val PICKUP_ITEM_FIRST = Regex("^(.+?)\\s*(?:을|를|도)?\\s+($PLACE)에서\\s*$PICKUP_VERB$")

    /** 등록 문장 뒤에 붙은 '알려 달라'는 말 → 하차 후 할 일로 */
    private val REMIND_TAIL = Regex("(잊지\\s*않게|잊지\\s*말게|까먹지\\s*않게|안\\s*잊게|나중에|내릴\\s*때|하차할\\s*때|내리면|챙기라고|챙겨야|가져와야|꺼내야|알려\\s*줘|리마인드|기억해\\s*줘)")

    private val LIST_ALL = listOf(
        Regex("^$PLACE$AT?\\s*(?:뭐|뭘|무엇을|무엇|무슨\\s*물건|어떤\\s*물건|뭔가)\\s*(?:을|를)?\\s*(?:뒀|두었|놔\\s*뒀|놨|놓았|넣어\\s*뒀|넣었|실었|있|두고\\s*내렸|두고\\s*왔|놓고\\s*왔|남겼)"),
        Regex("^$PLACE$AT\\s*(?:둔|두고\\s*온|남긴|놓고\\s*온|놔둔|있는)\\s*(?:물건|거|것|짐)"),
    )
    private val LIST_PICKUP = listOf(
        Regex("^$PLACE(?:에서)?\\s*(?:챙길|가져올|꺼낼|가지고\\s*올|가지고\\s*나올)\\s*(?:거|것|물건|게|건)"),
        Regex("^(?:내릴\\s*때|하차할\\s*때|하차\\s*후에?)\\s*(?:챙길|가져갈|가져올)\\s*(?:거|것|물건|게|건)"),
    )
    private val FIND = Regex(
        "^(?:내|제|우리)?\\s*(.+?)\\s*(?:은|는|이|가)?\\s*" +
            "(?:어디\\s*(?:에\\s*)?(?:있|뒀|두었|놨|놓았|갔|둔|놓|넣)|어딨|어디야|어디더라|어디지)\\S*$",
    )
    private val RETRIEVED = Regex(
        "^(?:($PLACE)에서\\s+)?(.+?)\\s*(?:을|를|도)?\\s*(?:($PLACE)에서\\s*)?(?:가져왔|챙겼|꺼냈|가지고\\s*왔|가지고\\s*나왔|찾아왔)(?:어요|어|다|음|습니다)?$",
    )
    private val PLACE_ONLY = Regex("^$PLACE$")

    /** 물건 이름으로 쓰지 않는 말 */
    private val NOT_NAMES = setOf("차", "차량", "자동차", "내 차", "내차", "주차", "주차 위치", "뭐", "뭘", "무엇", "거", "것", "그거", "이거", "저거", "나", "너", "여기", "거기")

    fun parse(text: String, wakeWord: String? = null): CarItemCommand? {
        val s = DriveCommands.sentence(text, wakeWord)
        if (s.isEmpty()) return null
        if (LIST_PICKUP.any { it.containsMatchIn(s) }) return CarItemCommand.ListPickup
        if (LIST_ALL.any { it.containsMatchIn(s) }) return CarItemCommand.ListAll

        PICKUP_PLACE_FIRST.find(s)?.let { m -> leave(m.groupValues[2], m.groupValues[1], pickup = true)?.let { return it } }
        PICKUP_ITEM_FIRST.find(s)?.let { m -> leave(m.groupValues[1], m.groupValues[2], pickup = true)?.let { return it } }
        LEAVE_PLACE_FIRST.find(s)?.let { m ->
            leave(m.groupValues[2], m.groupValues[1], pickup = REMIND_TAIL.containsMatchIn(m.groupValues[3]))?.let { return it }
        }
        LEAVE_ITEM_FIRST.find(s)?.let { m ->
            leave(m.groupValues[1], m.groupValues[2], pickup = REMIND_TAIL.containsMatchIn(m.groupValues[3]))?.let { return it }
        }
        RETRIEVED.find(s)?.let { m ->
            val name = clean(m.groupValues[2]) ?: return@let
            return CarItemCommand.Retrieved(name, saidCar = m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty())
        }
        FIND.find(s)?.let { m ->
            val name = clean(m.groupValues[1]) ?: return@let
            return CarItemCommand.Find(name)
        }
        return null
    }

    private fun leave(rawName: String, place: String, pickup: Boolean): CarItemCommand.Leave? {
        val name = clean(rawName) ?: return null
        return CarItemCommand.Leave(name, spotOf(place), pickup)
    }

    /** 물건 이름 다듬기: 앞의 '내', 뒤의 조사를 뗀다. 이름이 아니거나 너무 길면 null. */
    private fun clean(raw: String): String? {
        var name = raw.trim()
        for (prefix in listOf("내 ", "제 ", "우리 ")) name = name.removePrefix(prefix)
        name = name.removeSuffix(" 좀").trim()
        // 목적격 조사만 뗀다 ('고양이', '포도'처럼 이·가·도로 끝나는 이름을 자르지 않게)
        if (name.length > 1 && (name.endsWith("을") || name.endsWith("를"))) name = name.dropLast(1).trim()
        if (name.isEmpty() || name.length > MAX_NAME || name.split(' ').size > MAX_WORDS) return null
        if (name in NOT_NAMES || PLACE_ONLY.matches(name)) return null
        if (listOf("어디", "뭐", "뭘", "무엇").any { name.contains(it) }) return null
        return name
    }

    private fun spotOf(place: String): CarSpot? {
        val p = place.replace(" ", "")
        return when {
            "트렁크" in p -> CarSpot.TRUNK
            "뒷" in p -> CarSpot.BACK_SEAT
            "조수석" in p || "앞" in p || "운전석" in p -> CarSpot.FRONT_SEAT
            "글로브" in p || "글러브" in p -> CarSpot.GLOVE_BOX
            "콘솔" in p || "컵" in p -> CarSpot.CONSOLE
            else -> null
        }
    }

    /** 기록된 물건 이름과 말한 이름이 같은 물건인지 (띄어쓰기 무시, 한쪽이 다른 쪽을 포함: "택배" ↔ "택배 상자") */
    fun matches(itemName: String, query: String): Boolean {
        val a = itemName.replace(" ", "").lowercase()
        val b = query.replace(" ", "").lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** 하차 후 할 일 제목: "차에서 노트북 가져오기", "트렁크에서 운동화 가져오기" */
    fun pickupTitle(name: String, spot: CarSpot?): String = "${spot?.label ?: "차"}에서 $name 가져오기"

    /** 물건이 있는 자리 말: "트렁크", 없으면 "차" */
    fun placeLabel(spot: CarSpot?): String = spot?.label ?: "차"

    private const val MAX_NAME = 20
    private const val MAX_WORDS = 4
}

/** 물건 기록과 주차 기록 연결 규칙 */
object CarItemLink {

    /**
     * 물건을 등록할 때 연결할 주차 기록 id. 운전 중이면 지금 저장된 주차 위치는 출발 전의 것이므로 연결하지 않고
     * (null → 다음 주차가 저장될 때 연결), 아니면 마지막 주차 위치에 연결한다. 주차 기록이 없으면 null.
     */
    fun onRegister(driving: Boolean, latestParkingId: String?): String? = if (driving) null else latestParkingId

    /** 새 주차가 저장될 때 연결할지: 아직 주차와 연결되지 않았고, 차에 있다고 기록되어 있고, 그 주차보다 먼저 등록된 물건 */
    fun linkOnPark(linkedParkingId: String?, inCar: Boolean, createdAt: Long, parkedAt: Long): Boolean =
        linkedParkingId == null && inCar && createdAt <= parkedAt
}

/** 받침에 맞는 조사 */
object Josa {
    private fun hasFinal(word: String): Boolean {
        val last = word.trim().lastOrNull() ?: return false
        if (last !in '가'..'힣') return last.isDigit() && last in "013678"
        return (last - '가') % 28 != 0
    }

    fun eulReul(word: String) = word + if (hasFinal(word)) "을" else "를"
    fun eunNeun(word: String) = word + if (hasFinal(word)) "은" else "는"
    fun iGa(word: String) = word + if (hasFinal(word)) "이" else "가"
}
