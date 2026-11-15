package com.mycarhub.assistant.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Drive To-Do 동기화 서버(Supabase) 통신. 설계: docs/DRIVE_SYNC.md, 서버: supabase/migrations/20261110000000_drive_sync.sql.
 * 순수 Kotlin/JVM이라 Android 앱과 PC 검사(selfTest·syncLive)가 같은 코드를 쓴다.
 *
 * - 인증: 기기마다 익명 로그인 1회 (액세스 토큰 1시간 + 갱신 토큰). 공개(publishable) 키만 쓰고 service_role 키는 쓰지 않는다.
 * - 서버로 가는 레코드 내용은 이미 기기에서 암호화된 것이다 ([SyncCrypto]).
 */

class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>)

/** HTTP 한 번 보내기. 테스트에서 바꿔 끼울 수 있게 분리 ([UrlConnectionTransport]가 실제 구현). */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

class UrlConnectionTransport(private val timeoutMs: Int = 15_000) : HttpTransport {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }
            return HttpResponse(code, text, h)
        } finally {
            c.disconnect()
        }
    }
}

/** 서버가 오류로 답함. [code]는 HTTP 상태, [serverCode]는 Supabase/Postgres 오류 코드(있으면). */
class SyncHttpException(val code: Int, val serverCode: String?, message: String) : IOException(message) {
    val isAuth get() = code == 401
    val isForbidden get() = code == 403 || serverCode == "42501"
}

/** 갱신 토큰이 더는 통하지 않음: 이 기기의 서버 신원을 잃었다 (다시 페어링해야 함). */
class SessionLostException(message: String) : IOException(message)

data class AuthSession(val userId: String, val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    fun toJson(): String = MiniJson.stringify(mapOf("uid" to userId, "at" to accessToken, "rt" to refreshToken, "exp" to expiresAt))

    companion object {
        fun fromJson(json: String): AuthSession? = try {
            val m = MiniJson.parse(json) as Map<*, *>
            AuthSession(m.str("uid")!!, m.str("at")!!, m.str("rt")!!, m.long("exp")!!)
        } catch (e: Exception) {
            null
        }
    }
}

/** 서버에 저장된 레코드 한 줄 (내용은 암호문). [seq]·[serverAt]은 받은 것에만 있다. */
data class RemoteRecord(
    val kind: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val origin: String,
    val nonce: String,
    val cipherText: String,
    val seq: Long = 0,
    val serverAt: Long = 0,
) {
    val meta get() = SyncMeta(id, updatedAt, deleted, origin)
}

data class PullPage(val records: List<RemoteRecord>, val serverNow: Long)

data class Membership(val groupId: String, val role: String, val confirmed: Boolean)

