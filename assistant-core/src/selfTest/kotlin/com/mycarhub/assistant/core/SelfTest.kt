package com.mycarhub.assistant.core

import kotlin.system.exitProcess

/** 명령 해석·앱 매칭 자체 검사. 실패가 하나라도 있으면 종료 코드 1. */
private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual != expected) {
        failures++
        println("FAIL  $name\n      expected: $expected\n      actual:   $actual")
    }
}

private fun open(target: String, hasVerb: Boolean = true) = VoiceCommand.OpenApp(target, hasVerb)

private fun parserChecks() {
    val cases = listOf(
        "카카오톡 열어줘" to open("카카오톡"),
        "카카오톡 열어 줘." to open("카카오톡"),
        "카카오톡을 열어 주세요" to open("카카오톡"),
        "카톡 좀 켜 줄래" to open("카톡"),
        "내비게이션 실행해 줘" to open("내비게이션"),
        "내비게이션 실행해줘" to open("내비게이션"),
        "내비 실행" to open("내비"),
        "네비게이션을 실행시켜 줘" to open("네비게이션"),
        "유튜브 앱 실행해 줘" to open("유튜브"),
        "유튜브앱 켜줘" to open("유튜브"),
        "음악 틀어 줘" to open("음악"),
        "지도 보여줘" to open("지도"),
        "멜론 켜" to open("멜론"),
        "저기 티맵 좀 띄워 줘" to open("티맵"),
        "설정 들어가 줘" to open("설정"),
        "YouTube Music 열어줘" to open("youtube music"),
        "카카오톡" to open("카카오톡", hasVerb = false),
        "유튜브 뮤직" to open("유튜브 뮤직", hasVerb = false),
        "붕붕아 카카오톡 열어줘" to open("카카오톡"),
        "취소" to VoiceCommand.Cancel,
        "됐어" to VoiceCommand.Cancel,
        "아니야" to VoiceCommand.Cancel,
        "엄마한테 카톡 보내줘" to VoiceCommand.Unsupported("엄마한테 카톡 보내줘"),
        "아빠에게 전화 걸어 줘" to VoiceCommand.Unsupported("아빠에게 전화 걸어 줘"),
        "문자 읽어 줘" to VoiceCommand.ReadMessage("문자"),
        "오늘 날씨가 정말 좋네 그치" to VoiceCommand.Unknown("오늘 날씨가 정말 좋네 그치"),
        "" to VoiceCommand.Unknown(""),
        "열어줘" to VoiceCommand.Unknown("열어줘"),
    )
    for ((text, expected) in cases) check("parse \"$text\"", CommandParser.parse(text, wakeWord = "붕붕아"), expected)
}

private fun app(label: String, pkg: String, alias: String? = null, registered: Boolean = false) =
    AppEntry("$pkg/.Main", label, alias, pkg, registered)

private fun matcherChecks() {
    val kakao = app("카카오톡", "com.kakao.talk")
    val kakaoEn = app("KakaoTalk", "com.kakao.talk")
    val youtube = app("YouTube", "com.google.android.youtube")
    val ytMusic = app("YouTube Music", "com.google.android.apps.youtube.music")
    val navi = app("Navi", "com.pleos.navi", registered = true)
    val naviAlias = app("Navi", "com.pleos.navi", alias = "내비", registered = true)
    val gallery = app("갤러리", "com.android.gallery3d", registered = true)
    val radio = app("Radio", "com.android.car.radio")
    val settings = app("Settings", "com.android.car.settings")
    val all = listOf(kakao, youtube, ytMusic, navi, gallery, radio, settings)

    fun single(app: AppEntry) = AppMatch.Single(app)
    check("카카오톡 exact", AppMatcher.match("카카오톡", all), single(kakao))
    check("카톡 synonym", AppMatcher.match("카톡", all), single(kakao))
    check("카톡 → English label via package", AppMatcher.match("카톡", listOf(kakaoEn, youtube)), single(kakaoEn))
    check("유튜브 → YouTube, not Music", AppMatcher.match("유튜브", all), single(youtube))
    check("유튜브 뮤직", AppMatcher.match("유튜브 뮤직", all), single(ytMusic))
    check("youtube music english", AppMatcher.match("youtube music", all), single(ytMusic))
    check("alias first", AppMatcher.match("내비", listOf(naviAlias, gallery)), single(naviAlias))
    check("갤러리", AppMatcher.match("갤러리", all), single(gallery))
    check("라디오 known package", AppMatcher.match("라디오", all), single(radio))
    check("설정", AppMatcher.match("설정", all), single(settings))
    check("unknown app", AppMatcher.match("넷플릭스", all), AppMatch.None)
    check("one letter no partial", AppMatcher.match("카", all), AppMatch.None)

    val other = app("카카오맵", "net.daum.android.map")
    check("카카오 ambiguous", AppMatcher.match("카카오", listOf(kakao, other)), AppMatch.Multiple(listOf(kakao, other)))
    check(
        "registered wins tie",
        AppMatcher.match("카카오", listOf(kakao, other.copy(registered = true))),
        single(other.copy(registered = true)),
    )

    check("category 내비게이션", AppCategory.forName("내비게이션"), AppCategory.MAPS)
    check("category 네비", AppCategory.forName("네비"), AppCategory.MAPS)
    check("category 음악", AppCategory.forName("음악"), AppCategory.MUSIC)
    check("category none", AppCategory.forName("카카오톡"), null)
    check("hints navi package", AppMatcher.matchByHints(AppCategory.MAPS, all), single(navi))
    check("hints none", AppMatcher.matchByHints(AppCategory.CALCULATOR, all), AppMatch.None)
}

