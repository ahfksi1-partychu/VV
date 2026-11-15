package com.mycarhub.assistant.core

import java.io.IOException
import java.security.KeyPair

/** 이 기기에 있는 레코드 하나의 현재 상태. 지운 레코드는 [payload]가 null. */
data class LocalRecord(val meta: SyncMeta, val payload: String?)

/** 기기 안의 데이터 (Android: 주차·할 일·차에 둔 물건 저장소). */
interface SyncLocal {
    /** 지금 상태. 이 기기가 모르는 레코드(이미 다른 것으로 바뀐 옛 주차 등)는 null → 보낼 목록에서 뺀다. */
    fun snapshot(kind: String, id: String): LocalRecord?

    /** 받은 변경 적용 (병합 규칙은 저장소가 따른다). 적용했으면 true. 받은 변경은 다시 보낼 목록에 넣지 않는다. */
    fun applyRemote(kind: String, meta: SyncMeta, payload: String?): Boolean
}

/** 보낼 목록·받은 위치 보관 (Android: prefs). 앱의 다른 스레드가 동시에 [SyncOutbox.mark] 해도 되게 [ack]는 저장된 목록에 바로 반영한다. */
interface SyncProgress {
    fun pending(): List<Pair<String, Long>>
    fun ack(key: String, updatedAt: Long)
    var lastPulledSeq: Long
}

/** 서버 쪽 (Android·PC: [SupabaseRemote], 테스트: 메모리 서버). 그룹은 이미 정해져 있다. */
interface SyncRemote {
    fun push(records: List<RemoteRecord>)
    fun pull(afterSeq: Long, limit: Int): PullPage
}

class SupabaseRemote(private val api: SupabaseApi, private val keeper: SessionKeeper, private val groupId: String) : SyncRemote {
    override fun push(records: List<RemoteRecord>) = keeper.call { api.upsert(it, groupId, records) }
    override fun pull(afterSeq: Long, limit: Int) = keeper.call { api.pull(it, groupId, afterSeq, limit) }
}

/**
 * 받은 위치(커서) 올리기: 순서대로 보며, 서버 시각으로 [holdbackMs]보다 최근에 쓰인 행에서 멈춘다.
 * 번호(seq)는 트랜잭션이 끝나는 순서와 다를 수 있어서, 막 쓰인 행 앞에 아직 안 보이는 행이 있을 수 있기 때문이다.
 * 멈춘 뒤의 행은 다음에 다시 받는다 (다시 적용해도 결과가 같다).
 */
object PullCursor {
    const val HOLDBACK_MS = 10_000L

    fun advance(cursor: Long, records: List<RemoteRecord>, serverNow: Long, holdbackMs: Long = HOLDBACK_MS): Long {
        var c = cursor
        for (r in records.sortedBy { it.seq }) {
            if (r.serverAt > serverNow - holdbackMs) break
            if (r.seq > c) c = r.seq
        }
        return c
    }
}

/**
 * 한 번의 동기화: 보낼 목록을 암호화해 보내고(서버가 받은 뒤에만 목록에서 지움), 새 변경을 받아 복호화·적용한다.
 * 실패하면 예외를 그대로 던진다 (보낼 목록·기기 데이터는 그대로 → 나중에 다시 시도).
 */
class SyncEngine(
    private val local: SyncLocal,
    private val progress: SyncProgress,
    private val remote: SyncRemote,
    private val dataKey: ByteArray,
    private val batchSize: Int = 50,
    private val pageSize: Int = 200,
) {
    data class Result(
        val pushed: Int,
        val dropped: Int,
        val pulled: Int,
        val applied: Int,
        /** 복호화 실패(키가 다르거나 서버에서 바뀐 레코드) → 적용하지 않음 */
        val rejected: Int,
        /** 서버가 거부한 레코드 (예: 기기 시계가 하루 넘게 앞섬) → 보낼 목록에서 뺐다 */
        val refused: Int,
    )

    fun run(): Result {
        val (pushed, dropped, refused) = push()
        val (pulled, applied, rejected) = pull()
        return Result(pushed, dropped, pulled, applied, rejected, refused)
    }

    private fun push(): Triple<Int, Int, Int> {
        var pushed = 0
        var dropped = 0
        var refused = 0
        for (chunk in progress.pending().chunked(batchSize)) {
            val batch = ArrayList<Pair<String, RemoteRecord>>()
            for ((key, _) in chunk) {
                val sep = key.indexOf(':')
                if (sep <= 0) {
                    progress.ack(key, Long.MAX_VALUE)
                    dropped++
                    continue
                }
                val kind = key.substring(0, sep)
                val id = key.substring(sep + 1)
                val snap = local.snapshot(kind, id)
                if (snap == null) {
                    progress.ack(key, Long.MAX_VALUE)
                    dropped++
                    continue
                }
                val plain = (if (snap.meta.deleted) "" else snap.payload ?: "").toByteArray(Charsets.UTF_8)
                val sealed = SyncCrypto.seal(dataKey, snap.meta, kind, plain)
                batch += key to RemoteRecord(kind, id, snap.meta.updatedAt, snap.meta.deleted, snap.meta.origin, sealed.nonce, sealed.cipherText)
            }
            if (batch.isEmpty()) continue
            try {
                remote.push(batch.map { it.second })
                batch.forEach { (key, r) -> progress.ack(key, r.updatedAt) }
                pushed += batch.size
            } catch (e: SyncHttpException) {
                // 인증·권한·서버 오류는 위로 (다시 시도). 레코드 하나가 거부된 경우(400)만 하나씩 보내 그 레코드를 가려낸다.
                if (e.code != 400) throw e
                for ((key, r) in batch) {
                    try {
                        remote.push(listOf(r))
                        pushed++
                    } catch (one: SyncHttpException) {
                        if (one.code != 400) throw one
                        refused++
                    }
                    progress.ack(key, r.updatedAt)
                }
            }
        }
        return Triple(pushed, dropped, refused)
    }

    private fun pull(): Triple<Int, Int, Int> {
        var pulled = 0
        var applied = 0
        var rejected = 0
        while (true) {
            val cursor = progress.lastPulledSeq
            val page = remote.pull(cursor, pageSize)
            for (r in page.records) {
                pulled++
                val meta = r.meta
                val plain = SyncCrypto.open(dataKey, meta, r.kind, SyncCrypto.Sealed(r.nonce, r.cipherText))
                if (plain == null) {
                    rejected++
                    continue
                }
                val payload = if (meta.deleted) null else plain.toString(Charsets.UTF_8)
                if (local.applyRemote(r.kind, meta, payload)) applied++
            }
            val next = PullCursor.advance(cursor, page.records, page.serverNow)
            if (next > cursor) progress.lastPulledSeq = next
            if (page.records.size < pageSize || next == cursor) break
        }
        return Triple(pulled, applied, rejected)
    }
}

