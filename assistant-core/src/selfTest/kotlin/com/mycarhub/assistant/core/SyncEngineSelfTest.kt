package com.mycarhub.assistant.core

import java.io.IOException

/**
 * 동기화 전송 계층 자체 검사 (네트워크 없이): JSON, 받은 위치(커서), 엔진 송수신·병합, Supabase 요청 형식·토큰 갱신.
 * 실제 서버 검사는 :assistant-core:syncLive.
 */

/** 메모리 서버: 서버 트리거와 같은 덮어쓰기 규칙 (updated_at, deleted, origin) 튜플이 커야 반영. */
private class MemoryServer(var now: Long = 1_000_000L) {
    val rows = LinkedHashMap<String, RemoteRecord>()
    private var seq = 0L
    var failNextPush: IOException? = null
    var refuseIds = setOf<String>()
    var onPush: (List<RemoteRecord>) -> Unit = {}

    fun remote() = object : SyncRemote {
        override fun push(records: List<RemoteRecord>) {
            failNextPush?.let { failNextPush = null; throw it }
            if (records.any { it.id in refuseIds }) throw SyncHttpException(400, "P0001", "updated_at is in the future")
            onPush(records)
            for (r in records) {
                val key = "${r.kind}:${r.id}"
                val old = rows[key]
                if (old != null && compare(r, old) <= 0) continue
                rows[key] = r.copy(seq = ++seq, serverAt = now)
            }
        }

        override fun pull(afterSeq: Long, limit: Int) =
            PullPage(rows.values.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit), now)
    }

    private fun compare(a: RemoteRecord, b: RemoteRecord): Int =
        compareValuesBy(a, b, { it.updatedAt }, { it.deleted }, { it.origin })
}

/** 기기 안 저장소 흉내: 레코드마다 메타 + 내용, 받은 변경은 [SyncMerge]로. */
private class MemoryDevice(val origin: String) : SyncLocal, SyncProgress {
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
        val key = "$kind:${meta.id}"
        if (!SyncMerge.remoteWins(records[key]?.meta, meta)) return false
        records[key] = LocalRecord(meta, payload)
        return true
    }

    override fun pending() = outbox.pending()
    override fun ack(key: String, updatedAt: Long) = outbox.ack(key, updatedAt)
}

private class FakeHttp(val handler: (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpResponse) : HttpTransport {
    val calls = ArrayList<Triple<String, String, Map<String, String>>>()
    val bodies = ArrayList<String?>()
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        calls += Triple(method, url, headers)
        bodies += body
        return handler(method, url, headers, body)
    }
}