private fun driveChecks() {
    // 2026-11-10 (화) 오후 2시 기준
    val now = java.time.LocalDateTime.of(2026, 11, 10, 14, 0)
    fun at(month: Int, day: Int, hour: Int = DriveCommands.DEFAULT_DUE_HOUR, minute: Int = 0) =
        java.time.LocalDateTime.of(2026, month, day, hour, minute)
    val home = PlaceCondition(PlaceCondition.Kind.HOME, "집")
    data class Expect(val title: String, val due: java.time.LocalDateTime? = null, val hasTime: Boolean = false, val place: PlaceCondition? = null)
    val todos = listOf(
        "집에 가서 택배 확인해야 해" to Expect("택배 확인하기", place = home),
        "붕붕아, 집에 가서 택배 확인해야 해" to Expect("택배 확인하기", place = home),
        "뿡뿡아 휴지 사야 해" to Expect("휴지 사기"),
        "내일 엄마한테 전화해야 해" to Expect("엄마한테 전화하기", at(11, 11)),
        "휴지 사야 해" to Expect("휴지 사기"),
        "마트에 가면 우유 사야 돼" to Expect("우유 사기", place = PlaceCondition(PlaceCondition.Kind.OTHER, "마트")),
        "회사에 도착하면 메일 확인해야 해" to Expect("메일 확인하기", place = PlaceCondition(PlaceCondition.Kind.WORK, "회사")),
        "주유소 들르라고 나중에 알려줘" to Expect("주유소 들르기"),
        "휴지 사기 할 일에 추가해" to Expect("휴지 사기"),
        "세탁소 할 일에 추가해 줘" to Expect("세탁소"),
        "내일 오후 3시에 병원 예약 확인해야 해" to Expect("병원 예약 확인하기", at(11, 11, 15), hasTime = true),
        "금요일까지 보고서 보내야 해" to Expect("보고서 보내기", at(11, 13)),
        "다음 주 월요일에 세차해야 해" to Expect("세차하기", at(11, 16)),
        "이번 주말에 장 봐야 해" to Expect("장 보기", at(11, 14)),
        "저녁 7시에 약 먹어야 해" to Expect("약 먹기", at(11, 10, 19), hasTime = true),
        "책 읽어야 해" to Expect("책 읽기"),
        "마트 들러야 해" to Expect("마트 들르기"),
        "아이 챙겨야 돼" to Expect("아이 챙기기"),
        "택배 가져와야 해" to Expect("택배 가져오기"),
        "엄마한테 답장해야 해" to Expect("엄마한테 답장하기"),
    )
    for ((text, e) in todos) {
        val d = DriveCommands.parseTodo(text, now, wakeWord = "뿡뿡이")
        check("todo \"$text\"", d?.let { Expect(it.title, it.due, it.hasTime, it.place) }, e)
    }
    // 호출어 '루니야' (앱 이름 루니): 앞에 붙은 호출어를 떼고 명령을 읽는다
    for ((text, e) in listOf(
        "루니야 휴지 사야 해" to Expect("휴지 사기"),
        "루니야, 집에 가서 택배 확인해야 해" to Expect("택배 확인하기", place = home),
        "루니아 휴지 사야 해" to Expect("휴지 사기"),
    )) {
        val d = DriveCommands.parseTodo(text, now, wakeWord = "루니야")
        check("todo 루니야 \"$text\"", d?.let { Expect(it.title, it.due, it.hasTime, it.place) }, e)
    }
    check("parse 루니야 open", CommandParser.parse("루니야 카카오톡 열어줘", wakeWord = "루니야"), VoiceCommand.OpenApp("카카오톡", hasVerb = true))
    check("parse 루니야 read", CommandParser.parse("루니야 카톡 읽어줘", wakeWord = "루니야"), VoiceCommand.ReadMessage("카톡"))
    check("parking 루니야", DriveCommands.isParkingQuery("루니야, 차 어디 세웠지?"), true)
    // 할 일이 아닌 명령은 가로채지 않는다
    for (text in listOf("카카오톡 열어줘", "동진님 메시지 읽어줘", "차 어디 세웠지", "곧 간다고 답장해줘", "당근 메시지 읽어줘", "할 일 뭐 있어")) {
        check("not todo \"$text\"", DriveCommands.parseTodo(text, now, "뿡뿡이"), null)
    }

    val parking = listOf(
        "붕붕아, 차 어디 세웠지?" to true, "차 어디 세웠지" to true, "내 차 어디 있어" to true, "차를 어디에 주차했지" to true,
        "마지막 주차 위치 알려줘" to true, "주차한 곳 알려 줘" to true, "어디 주차했더라" to true,
        "주차 위치 저장해 줘" to false, "여기 주차했어" to false, "카카오톡 열어줘" to false, "어디 가지" to false, "차 막히네" to false,
    )
    for ((text, expected) in parking) check("parking query \"$text\"", DriveCommands.isParkingQuery(text), expected)
    check("park here 여기 주차했어", DriveCommands.isParkHere("여기 주차했어"), true)
    check("park here 주차 위치 저장", DriveCommands.isParkHere("주차 위치 저장해 줘"), true)
    check("park here 아님", DriveCommands.isParkHere("차 어디 세웠지"), false)
    check("todo list 뭐 있어", DriveCommands.isTodoListQuery("할 일 뭐 있어"), true)
    check("todo list 알려줘", DriveCommands.isTodoListQuery("오늘 할 일 알려줘"), true)
    check("todo list 아님 (추가)", DriveCommands.isTodoListQuery("휴지 사기 할 일에 추가해"), false)

    val suggestions = listOf(
        Triple("CJ대한통운", "[CJ대한통운] 고객님의 상품이 배송완료 되었습니다. 문 앞 보관", false) to TodoSuggestion(SuggestionKind.DELIVERY, "택배 확인하기"),
        Triple("쿠팡", "(광고) 배송완료 기념 할인 쿠폰", false) to null,
        Triple("김과장", "내일 회의 일정이 변경되었습니다", false) to TodoSuggestion(SuggestionKind.SCHEDULE_CHANGE, "변경된 일정 확인하기"),
        Triple("동진님", "내일 몇 시에 와?", true) to TodoSuggestion(SuggestionKind.REPLY, "동진님에게 답장하기"),
        Triple("엄마", "도착하면 전화 줘", true) to TodoSuggestion(SuggestionKind.REPLY, "엄마에게 답장하기"),
        Triple("동진님", "ㅋㅋㅋ 알겠어", true) to null,
        Triple("1588-0000", "언제 오세요?", false) to null,
    )
    for ((input, expected) in suggestions) {
        val (sender, text, person) = input
        check("suggest \"$text\"", TodoSuggester.suggest(sender, text, isGroup = false, isPersonChat = person), expected)
    }
    check("suggest 단체방 질문은 아님", TodoSuggester.suggest("동진님", "언제 와?", isGroup = true, isPersonChat = true), null)
}

