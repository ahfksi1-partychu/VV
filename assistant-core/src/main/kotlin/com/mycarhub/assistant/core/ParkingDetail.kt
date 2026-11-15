package com.mycarhub.assistant.core

import kotlin.math.abs

/**
 * 주차 위치 상세 기록 (구역·번호 메모, 사진)의 순수 규칙. 앱의 ParkingStore·기록 화면·알림이 쓴다. PC selfTest로 검증한다.
 *
 * - 메모는 사용자가 직접 입력한 짧은 글 ("B구역 13번", "지하 3층 A구역").
 * - 같은 주차를 차량(기어 P·시동 끔)과 휴대폰(블루투스 끊김)이 따로 저장하면 [ParkingMerge]가 하나를 고르는데,
 *   고르지 않은 쪽에만 있는 위치·메모·사진이 사라지지 않도록 [ParkingCombine]이 채워 넣는다.
 * - 같은 주차 이벤트에서 '기록하시겠습니까?' 안내를 두 번 띄우지 않는다 ([ParkingPromptRules]).
 */
object ParkingNote {

    const val MAX_LENGTH = 40

    /** 앞뒤 공백·줄바꿈·제어 문자를 정리하고 길이를 제한한다. 비면 null. */
    fun normalize(text: String?): String? {
        if (text == null) return null
        val cleaned = buildString {
            for (ch in text) append(if (ch.isISOControl() || ch.isWhitespace()) ' ' else ch)
        }.replace(Regex(" {2,}"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= MAX_LENGTH) cleaned else cleaned.take(MAX_LENGTH).trimEnd()
    }
}

object ParkingCombine {

    /** 병합에 필요한 주차 기록 요약. [detailAt]은 메모·사진을 마지막으로 바꾼 시각 (없으면 0). */
    data class Facts(
        val spot: ParkingMerge.Spot,
        val hasLocation: Boolean,
        val hasDetail: Boolean,
        val detailAt: Long,
    )

    /** 무엇을 다른 기록에서 가져올지 */
    data class Take(val location: Boolean, val detail: Boolean) {
        val any: Boolean get() = location || detail
    }

    private val NONE = Take(location = false, detail = false)

    /**
     * 같은 주차를 두 기기가 따로 저장한 것인지: 하나는 차량 신호, 하나는 휴대폰 블루투스이고 [ParkingMerge.SAME_PARKING_MS] 안.
     * (차량 신호를 우선하는 [ParkingMerge]의 규칙과 같은 기준)
     */
    fun sameParking(a: ParkingMerge.Spot, b: ParkingMerge.Spot): Boolean {
        if (a.id == b.id) return false
        if (abs(a.savedAt - b.savedAt) > ParkingMerge.SAME_PARKING_MS) return false
        val sources = setOf(a.source, b.source)
        return sources == setOf(ParkingMerge.Source.CAR_SIGNAL, ParkingMerge.Source.PHONE_BLUETOOTH)
    }

    /**
     * [kept](병합에서 고른 기록)에 [other]의 무엇을 채울지.
     * - 위치: [kept]에 위치가 없고 [other]에 있으면 (예: 차량은 지하라 위치를 못 얻고 휴대폰은 입구에서 얻음).
     * - 메모·사진: [other] 쪽을 더 나중에 고쳤으면 (메모·사진은 한 묶음으로, 나중에 고친 쪽이 이김).
     * 같은 주차가 아니면 아무것도 가져오지 않는다.
     */
    fun plan(kept: Facts, other: Facts): Take {
        if (!sameParking(kept.spot, other.spot)) return NONE
        return Take(
            location = !kept.hasLocation && other.hasLocation,
            detail = other.detailAt > kept.detailAt && (other.hasDetail || kept.hasDetail),
        )
    }
}

object ParkingPromptRules {

    /** 이보다 오래된 주차(늦게 동기화로 받은 기록 등)는 묻지 않는다 */
    const val MAX_AGE_MS = 6 * 60 * 60_000L

    /** 기기 시계 차이 허용 (미래 시각 기록) */
    private const val FUTURE_SLACK_MS = 5 * 60_000L

    /** 기억해 둘 최근 안내 수 */
    const val HISTORY = 20

    /** 이미 안내한 주차 (id, 주차 시각) */
    data class Shown(val id: String, val savedAt: Long)

    /**
     * 이 주차 기록에 대해 '주차 위치를 기록하시겠습니까?'를 띄울지.
     * 자동 감지(차량 신호·블루투스)만, 메모·사진이 이미 있으면 묻지 않음, 같은 id나 같은 주차(10분 안)를 이미 물었으면 묻지 않음.
     */
    fun shouldPrompt(spot: ParkingMerge.Spot, hasDetail: Boolean, now: Long, shown: List<Shown>): Boolean {
        if (spot.source == ParkingMerge.Source.MANUAL) return false
        if (hasDetail) return false
        if (now - spot.savedAt > MAX_AGE_MS || spot.savedAt - now > FUTURE_SLACK_MS) return false
        if (shown.any { it.id == spot.id }) return false
        if (shown.any { abs(it.savedAt - spot.savedAt) <= ParkingMerge.SAME_PARKING_MS }) return false
        return true
    }

    /** 안내 기록에 추가 (최근 [HISTORY]개만) */
    fun remember(shown: List<Shown>, spot: ParkingMerge.Spot): List<Shown> =
        (shown.filter { it.id != spot.id } + Shown(spot.id, spot.savedAt)).takeLast(HISTORY)
}

/** 동기화로 보내는 사진 미리보기의 크기 한도 (서버 레코드 ciphertext 16384자 제한 안에 메모·주소와 함께 들어가야 한다) */
object ParkingPreview {
    /** JPEG 바이트 최대 (Base64로 약 9.4KB, 암호화 후 Base64 약 13.5KB) */
    const val MAX_BYTES = 7_000

    /** 받은 미리보기가 쓸 만한 JPEG인지: 크기 한도 안이고 JPEG 시작·끝 표시가 있음 */
    fun isAcceptable(bytes: ByteArray): Boolean =
        bytes.size in 4..MAX_BYTES &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()

    /** 기기 안 사진 파일 이름으로 쓸 수 있는지 (경로 문자 금지) */
    fun isSafeFileName(name: String?): Boolean = name != null && Regex("[A-Za-z0-9-]{1,80}\\.jpg").matches(name)
}