private fun ok(body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(200, body, headers)

fun syncTransportChecks(check: (String, Any?, Any?) -> Unit) {
    jsonChecks(check)
    cursorChecks(check)
    engineChecks(check)
    apiChecks(check)
}

private fun jsonChecks(check: (String, Any?, Any?) -> Unit) {
    val text = """{"a":1,"b":-2.5,"c":"한글 \"따옴표\"\n줄","d":[true,false,null],"e":{"f":12345678901234},"g":"é"}"""
    val v = MiniJson.parse(text) as Map<*, *>
    check("JSON: 정수는 Long", v["a"], 1L)
    check("JSON: 소수", v["b"], -2.5)
    check("JSON: 문자열 이스케이프", v["c"], "한글 \"따옴표\"\n줄")
    check("JSON: 배열", v["d"], listOf(true, false, null))
    check("JSON: 큰 정수", (v["e"] as Map<*, *>)["f"], 12345678901234L)
    check("JSON: \\u 이스케이프", v["g"], "é")
    check("JSON: 다시 쓰고 읽기", MiniJson.parse(MiniJson.stringify(v)), v)
    check("JSON: 제어 문자", MiniJson.stringify("a\u0001b"), "\"a\\u0001b\"")
    check("JSON: 빈 객체·배열", MiniJson.parse(" { \"x\" : [ ] , \"y\" : { } } "), mapOf("x" to emptyList<Any>(), "y" to emptyMap<String, Any>()))
    val bad = listOf("{", "[1,]", "{\"a\" 1}", "\"abc", "01x", "tru", "{} x")
    for (b in bad) check("JSON: 잘못된 입력 거부 '$b'", runCatching { MiniJson.parse(b) }.isFailure, true)
}

private fun rec(seq: Long, serverAt: Long) = RemoteRecord("todo", "t$seq", 1, false, "o", "n", "c", seq, serverAt)

private fun cursorChecks(check: (String, Any?, Any?) -> Unit) {
    val now = 100_000L
    check("커서: 오래된 행은 모두 넘김", PullCursor.advance(0, listOf(rec(1, 1_000), rec(2, 2_000)), now), 2L)
    check("커서: 최근 행에서 멈춤", PullCursor.advance(0, listOf(rec(1, 1_000), rec(2, now - 5_000), rec(3, 1_000)), now), 1L)
    check("커서: 모두 최근이면 그대로", PullCursor.advance(7, listOf(rec(8, now)), now), 7L)
    check("커서: 빈 결과는 그대로", PullCursor.advance(7, emptyList(), now), 7L)
    check("커서: 뒤로 가지 않음", PullCursor.advance(9, listOf(rec(3, 1_000)), now), 9L)
}

private fun engineChecks(check: (String, Any?, Any?) -> Unit) {
    val key = ByteArray(32) { it.toByte() }
    val server = MemoryServer()
    val car = MemoryDevice("car-device")
    val phone = MemoryDevice("phone-device")
    fun sync(d: MemoryDevice, k: ByteArray = key, page: Int = 200) = SyncEngine(d, d, server.remote(), k, pageSize = page).run()
    fun age() { server.now += 60_000 }

    // 휴대폰 → 차량
    phone.put("todo", "t1", 1_000, """{"title":"우유 사기"}""")
    phone.put("parking", "p1", 1_100, """{"lat":37.5}""")
    val r1 = sync(phone)
    check("엔진: 2개 보냄", r1.pushed, 2)
    check("엔진: 보낸 뒤 보낼 목록 비움", phone.outbox.isEmpty(), true)
    check("엔진: 서버에는 암호문만", server.rows.values.none { it.cipherText.contains("우유") }, true)
    age()
    val r2 = sync(car)
    check("엔진: 차량이 2개 받음", r2.applied, 2)
    check("엔진: 차량에서 복호화된 내용", car.payload("todo", "t1"), """{"title":"우유 사기"}""")
    check("엔진: 차량이 커서를 올림", car.lastPulledSeq, 2L)

    // 차량에서 삭제 → 휴대폰
    car.delete("todo", "t1", 2_000)
    sync(car)
    age()
    sync(phone)
    check("엔진: 삭제가 휴대폰에 반영", phone.records["todo:t1"]?.meta?.deleted, true)
    check("엔진: 삭제된 것은 내용 없음", phone.payload("todo", "t1"), null)

    // 오프라인 동안 옛 수정 → 새 수정을 덮지 않음
    car.put("todo", "t2", 5_000, "new")
    sync(car)
    phone.put("todo", "t2", 4_000, "old")
    age()
    sync(phone)
    check("엔진: 옛 수정은 서버에서 무시", car.payload("todo", "t2"), "new")
    check("엔진: 휴대폰도 새 수정으로", phone.payload("todo", "t2"), "new")
    check("엔진: 옛 수정도 보낼 목록에서 빠짐", phone.outbox.isEmpty(), true)

    // 같은 변경을 다시 받아도 결과가 같다
    val beforeAgain = phone.records.toMap()
    phone.lastPulledSeq = 0
    val again = sync(phone)
    check("엔진: 처음부터 다시 받아도 적용 0개", again.applied, 0)
    check("엔진: 다시 받아도 데이터 같음", phone.records.toMap(), beforeAgain)

    // 서버가 수정 시각을 조작 → 거부
    car.put("todo", "t3", 6_000, "secret")
    sync(car)
    val k3 = "todo:t3"
    server.rows[k3] = server.rows[k3]!!.copy(updatedAt = 9_000_000)
    age()
    val tampered = sync(phone)
    check("엔진: 조작된 레코드는 거부", tampered.rejected, 1)
    check("엔진: 조작된 레코드는 적용 안 함", phone.records.containsKey(k3), false)

    // 다른 키(다른 그룹·옛 페어링)로 암호화된 것은 읽지 못함
    val stranger = MemoryDevice("stranger")
    val s = sync(stranger, ByteArray(32) { 7 })
    check("엔진: 다른 키는 모두 거부", s.applied, 0)
    check("엔진: 다른 키 거부 개수", s.rejected, s.pulled)

    // 보내는 중 실패 → 보낼 목록·데이터 유지
    phone.put("todo", "t4", 7_000, "offline")
    server.failNextPush = IOException("network down")
    check("엔진: 네트워크 실패는 예외", runCatching { sync(phone) }.isFailure, true)
    check("엔진: 실패해도 보낼 목록 유지", phone.outbox.pending().map { it.first }, listOf("todo:t4"))
    check("엔진: 실패해도 기기 데이터 유지", phone.payload("todo", "t4"), "offline")
    sync(phone)
    check("엔진: 다시 시도하면 보냄", phone.outbox.isEmpty(), true)

    // 보내는 사이 또 바뀜 → 목록에 남아 다음에 다시 보냄
    phone.put("todo", "t5", 8_000, "v1")
    server.onPush = { phone.put("todo", "t5", 8_500, "v2") }
    sync(phone)
    server.onPush = {}
    check("엔진: 보내는 중 바뀐 것은 남음", phone.outbox.pending().map { it.first }, listOf("todo:t5"))
    sync(phone)
    age()
    sync(car)
    check("엔진: 나중 변경까지 도착", car.payload("todo", "t5"), "v2")

    // 서버가 레코드 하나를 거부(400) → 그것만 빼고 나머지는 보냄
    phone.put("todo", "bad", 9_000, "x")
    phone.put("todo", "good", 9_001, "y")
    server.refuseIds = setOf("bad")
    val refused = sync(phone)
    server.refuseIds = emptySet()
    check("엔진: 거부된 레코드 수", refused.refused, 1)
    check("엔진: 나머지는 보냄", server.rows.containsKey("todo:good"), true)
    check("엔진: 거부된 것은 목록에서 뺌", phone.outbox.isEmpty(), true)

    // 기기가 모르는 레코드(이미 바뀐 옛 주차)는 목록에서 뺌
    phone.outbox.mark("parking:gone", 1)
    val dropped = sync(phone)
    check("엔진: 모르는 레코드는 뺌", dropped.dropped, 1)

    // 최근 행은 커서를 멈추고 다음에 다시 받음 (중복 적용 없음)
    car.put("todo", "t6", 10_000, "fresh")
    sync(car)
    val fresh = sync(phone)
    check("엔진: 막 쓰인 행도 적용", phone.payload("todo", "t6"), "fresh")
    check("엔진: 막 쓰인 행 앞에서 커서 멈춤", phone.lastPulledSeq < server.rows["todo:t6"]!!.seq, true)
    check("엔진: 막 쓰인 행 적용 1개", fresh.applied, 1)
    age()
    check("엔진: 다음에는 다시 받아도 적용 0", sync(phone).applied, 0)
    check("엔진: 그다음 커서 이동", phone.lastPulledSeq, server.rows.values.maxOf { it.seq })

    // 여러 쪽에 걸쳐 받기
    val many = MemoryDevice("many")
    val paged = sync(many, page = 2)
    check("엔진: 쪽 나눠 받기 전부", paged.pulled, server.rows.size)
}

private fun apiChecks(check: (String, Any?, Any?) -> Unit) {
    val authBody = """{"access_token":"A1","token_type":"bearer","expires_in":3600,"refresh_token":"R1","user":{"id":"u-1","is_anonymous":true}}"""
    var refreshCount = 0
    var recordsCalls = 0
    val http = FakeHttp { method, url, headers, body ->
        when {
            url.endsWith("/auth/v1/signup") -> ok(authBody)
            url.contains("/auth/v1/token?grant_type=refresh_token") -> {
                refreshCount++
                if (body?.contains("\"dead\"") == true) HttpResponse(400, """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""", emptyMap())
                else ok("""{"access_token":"A2","expires_in":3600,"refresh_token":"R2","user":{"id":"u-1"}}""")
            }
            url.contains("/rest/v1/sync_records") && method == "GET" -> {
                recordsCalls++
                if (headers["Authorization"] == "Bearer A1") HttpResponse(401, """{"code":"PGRST303","message":"JWT expired"}""", emptyMap())
                else ok(
                    """[{"kind":"todo","record_id":"t1","updated_at":5,"deleted":false,"origin":"o","nonce":"n","ciphertext":"c","seq":3,"server_at":"2026-11-11T03:04:05.123456+00:00"}]""",
                    mapOf("date" to "Wed, 11 Nov 2026 03:05:00 GMT"),
                )
            }
            url.contains("/rest/v1/sync_records") && method == "POST" -> HttpResponse(201, "", emptyMap())
            url.contains("/rest/v1/rpc/create_pairing") -> ok("""[{"pairing_id":"p-1","code":"012345","expires_at":"2026-11-11T03:09:05+00:00"}]""")
            url.contains("/rest/v1/rpc/leave_sync") -> HttpResponse(204, "", emptyMap())
            url.contains("/rest/v1/sync_members") -> ok("""[{"group_id":"g-1","role":"car","confirmed":true}]""")
            else -> HttpResponse(404, """{"message":"nope"}""", emptyMap())
        }
    }
    var now = 0L
    val api = SupabaseApi("https://example.supabase.co/", "sb_publishable_test", http) { now }
    val signed = api.signUpAnonymous()
    check("API: 익명 로그인 사용자", signed.userId, "u-1")
    check("API: 만료 시각", signed.expiresAt, 3_600_000L)
    check("API: 공개 키 헤더", http.calls.last().third["apikey"], "sb_publishable_test")
    check("API: 로그인 전에는 Authorization 없음", http.calls.last().third.containsKey("Authorization"), false)
    check("API: 세션 JSON 왕복", AuthSession.fromJson(signed.toJson()), signed)
    check("API: 깨진 세션 JSON", AuthSession.fromJson("{oops"), null)

    var saved: AuthSession? = signed
    val store = object : SessionStore {
        override fun load() = saved
        override fun save(session: AuthSession) { saved = session }
    }
    val keeper = SessionKeeper(api, store) { now }
    val page = keeper.call { api.pull(it, "g-1", 0, 200) }
    check("API: 401이면 갱신 후 다시 시도", recordsCalls, 2)
    check("API: 갱신 토큰 회전 저장", saved?.refreshToken, "R2")
    check("API: 받은 행 해석", page.records.single().seq, 3L)
    check("API: 서버 시각 해석", page.records.single().serverAt, SupabaseApi.parseTimestamp("2026-11-11T03:04:05.123Z"))
    check("API: 응답 Date 헤더", page.serverNow, SupabaseApi.parseTimestamp("2026-11-11T03:05:00Z"))
    check("API: 받기 URL", http.calls.last().second.contains("group_id=eq.g-1&seq=gt.0&order=seq.asc&limit=200"), true)

    now = saved!!.expiresAt - 30_000
    val before = refreshCount
    keeper.call { api.upsert(it, "g-1", listOf(RemoteRecord("todo", "t1", 5, false, "o", "n", "c"))) }
    check("API: 만료 직전이면 미리 갱신", refreshCount, before + 1)
    val post = http.calls.last()
    check("API: 덮어쓰기 Prefer", post.third["Prefer"], "resolution=merge-duplicates,return=minimal")
    check("API: 덮어쓰기 on_conflict", post.second.endsWith("on_conflict=group_id,kind,record_id"), true)
    val row = (MiniJson.parse(http.bodies.last()!!) as List<*>).single() as Map<*, *>
    check("API: 행 내용", listOf(row["group_id"], row["record_id"], row["updated_at"], row["ciphertext"]), listOf("g-1", "t1", 5L, "c"))
    check("API: seq·author는 보내지 않음", row.containsKey("seq") || row.containsKey("author"), false)

    check("API: 그룹 정보", keeper.call { api.membership(it) }, Membership("g-1", "car", true))
    check("API: 반환 없는 함수", keeper.call { api.rpc(it, "leave_sync", emptyMap()) }, null)
    val err = runCatching { keeper.call { api.rpc(it, "missing", emptyMap()) } }.exceptionOrNull()
    check("API: 오류 코드", (err as? SyncHttpException)?.code, 404)

    saved = saved!!.copy(refreshToken = "dead", expiresAt = 0)
    val lost = runCatching { keeper.call { api.membership(it) } }.exceptionOrNull()
    check("API: 갱신 토큰 무효 → 신원 잃음", lost is SessionLostException, true)
    check("API: 잘못된 URL 거부", runCatching { SupabaseApi("tkhrtz...supabase.co", "k") }.isFailure, true)

    // 페어링 시작: 기존 그룹이 있으면 먼저 나간다
    saved = signed.copy(accessToken = "A2", expiresAt = Long.MAX_VALUE)
    val pairing = SyncPairing(api, keeper)
    val offer = pairing.carStart()
    check("페어링: 코드 그대로 (앞자리 0)", offer.code, "012345")
    check("페어링: 기존 그룹에서 먼저 나감", http.calls.any { it.second.endsWith("/rpc/leave_sync") }, true)
    val createBody = MiniJson.parse(http.bodies[http.calls.indexOfLast { it.second.endsWith("/rpc/create_pairing") }]!!) as Map<*, *>
    check("페어링: 공개키만 보냄", createBody.keys, setOf("car_public_key"))
    check("페어링: 공개키 길이 (서버 200자 제한)", (createBody["car_public_key"] as String).length <= 200, true)
}