private fun parkingDeciderChecks() {
    val capture = ParkingDecider.Action.CAPTURE
    val commit = ParkingDecider.Action.COMMIT
    val cancel = ParkingDecider.Action.CANCEL
    val off = ParkingDecider.Ignition.OFF
    val min = 60_000L

    // 일반 주차: P → 시동 끔
    ParkingDecider().apply {
        check("주차: P → 위치 잡기", onGear(true, 0), listOf(capture))
        check("주차: 시동 끔 → 확정", onIgnition(off, 10_000), listOf(commit))
        check("주차: 다시 시동 끔 신호 → 중복 없음", onIgnition(ParkingDecider.Ignition.LOCK, 20_000), emptyList<ParkingDecider.Action>())
    }
    // 신호 대기: 기어 D 그대로 (속도 0이어도 신호를 넣지 않음) → 아무것도 하지 않음
    ParkingDecider().apply {
        check("신호 대기: D", onGear(false, 0), emptyList<ParkingDecider.Action>())
        check("신호 대기: 시간 흐름", onTick(10 * min), emptyList<ParkingDecider.Action>())
    }
    // 신호 대기 중 잠깐 P → 1분 뒤 D: 확정하지 않고 버린다
    ParkingDecider().apply {
        onGear(false, 0)
        check("잠깐 P: 위치 잡기", onGear(true, 0), listOf(capture))
        onParkingBrake(true, 0)
        check("잠깐 P: 1분은 확정 안 함", onTick(1 * min), emptyList<ParkingDecider.Action>())
        check("잠깐 P → D: 버림", onGear(false, 1 * min), listOf(cancel))
    }
    // 시동 신호 없는 차량: P + 주차 브레이크 3분 → 확정
    ParkingDecider().apply {
        onGear(true, 0)
        onParkingBrake(true, 0)
        check("시동 신호 없음: 2분 59초", onTick(3 * min - 1_000), emptyList<ParkingDecider.Action>())
        check("시동 신호 없음: 3분", onTick(3 * min), listOf(commit))
        check("확정 후 다시 확정하지 않음", onTick(10 * min), emptyList<ParkingDecider.Action>())
        check("다시 출발: 이미 확정된 것은 버리지 않음", onGear(false, 11 * min), emptyList<ParkingDecider.Action>())
        check("다음 주차: 다시 위치 잡기", onGear(true, 30 * min), listOf(capture))
    }
    // 주차 브레이크 해제 상태의 P: 3분으로는 확정하지 않음
    ParkingDecider().apply {
        onGear(true, 0)
        onParkingBrake(false, 0)
        check("브레이크 해제 P 3분", onTick(3 * min), emptyList<ParkingDecider.Action>())
        check("브레이크 해제 P 4분", onTick(4 * min), emptyList<ParkingDecider.Action>())
        check("브레이크 해제 P 5분 → 확정", onTick(5 * min), listOf(commit))
        check("브레이크 해제: 한 번만 확정", onTick(6 * min), emptyList<ParkingDecider.Action>())
    }
    // 시동이 켜진 채 P로 대기 (공회전): 시간으로 확정하지 않고 시동 끔에 확정
    ParkingDecider().apply {
        onIgnition(ParkingDecider.Ignition.ON, 0)
        onGear(true, 0)
        onParkingBrake(true, 0)
        check("시동 켜짐 P 10분: 확정 안 함", onTick(10 * min), emptyList<ParkingDecider.Action>())
        check("시동 켜짐 P → 시동 끔: 확정", onIgnition(off, 11 * min), listOf(commit))
    }
    // 주차 브레이크 신호도 없는 차량: P 5분 → 확정
    ParkingDecider().apply {
        onGear(true, 0)
        check("브레이크 신호 없음 4분", onTick(4 * min), emptyList<ParkingDecider.Action>())
        check("브레이크 신호 없음 5분", onTick(5 * min), listOf(commit))
    }
    // 기어 신호가 없는 차량: 시동 끔만으로 확정
    ParkingDecider().apply {
        check("기어 신호 없음: 시동 끔", onIgnition(off, 0), listOf(commit))
    }
    // 기어 D에서 시동 꺼짐 신호 (비정상): 확정하지 않음
    ParkingDecider().apply {
        onGear(false, 0)
        check("D에서 시동 끔", onIgnition(off, 0), emptyList<ParkingDecider.Action>())
    }
}