/**
 * 페어링 단계 (화면은 앱이 그린다). 모든 호출은 네트워크를 쓰므로 백그라운드 스레드에서.
 * 설계 §3: 차량이 코드를 만들고 → 휴대폰이 코드를 넣고 → 양쪽 화면의 확인 번호가 같으면 차량에서 승인.
 */
class SyncPairing(private val api: SupabaseApi, private val keeper: SessionKeeper) {

    class CarOffer(val pairingId: String, val code: String, val expiresAt: Long, val keyPair: KeyPair, val carPublic: String)

    class Derived(val keys: SyncCrypto.PairingKeys, val peerPublic: String)

    class PhoneClaim(val keys: SyncCrypto.PairingKeys, val pairingId: String)

    /** 기기가 이미 다른 그룹에 있으면 먼저 나간다 (차량이 나가면 그 그룹의 서버 레코드가 모두 지워진다). */
    fun leave() {
        keeper.call { api.rpc(it, "leave_sync", emptyMap()) }
    }

    fun membership(): Membership? = keeper.call { api.membership(it) }

    // 차량

    fun carStart(): CarOffer {
        keeper.ensure()
        val existing = membership()
        // 다시 페어링하면 새 데이터 키를 쓰므로, 옛 키로 암호화된 서버 레코드가 남지 않게 그룹을 새로 만든다
        if (existing != null) leave()
        val pair = SyncCrypto.newKeyPair()
        val pub = SyncCrypto.publicKey(pair)
        val rows = keeper.call { api.rpc(it, "create_pairing", mapOf("car_public_key" to pub)) } as? List<*>
        val m = rows?.firstOrNull() as? Map<*, *> ?: throw IOException("no pairing created")
        return CarOffer(
            pairingId = m.str("pairing_id") ?: throw IOException("no pairing id"),
            code = m.str("code") ?: throw IOException("no code"),
            expiresAt = m.str("expires_at")?.let(SupabaseApi::parseTimestamp) ?: (System.currentTimeMillis() + 5 * 60_000),
            keyPair = pair,
            carPublic = pub,
        )
    }

    /** 휴대폰이 코드를 넣었으면 확인 번호를 계산한다. 아직이면 null. 제안이 사라졌으면(만료·취소) 예외. */
    fun carPoll(offer: CarOffer): Derived? {
        val rows = keeper.call { api.rpc(it, "pairing_status", mapOf("pairing_id" to offer.pairingId)) } as? List<*>
        val m = rows?.firstOrNull() as? Map<*, *> ?: throw PairingGoneException()
        if (m.bool("claimed") != true) return null
        val phonePublic = m.str("phone_public_key") ?: return null
        val keys = SyncCrypto.derive(offer.keyPair.private, phonePublic, offer.code, offer.carPublic, phonePublic)
        return Derived(keys, phonePublic)
    }

    fun carConfirm(offer: CarOffer): Membership {
        keeper.call { api.rpc(it, "confirm_pairing", mapOf("pairing_id" to offer.pairingId)) }
        return membership() ?: throw IOException("no group after confirm")
    }

    fun carReject(offer: CarOffer) {
        keeper.call { api.rpc(it, "reject_pairing", mapOf("pairing_id" to offer.pairingId)) }
    }

    // 휴대폰

    /** 코드가 틀렸거나 만료됐으면 null. */
    fun phoneClaim(code: String): PhoneClaim? {
        keeper.ensure()
        val existing = membership()
        if (existing != null && existing.confirmed) leave()
        val pair = SyncCrypto.newKeyPair()
        val pub = SyncCrypto.publicKey(pair)
        val rows = keeper.call { api.rpc(it, "claim_pairing", mapOf("code" to code, "phone_public_key" to pub)) } as? List<*>
        val m = rows?.firstOrNull() as? Map<*, *> ?: return null
        val carPublic = m.str("car_public_key") ?: return null
        val keys = SyncCrypto.derive(pair.private, carPublic, code, carPublic, pub)
        return PhoneClaim(keys, m.str("pairing_id") ?: "")
    }

    /** 차량에서 승인했으면 그룹 정보, 아직이면 null. 거절·취소되어 그룹에서 빠졌으면 예외. */
    fun phonePoll(): Membership? {
        val m = membership() ?: throw PairingGoneException()
        return m.takeIf { it.confirmed }
    }

    class PairingGoneException : IOException("pairing gone")
}