class SupabaseApi(
    baseUrl: String,
    private val apiKey: String,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = baseUrl.trimEnd('/')

    init {
        require(base.startsWith("https://") || base.startsWith("http://")) { "bad Supabase URL" }
        require(apiKey.isNotBlank()) { "missing key" }
    }

    // region 인증

    fun signUpAnonymous(): AuthSession = session(call("POST", "$base/auth/v1/signup", null, mapOf("data" to emptyMap<String, Any>())))

    fun refresh(refreshToken: String): AuthSession = try {
        session(call("POST", "$base/auth/v1/token?grant_type=refresh_token", null, mapOf("refresh_token" to refreshToken)))
    } catch (e: SyncHttpException) {
        // 갱신 토큰이 무효·만료·재사용됨 → 이 기기의 익명 사용자에 다시 들어갈 방법이 없다
        if (e.code == 400 || e.code == 401 || e.code == 403) throw SessionLostException("refresh rejected: ${e.serverCode ?: e.code}")
        throw e
    }

    private fun session(r: Any?): AuthSession {
        val m = r as? Map<*, *> ?: throw IOException("bad auth response")
        val user = m["user"] as? Map<*, *>
        val expiresIn = m.long("expires_in") ?: 3600
        return AuthSession(
            userId = user?.str("id") ?: throw IOException("no user id"),
            accessToken = m.str("access_token") ?: throw IOException("no access token"),
            refreshToken = m.str("refresh_token") ?: throw IOException("no refresh token"),
            expiresAt = clock() + expiresIn * 1000,
        )
    }

    // endregion

    // region 페어링·그룹 (서버 함수)

    fun rpc(s: AuthSession, name: String, args: Map<String, Any?>): Any? = call("POST", "$base/rest/v1/rpc/$name", s, args)

    /** 이 기기의 그룹 정보 (그룹이 없으면 null) */
    fun membership(s: AuthSession): Membership? {
        val rows = call("GET", "$base/rest/v1/sync_members?select=group_id,role,confirmed&user_id=eq.${s.userId}", s, null) as? List<*>
        val m = rows?.firstOrNull() as? Map<*, *> ?: return null
        return Membership(m.str("group_id") ?: return null, m.str("role") ?: "", m.bool("confirmed") ?: false)
    }

    // endregion

    // region 레코드

    /** 레코드 덮어쓰기 (같은 키는 서버 규칙에 따라 더 새 것만 반영, 옛 것은 조용히 무시). */
    fun upsert(s: AuthSession, groupId: String, records: List<RemoteRecord>) {
        if (records.isEmpty()) return
        val rows = records.map {
            mapOf(
                "group_id" to groupId, "kind" to it.kind, "record_id" to it.id, "updated_at" to it.updatedAt,
                "deleted" to it.deleted, "origin" to it.origin, "nonce" to it.nonce, "ciphertext" to it.cipherText,
            )
        }
        call(
            "POST", "$base/rest/v1/sync_records?on_conflict=group_id,kind,record_id", s, rows,
            extra = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    /** [afterSeq] 다음 변경부터 순서대로. [PullPage.serverNow]는 서버 응답 시각 (기기 시계와 무관하게 '최근 행'을 가리는 데 쓴다). */
    fun pull(s: AuthSession, groupId: String, afterSeq: Long, limit: Int): PullPage {
        var date = 0L
        val rows = call(
            "GET",
            "$base/rest/v1/sync_records?select=kind,record_id,updated_at,deleted,origin,nonce,ciphertext,seq,server_at" +
                "&group_id=eq.$groupId&seq=gt.$afterSeq&order=seq.asc&limit=$limit",
            s, null, onHeaders = { h -> date = h["date"]?.let(::parseHttpDate) ?: 0L },
        ) as? List<*> ?: emptyList<Any?>()
        val records = rows.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            RemoteRecord(
                kind = m.str("kind") ?: return@mapNotNull null,
                id = m.str("record_id") ?: return@mapNotNull null,
                updatedAt = m.long("updated_at") ?: return@mapNotNull null,
                deleted = m.bool("deleted") ?: false,
                origin = m.str("origin") ?: "",
                nonce = m.str("nonce") ?: "",
                cipherText = m.str("ciphertext") ?: "",
                seq = m.long("seq") ?: return@mapNotNull null,
                serverAt = m.str("server_at")?.let(::parseTimestamp) ?: 0L,
            )
        }
        return PullPage(records, if (date > 0) date else clock())
    }

    // endregion

    private fun call(
        method: String,
        url: String,
        s: AuthSession?,
        body: Any?,
        extra: Map<String, String> = emptyMap(),
        onHeaders: (Map<String, String>) -> Unit = {},
    ): Any? {
        val headers = linkedMapOf("apikey" to apiKey, "Accept" to "application/json")
        if (s != null) headers["Authorization"] = "Bearer ${s.accessToken}"
        if (body != null) headers["Content-Type"] = "application/json"
        headers.putAll(extra)
        val r = http.send(method, url, headers, body?.let(MiniJson::stringify))
        if (r.code !in 200..299) {
            val err = try {
                MiniJson.parse(r.body) as? Map<*, *>
            } catch (e: Exception) {
                null
            }
            val serverCode = err?.let { it.str("error_code") ?: it.str("code") ?: it.str("error") }
            val message = err?.let { it.str("msg") ?: it.str("message") ?: it.str("error_description") } ?: "HTTP ${r.code}"
            throw SyncHttpException(r.code, serverCode, message)
        }
        onHeaders(r.headers)
        if (r.body.isBlank()) return null
        return try {
            MiniJson.parse(r.body)
        } catch (e: MiniJson.ParseException) {
            throw IOException("bad JSON from server")
        }
    }

    companion object {
        fun parseTimestamp(s: String): Long? = try {
            OffsetDateTime.parse(s.replace(' ', 'T')).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }

        fun parseHttpDate(s: String): Long? = try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}

/** 이 기기의 로그인 정보 보관 (Android: Keystore로 암호화한 prefs). */
interface SessionStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
}

/**
 * 토큰 관리: 만료 1분 전이면 미리 갱신하고, 401이면 한 번 갱신한 뒤 다시 시도한다.
 * 갱신 토큰은 쓸 때마다 바뀌므로 받은 즉시 저장한다. 한 기기 안에서는 한 스레드에서만 부른다.
 */
class SessionKeeper(private val api: SupabaseApi, private val store: SessionStore, private val clock: () -> Long = System::currentTimeMillis) {

    /** 로그인 정보가 없으면 익명 로그인 (페어링 시작 때만) */
    fun ensure(): AuthSession = store.load() ?: api.signUpAnonymous().also(store::save)

    fun <T> call(block: (AuthSession) -> T): T {
        var s = store.load() ?: throw SessionLostException("no session")
        if (s.expiresAt - clock() < 60_000) s = renew(s)
        return try {
            block(s)
        } catch (e: SyncHttpException) {
            if (!e.isAuth) throw e
            block(renew(s))
        }
    }

    private fun renew(s: AuthSession): AuthSession = api.refresh(s.refreshToken).also(store::save)
}