fun main() {
    parserChecks()
    matcherChecks()
    messageChecks()
    driveChecks()
    parkingDeciderChecks()
    syncChecks()
    carItemChecks()
    syncTransportChecks(::check)
    parkingDetailChecks(::check)
    volumeRuleChecks(::check)
    reminderChecks(::check)
    println("assistant-core self test: ${checks - failures}/$checks passed")
    if (failures > 0) exitProcess(1)
}

private fun messageChecks() {
    val read = listOf(
        "카카오톡에 가장 최근에 온 메시지 읽어줘" to VoiceCommand.ReadMessage("카카오톡"),
        "카톡 읽어줘" to VoiceCommand.ReadMessage("카톡"),
        "최근 메시지 읽어 줘" to VoiceCommand.ReadMessage(null),
        "새 메시지 확인해 줘" to VoiceCommand.ReadMessage(null),
        "메시지 읽어줘" to VoiceCommand.ReadMessage(null),
        "읽어줘" to VoiceCommand.ReadMessage(null),
        "문자 온 거 있어?" to VoiceCommand.ReadMessage("문자"),
        "카카오톡메시지 읽어줘" to VoiceCommand.ReadMessage("카카오톡"),
        "붕붕아 카톡 읽어줘" to VoiceCommand.ReadMessage("카톡"),
        "30분 뒤에 도착한다고 답장해 줘" to VoiceCommand.Reply("30분 뒤에 도착한다"),
        "30분 뒤에 도착한다고 답장해줘." to VoiceCommand.Reply("30분 뒤에 도착한다"),
        "알았어라고 보내 줘" to VoiceCommand.Reply("알았어"),
        "회의 중이라고 답장 보내줘" to VoiceCommand.Reply("회의 중"),
        "내일 보자고 회신해 줘" to VoiceCommand.Reply("내일 보자"),
        "곧 갈게 라고 답장해" to VoiceCommand.Reply("곧 갈게"),
        "답장해 줘" to VoiceCommand.Reply(null),
        "답장 보내줘 OK 확인했어" to VoiceCommand.Reply("OK 확인했어"),
        "다시 읽어줘" to VoiceCommand.Repeat,
        "한 번 더" to VoiceCommand.Repeat,
        "엄마한테 카톡 보내줘" to VoiceCommand.Unsupported("엄마한테 카톡 보내줘"),
        // 기존 앱 실행은 그대로
        "카카오톡 열어줘" to open("카카오톡"),
        "카카오톡" to open("카카오톡", hasVerb = false),
    )
    for ((text, expected) in read) check("parse \"$text\"", CommandParser.parse(text, wakeWord = "붕붕아"), expected)

    val confirmations = listOf(
        "응" to Confirmation.YES, "네 보내 주세요" to Confirmation.YES, "보내" to Confirmation.YES,
        "그래" to Confirmation.YES, "전송해" to Confirmation.YES, "오케이" to Confirmation.YES,
        "아니" to Confirmation.NO, "보내지 마" to Confirmation.NO, "취소" to Confirmation.NO,
        "아니 보내지 마" to Confirmation.NO, "잠깐만" to Confirmation.NO,
        "음 글쎄" to Confirmation.UNKNOWN, "" to Confirmation.UNKNOWN, "날씨 좋다" to Confirmation.UNKNOWN,
    )
    for ((text, expected) in confirmations) check("confirm \"$text\"", CommandParser.parseConfirmation(text), expected)

    check("body plain", CommandParser.parseReplyBody("곧 도착해"), "곧 도착해")
    check("body quoted", CommandParser.parseReplyBody("곧 도착한다고 보내줘"), "곧 도착한다")
    check("body cancel", CommandParser.parseReplyBody("취소"), null)
    check("body empty", CommandParser.parseReplyBody(""), null)

    fun msg(pkg: String, label: String, time: Long, sender: String = "홍길동") =
        IncomingMessage("k$time", pkg, label, sender, "본문$time", null, time, canReply = true)
    val kakao1 = msg("com.kakao.talk", "카카오톡", 100)
    val kakao2 = msg("com.kakao.talk", "카카오톡", 300)
    val sms = msg("com.samsung.android.messaging", "메시지", 200)
    val all = listOf(kakao1, sms, kakao2)
    check("latest any", MessageSelector.latest(all, null), MessageSelection.Found(kakao2))
    check("latest kakao", MessageSelector.latest(all, "카카오톡"), MessageSelection.Found(kakao2))
    check("latest 카톡", MessageSelector.latest(all, "카톡"), MessageSelection.Found(kakao2))
    check("latest 문자", MessageSelector.latest(all, "문자"), MessageSelection.Found(sms))
    check("latest none for app", MessageSelector.latest(listOf(sms), "카카오톡"), MessageSelection.NoneForApp("카카오톡"))
    check("latest empty", MessageSelector.latest(emptyList(), null), MessageSelection.None)
}

