package com.mycarhub.assistant.core

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/**
 * 실제 Supabase 서버 검사: gradlew --offline :assistant-core:syncLive
 * local.properties 의 supabase.url / supabase.anonKey 를 쓴다 (키는 출력하지 않음).
 *
 * 익명 사용자 3명(차량·휴대폰·제3자)을 만들어 앱과 같은 코드로 페어링 → 암호화 송수신 → 삭제·옛 수정 → 접근 차단 → 해제까지 확인한다.
 * 마지막에 차량이 동기화를 해제해 그룹과 서버 레코드는 지워진다. 익명 사용자 3명은 Supabase Authentication 목록에 남는다.
 */
private var liveFailures = 0
private var liveChecks = 0

private fun expect(name: String, actual: Any?, expected: Any?) {
    liveChecks++
    if (actual == expected) println("  ok    $name") else {
        liveFailures++
        println("  FAIL  $name\n        expected: $expected\n        actual:   $actual")
    }
}

private class MemSession : SessionStore {
    var s: AuthSession? = null
    override fun load() = s
    override fun save(session: AuthSession) { s = session }
}

fun main(args: Array<String>) {
    val propsFile = File(args.firstOrNull() ?: "local.properties")
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    val url = props.getProperty("supabase.url")?.trim().orEmpty()
    val key = props.getProperty("supabase.anonKey")?.trim().orEmpty()
    if (url.isEmpty() || key.isEmpty() || url.contains("...")) {
        println("syncLive: supabase.url / supabase.anonKey 가 비었거나 잘려 있습니다 (${propsFile.path})")
        exitProcess(2)
    }
    val api = SupabaseApi(url, key)
    val carStore = MemSession()
    val phoneStore = MemSession()
    val car = SessionKeeper(api, carStore)
    val phone = SessionKeeper(api, phoneStore)
    val carPairing = SyncPairing(api, car)
    val phonePairing = SyncPairing(api, phone)
    var groupId: String? = null

    try {
        println("1. 페어링")
        val offer = carPairing.carStart()
        expect("코드 6자리", Regex("[0-9]{6}").matches(offer.code), true)
        expect("차량·휴대폰은 다른 사용자", phone.ensure().userId != carStore.s!!.userId, true)
        expect("차량: 아직 코드 입력 전", carPairing.carPoll(offer), null)
        val wrong = if (offer.code == "000000") "000001" else "000000"
        expect("틀린 코드 → 거부", phonePairing.phoneClaim(wrong), null)
        val claim = phonePairing.phoneClaim(offer.code)
        expect("맞는 코드 → 차량 공개키 받음", claim != null, true)
        val derived = carPairing.carPoll(offer)
        expect("차량: 휴대폰 공개키 받음", derived != null, true)
        expect("두 화면 확인 번호 같음", derived?.keys?.verifyCode, claim?.keys?.verifyCode)
        expect("데이터 키 같음", derived?.keys?.dataKey?.contentEquals(claim!!.keys.dataKey), true)
        expect("휴대폰: 승인 전에는 대기", phonePairing.phonePoll(), null)
        val carGroup = carPairing.membership()!!.groupId
        val early = runCatching { phone.call { api.upsert(it, carGroup, listOf(RemoteRecord("todo", "early", System.currentTimeMillis(), false, "p", "n", "c"))) } }
        expect("휴대폰: 승인 전 쓰기 차단(403)", (early.exceptionOrNull() as? SyncHttpException)?.isForbidden, true)
        val m = carPairing.carConfirm(offer)
        groupId = m.groupId
        val pm = phonePairing.phonePoll()
        expect("휴대폰: 승인됨", pm?.confirmed, true)
        expect("같은 그룹", pm?.groupId, m.groupId)

        println("2. 암호화 송수신")
        val dataKey = claim!!.keys.dataKey
        val phoneDev = LiveDevice("phone-dev")
        val carDev = LiveDevice("car-dev")
        val phoneEngine = SyncEngine(phoneDev, phoneDev, SupabaseRemote(api, phone, m.groupId), dataKey)
        val carEngine = SyncEngine(carDev, carDev, SupabaseRemote(api, car, m.groupId), derived!!.keys.dataKey)
        val t0 = System.currentTimeMillis()
        phoneDev.put("todo", "live-todo", t0, """{"title":"우유 사기"}""")
        phoneDev.put("parking", "live-spot", t0, """{"lat":37.5665,"lng":126.978}""")
        phoneDev.put("car_item", "live-item", t0, """{"name":"노트북"}""")
        expect("휴대폰: 3개 보냄", phoneEngine.run().pushed, 3)
        expect("휴대폰: 보낼 목록 비움", phoneDev.outbox.isEmpty(), true)
        val got = carEngine.run()
        expect("차량: 3개 적용", got.applied, 3)
        expect("차량: 복호화 실패 0", got.rejected, 0)
        expect("차량: 할 일 내용", carDev.payload("todo", "live-todo"), """{"title":"우유 사기"}""")
        expect("차량: 물건 내용", carDev.payload("car_item", "live-item"), """{"name":"노트북"}""")

        println("3. 삭제·옛 수정·중복")
        carDev.delete("todo", "live-todo", t0 + 1_000)
        carDev.put("parking", "live-spot", t0 + 2_000, """{"lat":37.0,"lng":127.0}""")
        carEngine.run()
        phoneDev.put("parking", "live-spot", t0 + 500, """{"lat":1.0,"lng":1.0}""") // 오프라인 동안의 옛 수정
        phoneEngine.run()
        expect("휴대폰: 삭제 반영", phoneDev.records["todo:live-todo"]?.meta?.deleted, true)
        expect("휴대폰: 새 수정이 이김", phoneDev.payload("parking", "live-spot"), """{"lat":37.0,"lng":127.0}""")
        val carAgain = carEngine.run()
        expect("차량: 옛 수정은 서버에서 무시됨", carDev.payload("parking", "live-spot"), """{"lat":37.0,"lng":127.0}""")
        expect("차량: 다시 받아도 새로 적용 0", carAgain.applied, 0)
        val rows = car.call { api.pull(it, m.groupId, 0, 100) }.records
        expect("서버: 레코드 3줄 (중복 없음)", rows.size, 3)
        expect("서버: 평문 없음", rows.none { it.cipherText.contains("우유") || it.cipherText.contains("노트북") }, true)

        println("4. 다른 사용자 차단")
        val strangerStore = MemSession()
        val stranger = SessionKeeper(api, strangerStore)
        stranger.ensure()
        expect("제3자: 레코드 0줄", stranger.call { api.pull(it, m.groupId, 0, 100) }.records.size, 0)
        val write = runCatching { stranger.call { api.upsert(it, m.groupId, listOf(RemoteRecord("todo", "x", t0, false, "s", "n", "c"))) } }
        expect("제3자: 쓰기 차단(403)", (write.exceptionOrNull() as? SyncHttpException)?.isForbidden, true)
        expect("제3자: 그룹 정보 없음", stranger.call { api.membership(it) }, null)
        val reuse = SyncPairing(api, stranger).phoneClaim(offer.code)
        expect("제3자: 쓴 코드 재사용 불가", reuse, null)

        println("5. 토큰 갱신")
        phoneStore.s = phoneStore.s!!.copy(expiresAt = 0)
        expect("만료된 토큰 → 갱신 후 성공", phone.call { api.membership(it) }?.groupId, m.groupId)
        expect("갱신 토큰 회전", phoneStore.s!!.expiresAt > System.currentTimeMillis(), true)

        println("6. 해제")
        carPairing.leave()
        groupId = null
        expect("차량 해제 → 휴대폰 그룹 없음", phone.call { api.membership(it) }, null)
        expect("차량 해제 → 서버 레코드 없음", phone.call { api.pull(it, m.groupId, 0, 100) }.records.size, 0)
    } catch (e: Exception) {
        liveFailures++
        println("  FAIL  예외: ${e.javaClass.simpleName}: ${e.message}" + ((e as? SyncHttpException)?.let { " (HTTP ${it.code} ${it.serverCode})" } ?: ""))
    } finally {
        if (groupId != null) runCatching { carPairing.leave() }
    }
    println("syncLive: ${liveChecks - liveFailures}/$liveChecks passed" + if (liveFailures > 0) " — FAILED" else "")
    if (liveFailures > 0) exitProcess(1)
}

