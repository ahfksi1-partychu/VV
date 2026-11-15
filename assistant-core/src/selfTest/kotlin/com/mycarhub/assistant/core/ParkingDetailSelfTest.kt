package com.mycarhub.assistant.core

/** 주차 상세 기록 규칙 자체 검사: 메모 정리, 같은 주차 병합, 안내 중복 방지, 미리보기 검사. */
fun parkingDetailChecks(check: (String, Any?, Any?) -> Unit) {
    // 메모 정리
    check("메모: 앞뒤 공백", ParkingNote.normalize("  B구역 13번 "), "B구역 13번")
    check("메모: 줄바꿈·탭", ParkingNote.normalize("지하 3층\nA구역\t"), "지하 3층 A구역")
    check("메모: 연속 공백", ParkingNote.normalize("B구역    13번"), "B구역 13번")
    check("메모: 빈 글", ParkingNote.normalize("   "), null)
    check("메모: null", ParkingNote.normalize(null), null)
    check("메모: 길이 제한", ParkingNote.normalize("가".repeat(60))?.length, ParkingNote.MAX_LENGTH)
    check("메모: 제어 문자 제거", ParkingNote.normalize("B\u0000구역"), "B 구역")

    val car = ParkingMerge.Spot("car1", 1_000_000L, ParkingMerge.Source.CAR_SIGNAL)
    val phone = ParkingMerge.Spot("ph1", 1_000_000L + 60_000L, ParkingMerge.Source.PHONE_BLUETOOTH)
    val phoneLater = ParkingMerge.Spot("ph2", 1_000_000L + ParkingMerge.SAME_PARKING_MS + 1, ParkingMerge.Source.PHONE_BLUETOOTH)
    val manual = ParkingMerge.Spot("m1", 1_000_000L + 60_000L, ParkingMerge.Source.MANUAL)

    // 같은 주차 판단
    check("같은 주차: 차량+폰 1분 차이", ParkingCombine.sameParking(car, phone), true)
    check("같은 주차: 순서 무관", ParkingCombine.sameParking(phone, car), true)
    check("같은 주차: 10분 넘으면 다른 주차", ParkingCombine.sameParking(car, phoneLater), false)
    check("같은 주차: 직접 저장은 별개", ParkingCombine.sameParking(car, manual), false)
    check("같은 주차: 같은 id는 병합 대상 아님", ParkingCombine.sameParking(car, car), false)
    check(
        "같은 주차: 폰 두 번은 별개",
        ParkingCombine.sameParking(phone, phone.copy(id = "ph3")),
        false,
    )

    fun facts(s: ParkingMerge.Spot, loc: Boolean, detail: Boolean, at: Long) = ParkingCombine.Facts(s, loc, detail, at)

    // 차량 신호 기록이 이기는데 위치가 없고(지하), 폰 기록에 위치·메모가 있음 → 둘 다 가져옴
    val p1 = ParkingCombine.plan(facts(car, loc = false, detail = false, at = 0), facts(phone, loc = true, detail = true, at = 5))
    check("병합: 위치 없는 차량 기록에 폰 위치", p1.location, true)
    check("병합: 폰에서 입력한 메모를 차량 기록으로", p1.detail, true)
    // 차량 기록에 위치가 있으면 위치는 그대로
    val p2 = ParkingCombine.plan(facts(car, loc = true, detail = false, at = 0), facts(phone, loc = true, detail = true, at = 5))
    check("병합: 차량 위치 유지", p2.location, false)
    check("병합: 메모는 가져옴", p2.detail, true)
    // 고른 기록의 메모가 더 최근이면 유지
    val p3 = ParkingCombine.plan(facts(car, loc = true, detail = true, at = 9), facts(phone, loc = true, detail = true, at = 5))
    check("병합: 더 최근 메모 유지", p3.any, false)
    // 다른 쪽에서 더 나중에 메모·사진을 지웠으면 지운 상태를 가져옴
    val p4 = ParkingCombine.plan(facts(car, loc = true, detail = true, at = 5), facts(phone, loc = true, detail = false, at = 9))
    check("병합: 나중에 지운 것도 반영", p4.detail, true)
    // 양쪽 다 메모 없음
    val p5 = ParkingCombine.plan(facts(car, loc = true, detail = false, at = 0), facts(phone, loc = true, detail = false, at = 0))
    check("병합: 가져올 것 없음", p5.any, false)
    // 다른 주차면 아무것도 안 가져옴
    val p6 = ParkingCombine.plan(facts(car, loc = false, detail = false, at = 0), facts(phoneLater, loc = true, detail = true, at = 5))
    check("병합: 다른 주차는 섞지 않음", p6.any, false)

    // 안내 중복 방지
    val now = 1_000_000L + 2 * 60_000L
    check("안내: 차량 신호 주차", ParkingPromptRules.shouldPrompt(car, hasDetail = false, now = now, shown = emptyList()), true)
    check("안내: 블루투스 주차", ParkingPromptRules.shouldPrompt(phone, false, now, emptyList()), true)
    check("안내: 직접 저장은 묻지 않음", ParkingPromptRules.shouldPrompt(manual, false, now, emptyList()), false)
    check("안내: 이미 메모 있음", ParkingPromptRules.shouldPrompt(car, true, now, emptyList()), false)
    val shown = ParkingPromptRules.remember(emptyList(), phone)
    check("안내: 같은 기록 두 번 안 물음", ParkingPromptRules.shouldPrompt(phone, false, now, shown), false)
    check("안내: 같은 주차의 차량 기록이 나중에 동기화돼도 안 물음", ParkingPromptRules.shouldPrompt(car, false, now, shown), false)
    check(
        "안내: 다음 주차는 물음",
        ParkingPromptRules.shouldPrompt(phoneLater.copy(savedAt = now + 60 * 60_000L), false, now + 61 * 60_000L, shown),
        true,
    )
    check(
        "안내: 6시간 지난 기록은 안 물음",
        ParkingPromptRules.shouldPrompt(car, false, car.savedAt + ParkingPromptRules.MAX_AGE_MS + 1, emptyList()),
        false,
    )
    check("안내: 미래 시각 기록은 안 물음", ParkingPromptRules.shouldPrompt(car, false, car.savedAt - 10 * 60_000L, emptyList()), false)
    var history = emptyList<ParkingPromptRules.Shown>()
    repeat(30) { i -> history = ParkingPromptRules.remember(history, ParkingMerge.Spot("s$i", i * 3_600_000L, ParkingMerge.Source.CAR_SIGNAL)) }
    check("안내 기록: 최근 20개만", history.size, ParkingPromptRules.HISTORY)
    check("안내 기록: 최신 유지", history.last().id, "s29")
    check("안내 기록: 같은 id 중복 없음", ParkingPromptRules.remember(shown, phone).size, 1)

    // 미리보기·파일 이름
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    check("미리보기: JPEG", ParkingPreview.isAcceptable(jpeg), true)
    check("미리보기: JPEG 아님", ParkingPreview.isAcceptable(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0)), false)
    check("미리보기: 너무 큼", ParkingPreview.isAcceptable(jpeg + ByteArray(ParkingPreview.MAX_BYTES)), false)
    check("파일 이름: 정상", ParkingPreview.isSafeFileName("3f2a-9c.jpg"), true)
    check("파일 이름: 경로 조작", ParkingPreview.isSafeFileName("../drive_sync.jpg"), false)
    check("파일 이름: 하위 폴더", ParkingPreview.isSafeFileName("a/b.jpg"), false)
    check("파일 이름: 다른 확장자", ParkingPreview.isSafeFileName("a.png"), false)
    check("파일 이름: null", ParkingPreview.isSafeFileName(null), false)

    // 미리보기 크기가 서버 레코드 한도 안에 들어가는지 (메모 40자·주소 120자·기타 필드 포함, 암호화 후 Base64)
    val b64 = java.util.Base64.getEncoder()
    val worstPlain = "{\"id\":\"${"x".repeat(36)}\",\"lat\":37.123456789,\"lng\":127.123456789,\"acc\":12.5,\"at\":1731650000000," +
        "\"upd\":1731650000000,\"source\":\"PHONE_BLUETOOTH\",\"address\":\"${"가".repeat(120)}\",\"note\":\"${"나".repeat(ParkingNote.MAX_LENGTH)}\"," +
        "\"dat\":1731650000000,\"thumb\":\"${b64.encodeToString(ByteArray(ParkingPreview.MAX_BYTES))}\"}"
    val cipherChars = b64.encodeToString(ByteArray(worstPlain.toByteArray(Charsets.UTF_8).size + 16)).length
    check("미리보기: 최대 크기도 서버 16384자 한도 안", cipherChars <= 16_384, true)
}

/** 음성 안내 음량 규칙 자체 검사 */
fun volumeRuleChecks(check: (String, Any?, Any?) -> Unit) {
    check("음량: 0이면 작음", VolumeRule.isLow(0, 0, 15, muted = false), true)
    check("음량: 음소거면 작음", VolumeRule.isLow(10, 0, 15, muted = true), true)
    check("음량: 15단계 중 2 → 작음", VolumeRule.isLow(2, 0, 15, muted = false), true)
    check("음량: 15단계 중 3 → 보통", VolumeRule.isLow(3, 0, 15, muted = false), false)
    check("음량: 최대 → 보통", VolumeRule.isLow(15, 0, 15, muted = false), false)
    check("음량: 최소 단계가 1인 기기에서 1 → 작음", VolumeRule.isLow(1, 1, 25, muted = false), true)
    check("음량: 블루투스 127단계 중 20 → 작음", VolumeRule.isLow(20, 0, 127, muted = false), true)
    check("음량: 블루투스 127단계 중 60 → 보통", VolumeRule.isLow(60, 0, 127, muted = false), false)
    check("음량: 단계 정보 이상(최대=최소)이면 0만 작음", VolumeRule.isLow(5, 5, 5, muted = false), false)
}