private fun syncChecks() {
    val a = SyncMeta("t1", 1_000, deleted = false, origin = "car")
    check("동기화: 처음 받음", SyncMerge.remoteWins(null, a), true)
    check("동기화: 더 나중 변경이 이김", SyncMerge.remoteWins(a, a.copy(updatedAt = 2_000, origin = "phone")), true)
    check("동기화: 옛 변경은 무시", SyncMerge.remoteWins(a, a.copy(updatedAt = 500, origin = "phone")), false)
    check("동기화: 같은 레코드 다시 받음(중복)", SyncMerge.remoteWins(a, a), false)
    check("동기화: 같은 시각이면 삭제 우선", SyncMerge.remoteWins(a, a.copy(deleted = true, origin = "a")), true)
    check("동기화: 같은 시각 삭제는 되살리지 않음", SyncMerge.remoteWins(a.copy(deleted = true), a.copy(origin = "zzz")), false)
    val x = a.copy(origin = "phone")
    check("동기화: 같은 시각 충돌은 양쪽이 같은 결론", SyncMerge.remoteWins(a, x) != SyncMerge.remoteWins(x, a), true)
    check("동기화: 삭제 후 더 나중 수정은 적용", SyncMerge.remoteWins(a.copy(deleted = true), a.copy(updatedAt = 3_000)), true)

    val min = 60_000L
    val car = ParkingMerge.Spot("c", 10 * min, ParkingMerge.Source.CAR_SIGNAL)
    val phone = ParkingMerge.Spot("p", 12 * min, ParkingMerge.Source.PHONE_BLUETOOTH)
    check("주차 병합: 처음", ParkingMerge.remoteWins(null, car), true)
    check("주차 병합: 같은 주차면 차량 신호 우선 (폰이 나중이어도)", ParkingMerge.remoteWins(car, phone), false)
    check("주차 병합: 같은 주차면 차량 신호 우선 (받는 쪽)", ParkingMerge.remoteWins(phone, car), true)
    check("주차 병합: 같은 레코드는 다시 적용 안 함", ParkingMerge.remoteWins(car, car), false)
    val later = phone.copy(id = "p2", savedAt = 60 * min)
    check("주차 병합: 다른 주차(50분 뒤)는 최신 우선", ParkingMerge.remoteWins(car, later), true)
    val manual = ParkingMerge.Spot("m", 11 * min, ParkingMerge.Source.MANUAL)
    check("주차 병합: 직접 저장이 더 나중이면 이김", ParkingMerge.remoteWins(car, manual), true)
    check("주차 병합: 직접 저장보다 옛 차량 신호는 짐", ParkingMerge.remoteWins(manual, car), false)

    val outbox = SyncOutbox()
    outbox.mark("todo:1", 100)
    outbox.mark("todo:1", 200)
    outbox.mark("todo:1", 150)
    outbox.mark("parking:9", 50)
    check("보낼 목록: 같은 레코드는 하나, 최신 시각", outbox.pending(), listOf("parking:9" to 50L, "todo:1" to 200L))
    outbox.ack("todo:1", 100)
    check("보낼 목록: 보내는 사이 또 바뀌면 남김", outbox.toMap()["todo:1"], 200L)
    outbox.ack("todo:1", 200)
    outbox.ack("parking:9", 50)
    check("보낼 목록: 확인되면 지움", outbox.isEmpty(), true)
    outbox.ack("todo:404", 1)
    check("보낼 목록: 모르는 확인은 무시", outbox.isEmpty(), true)

    check("재시도: 첫 번째 30초", SyncBackoff.delayMs(0), 30_000L)
    check("재시도: 두 번째 60초", SyncBackoff.delayMs(1), 60_000L)
    check("재시도: 최대 30분", SyncBackoff.delayMs(20, jitter = 1.0), 30 * 60_000L)

    // 페어링: 양쪽이 같은 키·확인 번호를 얻는다
    val carKeys = SyncCrypto.newKeyPair()
    val phoneKeys = SyncCrypto.newKeyPair()
    val carPub = SyncCrypto.publicKey(carKeys)
    val phonePub = SyncCrypto.publicKey(phoneKeys)
    val onCar = SyncCrypto.derive(carKeys.private, phonePub, "482913", carPub, phonePub)
    val onPhone = SyncCrypto.derive(phoneKeys.private, carPub, "482913", carPub, phonePub)
    check("페어링: 같은 데이터 키", onCar.dataKey.contentEquals(onPhone.dataKey), true)
    check("페어링: 같은 확인 번호", onCar.verifyCode, onPhone.verifyCode)
    check("페어링: 확인 번호 6자리", onCar.verifyCode.length == 6 && onCar.verifyCode.all(Char::isDigit), true)
    check("페어링: 데이터 키 256비트", onCar.dataKey.size, 32)
    val wrongCode = SyncCrypto.derive(phoneKeys.private, carPub, "000000", carPub, phonePub)
    check("페어링: 코드가 다르면 다른 키", onCar.dataKey.contentEquals(wrongCode.dataKey), false)
    val mitm = SyncCrypto.newKeyPair()
    val swapped = SyncCrypto.derive(phoneKeys.private, SyncCrypto.publicKey(mitm), "482913", SyncCrypto.publicKey(mitm), phonePub)
    check("페어링: 공개키 바꿔치기면 확인 번호 다름", swapped.verifyCode != onCar.verifyCode, true)

    // 암호화: 메타데이터까지 묶여 있다
    val key = onCar.dataKey
    val meta = SyncMeta("t1", 1_000, deleted = false, origin = "car")
    val plain = "{\"title\":\"택배 확인\"}".toByteArray()
    val sealed = SyncCrypto.seal(key, meta, "todo", plain)
    check("암호화: 서버에는 평문이 없음", String(java.util.Base64.getDecoder().decode(sealed.cipherText), Charsets.ISO_8859_1).contains("title"), false)
    check("암호화: 복호화", SyncCrypto.open(onPhone.dataKey, meta, "todo", sealed)?.let { String(it) }, String(plain))
    check("암호화: 다른 키는 실패", SyncCrypto.open(wrongCode.dataKey, meta, "todo", sealed), null)
    check("암호화: 수정 시각 조작은 실패", SyncCrypto.open(key, meta.copy(updatedAt = 9_999), "todo", sealed), null)
    check("암호화: 삭제 표시 조작은 실패", SyncCrypto.open(key, meta.copy(deleted = true), "todo", sealed), null)
    check("암호화: 종류 조작은 실패", SyncCrypto.open(key, meta, "parking", sealed), null)
    check("암호화: 매번 다른 nonce", SyncCrypto.seal(key, meta, "todo", plain).nonce != sealed.nonce, true)
    check("암호화: 깨진 base64는 실패", SyncCrypto.open(key, meta, "todo", sealed.copy(cipherText = "!!")), null)
}