private class LiveDevice(val origin: String) : SyncLocal, SyncProgress {
    val records = LinkedHashMap<String, LocalRecord>()
    val outbox = SyncOutbox()
    override var lastPulledSeq = 0L

    fun put(kind: String, id: String, at: Long, payload: String) {
        records["$kind:$id"] = LocalRecord(SyncMeta(id, at, false, origin), payload)
        outbox.mark(SyncOutbox.key(kind, id), at)
    }

    fun delete(kind: String, id: String, at: Long) {
        records["$kind:$id"] = LocalRecord(SyncMeta(id, at, true, origin), null)
        outbox.mark(SyncOutbox.key(kind, id), at)
    }

    fun payload(kind: String, id: String) = records["$kind:$id"]?.takeIf { !it.meta.deleted }?.payload

    override fun snapshot(kind: String, id: String) = records["$kind:$id"]

    override fun applyRemote(kind: String, meta: SyncMeta, payload: String?): Boolean {
        val k = "$kind:${meta.id}"
        if (!SyncMerge.remoteWins(records[k]?.meta, meta)) return false
        records[k] = LocalRecord(meta, payload)
        return true
    }

    override fun pending() = outbox.pending()
    override fun ack(key: String, updatedAt: Long) = outbox.ack(key, updatedAt)
}
