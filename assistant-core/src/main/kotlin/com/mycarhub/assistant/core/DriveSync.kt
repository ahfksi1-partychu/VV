package com.mycarhub.assistant.core

/**
 * 차량 ↔ 휴대폰 Drive To-Do 동기화의 순수 규칙 (서버·네트워크와 무관, PC selfTest로 검증).
 * 설계: docs/DRIVE_SYNC.md. 전송(서버 연결)은 아직 없다.
 *
 * - 레코드마다 고유 id와 수정 시각이 있고, 같은 레코드는 덮어쓰기만 한다 (몇 번 받아도 결과가 같음).
 * - 삭제는 지우지 않고 '삭제됨' 표시(tombstone)를 남겨 다른 기기의 옛 데이터가 되살아나지 않게 한다.
 */
data class SyncMeta(val id: String, val updatedAt: Long, val deleted: Boolean, val origin: String)

object SyncMerge {

    /**
     * 받은 변경을 적용할지: 더 나중에 바뀐 쪽이 이긴다. 같은 시각이면 삭제가 이기고 (되살아나지 않게),
     * 그래도 같으면 [SyncMeta.origin]이 큰 쪽 (양쪽 기기가 같은 결론을 낸다).
     */
    fun remoteWins(local: SyncMeta?, remote: SyncMeta): Boolean {
        if (local == null) return true
        if (remote.updatedAt != local.updatedAt) return remote.updatedAt > local.updatedAt
        if (remote.deleted != local.deleted) return remote.deleted
        return remote.origin > local.origin
    }
}

/**
 * 마지막 주차 위치 병합. 주차 하나하나가 레코드(id)이고, 기기마다 '지금 주차 위치' 하나를 고른다.
 * 더 최근 주차가 이긴다. 단 차량 신호와 휴대폰 블루투스가 같은 주차를 [SAME_PARKING_MS] 안에 각각 저장했으면
 * 더 정확한 차량 신호 쪽을 쓴다 (휴대폰 위치는 차에서 걸어 나온 뒤일 수 있음). 직접 저장(MANUAL)은 일반 규칙을 따른다.
 */
object ParkingMerge {

    enum class Source { CAR_SIGNAL, PHONE_BLUETOOTH, MANUAL }

    data class Spot(val id: String, val savedAt: Long, val source: Source)

    const val SAME_PARKING_MS = 10 * 60_000L

    fun remoteWins(local: Spot?, remote: Spot): Boolean {
        if (local == null) return true
        if (local.id == remote.id) return false
        val gap = remote.savedAt - local.savedAt
        if (kotlin.math.abs(gap) <= SAME_PARKING_MS) {
            if (remote.source == Source.CAR_SIGNAL && local.source == Source.PHONE_BLUETOOTH) return true
            if (local.source == Source.CAR_SIGNAL && remote.source == Source.PHONE_BLUETOOTH) return false
        }
        if (gap != 0L) return gap > 0
        return remote.id > local.id
    }
}

/**
 * 보낼 목록 (기기 안에 저장). 키는 "종류:id", 값은 그 레코드의 수정 시각.
 * 같은 레코드를 여러 번 바꿔도 하나만 남고, 서버가 받았다고 확인한 시각 이하일 때만 지운다
 * (보내는 사이 또 바뀌었으면 남겨서 다음에 다시 보낸다).
 */
class SyncOutbox(entries: Map<String, Long> = emptyMap()) {

    private val pending = entries.toMutableMap()

    fun mark(key: String, updatedAt: Long) {
        val current = pending[key]
        if (current == null || updatedAt > current) pending[key] = updatedAt
    }

    /** 오래된 변경부터 */
    fun pending(): List<Pair<String, Long>> = pending.entries.sortedBy { it.value }.map { it.key to it.value }

    fun ack(key: String, updatedAt: Long) {
        val current = pending[key] ?: return
        if (current <= updatedAt) pending.remove(key)
    }

    fun isEmpty() = pending.isEmpty()

    fun toMap(): Map<String, Long> = pending.toMap()

    companion object {
        fun key(kind: String, id: String) = "$kind:$id"
    }
}

/** 실패 후 다시 시도할 간격: 30초부터 두 배씩, 최대 30분. [jitter] 0..1 (동시에 몰리지 않게). */
object SyncBackoff {
    private const val BASE_MS = 30_000L
    private const val MAX_MS = 30 * 60_000L

    fun delayMs(attempt: Int, jitter: Double = 0.0): Long {
        val exp = BASE_MS shl attempt.coerceIn(0, 6)
        val capped = exp.coerceAtMost(MAX_MS)
        return (capped * (1.0 + 0.2 * jitter.coerceIn(0.0, 1.0))).toLong().coerceAtMost(MAX_MS)
    }
}