private fun carItemChecks() {
    fun leave(name: String, spot: CarSpot?, pickup: Boolean) = CarItemCommand.Leave(name, spot, pickup)
    val cases = listOf(
        // 요청 예문
        "붕붕아, 차에 택배 상자 두고 내렸어" to leave("택배 상자", null, false),
        "붕붕아, 트렁크에 운동화 놔뒀어" to leave("운동화", CarSpot.TRUNK, false),
        "붕붕아, 차에 우산 두고 왔어" to leave("우산", null, false),
        "붕붕아, 차에서 노트북 챙겨야 해" to leave("노트북", null, true),
        "붕붕아, 차에 뭐 뒀지?" to CarItemCommand.ListAll,
        "붕붕아, 내 우산 어디 있어?" to CarItemCommand.Find("우산"),
        "붕붕아, 차에서 챙길 거 있어?" to CarItemCommand.ListPickup,
        // 변형
        "뒷좌석에 가방 두고 내렸어" to leave("가방", CarSpot.BACK_SEAT, false),
        "차 트렁크에 캠핑 의자 실어 뒀어" to leave("캠핑 의자", CarSpot.TRUNK, false),
        "조수석에 서류 봉투 놓고 내렸어" to leave("서류 봉투", CarSpot.FRONT_SEAT, false),
        "택배 상자 차에 두고 내렸어" to leave("택배 상자", null, false),
        "차에 우산을 두고 왔어" to leave("우산", null, false),
        "차에 우산 두고 왔어 잊지 않게 알려줘" to leave("우산", null, true),
        "트렁크에 운동화 넣어 놨어 내릴 때 알려줘" to leave("운동화", CarSpot.TRUNK, true),
        "트렁크에서 장본 거 꺼내야 해" to leave("장본 거", CarSpot.TRUNK, true),
        "차에서 노트북 챙기라고 알려줘" to leave("노트북", null, true),
        "노트북 차에서 가져와야 돼" to leave("노트북", null, true),
        "차에 있는 충전기 챙겨야 해" to leave("충전기", null, true),
        "뿡뿡아 차에 우산 두고 왔어" to leave("우산", null, false),
        "트렁크에 뭐 있어" to CarItemCommand.ListAll,
        "차에 둔 물건 알려줘" to CarItemCommand.ListAll,
        "내릴 때 챙길 거 있어?" to CarItemCommand.ListPickup,
        "우산 어딨어" to CarItemCommand.Find("우산"),
        "택배 상자는 어디 뒀지" to CarItemCommand.Find("택배 상자"),
        "노트북 가져왔어" to CarItemCommand.Retrieved("노트북", saidCar = false),
        "차에서 우산 챙겼어" to CarItemCommand.Retrieved("우산", saidCar = true),
        // 다른 명령을 가로채지 않음
        "카카오톡 열어줘" to null,
        "집에 가서 택배 확인해야 해" to null,
        "내일 엄마한테 전화해야 해" to null,
        "할 일 뭐 있어" to null,
        "동진님 메시지 읽어줘" to null,
        "차에 뭐가 문제야" to null,
        "주차 위치 알려줘" to null,
    )
    for ((text, expected) in cases) check("차 물건 \"$text\"", CarItems.parse(text, wakeWord = "뿡뿡이"), expected)
    // 주차 위치 질문은 그대로 (DriveAssistant가 물건보다 먼저 본다)
    check("차 물건: 내 차 어디 있어 → 주차 질문", DriveCommands.isParkingQuery("내 차 어디 있어"), true)
    check("차 물건: 내 차 어디 있어 → 물건 아님", CarItems.parse("내 차 어디 있어"), null)
    check("차 물건: 우산 어디 있어 → 주차 질문 아님", DriveCommands.isParkingQuery("내 우산 어디 있어"), false)
    check("차 물건: 트렁크에 운동화 → 주차 질문 아님", DriveCommands.isParkingQuery("트렁크에 운동화 놔뒀어"), false)

    check("이름 맞춤: 택배 ↔ 택배 상자", CarItems.matches("택배 상자", "택배"), true)
    check("이름 맞춤: 띄어쓰기", CarItems.matches("택배상자", "택배 상자"), true)
    check("이름 맞춤: 다른 물건", CarItems.matches("우산", "노트북"), false)
    check("챙길 일 제목", CarItems.pickupTitle("노트북", null), "차에서 노트북 가져오기")
    check("챙길 일 제목 트렁크", CarItems.pickupTitle("운동화", CarSpot.TRUNK), "트렁크에서 운동화 가져오기")
    check("조사 을", Josa.eulReul("우산"), "우산을")
    check("조사 를", Josa.eulReul("노트북 가방"), "노트북 가방을")
    check("조사 를2", Josa.eulReul("의자"), "의자를")
    check("조사 는", Josa.eunNeun("우산"), "우산은")

    check("연결: 주차 중 등록 → 마지막 주차", CarItemLink.onRegister(driving = false, latestParkingId = "p1"), "p1")
    check("연결: 운전 중 등록 → 다음 주차", CarItemLink.onRegister(driving = true, latestParkingId = "p1"), null)
    check("연결: 주차 기록 없음", CarItemLink.onRegister(driving = false, latestParkingId = null), null)
    check("연결: 다음 주차에 연결", CarItemLink.linkOnPark(null, inCar = true, createdAt = 100, parkedAt = 200), true)
    check("연결: 이미 연결된 물건은 그대로", CarItemLink.linkOnPark("p1", inCar = true, createdAt = 100, parkedAt = 200), false)
    check("연결: 가져온 물건은 연결 안 함", CarItemLink.linkOnPark(null, inCar = false, createdAt = 100, parkedAt = 200), false)
    check("연결: 주차 뒤에 등록된 물건은 연결 안 함", CarItemLink.linkOnPark(null, inCar = true, createdAt = 300, parkedAt = 200), false)
}
